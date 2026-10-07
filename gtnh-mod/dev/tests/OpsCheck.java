import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.edit.EditEngine;
import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.edit.Op;
import dev.agentcraft.gtnh.edit.PanelSpec;
import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.edit.Pos;
import dev.agentcraft.gtnh.edit.RelLayout;
import dev.agentcraft.gtnh.ops.DecisionData;
import dev.agentcraft.gtnh.ops.OpenDecisions;
import dev.agentcraft.gtnh.ops.OpsData;
import dev.agentcraft.gtnh.ops.OpsHealth;
import dev.agentcraft.gtnh.ops.OpsModel;
import dev.agentcraft.gtnh.ops.OpsPanels;
import dev.agentcraft.gtnh.ops.ToastPolicy;

/**
 * Card 5b headless checks: the server's ops model replays a real adapter wire trace
 * (make_ops_trace.py) and matches the adapter's model after every step; the client blob
 * round-trips; caps, display order and the worst-of fleet rule; the decision toast policy (new
 * only, dedupe, no replay after reconnect or restart, per-id cooldown, rate limit, mute); and the
 * four ops panel kinds go through the card 6 layout engine (place, inspect, rebind, resize,
 * export, remove) with no editor-specific code.
 */
public class OpsCheck {

    static int checks;

    static void ok(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) throws Exception {
        PanelTypes.registerBuiltins();
        OpsPanels.registerTypes();
        trace(args.length > 0 ? args[0] : "build/ops-trace.jsonl");
        model();
        blob();
        worst();
        decisions();
        toast();
        toastUnderCap();
        toastOverflow();
        health();
        panels();
        System.out.println("OpsCheck OK: " + checks + " checks");
    }

