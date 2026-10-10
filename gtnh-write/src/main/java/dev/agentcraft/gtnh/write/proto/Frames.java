package dev.agentcraft.gtnh.write.proto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The outer frame of docs/action-protocol.md section 2: exactly
 * {@code {"v":1,"type":..,"payload":"<json string>","sig":"<64 lowercase hex>"}}, where sig is
 * HMAC-SHA256 over the UTF-8 bytes of the payload string, lowercase hex. The receiver checks the
 * signature first, with a constant-time compare ({@link MessageDigest#isEqual}), before it parses
 * the payload.
 */
public final class Frames {

    public static final int MAX_BYTES = 16384;

    private Frames() {}

    /** Why a frame was refused; {@code code} is a short stable word used in audit lines and tests. */
    public static final class Reject extends Exception {

        private static final long serialVersionUID = 1L;
        public final String code;
        /** true when the signature was verified (so the sender holds the key). */
        public final boolean authentic;
        /** true = the connection must be closed (size, handshake violations). */
        public final boolean fatal;

        public Reject(String code, String detail, boolean authentic, boolean fatal) {
            super(code + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            this.code = code;
            this.authentic = authentic;
            this.fatal = fatal;
        }
    }

    public static String sign(byte[] key, String payload) {
        try {
            return Hex.encode(hmac(key, payload));
        } catch (GeneralSecurityExceptionWrapper e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class GeneralSecurityExceptionWrapper extends Exception {

        private static final long serialVersionUID = 1L;

        GeneralSecurityExceptionWrapper(Throwable t) {
            super(t);
        }
    }

    private static byte[] hmac(byte[] key, String payload) throws GeneralSecurityExceptionWrapper {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new GeneralSecurityExceptionWrapper(e);
        }
    }

    /** Constant-time check of a 64-lowercase-hex signature against the payload string. */
    public static boolean verify(byte[] key, String payload, String sigHex) {
        if (sigHex == null || !Hex.isLowerHex(sigHex, 64)) return false;
        try {
            byte[] expected = hmac(key, payload);
            byte[] given = Hex.decode(sigHex);
            return MessageDigest.isEqual(expected, given);
        } catch (GeneralSecurityExceptionWrapper e) {
            return false;
        }
    }

    /** The wire text of a frame. */
    public static String encode(byte[] key, String type, String payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("v", Long.valueOf(1));
        o.put("type", type);
        o.put("payload", payload);
        o.put("sig", sign(key, payload));
        return StrictJson.write(o);
    }

    /** Result of {@link #open}: the authenticated payload string and its outer type. */
    public static final class Opened {

        public final String type, payload;

        Opened(String type, String payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /**
     * Steps 1-3 of section 2.2: size, outer shape, signature. Throws {@link Reject} (never returns
     * an unauthenticated payload).
     */
    public static Opened open(byte[] key, String text) throws Reject {
        if (text == null) throw new Reject("shape", "no text", false, false);
        if (text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new Reject("size", "message over " + MAX_BYTES + " bytes", false, true);
        }
        Map<String, Object> o;
        try {
            o = StrictJson.parseObject(text);
        } catch (StrictJson.ParseException e) {
            throw new Reject("shape", "outer: " + e.getMessage(), false, false);
        }
        if (o.size() != 4 || !o.containsKey("v") || !o.containsKey("type") || !o.containsKey("payload") || !o.containsKey("sig")) {
            throw new Reject("shape", "outer object must hold exactly v, type, payload, sig", false, false);
        }
        Object v = o.get("v"), t = o.get("type"), p = o.get("payload"), s = o.get("sig");
        if (!(v instanceof Long) || ((Long) v).longValue() != 1L) throw new Reject("shape", "v must be the integer 1", false, false);
        if (!(t instanceof String) || ((String) t).isEmpty() || ((String) t).length() > 64) throw new Reject("shape", "type", false, false);
        if (!(p instanceof String)) throw new Reject("shape", "payload must be a string", false, false);
        if (!(s instanceof String) || !Hex.isLowerHex((String) s, 64)) throw new Reject("shape", "sig must be 64 lowercase hex", false, false);
        if (!verify(key, (String) p, (String) s)) throw new Reject("signature", "", false, false);
        return new Opened((String) t, (String) p);
    }
}
