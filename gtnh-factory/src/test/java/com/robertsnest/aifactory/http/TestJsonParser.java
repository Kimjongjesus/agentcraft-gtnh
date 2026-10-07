package com.robertsnest.aifactory.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict, independent JSON parser for tests.
 *
 * <p>
 * Deliberately written without reference to {@link Json}: the point is to
 * prove the writer's output is well-formed JSON by a reader that shares none
 * of its assumptions. Objects become {@link LinkedHashMap}, arrays
 * {@link ArrayList}, numbers {@link Long} when integral else {@link Double},
 * and {@code null} stays null. Any deviation from RFC 8259 throws.
 */
final class TestJsonParser {

    private final String s;
    private int i;

    private TestJsonParser(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        TestJsonParser p = new TestJsonParser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing data at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("not an object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("not an object: " + v);
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object v) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("not an array: " + v);
        }
        return (List<Object>) v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        return s.charAt(i);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + i + " got '" + peek() + "'");
        }
        i++;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                return number();
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("bad literal at " + i);
        }
        i += word.length();
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        ws();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            Object v = value();
            if (out.containsKey(key)) {
                throw new IllegalArgumentException("duplicate key " + key);
            }
            out.put(key, v);
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                return out;
            }
            throw new IllegalArgumentException("expected , or } at " + i);
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> out = new ArrayList<Object>();
        ws();
        if (peek() == ']') {
            i++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                return out;
            }
            throw new IllegalArgumentException("expected , or ] at " + i);
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            i++;
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("raw control character in string at " + (i - 1));
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw new IllegalArgumentException("short \\u escape");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                frac = true;
                i++;
            } else {
                break;
            }
        }
        String text = s.substring(start, i);
        if (text.isEmpty() || "-".equals(text)) {
            throw new IllegalArgumentException("bad number at " + start);
        }
        if (text.startsWith("0") && text.length() > 1 && Character.isDigit(text.charAt(1))) {
            throw new IllegalArgumentException("leading zero at " + start);
        }
        return frac ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
    }
}
