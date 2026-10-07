package dev.agentcraft.gtnh.ops;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Card 5b: the ops feeds ({@code docs/ops-protocol.md}) as the mod keeps them: plain records with
 * only the fields the panels draw, the compact binary form the server sends to clients, and the
 * display rules shared by every panel (sort order, worst state). Pure Java (no Minecraft), so the
 * plain-Java checks cover it.
 *
 * <p>
 * Strings are re-capped here to the spec's limits whatever the adapter sent, ids included; numbers
 * are clamped. Nothing in these records ever flows back to the adapter.
 */
public final class OpsData {

    /** Spec caps (section 4); the snapshot's {@code limits} may lower them, never raise them. */
    public static final int MAX_SERVICES = 256, MAX_JOBS = 128, MAX_USAGE = 32, MAX_ALERTS = 100, MAX_SOURCES = 16;
    public static final String[] KINDS = { "service", "job", "usage", "alert", "source" };

    private OpsData() {}

    // ---- records ------------------------------------------------------------------------------

    public static final class Service {

        public String id = "", sourceId = "", name = "", group = "", state = "unknown", detail = "";
        public long since;
        /** percent used 0..100, or -1 = not reported */
        public float cpu = -1, mem = -1, disk = -1;
    }

    public static final class Job {

        public String id = "", sourceId = "", name = "", lastStatus = "unknown", schedule = "", detail = "";
        public boolean enabled = true;
        public long lastRun, nextRun, durationMs;
    }

    public static final class Usage {

        public String id = "", sourceId = "", provider = "", window = "", detail = "";
        /** 0..100, or -1 = unknown (never drawn as 0 %) */
        public float remainingPct = -1;
        public long resetsAt;
    }

    public static final class Alert {

        public String id = "", sourceId = "", severity = "info", source = "", title = "", state = "open", detail = "";
        public long ts, resolvedAt;
    }

    public static final class Source {

        public String id = "", name = "", state = "starting", detail = "";
        public int interval;
        public long lastOk;
        /** kinds the last good collect reported, as {@link #KIND_SERVICE} ... bits; 0 = not reported */
        public int kinds;

        public boolean carries(int kindBit) {
            return kinds == 0 || (kinds & kindBit) != 0;
        }
    }

    public static final int KIND_SERVICE = 1, KIND_JOB = 2, KIND_USAGE = 4, KIND_ALERT = 8;

    static int kindBits(Object list) {
        if (!(list instanceof List)) return 0;
        int b = 0;
        for (Object o : (List<?>) list) {
            if ("service".equals(o)) b |= KIND_SERVICE;
            else if ("job".equals(o)) b |= KIND_JOB;
            else if ("usage".equals(o)) b |= KIND_USAGE;
            else if ("alert".equals(o)) b |= KIND_ALERT;
        }
        return b;
    }

    /** Everything the client has: one immutable view per received blob. */
    public static final class View {

        public final List<Service> services = new ArrayList<>();
        public final List<Job> jobs = new ArrayList<>();
        public final List<Usage> usage = new ArrayList<>();
        public final List<Alert> alerts = new ArrayList<>();
        public final List<Source> sources = new ArrayList<>();
        /** an ops.snapshot arrived on the current adapter connection (false = feed offline / not offered) */
        public boolean live;
        public long builtAt;

        public boolean isEmpty() {
            return services.isEmpty() && jobs.isEmpty() && usage.isEmpty() && alerts.isEmpty();
        }
    }

    // ---- parsing (adapter JSON, already parsed into maps) --------------------------------------

