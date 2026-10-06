package dev.agentcraft.gtnh.bridge;

import java.net.URI;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.agentcraft.gtnh.AgentCraftGTNH;

/**
 * Background thread that keeps a WebSocket to the Hermes adapter open: hello, then every message
 * goes into {@link #inbox} for the server thread to apply (never touch the world from here).
 * Reconnects with exponential backoff (1 s .. 30 s) and re-sends hello; the snapshot rebuilds state.
 * This is a read-only view: the mod never sends anything except hello.
 */
public final class ForemanBridge implements Runnable {

    public final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<>();
    public final AtomicLong received = new AtomicLong();
    public volatile boolean connected;
    public volatile String lastError = "";
    public volatile long connectedSince;

    private final String url;
    private final String modVersion;
    private volatile boolean running = true;
    private volatile WebSocketClient ws;
    private Thread thread;

    public ForemanBridge(String url, String modVersion) {
        this.url = url;
        this.modVersion = modVersion;
    }

    public String url() {
        return url;
    }

    public boolean threadAlive() {
        return thread != null && thread.isAlive();
    }

    public void start() {
        thread = new Thread(this, "AgentCraft-Bridge");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        WebSocketClient c = ws;
        if (c != null) c.close();
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        long backoff = 1000;
        JsonParser parser = new JsonParser();
        while (running) {
            WebSocketClient c = new WebSocketClient();
            ws = c;
            try {
                c.connect(URI.create(url), 5000);
                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("type", "hello");
                hello.addProperty("modVersion", modVersion);
                hello.addProperty("protocol", 1);
                hello.addProperty("client", "gtnh-mod");
                c.sendText(hello.toString());
                connected = true;
                connectedSince = System.currentTimeMillis();
                lastError = "";
                backoff = 1000;
                AgentCraftGTNH.LOG.info("connected to Hermes adapter at {}", url);
                while (running) {
                    String text = c.readText();
                    try {
                        JsonObject msg = parser.parse(text)
                            .getAsJsonObject();
                        received.incrementAndGet();
                        inbox.add(msg);
                    } catch (RuntimeException e) {
                        AgentCraftGTNH.LOG.warn("ignoring unparseable adapter message: {}", e.toString());
                    }
                }
            } catch (Throwable e) {
                if (!running) break;
                String why = e.getClass()
                    .getSimpleName() + ": " + e.getMessage();
                if (connected || !why.equals(lastError)) {
                    AgentCraftGTNH.LOG.warn("adapter link down ({}), retrying in {} ms", why, backoff);
                }
                lastError = why;
            } finally {
                boolean was = connected;
                connected = false;
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // never let cleanup kill the reconnect loop
                }
                if (was) {
                    JsonObject down = new JsonObject();
                    down.addProperty("type", "_disconnected");
                    inbox.add(down);
                }
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }
}
