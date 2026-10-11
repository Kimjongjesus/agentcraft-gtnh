package dev.agentcraft.gtnh.write.mc;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.agentcraft.gtnh.bridge.WebSocketClient;
import dev.agentcraft.gtnh.write.core.Controller;
import dev.agentcraft.gtnh.write.proto.Clock;
import dev.agentcraft.gtnh.write.proto.Frames;
import dev.agentcraft.gtnh.write.proto.KeyFile;
import dev.agentcraft.gtnh.write.proto.Msg;
import dev.agentcraft.gtnh.write.proto.Receiver;
import dev.agentcraft.gtnh.write.proto.Sender;

/**
 * The signed link to the control service: one daemon thread that connects, runs the handshake
 * (challenge, signed hello, ack, policy, state) and then reads frames; a short-lived writer thread per
 * connection sends what the server thread queued. Fail closed: when the connection is not fully
 * handshaked, {@link #send} returns null at once and nothing is kept for later; everything queued for
 * a connection dies with it. Verified messages go to {@link #inbox} for the server thread.
 *
 * <p>Invalidation boundary (game lock, security disarm, outbox overflow, stop): each connection is one
 * generation ({@code Conn}). A frame may start writing only after the writer, holding that
 * connection's {@code gate}, has seen it is not dead; invalidation flips {@code dead} under the same
 * gate, so every frame either started before the boundary or never starts. The gate is never held
 * across socket I/O. After the flip the outbox is emptied and the socket is aborted (closed at once,
 * no close frame, no wait for the send monitor), which cuts a write that is blocked on backpressure.
 * A frame dequeued but not started when the boundary passes is dropped like the rest of the outbox:
 * nothing is sent, and its pending request is answered "unknown" when the link-down reaches the server
 * thread. A frame whose write started before the boundary may have reached the peer (the control
 * service applies its own lock checks); none can start after it. Nothing here waits on the writer,
 * so a lock or disarm on the server thread returns at once even while the socket is backpressured.
 */
public final class ControlLink implements Controller.Uplink {

    /** One thing for the server thread. */
    public static final class Event {

        public static final int UP = 1, DOWN = 2, FRAME = 3, BAD = 4;
        public final int kind;
        public Msg.Policy policy;
        public Msg.State state;
        public Msg msg;
        public String code = "", detail = "";

        Event(int kind) {
            this.kind = kind;
        }
    }

    private static final class Conn {

        final WebSocketClient ws;
        final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(64);
        /** The send-start / invalidation boundary: {@code dead} only flips under it and a send only starts under it. Held for a flag check, never across I/O. */
        final Object gate = new Object();
        volatile boolean ready, dead;

        Conn(WebSocketClient ws) {
            this.ws = ws;
        }
    }

    public final ConcurrentLinkedQueue<Event> inbox = new ConcurrentLinkedQueue<>();

    private final String url;
    private final Clock clock = Clock.SYSTEM;
    private final byte[] key;
    private final String keyProblem;
    private final Receiver rx;
    private final Sender tx;
    private volatile Conn conn;
    private volatile String note = "not started";
    private volatile boolean stopped;
    private Thread thread;

    /** Test seam (package-private, null in production): sees the writer between dequeue and send-start, drops, and its exit. */
    interface WriterProbe {

        /** The writer took {@code frame} from the outbox and has not decided yet whether it may start. */
        void dequeued(String frame);

        /** {@code frame} was dequeued but the link was invalidated before it could start: it is never written. */
        void dropped(String frame);

        /** The writer thread is leaving; {@code error} is what ended it, null when it left because the link was dead. */
        void exited(Throwable error);
    }

    volatile WriterProbe probe;

    public ControlLink(String url, String keyFile, long processStartMs) {
        this.url = url;
        byte[] k = null;
        String problem = "";
        try {
            k = KeyFile.load(keyFile == null || keyFile.isEmpty() ? null : Paths.get(keyFile));
        } catch (IOException | RuntimeException e) {
            problem = "key file refused: " + e.getMessage();
        }
        this.key = k;
        this.keyProblem = problem;
        this.rx = k == null ? null : new Receiver(k, clock, processStartMs);
        this.tx = k == null ? null : new Sender(k, clock, new SecureRandom());
        if (k == null) note = problem;
    }

