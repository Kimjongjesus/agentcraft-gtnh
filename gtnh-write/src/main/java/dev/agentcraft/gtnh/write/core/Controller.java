package dev.agentcraft.gtnh.write.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.agentcraft.gtnh.write.proto.Caps;
import dev.agentcraft.gtnh.write.proto.Clock;
import dev.agentcraft.gtnh.write.proto.Fields;
import dev.agentcraft.gtnh.write.proto.Msg;
import dev.agentcraft.gtnh.write.proto.StrictJson;

/**
 * The write module's decision logic, with no Minecraft types: gate re-checks, per-request rules,
 * confirm tokens, lock, audit and result routing. Runs on the server thread only (the Minecraft
 * glue queues work onto it). Every refusal says why, is audited, and is reported to the player.
 * Order of a request: token bucket, actor == owner, gate (arming), argument validation, link,
 * policy (enabled and offered names), presence (tier 2), per-capability limits, audit, send.
 */
public final class Controller {

    public static final long PROMPT_MS = 60_000L, RECHECK_MS = 30_000L, UNKNOWN_AFTER_MS = 90_000L;
    public static final int MAX_ARGS_JSON = 8000;

    /** An authenticated player (or the console) as the glue sees them. */
    public static final class Player {

        public final String uuid, name;
        public final boolean op;

        public Player(String uuid, String name, boolean op) {
            this.uuid = uuid == null ? "" : uuid.toLowerCase(Locale.ROOT);
            this.name = name == null ? "?" : name;
            this.op = op;
        }
    }

    /** Who ran a command: a player or the console (player == null). */
    public static final class Principal {

        public final Player player;

        public Principal(Player p) {
            this.player = p;
        }

        public boolean console() {
            return player == null;
        }
    }

    public static final class Settings {

        public String owner = "";
        public int presenceRadius = 8;
        public boolean lockAlsoLocksEdit = true;
    }

    public interface Uplink {

        boolean ready();

        /** Sends one signed g2c frame with a fresh id, nonce and ts; returns the frame id, or null when nothing was sent. */
        String send(String type, Map<String, Object> body);

        /**
         * Fail-closed invalidation: every frame queued but not yet on the wire is discarded and can no
         * longer be sent, and the connection is closed (the control service then voids every open
         * confirmation of the connection). Idempotent. The link reconnects by itself; a game lock is
         * announced again on the new connection.
         */
        void invalidate();
    }

    public interface Notifier {

        void stateChanged();

        void prompt(String playerUuid, String requestId, String token, long msLeft, Map<String, String> summary);

        void promptClosed(String playerUuid, String token, String why);

        void result(String playerUuid, String requestId, String capability, String status, String error, Map<String, String> result, String audit, boolean dryRun);

        void chat(String playerUuid, String conversation, String agentId, String text, boolean fin);
    }

    /** Tier 2 presence: null when the player is near an HQ anchor in the HQ dimension, else why not. */
    public interface Presence {

        String check(Player p, int radius);
    }

    private static final class PromptRec {

        final String token, requestId, playerUuid, playerName;
        final long expires;

        PromptRec(String token, String requestId, String playerUuid, String playerName, long expires) {
            this.token = token;
            this.requestId = requestId;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.expires = expires;
        }
    }

    private static final class Pending {

        final String id, playerUuid, playerName, cap;
        final long sentAt;

