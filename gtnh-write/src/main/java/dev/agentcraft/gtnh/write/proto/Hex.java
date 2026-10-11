package dev.agentcraft.gtnh.write.proto;

/** Lowercase hex helpers. */
public final class Hex {

    private static final char[] D = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = D[(b[i] >> 4) & 15];
            c[i * 2 + 1] = D[b[i] & 15];
        }
        return new String(c);
    }

    /** true when {@code s} is exactly {@code len} characters of [0-9a-f]. */
    public static boolean isLowerHex(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Decodes an even-length hex string of either case; throws IllegalArgumentException otherwise. */
    public static byte[] decode(String s) {
        if (s == null || s.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0 || s.charAt(i * 2) > 127 || s.charAt(i * 2 + 1) > 127) throw new IllegalArgumentException("not hex");
            out[i] = (byte) (hi * 16 + lo);
        }
        return out;
    }
}
