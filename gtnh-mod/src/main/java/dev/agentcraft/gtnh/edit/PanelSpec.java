package dev.agentcraft.gtnh.edit;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What one panel block is: its type (a {@link PanelTypes} id), the direction its front faces, its
 * binding, screen size in blocks, an optional label and an optional per-panel theme. Immutable;
 * every value is sanitized on construction, so nothing unbounded or unprintable reaches a file, a
 * packet or a tile entity.
 */
public final class PanelSpec {

    /** Facings in clockwise order seen from above; metadata 2..5 = north, south, west, east. */
    public static final String[] FACINGS = { "north", "east", "south", "west" };
    public static final Pattern BINDING = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    public static final int MAX_LABEL = 32;

    public final String type, facing, binding, label, theme;
    public final int w, h;

    public PanelSpec(String type, String facing, String binding, int w, int h, String label, String theme) {
        this.type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        this.facing = facingOrNorth(facing);
        this.binding = cleanBinding(binding);
        this.w = Math.max(1, Math.min(w, 8));
        this.h = Math.max(1, Math.min(h, 6));
        this.label = cleanLabel(label);
        this.theme = "dark".equals(theme) || "light".equals(theme) ? theme : "";
    }

    public static String facingOrNorth(String f) {
        if (f == null) return "north";
        String s = f.toLowerCase(Locale.ROOT);
        for (String k : FACINGS) if (k.equals(s)) return k;
        return "north";
    }

    public static String cleanBinding(String b) {
        if (b == null) return "";
        String s = b.trim();
        return BINDING.matcher(s)
            .matches() ? s : "";
    }

    public static String cleanLabel(String l) {
        if (l == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : l.toCharArray()) {
            if (c < 32 || c == 127 || c == '\u00a7' || Character.isISOControl(c)) continue;
            if (b.length() >= MAX_LABEL) break;
            b.append(c);
        }
        return b.toString()
            .trim();
    }

    public PanelSpec withBinding(String b) {
        return new PanelSpec(type, facing, b, w, h, label, theme);
    }

    public PanelSpec withSize(int nw, int nh) {
        return new PanelSpec(type, facing, binding, nw, nh, label, theme);
    }

    public PanelSpec withLabel(String l) {
        return new PanelSpec(type, facing, binding, w, h, l, theme);
    }

    public PanelSpec withTheme(String t) {
        return new PanelSpec(type, facing, binding, w, h, label, t);
    }

    public PanelSpec withFacing(String f) {
        return new PanelSpec(type, f, binding, w, h, label, theme);
    }

    /** Facing turned by quarter turns clockwise (seen from above). */
    public static String rotate(String facing, int quarters) {
        int i = 0;
        for (int k = 0; k < 4; k++) if (FACINGS[k].equals(facing)) i = k;
        return FACINGS[Math.floorMod(i + quarters, 4)];
    }

    public static int quarterOf(String facing) {
        for (int k = 0; k < 4; k++) if (FACINGS[k].equals(facing)) return k;
        return 0;
    }

    /** Block metadata of a faced block whose front points this way (2..5, as BlockAgentCraft). */
    public static int meta(String facing) {
        switch (facingOrNorth(facing)) {
            case "south":
                return 3;
            case "west":
                return 4;
            case "east":
                return 5;
            default:
                return 2;
        }
    }

    public static String facingOfMeta(int meta) {
        switch (meta) {
            case 3:
                return "south";
            case 4:
                return "west";
            case 5:
                return "east";
            default:
                return "north";
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map("type", type, "facing", facing);
        if (!binding.isEmpty()) m.put("binding", binding);
        m.put("w", w);
        m.put("h", h);
        if (!label.isEmpty()) m.put("label", label);
        if (!theme.isEmpty()) m.put("theme", theme);
        return m;
    }

    public static PanelSpec fromJson(Map<String, Object> m) {
        if (m == null) return null;
        String t = Json.str(m, "type", "");
        if (t.isEmpty()) return null;
        return new PanelSpec(
            t,
            Json.str(m, "facing", "north"),
            Json.str(m, "binding", ""),
            Json.integer(m, "w", 1),
            Json.integer(m, "h", 1),
            Json.str(m, "label", ""),
            Json.str(m, "theme", ""));
    }

    /** "Task Wall · board all · 5x3 · “Main wall”" style one-liner for audits, diffs and chat. */
    public String describe() {
        PanelType t = PanelTypes.get(type);
        StringBuilder b = new StringBuilder(t == null ? type : t.name);
        b.append(" facing ")
            .append(facing);
        b.append(", ")
            .append(binding.isEmpty() ? "unbound" : "bound to " + binding);
        if (t == null || t.resizable) b.append(", ")
            .append(w)
            .append('x')
            .append(h);
        if (!label.isEmpty()) b.append(", label \"")
            .append(label)
            .append('"');
        if (!theme.isEmpty()) b.append(", ")
            .append(theme)
            .append(" theme");
        return b.toString();
    }

    /** What changed from {@code o} to this, e.g. "binding all -> homelab, size 5x3 -> 4x3". */
    public String changesFrom(PanelSpec o) {
        StringBuilder b = new StringBuilder();
        if (!type.equals(o.type)) add(b, "type " + o.type + " -> " + type);
        if (!facing.equals(o.facing)) add(b, "facing " + o.facing + " -> " + facing);
        if (!binding.equals(o.binding)) add(b, "binding " + show(o.binding) + " -> " + show(binding));
        if (w != o.w || h != o.h) add(b, "size " + o.w + "x" + o.h + " -> " + w + "x" + h);
        if (!label.equals(o.label)) add(b, "label \"" + o.label + "\" -> \"" + label + "\"");
        if (!theme.equals(o.theme)) add(b, "theme " + show(o.theme) + " -> " + show(theme));
        return b.toString();
    }

    private static String show(String s) {
        return s.isEmpty() ? "(none)" : s;
    }

    private static void add(StringBuilder b, String s) {
        if (b.length() > 0) b.append(", ");
        b.append(s);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PanelSpec)) return false;
        PanelSpec p = (PanelSpec) o;
        return type.equals(p.type) && facing.equals(p.facing)
            && binding.equals(p.binding)
            && w == p.w
            && h == p.h
            && label.equals(p.label)
            && theme.equals(p.theme);
    }

    @Override
    public int hashCode() {
        return ((type.hashCode() * 31 + facing.hashCode()) * 31 + binding.hashCode()) * 31 + w * 7 + h;
    }

    @Override
    public String toString() {
        return describe();
    }
}
