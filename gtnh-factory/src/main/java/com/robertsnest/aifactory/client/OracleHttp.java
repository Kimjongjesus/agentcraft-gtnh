package com.robertsnest.aifactory.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded GET-only consumer; never follows a redirect carrying credentials. */
public final class OracleHttp {

    private final String endpoint;
    private final String token;
    private static final int MAX_BYTES = 512 * 1024;

    public OracleHttp(String endpoint, String token) {
        try {
            URL url = new URL(endpoint);
            boolean loopback = url.getHost()
                .equals("127.0.0.1")
                || url.getHost()
                    .equals("[::1]");
            if (!(url.getProtocol()
                .equals("https")
                || (url.getProtocol()
                    .equals("http") && loopback))
                || url.getUserInfo() != null
                || url.getQuery() != null
                || url.getRef() != null
                || !(url.getPath()
                    .isEmpty()
                    || url.getPath()
                        .equals("/")))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Oracle endpoint must be HTTPS or numeric loopback HTTP, without path or credentials");
        }
        if (token == null || token.length() < 16 || token.length() > 4096 || !token.matches("[!-~]+"))
            throw new IllegalArgumentException("Oracle token must be 16..4096 printable non-space characters");
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.token = token;
    }

    public JsonObject get(String route) throws IOException {
        if (!OracleRequest.allowed(route)) throw new IllegalArgumentException("Not an Oracle read route");
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + route).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(3000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        long deadline = System.nanoTime() + 15000000000L;
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Oracle HTTP " + code);
            if (c.getContentLengthLong() > MAX_BYTES) throw new IOException("Oracle response too large");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > MAX_BYTES || System.nanoTime() > deadline)
                        throw new IOException("Oracle response limit exceeded");
                    out.write(buf, 0, n);
                }
                return new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid Oracle response");
        } finally {
            c.disconnect();
        }
    }
}
