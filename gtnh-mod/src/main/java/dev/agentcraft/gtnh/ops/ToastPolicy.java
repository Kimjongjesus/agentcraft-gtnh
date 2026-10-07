package dev.agentcraft.gtnh.ops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Card 5b: who gets a decision toast, decided on the client from the server's list of OPEN
 * decisions. Pure Java with an injected clock, so every rule is checked headless:
 * <ul>
 * <li><b>new only, deduped by id</b>: an id toasts at most once while it stays open; every id the
 * client has ever seen is remembered ({@link #export()} / {@link #load(String, long)} persist the
 * set across restarts), so a reconnect, a server restart or a client restart replays nothing;</li>
 * <li><b>no stale replay</b>: a decision older than {@code maxAgeMs} when first seen is recorded,
 * not toasted (the first list after joining usually holds old ones);</li>
 * <li><b>per-id cooldown</b>: an id that closed and re-opened toasts again only after
 * {@code cooldownMs} since its last toast; "closed" means missing from a COMPLETE open-id list,
 * never just missing from the capped details (see {@link #offer(List, Collection, boolean, long)});</li>
 * <li><b>bounded, conservative memory</b>: at most {@link #MAX_REMEMBERED} ids are remembered
 * exactly. When a cut (incomplete) list forces the client to forget an id that may still be open,
 * the id goes into a fixed-size tombstone filter instead of just disappearing: a tombstoned id that
 * comes back is re-learned quietly, never toasted as new. The filter can only err towards silence
 * (a rare genuinely new decision recorded without a toast), and the next complete list, which names
 * every open id exactly, clears it;</li>
 * <li><b>global rate limit</b>: at most {@code maxPerMinute} toasts in any 60 s; the rest are
 * folded into a "+N more" count on the next toast;</li>
 * <li><b>mute</b>: nothing toasts and nothing is queued; decisions seen while muted never replay.</li>
 * </ul>
 * Alerts and ops events never reach this class: only decisions toast.
 */
public final class ToastPolicy {

    public long maxAgeMs = 30 * 60_000L, cooldownMs = 10 * 60_000L;
    public int maxPerMinute = 3;
    public boolean muted;

    /** id -> first seen (ms); insertion ordered so the oldest are pruned first. */
    private final Map<String, Long> seen = new LinkedHashMap<>();
    private final Map<String, Long> lastToast = new LinkedHashMap<>();
    /** Ids treated as open, oldest first (so a cut list forgets the oldest first). */
    private Set<String> openNow = new LinkedHashSet<>();
    private final Tombstones tomb = new Tombstones();
    private final Deque<Long> recent = new ArrayDeque<>();
    private int folded;
    public static final int MAX_REMEMBERED = 2000;
    public static final long REMEMBER_MS = 30L * 86400_000L;
    private static final String TOMB_LINE = "\ttomb\t";

    /** One toast to show: the decision plus how many more were folded into it by the rate limit. */
    public static final class Toast {

        public final DecisionData.Decision decision;
        public final int more;

        Toast(DecisionData.Decision d, int more) {
            this.decision = d;
            this.more = more;
        }
    }

    /** A complete list of open decisions, all with details (tests, the test toast). */
    public List<Toast> offer(List<DecisionData.Decision> open, long now) {
        List<String> ids = new ArrayList<>();
        for (DecisionData.Decision d : open) ids.add(d.id);
        return offer(open, ids, true, now);
    }

    /** The server's decision blob. */
    public List<Toast> offer(DecisionData.Snapshot s, long now) {
        return offer(s.open, s.openIds, s.complete, now);
    }

    /**
     * The server's current open decisions: {@code details} (the newest, with the fields a toast
     * shows), {@code openIds} (every open id it knows) and whether that id list is
     * {@code complete}. Returns the toasts to show now (usually 0 or 1), newest decision first.
     * <p>
     * An id counts as closed only when a COMPLETE id list lacks it. Missing from the details means
     * nothing (the server sends only the newest {@link DecisionData#MAX}), and missing from a cut id
     * list means nothing either: such ids stay "open" here until a complete list says otherwise, so
     * cap eviction followed by re-entry never re-toasts. If keeping them all would exceed
     * {@link #MAX_REMEMBERED}, the oldest are tombstoned rather than forgotten (see the class doc).
     * An open id that arrives without details (older than the newest {@link DecisionData#MAX}) is
     * remembered without a toast.
     */
    public List<Toast> offer(List<DecisionData.Decision> details, Collection<String> openIds, boolean complete, long now) {
        Set<String> next = new LinkedHashSet<>();
        for (String id : openIds) if (id != null && !id.isEmpty()) next.add(id);
        for (DecisionData.Decision d : details) if (!d.id.isEmpty()) next.add(d.id);
        if (!complete) {
            // a cut list proves nothing about the ids it lacks: they stay open (bounded). Keep the
            // newest of them; the oldest that do not fit may still be open, so they are tombstoned
            int missing = 0;
            for (String id : openNow) if (!next.contains(id)) missing++;
            int drop = Math.max(0, missing - Math.max(0, MAX_REMEMBERED - next.size()));
            Set<String> kept = new LinkedHashSet<>();
            for (String id : openNow) {
                if (next.contains(id)) continue;
                if (drop > 0) {
                    drop--;
                    // forgotten while possibly open: drop it from memory now (so it can never look
                    // "known but closed", i.e. reopened) and tombstone it (so it is never "new")
                    tomb.add(id);
                    seen.remove(id);
                    lastToast.remove(id);
                } else {
                    kept.add(id);
                }
            }
            kept.addAll(next); // oldest first: the kept older ids, then this list's ids
            next = kept;
        }
        List<DecisionData.Decision> fresh = new ArrayList<>();
        for (DecisionData.Decision d : details) {
            if (d.id.isEmpty()) continue;
            boolean known = seen.containsKey(d.id);
            if (!known && tomb.mayContain(d.id)) {
                // forgotten while possibly still open: re-learn quietly, never toast it as new. It
                // counts as toasted now, so a later genuine close and reopen follows the cooldown
                seen.put(d.id, now);
                lastToast.put(d.id, now);
                continue;
            }
            boolean reopened = known && !openNow.contains(d.id);
            if (!known) seen.put(d.id, now);
            if (known && !reopened) continue; // still open, already handled
            if (muted) continue;
            if (!known && d.createdAt > 0 && now - d.createdAt > maxAgeMs) continue; // old: record only
            if (reopened) {
                Long lt = lastToast.get(d.id);
                if (lt == null || now - lt < cooldownMs) continue; // never toasted, or within cooldown
            }
            fresh.add(d);
        }
        // open ids sent without details (older than the newest MAX): remember, never toast later
        for (String id : next) if (!seen.containsKey(id)) {
            seen.put(id, now);
            if (tomb.mayContain(id)) lastToast.put(id, now); // re-learned, as above
        }
        openNow = next;
        // a complete list names every open id and all of them are now remembered exactly, so
        // whatever is still tombstoned is closed: a later reopen of it is genuine
        if (complete) tomb.clear();
        prune(now);
        List<Toast> out = new ArrayList<>();
        // newest first
        fresh.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        while (!recent.isEmpty() && now - recent.peekFirst() >= 60_000L) recent.removeFirst();
        for (DecisionData.Decision d : fresh) {
            lastToast.put(d.id, now);
            if (recent.size() >= maxPerMinute) {
                folded++;
                continue;
            }
            recent.addLast(now);
            out.add(new Toast(d, 0));
        }
        if (!out.isEmpty() && folded > 0) {
            Toast last = out.remove(out.size() - 1);
            out.add(new Toast(last.decision, folded));
            folded = 0;
        }
        return out;
    }

    /** Folded toasts still waiting for the rate limit (shown as "+N more" next time). */
    public int pendingFolded() {
        return folded;
    }

    /** A rate-limited summary is due: a slot is free again and toasts were folded. */
    public boolean summaryDue(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() >= 60_000L) recent.removeFirst();
        return folded > 0 && !muted && recent.size() < maxPerMinute;
    }

    /** Consume the folded count for a summary toast. */
    public int takeFolded(long now) {
        int n = folded;
        folded = 0;
        if (n > 0) recent.addLast(now);
        return n;
    }

    public boolean known(String id) {
        return seen.containsKey(id);
    }

    public int rememberedCount() {
        return seen.size();
    }

    /** Ids treated as open right now (at most {@link #MAX_REMEMBERED} under cut lists). */
    public int openCount() {
        return openNow.size();
    }

    /** True if the id was forgotten while possibly open (or a rare filter false positive). */
    public boolean tombstoned(String id) {
        return tomb.mayContain(id);
    }

    /** Ids added to the tombstone filter since it was last cleared. */
    public int tombstoneCount() {
        return tomb.count;
    }

    private void prune(long now) {
        java.util.Iterator<Map.Entry<String, Long>> it = seen.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            boolean tooMany = seen.size() > MAX_REMEMBERED;
            if (!openNow.contains(e.getKey()) && (tooMany || now - e.getValue() > REMEMBER_MS)) {
                it.remove();
                lastToast.remove(e.getKey());
            } else if (!tooMany) {
                break;
            }
        }
    }

    /**
     * "id\tfirstSeen" lines (ids are adapter-generated, no tabs/newlines; others are dropped), plus
     * one "\ttomb\t&lt;count&gt;\t&lt;base64&gt;" line while the tombstone filter is not empty
     * (older builds skip it: a line starting with a tab is ignored).
     */
    public String export() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Long> e : seen.entrySet()) {
            String id = e.getKey();
            if (id.indexOf('\t') >= 0 || id.indexOf('\n') >= 0) continue;
            b.append(id)
                .append('\t')
                .append(e.getValue())
                .append('\n');
        }
        if (tomb.count > 0) b.append(TOMB_LINE)
            .append(tomb.count)
            .append('\t')
            .append(tomb.encode())
            .append('\n');
        return b.toString();
    }

    /** Restore the seen set written by {@link #export()} (bad lines are ignored). */
    public void load(String text, long now) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            if (line.startsWith(TOMB_LINE)) {
                String[] p = line.substring(TOMB_LINE.length())
                    .split("\t", 2);
                if (p.length == 2) tomb.decode(p[0].trim(), p[1].trim());
                continue;
            }
            int t = line.indexOf('\t');
            if (t <= 0 || t > 96) continue;
            try {
                long at = Long.parseLong(line.substring(t + 1)
                    .trim());
                String id = line.substring(0, t);
                if (now - at <= REMEMBER_MS && seen.size() < MAX_REMEMBERED) seen.put(id, at);
                else tomb.add(id); // not kept, and it may still be open: never "new" again
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        // whatever was open before the restart counts as "still open" until the first list arrives
        openNow = new LinkedHashSet<>(seen.keySet());
    }

    /**
     * Fixed-size Bloom filter (16 KiB, 3 probes) of ids forgotten while possibly open. No false
     * negatives, so a forgotten open id is never toasted as new; a false positive only keeps a new
     * decision quiet. Deterministic hashes (String.hashCode and FNV-1a), so it survives export/load.
     */
    static final class Tombstones {

        static final int BITS = 1 << 17, K = 3;
        final long[] words = new long[BITS / 64];
        int count;

        void add(String id) {
            int h1 = id.hashCode(), h2 = fnv(id);
            for (int i = 0; i < K; i++) {
                int bit = (h1 + i * h2) & (BITS - 1);
                words[bit >>> 6] |= 1L << (bit & 63);
            }
            count++;
        }

        boolean mayContain(String id) {
            if (count == 0) return false;
            int h1 = id.hashCode(), h2 = fnv(id);
            for (int i = 0; i < K; i++) {
                int bit = (h1 + i * h2) & (BITS - 1);
                if ((words[bit >>> 6] & (1L << (bit & 63))) == 0) return false;
            }
            return true;
        }

        void clear() {
            if (count == 0) return;
            java.util.Arrays.fill(words, 0L);
            count = 0;
        }

        String encode() {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(words.length * 8);
            for (long w : words) buf.putLong(w);
            return java.util.Base64.getEncoder()
                .encodeToString(buf.array());
        }

        void decode(String n, String b64) {
            try {
                int c = Integer.parseInt(n);
                byte[] raw = java.util.Base64.getDecoder()
                    .decode(b64);
                if (raw.length != words.length * 8 || c <= 0) return;
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw);
                for (int i = 0; i < words.length; i++) words[i] |= buf.getLong();
                count += c;
            } catch (IllegalArgumentException ignored) {
                // a damaged line is ignored (NumberFormatException included)
            }
        }

        static int fnv(String s) {
            int h = 0x811c9dc5;
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= 0x01000193;
            }
            return h | 1; // odd, so the probes never collapse onto one bit
        }
    }
}
