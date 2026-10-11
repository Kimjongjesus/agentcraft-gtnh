import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.write.core.Controller;
import dev.agentcraft.gtnh.write.core.Gate;
import dev.agentcraft.gtnh.write.core.WriteAudit;
import dev.agentcraft.gtnh.write.core.WriteLock;
import dev.agentcraft.gtnh.write.proto.Hex;
import dev.agentcraft.gtnh.write.proto.Msg;

/** The controller: request order, confirm tokens, lock, disarm, audit, results. Pure Java with fakes for the link and the players. */
public class ControllerCheck {

    static final String TOK1 = "11111111111111111111111111111111", TOK2 = "22222222222222222222222222222222", TOK3 = "33333333333333333333333333333333";
    static final Controller.Player OWNER = new Controller.Player(Check.OWNER, "Owner", true);
    static final Controller.Player OTHER = new Controller.Player(Check.OTHER, "Other", false);

    static final class Sent {

        final String type, id;
        final Map<String, Object> body;

        Sent(String type, String id, Map<String, Object> body) {
            this.type = type;
            this.id = id;
            this.body = body;
        }
    }

    static final class Rig implements Controller.Uplink, Controller.Notifier, Controller.Presence {

        Check.FakeClock clock = new Check.FakeClock();
        File dir = Check.tmpDir("ctl");
        WriteAudit audit;
        WriteLock lock;
        Check.Facts facts = new Check.Facts();
        Controller c;
        boolean ready = true;
        /** queued mode: frames wait in {@link #queue} until {@link #flush} (the real link's outbox); {@link #sent} is the wire */
        boolean queued, closeOnInvalidate;
        List<Sent> queue = new ArrayList<>();
        int invalidations;
        int ids;
        List<Sent> sent = new ArrayList<>();
        List<String> closed = new ArrayList<>(), prompts = new ArrayList<>(), results = new ArrayList<>(), chats = new ArrayList<>();
        int stateChanges;
        String presenceNow = null;
        Controller.Settings cfg = new Controller.Settings();

        Rig() {
            this(null);
        }

        Rig(File auditFile) {
            audit = new WriteAudit(auditFile != null ? auditFile : new File(dir, "audit.log"), 8L * 1024 * 1024, clock);
            lock = new WriteLock(new File(dir, "write-lock.json"), clock);
            cfg.owner = Check.OWNER;
            final WriteAudit au = audit;
            final WriteLock lk = lock;
            facts = new Check.Facts() {

                @Override
                public boolean auditWritable() {
                    return au.writable();
                }

                @Override
                public boolean gameLocked() {
                    return lk.isLocked();
                }

                @Override
                public boolean overrideTainted() {
                    return c != null && c.overrideTainted();
                }
            };
            c = new Controller(cfg, clock, audit, lock, facts, this, this, this);
            Msg.Policy p = policy();
            c.onLinkUp(p, state());
            c.recheck();
        }

        static Msg.Policy policy() {
            Msg.Policy p = new Msg.Policy();
            p.revision = "r1";
            p.actors.add(Check.OWNER);
            p.boards.add("main");
            p.profiles.add("builder-a");
            p.services.add("service-1");
            p.jobs.add("job-a1");
            p.agents.add("helper-a");
            String[] names = { "decision.answer", "card.create", "card.edit", "agent.chat", "agent.ask", "card.dispatch", "service.restart", "cron.run" };
            int[] tiers = { 1, 1, 1, 1, 1, 2, 2, 2 };
            for (int i = 0; i < names.length; i++) {
                Msg.CapInfo ci = new Msg.CapInfo();
                ci.tier = tiers[i];
                ci.enabled = true;
                ci.confirm = names[i].equals("card.dispatch");
                p.capabilities.put(names[i], ci);
            }
            return p;
        }

        static Msg.State state() {
            return new Msg.State();
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public String send(String type, Map<String, Object> body) {
            if (!ready) return null;
            String id = "w-" + (++ids);
            (queued ? queue : sent).add(new Sent(type, id, body));
            return id;
        }

        @Override
        public void invalidate() {
            invalidations++;
            queue.clear();
            if (closeOnInvalidate) ready = false;
        }

        /** The writer thread catching up: everything still queued goes on the wire. */
        void flush() {
            sent.addAll(queue);
            queue.clear();
        }

        @Override
        public void stateChanged() {
            stateChanges++;
        }

        @Override
        public void prompt(String uuid, String requestId, String token, long msLeft, Map<String, String> summary) {
            prompts.add(uuid + "|" + requestId + "|" + token);
        }

        @Override
        public void promptClosed(String uuid, String token, String why) {
            closed.add(token + "|" + why);
        }

        @Override
        public void result(String uuid, String requestId, String cap, String status, String error, Map<String, String> result, String audit, boolean dryRun) {
            results.add(uuid + "|" + cap + "|" + status + "|" + error + "|" + dryRun);
        }

        @Override
        public void chat(String uuid, String conversation, String agentId, String text, boolean fin) {
            chats.add(uuid + "|" + text + "|" + fin);
        }

        @Override
        public String check(Controller.Player p, int radius) {
            return presenceNow;
        }

        int count(String type) {
            int n = 0;
            for (Sent s : sent) if (s.type.equals(type)) n++;
            return n;
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }

        String lastResult() {
            return results.isEmpty() ? "" : results.get(results.size() - 1);
        }

        /** Advances the clock past the token bucket so the next request is not rate limited. */
        void wait2s() {
            clock.t += 2100;
        }

        /** Requests a dispatch and delivers the control service's prompt; returns the request id. */
        String dispatch(String card, String token) {
            wait2s();
            int before = sent.size();
            c.handleRequest(OWNER, "card.dispatch", "{\"card\":\"" + card + "\",\"board\":\"main\",\"profile\":\"builder-a\"}");
            if (sent.size() == before) return null;
            String id = last().id;
            Msg.Prompt p = new Msg.Prompt();
            p.type = "action.prompt";
            p.re = id;
            p.token = token;
            p.expiresAt = clock.t + 60_000;
            for (String k : new String[] { "card", "title", "board", "profile", "model", "body" }) p.summary.put(k, k + "-v");
            c.onFrame(p);
            return id;
        }

        String auditText() throws Exception {
            return new String(Files.readAllBytes(audit.file().toPath()), StandardCharsets.UTF_8);
        }
    }

