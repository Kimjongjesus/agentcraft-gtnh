package com.robertsnest.aifactory.http;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared-secret authentication for the telemetry and action endpoints.
 *
 * <p>
 * Three properties matter here:
 *
 * <ol>
 * <li><b>Constant-time comparison.</b> A naive {@code equals} returns as soon
 * as two bytes differ, so response timing leaks how much of a guessed
 * token was correct — enough to recover a secret byte by byte. This
 * compares every byte regardless.</li>
 * <li><b>The token never enters a log, a config file in the world directory,
 * or an exception message.</b> World directories get backed up and shared;
 * a secret there is a secret published. It is read from a file whose path
 * the operator chooses.</li>
 * <li><b>An unreadable or empty token file disables the server</b> rather than
 * leaving it open. A missing secret is a misconfiguration, and the safe
 * reading of a misconfiguration is "refuse everything".</li>
 * </ol>
 */
public final class TokenAuthenticator {

    /** Shortest token accepted; anything less is not worth defending. */
    public static final int MIN_TOKEN_LENGTH = 16;

    private final byte[] expected;

    private TokenAuthenticator(byte[] expected) {
        this.expected = expected;
    }

    /**
     * Load a token from disk.
     *
     * @return an authenticator, or null when no usable token is present, in
     *         which case the caller must not start the server
     */
    public static TokenAuthenticator fromFile(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            List<String> lines = Files.readAllLines(path, Charset.forName("UTF-8"));
            for (String line : lines) {
                String candidate = line.trim();
                // Skip comments explicitly. Length alone is not enough: a long
                // "# put your token here" line would otherwise be adopted as
                // the secret and silently authenticate anyone who guessed it.
                if (candidate.isEmpty() || candidate.charAt(0) == '#') {
                    continue;
                }
                if (candidate.length() >= MIN_TOKEN_LENGTH) {
                    return new TokenAuthenticator(candidate.getBytes(Charset.forName("UTF-8")));
                }
            }
            return null;
        } catch (IOException e) {
            // Deliberately no token material and no file content in the message.
            return null;
        }
    }

    /** Build directly from a secret. Intended for tests. */
    public static TokenAuthenticator of(String token) {
        if (token == null || token.trim()
            .length() < MIN_TOKEN_LENGTH) {
            return null;
        }
        return new TokenAuthenticator(
            token.trim()
                .getBytes(Charset.forName("UTF-8")));
    }

    /**
     * Check a presented token in time independent of how much of it matches.
     *
     * <p>
     * Length is compared too, but only after the byte loop, so a wrong-length
     * guess costs the same as a right-length one.
     */
    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        byte[] actual = presented.trim()
            .getBytes(Charset.forName("UTF-8"));
        int difference = actual.length ^ expected.length;
        for (int i = 0; i < actual.length; i++) {
            // Index into expected safely; comparing against a fixed byte for
            // out-of-range positions keeps the loop length tied to the input
            // rather than to how much of the secret matched.
            byte mine = expected[i % expected.length];
            difference |= actual[i] ^ mine;
        }
        return difference == 0;
    }

    /**
     * Extract a bearer token from an Authorization header value.
     *
     * @return the token, or null when the header is absent or malformed
     */
    public static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (authorizationHeader.length() <= prefix.length()) {
            return null;
        }
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorizationHeader.substring(prefix.length())
            .trim();
        return token.isEmpty() ? null : token;
    }
}
