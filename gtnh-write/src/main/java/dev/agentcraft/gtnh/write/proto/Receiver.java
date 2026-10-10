package dev.agentcraft.gtnh.write.proto;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Receiver checks of docs/action-protocol.md section 2.2 for control -> game frames, in order:
 * size, outer shape, signature (constant time), payload shape (common fields + the type's own,
 * unknown / duplicate keys and wrong types refused), {@code type} equality, session, direction,
 * {@code ts} within 60 s of the clock and not before this process started, nonce not seen (kept
 * until ts + 60 s, never evicted early, at most 4096 live: new frames are refused when full), and a
 * latched refusal of everything once the clock has moved backwards by more than 60 s.
 */
public final class Receiver {

    public static final long WINDOW_MS = 60_000L;
    public static final int NONCE_CAP = 4096;

    private final byte[] key;
    private final Clock clock;
    private final long startMs;
    private final Map<String, Long> nonces = new HashMap<>(); // nonce -> expiry (ts + window)
    private String session; // null until the challenge is accepted
    private long maxNow;
    private boolean clockFault;

    public Receiver(byte[] key, Clock clock, long processStartMs) {
        this.key = key.clone();
        this.clock = clock;
        this.startMs = processStartMs;
        this.maxNow = clock.now();
    }

    /** Forget the session (a new connection): the next frame must be an action.challenge. */
    public synchronized void resetSession() {
        session = null;
    }

    public synchronized String session() {
        return session;
    }

    public synchronized boolean clockFault() {
        checkClock();
        return clockFault;
    }

    public synchronized int liveNonces() {
        purge(clock.now());
        return nonces.size();
    }

    private void checkClock() {
        long now = clock.now();
        if (now < maxNow - WINDOW_MS) clockFault = true;
        if (now > maxNow) maxNow = now;
    }

    private void purge(long now) {
        for (Iterator<Map.Entry<String, Long>> it = nonces.entrySet().iterator(); it.hasNext();) {
            if (now > it.next().getValue().longValue()) it.remove();
        }
    }

    /** Verifies one text message. On success returns the typed message; otherwise throws Frames.Reject. */
    public synchronized Msg accept(String text) throws Frames.Reject {
        checkClock();
        if (clockFault) throw new Frames.Reject("clock", "the clock moved backwards; restart the server", false, true);
        Frames.Opened o = Frames.open(key, text); // size, outer shape, signature
        Map<String, Object> p;
        try {
            p = StrictJson.parseObject(o.payload);
        } catch (StrictJson.ParseException e) {
            throw new Frames.Reject("payload", e.getMessage(), true, false);
        }
        String type, sess, dir, id, nonce;
        long ts;
        Msg msg;
        try {
            Fields f = new Fields(p, "payload");
            type = f.str("type", 1, 64);
            sess = f.hex("session", 32);
            dir = f.str("dir", 1, 3);
            id = f.id("id");
            nonce = f.hex("nonce", 32);
            ts = f.integer("ts", 0, Long.MAX_VALUE);
            if (!type.equals(o.type)) throw new Fields.Bad("payload: type differs from the outer type");
            if (!dir.equals("c2g")) throw new Fields.Bad("payload: dir must be c2g");
            msg = Msg.parseBody(type, f);
        } catch (Fields.Bad e) {
            throw new Frames.Reject("payload", e.getMessage(), true, false);
        }
        if (session == null) {
            if (!"action.challenge".equals(type)) throw new Frames.Reject("session", "first frame must be action.challenge", true, true);
        } else if (!session.equals(sess)) {
            throw new Frames.Reject("session", "wrong session", true, false);
        }
        long now = clock.now();
        if (Math.abs(now - ts) > WINDOW_MS) throw new Frames.Reject("ts", "outside the 60 s window", true, false);
        if (ts < startMs) throw new Frames.Reject("ts", "before this process started", true, false);
        purge(now);
        if (nonces.containsKey(nonce)) throw new Frames.Reject("replay", "nonce already seen", true, false);
        if (nonces.size() >= NONCE_CAP) throw new Frames.Reject("nonce-cache-full", "4096 live nonces", true, false);
        nonces.put(nonce, Long.valueOf(ts + WINDOW_MS));
        if (session == null) session = sess;
        msg.type = type;
        msg.session = sess;
        msg.id = id;
        msg.nonce = nonce;
        msg.ts = ts;
        return msg;
    }
}