    interface Tweak {

        void apply(Rig r);
    }

    static final Object[][] BREAKERS = {
        { "dedicated", (Tweak) r -> r.facts.dedicated = false },
        { "online-mode", (Tweak) r -> r.facts.online = false },
        { "whitelist off", (Tweak) r -> r.facts.wlOn = false },
        { "whitelist add", (Tweak) r -> r.facts.whitelist.add(Check.OTHER) },
        { "op added", (Tweak) r -> r.facts.ops.add(Check.OTHER) },
        { "owner malformed", (Tweak) r -> r.facts.owner = "Steve" },
        { "owner v3", (Tweak) r -> r.facts.owner = "00000000-0000-3000-8000-000000000001" },
        { "link", (Tweak) r -> r.facts.linkReady = false },
        { "policy actors", (Tweak) r -> r.facts.actors.add(Check.OTHER) },
        { "hermes not armed", (Tweak) r -> r.facts.hArmed = false },
        { "hermes lock", (Tweak) r -> r.facts.hLocked = true },
        { "clock", (Tweak) r -> r.facts.clockFault = true },
    };

    static final Object[][] UNDO = {
        { "dedicated", (Tweak) r -> r.facts.dedicated = true },
        { "online-mode", (Tweak) r -> r.facts.online = true },
        { "whitelist off", (Tweak) r -> r.facts.wlOn = true },
        { "whitelist add", (Tweak) r -> r.facts.whitelist.remove(Check.OTHER) },
        { "op added", (Tweak) r -> r.facts.ops.remove(Check.OTHER) },
        { "owner malformed", (Tweak) r -> r.facts.owner = Check.OWNER },
        { "owner v3", (Tweak) r -> r.facts.owner = Check.OWNER },
        { "link", (Tweak) r -> r.facts.linkReady = true },
        { "policy actors", (Tweak) r -> r.facts.actors.remove(Check.OTHER) },
        { "hermes not armed", (Tweak) r -> r.facts.hArmed = true },
        { "hermes lock", (Tweak) r -> r.facts.hLocked = false },
        { "clock", (Tweak) r -> r.facts.clockFault = false },
    };

    public static void main(String[] a) throws Exception {
        basics();
        refusals();
        limits();
        tokens();
        gateDrops();
        lockRules();
        linkAndResults();
        override();
        auditRules();
        uplinkInvalidation();
        tokenLifecycle();
        Check.summary("ControllerCheck");
    }

