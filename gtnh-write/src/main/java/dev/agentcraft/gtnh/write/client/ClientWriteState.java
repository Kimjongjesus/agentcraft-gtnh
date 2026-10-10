package dev.agentcraft.gtnh.write.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.agentcraft.gtnh.write.proto.StrictJson;

/**
 * What this client knows about the write module on the server it is connected to. Written by the
 * packet handlers (network thread), read by the GUI (render thread): reads are lock-free snapshots or
 * short synchronized copies. No Minecraft types in here. The server is the authority; nothing in this
 * class grants anything (a modified client gains nothing from editing it).
 *
 * <p>
 * Poll {@link #version} to know when to redraw. {@link #statusLine()} is the one-line text for
 * screens ("writes armed" / "writes disarmed: <reason>" / "writes LOCKED: <info>").
 */
public final class ClientWriteState {

    /** What the control service offers (display data only; the server re-checks every request). */
    public static final class Policy {

        public static final Policy EMPTY = new Policy();
        public final Map<String, Cap> capabilities = new LinkedHashMap<>();
        public final List<String> boards = new ArrayList<>(), profiles = new ArrayList<>(), services = new ArrayList<>(), jobs = new ArrayList<>(), agents = new ArrayList<>();

        /** true when the capability is offered, enabled and the module is armed and unlocked. */
        public boolean usable(String capability) {
            Cap c = capabilities.get(capability);
            return c != null && c.enabled && canWrite();
        }
    }

    public static final class Cap {

        public final int tier;
        public final boolean confirm, enabled;

        Cap(int tier, boolean confirm, boolean enabled) {
            this.tier = tier;
            this.confirm = confirm;
            this.enabled = enabled;
        }
    }

    /** An open Confirm screen request (dispatch). {@link #expiresAtMs} is client wall-clock time. */
    public static final class PromptInfo {

        public final String requestId, token;
        public final long expiresAtMs;
        /** card, title, board, profile, model, body (from Hermes, already cleaned by the server). */
        public final Map<String, String> summary;

        PromptInfo(String requestId, String token, long expiresAtMs, Map<String, String> summary) {
            this.requestId = requestId;
            this.token = token;
            this.expiresAtMs = expiresAtMs;
            this.summary = Collections.unmodifiableMap(summary);
        }

        public long msLeft() {
            return Math.max(0, expiresAtMs - System.currentTimeMillis());
        }
    }

    /** The outcome of a request: status is applied, refused, queued, unknown, prompted or cancelled. */
    public static final class ResultInfo {

        public final String requestId, capability, status, error, audit;
        public final Map<String, String> result;
        public final boolean dryRun;
        public final long atMs = System.currentTimeMillis();

        ResultInfo(String requestId, String capability, String status, String error, Map<String, String> result, String audit, boolean dryRun) {
            this.requestId = requestId;
            this.capability = capability;
            this.status = status;
            this.error = error;
            this.result = Collections.unmodifiableMap(result);
            this.audit = audit;
            this.dryRun = dryRun;
        }

        public boolean isFailure() {
            return !("applied".equals(status) || "queued".equals(status) || "prompted".equals(status));
        }
    }

    public static final class ChatLine {

        public final String conversation, agentId, text;
        public final boolean fin;
        public final long atMs = System.currentTimeMillis();

        ChatLine(String conversation, String agentId, String text, boolean fin) {
            this.conversation = conversation;
            this.agentId = agentId;
            this.text = text;
            this.fin = fin;
        }
    }

    /** Bumped on every change; the GUI redraws or re-reads when it moves. */
    public static final AtomicInteger version = new AtomicInteger();

    public static volatile boolean armed, locked, hermesLocked, dryRun, overridden, linkUp;
    /** Why writes are disarmed ("" when armed). */
    public static volatile String reason = "waiting for the server (is the write module installed there?)";
    public static volatile String lockInfo = "", revision = "";
    public static volatile Policy policy = Policy.EMPTY;
    public static volatile PromptInfo prompt;
    /** An NPC click asked for a chat window with this agent; consumed by {@link #takeOpenChat()}. */
    private static volatile String openChat;

    private static final Deque<ResultInfo> RESULTS = new ArrayDeque<>();
    private static final Deque<ChatLine> CHAT = new ArrayDeque<>();
    private static final int MAX_RESULTS = 16, MAX_CHAT = 200;

    private ClientWriteState() {}

    // ---- reads for the GUI ---------------------------------------------------------------------------

    public static boolean canWrite() {
        return armed && !locked;
    }

    public static String statusLine() {
        if (locked) return "writes LOCKED: " + lockInfo;
        if (!armed) return "writes disarmed: " + reason;
        return "writes armed" + (dryRun ? " (dry run: nothing real executes)" : "") + (overridden ? " [dev override]" : "");
    }

    /** The open Confirm request, or null (also null once its 60 s are up). */
    public static PromptInfo pendingPrompt() {
        PromptInfo p = prompt;
        if (p != null && p.msLeft() <= 0) {
            prompt = null;
            version.incrementAndGet();
            return null;
        }
        return p;
    }

    /** Newest first. */
    public static synchronized List<ResultInfo> lastResults() {
        return new ArrayList<>(RESULTS);
    }

    public static synchronized ResultInfo lastResult() {
        return RESULTS.peekFirst();
    }

