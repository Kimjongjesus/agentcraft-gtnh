package com.robertsnest.aifactory.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/**
 * Exercises the endpoint over a real socket. Auth, status codes, admission
 * bounds and lifecycle are the whole point of this class, so they are tested
 * against actual HTTP rather than by calling handler methods directly.
 *
 * <p>
 * These are synthetic socket tests with fixed sources; they are not a Forge
 * server launch and do not claim live-world validation.
 */
public class TelemetryHttpServerTest {

    private static final String TOKEN = "0123456789abcdef0123";

    private TelemetryHttpServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static TelemetryHttpServer.JsonSource fixed(final String body) {
        return new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() {
                return body;
            }
        };
    }

    private TelemetryHttpServer start(TokenAuthenticator auth, TelemetryHttpServer.JsonSource source) {
        Map<String, TelemetryHttpServer.JsonSource> routes = new LinkedHashMap<String, TelemetryHttpServer.JsonSource>();
        routes.put("/telemetry/snapshot", source);
        routes.put("/telemetry/multiblocks", source);
        routes.put("/telemetry/design", source);
        routes.put("/telemetry/capture", source);
        server = TelemetryHttpServer.start("127.0.0.1", 0, auth, routes, null);
        return server;
    }

    private int[] get(String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path)
            .openConnection();
        connection.setRequestMethod("GET");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        int status = connection.getResponseCode();
        InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        int length = 0;
        if (stream != null) {
            length = read(stream).length();
            stream.close();
        }
        connection.disconnect();
        return new int[] { status, length };
    }

    private String body(String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path)
            .openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        InputStream stream = connection.getResponseCode() < 400 ? connection.getInputStream()
            : connection.getErrorStream();
        String text = read(stream);
        stream.close();
        connection.disconnect();
        return text;
    }

    private static String read(InputStream stream) throws IOException {
        StringBuilder out = new StringBuilder();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) > 0) {
            out.append(new String(buffer, 0, count, Charset.forName("UTF-8")));
        }
        return out.toString();
    }

    @Test
    public void refusesToStartWithoutAToken() {
        TelemetryHttpServer started = TelemetryHttpServer
            .start("127.0.0.1", 0, null, TelemetryHttpServer.routes("/x", fixed("{}")), null);

        assertNull("no token must mean no listener, not an open one", started);
    }

    @Test
    public void servesTelemetryToAnAuthenticatedCaller() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{\"stock\":[]}"));

        assertEquals(200, get("/telemetry/snapshot", TOKEN)[0]);
        assertEquals("{\"stock\":[]}", body("/telemetry/snapshot", TOKEN));
    }

    @Test
    public void rejectsMissingWrongPrefixAndDoubledTokens() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{}"));

        assertEquals("no header", 401, get("/telemetry/snapshot", null)[0]);
        assertEquals("wrong token", 401, get("/telemetry/snapshot", "0123456789abcdef9999")[0]);
        assertEquals("prefix of the token", 401, get("/telemetry/snapshot", "0123456789")[0]);
        assertEquals("doubled token", 401, get("/telemetry/snapshot", TOKEN + TOKEN)[0]);
    }

    @Test
    public void unauthorizedRepliesAreByteIdenticalWhicheverCheckFailed() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{}"));

        String absent = errorBody("/telemetry/snapshot", null);
        String wrong = errorBody("/telemetry/snapshot", "0123456789abcdef9999");
        assertEquals("identical bodies for absent and wrong tokens", absent, wrong);
        assertEquals("{\"protocol\":\"ai-factory/v2\",\"error\":\"unauthorized\"}", absent);
    }

    private String errorBody(String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path)
            .openConnection();
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        connection.getResponseCode();
        String text = read(connection.getErrorStream());
        connection.disconnect();
        return text;
    }

    @Test
    public void everyTelemetryRouteIsProtected() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{}"));

        for (String path : new String[] { "/telemetry/multiblocks", "/telemetry/design", "/telemetry/capture" }) {
            assertEquals(path, 401, get(path, null)[0]);
            assertEquals(path, 200, get(path, TOKEN)[0]);
        }
    }

    @Test
    public void healthNeedsAuthAndDoesNotShareTheTelemetryPermit() throws Exception {
        // A telemetry source that blocks until released simulates a slow
        // serialisation; /health must answer while it is held.
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        start(TokenAuthenticator.of(TOKEN), new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() throws Exception {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "{}";
            }
        });
        Thread slow = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    get("/telemetry/snapshot", TOKEN);
                } catch (IOException ignored) {
                    // not the subject of this test
                }
            }
        });
        slow.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        assertEquals(401, get("/health", null)[0]);
        long began = System.currentTimeMillis();
        assertEquals(200, get("/health", TOKEN)[0]);
        assertTrue("health answered while telemetry was busy", System.currentTimeMillis() - began < 2000L);
        assertEquals(
            "a second telemetry call is refused, not queued behind the permit",
            503,
            get("/telemetry/design", TOKEN)[0]);
        release.countDown();
        slow.join(5000L);
    }

    @Test
    public void aSourceWithNoCaptureYetYields503() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed(null));

        assertEquals(503, get("/telemetry/snapshot", TOKEN)[0]);
        assertTrue(body("/telemetry/snapshot", TOKEN).contains("no capture yet"));
    }

    @Test
    public void nonGetMethodsAreRejected() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{}"));

        for (String verb : new String[] { "DELETE", "POST", "PUT" }) {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.port() + "/telemetry/snapshot").openConnection();
            connection.setRequestMethod(verb);
            connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            if (!"DELETE".equals(verb)) {
                connection.setDoOutput(true);
                connection.getOutputStream()
                    .close();
            }
            assertEquals("a read-only surface must refuse " + verb, 405, connection.getResponseCode());
            connection.disconnect();
        }
    }

    @Test
    public void aFailingSourceReportsAGenericError() throws Exception {
        start(TokenAuthenticator.of(TOKEN), new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() {
                throw new IllegalStateException("ME network exploded at /home/user/secret/path");
            }
        });

        int[] result = get("/telemetry/snapshot", TOKEN);
        assertEquals(500, result[0]);
        String text = body("/telemetry/snapshot", TOKEN);
        assertFalse("internals must not leak to the caller", text.contains("/home/user"));
        assertTrue(text.contains("internal error"));
    }

    @Test
    public void defaultBindIsLoopbackAndNotWildcard() throws Exception {
        // Blank host must fall back to loopback: reach it via 127.0.0.1 and
        // prove a non-loopback local address refuses the connection.
        server = TelemetryHttpServer.start(
            "",
            0,
            TokenAuthenticator.of(TOKEN),
            TelemetryHttpServer.routes("/telemetry/snapshot", fixed("{}")),
            null);
        assertNotNull(server);
        assertEquals(200, get("/health", TOKEN)[0]);

        InetAddress local = InetAddress.getLocalHost();
        if (!local.isLoopbackAddress()) {
            Socket probe = new Socket();
            boolean refused = false;
            try {
                probe.connect(new java.net.InetSocketAddress(local, server.port()), 1000);
            } catch (IOException e) {
                refused = true;
            } finally {
                probe.close();
            }
            assertTrue("listener must not be reachable on " + local, refused);
        }
    }

    @Test
    public void oversizedBodiesAreRefusedNotStreamed() throws Exception {
        final StringBuilder huge = new StringBuilder(TelemetryHttpServer.MAX_RESPONSE_BYTES + 10);
        huge.append('"');
        for (int i = 0; i < TelemetryHttpServer.MAX_RESPONSE_BYTES + 8; i++) {
            huge.append('x');
        }
        huge.append('"');
        start(TokenAuthenticator.of(TOKEN), fixed(huge.toString()));

        int[] result = get("/telemetry/snapshot", TOKEN);
        assertEquals(500, result[0]);
        assertTrue("small error body instead of the payload", result[1] < 200);
    }

    @Test
    public void concurrentRequestsAreServedOrHonestlyRefusedNeverHung() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{\"ok\":true}"));
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger busy = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        Thread[] callers = new Thread[24];

        long began = System.currentTimeMillis();
        for (int i = 0; i < callers.length; i++) {
            callers[i] = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        int status = get("/telemetry/snapshot", TOKEN)[0];
                        if (status == 200) {
                            ok.incrementAndGet();
                        } else if (status == 503) {
                            busy.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (IOException e) {
                        // A refused/closed connection under overload is an
                        // honest signal too; it is not a hang.
                        busy.incrementAndGet();
                    }
                }
            });
            callers[i].start();
        }
        for (Thread caller : callers) {
            caller.join(15000L);
        }
        long took = System.currentTimeMillis() - began;

        assertEquals("every caller got a definite outcome", callers.length, ok.get() + busy.get() + failed.get());
        assertEquals("no unexpected status", 0, failed.get());
        assertTrue("at least some succeed", ok.get() > 0);
        assertTrue("a storm must resolve quickly, took " + took + "ms", took < 10000L);
    }

    @Test
    public void stopTerminatesTheWorkerPoolSoRepeatedCyclesDoNotLeakThreads() throws Exception {
        int before = countHttpThreads();
        for (int cycle = 0; cycle < 5; cycle++) {
            start(TokenAuthenticator.of(TOKEN), fixed("{}"));
            assertEquals(200, get("/health", TOKEN)[0]);
            server.stop();
            assertTrue("pool terminated after stop", server.terminated());
            server.stop(); // idempotent
            server = null;
        }
        Thread.sleep(200L);
        int after = countHttpThreads();
        assertTrue("HTTP worker threads before=" + before + " after=" + after, after <= before + 1);
    }

    private static int countHttpThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces()
            .keySet()) {
            if ("AiFactory-HTTP".equals(t.getName()) && t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void portInUseIsReportedAsNullNotACrash() throws Exception {
        start(TokenAuthenticator.of(TOKEN), fixed("{}"));
        TelemetryHttpServer second = TelemetryHttpServer.start(
            "127.0.0.1",
            server.port(),
            TokenAuthenticator.of(TOKEN),
            TelemetryHttpServer.routes("/telemetry/snapshot", fixed("{}")),
            null);
        assertNull("second bind on the same port must fail closed", second);
        assertEquals("first listener unaffected", 200, get("/health", TOKEN)[0]);
    }
}
