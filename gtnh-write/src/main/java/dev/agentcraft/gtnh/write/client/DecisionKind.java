package dev.agentcraft.gtnh.write.client;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the decision screen offers for an open decision. This is a DISPLAY decision only: it mirrors the
 * control service's classification (hermes_control/classify.py) so the footer shows the right controls, but
 * the control service classifies again from the board data and refuses what it does not allow. A wrong
 * guess here can therefore only show a button that gets refused (or hide one), never allow an action.
 *
 * <p>
 * Lopsided on purpose, like the original: any sign of "permission" wins, and anything not recognised is
 * {@link Kind#UNKNOWN} (read-only), never a question. No Minecraft types (pure check).
 */
public final class DecisionKind {

    public enum Kind {
        /** pick an offered choice, or send text when no choices are offered */
        QUESTION,
        /** only "Deny" (+ a note) from the game; approving happens outside the game */
        PERMISSION,
        /** demo-ready / review style: read-only here */
        HANDOFF,
        /** not recognised: read-only */
        UNKNOWN
    }

    public static final String WITHHELD = "[withheld: mentions personal notes]";
    /** the only choice a permission halt may be answered with */
    public static final String DENY = "Deny";

    private static final Pattern PREFIX = Pattern.compile("^\\s*(QUESTION|PERMISSION|DEMO[ -]READY|REVISE|HELD|NEEDS[ -]INPUT)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDOFF_WORDS = Pattern.compile(
        "demo[ -]?ready|send[ -]to[ -]review|ready (?:for|to) review|request(?:ing)? review|hand[ -]?off|handing off|\\brevise\\b|\\bheld\\b");
    private static final Pattern PERMISSION_WORDS = Pattern.compile("permission|\\bapprov|\\bauthori[sz]|\\bgrant\\b|\\ballow\\b|\\bsudo\\b");
    private static final Pattern APPROVE_LIKE = Pattern.compile("approv|allow|grant|authori[sz]|permit");

    private DecisionKind() {}

    /** NFKC, control and format characters removed, lower case, single spaces: for comparison only. */
    static String norm(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int type = Character.getType(c);
            if (type == Character.FORMAT || (type == Character.CONTROL && c != ' ' && c != '\t' && c != '\n')) continue;
            b.append(Character.isWhitespace(c) || type == Character.SPACE_SEPARATOR ? ' ' : c);
        }
        return b.toString()
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    /**
     * @param adapterKind the read adapter's kind ("question" or "permission")
     * @param question    the question text as the screen shows it (it keeps its QUESTION/PERMISSION prefix)
     * @param options     the offered choices
     */
    public static Kind classify(String adapterKind, String question, List<String> options) {
        String q = question == null ? "" : question;
        if (q.contains(WITHHELD)) return Kind.UNKNOWN;
        String head = norm(q);
        if (head.length() > 240) head = head.substring(0, 240);
        String cleaned = Normalizer.normalize(q, Normalizer.Form.NFKC)
            .replaceAll("\\p{Cf}", "");
        java.util.regex.Matcher m = PREFIX.matcher(cleaned);
        String prefix = m.find() ? m.group(1).toUpperCase(Locale.ROOT).replace('-', ' ') : "";
        boolean approveOption = false;
        if (options != null) for (String o : options) if (APPROVE_LIKE.matcher(norm(o)).find()) approveOption = true;
        if ("permission".equals(adapterKind) || "PERMISSION".equals(prefix) || PERMISSION_WORDS.matcher(head).find() || approveOption) return Kind.PERMISSION;
        if ("DEMO READY".equals(prefix) || "REVISE".equals(prefix) || "HELD".equals(prefix) || HANDOFF_WORDS.matcher(head).find()) return Kind.HANDOFF;
        if ("QUESTION".equals(prefix) || "NEEDS INPUT".equals(prefix)) return Kind.QUESTION;
        return Kind.UNKNOWN;
    }

    /** true when the options contain exactly the word Deny (what the game may send for a permission halt). */
    public static boolean offersDeny(List<String> options) {
        if (options == null) return false;
        for (String o : options) if (DENY.equals(o)) return true;
        return false;
    }

    /** The read-only sentence for decisions the game may not answer. */
    public static String readOnlyNote(Kind k) {
        switch (k) {
            case PERMISSION:
                return "Permission halt: Deny only here. Approve outside the game.";
            case HANDOFF:
                return "Hand-off decision: approve outside the game.";
            default:
                return "Read-only here: approve outside the game.";
        }
    }
}
