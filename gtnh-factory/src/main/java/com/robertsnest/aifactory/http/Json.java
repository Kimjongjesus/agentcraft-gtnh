package com.robertsnest.aifactory.http;

/**
 * Minimal JSON writer for the fixed shapes this mod emits.
 *
 * <p>
 * <b>Why not Gson.</b> Gson is present inside the 1.7.10 server jar, but it
 * is unshaded and version-pinned by Mojang, and it is not on this mod's compile
 * classpath. Depending on it would mean either shading a second copy into the
 * jar or relying on a runtime coincidence. The payloads here are a handful of
 * flat objects and arrays, so a correct escaper is smaller than the dependency
 * argument.
 *
 * <p>
 * Escaping is the part that actually matters: item and machine names come
 * from arbitrary mods and routinely contain quotes, backslashes and section
 * signs, any of which would produce malformed JSON if passed through raw.
 */
public final class Json {

    private final StringBuilder out = new StringBuilder(256);
    private boolean needsComma;

    public Json object() {
        separate();
        out.append('{');
        needsComma = false;
        return this;
    }

    /** Opens a nested object under a key. Pair with {@link #endObject()}. */
    public Json objectField(String key) {
        field(key);
        out.append('{');
        needsComma = false;
        return this;
    }

    public Json endObject() {
        out.append('}');
        needsComma = true;
        return this;
    }

    public Json array(String key) {
        field(key);
        out.append('[');
        needsComma = false;
        return this;
    }

    public Json endArray() {
        out.append(']');
        needsComma = true;
        return this;
    }

    public Json put(String key, String value) {
        field(key);
        if (value == null) {
            out.append("null");
        } else {
            quote(value);
        }
        needsComma = true;
        return this;
    }

    public Json put(String key, long value) {
        field(key);
        out.append(value);
        needsComma = true;
        return this;
    }

    public Json put(String key, double value) {
        field(key);
        // NaN and infinities are not valid JSON; emit null rather than
        // something a parser will reject.
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append("null");
        } else {
            out.append(value);
        }
        needsComma = true;
        return this;
    }

    public Json put(String key, boolean value) {
        field(key);
        out.append(value);
        needsComma = true;
        return this;
    }

    /** Writes a tri-state: null stays null rather than becoming false. */
    public Json put(String key, Boolean value) {
        field(key);
        out.append(value == null ? "null" : value.toString());
        needsComma = true;
        return this;
    }

    /** Nullable integer: null means "not measured", never zero. */
    public Json put(String key, Long value) {
        field(key);
        out.append(value == null ? "null" : value.toString());
        needsComma = true;
        return this;
    }

    public Json put(String key, Integer value) {
        field(key);
        out.append(value == null ? "null" : value.toString());
        needsComma = true;
        return this;
    }

    public Json put(String key, Double value) {
        if (value == null) {
            field(key);
            out.append("null");
            needsComma = true;
            return this;
        }
        return put(key, value.doubleValue());
    }

    /** A bare string element inside an array. */
    public Json value(String value) {
        separate();
        if (value == null) {
            out.append("null");
        } else {
            quote(value);
        }
        needsComma = true;
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void field(String key) {
        separate();
        quote(key);
        out.append(':');
    }

    private void separate() {
        if (needsComma) {
            out.append(',');
        }
    }

    private void quote(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    // Control characters must be escaped; Minecraft's section
                    // sign and other high characters are valid UTF-8 and pass
                    // through unchanged. A lone surrogate cannot be encoded as
                    // UTF-8, so it is written as a \\u escape: the JSON stays
                    // valid and the original code unit is preserved for the
                    // consumer instead of being silently replaced with U+FFFD.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else if (Character.isSurrogate(c)) {
                        boolean paired = Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1));
                        if (paired) {
                            out.append(c)
                                .append(value.charAt(i + 1));
                            i++;
                        } else {
                            out.append(String.format("\\u%04x", Integer.valueOf(c)));
                        }
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
