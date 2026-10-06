package dev.agentcraft.gtnh.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small JSON reader/writer for the edit tool's files and messages (objects are insertion-ordered
 * {@code Map<String, Object>}, arrays {@code List<Object>}, numbers {@code Double}, plus String,
 * Boolean and null). Pure Java on purpose: the layout engine and its checks run with no Minecraft
 * or Gson on the classpath. Strict: trailing garbage, unterminated strings, nesting deeper than 64
 * and documents longer than the caller's limit are errors.
 */
public final class Json {

    public static final class ParseException extends Exception {

        public ParseException(String m) {
            super(m);
        }
    }

    private final String s;
    private int i, depth;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws ParseException {
        if (text == null) throw new ParseException("no text");
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != text.length()) throw new ParseException("trailing characters at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new ParseException("not a JSON object");
        return (Map<String, Object>) o;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object value() throws ParseException {
        if (i >= s.length()) throw new ParseException("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new ParseException("unexpected '" + c + "' at " + i);
        }
    }

    private void word(String w) throws ParseException {
        if (!s.startsWith(w, i)) throw new ParseException("bad literal at " + i);
        i += w.length();
    }

    private Map<String, Object> object() throws ParseException {
        if (++depth > 64) throw new ParseException("nested too deep");
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            depth--;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw new ParseException("expected a key at " + i);
            String k = string();
            ws();
            if (peek() != ':') throw new ParseException("expected ':' at " + i);
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == ',') continue;
            if (c == '}') break;
            throw new ParseException("expected ',' or '}' at " + (i - 1));
        }
        depth--;
        return m;
    }

    private List<Object> array() throws ParseException {
        if (++depth > 64) throw new ParseException("nested too deep");
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            depth--;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ',') continue;
            if (c == ']') break;
            throw new ParseException("expected ',' or ']' at " + (i - 1));
        }
        depth--;
        return l;
    }

    private char peek() throws ParseException {
        if (i >= s.length()) throw new ParseException("unexpected end");
        return s.charAt(i);
    }

    private String string() throws ParseException {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw new ParseException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) throw new ParseException("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        b.append('\n');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) throw new ParseException("bad unicode escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad unicode escape");
                        }
                        i += 4;
                        break;
                    default:
                        b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() throws ParseException {
        int st = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        try {
            return Double.valueOf(s.substring(st, i));
        } catch (NumberFormatException e) {
            throw new ParseException("bad number at " + st);
        }
    }

    // ---- writing ------------------------------------------------------------------------------

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, -1, 0);
        return b.toString();
    }

    /** Human-readable: two-space indent, short arrays of numbers on one line. */
    public static String pretty(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 2, 0);
        return b.append('\n')
            .toString();
    }

    private static void nl(StringBuilder b, int indent, int level) {
        if (indent < 0) return;
        b.append('\n');
        for (int k = 0; k < indent * level; k++) b.append(' ');
    }

    private static void write(StringBuilder b, Object v, int indent, int level) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof Number) {
            b.append(num(((Number) v).doubleValue()));
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, indent, level + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(indent < 0 ? ":" : ": ");
                write(b, e.getValue(), indent, level + 1);
            }
            nl(b, indent, level);
            b.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            boolean flat = indent < 0 || l.size() <= 6 && allScalar(l);
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(flat && indent >= 0 ? ", " : ",");
                if (!flat) nl(b, indent, level + 1);
                write(b, l.get(k), flat ? -1 : indent, level + 1);
            }
            if (!flat) nl(b, indent, level);
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static boolean allScalar(List<?> l) {
        for (Object o : l) if (o instanceof Map || o instanceof List) return false;
        return true;
    }

    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = String.format(Locale.ROOT, "%.4f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x7F || c == '\u00a7') b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ---- typed getters ------------------------------------------------------------------------

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String ? (String) v : v instanceof Number || v instanceof Boolean ? String.valueOf(v) : def;
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static int integer(Map<String, Object> m, String k, int def) {
        double d = num(m, k, Double.NaN);
        return Double.isNaN(d) ? def : (int) Math.round(d);
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : null;
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put(String.valueOf(kv[k]), kv[k + 1]);
        return m;
    }
}
