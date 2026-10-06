package dev.agentcraft.gtnh.ui;

/**
 * Colour themes for the office UI (pure Java, no Minecraft classes: checked by
 * dev/tests/UiPureCheck.java). Two palettes, both aimed at WCAG AA (contrast >= 4.5:1 for body
 * text, >= 3:1 for large headline text):
 * <ul>
 * <li>{@link #DARK}: walnut panels with cream text and light "paper" cards (default; the in-world
 * screens glow at night and do not look washed out in daylight),</li>
 * <li>{@link #LIGHT}: cream panels with ink text.</li>
 * </ul>
 * Agent and status colours are arbitrary (they come from the adapter), so text drawn in them goes
 * through {@link #readable(int, int)} first, which darkens or lightens the colour until it reaches
 * 4.5:1 against the background it sits on.
 */
public final class Theme {

    public final String id;
    /** panel background, column/section surface, raised surface, divider lines */
    public final int bg, surface, raised, line;
    /** main text, secondary text (both on bg and surface) */
    public final int text, muted;
    /** cards: paper background, text on it, secondary text on it, selected card */
    public final int card, cardText, cardMuted, cardSelected;
    /** accent (decisions, "needs you"), warning text */
    public final int accent, danger;
    /** frame around in-world screens */
    public final int frame;

    /** Wall columns todo, doing, review, done, blocked: header fills (dark ink text sits on them). */
    public static final int[] COLUMN = { 0x9C9488, 0x2FA3A0, 0xC9A227, 0x8FA98B, 0xD97757 };
    public static final String[] COLUMN_NAMES = { "To do", "Doing", "Review", "Done", "Blocked" };
    public static final int INK = 0x1F1E1D, CREAM = 0xF4EFE6;

    private Theme(String id, int bg, int surface, int raised, int line, int text, int muted, int card, int cardText,
        int cardMuted, int cardSelected, int accent, int danger, int frame) {
        this.id = id;
        this.bg = bg;
        this.surface = surface;
        this.raised = raised;
        this.line = line;
        this.text = text;
        this.muted = muted;
        this.card = card;
        this.cardText = cardText;
        this.cardMuted = cardMuted;
        this.cardSelected = cardSelected;
        this.accent = accent;
        this.danger = danger;
        this.frame = frame;
    }

    public static final Theme DARK = new Theme(
        "dark",
        0x1C1815,
        0x28221E,
        0x352D27,
        0x4A3F37,
        CREAM,
        0xC4BAAC,
        0xF4EFE6,
        INK,
        0x5A5249,
        0xFFFFFF,
        0xEE9474,
        0xF07A70,
        0x3B2A20);

    public static final Theme LIGHT = new Theme(
        "light",
        0xF4EFE6,
        0xE9E1D3,
        0xFFFFFF,
        0xCFC4B3,
        INK,
        0x544C43,
        0xFFFFFF,
        INK,
        0x5A5249,
        0xFFF7EA,
        0xA8462B,
        0xB3261E,
        0x3B2A20);

    public static Theme byId(String id) {
        return "light".equalsIgnoreCase(id) ? LIGHT : DARK;
    }

    /** Text colour for a column header fill. */
    public static int onColumn(int column) {
        return INK;
    }

    // ---- WCAG maths --------------------------------------------------------------------------

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance of an RGB colour (alpha ignored). */
    public static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    /** WCAG contrast ratio, 1 .. 21. */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** {@code fg} moved towards black or white (whichever the background needs) until >= 4.5:1. */
    public static int readable(int fg, int bg) {
        return readable(fg, bg, 4.5);
    }

    public static int readable(int fg, int bg, double min) {
        fg &= 0xFFFFFF;
        if (contrast(fg, bg) >= min) return fg;
        boolean darken = luminance(bg) > 0.18;
        int r = (fg >> 16) & 0xFF, g = (fg >> 8) & 0xFF, b = fg & 0xFF;
        for (int i = 1; i <= 20; i++) {
            double k = i / 20.0;
            int rr, gg, bb;
            if (darken) {
                rr = (int) Math.round(r * (1 - k));
                gg = (int) Math.round(g * (1 - k));
                bb = (int) Math.round(b * (1 - k));
            } else {
                rr = (int) Math.round(r + (255 - r) * k);
                gg = (int) Math.round(g + (255 - g) * k);
                bb = (int) Math.round(b + (255 - b) * k);
            }
            int c = (rr << 16) | (gg << 8) | bb;
            if (contrast(c, bg) >= min) return c;
        }
        return darken ? 0x000000 : 0xFFFFFF;
    }

    /** Blend two RGB colours, t = 0 -> a, 1 -> b. */
    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    public static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }
}
