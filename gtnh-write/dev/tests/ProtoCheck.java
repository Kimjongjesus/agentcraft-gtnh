import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.agentcraft.gtnh.write.proto.Caps;
import dev.agentcraft.gtnh.write.proto.Fields;
import dev.agentcraft.gtnh.write.proto.Frames;
import dev.agentcraft.gtnh.write.proto.Hex;
import dev.agentcraft.gtnh.write.proto.KeyFile;
import dev.agentcraft.gtnh.write.proto.Msg;
import dev.agentcraft.gtnh.write.proto.Receiver;
import dev.agentcraft.gtnh.write.proto.Sender;
import dev.agentcraft.gtnh.write.proto.StrictJson;

/** Wire contract checks: docs/action-protocol.md sections 2 and 4 (frames, forged frames, replay, windows, schemas). */
public class ProtoCheck {

    // from dev/tests/make_vector.py: key bytes 00..1f over the payload {"a":1}
    static final String TOK = "abababababababababababababababab";
    static final String VECTOR = "b4cebde30982443ec36c18fa99ad10a70e705bba779f57c37e84decb64ae50fb";

    static Check.FakeClock clock;
    static Receiver rx;
    static long start;

    static Receiver fresh() {
        clock = new Check.FakeClock();
        start = clock.t - 1000;
        return new Receiver(Check.KEY, clock, start);
    }

    static String reject(Receiver r, String text) {
        try {
            r.accept(text);
            return null;
        } catch (Frames.Reject e) {
            return e.code;
        }
    }

    static String challenge(String nonce, long ts) {
        return Check.frame("action.challenge", Check.SESSION, "c1", nonce, ts, ",\"challenge\":\"00112233445566778899aabbccddeeff\"");
    }

    static String ackFrame(String id, String nonce, long ts) {
        return Check.frame("ack", Check.SESSION, id, nonce, ts, ",\"result\":{\"features\":[\"action\"]}");
    }

    public static void main(String[] a) throws Exception {
        vector();
        accepted();
        forged();
        replayAndWindows();
        cacheFull();
        clockBackwards();
        sender();
        caps();
        schemas();
        keyFile();
        json();
        Check.summary("ProtoCheck");
    }

