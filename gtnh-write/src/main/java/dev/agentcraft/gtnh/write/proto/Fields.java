package dev.agentcraft.gtnh.write.proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict field reader over a parsed JSON object: every key must be read exactly once with the
 * right type; {@link #done()} refuses any key left over (unknown keys). Wrong type, missing key,
 * out-of-range number and over-long strings all throw {@link Bad}. Never coerces (a boolean is not
 * an integer, a string is not a number).
 */
public final class Fields {

    public static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    public static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public static final class Bad extends Exception {

        private static final long serialVersionUID = 1L;

        public Bad(String m) {
            super(m);
        }
    }

    private final Map<String, Object> m;
    private final String where;

    public Fields(Map<String, Object> src, String where) {
        this.m = new LinkedHashMap<>(src);
        this.where = where;
    }

    private Object take(String k) throws Bad {
        if (!m.containsKey(k)) throw new Bad(where + ": missing " + k);
        return m.remove(k);
    }

    public boolean has(String k) {
        return m.containsKey(k);
    }

    public String str(String k, int min, int max) throws Bad {
        Object o = take(k);
        if (!(o instanceof String)) throw new Bad(where + ": " + k + " must be a string");
        String s = (String) o;
        if (s.length() < min || s.length() > max) throw new Bad(where + ": " + k + " length");
        return s;
    }

    public String hex(String k, int len) throws Bad {
        String s = str(k, len, len);
        if (!Hex.isLowerHex(s, len)) throw new Bad(where + ": " + k + " must be " + len + " lowercase hex");
        return s;
    }

    public String id(String k) throws Bad {
        String s = str(k, 1, 64);
        if (!ID.matcher(s).matches()) throw new Bad(where + ": " + k + " is not a valid id");
        return s;
    }

    /** An id, or the empty string. */
    public String idOrEmpty(String k) throws Bad {
        String s = str(k, 0, 64);
        if (!s.isEmpty() && !ID.matcher(s).matches()) throw new Bad(where + ": " + k + " is not a valid id");
        return s;
    }

    public long integer(String k, long lo, long hi) throws Bad {
        Object o = take(k);
        if (!(o instanceof Long)) throw new Bad(where + ": " + k + " must be an integer");
        long v = ((Long) o).longValue();
        if (v < lo || v > hi) throw new Bad(where + ": " + k + " out of range");
        return v;
    }

    public boolean bool(String k) throws Bad {
        Object o = take(k);
        if (!(o instanceof Boolean)) throw new Bad(where + ": " + k + " must be a boolean");
        return ((Boolean) o).booleanValue();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> obj(String k) throws Bad {
        Object o = take(k);
        if (!(o instanceof Map)) throw new Bad(where + ": " + k + " must be an object");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public List<Object> arr(String k, int maxItems) throws Bad {
        Object o = take(k);
        if (!(o instanceof List)) throw new Bad(where + ": " + k + " must be an array");
        List<Object> l = (List<Object>) o;
        if (l.size() > maxItems) throw new Bad(where + ": " + k + " has too many entries");
        return l;
    }

    /** An array of strings, each 1..maxLen chars (or 0..maxLen when allowEmpty). */
    public List<String> strings(String k, int maxItems, int maxLen) throws Bad {
        List<Object> l = arr(k, maxItems);
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof String)) throw new Bad(where + ": " + k + " entries must be strings");
            String s = (String) o;
            if (s.isEmpty() || s.length() > maxLen) throw new Bad(where + ": " + k + " entry length");
            out.add(s);
        }
        return out;
    }

    /** Refuse unknown keys. */
    public void done() throws Bad {
        if (!m.isEmpty()) throw new Bad(where + ": unknown key " + m.keySet().iterator().next());
    }
}
