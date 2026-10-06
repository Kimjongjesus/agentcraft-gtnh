package dev.agentcraft.gtnh.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.StationAssigner;

/**
 * A shareable office layout (bundled presets, exports, imports): panels and anchors at offsets
 * from an origin block, authored for someone standing on the origin and looking {@link #facing}.
 * Applying it at another origin, looking another way, rotates every offset and facing by the
 * quarter turns between the two. No world coordinates are stored, and {@link #export} replaces
 * agent ids, board slugs and labels with placeholders unless asked to keep them.
 */
public final class RelLayout {

    public static final String KIND = "agentcraft-office-layout";

    public static final class RelPanel {

        public final int dx, dy, dz;
        public final PanelSpec spec;

        public RelPanel(int dx, int dy, int dz, PanelSpec spec) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.spec = spec;
        }
    }

    public static final class RelAnchor {

        public final String name;
        public final double dx, dy, dz;
        public final float yaw;

        public RelAnchor(String name, double dx, double dy, double dz, float yaw) {
            this.name = name;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.yaw = yaw;
        }
    }

    public String name = "", description = "", facing = "north";
    public final List<RelPanel> panels = new ArrayList<>();
    public final List<RelAnchor> anchors = new ArrayList<>();
    public final Map<String, String> display = new TreeMap<>();

    /** Quarter turns clockwise from this layout's facing to {@code to}. */
    public int quartersTo(String to) {
        return Math.floorMod(PanelSpec.quarterOf(PanelSpec.facingOrNorth(to)) - PanelSpec.quarterOf(facing), 4);
    }

    /** (dx, dz) turned clockwise by quarter turns, seen from above (north -> east -> south -> west). */
    public static int[] rot(int dx, int dz, int q) {
        int x = dx, z = dz;
        for (int i = 0; i < Math.floorMod(q, 4); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[] { x, z };
    }

    public static double[] rot(double dx, double dz, int q) {
        double x = dx, z = dz;
        for (int i = 0; i < Math.floorMod(q, 4); i++) {
            double t = x;
            x = -z;
            z = t;
        }
        return new double[] { x, z };
    }

    public Pos panelPos(RelPanel p, Pos origin, int q) {
        int[] r = rot(p.dx, p.dz, q);
        return origin.add(r[0], p.dy, r[1]);
    }

    public PanelSpec panelSpec(RelPanel p, int q) {
        return p.spec.withFacing(PanelSpec.rotate(p.spec.facing, q));
    }

    public Anchor anchorAt(RelAnchor a, String name, Pos origin, int q) {
        double[] r = rot(a.dx, a.dz, q);
        return new Anchor(name, origin.x + 0.5 + r[0], origin.y + a.dy, origin.z + 0.5 + r[1], a.yaw + 90.0F * q, 0.0F);
    }

    // ---- export ------------------------------------------------------------------------------

    /**
     * The panels and anchors within {@code radius} blocks (a cube) of {@code origin}, re-expressed
     * as offsets for someone looking {@code facing}, stored facing north. With keepNames false:
     * agent ids -> agent-1.., board slugs -> board-1.., labels dropped, personal anchors renamed to
     * the same placeholders, QA cameras left out.
     */
    public static RelLayout export(String name, Map<Pos, PanelSpec> panels, Map<String, Anchor> anchors, Map<String, String> display,
        Pos origin, String facing, int radius, boolean keepNames) {
        RelLayout r = new RelLayout();
        r.name = name;
        r.facing = "north";
        int q = Math.floorMod(-PanelSpec.quarterOf(PanelSpec.facingOrNorth(facing)), 4);
        Map<String, String> agents = new LinkedHashMap<>(), boards = new LinkedHashMap<>();
        for (Map.Entry<Pos, PanelSpec> e : panels.entrySet()) {
            Pos p = e.getKey();
            int dx = p.x - origin.x, dy = p.y - origin.y, dz = p.z - origin.z;
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            int[] rr = rot(dx, dz, q);
            PanelSpec s = e.getValue();
            s = s.withFacing(PanelSpec.rotate(s.facing, q));
            if (!keepNames) {
                PanelType t = PanelTypes.get(s.type);
                String b = s.binding;
                if (!b.isEmpty() && !"all".equals(b) && !"fleet".equals(b)) {
                    boolean board = t != null && PanelType.BOARD.equals(t.source);
                    Map<String, String> m = board ? boards : agents;
                    String ph = m.get(b);
                    if (ph == null) {
                        ph = (board ? "board-" : "agent-") + (m.size() + 1);
                        m.put(b, ph);
                    }
                    b = ph;
                }
                s = s.withBinding(b)
                    .withLabel("");
            }
            r.panels.add(new RelPanel(rr[0], dy, rr[1], s));
        }
        for (Anchor a : anchors.values()) {
            double dx = a.x - (origin.x + 0.5), dy = a.y - origin.y, dz = a.z - (origin.z + 0.5);
            if (Math.abs(dx) > radius + 0.5 || Math.abs(dy) > radius || Math.abs(dz) > radius + 0.5) continue;
            String n = a.name;
            if (!keepNames) {
                if (n.startsWith("cam_")) continue;
                n = stripPersonal(n, agents);
            }
            double[] rr = rot(dx, dz, q);
            r.anchors.add(new RelAnchor(n, rr[0], dy, rr[1], a.yaw + 90.0F * q));
        }
        r.display.putAll(display);
        return r;
    }

    /** desk_claude-builder -> desk_agent-1 (same placeholder as that agent's monitor binding). */
    static String stripPersonal(String n, Map<String, String> agents) {
        for (String st : StationAssigner.STATIONS) {
            if (!n.startsWith(st + "_")) continue;
            String rest = n.substring(st.length() + 1);
            if (rest.matches("\\d+") || rest.matches("agent-\\d+")) return n;
            String ph = agents.get(rest);
            if (ph == null) {
                ph = "agent-" + (agents.size() + 1);
                agents.put(rest, ph);
            }
            return st + "_" + ph;
        }
        return n;
    }

    /** "agent-3" / "board-2": a placeholder left by an export with names stripped. */
    public static boolean placeholder(String binding) {
        return binding != null && binding.matches("(agent|board)-\\d+");
    }

    // ---- codec -------------------------------------------------------------------------------

    public Map<String, Object> toJson() {
        List<Object> ps = new ArrayList<>();
        for (RelPanel p : panels) {
            Map<String, Object> m = Json.map("at", list(p.dx, p.dy, p.dz));
            m.putAll(p.spec.toJson());
            ps.add(m);
        }
        List<Object> as = new ArrayList<>();
        for (RelAnchor a : anchors) {
            as.add(Json.map("name", a.name, "at", list(round(a.dx), round(a.dy), round(a.dz)), "facing", Anchor.facingName(a.yaw), "yaw", (double) Anchor.normYaw(a.yaw)));
        }
        Map<String, Object> m = Json.map("schema", (double) EditEngine.SCHEMA, "kind", KIND, "name", name);
        if (!description.isEmpty()) m.put("description", description);
        m.put("facing", facing);
        m.put("panels", ps);
        m.put("anchors", as);
        m.put("display", new LinkedHashMap<String, Object>(display));
        return m;
    }

    private static double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    private static List<Object> list(Object... v) {
        List<Object> l = new ArrayList<>();
        for (Object o : v) l.add(o instanceof Integer ? Double.valueOf((Integer) o) : o);
        return l;
    }

    /** Parse an export / preset. Throws on a missing or newer schema and on the wrong kind. */
    @SuppressWarnings("unchecked")
    public static RelLayout fromJson(Map<String, Object> m) throws Json.ParseException {
        int schema = Json.integer(m, "schema", -1);
        if (schema < 0) throw new Json.ParseException("no \"schema\": not an AgentCraft layout file");
        if (schema > EditEngine.SCHEMA) throw new Json.ParseException("schema " + schema + " is newer than this mod (" + EditEngine.SCHEMA + "); update the mod");
        if (!KIND.equals(Json.str(m, "kind", KIND))) throw new Json.ParseException("not an office layout (kind " + Json.str(m, "kind", "") + ")");
        RelLayout r = new RelLayout();
        r.name = PanelSpec.cleanLabel(Json.str(m, "name", ""));
        r.description = clean(Json.str(m, "description", ""), 160);
        r.facing = PanelSpec.facingOrNorth(Json.str(m, "facing", "north"));
        List<Object> ps = Json.arr(m, "panels");
        if (ps != null) for (Object o : ps) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> pm = (Map<String, Object>) o;
            List<Object> at = Json.arr(pm, "at");
            PanelSpec s = PanelSpec.fromJson(pm);
            if (at == null || at.size() != 3 || s == null) continue;
            r.panels.add(new RelPanel(intOf(at.get(0)), intOf(at.get(1)), intOf(at.get(2)), s));
        }
        List<Object> as = Json.arr(m, "anchors");
        if (as != null) for (Object o : as) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> am = (Map<String, Object>) o;
            List<Object> at = Json.arr(am, "at");
            String n = Json.str(am, "name", "")
                .toLowerCase(java.util.Locale.ROOT);
            if (at == null || at.size() != 3 || !EditEngine.ANCHOR_NAME.matcher(n)
                .matches()) continue;
            float yaw = 0;
            Float f = Anchor.parseFacing(Json.str(am, "facing", null));
            if (f != null) yaw = f;
            if (am.containsKey("yaw")) yaw = (float) Json.num(am, "yaw", yaw);
            r.anchors.add(new RelAnchor(n, dbl(at.get(0)), dbl(at.get(1)), dbl(at.get(2)), yaw));
        }
        Map<String, Object> d = Json.obj(m, "display");
        if (d != null) for (Map.Entry<String, Object> e : d.entrySet()) {
            if (e.getValue() instanceof String && EditEngine.displayValueOk(e.getKey(), (String) e.getValue())) r.display.put(e.getKey(), (String) e.getValue());
        }
        return r;
    }

    static String clean(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 32 || c == 127 || c == '\u00a7') c = ' ';
            if (b.length() >= max) break;
            b.append(c);
        }
        return b.toString()
            .trim();
    }

    private static int intOf(Object o) {
        return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : 0;
    }

    private static double dbl(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