        Pending(String id, String playerUuid, String playerName, String cap, long sentAt) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.cap = cap;
            this.sentAt = sentAt;
        }
    }

    private final Settings cfg;
    private final Clock clock;
    private final WriteAudit audit;
    private final WriteLock lock;
    private final GateFacts facts;
    private final Uplink up;
    private final Notifier note;
    private final Presence presence;
    private final RateLimits.Bucket bucket;
    private final RateLimits.Windows windows;

    private boolean armed;
    private String disarmReason = "not checked yet";
    private Gate.Result last;
    private long lastRecheck = Long.MIN_VALUE;
    private final Map<String, PromptRec> prompts = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, String> confirmToRequest = new HashMap<>();
    private final Map<String, long[]> throttles = new HashMap<>();
    private volatile Msg.Policy policy;
    private volatile Msg.State hstate;
    private volatile boolean overrideTainted;
    private long localCounter;

    public Controller(Settings cfg, Clock clock, WriteAudit audit, WriteLock lock, GateFacts facts, Uplink up, Notifier note, Presence presence) {
        this.cfg = cfg;
        this.clock = clock;
        this.audit = audit;
        this.lock = lock;
        this.facts = facts;
        this.up = up;
        this.note = note;
        this.presence = presence;
        this.bucket = new RateLimits.Bucket(clock);
        this.windows = new RateLimits.Windows(clock);
    }

    // ---- accessors for the glue / facts ------------------------------------------------------------

    public boolean armed() {
        return armed;
    }

    public String disarmReason() {
        return disarmReason;
    }

    public Msg.Policy policy() {
        return policy;
    }

    public Msg.State hermesState() {
        return hstate;
    }

    public boolean overrideTainted() {
        return overrideTainted;
    }

    public Gate.Result lastGate() {
        return last;
    }

    public int openPrompts() {
        return prompts.size();
    }

    public int pendingRequests() {
        return pending.size();
    }

    public boolean promptOpen(String token) {
        return prompts.containsKey(token);
    }

    public WriteLock lockState() {
        return lock;
    }

    /** The edit-tool lock query (core hook): a reason while the write lock is set and lockAlsoLocksEdit, else null. */
    public String editLockReason() {
        if (cfg.lockAlsoLocksEdit && lock.isLocked()) return "the write lock is set (" + lock.info() + "); /agentcraft write unlock (owner or console)";
        return null;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Display text: control characters (except newline) and section signs become spaces. */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append((c < 32 && c != '\n') || c == 127 || c == '\u00a7' ? ' ' : c);
        }
        return b.toString();
    }

    private static Map<String, String> cleanMap(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) out.put(clean(e.getKey()), clean(e.getValue()));
        return out;
    }

    private boolean aud(Player p, String action, String target, String before, String after, String detail) {
        boolean ok = audit.record(p == null ? "console" : WriteAudit.who(p.name, p.uuid), action, target, before, after, detail);
        if (!ok) forceDisarm("audit-writable: game audit log cannot be written: " + audit.lastError());
        return ok;
    }

    private boolean audSys(String action, String target, String before, String after, String detail) {
        boolean ok = audit.record("system", action, target, before, after, detail);
        if (!ok) forceDisarm("audit-writable: game audit log cannot be written: " + audit.lastError());
        return ok;
    }

    /** true when a line for {@code key} may be written now (at most one per gap); the count of skipped ones goes to {@code skipped[0]}. */
    private boolean allow(String key, long gapMs, int[] skipped) {
        long now = clock.now();
        long[] t = throttles.get(key);
        if (t == null) {
            if (throttles.size() > 512) throttles.clear();
            t = new long[] { 0, 0 };
            throttles.put(key, t);
        }
        if (t[0] != 0 && now - t[0] < gapMs) {
            t[1]++;
            return false;
        }
        skipped[0] = (int) t[1];
        t[0] = now;
        t[1] = 0;
        return true;
    }

    private String localId() {
        return "local-" + Long.toString(++localCounter, 36);
    }

    private void refuse(Player p, String cap, String why, boolean throttle) {
        boolean write = true;
        String extra = "";
        if (throttle) {
            int[] sk = { 0 };
            write = allow("refuse:" + p.uuid, 10_000L, sk);
            if (sk[0] > 0) extra = " (+" + sk[0] + " more refused since the last line)";
        }
        if (write) aud(p, "refused", cap == null ? "-" : cap, "-", "-", why + extra);
        note.result(p.uuid, localId(), cap == null ? "" : cap, "refused", why, Collections.<String, String>emptyMap(), "", false);
    }

    // ---- gate ---------------------------------------------------------------------------------------

    /** Runs the gate now; a failing check disarms (and drops confirm tokens), a passing gate re-arms. */
    public Gate.Result recheck() {
        lock.load();
        Gate.Result r = Gate.evaluate(facts);
        lastRecheck = clock.now();
        last = r;
        if (r.armed) {
            if (!armed) {
                armed = true;
                disarmReason = "";
                audSys("arm", "-", "disarmed", "armed", r.overrideActive ? r.overrideNote : "all checks pass");
                note.stateChanged();
            }
        } else {
            boolean changed = armed || !r.reason.equals(disarmReason);
            disarm(r.reason);
            if (changed) {
                audSys("disarm", "-", "-", "disarmed", r.reason);
                note.stateChanged();
            }
        }
        return r;
    }

    private void forceDisarm(String reason) {
        boolean was = armed || !reason.equals(disarmReason);
        disarm(reason);
        if (was) note.stateChanged();
    }

    /**
     * Disarms. Every open confirmation is dropped and its request resolved at once as cancelled; when
     * writes were armed (or a confirmation was open) the uplink is invalidated: queued frames die and the
     * connection closes, so the control service's connection-close cleanup voids all of its tokens.
     */
    private void disarm(String reason) {
        boolean wasArmed = armed, hadPrompts = !prompts.isEmpty();
        armed = false;
        disarmReason = reason;
        if (wasArmed || hadPrompts) up.invalidate();
        dropAll("disarmed: " + reason, "disarmed: " + reason);
    }

    /**
     * Drops every open confirmation locally. {@code cancelled} null: nothing else (the link is gone and the
     * pending requests are answered "unknown" by the caller); else each dropped request is resolved now as
     * cancelled with that text (audit detail "cancelled: ..."), so it is not left to time out as "unknown".
     */
    private void dropAll(String why, String cancelled) {
        if (prompts.isEmpty()) return;
        List<PromptRec> all = new ArrayList<>(prompts.values());
        prompts.clear();
        for (PromptRec r : all) {
            audit.record("system", "token-drop", r.requestId, "-", "-", why);
            note.promptClosed(r.playerUuid, r.token, why);
            if (cancelled != null) resolveCancelled(r, cancelled);
        }
    }

    private void resolveCancelled(PromptRec r, String why) {
        Pending p = pending.remove(r.requestId);
        if (p == null) return;
        audit.record("system", "result", p.cap, p.id, "cancelled", "cancelled: " + why);
        note.result(p.playerUuid, p.id, p.cap, "cancelled", why, Collections.<String, String>emptyMap(), "", false);
    }

    /** Best effort: tells the control service that a token is dead (not rate limited: it only ever closes things). */
    private void cancelUpstream(PromptRec r) {
        if (!armed || lock.isLocked() || !up.ready()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor(r.playerUuid, r.playerName));
        body.put("token", r.token);
        up.send("action.cancel", body);
    }

    // ---- link events ------------------------------------------------------------------------------

    public void onLinkUp(Msg.Policy pol, Msg.State st) {
        policy = pol;
        hstate = st;
        audSys("link-up", "-", "-", "-", "signed handshake ok, policy " + pol.revision + (pol.dryRun ? " (dryRun)" : ""));
        recheck();
        if (lock.isLocked()) sendLockNotice(null, "game write lock is set: " + lock.info());
        note.stateChanged();
    }

    public void onLinkDown(String why) {
        policy = null;
        hstate = null;
        dropAll("connection closed", null);
        if (!pending.isEmpty()) {
            for (Pending p : new ArrayList<>(pending.values())) {
                note.result(p.playerUuid, p.id, p.cap, "unknown", "the link to the control service closed; check outside the game", Collections.<String, String>emptyMap(), "", false);
                audSys("result", p.cap, p.id, "unknown", "link closed before an answer");
            }
            pending.clear();
            confirmToRequest.clear();
        }
        audSys("link-down", "-", "-", "-", why);
        recheck();
        note.stateChanged();
    }

    /** A signed, verified control -> game message after the handshake. */
    public void onFrame(Msg m) {
        if (m instanceof Msg.Policy) {
            Msg.Policy old = policy;
            policy = (Msg.Policy) m;
            // the control service voided its tokens when it reloaded: close the local prompts on every revision change
            if (old != null && !old.revision.equals(policy.revision)) dropAll("policy revision changed", "policy revision changed");
            recheck();
            note.stateChanged();
        } else if (m instanceof Msg.State) {
            hstate = (Msg.State) m;
            recheck();
            note.stateChanged();
        } else if (m instanceof Msg.Prompt) {
            onPrompt((Msg.Prompt) m);
        } else if (m instanceof Msg.Result) {
            onResult((Msg.Result) m);
        } else if (m instanceof Msg.Chat) {
            onChat((Msg.Chat) m);
        } else if (m instanceof Msg.Error) {
            onError((Msg.Error) m);
        } else {
            audSys("protocol", m.type, "-", "-", "unexpected message after the handshake");
        }
    }

    public void onBadFrame(String code, String detail) {
        int[] sk = { 0 };
        if (allow("badframe", 1000L, sk)) audSys("bad-frame", code, "-", "-", detail + (sk[0] > 0 ? " (+" + sk[0] + " more)" : ""));
    }

    // ---- control -> game ----------------------------------------------------------------------------

    private void onPrompt(Msg.Prompt m) {
        Pending p = pending.get(m.re);
        long now = clock.now();
        if (p == null || !"card.dispatch".equals(p.cap)) {
            audSys("prompt-dropped", m.re, "-", "-", "no matching card.dispatch request");
            return;
        }
        recheck();
        if (!armed || lock.isLocked()) {
            audSys("prompt-dropped", m.re, "-", "-", armed ? "write lock set" : "disarmed: " + disarmReason);
            return;
        }
        if (m.expiresAt <= now) {
            audSys("prompt-dropped", m.re, "-", "-", "already expired");
            return;
        }
        if (prompts.containsKey(m.token)) {
            audSys("prompt-dropped", m.re, "-", "-", "token reused");
            return;
        }
        // one open prompt per player: a newer one replaces the older
        for (Iterator<PromptRec> it = prompts.values().iterator(); it.hasNext();) {
            PromptRec old = it.next();
            if (old.playerUuid.equals(p.playerUuid)) {
                it.remove();
                note.promptClosed(old.playerUuid, old.token, "replaced by a newer confirmation");
                cancelUpstream(old);
                resolveCancelled(old, "replaced by a newer confirmation");
            }
        }
        long exp = Math.min(m.expiresAt, now + PROMPT_MS);
        prompts.put(m.token, new PromptRec(m.token, m.re, p.playerUuid, p.playerName, exp));
        Map<String, String> s = cleanMap(m.summary);
        audSys("prompt", s.get("card"), "-", "-", "confirm screen for " + s.get("profile") + " on " + s.get("board") + " (60 s)");
        note.prompt(p.playerUuid, m.re, m.token, exp - now, s);
    }

    private void onResult(Msg.Result m) {
        String reqId = m.re;
        Pending p = pending.get(reqId);
        if (p == null && confirmToRequest.containsKey(reqId)) {
            reqId = confirmToRequest.get(reqId);
            p = pending.get(reqId);
        }
        if (p == null) {
            int[] sk = { 0 };
            if (allow("orphan", 5000L, sk)) audSys("result-orphan", m.re, "-", m.status, "no pending request" + (sk[0] > 0 ? " (+" + sk[0] + " more)" : ""));
            return;
        }
        Gate.Result g = last;
        if (g != null && g.overrideActive && !m.dryRun) {
            overrideTainted = true;
            audSys("override-violation", p.cap, reqId, m.status, "result without dryRun under the loopback dry-run override");
            forceDisarm("dev-override: a result did not say dryRun");
            recheck();
        }
        audSys("result", p.cap, reqId, m.status, (m.error.isEmpty() ? "" : m.error + "; ") + "audit=" + m.audit + (m.dryRun ? " dryRun" : ""));
        note.result(p.playerUuid, reqId, p.cap, m.status, clean(m.error), cleanMap(m.result), clean(m.audit), m.dryRun);
        if (!"prompted".equals(m.status) && !"queued".equals(m.status)) {
            pending.remove(reqId);
            // an open prompt for a finished request is stale
            for (Iterator<PromptRec> it = prompts.values().iterator(); it.hasNext();) {
                PromptRec r = it.next();
                if (r.requestId.equals(reqId)) {
                    it.remove();
                    note.promptClosed(r.playerUuid, r.token, "request finished: " + m.status);
                }
            }
            for (Iterator<Map.Entry<String, String>> it = confirmToRequest.entrySet().iterator(); it.hasNext();) {
                if (it.next().getValue().equals(reqId)) it.remove();
            }
        }
    }

    private void onChat(Msg.Chat m) {
        Pending p = pending.get(m.re);
        if (p == null || !("agent.chat".equals(p.cap) || "agent.ask".equals(p.cap))) {
            int[] sk = { 0 };
            if (allow("chat-orphan", 5000L, sk)) audSys("chat-dropped", m.re, "-", "-", "no matching chat request");
            return;
        }
        if (m.fin) audSys("chat", m.agentId, "-", "-", m.text.length() + " chars final, conversation " + m.conversation);
        note.chat(p.playerUuid, clean(m.conversation), clean(m.agentId), clean(m.text), m.fin);
    }

    private void onError(Msg.Error m) {
        audSys("control-error", m.re, "-", "-", m.error);
        Pending p = m.re.isEmpty() ? null : pending.remove(m.re);
        if (p != null) {
            note.result(p.playerUuid, p.id, p.cap, "unknown", "control service error: " + clean(m.error) + "; check outside the game", Collections.<String, String>emptyMap(), "", false);
        }
    }

    // ---- player requests ------------------------------------------------------------------------------

    /** A capability request from a player's client. {@code argsJson} is untrusted text. */
    public void handleRequest(Player p, String cap, String argsJson) {
        if (!bucket.tryTake(p.uuid)) {
            refuse(p, cap, "slow down: at most 1 request per 2 s (burst 3)", true);
            return;
        }
        recheck(); // the gate runs again on every request
        String denied = Gate.actorDenied(facts, p.uuid);
        if (denied != null) {
            refuse(p, cap, denied, true);
            return;
        }
        if (!armed) {
            refuse(p, cap, "writes disarmed: " + disarmReason, false);
            return;
        }
        Caps.Cap c = Caps.get(cap);
        if (c == null) {
            refuse(p, cap, "unknown capability", false);
            return;
        }
        Map<String, Object> args;
        try {
            if (argsJson == null || argsJson.length() > MAX_ARGS_JSON) throw new Fields.Bad("args too long");
            args = Caps.validate(cap, StrictJson.parseObject(argsJson));
        } catch (Fields.Bad | StrictJson.ParseException e) {
            refuse(p, cap, "bad arguments: " + e.getMessage(), false);
            return;
        }
        if (lock.isLocked()) {
            refuse(p, cap, "write lock is set (" + lock.info() + ")", false);
            return;
        }
        Msg.Policy pol = policy;
        if (!up.ready() || pol == null) {
            refuse(p, cap, "the control service link is down (nothing is queued; try again when it is back)", false);
            return;
        }
        Msg.CapInfo ci = pol.capabilities.get(cap);
        if (ci == null || !ci.enabled) {
            refuse(p, cap, "the control service policy has " + cap + " off", false);
            return;
        }
        String offered = offeredCheck(pol, cap, args);
        if (offered != null) {
            refuse(p, cap, offered, false);
            return;
        }
        if (c.tier == 2 && cfg.presenceRadius > 0) {
            String far = presence.check(p, cfg.presenceRadius);
            if (far != null) {
                refuse(p, cap, "tier 2 needs you at the HQ: " + far, false);
                return;
            }
        }
        String lim = windows.tryAcquire(cap, args);
        if (lim != null) {
            refuse(p, cap, lim, false);
            return;
        }
        if (!aud(p, "request", cap, "-", "-", describe(args))) {
            note.result(p.uuid, localId(), cap, "refused", "writes disarmed: " + disarmReason, Collections.<String, String>emptyMap(), "", false);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor(p.uuid, p.name));
        body.put("capability", cap);
        body.put("tier", Long.valueOf(c.tier));
        body.put("args", args);
        String id = up.send("action.request", body);
        if (id == null) {
            refuse(p, cap, "could not send to the control service (link down)", false);
            return;
        }
        pending.put(id, new Pending(id, p.uuid, p.name, cap, clock.now()));
    }

    private static String offeredCheck(Msg.Policy pol, String cap, Map<String, Object> a) {
        switch (cap) {
            case "card.create":
                return in(pol.boards, a.get("board"), "board");
            case "card.dispatch": {
                String e = in(pol.boards, a.get("board"), "board");
                return e != null ? e : in(pol.profiles, a.get("profile"), "builder profile");
            }
            case "agent.chat":
            case "agent.ask":
                return in(pol.agents, a.get("agent"), "agent");
            case "service.restart":
                return in(pol.services, a.get("service"), "service");
            case "cron.run":
                return in(pol.jobs, a.get("job"), "job");
            default:
                return null;
        }
    }

    private static String in(List<String> offered, Object v, String what) {
        return offered.contains(v) ? null : "the control service does not offer that " + what;
    }

    private static String describe(Map<String, Object> args) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String k = e.getKey();
            if (k.equals("text") || k.equals("body") || k.equals("comment")) {
                b.append(k).append('=').append(((String) e.getValue()).length()).append("chars ");
            } else {
                b.append(k).append('=').append(e.getValue()).append(' ');
            }
        }
        return b.toString().trim();
    }

    private static Map<String, Object> actor(String uuid, String name) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("uuid", uuid);
        a.put("name", name.length() > 16 ? name.substring(0, 16) : name);
        return a;
    }

    /** "Confirm" on the dispatch screen. */
    public void handleConfirm(Player p, String token) {
        if (!bucket.tryTake(p.uuid)) {
            refuse(p, "card.dispatch", "slow down: at most 1 request per 2 s (burst 3)", true);
            return;
        }
        recheck(); // the gate runs again on confirm (a failure drops every token, this one included)
        PromptRec r = token == null ? null : prompts.get(token);
        if (r == null) {
            refuse(p, "card.dispatch", "no such confirmation (it expired, was cancelled, or writes were disarmed or locked)", false);
            return;
        }
        if (!r.playerUuid.equals(p.uuid)) {
            aud(p, "refused", "card.dispatch", r.requestId, "-", "confirm by a player who did not request it");
            note.result(p.uuid, localId(), "card.dispatch", "refused", "that confirmation belongs to another player", Collections.<String, String>emptyMap(), "", false);
            return;
        }
        if (clock.now() >= r.expires) {
            prompts.remove(token);
            note.promptClosed(r.playerUuid, token, "expired");
            refuse(p, "card.dispatch", "the confirmation expired (60 s); start again", false);
            return;
        }
        String denied = Gate.actorDenied(facts, p.uuid);
        if (denied != null) {
            refuse(p, "card.dispatch", denied, false);
            return;
        }
        if (!armed || !prompts.containsKey(token)) {
            refuse(p, "card.dispatch", "writes disarmed: " + disarmReason, false);
            return;
        }
        if (lock.isLocked()) {
            refuse(p, "card.dispatch", "write lock is set (" + lock.info() + ")", false);
            return;
        }
        if (!up.ready()) {
            refuse(p, "card.dispatch", "the control service link is down", false);
            return;
        }
        if (cfg.presenceRadius > 0) {
            String far = presence.check(p, cfg.presenceRadius);
            if (far != null) {
                refuse(p, "card.dispatch", "tier 2 needs you at the HQ: " + far, false);
                return;
            }
        }
        if (!aud(p, "confirm", "card.dispatch", r.requestId, "-", "dispatch confirmed")) return;
        prompts.remove(token); // single use, whatever happens next
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor(p.uuid, p.name));
        body.put("token", token);
        String id = up.send("action.confirm", body);
        note.promptClosed(p.uuid, token, "confirmed");
        if (id == null) {
            note.result(p.uuid, r.requestId, "card.dispatch", "unknown", "could not send the confirmation; check outside the game", Collections.<String, String>emptyMap(), "", false);
            pending.remove(r.requestId);
            return;
        }
        confirmToRequest.put(id, r.requestId);
    }

    /** "Cancel" on the dispatch screen (also allowed while disarmed: it only closes the screen). */
    public void handleCancel(Player p, String token) {
        PromptRec r = token == null ? null : prompts.get(token);
        if (r == null || !r.playerUuid.equals(p.uuid)) return;
        prompts.remove(token);
        aud(p, "cancel", "card.dispatch", r.requestId, "-", "cancelled by the player");
        note.promptClosed(p.uuid, token, "cancelled");
        pending.remove(r.requestId);
        cancelUpstream(r); // never depends on the request bucket: a cancel only closes things
    }

    // ---- lock -----------------------------------------------------------------------------------------

    /** Lock request from the console, a player's command or a client lock packet. Returns the message for the sender. */
    public String lockBy(Principal who, String reason) {
        if (!who.console() && !who.player.op) {
            aud(who.player, "lock-refused", "-", "-", "-", "only ops may lock");
            return "only ops may set the write lock";
        }
        String by = who.console() ? "console" : WriteAudit.who(who.player.name, who.player.uuid);
        String how = lock.lock(by, reason);
        aud(who.player, "lock", "-", "unlocked", "locked", (reason == null ? "" : reason) + " | " + how);
        // atomically: nothing queued before this lock may reach the wire after it
        up.invalidate();
        dropAll("write lock set", "locked");
        recheck(); // (its disarm invalidates the uplink again, so the notice is queued after it)
        // only goes out while the link is still ready: a real link has closed and announces the lock when it reconnects
        sendLockNotice(who.player, reason);
        note.stateChanged();
        return "write lock " + how + (cfg.lockAlsoLocksEdit ? "; the edit tool is locked too" : "");
    }

    private void sendLockNotice(Player who, String reason) {
        if (!up.ready()) return;
        String name = who == null ? "console" : who.name;
        Map<String, Object> body = new LinkedHashMap<>();
        // the control service only accepts its configured actor; the real sender is named in the reason
        body.put("actor", actor(cfg.owner, name));
        String r = "by " + name + ": " + (reason == null ? "" : clean(reason));
        body.put("reason", r.length() > 200 ? r.substring(0, 200) : r);
        up.send("action.lock", body);
    }

    public String unlockBy(Principal who) {
        boolean owner = !who.console() && who.player.uuid.equals(cfg.owner);
        String err = lock.unlock(owner, who.console());
        if (err != null) {
            aud(who.player, "unlock-refused", "-", "locked", "locked", err);
            return err;
        }
        aud(who.player, "unlock", "-", "locked", "unlocked", "write lock cleared");
        recheck();
        note.stateChanged();
        return "write lock cleared" + (armed ? "; writes are armed" : "; writes stay disarmed: " + disarmReason);
    }

    // ---- time -----------------------------------------------------------------------------------------

    /** Call about once a second (and any time): expiries and the 30 s re-check. */
    public void tick() {
        long now = clock.now();
        if (lastRecheck == Long.MIN_VALUE || now - lastRecheck >= RECHECK_MS || now < lastRecheck) recheck();
        for (Iterator<PromptRec> it = prompts.values().iterator(); it.hasNext();) {
            PromptRec r = it.next();
            if (now >= r.expires) {
                it.remove();
                audSys("prompt-expired", r.requestId, "-", "-", "no answer in 60 s");
                note.promptClosed(r.playerUuid, r.token, "expired");
            }
        }
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext();) {
            Pending p = it.next();
            if (now - p.sentAt >= UNKNOWN_AFTER_MS) {
                it.remove();
                audSys("result", p.cap, p.id, "unknown", "no answer in " + (UNKNOWN_AFTER_MS / 1000) + " s; never retried");
                note.result(p.playerUuid, p.id, p.cap, "unknown", "no answer from the control service; check outside the game", Collections.<String, String>emptyMap(), "", false);
            }
        }
    }

    /** A player left: their open confirmations and requests are dropped, their rate bucket forgotten. */
    public void playerLeft(String uuid) {
        for (PromptRec r : new ArrayList<>(prompts.values())) { // a copy: an audit failure below may disarm and clear the map
            if (r.playerUuid.equals(uuid) && prompts.remove(r.token) != null) {
                audSys("token-drop", r.requestId, "-", "-", "player left");
                cancelUpstream(r);
                resolveCancelled(r, "player left");
            }
        }
        bucket.forget(uuid);
    }

    // ---- text for commands and the client -----------------------------------------------------------------

    public List<String> statusLines() {
        Gate.Result r = recheck();
        List<String> out = new ArrayList<>();
        boolean locked = lock.isLocked();
        out.add(
            (locked ? "\u00a7cLOCKED\u00a7r " : "") + (armed ? "\u00a7awrites ARMED\u00a7r" : "\u00a7cwrites DISARMED\u00a7r: " + disarmReason));
        Msg.Policy pol = policy;
        Msg.State hs = hstate;
        out.add(
            " link: " + (up.ready() ? "ready" : "not ready (" + facts.linkNote() + ")") + (pol == null ? "" : " | policy " + pol.revision + (pol.dryRun ? " | DRY RUN" : "")));
        out.add(" lock: game " + (locked ? "SET " + lock.info() : "clear") + " | hermes " + (hs == null ? "unknown" : hs.locked ? "SET " + hs.lockReason : "clear"));
        out.add(" edit tool also locked by the write lock: " + cfg.lockAlsoLocksEdit);
        if (!r.overrideNote.isEmpty()) out.add(" " + r.overrideNote);
        out.add(" prompts " + prompts.size() + " | requests waiting " + pending.size() + " | audit " + audit.file().getPath() + " (" + audit.lines() + " lines this run)");
        return out;
    }

    /** Runs the gate and returns every 7.1 check; also writes each one to the audit log. */
    public List<String> verifyLines() {
        Gate.Result r = recheck();
        List<String> out = new ArrayList<>();
        audSys("verify", "gate", "-", r.armed ? "armed" : "disarmed", r.armed ? "all checks pass" : r.reason);
        for (Gate.Check c : r.checks) {
            String color = c.status == Gate.Status.PASS ? "\u00a7a" : c.status == Gate.Status.FAIL ? "\u00a7c" : "\u00a7e";
            out.add(" " + color + c.status + "\u00a7r " + c.name + (c.detail.isEmpty() ? "" : " - " + c.detail));
            audSys("verify", c.name, "-", c.status.toString(), c.detail);
        }
        out.add(r.armed ? "\u00a7aall checks pass: writes are armed\u00a7r" : "\u00a7cDISARMED: " + r.reason + "\u00a7r");
        return out;
    }

    /** The state the client shows (JSON, small). */
    public String stateJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        Gate.Result r = last;
        m.put("armed", Boolean.valueOf(armed));
        m.put("reason", armed ? "" : clean(disarmReason));
        m.put("locked", Boolean.valueOf(lock.isLocked()));
        m.put("lockInfo", clean(lock.info()));
        Msg.State hs = hstate;
        m.put("hermesLocked", Boolean.valueOf(hs != null && hs.locked));
        Msg.Policy pol = policy;
        m.put("dryRun", Boolean.valueOf(pol != null && pol.dryRun));
        m.put("overridden", Boolean.valueOf(r != null && r.overrideActive));
        m.put("link", Boolean.valueOf(up.ready()));
        m.put("revision", pol == null ? "" : clean(pol.revision));
        Map<String, Object> caps = new LinkedHashMap<>();
        if (pol != null) {
            for (Map.Entry<String, Msg.CapInfo> e : pol.capabilities.entrySet()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("tier", Long.valueOf(e.getValue().tier));
                c.put("confirm", Boolean.valueOf(e.getValue().confirm));
                c.put("enabled", Boolean.valueOf(e.getValue().enabled));
                caps.put(e.getKey(), c);
            }
        }
        m.put("capabilities", caps);
        m.put("boards", cleanList(pol == null ? null : pol.boards));
        m.put("profiles", cleanList(pol == null ? null : pol.profiles));
        m.put("services", cleanList(pol == null ? null : pol.services));
        m.put("jobs", cleanList(pol == null ? null : pol.jobs));
        m.put("agents", cleanList(pol == null ? null : pol.agents));
        return StrictJson.write(m);
    }

    private static List<Object> cleanList(List<String> l) {
        List<Object> out = new ArrayList<>();
        if (l != null) for (String s : l) out.add(clean(s));
        return out;
    }
}
