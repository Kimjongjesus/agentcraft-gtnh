package dev.agentcraft.gtnh.write.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.write.proto.Caps;
import dev.agentcraft.gtnh.write.proto.Clock;

/**
 * Game-side request limits. {@link Bucket}: per player, 1 request per 2 s, burst 3, for every
 * packet. {@link Windows}: the per-capability limits of docs/action-protocol.md section 4.1
 * (sliding windows, overall and per card / service / job). Both are plain data structures driven by
 * an injected clock.
 */
public final class RateLimits {

    private RateLimits() {}

    /** Token bucket: capacity {@code burst}, one token back every {@code refillMs}. */
    public static final class Bucket {

        public static final int BURST = 3;
        public static final long REFILL_MS = 2000L;
        private static final int MAX_PLAYERS = 256;

        private static final class B {

            double tokens = BURST;
            long last;
        }

        private final Clock clock;
        private final Map<String, B> buckets = new HashMap<>();

        public Bucket(Clock clock) {
            this.clock = clock;
        }

        /** Takes one token for {@code who}; false when the bucket is empty. */
        public synchronized boolean tryTake(String who) {
            long now = clock.now();
            B b = buckets.get(who);
            if (b == null) {
                if (buckets.size() >= MAX_PLAYERS) prune(now);
                b = new B();
                b.last = now;
                buckets.put(who, b);
            }
            long dt = Math.max(0, now - b.last);
            b.tokens = Math.min(BURST, b.tokens + (double) dt / REFILL_MS);
            b.last = now;
            if (b.tokens < 1.0) return false;
            b.tokens -= 1.0;
            return true;
        }

        private void prune(long now) {
            for (Iterator<Map.Entry<String, B>> it = buckets.entrySet().iterator(); it.hasNext();) {
                B b = it.next().getValue();
                if (b.tokens + (double) (now - b.last) / REFILL_MS >= BURST) it.remove();
            }
            if (buckets.size() >= MAX_PLAYERS) buckets.clear(); // all were busy: start over (fails open per player, not globally)
        }

        public synchronized void forget(String who) {
            buckets.remove(who);
        }
    }

    /** Per-capability sliding windows. */
    public static final class Windows {

        private static final int MAX_KEYS = 2048;
        private final Clock clock;
        private final Map<String, Deque<Long>> hits = new HashMap<>();

        public Windows(Clock clock) {
            this.clock = clock;
        }

        /**
         * Checks every rule of {@code cap} and, if all pass, records the request. Returns null when allowed,
         * else a short reason. {@code scopeValues}: the args value for the "card" / "service" / "job" scopes.
         */
        public synchronized String tryAcquire(String cap, Map<String, Object> args) {
            long now = clock.now();
            if (hits.size() >= MAX_KEYS) {
                for (Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator(); it.hasNext();) {
                    Deque<Long> d = it.next().getValue();
                    if (d.isEmpty() || now - d.peekLast().longValue() >= 3_600_000L) it.remove();
                }
            }
            List<Caps.Limit> rules = Caps.limits(cap);
            for (Caps.Limit l : rules) {
                String key = key(cap, l, args);
                if (key == null) return "missing " + l.scope;
                Deque<Long> q = hits.get(key);
                if (q == null && hits.size() >= MAX_KEYS) return "too many tracked keys (limiter full)";
                if (q != null) trim(q, now, l.windowMs);
                if (q != null && q.size() >= l.max) {
                    long wait = (q.peekFirst().longValue() + l.windowMs - now + 999) / 1000;
                    return "limit reached: " + l.max + " per " + human(l.windowMs) + (l.scope.isEmpty() ? "" : " per " + l.scope) + " (try again in " + wait + " s)";
                }
            }
            for (Caps.Limit l : rules) {
                String key = key(cap, l, args);
                Deque<Long> q = hits.get(key);
                if (q == null) {
                    q = new ArrayDeque<>();
                    hits.put(key, q);
                }
                q.addLast(Long.valueOf(now));
            }
            return null;
        }

        private static String key(String cap, Caps.Limit l, Map<String, Object> args) {
            if (l.scope.isEmpty()) return cap + "|*|" + l.windowMs;
            Object v = args.get(l.scope);
            return v instanceof String ? cap + "|" + l.scope + "=" + v + "|" + l.windowMs : null;
        }

        private static void trim(Deque<Long> q, long now, long window) {
            while (!q.isEmpty() && now - q.peekFirst().longValue() >= window) q.pollFirst();
        }

        private static String human(long ms) {
            return ms >= 3_600_000L ? (ms / 3_600_000L) + " h" : (ms / 60_000L) + " min";
        }
    }
}