    public static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h;
        } catch (URISyntaxException e) {
            return "";
        }
    }

    public String note() {
        return note;
    }

    public boolean clockFault() {
        return rx != null && rx.clockFault();
    }

    public void start() {
        if (key == null) return;
        thread = new Thread(this::run, "AgentCraftWrite-link");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        Conn c = conn;
        if (c != null) kill(c);
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean ready() {
        Conn c = conn;
        return c != null && c.ready && !c.dead;
    }

    @Override
    public String send(String type, Map<String, Object> body) {
        Conn c = conn;
        if (c == null || !c.ready || c.dead || tx == null) return null;
        String id = tx.nextId();
        String frame = tx.frame(type, id, body);
        if (frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Frames.MAX_BYTES) return null; // would be refused as oversize
        if (!c.outbox.offer(frame)) {
            kill(c);
            return null;
        }
        return id;
    }

    /** The write link's transport: one message is at most the wire limit (16384 bytes), text only, checked before any allocation. */
    public static WebSocketClient newClient() {
        return new WebSocketClient(Frames.MAX_BYTES, true);
    }

    /**
     * Atomic invalidation (game lock, security disarm): the connection is marked dead under its send gate
     * (no frame starts after that), the outbox is emptied, the socket is aborted. A frame whose write
     * started before may have reached the peer; nothing queued, or dequeued but not started, ever does.
     * Never blocks: safe on the server thread while the writer is stuck on a full socket.
     */
    @Override
    public void invalidate() {
        Conn c = conn;
        if (c != null) kill(c);
    }

    private static void kill(Conn c) {
        synchronized (c.gate) {
            c.dead = true;
            c.ready = false;
        }
        c.outbox.clear();
        c.ws.abort(); // no close frame, no wait for the send monitor: a write blocked on backpressure fails now
    }

    /** The send-start decision: true (the frame may be written) only while this connection is not dead. */
    private static boolean mayStart(Conn c) {
        synchronized (c.gate) {
            return !c.dead;
        }
    }

    private void run() {
        long backoff = 1000;
        while (!stopped) {
            Conn c = null;
            boolean wasUp = false;
            String why = "closed";
            try {
                note = "connecting to " + url;
                WebSocketClient ws = newClient();
                c = new Conn(ws);
                ws.connect(new URI(url), 5000);
                rx.resetSession();
                tx.setSession("");
                handshake(c);
                wasUp = true;
                backoff = 1000;
                loop(c);
            } catch (Frames.Reject e) {
                why = "protocol: " + e.getMessage();
                if (!wasUp && inbox.size() < 256) {
                    Event b = new Event(Event.BAD);
                    b.code = "handshake-" + e.code;
                    b.detail = e.getMessage();
                    inbox.add(b);
                }
            } catch (IOException | URISyntaxException | RuntimeException e) {
                why = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c != null) kill(c);
                conn = null;
            }
            note = "link down (" + why + "); retrying";
            if (wasUp) {
                Event d = new Event(Event.DOWN);
                d.detail = why;
                inbox.add(d);
            }
            if (stopped) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                if (stopped) break;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private Msg next(Conn c) throws IOException, Frames.Reject {
        String text = c.ws.readText();
        return rx.accept(text);
    }

    /** challenge -> hello -> ack -> policy -> state, in that order; anything else closes the connection. */
    private void handshake(Conn c) throws IOException, Frames.Reject {
        note = "handshake";
        Msg m = next(c);
        if (!(m instanceof Msg.Challenge)) throw new Frames.Reject("handshake", "first frame is not action.challenge", true, true);
        tx.setSession(rx.session());
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("challenge", ((Msg.Challenge) m).challenge);
        hello.put("features", Collections.<Object>singletonList("action"));
        c.ws.sendText(tx.frame("hello", tx.nextId(), hello));
        m = next(c);
        if (!(m instanceof Msg.Ack) || !((Msg.Ack) m).features.contains("action")) throw new Frames.Reject("handshake", "expected ack with the action feature", true, true);
        m = next(c);
        if (!(m instanceof Msg.Policy)) throw new Frames.Reject("handshake", "expected action.policy after ack", true, true);
        Msg.Policy pol = (Msg.Policy) m;
        m = next(c);
        if (!(m instanceof Msg.State)) throw new Frames.Reject("handshake", "expected action.state after action.policy", true, true);
        conn = c;
        c.ready = true;
        note = "ready";
        Event up = new Event(Event.UP);
        up.policy = pol;
        up.state = (Msg.State) m;
        inbox.add(up);
        Thread w = new Thread(() -> writer(c), "AgentCraftWrite-link-writer");
        w.setDaemon(true);
        w.start();
    }

    private void loop(Conn c) throws IOException, Frames.Reject {
        while (!stopped && !c.dead) {
            String text = c.ws.readText();
            try {
                Msg m = rx.accept(text);
                if (m instanceof Msg.Challenge || m instanceof Msg.Ack) throw new Frames.Reject("handshake", "repeated " + m.type, true, true);
                Event e = new Event(Event.FRAME);
                e.msg = m;
                inbox.add(e);
            } catch (Frames.Reject r) {
                if (r.fatal) throw r;
                if (inbox.size() < 256) {
                    Event e = new Event(Event.BAD);
                    e.code = r.code;
                    e.detail = r.getMessage();
                    inbox.add(e);
                }
            }
        }
    }

    private void writer(Conn c) {
        Throwable error = null;
        try {
            while (!c.dead) {
                String f = c.outbox.poll(1, TimeUnit.SECONDS);
                if (f == null) continue;
                WriterProbe p = probe;
                if (p != null) p.dequeued(f);
                if (!mayStart(c)) { // invalidated after the dequeue: dropped like the rest of the outbox
                    if (p != null) p.dropped(f);
                    break;
                }
                c.ws.sendText(f); // started before the boundary; an abort from here on fails this write
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            error = e;
            kill(c);
        } finally {
            WriterProbe p = probe;
            if (p != null) p.exited(error);
        }
    }

    /** For status text only. */
    public List<String> describe() {
        List<String> l = new ArrayList<>();
        l.add(url + " - " + note);
        return l;
    }
}