    /** Oldest first; at most 200 lines kept. */
    public static synchronized List<ChatLine> chatLines() {
        List<ChatLine> l = new ArrayList<>(CHAT);
        Collections.reverse(l);
        return l;
    }

    public static synchronized List<ChatLine> chatLines(String agentId) {
        List<ChatLine> out = new ArrayList<>();
        for (ChatLine c : chatLines()) if (c.agentId.equals(agentId)) out.add(c);
        return out;
    }

    /** The agent a server-side NPC click asked a chat window for, once; null if none. */
    public static String takeOpenChat() {
        String a = openChat;
        openChat = null;
        return a;
    }

    // ---- writes from the packet handlers ----------------------------------------------------------------

    public static void reset() {
        armed = false;
        locked = false;
        hermesLocked = false;
        dryRun = false;
        overridden = false;
        linkUp = false;
        reason = "waiting for the server (is the write module installed there?)";
        lockInfo = "";
        revision = "";
        policy = Policy.EMPTY;
        prompt = null;
        openChat = null;
        synchronized (ClientWriteState.class) {
            RESULTS.clear();
            CHAT.clear();
        }
        version.incrementAndGet();
    }

    /** A refusal made on this client before anything was sent (shown like any other result). */
    public static void localRefusal(String capability, String why) {
        push(new ResultInfo("local", capability, "refused", why, Collections.<String, String>emptyMap(), "", false));
    }

    @SuppressWarnings("unchecked")
    public static void onState(String json) {
        try {
            Map<String, Object> m = StrictJson.parseObject(json);
            Policy p = new Policy();
            Object caps = m.get("capabilities");
            if (caps instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) caps).entrySet()) {
                    if (!(e.getValue() instanceof Map)) continue;
                    Map<String, Object> c = (Map<String, Object>) e.getValue();
                    Object tier = c.get("tier");
                    p.capabilities.put(e.getKey(), new Cap(tier instanceof Long ? ((Long) tier).intValue() : 0, Boolean.TRUE.equals(c.get("confirm")), Boolean.TRUE.equals(c.get("enabled"))));
                }
            }
            strings(m.get("boards"), p.boards);
            strings(m.get("profiles"), p.profiles);
            strings(m.get("services"), p.services);
            strings(m.get("jobs"), p.jobs);
            strings(m.get("agents"), p.agents);
            policy = p;
            locked = Boolean.TRUE.equals(m.get("locked"));
            hermesLocked = Boolean.TRUE.equals(m.get("hermesLocked"));
            dryRun = Boolean.TRUE.equals(m.get("dryRun"));
            overridden = Boolean.TRUE.equals(m.get("overridden"));
            linkUp = Boolean.TRUE.equals(m.get("link"));
            lockInfo = str(m.get("lockInfo"));
            revision = str(m.get("revision"));
            reason = str(m.get("reason"));
            armed = Boolean.TRUE.equals(m.get("armed"));
            if (!armed && reason.isEmpty()) reason = "unknown";
            if (!canWrite()) prompt = null; // a screen for a dropped token must not stay up
        } catch (StrictJson.ParseException | RuntimeException e) {
            armed = false;
            reason = "unreadable state from the server";
        }
        version.incrementAndGet();
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static void strings(Object o, List<String> out) {
        if (!(o instanceof List)) return;
        for (Object s : (List<?>) o) if (s instanceof String) out.add((String) s);
    }

    public static void onPrompt(String requestId, String token, long msLeft, String summaryJson) {
        Map<String, String> s = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> e : StrictJson.parseObject(summaryJson).entrySet()) {
                if (e.getValue() instanceof String) s.put(e.getKey(), (String) e.getValue());
            }
        } catch (StrictJson.ParseException e) {
            // an empty summary is shown as such; the server already refused anything odd
        }
        prompt = new PromptInfo(requestId, token, System.currentTimeMillis() + Math.max(0, msLeft), s);
        version.incrementAndGet();
    }

    public static void onPromptClosed(String token, String why) {
        PromptInfo p = prompt;
        if (p != null && p.token.equals(token)) {
            prompt = null;
            if (!"confirmed".equals(why) && !"cancelled".equals(why)) push(new ResultInfo(p.requestId, "card.dispatch", "cancelled", "confirmation closed: " + why, Collections.<String, String>emptyMap(), "", false));
        }
        version.incrementAndGet();
    }

    public static void onResult(String requestId, String capability, String status, String error, String resultJson, String audit, boolean dryRun) {
        Map<String, String> r = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> e : StrictJson.parseObject(resultJson).entrySet()) {
                if (e.getValue() instanceof String) r.put(e.getKey(), (String) e.getValue());
            }
        } catch (StrictJson.ParseException e) {
            // keep an empty result map
        }
        push(new ResultInfo(requestId, capability, status, error, r, audit, dryRun));
    }

    private static void push(ResultInfo r) {
        synchronized (ClientWriteState.class) {
            RESULTS.addFirst(r);
            while (RESULTS.size() > MAX_RESULTS) RESULTS.removeLast();
        }
        version.incrementAndGet();
    }

    public static void onChat(String conversation, String agentId, String text, boolean fin) {
        synchronized (ClientWriteState.class) {
            CHAT.addFirst(new ChatLine(conversation, agentId, text, fin));
            while (CHAT.size() > MAX_CHAT) CHAT.removeLast();
        }
        version.incrementAndGet();
    }

    public static void requestOpenChat(String agentId) {
        openChat = agentId;
        version.incrementAndGet();
    }
}