    static void vector() {
        Check.eq(Hex.encode(Check.KEY), "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "key bytes 00..1f");
        Check.eq(Frames.sign(Check.KEY, "{\"a\":1}"), VECTOR, "HMAC-SHA256 known vector (computed by make_vector.py)");
        Check.ok(Frames.verify(Check.KEY, "{\"a\":1}", VECTOR), "vector verifies");
        Check.ok(!Frames.verify(Check.KEY, "{\"a\":2}", VECTOR), "other payload does not verify");
        Check.ok(!Frames.verify(Check.KEY, "{\"a\":1}", VECTOR.toUpperCase()), "uppercase hex signature refused");
        Check.ok(!Frames.verify(Check.KEY, "{\"a\":1}", VECTOR.substring(1)), "short signature refused");
        Check.ok(!Frames.verify(Check.KEY, "{\"a\":1}", VECTOR.substring(0, 63) + (VECTOR.charAt(63) == '0' ? '1' : '0')), "one flipped nibble refused");
        byte[] other = Check.KEY.clone();
        other[0] ^= 1;
        Check.ok(!Frames.verify(other, "{\"a\":1}", VECTOR), "other key refused");
        Check.eq(Frames.encode(Check.KEY, "x", "{\"a\":1}"), "{\"v\":1,\"type\":\"x\",\"payload\":\"{\\\"a\\\":1}\",\"sig\":\"" + VECTOR + "\"}", "encoded frame is exactly v,type,payload,sig");
    }

    static void accepted() throws Exception {
        rx = fresh();
        Msg m = rx.accept(challenge(Check.nonce(1), clock.t));
        Check.ok(m instanceof Msg.Challenge, "challenge accepted");
        Check.eq(((Msg.Challenge) m).challenge, "00112233445566778899aabbccddeeff", "challenge value");
        Check.eq(rx.session(), Check.SESSION, "session adopted from the challenge");
        Check.ok(rx.accept(ackFrame("a1", Check.nonce(2), clock.t)) instanceof Msg.Ack, "ack accepted in the session");
        String pol = Check.frame(
            "action.policy",
            Check.SESSION,
            "p1",
            Check.nonce(3),
            clock.t,
            ",\"revision\":\"r1\",\"dryRun\":true,\"actors\":[\"" + Check.OWNER + "\"],\"capabilities\":{\"card.dispatch\":{\"tier\":2,\"confirm\":true,\"enabled\":true,\"limits\":{\"perHour\":6}}},"
                + "\"services\":[\"service-1\"],\"jobs\":[\"job-a1\"],\"boards\":[\"main\"],\"profiles\":[\"builder-a\"],\"agents\":[\"helper-a\"]");
        Msg.Policy p = (Msg.Policy) rx.accept(pol);
        Check.ok(p.dryRun && p.actors.size() == 1 && p.capabilities.get("card.dispatch").confirm, "policy parsed with typed fields");
        Msg.State s = (Msg.State) rx.accept(Check.frame("action.state", Check.SESSION, "s1", Check.nonce(4), clock.t, ",\"armed\":true,\"locked\":false,\"lockReason\":\"\",\"lockedBy\":\"\",\"since\":0"));
        Check.ok(s.armed && !s.locked, "state parsed");
    }

    static void forged() throws Exception {
        rx = fresh();
        String good = challenge(Check.nonce(10), clock.t);
        // outer shape
        Check.eq(reject(rx, "not json"), "shape", "garbage is a shape error");
        Check.eq(reject(rx, "[]"), "shape", "array is not an outer object");
        Check.eq(reject(rx, good.replace("\"v\":1", "\"v\":2")), "shape", "v=2 refused");
        Check.eq(reject(rx, good.replace("\"v\":1", "\"v\":\"1\"")), "shape", "v as string refused");
        Check.eq(reject(rx, good.replace("\"v\":1", "\"v\":true")), "shape", "v as boolean refused");
        Check.eq(reject(rx, good.substring(0, good.length() - 1) + ",\"extra\":1}"), "shape", "extra outer key refused");
        Check.eq(reject(rx, good.replace("\"v\":1,", "\"v\":1,\"v\":1,")), "shape", "duplicate outer key refused");
        Check.eq(reject(rx, good.replaceAll("\"sig\":\"[0-9a-f]{64}\"", "\"sig\":\"" + VECTOR.toUpperCase() + "\"")), "shape", "uppercase sig refused");
        // signature
        String sig = good.replaceAll(".*\"sig\":\"([0-9a-f]{64})\".*", "$1");
        char flip = sig.charAt(0) == '0' ? '1' : '0';
        Check.eq(reject(rx, good.replace(sig, flip + sig.substring(1))), "signature", "bad signature refused");
        Check.eq(reject(rx, good.replace("action.challenge\",\"payload", "other.type\",\"payload")), "payload", "outer type edited after signing: the signature covers the payload only, the type mismatch is caught in the payload step");
        // authenticated but off-contract payloads (these are signed correctly)
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", payloadOf("action.challenge", Check.nonce(11), clock.t, ",\"challenge\":\"00112233445566778899aabbccddeeff\"").replace("\"ts\"", "\"ts\":1,\"ts\""))), "payload", "duplicate payload key refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", payloadOf("action.challenge", Check.nonce(12), clock.t, ",\"challenge\":\"00112233445566778899aabbccddeeff\",\"unknown\":1"))), "payload", "unknown payload key refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", payloadOf("action.challenge", Check.nonce(13), clock.t, ""))), "payload", "missing field refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "ack", payloadOf("action.challenge", Check.nonce(14), clock.t, ",\"challenge\":\"00112233445566778899aabbccddeeff\""))), "payload", "outer type differs from the inner type");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", payloadOf("action.challenge", Check.nonce(15), clock.t, ",\"challenge\":\"00112233445566778899AABBCCDDEEFF\""))), "payload", "uppercase challenge refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", payloadOf("action.challenge", Check.nonce(16), clock.t, ",\"challenge\":7"))), "payload", "challenge as number refused");
        String base = payloadOf("action.challenge", Check.nonce(17), clock.t, ",\"challenge\":\"00112233445566778899aabbccddeeff\"");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"ts\":" + clock.t, "\"ts\":\"" + clock.t + "\""))), "payload", "ts as string refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"ts\":" + clock.t, "\"ts\":" + clock.t + ".0"))), "payload", "ts as fraction refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"ts\":" + clock.t, "\"ts\":true"))), "payload", "ts as boolean refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"ts\":" + clock.t, "\"ts\":1.8e12"))), "payload", "ts with exponent refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"dir\":\"c2g\"", "\"dir\":\"g2c\""))), "payload", "wrong direction refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace(Check.nonce(17), "abc"))), "payload", "short nonce refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"id\":\"c1\"", "\"id\":\"has space\""))), "payload", "bad id characters refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace("\"session\":\"" + Check.SESSION + "\"", "\"session\":\"zz\""))), "payload", "bad session refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", "[1]")), "payload", "payload that is not an object refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base + " x")), "payload", "payload with trailing text refused");
        Check.eq(reject(rx, Frames.encode(Check.KEY, "action.challenge", base.replace(",\"challenge\":\"00112233445566778899aabbccddeeff\"", ",\"challenge\":null"))), "payload", "null refused");
        // none of those used nonce space: the good frame still goes through
        Check.ok(rx.accept(good) instanceof Msg.Challenge, "after refusals the genuine challenge is accepted (refusals took no nonce space)");
        Check.eq(rx.liveNonces(), 1, "only the accepted frame holds a nonce");
        // session rules
        Check.eq(reject(rx, Check.frame("ack", "ffffffffffffffffffffffffffffffff", "a1", Check.nonce(20), clock.t, ",\"result\":{\"features\":[\"action\"]}")), "session", "wrong session refused");
        Receiver r2 = fresh();
        Check.eq(reject(r2, ackFrame("a1", Check.nonce(21), clock.t)), "session", "the first frame must be the challenge");
        // oversize
        char[] big = new char[Frames.MAX_BYTES + 10];
        java.util.Arrays.fill(big, 'x');
        try {
            fresh().accept(new String(big));
            Check.ok(false, "oversize message");
        } catch (Frames.Reject e) {
            Check.ok(e.code.equals("size") && e.fatal, "oversize message closes the connection");
        }
    }

    static String payloadOf(String type, String nonce, long ts, String body) {
        return "{\"type\":\"" + type + "\",\"session\":\"" + Check.SESSION + "\",\"dir\":\"c2g\",\"id\":\"c1\",\"nonce\":\"" + nonce + "\",\"ts\":" + ts + body + "}";
    }

    static void replayAndWindows() throws Exception {
        clock = new Check.FakeClock();
        start = clock.t - 200_000; // a long-running process, so the +-60 s window is the limit under test
        rx = new Receiver(Check.KEY, clock, start);
        String f = challenge(Check.nonce(30), clock.t);
        rx.accept(f);
        Check.eq(reject(rx, f), "replay", "the same frame twice is a replay");
        Check.eq(reject(rx, ackFrame("a2", Check.nonce(30), clock.t)), "replay", "a new frame with a reused nonce is a replay");
        Check.ok(rx.accept(ackFrame("a3", Check.nonce(31), clock.t + 60_000)) instanceof Msg.Ack, "ts +60000 ms is inside the window");
        Check.eq(reject(rx, ackFrame("a4", Check.nonce(32), clock.t + 60_001)), "ts", "ts +60001 ms is outside");
        Check.ok(rx.accept(ackFrame("a5", Check.nonce(33), clock.t - 60_000)) instanceof Msg.Ack, "ts -60000 ms is inside the window");
        Check.eq(reject(rx, ackFrame("a6", Check.nonce(34), clock.t - 60_001)), "ts", "ts -60001 ms is outside");
        // a nonce is remembered until ts + 60 s passed, then the frame itself is out of the window
        long old = clock.t;
        clock.t += 60_001;
        Check.eq(reject(rx, ackFrame("a9", Check.nonce(30), old)), "ts", "replay of an expired frame fails the ts window");
        // a process that started 1 s ago refuses anything stamped before that
        Check.FakeClock c2 = new Check.FakeClock();
        Receiver young = new Receiver(Check.KEY, c2, c2.t - 1000);
        Check.eq(reject(young, challenge(Check.nonce(70), c2.t - 1001)), "ts", "ts before the process started is refused (inside the window)");
        Check.ok(young.accept(challenge(Check.nonce(71), c2.t - 1000)) instanceof Msg.Challenge, "ts exactly at process start is allowed");
    }

    static void cacheFull() throws Exception {
        rx = fresh();
        rx.accept(challenge(Check.nonce(0), clock.t));
        for (int i = 1; i < Receiver.NONCE_CAP; i++) rx.accept(ackFrame("a" + i, Check.nonce(i), clock.t));
        Check.eq(rx.liveNonces(), Receiver.NONCE_CAP, "4096 live nonces held");
        Check.eq(reject(rx, ackFrame("full", Check.nonce(9000), clock.t)), "nonce-cache-full", "frame 4097 is refused, not admitted by evicting an old nonce");
        clock.t += 30_000;
        Check.eq(reject(rx, ackFrame("full2", Check.nonce(9001), clock.t)), "nonce-cache-full", "still full 30 s later: nothing is evicted early");
        Check.eq(reject(rx, ackFrame("replay", Check.nonce(5), clock.t)), "replay", "an old nonce is still remembered while the cache is full");
        clock.t += 30_001; // first batch (ts = t0) now past ts + 60 s
        Check.ok(rx.accept(ackFrame("again", Check.nonce(9002), clock.t)) instanceof Msg.Ack, "after the entries expire new frames are admitted");
    }

    static void clockBackwards() throws Exception {
        clock = new Check.FakeClock();
        rx = new Receiver(Check.KEY, clock, clock.t - 500_000);
        rx.accept(challenge(Check.nonce(40), clock.t));
        long t0 = clock.t;
        clock.t -= 59_000;
        Check.ok(rx.accept(ackFrame("b1", Check.nonce(41), clock.t)) instanceof Msg.Ack, "a small step back (59 s) is tolerated");
        clock.t = t0 - 61_000;
        Check.eq(reject(rx, ackFrame("b2", Check.nonce(42), clock.t)), "clock", "a step back of more than 60 s refuses everything");
        Check.ok(rx.clockFault(), "clock fault is reported");
        clock.t = t0 + 5000;
        Check.eq(reject(rx, ackFrame("b3", Check.nonce(43), clock.t)), "clock", "and stays refused until restart, even when the clock is fine again");
        Check.eq(reject(rx, challenge(Check.nonce(44), clock.t)), "clock", "including a fresh challenge");
    }

    static void sender() throws Exception {
        clock = new Check.FakeClock();
        Sender s = new Sender(Check.KEY, clock, new SecureRandom());
        s.setSession(Check.SESSION);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challenge", "00112233445566778899aabbccddeeff");
        java.util.List<Object> feats = new java.util.ArrayList<>();
        feats.add("action");
        body.put("features", feats);
        String id1 = s.nextId(), id2 = s.nextId();
        Check.ok(!id1.equals(id2) && Fields.ID.matcher(id1).matches() && Fields.ID.matcher(id2).matches(), "ids are unique and valid");
        String text = s.frame("hello", id1, body);
        Frames.Opened o = Frames.open(Check.KEY, text);
        Map<String, Object> p = StrictJson.parseObject(o.payload);
        Check.eq(p.get("type"), "hello", "hello payload type");
        Check.eq(p.get("dir"), "g2c", "game frames are g2c");
        Check.eq(p.get("session"), Check.SESSION, "session set");
        Check.eq(p.get("ts"), Long.valueOf(clock.t), "ts from the clock");
        Check.ok(Hex.isLowerHex((String) p.get("nonce"), 32), "nonce is 128 random bits as 32 hex");
        Check.ok(!s.nonce().equals(s.nonce()), "nonces differ");
        Check.eq(o.type, "hello", "outer type");
        Check.ok(p.keySet().toString().equals("[type, session, dir, id, nonce, ts, challenge, features]"), "payload key set is exactly the common fields plus the body");
    }

    static Map<String, Object> obj(String json) throws Exception {
        return StrictJson.parseObject(json);
    }

    static String bad(String cap, String json) {
        try {
            Caps.validate(cap, StrictJson.parseObject(json));
            return null;
        } catch (Fields.Bad e) {
            return e.getMessage();
        } catch (StrictJson.ParseException e) {
            return "parse";
        }
    }

    static void caps() throws Exception {
        Check.eq(Caps.names().size(), 8, "eight capabilities, no more");
        Check.eq(Caps.get("card.dispatch").tier, 2, "dispatch is tier 2");
        Check.ok(Caps.get("card.dispatch").confirm && !Caps.get("service.restart").confirm, "only dispatch has a confirm screen");
        Check.ok(Caps.get("hypervisor.restart") == null && Caps.get("shell.run") == null, "no capability for infrastructure or shell");
        Check.eq(Caps.validate("card.dispatch", obj("{\"card\":\"c1\",\"board\":\"main\",\"profile\":\"builder-a\"}")).toString(), "{card=c1, board=main, profile=builder-a}", "dispatch args");
        Check.ok(bad("card.dispatch", "{\"card\":\"c1\",\"board\":\"main\",\"profile\":\"b\",\"actor\":\"x\"}") != null, "client-supplied actor is an unknown key");
        Check.ok(bad("card.dispatch", "{\"card\":\"c1\",\"board\":\"main\",\"profile\":\"b\",\"id\":\"x\"}") != null, "client-supplied id is an unknown key");
        Check.ok(bad("card.dispatch", "{\"card\":\"c1\",\"board\":\"main\",\"profile\":\"b\",\"nonce\":\"x\"}") != null, "client-supplied nonce is an unknown key");
        Check.ok(bad("card.dispatch", "{\"card\":\"c1\",\"board\":\"main\",\"profile\":\"b\",\"ts\":1}") != null, "client-supplied ts is an unknown key");
        Check.ok(bad("card.dispatch", "{\"card\":\"c1\",\"board\":\"main\"}") != null, "missing key refused");
        Check.ok(bad("card.dispatch", "{\"card\":1,\"board\":\"main\",\"profile\":\"b\"}") != null, "number for a string refused");
        char[] c65 = new char[65];
        java.util.Arrays.fill(c65, 'a');
        Check.ok(bad("cron.run", "{\"job\":\"" + new String(c65) + "\"}") != null, "65-char job refused, not cut");
        Check.ok(bad("cron.run", "{\"job\":\"a\\nb\"}") != null, "control character in a name refused");
        Check.ok(bad("cron.run", "{\"job\":\"\"}") != null, "empty job refused");
        Check.ok(bad("decision.answer", "{\"card\":\"c\",\"decision\":\"d\"}") != null, "decision.answer needs a choice or text");
        Check.ok(bad("decision.answer", "{\"card\":\"c\",\"decision\":\"d\",\"choice\":\"Deny\"}") == null, "decision.answer with a choice");
        Check.ok(bad("decision.answer", "{\"card\":\"c\",\"decision\":\"d\",\"text\":\"line1\\nline2\"}") == null, "multi-line text allowed");
        Check.ok(bad("card.create", "{\"board\":\"main\",\"title\":\"t\",\"priority\":101}") != null, "priority 101 refused");
        Check.ok(bad("card.create", "{\"board\":\"main\",\"title\":\"t\",\"priority\":true}") != null, "boolean priority refused");
        Check.ok(bad("card.create", "{\"board\":\"main\",\"title\":\"t\",\"priority\":5.0}") != null, "fractional priority refused");
        Check.ok(bad("card.create", "{\"board\":\"main\",\"title\":\"t\",\"priority\":50,\"body\":\"b\"}") == null, "card.create with optionals");
        Check.ok(bad("card.edit", "{\"card\":\"c\",\"comment\":\"hi\"}") == null, "comment alone");
        Check.ok(bad("card.edit", "{\"card\":\"c\",\"comment\":\"hi\",\"title\":\"t\"}") != null, "comment together with title refused");
        Check.ok(bad("card.edit", "{\"card\":\"c\"}") != null, "card.edit with nothing to change refused");
        Check.ok(bad("card.edit", "{\"card\":\"c\",\"title\":\"\"}") != null, "empty title refused");
        Check.ok(bad("card.edit", "{\"card\":\"c\",\"priority\":0,\"body\":\"\"}") == null, "priority 0 and empty body allowed");
        Check.ok(bad("agent.ask", "{\"agent\":\"a\",\"text\":\"q\"}") == null, "agent.ask");
        Check.ok(bad("agent.chat", "{\"agent\":\"a\",\"text\":\"q\"}") != null, "agent.chat needs a conversation");
        Check.ok(bad("shell.run", "{\"cmd\":\"x\"}") != null, "unknown capability refused");
        Check.eq(Caps.limits("card.dispatch").size(), 2, "dispatch has two rules (6/h and 1 per card per 10 min)");
    }

    static void schemas() throws Exception {
        rx = fresh();
        rx.accept(challenge(Check.nonce(50), clock.t));
        String capBad = ",\"revision\":\"r\",\"dryRun\":false,\"actors\":[],\"capabilities\":{\"shell.run\":{\"tier\":2,\"confirm\":false,\"enabled\":true,\"limits\":{}}},\"services\":[],\"jobs\":[],\"boards\":[],\"profiles\":[],\"agents\":[]";
        Check.eq(reject(rx, Check.frame("action.policy", Check.SESSION, "p", Check.nonce(51), clock.t, capBad)), "payload", "a policy naming an unknown capability is refused");
        String tierBad = capBad.replace("shell.run", "card.dispatch");
        Check.eq(reject(rx, Check.frame("action.policy", Check.SESSION, "p", Check.nonce(52), clock.t, tierBad.replace("\"tier\":2", "\"tier\":1"))), "payload", "a policy that changes a tier is refused");
        Check.eq(reject(rx, Check.frame("action.policy", Check.SESSION, "p", Check.nonce(53), clock.t, tierBad.replace("\"actors\":[]", "\"actors\":[\"0000000A-0000-4000-8000-00000000000B\"]"))), "payload", "an uppercase actor uuid is refused");
        Check.eq(reject(rx, Check.frame("action.state", Check.SESSION, "s", Check.nonce(54), clock.t, ",\"armed\":1,\"locked\":false,\"lockReason\":\"\",\"lockedBy\":\"\",\"since\":0")), "payload", "armed as number refused");
        Check.eq(reject(rx, Check.frame("action.result", Check.SESSION, "r", Check.nonce(55), clock.t, ",\"re\":\"x\",\"status\":\"applied\",\"error\":\"\",\"result\":{\"k\":3},\"audit\":\"\",\"dryRun\":false")), "payload", "non-string result value refused");
        Check.eq(reject(rx, Check.frame("action.result", Check.SESSION, "r", Check.nonce(56), clock.t, ",\"re\":\"x\",\"status\":\"done\",\"error\":\"\",\"result\":{},\"audit\":\"\",\"dryRun\":false")), "payload", "unknown status refused");
        Msg ok = rx.accept(Check.frame("action.result", Check.SESSION, "r", Check.nonce(57), clock.t, ",\"re\":\"x\",\"status\":\"applied\",\"error\":\"\",\"result\":{\"k\":\"v\"},\"audit\":\"a1\",\"dryRun\":true"));
        Check.ok(ok instanceof Msg.Result && ((Msg.Result) ok).dryRun, "valid result");
        Check.eq(reject(rx, Check.frame("action.prompt", Check.SESSION, "r", Check.nonce(58), clock.t, ",\"re\":\"x\",\"token\":\"" + TOK + "\",\"expiresAt\":5,\"summary\":{\"card\":\"c\"}")), "payload", "prompt summary must hold all six keys");
        Check.ok(
            rx.accept(
                Check.frame(
                    "action.prompt",
                    Check.SESSION,
                    "r",
                    Check.nonce(59),
                    clock.t,
                    ",\"re\":\"x\",\"token\":\"" + TOK
                        + "\",\"expiresAt\":5,\"summary\":{\"card\":\"c\",\"title\":\"t\",\"board\":\"b\",\"profile\":\"p\",\"model\":\"m\",\"body\":\"x\"}")) instanceof Msg.Prompt,
            "valid prompt");
        Check.eq(reject(rx, Check.frame("action.chat", Check.SESSION, "r", Check.nonce(60), clock.t, ",\"re\":\"x\",\"conversation\":\"c\",\"agentId\":\"a\",\"text\":\"t\",\"final\":\"yes\"")), "payload", "final as string refused");
        Check.ok(rx.accept(Check.frame("error", Check.SESSION, "r", Check.nonce(61), clock.t, ",\"re\":\"\",\"error\":\"generic\"")) instanceof Msg.Error, "error with empty re");
        Check.eq(reject(rx, Check.frame("hello", Check.SESSION, "r", Check.nonce(62), clock.t, ",\"challenge\":\"00112233445566778899aabbccddeeff\",\"features\":[\"action\"]")), "payload", "a g2c-only type arriving c2g is refused");
        Check.eq(reject(rx, Check.frame("ack", Check.SESSION, "r", Check.nonce(63), clock.t, ",\"result\":{\"features\":[\"action\"],\"x\":1}")), "payload", "unknown key inside ack.result refused");
    }

    static void keyFile() throws Exception {
        File d = Check.tmpDir("kf");
        File f = new File(d, "key");
        String hex = Hex.encode(Check.KEY);
        Files.write(f.toPath(), ("# comment\n\n" + hex + "\n").getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
        Check.eq(Hex.encode(KeyFile.load(f.toPath())), hex, "0600 key file with comments loads");
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-r-----"));
        Check.ok(loadFails(f), "group-readable key file refused");
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw----r--"));
        Check.ok(loadFails(f), "world-readable key file refused");
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-rw----"));
        Check.ok(loadFails(f), "group-writable key file refused");
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
        Files.write(f.toPath(), (hex + "\n" + hex + "\n").getBytes(StandardCharsets.UTF_8));
        Check.ok(loadFails(f), "two key lines refused");
        Files.write(f.toPath(), hex.substring(0, 62).getBytes(StandardCharsets.UTF_8));
        Check.ok(loadFails(f), "31-byte key refused");
        Files.write(f.toPath(), ("zz" + hex.substring(2)).getBytes(StandardCharsets.UTF_8));
        Check.ok(loadFails(f), "non-hex key refused");
        Files.write(f.toPath(), (hex + hex).getBytes(StandardCharsets.UTF_8));
        Check.eq(KeyFile.load(f.toPath()).length, 64, "a longer key is accepted");
        Check.ok(loadFails(new File(d, "missing")), "missing key file refused");
    }

    static boolean loadFails(File f) {
        try {
            KeyFile.load(f.toPath());
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }

    static boolean parseFails(String s) {
        try {
            StrictJson.parse(s);
            return false;
        } catch (StrictJson.ParseException e) {
            return true;
        }
    }

    static void json() throws Exception {
        Check.ok(parseFails("{\"a\":1,\"a\":2}"), "duplicate key refused");
        Check.ok(parseFails("{\"o\":{\"a\":1,\"a\":2}}"), "duplicate key in a nested object refused");
        Check.ok(parseFails("{\"a\":null}"), "null refused");
        Check.ok(parseFails("{\"a\":01}"), "leading zero refused");
        Check.ok(parseFails("{\"a\":1} x"), "trailing text refused");
        Check.ok(parseFails("{\"a\":\"\u0001\"}"), "raw control character in a string refused");
        Check.ok(parseFails("{\"a\":99999999999999999999}"), "integer beyond long refused");
        Check.ok(parseFails("[[[[[[[[[[1]]]]]]]]]]"), "nesting past the limit refused");
        Check.ok(StrictJson.parse("{\"a\":1e2}") instanceof Map && ((Map<?, ?>) StrictJson.parse("{\"a\":1e2}")).get("a") instanceof Double, "1e2 is a double, never an integer");
        Check.ok(((Map<?, ?>) StrictJson.parse("{\"a\":-5}")).get("a") instanceof Long, "-5 is an integer");
        Check.eq(StrictJson.write(StrictJson.parse("{\"s\":\"a\\\"b\\n\\u0001\",\"l\":[1,true]}")), "{\"s\":\"a\\\"b\\n\\u0001\",\"l\":[1,true]}", "write round-trips escapes");
    }
}
