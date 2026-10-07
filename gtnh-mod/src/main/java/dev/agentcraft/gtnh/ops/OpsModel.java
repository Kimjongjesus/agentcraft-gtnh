package dev.agentcraft.gtnh.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.edit.Json;

/**
 * Card 5b, server side: the ops state built from the adapter's {@code ops.*} messages, following
 * the same rules as the adapter's reference receiver ({@code hermes_adapter/ops_mirror.py}):
 * {@code ops.snapshot} replaces everything, {@code ops.<kind>.upsert} replaces one whole entity by
 * id, {@code ops.remove} drops one, unknown {@code ops.*} types are ignored. On top of that it is
 * bounded: per kind at most the spec's cap (or the lower cap the snapshot announces); over a cap the
 * least important entry goes first (the same order the adapter uses to decide who survives).
 * Pure Java: the plain-Java checks drive it with the adapter's own messages.
 */
public final class OpsModel {

    private final Map<String, Map<String, Object>> byKind = new LinkedHashMap<>();
    private final int[] caps = { OpsData.MAX_SERVICES, OpsData.MAX_JOBS, OpsData.MAX_USAGE, OpsData.MAX_ALERTS, OpsData.MAX_SOURCES };
    /** an ops.snapshot arrived on the current connection */
    private boolean live;
    private boolean dirty;
    public long messages, ignored, evicted;

    public OpsModel() {
        for (String k : OpsData.KINDS) byKind.put(k, new LinkedHashMap<>());
    }

    public boolean live() {
        return live;
    }

    /** True once since the last call if anything visible changed. */
    public boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public int count(String kind) {
        Map<String, Object> m = byKind.get(kind);
        return m == null ? 0 : m.size();
    }

    /** The adapter link dropped: the panels say "ops feed offline" until the next ops.snapshot. */
    public void disconnected() {
        if (live) dirty = true;
        live = false;
    }

    /**
     * One adapter message (already parsed). Returns false if it is not an {@code ops.*} message.
     * Malformed entities are skipped, never thrown.
     */
    @SuppressWarnings("unchecked")
    public boolean apply(String type, Map<String, Object> m) {
        if (type == null || !type.startsWith("ops.")) return false;
        messages++;
        if ("ops.snapshot".equals(type)) {
            readLimits(Json.obj(m, "limits"));
            for (int i = 0; i < OpsData.KINDS.length; i++) {
                String kind = OpsData.KINDS[i];
                Map<String, Object> map = byKind.get(kind);
                map.clear();
                List<Object> list = Json.arr(m, plural(kind));
                if (list != null) for (Object o : list) if (o instanceof Map) put(i, (Map<String, Object>) o);
            }
            live = true;
            dirty = true;
            return true;
        }
        if ("ops.remove".equals(type)) {
            Map<String, Object> map = byKind.get(Json.str(m, "kind", ""));
            if (map != null && map.remove(Json.str(m, "id", "")) != null) dirty = true;
            return true;
        }
        if (type.endsWith(".upsert") && type.length() > 11) {
            String kind = type.substring(4, type.length() - 7);
            int i = index(kind);
            Map<String, Object> e = Json.obj(m, kind);
            if (i >= 0 && e != null) {
                put(i, e);
                dirty = true;
                return true;
            }
        }
        ignored++;
        return true; // an ops.* type this mod does not know: ignored, but still ours
    }

    static String plural(String kind) {
        return "usage".equals(kind) ? "usage" : kind + "s";
    }

    static int index(String kind) {
        for (int i = 0; i < OpsData.KINDS.length; i++) if (OpsData.KINDS[i].equals(kind)) return i;
        return -1;
    }

    private void readLimits(Map<String, Object> l) {
        int[] spec = { OpsData.MAX_SERVICES, OpsData.MAX_JOBS, OpsData.MAX_USAGE, OpsData.MAX_ALERTS, OpsData.MAX_SOURCES };
        for (int i = 0; i < OpsData.KINDS.length; i++) {
            int v = l == null ? -1 : Json.integer(l, plural(OpsData.KINDS[i]), -1);
            caps[i] = v > 0 ? Math.min(v, spec[i]) : spec[i];
        }
    }

    private void put(int kindIndex, Map<String, Object> e) {
        String id = OpsData.clean(e.get("id"), 89);
        if (id.isEmpty()) return;
        Map<String, Object> map = byKind.get(OpsData.KINDS[kindIndex]);
        map.remove(id);
        map.put(id, e); // upsert = replace the whole entity; re-insert keeps "newest last"
        while (map.size() > caps[kindIndex]) {
            String victim = victim(kindIndex, map);
            map.remove(victim);
            evicted++;
        }
    }

    /** Least important entry of a kind (the adapter keeps the most important ones under a cap). */
    @SuppressWarnings("unchecked")
    private static String victim(int kindIndex, Map<String, Object> map) {
        String worstId = null;
        long worstScore = Long.MIN_VALUE;
        long order = 0;
        for (Map.Entry<String, Object> en : map.entrySet()) {
            Map<String, Object> e = (Map<String, Object>) en.getValue();
            long score;
            switch (kindIndex) {
                case 0:
                    score = OpsData.serviceRank(OpsData.service(e).state);
                    break;
                case 1:
                    score = OpsData.jobRank(OpsData.job(e));
                    break;
                case 2: {
                    float r = OpsData.usage(e).remainingPct;
                    score = Math.round((r < 0 ? 101 : r) * 10);
                    break;
                }
                case 3: {
                    OpsData.Alert a = OpsData.alert(e);
                    // resolved after open, low severity after high, oldest last
                    score = ("open".equals(a.state) ? 0 : 1_000_000_000_000_000L) + OpsData.severityRank(a.severity) * 100_000_000_000_000L
                        + (100_000_000_000_000L - 1 - Math.min(a.ts, 100_000_000_000_000L - 1));
                    break;
                }
                default:
                    score = 0;
            }
            // ties: the earliest inserted goes first
            long s = score * 1_000_000L - order++;
            if (kindIndex == 3) s = score; // alert score already orders by age
            if (worstId == null || s > worstScore) {
                worstId = en.getKey();
                worstScore = s;
            }
        }
        return worstId;
    }

    /** The display records (already capped), for the client blob and the server's fleet colour. */
    @SuppressWarnings("unchecked")
    public OpsData.View view() {
        OpsData.View v = new OpsData.View();
        v.live = live;
        for (Object o : byKind.get("service").values()) v.services.add(OpsData.service((Map<String, Object>) o));
        for (Object o : byKind.get("job").values()) v.jobs.add(OpsData.job((Map<String, Object>) o));
        for (Object o : byKind.get("usage").values()) v.usage.add(OpsData.usage((Map<String, Object>) o));
        for (Object o : byKind.get("alert").values()) v.alerts.add(OpsData.alert((Map<String, Object>) o));
        for (Object o : byKind.get("source").values()) v.sources.add(OpsData.source((Map<String, Object>) o));
        OpsData.sortForDisplay(v);
        return v;
    }

    /** Ids per kind, sorted (tests compare this with the adapter's own model). */
    public List<String> ids(String kind) {
        List<String> out = new ArrayList<>(byKind.get(kind).keySet());
        java.util.Collections.sort(out);
        return out;
    }
}