    public static String clean(Object v, int max) {
        String s = v instanceof String ? (String) v : v instanceof Number || v instanceof Boolean ? String.valueOf(v) : "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') c = '?';
            if (c < 0x20) c = ' ';
            b.append(c);
        }
        return b.toString();
    }

    static String str(Map<String, Object> m, String k, int max) {
        return clean(m.get(k), max);
    }

    static long time(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number)) return 0;
        double d = ((Number) v).doubleValue();
        return d > 0 && d < 4.0E15 ? (long) d : 0;
    }

    static float pct(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number)) return -1;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d)) return -1;
        return (float) Math.max(0, Math.min(100, d));
    }

    static String oneOf(String v, String dflt, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        return dflt;
    }

    public static Service service(Map<String, Object> m) {
        Service s = new Service();
        s.id = str(m, "id", 89);
        s.sourceId = str(m, "sourceId", 24);
        s.name = str(m, "name", 40);
        s.group = str(m, "group", 24);
        if (s.group.isEmpty()) s.group = "other";
        s.state = oneOf(str(m, "state", 16), "unknown", "up", "degraded", "down", "unknown");
        s.since = time(m, "since");
        s.detail = str(m, "detail", 80);
        s.cpu = pct(m, "cpu");
        s.mem = pct(m, "mem");
        s.disk = pct(m, "disk");
        return s;
    }

    public static Job job(Map<String, Object> m) {
        Job j = new Job();
        j.id = str(m, "id", 89);
        j.sourceId = str(m, "sourceId", 24);
        j.name = str(m, "name", 40);
        j.lastStatus = oneOf(str(m, "lastStatus", 16), "unknown", "ok", "failed", "running", "unknown");
        Object en = m.get("enabled");
        j.enabled = !(en instanceof Boolean) || (Boolean) en;
        j.schedule = str(m, "schedule", 48);
        j.lastRun = time(m, "lastRun");
        j.nextRun = time(m, "nextRun");
        j.durationMs = Math.min(time(m, "durationMs"), 30L * 86400000L);
        j.detail = str(m, "detail", 80);
        return j;
    }

    public static Usage usage(Map<String, Object> m) {
        Usage u = new Usage();
        u.id = str(m, "id", 89);
        u.sourceId = str(m, "sourceId", 24);
        u.provider = str(m, "provider", 32);
        u.window = str(m, "window", 32);
        u.remainingPct = pct(m, "remainingPct");
        u.resetsAt = time(m, "resetsAt");
        u.detail = str(m, "detail", 80);
        return u;
    }

    public static Alert alert(Map<String, Object> m) {
        Alert a = new Alert();
        a.id = str(m, "id", 89);
        a.sourceId = str(m, "sourceId", 24);
        a.ts = time(m, "ts");
        a.severity = oneOf(str(m, "severity", 16), "info", "info", "warn", "critical");
        a.source = str(m, "source", 24);
        if (a.source.isEmpty()) a.source = a.sourceId;
        a.title = str(m, "title", 100);
        a.state = oneOf(str(m, "state", 16), "open", "open", "resolved");
        a.detail = str(m, "detail", 200);
        a.resolvedAt = "resolved".equals(a.state) ? time(m, "resolvedAt") : 0;
        return a;
    }

    public static Source source(Map<String, Object> m) {
        Source s = new Source();
        s.id = str(m, "id", 24);
        s.name = str(m, "name", 40);
        s.state = oneOf(str(m, "state", 16), "starting", "starting", "ok", "warn", "error", "stale");
        Object iv = m.get("interval");
        s.interval = iv instanceof Number ? (int) Math.max(0, Math.min(86400, ((Number) iv).doubleValue())) : 0;
        s.lastOk = time(m, "lastOk");
        s.detail = str(m, "detail", 120);
        s.kinds = kindBits(m.get("kinds"));
        return s;
    }

    // ---- binary form (server -> client blob) ---------------------------------------------------

    static void w(DataOutputStream out, String s) throws IOException {
        out.writeUTF(s == null ? "" : s);
    }

    static String r(DataInputStream in, int max) throws IOException {
        return clean(in.readUTF(), max);
    }

    static void pct(DataOutputStream out, float v) throws IOException {
        out.writeShort(v < 0 ? -1 : Math.round(v * 10));
    }

    static float pct(DataInputStream in) throws IOException {
        int v = in.readShort();
        return v < 0 ? -1 : Math.min(100, v / 10.0F);
    }

    /** 2: sources carry their kinds (card 5b review r1: source-aware panel health). */
    public static final int VERSION = 2;

    public static void write(DataOutputStream out, boolean live, List<Service> services, List<Job> jobs, List<Usage> usage,
        List<Alert> alerts, List<Source> sources) throws IOException {
        out.writeByte(VERSION);
        out.writeBoolean(live);
        int n = Math.min(services.size(), MAX_SERVICES);
        out.writeShort(n);
        for (int i = 0; i < n; i++) {
            Service s = services.get(i);
            w(out, s.id);
            w(out, s.sourceId);
            w(out, s.name);
            w(out, s.group);
            w(out, s.state);
            w(out, s.detail);
            out.writeLong(s.since);
            pct(out, s.cpu);
            pct(out, s.mem);
            pct(out, s.disk);
        }
        n = Math.min(jobs.size(), MAX_JOBS);
        out.writeShort(n);
        for (int i = 0; i < n; i++) {
            Job j = jobs.get(i);
            w(out, j.id);
            w(out, j.sourceId);
            w(out, j.name);
            w(out, j.lastStatus);
            w(out, j.schedule);
            w(out, j.detail);
            out.writeBoolean(j.enabled);
            out.writeLong(j.lastRun);
            out.writeLong(j.nextRun);
            out.writeLong(j.durationMs);
        }
        n = Math.min(usage.size(), MAX_USAGE);
        out.writeShort(n);
        for (int i = 0; i < n; i++) {
            Usage u = usage.get(i);
            w(out, u.id);
            w(out, u.sourceId);
            w(out, u.provider);
            w(out, u.window);
            w(out, u.detail);
            pct(out, u.remainingPct);
            out.writeLong(u.resetsAt);
        }
        n = Math.min(alerts.size(), MAX_ALERTS);
        out.writeShort(n);
        for (int i = 0; i < n; i++) {
            Alert a = alerts.get(i);
            w(out, a.id);
            w(out, a.sourceId);
            w(out, a.severity);
            w(out, a.source);
            w(out, a.title);
            w(out, a.state);
            w(out, a.detail);
            out.writeLong(a.ts);
            out.writeLong(a.resolvedAt);
        }
        n = Math.min(sources.size(), MAX_SOURCES);
        out.writeShort(n);
        for (int i = 0; i < n; i++) {
            Source s = sources.get(i);
            w(out, s.id);
            w(out, s.name);
            w(out, s.state);
            w(out, s.detail);
            out.writeInt(s.interval);
            out.writeLong(s.lastOk);
            out.writeByte(s.kinds & 0x0F);
        }
    }

    public static View read(DataInputStream in) throws IOException {
        View v = new View();
        int ver = in.readUnsignedByte();
        if (ver != VERSION) throw new IOException("unknown ops blob version " + ver);
        v.live = in.readBoolean();
        int n = Math.min(in.readUnsignedShort(), MAX_SERVICES);
        for (int i = 0; i < n; i++) {
            Service s = new Service();
            s.id = r(in, 89);
            s.sourceId = r(in, 24);
            s.name = r(in, 40);
            s.group = r(in, 24);
            s.state = oneOf(r(in, 16), "unknown", "up", "degraded", "down", "unknown");
            s.detail = r(in, 80);
            s.since = in.readLong();
            s.cpu = pct(in);
            s.mem = pct(in);
            s.disk = pct(in);
            v.services.add(s);
        }
        n = Math.min(in.readUnsignedShort(), MAX_JOBS);
        for (int i = 0; i < n; i++) {
            Job j = new Job();
            j.id = r(in, 89);
            j.sourceId = r(in, 24);
            j.name = r(in, 40);
            j.lastStatus = oneOf(r(in, 16), "unknown", "ok", "failed", "running", "unknown");
            j.schedule = r(in, 48);
            j.detail = r(in, 80);
            j.enabled = in.readBoolean();
            j.lastRun = in.readLong();
            j.nextRun = in.readLong();
            j.durationMs = in.readLong();
            v.jobs.add(j);
        }
        n = Math.min(in.readUnsignedShort(), MAX_USAGE);
        for (int i = 0; i < n; i++) {
            Usage u = new Usage();
            u.id = r(in, 89);
            u.sourceId = r(in, 24);
            u.provider = r(in, 32);
            u.window = r(in, 32);
            u.detail = r(in, 80);
            u.remainingPct = pct(in);
            u.resetsAt = in.readLong();
            v.usage.add(u);
        }
        n = Math.min(in.readUnsignedShort(), MAX_ALERTS);
        for (int i = 0; i < n; i++) {
            Alert a = new Alert();
            a.id = r(in, 89);
            a.sourceId = r(in, 24);
            a.severity = oneOf(r(in, 16), "info", "info", "warn", "critical");
            a.source = r(in, 24);
            a.title = r(in, 100);
            a.state = oneOf(r(in, 16), "open", "open", "resolved");
            a.detail = r(in, 200);
            a.ts = in.readLong();
            a.resolvedAt = in.readLong();
            v.alerts.add(a);
        }
        n = Math.min(in.readUnsignedShort(), MAX_SOURCES);
        for (int i = 0; i < n; i++) {
            Source s = new Source();
            s.id = r(in, 24);
            s.name = r(in, 40);
            s.state = oneOf(r(in, 16), "starting", "starting", "ok", "warn", "error", "stale");
            s.detail = r(in, 120);
            s.interval = in.readInt();
            s.lastOk = in.readLong();
            s.kinds = in.readUnsignedByte() & 0x0F;
            v.sources.add(s);
        }
        sortForDisplay(v);
        return v;
    }

    // ---- display rules -------------------------------------------------------------------------

    public static int serviceRank(String state) {
        switch (state) {
            case "down":
                return 0;
            case "degraded":
                return 1;
            case "unknown":
                return 2;
            default:
                return 3;
        }
    }

    public static int jobRank(Job j) {
        int r = "failed".equals(j.lastStatus) ? 0 : "running".equals(j.lastStatus) ? 1 : "unknown".equals(j.lastStatus) ? 2 : 3;
        return j.enabled ? r : r + 4;
    }

    public static int severityRank(String sev) {
        return "critical".equals(sev) ? 0 : "warn".equals(sev) ? 1 : 2;
    }

    /** Client order (never rely on arrival order): worst first, then name; alerts open first, newest first. */
    public static void sortForDisplay(View v) {
        Collections.sort(v.services, (a, b) -> {
            int c = Integer.compare(serviceRank(a.state), serviceRank(b.state));
            if (c != 0) return c;
            c = a.group.compareToIgnoreCase(b.group);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        Collections.sort(v.jobs, (a, b) -> {
            int c = Integer.compare(jobRank(a), jobRank(b));
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        Collections.sort(v.usage, (a, b) -> {
            float ra = a.remainingPct < 0 ? 101 : a.remainingPct, rb = b.remainingPct < 0 ? 101 : b.remainingPct;
            int c = Float.compare(ra, rb);
            if (c != 0) return c;
            c = a.provider.compareToIgnoreCase(b.provider);
            return c != 0 ? c : a.window.compareToIgnoreCase(b.window);
        });
        Collections.sort(v.alerts, (a, b) -> {
            boolean oa = "open".equals(a.state), ob = "open".equals(b.state);
            if (oa != ob) return oa ? -1 : 1;
            return Long.compare(b.ts, a.ts);
        });
    }

    /**
     * Worst ops state for the fleet beacon / lamps / atrium: "error" (a service down, an open
     * critical alert or an enabled job whose last run failed), "warn" (a degraded service, an open
     * warn alert, an ops source in error or stale), "ok" (data and nothing wrong) or "none" (no ops
     * feed). Unknown services are not counted as errors (nobody checked), only shown grey.
     */
    public static String worst(View v) {
        if (v == null || !v.live) return "none";
        boolean warn = false;
        for (Service s : v.services) {
            if ("down".equals(s.state)) return "error";
            if ("degraded".equals(s.state)) warn = true;
        }
        for (Alert a : v.alerts) {
            if (!"open".equals(a.state)) continue;
            if ("critical".equals(a.severity)) return "error";
            if ("warn".equals(a.severity)) warn = true;
        }
        for (Job j : v.jobs) if (j.enabled && "failed".equals(j.lastStatus)) return "error";
        for (Source s : v.sources) if ("error".equals(s.state) || "stale".equals(s.state)) warn = true;
        if (warn) return "warn";
        return v.isEmpty() && v.sources.isEmpty() ? "none" : "ok";
    }

    /**
     * Fleet colour = worst of (agents, ops), most urgent first: waiting (someone waits on the
     * player) > error (an agent errored, or ops "error") > warn (ops only: degraded / warning) >
     * working > thinking > idle. Offline stays offline (the adapter link is down: nothing is known).
     */
    public static String combineFleet(String agentFamily, String opsWorst) {
        if ("offline".equals(agentFamily) || "waiting".equals(agentFamily) || "error".equals(agentFamily)) return agentFamily;
        if ("error".equals(opsWorst)) return "error";
        if ("warn".equals(opsWorst)) return "warn";
        return agentFamily;
    }

    /** Group filter for a panel binding: "" / "all" = everything, else a group, source id or provider. */
    public static boolean matches(String binding, String... keys) {
        if (binding == null || binding.isEmpty() || "all".equals(binding)) return true;
        for (String k : keys) if (k != null && binding.equalsIgnoreCase(k)) return true;
        return false;
    }

    /** "3m", "2h", "4d" (or "in 3m" for a future time when future = true). */
    public static String age(long ts, long now) {
        if (ts <= 0) return "";
        long s = Math.abs(now - ts) / 1000;
        String a = s < 60 ? s + "s" : s < 3600 ? (s / 60) + "m" : s < 86400 ? (s / 3600) + "h" : (s / 86400) + "d";
        return ts > now ? "in " + a : a + " ago";
    }
}
