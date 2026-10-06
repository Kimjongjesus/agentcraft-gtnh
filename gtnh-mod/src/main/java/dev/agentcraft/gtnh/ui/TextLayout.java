package dev.agentcraft.gtnh.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-width text layout (pure Java, checked by dev/tests/UiPureCheck.java). Everything is measured
 * in the caller's units through {@link Measure}, so the same code wraps a card title on a 5-block
 * wall, a GUI list row or a nameplate. There is no character-count truncation anywhere: a line is
 * as long as the space allows, and only the last allowed line is ellipsized ("…"), by width.
 */
public final class TextLayout {

    public interface Measure {

        float width(String s);
    }

    public static final String ELLIPSIS = "\u2026";

    private TextLayout() {}

    /** {@code s} if it fits, else the longest prefix that fits together with "…". */
    public static String ellipsize(String s, float maxW, Measure m) {
        if (s == null) return "";
        if (m.width(s) <= maxW) return s;
        float ew = m.width(ELLIPSIS);
        if (ew > maxW) return "";
        // binary search on the prefix length (width is monotonic in the prefix)
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (m.width(s.substring(0, mid)) + ew <= maxW) lo = mid;
            else hi = mid - 1;
        }
        String head = s.substring(0, lo);
        // do not end on a space before the ellipsis
        int end = head.length();
        while (end > 0 && head.charAt(end - 1) == ' ') end--;
        return head.substring(0, end) + ELLIPSIS;
    }

    /**
     * Word-wrap to {@code maxW}. Paragraphs ("\n") are kept; words longer than a line are broken.
     * {@code maxLines <= 0} means unlimited; otherwise the last line carries the rest of the text,
     * ellipsized by width.
     */
    public static List<String> wrap(String text, float maxW, int maxLines, Measure m) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] paras = text.split("\n", -1);
        StringBuilder rest = null; // text that did not fit once the line budget ran out
        outer: for (int p = 0; p < paras.length; p++) {
            String para = paras[p].trim();
            if (para.isEmpty()) {
                if (!out.isEmpty() && p < paras.length - 1) {
                    if (maxLines > 0 && out.size() >= maxLines) break;
                    out.add("");
                }
                continue;
            }
            String[] words = para.split(" +");
            StringBuilder line = new StringBuilder();
            for (int w = 0; w < words.length; w++) {
                String word = words[w];
                String cand = line.length() == 0 ? word : line + " " + word;
                if (m.width(cand) <= maxW) {
                    line.setLength(0);
                    line.append(cand);
                    continue;
                }
                if (line.length() > 0) {
                    if (maxLines > 0 && out.size() == maxLines - 1) {
                        rest = new StringBuilder(line).append(' ')
                            .append(join(words, w));
                        for (int q = p + 1; q < paras.length; q++) rest.append(' ')
                            .append(paras[q].trim());
                        break outer;
                    }
                    out.add(line.toString());
                    line.setLength(0);
                }
                // the word alone: break it if it is wider than a line
                while (m.width(word) > maxW && word.length() > 1) {
                    int cut = fit(word, maxW, m);
                    if (maxLines > 0 && out.size() == maxLines - 1) {
                        rest = new StringBuilder(word);
                        if (w + 1 < words.length) rest.append(' ')
                            .append(join(words, w + 1));
                        for (int q = p + 1; q < paras.length; q++) rest.append(' ')
                            .append(paras[q].trim());
                        break outer;
                    }
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                line.append(word);
            }
            if (line.length() > 0) {
                if (maxLines > 0 && out.size() == maxLines - 1 && p < paras.length - 1 && hasText(paras, p + 1)) {
                    rest = new StringBuilder(line);
                    for (int q = p + 1; q < paras.length; q++) rest.append(' ')
                        .append(paras[q].trim());
                    break;
                }
                if (maxLines > 0 && out.size() >= maxLines) break;
                out.add(line.toString());
            }
        }
        if (rest != null) out.add(ellipsize(rest.toString().trim(), maxW, m));
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    private static boolean hasText(String[] paras, int from) {
        for (int i = from; i < paras.length; i++) if (!paras[i].trim().isEmpty()) return true;
        return false;
    }

    private static String join(String[] words, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < words.length; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(words[i]);
        }
        return b.toString();
    }

    /** Longest prefix length (>= 1) of {@code s} that fits in {@code maxW}. */
    private static int fit(String s, float maxW, Measure m) {
        int lo = 1, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (m.width(s.substring(0, mid)) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