    static void basics() throws Exception {
        Rig r = new Rig();
        Check.ok(r.c.armed(), "healthy rig is armed");
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"status?\"}");
        Check.eq(r.count("action.request"), 1, "one request frame sent");
        Map<String, Object> body = r.last().body;
        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) body.get("actor");
        Check.eq(actor.get("uuid"), Check.OWNER, "actor uuid comes from the authenticated sender");
        Check.eq(actor.get("name"), "Owner", "actor name from the sender");
        Check.eq(body.get("capability"), "agent.ask", "capability");
        Check.eq(body.get("tier"), Long.valueOf(1), "tier from the table, not from the client");
        Check.eq(body.keySet().toString(), "[actor, capability, tier, args]", "body keys are exactly the contract's");
        Check.eq(r.c.pendingRequests(), 1, "request is pending a result");
        // client-supplied fields can't reach the frame
        for (String bad : new String[] { "actor", "id", "nonce", "ts", "tier", "session" }) {
            r.wait2s();
            int n = r.sent.size();
            r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\",\"" + bad + "\":\"" + Check.OTHER + "\"}");
            Check.eq(r.sent.size(), n, "extra client key '" + bad + "' is refused, nothing sent");
        }
        // a request names no tier at all: a different tier can't be asked for (the client has no such field)
        r.wait2s();
        r.c.handleRequest(OWNER, "nope.cap", "{}");
        Check.ok(r.lastResult().contains("refused|unknown capability"), "unknown capability refused");
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "not json");
        Check.ok(r.lastResult().contains("bad arguments"), "unparseable args refused");
        r.wait2s();
        char[] big = new char[9000];
        Arrays.fill(big, 'a');
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"" + new String(big) + "\"}");
        Check.ok(r.lastResult().contains("refused"), "oversized args refused");
        Check.ok(r.auditText().contains("action=request where=agent.ask"), "audit has the request line");
        Check.ok(r.auditText().contains("action=refused"), "audit has refusal lines");
    }

    static void refusals() throws Exception {
        Rig r = new Rig();
        r.c.handleRequest(OTHER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        Check.eq(r.sent.size(), 0, "another player: nothing sent");
        Check.ok(r.lastResult().contains("only the configured owner"), "another player is told only the owner may write");
        Controller.Player op2 = new Controller.Player(Check.OTHER, "OtherOp", true);
        r.wait2s();
        r.c.handleRequest(op2, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        Check.eq(r.sent.size(), 0, "an op who is not the owner can't write either");
        // link down fails at once and nothing is queued
        r.ready = false;
        r.facts.linkReady = false;
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        Check.ok(r.lastResult().contains("disarmed") || r.lastResult().contains("link"), "link down: refused at once (" + r.lastResult() + ")");
        r.ready = true;
        r.facts.linkReady = true;
        r.c.recheck();
        Check.eq(r.sent.size(), 0, "link back: nothing that failed before was queued or replayed");
        // policy: disabled capability, name not offered
        r.c.policy().capabilities.get("cron.run").enabled = false;
        r.wait2s();
        r.c.handleRequest(OWNER, "cron.run", "{\"job\":\"job-a1\"}");
        Check.ok(r.lastResult().contains("policy has cron.run off"), "disabled capability refused");
        r.wait2s();
        r.c.handleRequest(OWNER, "service.restart", "{\"service\":\"hypervisor\"}");
        Check.ok(r.lastResult().contains("does not offer"), "a service the policy does not list is refused locally");
        r.wait2s();
        r.c.handleRequest(OWNER, "card.dispatch", "{\"card\":\"c\",\"board\":\"main\",\"profile\":\"root\"}");
        Check.ok(r.lastResult().contains("does not offer"), "a builder profile the policy does not list is refused");
        // presence (tier 2 only)
        r.presenceNow = "you are 40 blocks from the nearest HQ anchor";
        r.wait2s();
        r.c.handleRequest(OWNER, "service.restart", "{\"service\":\"service-1\"}");
        Check.ok(r.lastResult().contains("needs you at the HQ"), "tier 2 refused when away from the HQ");
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        Check.eq(r.count("action.request"), 1, "tier 1 does not need presence");
        r.cfg.presenceRadius = 0;
        r.wait2s();
        r.c.handleRequest(OWNER, "service.restart", "{\"service\":\"service-1\"}");
        Check.eq(r.count("action.request"), 2, "presenceRadius 0 disables the presence rule");
        r.cfg.presenceRadius = 8;
        // token bucket: burst 3 then refuse, refill 1 per 2 s
        Rig b = new Rig();
        b.clock.t += 10_000;
        for (int i = 0; i < 3; i++) b.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q" + i + "\"}");
        Check.eq(b.count("action.request"), 3, "burst of 3 goes through");
        b.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q4\"}");
        Check.eq(b.count("action.request"), 3, "the 4th at once is refused");
        Check.ok(b.lastResult().contains("slow down"), "refusal says why");
        b.clock.t += 1900;
        b.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q5\"}");
        Check.eq(b.count("action.request"), 3, "1.9 s later: still no token");
        b.clock.t += 200;
        b.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q6\"}");
        Check.eq(b.count("action.request"), 4, "2.1 s later: one token back");
        // a flood does not flood the audit log
        Rig f = new Rig();
        f.clock.t += 10_000;
        long before = f.audit.lines();
        for (int i = 0; i < 200; i++) f.c.handleRequest(OTHER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        Check.ok(f.audit.lines() - before <= 6, "200 refused packets wrote at most a handful of audit lines (" + (f.audit.lines() - before) + ")");
    }

    static void limits() {
        Rig r = new Rig();
        for (int i = 0; i < 20; i++) {
            r.wait2s();
            r.c.handleRequest(OWNER, "decision.answer", "{\"card\":\"c" + i + "\",\"decision\":\"d\",\"choice\":\"Deny\"}");
        }
        Check.eq(r.count("action.request"), 20, "20 decision answers in the hour go through");
        r.wait2s();
        r.c.handleRequest(OWNER, "decision.answer", "{\"card\":\"c99\",\"decision\":\"d\",\"choice\":\"Deny\"}");
        Check.eq(r.count("action.request"), 20, "the 21st is refused");
        Check.ok(r.lastResult().contains("limit reached"), "the refusal names the limit");
        r.clock.t += 3_600_000L;
        r.c.handleRequest(OWNER, "decision.answer", "{\"card\":\"c100\",\"decision\":\"d\",\"choice\":\"Deny\"}");
        Check.eq(r.count("action.request"), 21, "an hour later it works again");
        // dispatch: 1 per card per 10 min
        Rig d = new Rig();
        d.dispatch("c1", TOK1);
        Check.eq(d.count("action.request"), 1, "first dispatch of c1");
        d.dispatch("c1", TOK2);
        Check.eq(d.count("action.request"), 1, "second dispatch of the same card within 10 min refused");
        Check.ok(d.lastResult().contains("limit reached"), "per-card limit named");
        d.dispatch("c2", TOK2);
        Check.eq(d.count("action.request"), 2, "another card is fine");
        d.clock.t += 10 * 60_000L + 1;
        d.dispatch("c1", TOK3);
        Check.eq(d.count("action.request"), 3, "after 10 min the same card again");
        // service.restart: 1 per service per 10 min
        Rig s = new Rig();
        s.c.handleRequest(OWNER, "service.restart", "{\"service\":\"service-1\"}");
        s.wait2s();
        s.c.handleRequest(OWNER, "service.restart", "{\"service\":\"service-1\"}");
        Check.eq(s.count("action.request"), 1, "service restart twice in 10 min: second refused");
        // cron.run overall 12 per hour
        Rig j = new Rig();
        j.c.policy().jobs.clear();
        for (int i = 0; i < 14; i++) j.c.policy().jobs.add("job-" + i);
        for (int i = 0; i < 14; i++) {
            j.wait2s();
            j.c.handleRequest(OWNER, "cron.run", "{\"job\":\"job-" + i + "\"}");
        }
        Check.eq(j.count("action.request"), 12, "cron.run: 12 per hour overall");
        // agent.ask 10 per minute
        Rig q = new Rig();
        for (int i = 0; i < 12; i++) {
            q.clock.t += 2100;
            q.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q" + i + "\"}");
        }
        Check.eq(q.count("action.request"), 10, "agent.ask: 10 per minute (the bucket allows 1 per 2 s, the window cuts at 10)");
    }

    static void tokens() throws Exception {
        Rig r = new Rig();
        String id = r.dispatch("c1", TOK1);
        Check.eq(r.prompts.size(), 1, "prompt delivered to the requesting player");
        Check.ok(r.prompts.get(0).startsWith(Check.OWNER + "|" + id + "|" + TOK1), "prompt goes to the requester only");
        Check.eq(r.c.openPrompts(), 1, "one open token");
        // wrong player
        r.c.handleConfirm(OTHER, TOK1);
        Check.eq(r.count("action.confirm"), 0, "another player can't confirm");
        Check.ok(r.c.promptOpen(TOK1), "the token is not consumed by the wrong player");
        Check.ok(r.auditText().contains("confirm by a player who did not request it"), "wrong-player confirm is audited");
        // unknown token
        r.wait2s();
        r.c.handleConfirm(OWNER, TOK2);
        Check.eq(r.count("action.confirm"), 0, "unknown token refused");
        // confirm
        r.wait2s();
        r.c.handleConfirm(OWNER, TOK1);
        Check.eq(r.count("action.confirm"), 1, "owner confirms");
        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) r.last().body.get("actor");
        Check.eq(actor.get("uuid"), Check.OWNER, "confirm carries the sender's uuid");
        Check.eq(r.last().body.get("token"), TOK1, "confirm carries the token");
        Check.ok(!r.c.promptOpen(TOK1), "single use: the token is gone");
        r.wait2s();
        r.c.handleConfirm(OWNER, TOK1);
        Check.eq(r.count("action.confirm"), 1, "a second confirm of the same token sends nothing");
        // result after confirm arrives under the confirm frame's id
        Msg.Result res = new Msg.Result();
        res.type = "action.result";
        res.re = r.last().id;
        res.status = "applied";
        res.dryRun = false;
        int before = r.results.size();
        r.c.onFrame(res);
        Check.eq(r.results.size(), before + 1, "a result naming the confirm id reaches the player");
        Check.ok(r.results.get(r.results.size() - 1).contains("card.dispatch|applied"), "mapped back to card.dispatch");
        Check.eq(r.c.pendingRequests(), 0, "request finished");

        // expiry after 60 s
        Rig e = new Rig();
        e.dispatch("c1", TOK1);
        e.clock.t += 59_000;
        e.c.tick();
        Check.ok(e.c.promptOpen(TOK1), "still open at 59 s");
        e.clock.t += 1_100;
        e.c.tick();
        Check.ok(!e.c.promptOpen(TOK1), "closed after 60 s");
        Check.ok(e.closed.get(e.closed.size() - 1).contains("expired"), "the client is told it expired");
        e.wait2s();
        e.c.handleConfirm(OWNER, TOK1);
        Check.eq(e.count("action.confirm"), 0, "confirming an expired token sends nothing");
        // expiry caught at confirm time even without a tick
        Rig e2 = new Rig();
        e2.dispatch("c1", TOK1);
        e2.clock.t += 61_000;
        e2.c.handleConfirm(OWNER, TOK1);
        Check.eq(e2.count("action.confirm"), 0, "expired is refused at confirm time too");
        // prompt for a request that does not exist / wrong type / already expired
        Rig p = new Rig();
        Msg.Prompt orphan = new Msg.Prompt();
        orphan.re = "nope";
        orphan.token = TOK1;
        orphan.expiresAt = p.clock.t + 60_000;
        p.c.onFrame(orphan);
        Check.eq(p.c.openPrompts(), 0, "a prompt for no request is dropped");
        p.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        orphan.re = p.last().id;
        p.c.onFrame(orphan);
        Check.eq(p.c.openPrompts(), 0, "a prompt for a non-dispatch request is dropped");
        // a newer prompt replaces the player's older one
        Rig n = new Rig();
        n.dispatch("c1", TOK1);
        n.dispatch("c2", TOK2);
        Check.eq(n.c.openPrompts(), 1, "one open confirmation per player");
        Check.ok(!n.c.promptOpen(TOK1) && n.c.promptOpen(TOK2), "the newer one wins");
        // cancel
        n.c.handleCancel(OTHER, TOK2);
        Check.ok(n.c.promptOpen(TOK2), "another player can't cancel it");
        n.wait2s();
        n.c.handleCancel(OWNER, TOK2);
        Check.ok(!n.c.promptOpen(TOK2), "the owner cancels");
        Check.eq(n.count("action.cancel"), 2, "action.cancel sent for the replaced prompt (F8) and for the owner's cancel");
        n.wait2s();
        n.c.handleConfirm(OWNER, TOK2);
        Check.eq(n.count("action.confirm"), 0, "no confirm after cancel");
        // presence re-checked at confirm
        Rig pr = new Rig();
        pr.dispatch("c1", TOK1);
        pr.presenceNow = "you are 99 blocks away";
        pr.wait2s();
        pr.c.handleConfirm(OWNER, TOK1);
        Check.eq(pr.count("action.confirm"), 0, "tier 2 presence is re-checked at confirm");
        // a player leaving drops their token
        Rig lv = new Rig();
        lv.dispatch("c1", TOK1);
        lv.c.playerLeft(Check.OWNER);
        Check.eq(lv.c.openPrompts(), 0, "player left: token dropped");
    }

    static void gateDrops() throws Exception {
        for (int i = 0; i < BREAKERS.length; i++) {
            String name = (String) BREAKERS[i][0];
            // (a) the 30 s / explicit re-check disarms and drops the token
            Rig r = new Rig();
            r.dispatch("c1", TOK1);
            Check.ok(r.c.promptOpen(TOK1), name + ": token open before");
            ((Tweak) BREAKERS[i][1]).apply(r);
            r.clock.t += 31_000;
            r.c.tick(); // the periodic re-check
            Check.ok(!r.c.armed(), name + ": disarmed by the periodic check");
            Check.ok(!r.c.promptOpen(TOK1) && r.c.openPrompts() == 0, name + ": confirm token dropped on disarm");
            Check.ok(r.closed.size() > 0 && r.closed.get(r.closed.size() - 1).startsWith(TOK1), name + ": the client is told the screen is closed");
            r.wait2s();
            r.c.handleConfirm(OWNER, TOK1);
            Check.eq(r.count("action.confirm"), 0, name + ": the dropped token can't be confirmed");
            int before = r.count("action.request");
            r.wait2s();
            r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
            Check.eq(r.count("action.request"), before, name + ": new requests refused while disarmed");
            Check.ok(r.lastResult().contains("disarmed") || r.lastResult().contains("owner"), name + ": refusal says why (" + r.lastResult() + ")");
            Check.ok(r.c.statusLines().get(0).contains("DISARMED"), name + ": status says why");
            // (b) it re-arms on its own once every check passes again
            ((Tweak) UNDO[i][1]).apply(r);
            r.c.recheck();
            Check.ok(r.c.armed(), name + ": re-armed by itself when the cause is gone");
            r.wait2s();
            r.c.handleConfirm(OWNER, TOK1);
            Check.eq(r.count("action.confirm"), 0, name + ": the token stays dead after re-arming");

            // (c) no periodic check at all: the confirm itself re-runs the gate
            Rig q = new Rig();
            q.dispatch("c1", TOK1);
            ((Tweak) BREAKERS[i][1]).apply(q);
            q.wait2s();
            q.c.handleConfirm(OWNER, TOK1);
            Check.eq(q.count("action.confirm"), 0, name + ": confirm re-runs the gate and refuses");
            Check.ok(!q.c.armed() && !q.c.promptOpen(TOK1), name + ": ... and disarms and drops the token");
        }
        // game lock and audit failure are covered by their own tests (lockRules, auditRules)
    }

    static void lockRules() throws Exception {
        Rig r = new Rig();
        r.dispatch("c1", TOK1);
        r.wait2s();
        r.sent.clear();
        String msg = r.c.lockBy(new Controller.Principal(OWNER), "maintenance");
        Check.ok(msg.contains("locked"), "an op locks (" + msg + ")");
        Check.ok(r.lock.isLocked(), "lock held");
        Check.ok(new File(r.dir, "write-lock.json").isFile(), "lock persisted to write-lock.json");
        Check.ok(!r.c.promptOpen(TOK1) && r.c.openPrompts() == 0, "lock drops open confirmation tokens");
        Check.ok(!r.c.armed(), "lock disarms");
        Check.eq(r.sent.size(), 1, "the lock sends exactly one frame");
        Check.eq(r.last().type, "action.lock", "... the action.lock notice");
        Check.ok(((String) r.last().body.get("reason")).contains("maintenance"), "notice carries the reason");
        r.wait2s();
        r.c.handleConfirm(OWNER, TOK1);
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        r.wait2s();
        r.c.handleCancel(OWNER, TOK1);
        Check.eq(r.sent.size(), 1, "after the lock nothing but the lock notice is ever sent");
        Check.ok(r.c.editLockReason() != null && r.c.editLockReason().contains("write lock"), "the write lock also locks the edit tool (hook)");
        r.cfg.lockAlsoLocksEdit = false;
        Check.eq(r.c.editLockReason(), null, "lockAlsoLocksEdit=false: the edit tool is not locked");
        r.cfg.lockAlsoLocksEdit = true;
        // who may unlock
        Check.ok(r.c.unlockBy(new Controller.Principal(new Controller.Player(Check.OTHER, "Other", true))).contains("only the owner"), "an op who is not the owner can't unlock");
        Check.ok(r.lock.isLocked(), "still locked");
        Check.ok(r.c.unlockBy(new Controller.Principal(new Controller.Player(Check.OTHER, "Other", false))).contains("only the owner"), "a plain player can't unlock");
        Check.ok(r.lock.isLocked(), "still locked (2)");
        Check.ok(r.c.unlockBy(new Controller.Principal(OWNER)).contains("cleared"), "the owner unlocks");
        Check.ok(!r.lock.isLocked() && !new File(r.dir, "write-lock.json").exists(), "lock cleared and file removed");
        Check.ok(r.c.armed(), "unlock re-arms (all checks pass)");
        Check.eq(r.c.editLockReason(), null, "edit tool free again");
        r.c.lockBy(new Controller.Principal(null), "console panic");
        Check.ok(r.lock.isLocked(), "the console can lock");
        Check.ok(r.c.unlockBy(new Controller.Principal(null)).contains("cleared"), "the console can unlock");
        // non-op players can't lock; the packet path uses the same method
        String no = r.c.lockBy(new Controller.Principal(OTHER), "grief");
        Check.ok(no.contains("only ops") && !r.lock.isLocked(), "a non-op can't lock");
        // lock survives a restart; unreadable lock file = locked
        r.c.lockBy(new Controller.Principal(OWNER), "persist me");
        WriteLock again = new WriteLock(new File(r.dir, "write-lock.json"), r.clock);
        again.load();
        Check.ok(again.isLocked() && again.info().contains("persist me"), "a new process reads the lock back");
        // lock notice resent on connect while locked
        Rig k = new Rig();
        k.c.lockBy(new Controller.Principal(OWNER), "before link");
        k.sent.clear();
        k.c.onLinkUp(Rig.policy(), Rig.state());
        Check.eq(k.sent.size(), 1, "link up while locked: only the lock notice is sent");
        Check.eq(k.last().type, "action.lock", "... as the notice");
    }

    static void linkAndResults() throws Exception {
        Rig r = new Rig();
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        String id = r.last().id;
        Msg.Result res = new Msg.Result();
        res.type = "action.result";
        res.re = id;
        res.status = "applied";
        r.c.onFrame(res);
        Check.ok(r.lastResult().startsWith(Check.OWNER + "|agent.ask|applied"), "result delivered to the requester");
        Check.eq(r.c.pendingRequests(), 0, "applied closes the request");
        Msg.Result stray = new Msg.Result();
        stray.type = "action.result";
        stray.re = "nobody";
        stray.status = "applied";
        int n = r.results.size();
        r.c.onFrame(stray);
        Check.eq(r.results.size(), n, "a result for an unknown request is dropped");
        // chat routes to the requester
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.chat", "{\"agent\":\"helper-a\",\"conversation\":\"k1\",\"text\":\"hi\"}");
        Msg.Chat chat = new Msg.Chat();
        chat.type = "action.chat";
        chat.re = r.last().id;
        chat.conversation = "k1";
        chat.agentId = "helper-a";
        chat.text = "hello \u00a7cred";
        chat.fin = true;
        r.c.onFrame(chat);
        Check.ok(r.chats.size() == 1 && r.chats.get(0).startsWith(Check.OWNER + "|hello  cred|true"), "chat reaches the requester with formatting codes stripped");
        // no answer: unknown after 90 s, never retried
        Rig t = new Rig();
        t.wait2s();
        t.c.handleRequest(OWNER, "cron.run", "{\"job\":\"job-a1\"}");
        int sent = t.sent.size();
        t.clock.t += 91_000;
        t.c.tick();
        Check.ok(t.lastResult().contains("cron.run|unknown"), "no answer in 90 s is unknown");
        Check.eq(t.sent.size(), sent, "unknown is never retried");
        // link drops: pending become unknown, tokens dropped, disarmed, requests fail at once
        Rig d = new Rig();
        d.dispatch("c1", TOK1);
        d.ready = false;
        d.facts.linkReady = false;
        d.c.onLinkDown("socket closed");
        Check.eq(d.c.openPrompts(), 0, "connection closed: tokens dropped");
        Check.ok(d.lastResult().contains("unknown"), "pending request reported unknown");
        Check.ok(!d.c.armed(), "link down disarms");
        d.sent.clear();
        d.wait2s();
        d.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        Check.eq(d.sent.size(), 0, "nothing sent while the link is down");
        // control service lock / unarmed state arrives
        Rig h = new Rig();
        h.dispatch("c1", TOK1);
        Msg.State st = new Msg.State();
        st.armed = true;
        st.locked = true;
        st.lockReason = "ops said so";
        h.facts.hLocked = true;
        h.c.onFrame(st);
        Check.ok(!h.c.armed() && h.c.openPrompts() == 0, "Hermes-side lock disarms at once and drops tokens");
        // error frame
        Rig e = new Rig();
        e.wait2s();
        e.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        Msg.Error er = new Msg.Error();
        er.type = "error";
        er.re = e.last().id;
        er.error = "generic";
        e.c.onFrame(er);
        Check.ok(e.lastResult().contains("unknown"), "an error frame about a request reports unknown (never retried)");
    }

    static void override() throws Exception {
        Rig r = new Rig();
        r.facts.prop = "loopback-dry-run";
        r.facts.online = false;
        r.facts.serverIp = "127.0.0.1";
        r.facts.dryRun = true;
        r.c.recheck();
        Check.ok(r.c.armed() && r.c.lastGate().overrideActive, "override active in the rig");
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        Msg.Result res = new Msg.Result();
        res.type = "action.result";
        res.re = r.last().id;
        res.status = "applied";
        res.dryRun = false; // a real execution under the override
        r.c.onFrame(res);
        Check.ok(!r.c.armed(), "a result without dryRun under the override disarms");
        Check.ok(r.c.overrideTainted(), "... and kills the override for the rest of the run");
        Check.ok(!r.c.armed() && r.c.lastGate().get("online-mode").status == Gate.Status.FAIL, "online-mode is a plain FAIL again");
        Check.ok(r.auditText().contains("override-violation"), "the violation is audited");
    }

    static void auditRules() throws Exception {
        Rig r = new Rig();
        String tok = TOK1;
        r.dispatch("c1", tok);
        r.wait2s();
        r.c.handleConfirm(OWNER, tok);
        r.dispatch("c2", TOK2); // left open so the lock has a token to drop
        r.c.lockBy(new Controller.Principal(OWNER), "x");
        r.c.unlockBy(new Controller.Principal(OWNER));
        r.c.verifyLines();
        String log = r.auditText();
        for (String action : new String[] { "action=arm", "action=request", "action=prompt", "action=confirm", "action=lock ", "action=unlock", "action=verify", "action=token-drop", "action=link-up" }) {
            Check.ok(log.contains(action), "audit has a '" + action.trim() + "' line");
        }
        Check.ok(!log.contains(tok), "the confirm token is never written to the audit log");
        Check.ok(!log.contains(Hex.encode(Check.KEY)), "the key is never written to the audit log");
        // verify prints every check
        List<String> v = r.c.verifyLines();
        String all = String.join("\n", v);
        for (String name : new String[] { "dedicated-server", "online-mode", "whitelist-enforced", "whitelist-is-owner", "ops-only-owner", "owner-uuid-v4", "identity-per-request", "control-link",
            "policy-actors-equal-owner", "not-locked-game", "not-locked-hermes", "audit-writable" }) {
            Check.ok(all.contains(name), "verify prints " + name);
        }
        Check.ok(all.contains("PASS") && !all.contains("FAIL"), "verify: all PASS on a healthy rig");
        r.facts.wlOn = false;
        Check.ok(String.join("\n", r.c.verifyLines()).contains("FAIL"), "verify shows FAIL when a check fails");
        // audit unwritable: the file's parent is a regular file
        File d = Check.tmpDir("badaudit");
        File notDir = new File(d, "file");
        Files.write(notDir.toPath(), new byte[] { 1 });
        Rig u = new Rig(new File(notDir, "audit.log"));
        Check.ok(!u.audit.writable(), "audit under a regular file is not writable");
        Check.ok(!u.c.armed(), "unwritable audit: the module is disarmed");
        Check.ok(u.c.disarmReason().contains("audit"), "... and says why (" + u.c.disarmReason() + ")");
        u.wait2s();
        u.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        Check.eq(u.sent.size(), 0, "no audit, no action: nothing is sent");
        // an audit that dies mid-run disarms even if the gate facts still say writable
        Rig m = new Rig();
        Check.ok(m.c.armed(), "healthy before");
        Files.delete(m.audit.file().toPath());
        Files.createDirectory(m.audit.file().toPath()); // a directory where the log should be
        m.wait2s();
        m.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"q\"}");
        Check.eq(m.sent.size(), 0, "audit write fails on the request: nothing sent");
        Check.ok(!m.c.armed(), "audit write failure disarms");
        // state json
        Rig s = new Rig();
        String js = s.c.stateJson();
        Check.ok(js.contains("\"armed\":true") && js.contains("\"capabilities\"") && js.contains("builder-a"), "client state json carries armed + policy display data");
        s.facts.wlOn = false;
        s.c.recheck();
        Check.ok(s.c.stateJson().contains("whitelist-enforced"), "client state json carries the disarm reason");
    }

    // ---- F4: lock and disarm invalidate the uplink -------------------------------------------------------------

    static void uplinkInvalidation() throws Exception {
        // a queued request, then the game lock: nothing queued before the lock reaches the wire after it
        Rig r = new Rig();
        r.sent.clear();
        r.queued = true;
        r.wait2s();
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"queued before the lock\"}");
        Check.eq(r.queue.size(), 1, "F4: the request waits in the outbox, not yet on the wire");
        Check.eq(r.sent.size(), 0, "F4: ... the wire is empty");
        r.c.lockBy(new Controller.Principal(OWNER), "panic");
        Check.ok(r.invalidations >= 1, "F4: the lock invalidates the uplink");
        r.flush(); // the writer thread catches up
        Check.eq(r.count("action.request"), 0, "F4: the request queued before the lock never reaches the wire");
        Check.eq(r.sent.size(), 1, "F4: only the lock notice is on the wire");
        Check.eq(r.sent.get(0).type, "action.lock", "F4: ... and it is action.lock");

        // a queued CONFIRM, then the lock
        Rig q = new Rig();
        q.dispatch("c1", TOK1);
        q.sent.clear();
        q.queued = true;
        q.wait2s();
        q.c.handleConfirm(OWNER, TOK1);
        Check.eq(q.queue.size(), 1, "F4: the confirm waits in the outbox");
        q.c.lockBy(new Controller.Principal(null), "console panic");
        q.flush();
        Check.eq(q.count("action.confirm"), 0, "F4: the confirm queued before the lock never reaches the wire");
        Check.eq(q.sent.size(), 1, "F4: only the lock notice is on the wire (confirm case)");

        // the real link closes on invalidate (not ready afterwards) and announces the lock when it is back up
        Rig k = new Rig();
        k.sent.clear();
        k.queued = true;
        k.closeOnInvalidate = true;
        k.wait2s();
        k.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"x\"}");
        k.c.lockBy(new Controller.Principal(OWNER), "closing link");
        k.flush();
        Check.eq(k.sent.size(), 0, "F4: closing link: nothing at all reaches the wire after the lock");
        k.ready = true;
        k.queued = false;
        k.c.onLinkUp(Rig.policy(), Rig.state());
        Check.eq(k.sent.size(), 1, "F4: ... the reconnect announces the lock");
        Check.eq(k.last().type, "action.lock", "F4: ... as action.lock");

        // a security disarm does the same
        Rig d = new Rig();
        d.sent.clear();
        d.queued = true;
        d.wait2s();
        d.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"queued before the disarm\"}");
        int inv = d.invalidations;
        d.facts.online = false;
        d.c.recheck();
        Check.ok(!d.c.armed(), "F4: disarmed");
        Check.ok(d.invalidations > inv, "F4: the security disarm invalidates the uplink");
        d.flush();
        Check.eq(d.sent.size(), 0, "F4: nothing queued before the disarm reaches the wire");
        // a second recheck while still disarmed does not bounce the link again
        int inv2 = d.invalidations;
        d.c.recheck();
        d.c.recheck();
        Check.eq(d.invalidations, inv2, "F4: staying disarmed does not close the link again");
    }

    // ---- F8: token lifecycle -------------------------------------------------------------------------------------

    static void tokenLifecycle() throws Exception {
        // lock voids a prompted request at once: cancelled, audited, not "unknown" after 90 s
        Rig r = new Rig();
        String id = r.dispatch("c1", TOK1);
        r.results.clear();
        r.c.lockBy(new Controller.Principal(OWNER), "panic");
        Check.eq(r.c.pendingRequests(), 0, "F8: lock resolves the prompted request (nothing left pending)");
        Check.eq(r.results.size(), 1, "F8: exactly one result for the voided request");
        Check.eq(r.results.get(0), Check.OWNER + "|card.dispatch|cancelled|locked|false", "F8: resolved as cancelled: locked");
        Check.ok(r.auditText().contains("result") && r.auditText().contains("cancelled: locked"), "F8: audit line 'cancelled: locked'");
        r.clock.t += 120_000;
        r.c.tick();
        boolean unknown = false;
        for (String x : r.results) if (x.contains("|unknown|")) unknown = true;
        Check.ok(!unknown, "F8: no 'unknown' result 90 s later");
        Check.ok(id != null, "F8: the request existed");

        // disarm: same, 'cancelled: disarmed'
        Rig d = new Rig();
        d.dispatch("c1", TOK1);
        d.results.clear();
        d.facts.online = false;
        d.c.recheck();
        Check.eq(d.c.pendingRequests(), 0, "F8: disarm resolves the prompted request");
        Check.ok(d.results.size() == 1 && d.results.get(0).contains("|cancelled|disarmed: "), "F8: resolved as cancelled: disarmed (" + d.results + ")");
        Check.ok(d.auditText().contains("cancelled: disarmed"), "F8: audit line 'cancelled: disarmed'");
        Check.ok(d.invalidations >= 1, "F8: security disarm invalidates the uplink (closes it, so the control service voids all tokens)");

        // a link that goes down still answers 'unknown' (we do not know what the control service did)
        Rig l = new Rig();
        l.dispatch("c1", TOK1);
        l.results.clear();
        l.c.onLinkDown("test");
        Check.ok(l.results.size() == 1 && l.results.get(0).contains("|unknown|"), "F8: link down: still 'unknown' (" + l.results + ")");

        // policy revision change closes the local prompts; the same revision does not
        Rig p = new Rig();
        p.dispatch("c1", TOK1);
        Msg.Policy same = Rig.policy();
        same.type = "action.policy";
        p.c.onFrame(same);
        Check.ok(p.c.promptOpen(TOK1), "F8: a policy frame with the same revision keeps the prompt");
        p.results.clear();
        Msg.Policy newer = Rig.policy();
        newer.type = "action.policy";
        newer.revision = "r2";
        p.c.onFrame(newer);
        Check.ok(!p.c.promptOpen(TOK1) && p.c.openPrompts() == 0, "F8: a new policy revision closes the Confirm prompt");
        Check.ok(p.closed.get(p.closed.size() - 1).startsWith(TOK1), "F8: ... and tells the screen to close");
        Check.ok(p.results.size() == 1 && p.results.get(0).contains("|cancelled|policy revision changed"), "F8: ... and resolves the request as cancelled (" + p.results + ")");
        p.wait2s();
        p.c.handleConfirm(OWNER, TOK1);
        Check.eq(p.count("action.confirm"), 0, "F8: the closed token cannot be confirmed");

        // cancel does not depend on the request bucket
        Rig b = new Rig();
        b.dispatch("c1", TOK1);
        for (int i = 0; i < 6; i++) b.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"spam\"}");
        Check.ok(b.lastResult().contains("slow down"), "F8: the request bucket is empty (" + b.lastResult() + ")");
        b.c.handleCancel(OWNER, TOK1);
        Check.eq(b.count("action.cancel"), 1, "F8: cancel still goes to the control service with an empty bucket");
        Check.eq(b.last().body.get("token"), TOK1, "F8: ... carrying the token");

        // a replaced prompt and a departing player are cancelled upstream too
        Rig m = new Rig();
        m.dispatch("c1", TOK1);
        m.dispatch("c2", TOK2); // same player: replaces the first
        Check.eq(m.count("action.cancel"), 1, "F8: the replaced prompt is cancelled upstream");
        Check.eq(m.last().body.get("token"), TOK1, "F8: ... the old token");
        Check.ok(m.c.promptOpen(TOK2) && !m.c.promptOpen(TOK1), "F8: only the newer prompt stays");
        m.c.playerLeft(Check.OWNER);
        Check.eq(m.count("action.cancel"), 2, "F8: a departing player's prompt is cancelled upstream");
        Check.eq(m.last().body.get("token"), TOK2, "F8: ... the open token");
        Check.eq(m.c.pendingRequests(), 0, "F8: ... and its request is resolved");
    }
}
