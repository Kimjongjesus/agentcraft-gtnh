import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.agentcraft.gtnh.bridge.WebSocketClient;
import dev.agentcraft.gtnh.write.core.Controller;
import dev.agentcraft.gtnh.write.core.WriteAudit;
import dev.agentcraft.gtnh.write.core.WriteLock;
import dev.agentcraft.gtnh.write.mc.ControlLink;
import dev.agentcraft.gtnh.write.mc.LinkProbe;
import dev.agentcraft.gtnh.write.proto.Hex;

/**
 * R4: invalidation of the REAL write link (ControlLink + WebSocketClient over loopback, signed handshake included),
 * driven through the real Controller. (a) A lock, a security disarm and an outbox overflow on the "server thread"
 * return at once while the writer is blocked mid-write on a socket the peer never reads, and the blocked write fails.
 * (b) A frame dequeued but not started when the boundary passes is never written and is resolved like any unsent
 * frame. Plus the transport's own abort(). Deterministic: blocking is observed from the writer's stack, the race is
 * held open on latches. No Minecraft.
 */
public class LinkCheck {

    static final long LIMIT_MS = 500;
    static final String WRITER = "AgentCraftWrite-link-writer";
    static final Controller.Player OWNER = new Controller.Player(Check.OWNER, "Owner", true);

    public static void main(String[] a) throws Exception {
        rawAbort();
        backpressure("lock");
        backpressure("disarm");
        backpressure("overflow");
        sendStartRace();
        Check.summary("LinkCheck");
    }

    // ---- loopback control service --------------------------------------------------------------------------------

    /** WebSocket upgrade, then (signed) challenge -> hello -> ack, policy, state; then it reads and records, or never reads. */
    static final class Peer implements Runnable {

        final ServerSocket ss;
        final boolean signed, read;
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch upgraded = new CountDownLatch(1), done = new CountDownLatch(1), hold = new CountDownLatch(1);
        volatile String hello = "";
        volatile boolean closeFrame;
        volatile Socket peer;

        Peer(boolean signed, boolean read) throws IOException {
            this.signed = signed;
            this.read = read;
            ss = new ServerSocket();
            if (!read) ss.setReceiveBufferSize(4096); // accepted sockets inherit it: the writer hits backpressure early
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            Thread t = new Thread(this, "r4-control-peer");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "ws://127.0.0.1:" + ss.getLocalPort() + "/";
        }

