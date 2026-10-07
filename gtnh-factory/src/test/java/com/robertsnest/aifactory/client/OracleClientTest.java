package com.robertsnest.aifactory.client;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

public class OracleClientTest {

    @Test
    public void chatUsesEncodedReadOnlyRoutes() throws Exception {
        assertEquals("/oracle/base", OracleRequest.command(new String[] { "status" }));
        assertEquals("/oracle/shift", OracleRequest.command(new String[] { "shift" }));
        assertEquals(
            "/oracle/diagnose?symptom=EBF+%26+wire",
            OracleRequest.command(new String[] { "diagnose", "EBF", "&", "wire" }));
        assertEquals(
            "/oracle/ask?persona=power&question=why+idle%3F",
            OracleRequest.command(new String[] { "ask", "power", "why", "idle?" }));
        for (String[] bad : new String[][] { {}, { "craft" }, { "ask", "power" }, { "status", "extra" } }) {
            try {
                OracleRequest.command(bad);
                fail("invalid command accepted");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void realSocketGetRejectsRedirectsErrorsAndOversize() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oracle/base", e -> {
            observed.set(
                e.getRequestMethod() + ":"
                    + e.getRequestHeaders()
                        .getFirst("Authorization"));
            byte[] body = "{\"status\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody()
                .write(body);
            e.close();
        });
        server.createContext("/oracle/shift", e -> {
            e.getResponseHeaders()
                .set("Location", "/oracle/base");
            e.sendResponseHeaders(302, -1);
            e.close();
        });
        server.createContext("/oracle/personas", e -> {
            e.sendResponseHeaders(401, -1);
            e.close();
        });
        server.createContext("/oracle/overlay", e -> {
            e.sendResponseHeaders(200, 600000);
            e.close();
        });
        server.start();
        try {
            OracleHttp http = new OracleHttp(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort(),
                "fixture-credential-only");
            assertEquals(
                "fixture",
                http.get("/oracle/base")
                    .get("status")
                    .getAsString());
            assertEquals("GET:Bearer fixture-credential-only", observed.get());
            for (String route : new String[] { "/oracle/shift", "/oracle/personas", "/oracle/overlay", "/oracle/craft",
                "//evil/" }) {
                try {
                    http.get(route);
                    fail("unsafe/error response accepted");
                } catch (java.io.IOException | IllegalArgumentException expected) {
                    assertFalse(
                        expected.toString()
                            .contains("fixture-credential-only"));
                }
            }
            try {
                new OracleHttp("http://example.com", "fixture-credential-only");
                fail("plaintext remote accepted");
            } catch (IllegalArgumentException expected) {}
        } finally {
            server.stop(0);
        }
    }
}
