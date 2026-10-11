package dev.agentcraft.gtnh.write.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The small decisions the write screens make before they send anything (pure Java, checked in
 * dev/tests/ClientCheck.java). None of this is security: the server validates every request again.
 * It only keeps the screens from sending requests that are certain to be refused, and from showing
 * a confusing board or card id.
 */
public final class FormLogic {

    private FormLogic() {}

    /** The wall's card id may be {@code <board>:<id>} when two boards share an id; Hermes wants the plain id. */
    public static String cardId(String wallId, String board) {
        if (wallId == null) return "";
        if (board != null && !board.isEmpty() && wallId.startsWith(board + ":")) return wallId.substring(board.length() + 1);
        return wallId;
    }

    /** The board to dispatch/create on: the card's own board if offered, else the only offered one, else null. */
    public static String pickBoard(String taskBoard, List<String> offered) {
        if (offered == null || offered.isEmpty()) return null;
        if (taskBoard != null && offered.contains(taskBoard)) return taskBoard;
        if (taskBoard == null || taskBoard.isEmpty()) return offered.size() == 1 ? offered.get(0) : null;
        return null;
    }

    /** "" -> null (not given); a number 0..100 -> value; anything else -> -1 (invalid). */
    public static int parsePriority(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return Integer.MIN_VALUE;
        if (t.length() > 3) return -1;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) < '0' || t.charAt(i) > '9') return -1;
        int v = Integer.parseInt(t);
        return v > 100 ? -1 : v;
    }

    /** Result of {@link #edit}: either an error or the fields to send (null = unchanged). */
    public static final class Edit {

        public String error, title, body;
        public Integer priority;
    }

    /**
     * Only fields that differ from the card are sent; at least one must. The title is 1..120 chars, the body
     * at most 4000, the priority 0..100.
     */
    public static Edit edit(String oldTitle, String oldBody, int oldPriority, String title, String body, String priority) {
        Edit e = new Edit();
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.trim();
        if (t.isEmpty()) {
            e.error = "The title cannot be empty.";
            return e;
        }
        if (t.length() > 120) {
            e.error = "The title is longer than 120 characters.";
            return e;
        }
        if (b.length() > 4000) {
            e.error = "The body is longer than 4000 characters.";
            return e;
        }
        int p = parsePriority(priority);
        if (p == -1) {
            e.error = "Priority must be a number from 0 to 100.";
            return e;
        }
        if (!t.equals(oldTitle == null ? "" : oldTitle.trim())) e.title = t;
        if (!b.equals(oldBody == null ? "" : oldBody.trim())) e.body = b;
        if (p != Integer.MIN_VALUE && p != oldPriority) e.priority = Integer.valueOf(p);
        if (e.title == null && e.body == null && e.priority == null) e.error = "Nothing changed.";
        // the control service refuses a priority-only edit: edited text must carry the game tag (review r1 R5)
        else if (e.title == null && e.body == null) e.error = "A priority change needs a title or details change with it.";
        return e;
    }

    /** New card checks; returns an error text or null. */
    public static String createError(String board, String title, String body, String priority) {
        if (board == null || board.isEmpty()) return "Pick a board.";
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return "Give the card a title.";
        if (t.length() > 120) return "The title is longer than 120 characters.";
        if (body != null && body.length() > 4000) return "The body is longer than 4000 characters.";
        if (parsePriority(priority) == -1) return "Priority must be a number from 0 to 100.";
        return null;
    }

    /** Comment checks (1..2000 chars). */
    public static String commentError(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "Write a comment first.";
        if (t.length() > 2000) return "The comment is longer than 2000 characters.";
        return null;
    }

    /** A stable conversation id for a chat window (the control side keys the thread on it; up to 64 chars of the id charset). */
    public static String conversationId(String agentId) {
        StringBuilder b = new StringBuilder("mc-");
        String a = agentId == null ? "" : agentId;
        for (int i = 0; i < a.length() && b.length() < 64; i++) {
            char c = a.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '-');
        }
        return b.toString();
    }

    /** Whole seconds left for a countdown (never negative, rounds up so "1 s" shows until the last moment). */
    public static int secondsLeft(long msLeft) {
        return (int) Math.max(0, (msLeft + 999) / 1000);
    }

    /** Fraction of the 60 s window that is left, 0..1. */
    public static float fractionLeft(long msLeft, long totalMs) {
        if (totalMs <= 0) return 0;
        return Math.max(0, Math.min(1, (float) msLeft / totalMs));
    }

    /** The first {@code maxLines} non-empty lines of a body (the Confirm screen shows these). */
    public static List<String> firstLines(String body, int maxLines) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        for (String l : body.split("\\r?\\n")) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            out.add(t);
            if (out.size() >= maxLines) break;
        }
        return out;
    }

    /** One-line text for a result ("applied (dry run: nothing ran)", "refused: why"). */
    public static String resultLine(String status, String error, boolean dryRun) {
        String s = status == null ? "" : status;
        switch (s) {
            case "applied":
                return dryRun ? "applied (dry run: nothing ran)" : "applied";
            case "queued":
                return "queued";
            case "prompted":
                return "waiting for your confirmation";
            case "cancelled":
                return "cancelled" + (error == null || error.isEmpty() ? "" : ": " + error);
            case "unknown":
                return "unknown, check outside the game" + (error == null || error.isEmpty() ? "" : " (" + error + ")");
            default:
                return "refused" + (error == null || error.isEmpty() ? "" : ": " + error);
        }
    }
}