        static byte[] text(String s) {
            return HardeningCheck.frame(true, 1, s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void run() {
            try {
                Socket s = ss.accept();
                peer = s;
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    int b = in.read();
                    if (b < 0) return;
                    head.append((char) b);
                }
                String key = "";
                for (String line : head.toString().split("\r\n")) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':') + 1).trim();
                }
                String acc = java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                if (signed) {
                    long now = System.currentTimeMillis();
                    out.write(text(Check.frame("action.challenge", Check.SESSION, "c1", Check.nonce(1), now, ",\"challenge\":\"00112233445566778899aabbccddeeff\"")));
                    out.flush();
                    hello = readFrame(in);
                    out.write(text(Check.frame("ack", Check.SESSION, "a1", Check.nonce(2), now, ",\"result\":{\"features\":[\"action\"]}")));
                    out.write(text(Check.frame("action.policy", Check.SESSION, "p1", Check.nonce(3), now,
                        ",\"revision\":\"r1\",\"dryRun\":false,\"actors\":[\"" + Check.OWNER + "\"],\"capabilities\":{\"agent.ask\":{\"tier\":1,\"confirm\":false,\"enabled\":true,\"limits\":{\"perHour\":60}}},"
                            + "\"services\":[\"service-1\"],\"jobs\":[\"job-a1\"],\"boards\":[\"main\"],\"profiles\":[\"builder-a\"],\"agents\":[\"helper-a\"]")));
                    out.write(text(Check.frame("action.state", Check.SESSION, "s1", Check.nonce(4), now, ",\"armed\":true,\"locked\":false,\"lockReason\":\"\",\"lockedBy\":\"\",\"since\":0")));
                    out.flush();
                }
                upgraded.countDown();
                if (read) {
                    for (String f; (f = readFrame(in)) != null;) got.add(f);
                } else {
                    hold.await(60, TimeUnit.SECONDS); // never reads another byte
                }
            } catch (Exception ignored) {
                // a reset from the client's abort ends the session here
            } finally {
                done.countDown();
                close();
            }
        }

        /** One masked client frame as text; null on EOF or a close frame (noted). */
        String readFrame(InputStream raw) throws IOException {
            DataInputStream in = new DataInputStream(raw);
            int b1 = in.read();
            if (b1 < 0) return null;
            int b2 = in.readUnsignedByte();
            long len = b2 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] mask = new byte[4];
            if ((b2 & 0x80) != 0) in.readFully(mask);
            byte[] p = new byte[(int) len];
            in.readFully(p);
            for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            if ((b1 & 0x0F) == 8) {
                closeFrame = true;
                return null;
            }
            return new String(p, StandardCharsets.UTF_8);
        }

        void close() {
            hold.countDown();
            try {
                ss.close();
                if (peer != null) peer.close();
            } catch (IOException ignored) {}
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    static Set<Thread> writers() {
        Set<Thread> s = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals(WRITER) && t.isAlive()) s.add(t);
        return s;
    }

    /** True while {@code t} is inside WebSocketClient.sendFrame (the send monitor is held there). */
    static boolean inSend(Thread t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().endsWith("WebSocketClient") && e.getMethodName().equals("sendFrame")) return true;
        }
        return false;
    }

    /** Blocked mid-write: still inside sendFrame after four looks 50 ms apart with nothing new sent (an unblocked 15 KB write takes microseconds). */
    static boolean blocked(Thread t) throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            if (!inSend(t)) return false;
            Thread.sleep(50);
        }
        return inSend(t);
    }

    static Map<String, Object> pad() {
        char[] c = new char[15000];
        Arrays.fill(c, 'p');
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pad", new String(c));
        return m;
    }

    /** Runs {@code r} on a separate "server thread"; returns how long it took, or -1 when it has not returned after 3 s. */
    static long onServerThread(final Runnable r) throws InterruptedException {
        final long[] ms = { -1 };
        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                long t0 = System.nanoTime();
                r.run();
                ms[0] = (System.nanoTime() - t0) / 1_000_000;
            }
        }, "r4-server-thread");
        t.setDaemon(true);
        t.start();
        t.join(3000);
        return t.isAlive() ? -1 : ms[0];
    }

    static ControlLink.Event waitEvent(ControlLink link, int kind, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ControlLink.Event e = link.inbox.poll();
            if (e == null) Thread.sleep(10);
            else if (e.kind == kind) return e;
        }
        return null;
    }

    static final class Ctl implements Controller.Notifier, Controller.Presence {

        final List<String> results = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void stateChanged() {}

        @Override
        public void prompt(String playerUuid, String requestId, String token, long msLeft, Map<String, String> summary) {}

        @Override
        public void promptClosed(String playerUuid, String token, String why) {}

        @Override
        public void result(String uuid, String requestId, String cap, String status, String error, Map<String, String> result, String audit, boolean dryRun) {
            results.add(requestId + "|" + cap + "|" + status + "|" + error);
        }

        @Override
        public void chat(String playerUuid, String conversation, String agentId, String text, boolean fin) {}

        @Override
        public String check(Controller.Player p, int radius) {
            return null;
        }
    }

    /** A real link, connected and handshaked to a loopback peer, under a real Controller (armed). */
    static final class Rig {

        Peer peer;
        ControlLink link;
        LinkProbe probe;
        Thread writer;
        Controller c;
        Ctl ctl = new Ctl();
        Check.FakeClock clock = new Check.FakeClock();
        File dir = Check.tmpDir("r4");
        WriteAudit audit;
        WriteLock lock;
        Check.Facts facts;

        Rig(boolean peerReads) throws Exception {
            peer = new Peer(true, peerReads);
            File key = new File(dir, "control.key");
            Files.write(key.toPath(), (Hex.encode(Check.KEY) + "\n").getBytes(StandardCharsets.US_ASCII));
            try {
                Files.setPosixFilePermissions(key.toPath(), PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {}
            link = new ControlLink(peer.url(), key.getPath(), 0L);
            probe = LinkProbe.on(link);
            Set<Thread> before = writers();
            link.start();
            ControlLink.Event up = waitEvent(link, ControlLink.Event.UP, 5000);
            if (up == null) throw new IllegalStateException("R4 rig: the real link did not come up: " + link.note());
            for (int i = 0; i < 100 && writer == null; i++) {
                for (Thread t : writers()) if (!before.contains(t)) writer = t;
                if (writer == null) Thread.sleep(10);
            }
            audit = new WriteAudit(new File(dir, "audit.log"), 8L * 1024 * 1024, clock);
            lock = new WriteLock(new File(dir, "write-lock.json"), clock);
            final WriteAudit au = audit;
            final WriteLock lk = lock;
            final ControlLink l = link;
            facts = new Check.Facts() {

                @Override
                public boolean linkReady() {
                    return l.ready();
                }

                @Override
                public List<String> policyActors() {
                    return l.ready() ? actors : null;
                }

                @Override
                public boolean gameLocked() {
                    return lk.isLocked();
                }

                @Override
                public boolean auditWritable() {
                    return au.writable();
                }
            };
            Controller.Settings cfg = new Controller.Settings();
            cfg.owner = Check.OWNER;
            c = new Controller(cfg, clock, audit, lock, facts, link, ctl, ctl);
            c.onLinkUp(up.policy, up.state);
            c.recheck();
        }

        String auditText() throws IOException {
            return new String(Files.readAllBytes(new File(dir, "audit.log").toPath()), StandardCharsets.UTF_8);
        }

        void close() {
            link.stop();
            peer.close();
        }
    }

    // ---- the transport alone -------------------------------------------------------------------------------------

    static void rawAbort() throws Exception {
        // a writer blocked mid-write on the real client: abort() returns at once and the write fails
        Peer p = new Peer(false, false);
        final WebSocketClient ws = ControlLink.newClient();
        ws.connect(URI.create(p.url()), 3000);
        final Throwable[] err = { null };
        final String big = (String) pad().get("pad");
        Thread w = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    for (int i = 0; i < 100_000; i++) ws.sendText(big);
                } catch (Throwable t) {
                    err[0] = t;
                }
            }
        }, "r4-raw-writer");
        w.setDaemon(true);
        w.start();
        boolean blocked = false;
        for (int i = 0; i < 200 && !blocked && w.isAlive(); i++) blocked = blocked(w);
        Check.ok(blocked, "R4 transport: a sendText is blocked mid-write on a socket the peer never reads");
        long abortMs = onServerThread(new Runnable() {

            @Override
            public void run() {
                ws.abort();
            }
        });
        Check.ok(abortMs >= 0 && abortMs < LIMIT_MS, "R4 transport: abort() returns at once while the send monitor is held by the blocked writer (" + abortMs + " ms)");
        w.join(2000);
        Check.ok(!w.isAlive(), "R4 transport: the blocked writer is released");
        Check.ok(err[0] instanceof IOException, "R4 transport: ... with an IOException (" + err[0] + ")");
        long closeMs = onServerThread(new Runnable() {

            @Override
            public void run() {
                ws.close();
            }
        });
        Check.ok(closeMs >= 0 && closeMs < LIMIT_MS, "R4 transport: close() after abort() returns at once (" + closeMs + " ms)");
        try {
            ws.sendText("late");
            Check.ok(false, "R4 transport: a send after abort() fails");
        } catch (IOException e) {
            Check.ok(true, "R4 transport: a send after abort() fails");
        }
        p.close();

        // a reader blocked in readText is released by abort() too
        Peer q = new Peer(false, false);
        final WebSocketClient rd = ControlLink.newClient();
        rd.connect(URI.create(q.url()), 3000);
        final Throwable[] rerr = { null };
        Thread r = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    rd.readText();
                } catch (Throwable t) {
                    rerr[0] = t;
                }
            }
        }, "r4-raw-reader");
        r.setDaemon(true);
        r.start();
        Thread.sleep(100);
        rd.abort();
        r.join(2000);
        Check.ok(!r.isAlive() && rerr[0] instanceof IOException, "R4 transport: a reader blocked in readText gets an IOException on abort() (" + rerr[0] + ")");
        q.close();

        // abort sends no close frame; the graceful close still does
        Peer g = new Peer(false, true);
        WebSocketClient gc = ControlLink.newClient();
        gc.connect(URI.create(g.url()), 3000);
        gc.sendText("bye");
        gc.close();
        Check.ok(g.done.await(3, TimeUnit.SECONDS), "R4 transport: graceful close ends the peer's session");
        Check.ok(g.closeFrame && g.got.contains("bye"), "R4 transport: graceful close() still delivers the queued text and a close frame");
        Peer h = new Peer(false, true);
        WebSocketClient hc = ControlLink.newClient();
        hc.connect(URI.create(h.url()), 3000);
        hc.abort();
        Check.ok(h.done.await(3, TimeUnit.SECONDS), "R4 transport: abort() ends the peer's session");
        Check.ok(!h.closeFrame, "R4 transport: abort() sends no close frame");
    }

    // ---- (a) backpressure: lock / disarm / overflow on the server thread while the writer is stuck ----------------

    static void backpressure(final String how) throws Exception {
        final Rig r = new Rig(false);
        Check.ok(r.c.armed(), "R4(a) " + how + ": the controller is armed over the real link");
        Check.ok(r.writer != null, "R4(a) " + how + ": the link's writer thread is found");
        // fill the socket: one 15 KB frame at a time, only while the writer is not inside a send, until it stays blocked
        Map<String, Object> body = pad();
        boolean blocked = false;
        long end = System.currentTimeMillis() + 20_000;
        while (!blocked && System.currentTimeMillis() < end && r.link.ready()) {
            if (inSend(r.writer)) {
                blocked = blocked(r.writer);
                continue;
            }
            r.link.send("action.request", body);
        }
        Check.ok(blocked, "R4(a) " + how + ": the real writer is blocked mid-write (the peer never reads)");
        Check.ok(r.link.send("action.request", body) != null, "R4(a) " + how + ": a further frame still queues behind the blocked write");
        Runnable act;
        if (how.equals("lock")) {
            act = new Runnable() {

                @Override
                public void run() {
                    r.c.lockBy(new Controller.Principal(null), "panic under backpressure");
                }
            };
        } else if (how.equals("disarm")) {
            act = new Runnable() {

                @Override
                public void run() {
                    r.facts.online = false; // a security fact turns: the recheck disarms and invalidates
                    r.c.recheck();
                }
            };
        } else {
            act = new Runnable() {

                @Override
                public void run() {
                    // the server thread keeps queueing: the 64-frame outbox overflows and the link is killed
                    Map<String, Object> small = new LinkedHashMap<>();
                    small.put("n", "x");
                    for (int i = 0; i < 200 && r.link.send("action.request", small) != null; i++) {}
                }
            };
        }
        long ms = onServerThread(act);
        Check.ok(ms >= 0 && ms < LIMIT_MS, "R4(a) " + how + ": returns at once on the server thread while the writer is blocked (" + ms + " ms; -1 = still stuck after 3 s)");
        if (how.equals("lock")) Check.ok(r.lock.isLocked(), "R4(a) lock: the game lock is set");
        if (how.equals("disarm")) Check.ok(!r.c.armed(), "R4(a) disarm: disarmed");
        Check.ok(!r.link.ready(), "R4(a) " + how + ": the link is no longer ready");
        Check.ok(r.probe.exited.await(2, TimeUnit.SECONDS), "R4(a) " + how + ": the blocked writer exits");
        Check.ok(r.probe.exitError instanceof IOException, "R4(a) " + how + ": ... with an I/O error from the aborted socket (" + r.probe.exitError + ")");
        r.writer.join(1000);
        Check.ok(!r.writer.isAlive(), "R4(a) " + how + ": the writer thread is gone");
        Check.ok(waitEvent(r.link, ControlLink.Event.DOWN, 3000) != null, "R4(a) " + how + ": the reader is released too and reports the link down");
        long again = onServerThread(new Runnable() {

            @Override
            public void run() {
                r.link.invalidate();
            }
        });
        Check.ok(again >= 0 && again < LIMIT_MS, "R4(a) " + how + ": a second invalidation is a prompt no-op (" + again + " ms)");
        r.close();
    }

    // ---- (b) send-start race: dequeued, then invalidated, then released -------------------------------------------

    static void sendStartRace() throws Exception {
        Rig r = new Rig(true);
        Check.ok(r.c.armed(), "R4(b): the controller is armed over the real link");
        Check.ok(r.peer.hello.contains("hello"), "R4(b): the peer got the signed hello");
        // control: a request with no invalidation reaches the peer through the same writer
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"control frame\"}");
        for (int i = 0; i < 300 && r.peer.got.isEmpty(); i++) Thread.sleep(10);
        Check.eq(r.peer.got.size(), 1, "R4(b): control: a request with no invalidation is written and read by the peer");
        Check.ok(r.peer.got.size() == 1 && r.peer.got.get(0).contains("action.request") && r.peer.got.get(0).contains("control frame"), "R4(b): control: ... it is the action.request");

        // hold the writer between the dequeue and the send-start decision
        CountDownLatch at = new CountDownLatch(1), release = new CountDownLatch(1);
        r.probe.holdNext(at, release);
        r.clock.t += 2100;
        r.c.handleRequest(OWNER, "agent.ask", "{\"agent\":\"helper-a\",\"text\":\"held at the boundary\"}");
        Check.ok(at.await(3, TimeUnit.SECONDS), "R4(b): the writer has dequeued the request and waits before the send-start");
        Check.eq(r.c.pendingRequests(), 2, "R4(b): both requests are pending");
        final Rig rr = r;
        long ms = onServerThread(new Runnable() {

            @Override
            public void run() {
                rr.c.lockBy(new Controller.Principal(null), "lock while a frame is dequeued");
            }
        });
        Check.ok(ms >= 0 && ms < LIMIT_MS, "R4(b): the lock returns at once while the writer holds a dequeued frame (" + ms + " ms)");
        Check.ok(!r.link.ready(), "R4(b): the link is invalidated");
        release.countDown();
        Check.ok(r.probe.exited.await(3, TimeUnit.SECONDS), "R4(b): the writer exits after the release");
        Check.eq(r.probe.dropped.size(), 1, "R4(b): the dequeued frame was dropped at the send-start boundary");
        Check.ok(r.probe.dropped.size() == 1 && r.probe.dropped.get(0).contains("held at the boundary"), "R4(b): ... and it is the held request");
        Check.ok(r.probe.exitError == null, "R4(b): ... the writer left because the link is dead, not on an error (" + r.probe.exitError + ")");
        Check.ok(r.peer.done.await(3, TimeUnit.SECONDS), "R4(b): the peer's session ended (socket aborted)");
        Check.eq(r.peer.got.size(), 1, "R4(b): the peer read nothing after the boundary (only the control frame)");
        boolean leaked = false;
        for (String f : new ArrayList<>(r.peer.got)) leaked |= f.contains("held at the boundary") || f.contains("action.lock");
        Check.ok(!leaked, "R4(b): neither the held request nor a lock notice reached the peer");
        Check.ok(!r.peer.closeFrame, "R4(b): no close frame either (abort, not a graceful close)");

        // resolved like every unsent frame: the link-down answers the pending requests "unknown"
        ControlLink.Event down = waitEvent(r.link, ControlLink.Event.DOWN, 3000);
        Check.ok(down != null, "R4(b): the link reports down");
        if (down != null) r.c.onLinkDown(down.detail);
        int unknown = 0;
        for (String s : new ArrayList<>(r.ctl.results)) if (s.contains("|unknown|") && s.contains("link to the control service closed")) unknown++;
        Check.eq(unknown, 2, "R4(b): both pending requests (the sent one and the dropped one) are answered unknown on link down");
        Check.eq(r.c.pendingRequests(), 0, "R4(b): nothing left pending");
        Check.ok(r.auditText().contains("link closed before an answer"), "R4(b): the audit has the unknown results");
        r.close();
    }
}
