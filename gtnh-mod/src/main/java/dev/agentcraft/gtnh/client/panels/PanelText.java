package dev.agentcraft.gtnh.client.panels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.UiFont;

/** Small shared helpers for the in-world panels: a wrap cache and status/time formatting. */
final class PanelText {

    private static final Map<String, List<String>> WRAPS = new HashMap<>();

    private PanelText() {}

    static List<String> wrap(UiFont f, String text, float width, float size, int maxLines) {
        String key = f.file + "|" + width + "|" + size + "|" + maxLines + "|" + text;
        List<String> got = WRAPS.get(key);
        if (got != null) return got;
        if (WRAPS.size() > 4000) WRAPS.clear();
        got = TextLayout.wrap(text, width, maxLines, f.measure(size));
        WRAPS.put(key, got);
        return got;
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** 0..1 slow pulse (decisions waiting). */
    static float pulse(long now) {
        double t = (now % 2000L) / 2000.0D;
        return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
    }
}
