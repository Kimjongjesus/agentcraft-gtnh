package dev.agentcraft.gtnh.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.hq.Anchor;

/**
 * One recorded edit: a label, who made it, when, and the ordered changes it applied, each with the
 * state before and after (null = absent). Undo applies {@link #inverse()}; redo applies the op
 * again. Requests are built from target states only; the engine fills in every "before" from the
 * live world / anchors file at the moment it executes, so an undo always restores what was really
 * there.
 */
public final class Op {

    public static final String PANEL = "panel", ANCHOR = "anchor", DISPLAY = "display";

    /** One change. Exactly one of the three payload kinds is used, as named by {@link #kind}. */
    public static final class Change {

        public final String kind;
        public final Pos pos; // PANEL
        public final String name; // ANCHOR name, DISPLAY key
        public final Object before, after; // PanelSpec | Anchor | String, or null

        private Change(String kind, Pos pos, String name, Object before, Object after) {
            this.kind = kind;
            this.pos = pos;
            this.name = name;
            this.before = before;
            this.after = after;
        }

        public static Change panel(Pos p, PanelSpec after) {
            return new Change(PANEL, p, null, null, after);
        }

        public static Change panel(Pos p, PanelSpec before, PanelSpec after) {
            return new Change(PANEL, p, null, before, after);
        }

        public static Change anchor(String name, Anchor after) {
            return new Change(ANCHOR, null, name, null, after);
        }

        public static Change anchor(String name, Anchor before, Anchor after) {
            return new Change(ANCHOR, null, name, before, after);
        }

        public static Change display(String key, String after) {
            return new Change(DISPLAY, null, key, null, after);
        }

        public static Change display(String key, String before, String after) {
            return new Change(DISPLAY, null, key, before, after);
        }

        public Change withBefore(Object b) {
            return new Change(kind, pos, name, b, after);
        }

        public Change inverse() {
            return new Change(kind, pos, name, after, before);
        }

        public String target() {
            return PANEL.equals(kind) ? pos.key() : name;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map("k", kind);
            if (PANEL.equals(kind)) m.put("pos", pos.key());
            else m.put("name", name);
            m.put("before", enc(before));
            m.put("after", enc(after));
            return m;
        }

        private static Object enc(Object o) {
            if (o instanceof PanelSpec) return ((PanelSpec) o).toJson();
            if (o instanceof Anchor) return anchorJson((Anchor) o);
            return o;
        }

        @SuppressWarnings("unchecked")
        public static Change fromJson(Map<String, Object> m) {
            String k = Json.str(m, "k", "");
            Object b = m.get("before"), a = m.get("after");
            switch (k) {
                case PANEL: {
                    Pos p = Pos.parse(Json.str(m, "pos", ""));
                    if (p == null) return null;
                    return panel(
                        p,
                        b instanceof Map ? PanelSpec.fromJson((Map<String, Object>) b) : null,
                        a instanceof Map ? PanelSpec.fromJson((Map<String, Object>) a) : null);
                }
                case ANCHOR: {
                    String n = Json.str(m, "name", "");
                    if (n.isEmpty()) return null;
                    return anchor(
                        n,
                        b instanceof Map ? anchorFrom(n, (Map<String, Object>) b) : null,
                        a instanceof Map ? anchorFrom(n, (Map<String, Object>) a) : null);
                }
                case DISPLAY: {
                    String n = Json.str(m, "name", "");
                    if (n.isEmpty()) return null;
                    return display(n, b instanceof String ? (String) b : null, a instanceof String ? (String) a : null);
                }
                default:
                    return null;
            }
        }
    }

    public final String label, who;
    public final long time;
    public final List<Change> changes;

    public Op(String label, String who, long time, List<Change> changes) {
        this.label = label == null ? "" : label;
        this.who = who == null ? "" : who;
        this.time = time;
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
    }

    public Op inverse(String newLabel) {
        List<Change> inv = new ArrayList<>();
        for (int i = changes.size() - 1; i >= 0; i--) inv.add(changes.get(i)
            .inverse());
        return new Op(newLabel, who, time, inv);
    }

    public Map<String, Object> toJson() {
        List<Object> cs = new ArrayList<>();
        for (Change c : changes) cs.add(c.toJson());
        return Json.map("label", label, "who", who, "time", (double) time, "changes", cs);
    }

    @SuppressWarnings("unchecked")
    public static Op fromJson(Map<String, Object> m) {
        if (m == null) return null;
        List<Object> cs = Json.arr(m, "changes");
        if (cs == null) return null;
        List<Change> out = new ArrayList<>();
        for (Object o : cs) {
            if (!(o instanceof Map)) continue;
            Change c = Change.fromJson((Map<String, Object>) o);
            if (c != null) out.add(c);
        }
        return new Op(Json.str(m, "label", ""), Json.str(m, "who", ""), (long) Json.num(m, "time", 0), out);
    }

    public static Map<String, Object> anchorJson(Anchor a) {
        Map<String, Object> o = Json.map("x", a.x, "y", a.y, "z", a.z, "facing", Anchor.facingName(a.yaw), "yaw", (double) a.yaw);
        if (a.pitch != 0.0F) o.put("pitch", (double) a.pitch);
        return o;
    }

    public static Anchor anchorFrom(String name, Map<String, Object> o) {
        if (o == null || !o.containsKey("x") || !o.containsKey("y") || !o.containsKey("z")) return null;
        float yaw = 0;
        Float f = Anchor.parseFacing(Json.str(o, "facing", null));
        if (f != null) yaw = f;
        if (o.containsKey("yaw")) yaw = (float) Json.num(o, "yaw", yaw);
        return new Anchor(name, Json.num(o, "x", 0), Json.num(o, "y", 0), Json.num(o, "z", 0), yaw, (float) Json.num(o, "pitch", 0));
    }

    public static boolean sameAnchor(Anchor a, Anchor b) {
        if (a == null || b == null) return a == b;
        return a.name.equals(b.name) && Math.abs(a.x - b.x) < 1e-6
            && Math.abs(a.y - b.y) < 1e-6
            && Math.abs(a.z - b.z) < 1e-6
            && Math.abs(Anchor.normYaw(a.yaw - b.yaw)) < 1e-3
            && Math.abs(a.pitch - b.pitch) < 1e-3;
    }
}
