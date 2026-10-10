package dev.agentcraft.gtnh.write.proto;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds signed game -> control frames. The id, nonce, ts, session and dir are always filled here,
 * never taken from a player: callers pass only the body fields.
 */
public final class Sender {

    private final byte[] key;
    private final Clock clock;
    private final SecureRandom random;
    private final String idPrefix;
    private long counter;
    private volatile String session = "";

    public Sender(byte[] key, Clock clock, SecureRandom random) {
        this.key = key.clone();
        this.clock = clock;
        this.random = random;
        byte[] b = new byte[4];
        random.nextBytes(b);
        this.idPrefix = "w" + Hex.encode(b);
    }

    public void setSession(String s) {
        this.session = s == null ? "" : s;
    }

    public String session() {
        return session;
    }

    /** A request id unique to this process run: wXXXXXXXX-n. */
    public synchronized String nextId() {
        return idPrefix + "-" + Long.toString(++counter, 36);
    }

    public String nonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Hex.encode(b);
    }

    /** Payload JSON string for {@code type}: common fields first, then the body in the order given. */
    public String payload(String type, String id, Map<String, Object> body) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("session", session);
        p.put("dir", "g2c");
        p.put("id", id);
        p.put("nonce", nonce());
        p.put("ts", Long.valueOf(clock.now()));
        p.putAll(body);
        return StrictJson.write(p);
    }

    /** The wire text of a signed frame. */
    public String frame(String type, String id, Map<String, Object> body) {
        return Frames.encode(key, type, payload(type, id, body));
    }
}