    // ---- wire trace from the real adapter ------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void trace(String path) throws Exception {
        File f = new File(path);
        ok(f.isFile(), "trace file " + path + " (run dev/tests/make_ops_trace.py first)");
        OpsModel m = new OpsModel();
        int msgs = 0, steps = 0;
        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim()
                .isEmpty()) continue;
            Map<String, Object> o = Json.parseObject(line);
            Map<String, Object> msg = Json.obj(o, "msg");
            if (msg != null) {
                ok(m.apply(Json.str(msg, "type", ""), msg), "ops message accepted");
                msgs++;
                continue;
            }
            Map<String, Object> exp = Json.obj(o, "expect");
            steps++;
            for (String kind : OpsData.KINDS) {
                String plural = "usage".equals(kind) ? "usage" : kind + "s";
                List<String> want = new ArrayList<>();
                for (Object id : Json.arr(exp, plural)) want.add((String) id);
                Collections.sort(want);
                ok(want.equals(m.ids(kind)), "step " + steps + " " + plural + ": mod " + m.ids(kind).size() + " ids, adapter " + want.size());
            }
            Map<String, Object> states = Json.obj(o, "states");
            for (OpsData.Service s : m.view().services) {
                ok(s.state.equals(states.get(s.id)), "step " + steps + " state of " + s.id);
            }
            ok(m.live(), "live after the snapshot");
        }
        ok(msgs > 1000 && steps > 50, "trace is substantial: " + msgs + " messages, " + steps + " steps");
        ok(m.evicted == 0, "the mod's caps never cut what the adapter kept");
        ok(m.view().services.size() <= OpsData.MAX_SERVICES, "service cap");
        System.out.println("  trace: " + msgs + " adapter messages, " + steps + " steps, model equal after every step");
    }

    // ---- model rules ---------------------------------------------------------------------------

    static Map<String, Object> obj(String json) throws Exception {
        return Json.parseObject(json);
    }

    static void model() throws Exception {
        OpsModel m = new OpsModel();
        ok(!m.apply("agent.upsert", obj("{\"agent\":{\"id\":\"x\"}}")), "non-ops messages are not ours");
        ok(m.apply("ops.future.upsert", obj("{\"future\":{\"id\":\"x\"}}")) && m.ignored == 1, "unknown ops types ignored");
        ok(m.apply("ops.remove", obj("{\"kind\":\"service\",\"id\":\"never\"}")), "remove of an unknown id is a no-op");
        ok(!m.live(), "not live before a snapshot");
        m.apply("ops.snapshot", obj("{\"services\":[],\"jobs\":[],\"usage\":[],\"alerts\":[],\"sources\":[],\"limits\":{\"services\":5}}"));
        ok(m.live() && m.takeDirty(), "snapshot: live + dirty");
        ok(!m.takeDirty(), "dirty is taken once");
        for (int i = 0; i < 9; i++) {
            String st = i == 3 ? "down" : i == 6 ? "degraded" : "up";
            m.apply("ops.service.upsert", obj("{\"service\":{\"id\":\"src/s" + i + "\",\"sourceId\":\"src\",\"name\":\"service-" + i + "\",\"group\":\"hosts\",\"state\":\"" + st + "\"}}"));
        }
        ok(m.count("service") == 5, "announced cap (5) holds: " + m.count("service"));
        ok(m.ids("service").contains("src/s3") && m.ids("service").contains("src/s6"), "down and degraded survive the cap");
        m.apply("ops.service.upsert", obj("{\"service\":{\"id\":\"src/s3\",\"sourceId\":\"src\",\"name\":\"service-3\",\"group\":\"hosts\",\"state\":\"up\"}}"));
        OpsData.Service s3 = null;
        for (OpsData.Service s : m.view().services) if (s.id.equals("src/s3")) s3 = s;
        ok(s3 != null && "up".equals(s3.state) && s3.detail.isEmpty(), "upsert replaces the whole entity");
        m.apply("ops.remove", obj("{\"kind\":\"service\",\"id\":\"src/s3\"}"));
        ok(!m.ids("service").contains("src/s3"), "remove");
        m.disconnected();
        ok(!m.live() && m.takeDirty(), "disconnect: offline + dirty");
        // hostile input: long strings, bad enums, huge numbers, section signs
        m.apply("ops.snapshot", obj("{\"services\":[{\"id\":\"x/1\",\"name\":\"" + repeat("n", 500) + "\",\"state\":\"exploded\",\"cpu\":1e9,\"detail\":\"\\u00a7cred\"}],"
            + "\"jobs\":[],\"usage\":[{\"id\":\"x/u\",\"provider\":\"p\",\"window\":\"w\",\"remainingPct\":-5}],\"alerts\":[],\"sources\":[]}"));
        OpsData.Service h = m.view().services.get(0);
        ok(h.name.length() == 40 && "unknown".equals(h.state) && h.cpu == 100 && !h.detail.contains("\u00a7"), "hostile service is bounded");
        ok(m.view().usage.get(0).remainingPct == 0, "remainingPct clamped to 0..100");
        OpsData.Usage none = OpsData.usage(obj("{\"id\":\"x/v\",\"provider\":\"p\",\"window\":\"w\"}"));
        ok(none.remainingPct < 0, "missing remainingPct stays unknown, never 0");
    }

    static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    // ---- client blob ---------------------------------------------------------------------------

    static void blob() throws Exception {
        OpsModel m = new OpsModel();
        m.apply("ops.snapshot", obj("{\"services\":[{\"id\":\"mock/host-a\",\"sourceId\":\"mock\",\"name\":\"host-a\",\"group\":\"hosts\",\"state\":\"up\",\"cpu\":20.5,\"mem\":41.5},"
            + "{\"id\":\"mock/service-2\",\"sourceId\":\"mock\",\"name\":\"service-2\",\"group\":\"services\",\"state\":\"down\",\"since\":1791000000000,\"detail\":\"no answer\"}],"
            + "\"jobs\":[{\"id\":\"mock/job-1\",\"sourceId\":\"mock\",\"name\":\"job-1\",\"lastStatus\":\"ok\",\"enabled\":true,\"nextRun\":1791000600000},"
            + "{\"id\":\"mock/job-2\",\"sourceId\":\"mock\",\"name\":\"job-2\",\"lastStatus\":\"failed\",\"enabled\":true,\"detail\":\"exit 1\"}],"
            + "\"usage\":[{\"id\":\"mock/p-week\",\"sourceId\":\"mock\",\"provider\":\"provider-a\",\"window\":\"week\",\"remainingPct\":12.5,\"resetsAt\":1791100000000}],"
            + "\"alerts\":[{\"id\":\"mock/a1\",\"sourceId\":\"mock\",\"ts\":1791000000000,\"severity\":\"critical\",\"source\":\"probe\",\"title\":\"service-2 down\",\"state\":\"open\"},"
            + "{\"id\":\"mock/a0\",\"sourceId\":\"mock\",\"ts\":1790000000000,\"severity\":\"info\",\"source\":\"probe\",\"title\":\"old\",\"state\":\"resolved\",\"resolvedAt\":1790000500000}],"
            + "\"sources\":[{\"id\":\"mock\",\"name\":\"Mock\",\"state\":\"ok\",\"interval\":10,\"kinds\":[],\"counts\":{}}]}"));
        OpsData.View v = m.view();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OpsData.write(new DataOutputStream(bos), v.live, v.services, v.jobs, v.usage, v.alerts, v.sources);
        OpsData.View back = OpsData.read(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        ok(back.live && back.services.size() == 2 && back.jobs.size() == 2 && back.usage.size() == 1 && back.alerts.size() == 2 && back.sources.size() == 1, "blob counts");
        ok("down".equals(back.services.get(0).state), "services: down first");
        ok("failed".equals(back.jobs.get(0).lastStatus), "jobs: failed first");
        ok("open".equals(back.alerts.get(0).state), "alerts: open first");
        OpsData.Service a = back.services.get(1);
        ok(Math.abs(a.cpu - 20.5F) < 0.01 && Math.abs(a.mem - 41.5F) < 0.01 && a.disk < 0, "percent bars round-trip; missing stays missing");
        ok(Math.abs(back.usage.get(0).remainingPct - 12.5F) < 0.01 && back.usage.get(0).resetsAt == 1791100000000L, "usage round-trip");
        boolean threw = false;
        try {
            OpsData.read(new DataInputStream(new ByteArrayInputStream(new byte[] { 9 })));
        } catch (java.io.IOException e) {
            threw = true;
        }
        ok(threw, "unknown blob version refused");
        ok("in 10m".equals(OpsData.age(1791000600000L, 1791000000000L)) && "2h ago".equals(OpsData.age(1791000000000L - 7_200_000L, 1791000000000L)), "age text");
        ok(OpsData.matches("all", "hosts") && OpsData.matches("", "x") && OpsData.matches("hosts", "hosts", "mock") && !OpsData.matches("hosts", "services", "mock"), "binding filter");
    }

    // ---- worst-of rule -------------------------------------------------------------------------

    static void worst() {
        OpsData.View v = new OpsData.View();
        ok("none".equals(OpsData.worst(v)), "no feed: none");
        v.live = true;
        ok("none".equals(OpsData.worst(v)), "live but empty: none");
        OpsData.Service s = new OpsData.Service();
        s.state = "up";
        v.services.add(s);
        ok("ok".equals(OpsData.worst(v)), "all up: ok");
        s.state = "unknown";
        ok("ok".equals(OpsData.worst(v)), "unknown is grey, not an error");
        s.state = "degraded";
        ok("warn".equals(OpsData.worst(v)), "degraded: warn");
        OpsData.Alert al = new OpsData.Alert();
        al.severity = "critical";
        al.state = "resolved";
        v.alerts.add(al);
        ok("warn".equals(OpsData.worst(v)), "resolved alerts do not count");
        al.state = "open";
        ok("error".equals(OpsData.worst(v)), "open critical alert: error");
        v.live = false;
        ok("none".equals(OpsData.worst(v)), "feed offline: none");
        String[] fam = { "offline", "waiting", "error", "working", "thinking", "idle" };
        String[][] want = { { "offline", "offline", "offline" }, { "waiting", "waiting", "waiting" }, { "error", "error", "error" },
            { "working", "warn", "error" }, { "thinking", "warn", "error" }, { "idle", "warn", "error" } };
        String[] opsW = { "ok", "warn", "error" };
        for (int i = 0; i < fam.length; i++) for (int j = 0; j < opsW.length; j++)
            ok(want[i][j].equals(OpsData.combineFleet(fam[i], opsW[j])), "fleet " + fam[i] + " + ops " + opsW[j]);
        ok("idle".equals(OpsData.combineFleet("idle", "none")), "no ops feed changes nothing");
    }

    // ---- decisions -----------------------------------------------------------------------------

    static DecisionData.Decision dec(String id, long created) {
        DecisionData.Decision d = new DecisionData.Decision();
        d.id = id;
        d.agentId = "builder-a";
        d.agentName = "Builder A";
        d.question = "Approve the plan for job-1?";
        d.taskId = "main:t_1";
        d.createdAt = created;
        return d;
    }

    static void decisions() throws Exception {
        List<DecisionData.Decision> l = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            DecisionData.Decision d = dec("d-main-" + i, 1000 + i);
            d.kind = i % 2 == 0 ? "question" : "permission";
            for (int k = 0; k < 9; k++) d.options.add("option " + k);
            l.add(d);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DecisionData.write(new DataOutputStream(bos), true, l);
        DecisionData.Snapshot s = DecisionData.read(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        ok(s.live && s.open.size() == DecisionData.MAX, "decision blob capped at " + DecisionData.MAX);
        ok(s.open.get(1).options.size() == DecisionData.MAX_OPTIONS && "Approval".equals(s.open.get(1).label()) && "Decision".equals(s.open.get(0).label()), "options capped, labels");
    }

    static List<DecisionData.Decision> list(DecisionData.Decision... ds) {
        List<DecisionData.Decision> l = new ArrayList<>();
        Collections.addAll(l, ds);
        return l;
    }

    static void toast() {
        long t0 = 1_791_000_000_000L, min = 60_000L;
        ToastPolicy p = new ToastPolicy();
        // first list after joining: an old decision is recorded, not toasted; a fresh one toasts
        List<ToastPolicy.Toast> out = p.offer(list(dec("d-old", t0 - 3 * 3600_000L), dec("d-new", t0 - 2 * min)), t0);
        ok(out.size() == 1 && "d-new".equals(out.get(0).decision.id), "only the fresh decision toasts on join");
        ok(p.known("d-old"), "the old one is remembered");
        // same list again (periodic resend): nothing
        ok(p.offer(list(dec("d-old", t0 - 3 * 3600_000L), dec("d-new", t0 - 2 * min)), t0 + 5000).isEmpty(), "dedupe by id");
        // a new decision arrives
        out = p.offer(list(dec("d-old", 0), dec("d-new", 0), dec("d-3", t0 + 10_000)), t0 + 10_000);
        ok(out.size() == 1 && "d-3".equals(out.get(0).decision.id), "a new decision toasts");
        // reconnect: the server resends the same open list -> nothing replays
        ok(p.offer(list(dec("d-old", 0), dec("d-new", 0), dec("d-3", 0)), t0 + 20_000).isEmpty(), "reconnect: no replay");
        // client restart: the persisted seen set comes back -> nothing replays, even young ones
        ToastPolicy q = new ToastPolicy();
        q.load(p.export(), t0 + 30_000);
        ok(q.rememberedCount() == 3, "seen set persisted: " + q.rememberedCount());
        ok(q.offer(list(dec("d-new", t0 - 2 * min), dec("d-3", t0 + 10_000)), t0 + 30_000).isEmpty(), "restart: no replay");
        // per-id cooldown: d-3 closes, re-opens within the cooldown -> no toast; after it -> toast again
        p.offer(list(dec("d-new", 0)), t0 + 40_000);
        ok(p.offer(list(dec("d-new", 0), dec("d-3", t0 + 10_000)), t0 + 50_000).isEmpty(), "re-open inside the cooldown: no toast");
        p.offer(list(dec("d-new", 0)), t0 + 11 * min);
        out = p.offer(list(dec("d-new", 0), dec("d-3", t0 + 10_000)), t0 + 12 * min);
        ok(out.size() == 1 && "d-3".equals(out.get(0).decision.id), "re-open after the cooldown toasts again");
        // global rate limit: 6 new at once -> 3 toasts, the last one says "+3 more"
        ToastPolicy r = new ToastPolicy();
        List<DecisionData.Decision> burst = new ArrayList<>();
        for (int i = 0; i < 6; i++) burst.add(dec("b" + i, t0 + i));
        out = r.offer(burst, t0 + 10);
        ok(out.size() == 3 && out.get(2).more == 3 && "b5".equals(out.get(0).decision.id), "rate limit: 3 toasts, newest first, +3 folded: " + out.size());
        List<DecisionData.Decision> more = new ArrayList<>(burst);
        more.add(dec("b6", t0 + 20));
        out = r.offer(more, t0 + 20);
        ok(out.isEmpty() && r.pendingFolded() == 1, "still limited within the minute");
        ok(!r.summaryDue(t0 + 30) && r.summaryDue(t0 + 61_000) && r.takeFolded(t0 + 61_000) == 1, "summary once a slot frees up");
        // mute: nothing toasts, and nothing replays after unmute
        ToastPolicy u = new ToastPolicy();
        u.muted = true;
        ok(u.offer(list(dec("m1", t0)), t0).isEmpty(), "muted: no toast");
        u.muted = false;
        ok(u.offer(list(dec("m1", t0)), t0 + 1000).isEmpty(), "unmute does not replay");
        ok(u.offer(list(dec("m1", t0), dec("m2", t0 + 2000)), t0 + 2000).size() == 1, "unmuted: new ones toast");
        // bad persisted lines are ignored; stale entries are dropped
        ToastPolicy z = new ToastPolicy();
        z.load("garbage\n\tx\nd-a\tnot-a-number\nd-b\t" + (t0 - 40L * 86400_000L) + "\nd-c\t" + t0 + "\n", t0);
        ok(z.rememberedCount() == 1 && z.known("d-c"), "load ignores bad and expired lines");
    }

    // ---- review r1: the decision cap must never look like a close -------------------------------

    /** What a client gets from the server's OpenDecisions: the real blob, encoded and decoded. */
    static DecisionData.Snapshot wire(OpenDecisions od) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DecisionData.write(new DataOutputStream(bos), true, od.newest(DecisionData.MAX), od.ids(), od.complete());
        return DecisionData.read(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
    }

    static int toastsFor(List<ToastPolicy.Toast> l, String id) {
        int n = 0;
        for (ToastPolicy.Toast t : l) if (id.equals(t.decision.id)) n++;
        return n;
    }

    static void toastUnderCap() throws Exception {
        long t0 = 1_791_000_000_000L, min = 60_000L;
        // 1) the reviewer's probe: d0 toasts, 32+ newer push it out of the details, it stays open the
        // whole time, comes back into the details after the cooldown -> it must NOT toast again
        OpenDecisions od = new OpenDecisions();
        ToastPolicy p = new ToastPolicy();
        p.maxPerMinute = 1000; // rate limit off: count every toast
        od.put(dec("d0", t0));
        int d0 = toastsFor(p.offer(wire(od), t0), "d0");
        ok(d0 == 1, "d0 toasts once when new");
        for (int i = 1; i <= 40; i++) {
            od.put(dec("n" + i, t0 + i * 1000L));
            DecisionData.Snapshot s = wire(od);
            boolean inDetails = false;
            for (DecisionData.Decision d : s.open) inDetails |= "d0".equals(d.id);
            ok(s.complete && s.openIds.contains("d0"), "d0 still listed as open (step " + i + ")");
            if (i >= DecisionData.MAX) ok(!inDetails, "d0 cut from the details at step " + i);
            d0 += toastsFor(p.offer(s, t0 + i * 1000L), "d0");
        }
        DecisionData.Snapshot full = wire(od);
        ok(full.open.size() == DecisionData.MAX && full.openIds.size() == 41 && full.complete, "41 open: 32 details, 41 ids, complete");
        // 12 minutes later (past the 10 min cooldown) the newer ones close one by one; d0 re-enters
        for (int i = 1; i <= 40; i++) {
            od.close("n" + i);
            d0 += toastsFor(p.offer(wire(od), t0 + 12 * min + i * 1000L), "d0");
        }
        ok(wire(od).open.size() == 1 && "d0".equals(wire(od).open.get(0).id), "d0 back in the details");
        ok(d0 == 1, "continuously open d0 never re-toasts after cap eviction and re-entry: " + d0);
        // 2) a genuine close and reopen still works: closed (missing from a COMPLETE list) and
        // re-opened after the cooldown -> toasts again; re-opened inside the cooldown -> no toast
        od.close("d0");
        ok(p.offer(wire(od), t0 + 13 * min).isEmpty(), "close: nothing");
        od.put(dec("d0", t0));
        ok(toastsFor(p.offer(wire(od), t0 + 14 * min), "d0") == 1, "genuine reopen 14 min after the last toast: toasts again");
        od.close("d0");
        p.offer(wire(od), t0 + 15 * min);
        od.put(dec("d0", t0));
        ok(p.offer(wire(od), t0 + 16 * min).isEmpty(), "genuine reopen 2 min after the last toast: cooldown, no toast");
        ToastPolicy q = new ToastPolicy();
        q.offer(list(dec("r1", t0)), t0);
        q.offer(new ArrayList<DecisionData.Decision>(), t0 + min); // genuine close
        ok(q.offer(list(dec("r1", t0)), t0 + 2 * min).isEmpty(), "genuine reopen inside the cooldown: no toast");
        q.offer(new ArrayList<DecisionData.Decision>(), t0 + 3 * min);
        ok(toastsFor(q.offer(list(dec("r1", t0)), t0 + 11 * min), "r1") == 1, "genuine reopen after the cooldown toasts again");
        // 3) the server's own hold cap: details for 32, ids for the rest, still complete
        OpenDecisions small = new OpenDecisions(DecisionData.MAX, 8);
        for (int i = 0; i < 40; i++) small.put(dec("h" + i, t0 + i));
        ok(small.size() == 40 && small.complete() && small.ids().size() == 40 && small.newest(99).size() == 32, "hold cap keeps evicted ids open");
        ok("h39".equals(small.ids().get(0)) && small.ids().contains("h0"), "ids newest first, evicted ones included");
        ok(small.close("h0") && !small.ids().contains("h0") && small.size() == 39, "an evicted id closes explicitly");
        small.put(dec("h0", t0)); // re-opened: back in the details, out of the id-only set
        ok(small.size() == 40 && small.newest(99).size() == 32, "re-put keeps the counts");
        // 4) id-only overflow: the list turns incomplete, and an incomplete list never closes anything
        for (int i = 40; i < 60; i++) small.put(dec("h" + i, t0 + i));
        ok(!small.complete(), "id-only overflow -> incomplete");
        ToastPolicy r = new ToastPolicy();
        r.maxPerMinute = 1000;
        r.offer(list(dec("x0", t0)), t0); // x0 toasts, open
        ok(r.offer(new ArrayList<DecisionData.Decision>(), new ArrayList<String>(), false, t0 + min).isEmpty(), "incomplete empty list");
        ok(r.offer(list(dec("x0", t0)), t0 + 12 * min).isEmpty(), "x0 missing only from an INCOMPLETE list was not closed: no re-toast");
        small.reset();
        ok(small.complete() && small.isEmpty(), "a new adapter snapshot starts complete");
        // 5) wire: more than MAX_IDS open ids -> the blob says incomplete
        OpenDecisions big = new OpenDecisions(DecisionData.MAX, 10_000);
        for (int i = 0; i < DecisionData.MAX_IDS + 5; i++) big.put(dec("b" + i, t0 + i));
        DecisionData.Snapshot bs = wire(big);
        ok(!bs.complete && bs.openIds.size() == DecisionData.MAX_IDS && bs.open.size() == DecisionData.MAX, "over MAX_IDS: cut and marked incomplete");
        // 6) an open id that arrives without details (older than the newest 32) is remembered, no toast
        ToastPolicy u = new ToastPolicy();
        u.maxPerMinute = 1000;
        List<String> ids = new ArrayList<>();
        ids.add("old-1");
        ok(u.offer(new ArrayList<DecisionData.Decision>(), ids, true, t0).isEmpty() && u.known("old-1"), "id-only open decision remembered");
        ok(u.offer(list(dec("old-1", t0)), t0 + 12 * min).isEmpty(), "and does not toast when its details arrive later");
    }

    // ---- review r2: client memory overflow must never re-toast a possibly-open decision ---------

    /** One run of the reviewer's r2 burst with {@code total} decisions open at the peak. */
    static void overflowBurst(int total) throws Exception {
        long t = 1_791_000_000_000L, min = 60_000L;
        String at = " (" + total + " open)";
        OpenDecisions server = new OpenDecisions(4 * DecisionData.MAX, 10_000); // room for the big burst
        ToastPolicy client = new ToastPolicy();
        client.maxPerMinute = 1_000_000; // rate limit off: count every toast
        Map<String, Integer> toasts = new java.util.HashMap<>();
        server.put(dec("d0", t));
        for (ToastPolicy.Toast x : client.offer(wire(server), t)) toasts.merge(x.decision.id, 1, Integer::sum);
        ok(toasts.getOrDefault("d0", 0) == 1, "d0 toasts once when new" + at);
        int maxRemembered = 0, maxOpen = 0;
        for (int i = 1; i < total; i++) {
            server.put(dec("n" + i, t + i));
            for (ToastPolicy.Toast x : client.offer(wire(server), t + i)) toasts.merge(x.decision.id, 1, Integer::sum);
            maxRemembered = Math.max(maxRemembered, client.rememberedCount());
            maxOpen = Math.max(maxOpen, client.openCount());
        }
        DecisionData.Snapshot peak = wire(server);
        ok(server.size() == total && !peak.complete, "server holds every open id, the wire list is cut" + at);
        ok(maxRemembered <= ToastPolicy.MAX_REMEMBERED && maxOpen <= ToastPolicy.MAX_REMEMBERED, "client memory stays bounded: " + maxRemembered + "/" + maxOpen + at);
        boolean fits = total <= ToastPolicy.MAX_REMEMBERED;
        ok(client.known("d0") == fits, "d0 remembered exactly iff it fits" + at);
        ok(fits ? client.tombstoneCount() == 0 : client.tombstoned("d0"), "d0 tombstoned (not just forgotten) once it no longer fits" + at);
        ok(client.tombstoneCount() == Math.max(0, total - ToastPolicy.MAX_REMEMBERED), "exactly the oldest overflow is tombstoned: " + client.tombstoneCount() + at);
        // client restart while overflowed: the tombstones come back with the seen set
        ToastPolicy restarted = new ToastPolicy();
        restarted.maxPerMinute = 1_000_000;
        restarted.load(client.export(), t + total + 1);
        ok(restarted.known("d0") == fits && (fits || restarted.tombstoned("d0")), "export/load keeps the tombstones" + at);
        // d0 (never closed) is upserted again with its ORIGINAL createdAt; the newest close so
        // its details fit again, all within incomplete lists, 12 min later (past the cooldown)
        server.put(dec("d0", t));
        for (int i = Math.max(1, total - 127); i < total; i++) server.close("n" + i);
        DecisionData.Snapshot back = wire(server);
        boolean d0InDetails = false;
        for (DecisionData.Decision d : back.open) d0InDetails |= "d0".equals(d.id);
        ok(d0InDetails && back.complete == (server.size() <= DecisionData.MAX_IDS), "d0 back in the details" + at);
        long later = t + 12 * min;
        ok(toastsFor(client.offer(back, later), "d0") == 0, "continuously open d0 does not re-toast on re-entry" + at);
        ok(toastsFor(restarted.offer(back, later), "d0") == 0, "nor after a client restart" + at);
        ok(client.known("d0") && client.openCount() <= ToastPolicy.MAX_REMEMBERED, "d0 re-learned quietly" + at);
        // a genuinely new decision while the client is overflowed still toasts, exactly once
        server.put(dec("fresh-1", later + 1000));
        ok(toastsFor(client.offer(wire(server), later + 1000), "fresh-1") == 1, "a genuinely new decision still toasts while overflowed" + at);
        ok(toastsFor(client.offer(wire(server), later + 2000), "fresh-1") == 0, "and only once" + at);
        for (Map.Entry<String, Integer> e : toasts.entrySet()) ok(e.getValue() == 1, "no id toasted twice during the burst: " + e.getKey() + at);
        ok(toasts.size() == total, "every decision toasted exactly once: " + toasts.size() + at);
        // genuine close and reopen still work once a complete list arrives: close every n (the list
        // fits again and is complete, which clears the tombstones), then close d0 for real
        for (int i = 1; i < total; i++) server.close("n" + i);
        DecisionData.Snapshot small = wire(server);
        ok(small.complete && small.openIds.size() == 2, "the list is complete again" + at);
        ok(client.offer(small, later + 3 * min).isEmpty() && client.tombstoneCount() == 0, "a complete list clears the tombstones" + at);
        server.close("d0");
        ok(client.offer(wire(server), later + 4 * min).isEmpty(), "genuine close of d0: nothing" + at);
        server.put(dec("d0", t));
        ok(toastsFor(client.offer(wire(server), later + 11 * min), "d0") == 1, "genuine reopen after the cooldown toasts again" + at);
        server.close("d0");
        client.offer(wire(server), later + 12 * min);
        server.put(dec("d0", t));
        ok(client.offer(wire(server), later + 13 * min).isEmpty(), "genuine reopen inside the cooldown: no toast" + at);
    }

    static void toastOverflow() throws Exception {
        // the boundary: 2,000 open fit exactly; 2,001 and 2,002 (the reviewer's probe) and a larger
        // burst overflow the client's exact memory
        for (int total : new int[] { ToastPolicy.MAX_REMEMBERED - 1, ToastPolicy.MAX_REMEMBERED, ToastPolicy.MAX_REMEMBERED + 1, ToastPolicy.MAX_REMEMBERED + 2, 5000 }) overflowBurst(total);
        long t0 = 1_791_000_000_000L, min = 60_000L;
        // policy level, exact: cut lists that never repeat old ids; the oldest is forgotten first
        ToastPolicy p = new ToastPolicy();
        p.maxPerMinute = 1_000_000;
        p.offer(list(dec("a0", t0)), t0);
        for (int i = 1; i <= ToastPolicy.MAX_REMEMBERED; i++) {
            List<String> ids = new ArrayList<>();
            ids.add("a" + i);
            p.offer(list(dec("a" + i, t0 + i)), ids, false, t0 + i);
            if (i == ToastPolicy.MAX_REMEMBERED - 1) ok(p.known("a0") && p.openCount() == ToastPolicy.MAX_REMEMBERED, "2,000 open: all remembered");
        }
        ok(!p.known("a0") && p.tombstoned("a0") && p.known("a1") && p.openCount() == ToastPolicy.MAX_REMEMBERED, "2,001 open: only the oldest is tombstoned");
        List<String> back = new ArrayList<>();
        back.add("a0");
        ok(p.offer(list(dec("a0", t0)), back, false, t0 + 20 * min).isEmpty(), "the tombstoned id comes back: no toast");
        // load: lines beyond the cap are tombstoned, not forgotten
        StringBuilder file = new StringBuilder();
        for (int i = 0; i <= ToastPolicy.MAX_REMEMBERED; i++) file.append("L")
            .append(i)
            .append('\t')
            .append(t0)
            .append('\n');
        ToastPolicy q = new ToastPolicy();
        q.load(file.toString(), t0);
        String last = "L" + ToastPolicy.MAX_REMEMBERED;
        ok(q.rememberedCount() == ToastPolicy.MAX_REMEMBERED && !q.known(last) && q.tombstoned(last), "load over the cap tombstones the rest");
        ok(q.offer(list(dec(last, t0)), new ArrayList<String>(), false, t0 + min).isEmpty(), "and the dropped one does not toast");
        // a damaged tombstone line is ignored; a filter that was never filled stays empty
        ToastPolicy z = new ToastPolicy();
        z.load("x1\t" + t0 + "\n\ttomb\t5\tnot-base64!\n\ttomb\tx\t\n", t0);
        ok(z.rememberedCount() == 1 && z.tombstoneCount() == 0 && !z.tombstoned("x1"), "damaged tombstone lines ignored");
        ok(!new ToastPolicy().export()
            .contains("\ttomb\t"), "no tombstone line when there is nothing to keep");
        // false positives only ever mean silence (a new decision recorded without a toast); with
        // 16 KiB and 3 probes the expected rate at 10,000 forgotten ids is about 0.9%
        ToastPolicy f = new ToastPolicy();
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < ToastPolicy.MAX_REMEMBERED + 10_000; i++) many.append("t")
            .append(i)
            .append('\t')
            .append(t0)
            .append('\n');
        f.load(many.toString(), t0);
        int fp = 0;
        for (int i = 0; i < 10_000; i++) if (f.tombstoned("other-" + i)) fp++;
        ok(f.tombstoneCount() == 10_000 && fp < 200, "tombstone false positives under 2% at 10,000 forgotten ids: " + fp);
        System.out.println("  tombstone false positives at 10,000 forgotten ids: " + fp + " / 10000");
    }

    // ---- review r1: source-aware panel health (unknown and stale data are never "healthy") -----

    static OpsData.Service svc(String id, String src, String state) {
        OpsData.Service s = new OpsData.Service();
        s.id = id;
        s.name = id;
        s.sourceId = src;
        s.group = "hosts";
        s.state = state;
        return s;
    }

    static OpsData.Job job(String id, String src, String status) {
        OpsData.Job j = new OpsData.Job();
        j.id = id;
        j.name = id;
        j.sourceId = src;
        j.lastStatus = status;
        return j;
    }

    static OpsData.Usage use(String id, String src, float pct) {
        OpsData.Usage u = new OpsData.Usage();
        u.id = id;
        u.sourceId = src;
        u.provider = "provider-" + id;
        u.window = "5h";
        u.remainingPct = pct;
        return u;
    }

    static OpsData.Source src(String id, String state, int kinds) {
        OpsData.Source s = new OpsData.Source();
        s.id = id;
        s.name = "Source " + id;
        s.state = state;
        s.kinds = kinds;
        if (!"ok".equals(state)) s.detail = "collect failed";
        return s;
    }

    /** The view as a client sees it: through the real blob (so source kinds must survive the wire). */
    static OpsData.View client(OpsData.View v) throws Exception {
        v.live = true;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OpsData.write(new DataOutputStream(bos), v.live, v.services, v.jobs, v.usage, v.alerts, v.sources);
        return OpsData.read(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
    }

    static void health() throws Exception {
        int all = OpsData.KIND_SERVICE | OpsData.KIND_JOB | OpsData.KIND_USAGE | OpsData.KIND_ALERT;
        // fleet: unknown only -> grey, never "all up"
        OpsData.View v = new OpsData.View();
        v.sources.add(src("s1", "ok", all));
        v.services.add(svc("host-a", "s1", "unknown"));
        v.services.add(svc("host-b", "s1", "unknown"));
        OpsHealth.Summary f = OpsHealth.fleet(client(v), "all");
        ok(OpsHealth.UNKNOWN.equals(f.tone) && "2 unknown".equals(f.headline), "unknown-only fleet is grey: " + f.headline);
        // mixed up + unknown -> grey with the counts, not green
        v.services.add(svc("host-c", "s1", "up"));
        f = OpsHealth.fleet(client(v), "all");
        ok(OpsHealth.UNKNOWN.equals(f.tone) && "2 unknown".equals(f.headline) && f.counts.contains("1 up"), "mixed up/unknown is not green: " + f.headline + " / " + f.counts);
        // all up, source ok -> green "all up" (the only way to get green)
        OpsData.View h = new OpsData.View();
        h.sources.add(src("s1", "ok", all));
        h.services.add(svc("host-a", "s1", "up"));
        h.services.add(svc("host-b", "s1", "up"));
        h.jobs.add(job("job-1", "s1", "ok"));
        h.usage.add(use("u1", "s1", 80));
        OpsData.Alert al = new OpsData.Alert();
        al.id = "a1";
        al.sourceId = "s1";
        al.source = "monitor";
        al.title = "disk warning";
        al.state = "resolved";
        h.alerts.add(al);
        OpsData.View hc = client(h);
        ok("all up".equals(OpsHealth.fleet(hc, "all").headline) && OpsHealth.OK.equals(OpsHealth.fleet(hc, "all").tone), "healthy fleet is green");
        ok("all ok".equals(OpsHealth.jobs(hc, "all").headline) && OpsHealth.OK.equals(OpsHealth.jobs(hc, "all").tone), "healthy jobs are green");
        ok(OpsHealth.OK.equals(OpsHealth.usage(hc, "all").tone), "healthy usage is green");
        ok("no open alerts".equals(OpsHealth.alerts(hc, "all").headline) && OpsHealth.OK.equals(OpsHealth.alerts(hc, "all").tone), "healthy alerts are green");
        ok(OpsHealth.fleet(hc, "all").problem == null && OpsHealth.alerts(hc, "all").problem == null, "no problem line when healthy");
        // the same last-good data, then the source goes into error while the adapter stays connected
        for (String bad : new String[] { "error", "stale" }) {
            h.sources.set(0, src("s1", bad, all));
            OpsData.View e = client(h);
            ok(e.live, bad + ": adapter still connected");
            String word = "error".equals(bad) ? "source error" : "data stale";
            OpsHealth.Summary[] ss = { OpsHealth.fleet(e, "all"), OpsHealth.jobs(e, "all"), OpsHealth.usage(e, "all") };
            for (OpsHealth.Summary s : ss) {
                ok(OpsHealth.WARN.equals(s.tone) && word.equals(s.headline), bad + ": headline names the problem at every distance: " + s.headline);
                ok(s.problem != null && s.problem.contains("Source s1") && s.problem.contains(bad) && s.small()
                    .equals(s.problem), bad + ": FAR second line is the source problem");
            }
            OpsHealth.Summary a = OpsHealth.alerts(e, "all");
            ok(OpsHealth.WARN.equals(a.tone) && "alerts unverified".equals(a.headline) && a.problem != null, bad + ": alert feed says unverified: " + a.headline);
            ok(OpsHealth.UNKNOWN.equals(OpsHealth.serviceTone(e, e.services.get(0))), bad + ": an up tile from a failing source is grey");
            ok(OpsHealth.UNKNOWN.equals(OpsHealth.jobTone(e, e.jobs.get(0))), bad + ": an ok job from a failing source is grey");
            ok(OpsHealth.UNKNOWN.equals(OpsHealth.usageTone(e, e.usage.get(0))), bad + ": a healthy usage bar from a failing source is grey");
            ok(OpsHealth.stateLabel(e, "s1", "up")
                .contains("last known"), bad + ": detail line says last known");
        }
        // bad data stays bad on a failing source (stale data may warn, never reassure)
        h.services.add(svc("host-d", "s1", "down"));
        h.jobs.add(job("job-2", "s1", "failed"));
        h.usage.add(use("u2", "s1", 5));
        OpsData.View e = client(h);
        ok("1 down".equals(OpsHealth.fleet(e, "all").headline) && OpsHealth.BAD.equals(OpsHealth.fleet(e, "all").tone), "down outranks the source problem");
        ok(OpsHealth.fleet(e, "all").small().contains("Source s1"), "...and the FAR line still names the failing source");
        ok("1 failed".equals(OpsHealth.jobs(e, "all").headline), "failed job outranks the source problem");
        ok(OpsHealth.BAD.equals(OpsHealth.usage(e, "all").tone) && "5% left".equals(OpsHealth.usage(e, "all").headline), "low usage outranks the source problem");
        // a failing source that only carries jobs does not touch a fleet panel bound to another source
        OpsData.View two = new OpsData.View();
        two.sources.add(src("s1", "ok", OpsData.KIND_SERVICE));
        two.sources.add(src("s2", "error", OpsData.KIND_JOB));
        two.services.add(svc("host-a", "s1", "up"));
        two.jobs.add(job("job-1", "s2", "ok"));
        OpsData.View tc = client(two);
        ok(tc.sources.get(1).kinds == OpsData.KIND_JOB, "source kinds survive the blob");
        ok("all up".equals(OpsHealth.fleet(tc, "all").headline), "a jobs-only source error does not grey the fleet");
        ok("source error".equals(OpsHealth.jobs(tc, "all").headline), "but it does mark the cron board");
        ok("no alert source".equals(OpsHealth.alerts(tc, "all").headline) && OpsHealth.UNKNOWN.equals(OpsHealth.alerts(tc, "all").tone),
            "no source carries alerts: not green");
        // a failing source that never reported its kinds may carry anything: every panel says so
        two.sources.set(1, src("s2", "stale", 0));
        tc = client(two);
        ok("data stale".equals(OpsHealth.fleet(tc, "all").headline) && "alerts unverified".equals(OpsHealth.alerts(tc, "all").headline), "unknown kinds: conservative");
        ok("all up".equals(OpsHealth.fleet(tc, "s1").headline), "a panel bound to a healthy source is unaffected");
        // jobs: never-run / unknown jobs are not "all ok"; paused only is grey
        OpsData.View j = new OpsData.View();
        j.sources.add(src("s1", "ok", all));
        j.jobs.add(job("job-1", "s1", "unknown"));
        j.jobs.add(job("job-2", "s1", "ok"));
        OpsHealth.Summary js = OpsHealth.jobs(client(j), "all");
        ok("1 unknown".equals(js.headline) && OpsHealth.UNKNOWN.equals(js.tone), "a never-run job keeps the cron board grey: " + js.headline);
        j.jobs.clear();
        OpsData.Job p = job("job-3", "s1", "ok");
        p.enabled = false;
        j.jobs.add(p);
        ok("all paused".equals(OpsHealth.jobs(client(j), "all").headline), "paused only: grey");
        // usage: a known healthy lowest with an unknown window is not green
        OpsData.View u = new OpsData.View();
        u.sources.add(src("s1", "ok", all));
        u.usage.add(use("u1", "s1", 70));
        u.usage.add(use("u2", "s1", -1));
        OpsHealth.Summary us = OpsHealth.usage(client(u), "all");
        ok(OpsHealth.UNKNOWN.equals(us.tone) && us.counts.contains("1 unknown"), "usage with an unknown window is grey: " + us.counts);
        // the empty view says "no data", grey
        OpsData.View none = client(new OpsData.View());
        ok(OpsHealth.UNKNOWN.equals(OpsHealth.fleet(none, "all").tone) && OpsHealth.UNKNOWN.equals(OpsHealth.jobs(none, "all").tone)
            && OpsHealth.UNKNOWN.equals(OpsHealth.usage(none, "all").tone) && OpsHealth.UNKNOWN.equals(OpsHealth.alerts(none, "all").tone), "empty view: all grey");
    }

    // ---- the four panel kinds through the card 6 layout engine ----------------------------------

    static void panels() throws Exception {
        for (String id : new String[] { OpsPanels.FLEET, OpsPanels.CRON, OpsPanels.USAGE, OpsPanels.ALERTS }) {
            PanelType t = PanelTypes.get(id);
            ok(t != null && t.placeable && t.faced && t.resizable && PanelType.OPS.equals(t.source), id + " registered");
            ok("all".equals(t.defaultBinding()) && t.accepts("hosts") && t.accepts("all") && !t.accepts("../x"), id + " bindings");
        }
        // the inspector's binding list for the ops source comes from a registered hook, not editor code
        PanelTypes.registerChoices(PanelType.OPS, new PanelTypes.BindingChoices() {

            @Override
            public String hint() {
                return "binds to an ops filter (or all)";
            }

            @Override
            public List<Object[]> choices() {
                List<Object[]> l = new ArrayList<>();
                l.add(new Object[] { "all", "Everything", 0xC9A227 });
                l.add(new Object[] { "hosts", "Group hosts", 0x2FA3A0 });
                return l;
            }
        });
        ok(PanelTypes.choices(PanelType.OPS)
            .choices()
            .size() == 2, "ops binding choices hook");
        EditCheck.Rig r = new EditCheck.Rig(EditCheck.tmp("ops"));
        int x = 100;
        boolean lastBadOk = false;
        for (String id : new String[] { OpsPanels.FLEET, OpsPanels.CRON, OpsPanels.USAGE, OpsPanels.ALERTS }) {
            EditEngine.Result res = r.place(x, 64, 100, id, "all", 4, 3);
            ok(res.ok, "place " + id + ": " + res);
            Pos p = new Pos(x, 64, 100);
            PanelSpec s = r.w.panels.get(p);
            ok(s != null && id.equals(s.type) && "all".equals(s.binding) && s.w == 4 && s.h == 3, "inspect " + id);
            res = r.e.edit("Tester", "rebind", EditCheck.one(Op.Change.panel(p, s.withBinding("hosts").withSize(5, 2))));
            ok(res.ok && "hosts".equals(r.w.panels.get(p).binding) && r.w.panels.get(p).w == 5, "rebind + resize " + id);
            res = r.e.edit("Tester", "bad", EditCheck.one(Op.Change.panel(p, s.withBinding("fleet/../../x"))));
            ok(!res.ok || PanelTypes.get(id)
                .accepts(r.w.panels.get(p).binding), "bad binding refused or normalized for " + id);
            lastBadOk = res.ok;
            x += 6;
        }
        ok(r.e.undo("Tester").ok && (!lastBadOk || r.e.undo("Tester").ok) && "all".equals(r.w.panels.get(new Pos(118, 64, 100)).binding)
            && r.w.panels.get(new Pos(118, 64, 100)).w == 4, "undo restores binding and size");
        r.e.redo("Tester");
        RelLayout rel = r.e.export("ops-office", new Pos(100, 64, 100), "north", 32, false);
        String text = Json.write(rel.toJson());
        ok(text.contains("filter-1") && !text.contains("\"hosts\""), "export replaces ops filters with placeholders: " + text);
        ok(text.contains(OpsPanels.ALERTS), "export keeps the kinds");
        EditEngine.Result rm = r.e.edit("Tester", "remove", EditCheck.one(Op.Change.panel(new Pos(100, 64, 100), null)));
        ok(rm.ok && !r.w.panels.containsKey(new Pos(100, 64, 100)), "remove");
    }
}
