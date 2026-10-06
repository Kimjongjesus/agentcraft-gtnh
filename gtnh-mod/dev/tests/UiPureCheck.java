import java.util.List;

import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;

/**
 * Plain-Java checks of the UI toolkit's pure parts (no Minecraft on the classpath):
 * theme contrast (WCAG AA) and pixel-width text layout (no character-count truncation).
 *   javac --release 8 -d out src/main/java/dev/agentcraft/gtnh/ui/{Theme,TextLayout}.java dev/tests/UiPureCheck.java
 *   java -ea -cp out UiPureCheck
 */
public class UiPureCheck {

    static int checks;

    static void ok(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError(what);
    }

    /** A proportional-ish fake font: narrow i/l/., wide m/w, 6 units otherwise. */
    static final TextLayout.Measure M = s -> {
        float w = 0;
        for (char c : s.toCharArray()) w += "il.,'!| ".indexOf(c) >= 0 ? 3 : "mwMW".indexOf(c) >= 0 ? 9 : c == '\u2026' ? 7 : 6;
        return w;
    };

    static void contrast(Theme t) {
        String n = t.id;
        ok(Theme.contrast(t.text, t.bg) >= 4.5, n + " text on bg " + Theme.contrast(t.text, t.bg));
        ok(Theme.contrast(t.text, t.surface) >= 4.5, n + " text on surface");
        ok(Theme.contrast(t.text, t.raised) >= 4.5, n + " text on raised");
        ok(Theme.contrast(t.muted, t.bg) >= 4.5, n + " muted on bg " + Theme.contrast(t.muted, t.bg));
        ok(Theme.contrast(t.muted, t.surface) >= 4.5, n + " muted on surface " + Theme.contrast(t.muted, t.surface));
        ok(Theme.contrast(t.cardText, t.card) >= 4.5, n + " card text");
        ok(Theme.contrast(t.cardMuted, t.card) >= 4.5, n + " card muted " + Theme.contrast(t.cardMuted, t.card));
        ok(Theme.contrast(t.cardText, t.cardSelected) >= 4.5, n + " selected card text");
        ok(Theme.contrast(t.accent, t.bg) >= 4.5, n + " accent on bg " + Theme.contrast(t.accent, t.bg));
        ok(Theme.contrast(t.danger, t.bg) >= 4.5, n + " danger on bg " + Theme.contrast(t.danger, t.bg));
        for (int c = 0; c < Theme.COLUMN.length; c++) {
            double r = Theme.contrast(Theme.onColumn(c), Theme.COLUMN[c]);
            ok(r >= 4.5, n + " column header " + Theme.COLUMN_NAMES[c] + " " + r);
        }
    }

    public static void main(String[] args) {
        contrast(Theme.DARK);
        contrast(Theme.LIGHT);
        // arbitrary agent colours become readable on every surface they are drawn on
        int[] agents = { 0xFFD700, 0x2FA3A0, 0x111111, 0x5B8DEF, 0xFF00FF, 0x8FA98B, 0xC9A227 };
        int[] bgs = { Theme.DARK.bg, Theme.DARK.card, Theme.LIGHT.bg, Theme.LIGHT.card, 0x101418 };
        for (int a : agents) for (int b : bgs) ok(Theme.contrast(Theme.readable(a, b), b) >= 4.5, "readable " + Integer.toHexString(a) + " on " + Integer.toHexString(b));

        // ellipsize by width, never past it
        String longTitle = "AgentCraft GTNH port: card 4 (readable UI, font, overlap fixes) for the office";
        for (float w : new float[] { 20, 57, 100, 180, 333 }) {
            String e = TextLayout.ellipsize(longTitle, w, M);
            ok(M.width(e) <= w, "ellipsized fits " + w);
            ok(e.endsWith(TextLayout.ELLIPSIS), "ellipsis at " + w);
        }
        ok(TextLayout.ellipsize("short", 100, M).equals("short"), "fits unchanged");

        // wrap: every line fits, the text is complete when unlimited, no 25-char cut
        List<String> lines = TextLayout.wrap(longTitle, 120, 0, M);
        StringBuilder back = new StringBuilder();
        for (String l : lines) {
            ok(M.width(l) <= 120, "line fits: " + l);
            back.append(back.length() == 0 ? "" : " ").append(l);
        }
        ok(back.toString().equals(longTitle), "unlimited wrap keeps every word: " + back);
        boolean longerThan25 = false;
        for (String l : TextLayout.wrap(longTitle, 240, 0, M)) longerThan25 |= l.length() > 25;
        ok(longerThan25, "a wide line holds more than 25 characters (no hard-coded cut)");

        // limited: last line ellipsized, still within width
        List<String> two = TextLayout.wrap(longTitle, 120, 2, M);
        ok(two.size() == 2, "two lines " + two);
        ok(two.get(1).endsWith(TextLayout.ELLIPSIS), "second line ellipsized " + two);
        for (String l : two) ok(M.width(l) <= 120, "limited line fits");
        // fits in the budget: no ellipsis
        List<String> fit = TextLayout.wrap("Backup job: weekly verify", 120, 3, M);
        for (String l : fit) ok(!l.endsWith(TextLayout.ELLIPSIS), "no ellipsis when it fits");
        // a single unbreakable word longer than a line is broken, not dropped
        List<String> word = TextLayout.wrap("t_d1f97d18_with_a_very_long_identifier_and_more", 60, 0, M);
        ok(word.size() > 1, "long word broken");
        for (String l : word) ok(M.width(l) <= 60, "broken part fits");
        // paragraphs
        List<String> paras = TextLayout.wrap("First line.\n\nSecond paragraph here.", 400, 0, M);
        ok(paras.size() == 3 && paras.get(1).isEmpty(), "paragraph break kept " + paras);
        System.out.println("UiPureCheck OK: " + checks + " checks");
    }
}
