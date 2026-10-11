package dev.agentcraft.gtnh.write.proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON for the control wire: refuses duplicate object keys (at any depth), {@code null}
 * (no field of the contract is nullable), trailing garbage, raw control characters in strings,
 * numbers outside the JSON grammar, integers that do not fit a long and nesting past
 * {@link #MAX_DEPTH}. Values: Map (insertion order), List, String, Long (a JSON integer without
 * fraction or exponent), Double (any other number), Boolean. Pure Java, no Minecraft.
 */
public final class StrictJson {

    public static final int MAX_DEPTH = 8;

    public static final class ParseException extends Exception {

        private static final long serialVersionUID = 1L;

        public ParseException(String m) {
            super(m);
        }
    }

    private final String s;
    private int i;

    private StrictJson(String s) {
        this.s = s;
    }

    /** Parses a document whose top level must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new ParseException("not an object");
        return (Map<String, Object>) v;
    }

    public static Object parse(String text) throws ParseException {
        if (text == null) throw new ParseException("null text");
        StrictJson p = new StrictJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw new ParseException("trailing characters");
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object value(int depth) throws ParseException {
        if (depth > MAX_DEPTH) throw new ParseException("nested too deep");
        if (i >= s.length()) throw new ParseException("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                throw new ParseException("null is not allowed");
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new ParseException("unexpected character at " + i);
        }
    }

    private Object word(String w, Object v) throws ParseException {
        if (!s.startsWith(w, i)) throw new ParseException("bad literal at " + i);
        i += w.length();
        return v;
    }

    private Map<String, Object> object(int depth) throws ParseException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw new ParseException("object key expected at " + i);
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw new ParseException("':' expected at " + i);
            i++;
            ws();
            Object v = value(depth + 1);
            if (m.containsKey(k)) throw new ParseException("duplicate key");
            m.put(k, v);
            ws();
            if (i >= s.length()) throw new ParseException("unexpected end in object");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new ParseException("',' or '}' expected at " + (i - 1));
        }
    }

    private List<Object> array(int depth) throws ParseException {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw new ParseException("unexpected end in array");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new ParseException("',' or ']' expected at " + (i - 1));
        }
    }

    private String string() throws ParseException {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new ParseException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new ParseException("control character in string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw new ParseException("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    b.append(e);
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'n':
                    b.append('\n');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'u': {
                    if (i + 4 > s.length()) throw new ParseException("short \\u escape");
                    int cp = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit(s.charAt(i + k), 16);
                        if (d < 0) throw new ParseException("bad \\u escape");
                        cp = cp * 16 + d;
                    }
                    i += 4;
                    b.append((char) cp);
                    break;
                }
                default:
                    throw new ParseException("bad escape");
            }
        }
    }

    private Object number() throws ParseException {
        int st = i;
        if (s.charAt(i) == '-') i++;
        if (i >= s.length()) throw new ParseException("bad number");
        if (s.charAt(i) == '0') {
            i++;
        } else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') {
            while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) i++;
        } else {
            throw new ParseException("bad number");
        }
        boolean frac = false;
        if (i < s.length() && s.charAt(i) == '.') {
            frac = true;
            i++;
            int d = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == d) throw new ParseException("bad fraction");
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            frac = true;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int d = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == d) throw new ParseException("bad exponent");
        }
        String t = s.substring(st, i);
        try {
            if (frac) return Double.valueOf(t);
            return Long.valueOf(t);
        } catch (NumberFormatException e) {
            throw new ParseException("number out of range");
        }
    }

    // ---- writer -------------------------------------------------------------------------------

    /** Compact JSON for Map / List / String / Long / Integer / Boolean values. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else {
            throw new IllegalArgumentException("cannot write " + (v == null ? "null" : v.getClass().getName()));
        }
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
                    if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
