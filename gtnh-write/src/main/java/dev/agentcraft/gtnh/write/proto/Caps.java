package dev.agentcraft.gtnh.write.proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The capability table of docs/action-protocol.md section 4.1: names, tiers, confirm flags, the
 * typed arguments of each and the default rate limits. There is nothing else: a name that is not
 * here has no code path.
 */
public final class Caps {

    public static final class Cap {

        public final String name;
        public final int tier;
        public final boolean confirm;

        Cap(String name, int tier, boolean confirm) {
            this.name = name;
            this.tier = tier;
            this.confirm = confirm;
        }
    }

    /** One rate rule: at most {@code max} per {@code windowMs}, counted per {@code scope} ("" = overall, else an args key). */
    public static final class Limit {

        public final String scope;
        public final int max;
        public final long windowMs;

        Limit(String scope, int max, long windowMs) {
            this.scope = scope;
            this.max = max;
            this.windowMs = windowMs;
        }
    }

    private static final long MIN = 60_000L, HOUR = 3_600_000L;
    private static final Map<String, Cap> TABLE = new LinkedHashMap<>();
    private static final Map<String, List<Limit>> LIMITS = new LinkedHashMap<>();

    private static void def(String name, int tier, boolean confirm, Limit... limits) {
        TABLE.put(name, new Cap(name, tier, confirm));
        List<Limit> l = new ArrayList<>();
        Collections.addAll(l, limits);
        LIMITS.put(name, Collections.unmodifiableList(l));
    }

    static {
        def("decision.answer", 1, false, new Limit("", 20, HOUR));
        def("card.create", 1, false, new Limit("", 20, HOUR));
        def("card.edit", 1, false, new Limit("", 60, HOUR));
        def("agent.chat", 1, false, new Limit("", 10, MIN));
        def("agent.ask", 1, false, new Limit("", 10, MIN));
        def("card.dispatch", 2, true, new Limit("", 6, HOUR), new Limit("card", 1, 10 * MIN));
        def("service.restart", 2, false, new Limit("service", 1, 10 * MIN), new Limit("", 6, HOUR));
        def("cron.run", 2, false, new Limit("job", 1, 5 * MIN), new Limit("", 12, HOUR));
    }

    private Caps() {}

    public static Cap get(String name) {
        return TABLE.get(name);
    }

    public static List<String> names() {
        return new ArrayList<>(TABLE.keySet());
    }

    public static List<Limit> limits(String name) {
        List<Limit> l = LIMITS.get(name);
        return l == null ? Collections.<Limit>emptyList() : l;
    }

    /** true when the string has a control character (newline and tab allowed when {@code multiline}). */
    static boolean hasControl(String s, boolean multiline) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && !(multiline && (c == '\n' || c == '\t' || c == '\r'))) return true;
            if (c == 0x7f) return true;
        }
        return false;
    }

    private static String word(Fields f, String key, int min, int max, boolean multiline) throws Fields.Bad {
        String s = f.str(key, min, max);
        if (hasControl(s, multiline)) throw new Fields.Bad("args: " + key + " has control characters");
        return s;
    }

    /**
     * Validates the arguments a player sent for {@code cap}: every key typed and capped, unknown keys
     * and wrong types refused. Returns a clean map in contract order. Throws {@link Fields.Bad}.
     */
    public static Map<String, Object> validate(String cap, Map<String, Object> args) throws Fields.Bad {
        Cap c = TABLE.get(cap);
        if (c == null) throw new Fields.Bad("unknown capability");
        Fields f = new Fields(args, "args");
        Map<String, Object> out = new LinkedHashMap<>();
        switch (cap) {
            case "decision.answer": {
                out.put("card", word(f, "card", 1, 64, false));
                out.put("decision", word(f, "decision", 1, 16, false));
                boolean any = false;
                if (f.has("choice")) {
                    out.put("choice", word(f, "choice", 1, 120, false));
                    any = true;
                }
                if (f.has("text")) {
                    out.put("text", word(f, "text", 1, 2000, true));
                    any = true;
                }
                if (!any) throw new Fields.Bad("args: choice or text required");
                break;
            }
            case "card.create": {
                out.put("board", word(f, "board", 1, 64, false));
                out.put("title", word(f, "title", 1, 120, false));
                if (f.has("body")) out.put("body", word(f, "body", 0, 4000, true));
                if (f.has("priority")) out.put("priority", Long.valueOf(f.integer("priority", 0, 100)));
                break;
            }
            case "card.edit": {
                out.put("card", word(f, "card", 1, 64, false));
                if (f.has("comment")) {
                    out.put("comment", word(f, "comment", 1, 2000, true));
                } else {
                    boolean any = false;
                    if (f.has("title")) {
                        out.put("title", word(f, "title", 1, 120, false));
                        any = true;
                    }
                    if (f.has("body")) {
                        out.put("body", word(f, "body", 0, 4000, true));
                        any = true;
                    }
                    if (f.has("priority")) {
                        out.put("priority", Long.valueOf(f.integer("priority", 0, 100)));
                        any = true;
                    }
                    if (!any) throw new Fields.Bad("args: comment, or at least one of title, body, priority");
                }
                break;
            }
            case "agent.chat":
                out.put("agent", word(f, "agent", 1, 64, false));
                out.put("conversation", word(f, "conversation", 1, 64, false));
                out.put("text", word(f, "text", 1, 2000, true));
                break;
            case "agent.ask":
                out.put("agent", word(f, "agent", 1, 64, false));
                out.put("text", word(f, "text", 1, 2000, true));
                break;
            case "card.dispatch":
                out.put("card", word(f, "card", 1, 64, false));
                out.put("board", word(f, "board", 1, 64, false));
                out.put("profile", word(f, "profile", 1, 64, false));
                break;
            case "service.restart":
                out.put("service", word(f, "service", 1, 64, false));
                break;
            case "cron.run":
                out.put("job", word(f, "job", 1, 64, false));
                break;
            default:
                throw new Fields.Bad("unknown capability");
        }
        f.done();
        return out;
    }
}
