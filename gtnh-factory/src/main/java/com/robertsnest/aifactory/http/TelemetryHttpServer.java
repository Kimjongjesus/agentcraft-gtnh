package com.robertsnest.aifactory.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.robertsnest.aifactory.AiFactoryMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Read-only HTTP surface for factory telemetry.
 *
 * <p>
 * Deliberate limits, each chosen because the alternative is a real hazard on
 * a live game server:
 *
 * <ul>
 * <li><b>Binds loopback by default.</b> A Minecraft server is an internet-
 * facing box; a telemetry port on {@code 0.0.0.0} is an inventory feed for
 * anyone scanning. Remote access is an operator-configured tunnel's job.</li>
 * <li><b>Refuses to start without a token.</b> Failing closed on a missing
 * secret is the only safe reading of that misconfiguration.</li>
 * <li><b>Never touches game state.</b> Every source here reads an already
 * published immutable capture. No handler schedules server-thread work, so
 * request volume cannot cost TPS at all.</li>
 * <li><b>Bounded admission (H3).</b> The executor has a small bounded queue
 * and rejects with 503 when full; a per-route semaphore keeps {@code /health}
 * answerable while telemetry routes are saturated. Response bodies are size
 * capped; the accept backlog is explicit.</li>
 * <li><b>Owns its executor (M2).</b> {@link #stop()} shuts the pool down and
 * waits briefly, so repeated world open/close cycles do not accumulate
 * threads.</li>
 * </ul>
 */
public final class TelemetryHttpServer {

    public static final int DEFAULT_PORT = 25580;
    public static final String DEFAULT_BIND = "127.0.0.1";
    /** Pending exchanges accepted before new ones are refused with 503. */
    public static final int DEFAULT_QUEUE_CAPACITY = 8;
    /** Largest body written; a capture bigger than this is an operator bug. */
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final HttpServer server;
    private final ThreadPoolExecutor pool;
    private final TokenAuthenticator auth;
    private final Semaphore telemetryPermits;
    private final AtomicInteger rejected = new AtomicInteger();
    private volatile boolean stopped;

    private TelemetryHttpServer(HttpServer server, ThreadPoolExecutor pool, TokenAuthenticator auth,
        int telemetryConcurrency) {
        this.server = server;
        this.pool = pool;
        this.auth = auth;
        this.telemetryPermits = new Semaphore(Math.max(1, telemetryConcurrency));
    }

    /** Supplies a JSON body. Must not touch game state; called on an HTTP thread. */
    public interface JsonSource extends Callable<String> {
    }

    /**
     * Start the endpoint.
     *
     * @param routes path to source; {@code /health} is always added
     * @return the running server, or null when it must not run (no token, or
     *         the port could not be bound); the mod stays loaded either way
     */
    public static TelemetryHttpServer start(String bindHost, int port, TokenAuthenticator auth,
        Map<String, JsonSource> routes, JsonSource healthSource) {
        if (auth == null) {
            AiFactoryMod.LOG.warn("AI Factory HTTP telemetry disabled: no usable token file. Endpoint NOT started.");
            return null;
        }
        if (routes == null) {
            throw new IllegalArgumentException("routes are required");
        }
        ThreadPoolExecutor pool = null;
        try {
            String host = bindHost == null || bindHost.trim()
                .isEmpty() ? DEFAULT_BIND : bindHost.trim();
            // Backlog 4: a small explicit accept queue, not the OS default.
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 4);
            pool = new ThreadPoolExecutor(
                2,
                2,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(DEFAULT_QUEUE_CAPACITY),
                new ThreadFactory() {

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "AiFactory-HTTP");
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
            final TelemetryHttpServer instance = new TelemetryHttpServer(http, pool, auth, 1);

            http.createContext("/health", instance.handler(healthSource == null ? new JsonSource() {

                @Override
                public String call() {
                    return "{\"status\":\"ok\"}";
                }
            } : healthSource, false));
            for (Map.Entry<String, JsonSource> route : routes.entrySet()) {
                http.createContext(route.getKey(), instance.handler(route.getValue(), true));
            }
            final ThreadPoolExecutor executor = pool;
            http.setExecutor(new java.util.concurrent.Executor() {

                @Override
                public void execute(Runnable command) {
                    try {
                        executor.execute(command);
                    } catch (RejectedExecutionException e) {
                        // Queue full: refuse on the accept thread with a tiny
                        // fixed body instead of letting the backlog grow.
                        instance.rejected.incrementAndGet();
                        instance.rejectInline(command);
                    }
                }
            });
            http.start();
            AiFactoryMod.LOG.info(
                "AI Factory telemetry listening on {}:{} (read-only, token required)",
                host,
                Integer.valueOf(
                    http.getAddress()
                        .getPort()));
            return instance;
        } catch (IOException e) {
            AiFactoryMod.LOG.error("AI Factory could not start telemetry HTTP endpoint", e);
            if (pool != null) {
                pool.shutdownNow();
            }
            return null;
        } catch (RuntimeException e) {
            AiFactoryMod.LOG.error("AI Factory could not start telemetry HTTP endpoint", e);
            if (pool != null) {
                pool.shutdownNow();
            }
            return null;
        }
    }

    /** Convenience for a single-route server (tests). */
    public static Map<String, JsonSource> routes(String path, JsonSource source) {
        Map<String, JsonSource> m = new LinkedHashMap<String, JsonSource>();
        m.put(path, source);
        return m;
    }

    /**
     * Called on the server's dispatcher thread when the pool refused work.
     * The command is the server's own exchange runnable, so it is run inline
     * with a thread-local flag that makes the handler answer 503 immediately,
     * before authentication and without touching any source. That keeps the
     * cost on the dispatcher to header parsing plus one tiny write, and gives
     * the client a real overload signal instead of a hung connection.
     */
    private void rejectInline(Runnable command) {
        OVERLOADED.set(Boolean.TRUE);
        try {
            command.run();
        } finally {
            OVERLOADED.remove();
        }
    }

    private static final ThreadLocal<Boolean> OVERLOADED = new ThreadLocal<Boolean>();

    /** Requests refused because the queue was full. */
    public int rejectedCount() {
        return rejected.get();
    }

    /** Stop accepting requests and shut the worker pool down. Safe to call more than once. */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            server.stop(1);
        } catch (RuntimeException e) {
            AiFactoryMod.LOG.warn("AI Factory telemetry endpoint did not stop cleanly", e);
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread()
                .interrupt();
        }
    }

    /** True once the worker pool has fully terminated. */
    public boolean terminated() {
        return pool.isTerminated();
    }

    /** The bound port, useful when 0 was requested. */
    public int port() {
        return server.getAddress()
            .getPort();
    }

    private HttpHandler handler(final JsonSource source, final boolean telemetry) {
        return new HttpHandler() {

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                boolean permit = false;
                try {
                    if (Boolean.TRUE.equals(OVERLOADED.get())) {
                        respond(exchange, 503, SnapshotJson.error("server busy"));
                        return;
                    }
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        respond(exchange, 405, SnapshotJson.error("method not allowed"));
                        return;
                    }
                    String presented = TokenAuthenticator.bearerToken(
                        exchange.getRequestHeaders()
                            .getFirst("Authorization"));
                    if (presented == null || !auth.matches(presented)) {
                        // No detail about why: distinguishing "no token" from
                        // "wrong token" helps only an attacker.
                        respond(exchange, 401, SnapshotJson.error("unauthorized"));
                        return;
                    }
                    if (source == null) {
                        respond(exchange, 503, SnapshotJson.error("telemetry source unavailable"));
                        return;
                    }
                    if (telemetry) {
                        // Health never takes this permit, so it stays answerable.
                        permit = telemetryPermits.tryAcquire(200, TimeUnit.MILLISECONDS);
                        if (!permit) {
                            respond(exchange, 503, SnapshotJson.error("server busy"));
                            return;
                        }
                    }
                    String body = source.call();
                    if (body == null) {
                        respond(exchange, 503, SnapshotJson.error("no capture yet"));
                        return;
                    }
                    respond(exchange, 200, body);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                    respond(exchange, 503, SnapshotJson.error("server busy"));
                } catch (Exception e) {
                    AiFactoryMod.LOG.warn("AI Factory telemetry request failed", e);
                    // The message is deliberately generic: exception text can
                    // carry paths and internals a caller has no business seeing.
                    respond(exchange, 500, SnapshotJson.error("internal error"));
                } finally {
                    if (permit) {
                        telemetryPermits.release();
                    }
                    exchange.close();
                }
            }
        };
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        if (bytes.length > MAX_RESPONSE_BYTES) {
            bytes = SnapshotJson.error("response too large")
                .getBytes(Charset.forName("UTF-8"));
            status = 500;
        }
        exchange.getResponseHeaders()
            .set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders()
            .set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }
}
