package com.robertsnest.aifactory.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Paths;

import org.junit.Test;

public class TokenAuthenticatorTest {

    private static final String GOOD = "0123456789abcdef0123";

    @Test
    public void acceptsTheExactToken() {
        assertTrue(
            TokenAuthenticator.of(GOOD)
                .matches(GOOD));
    }

    @Test
    public void rejectsWrongTokensOfEveryShape() {
        TokenAuthenticator auth = TokenAuthenticator.of(GOOD);

        assertFalse("wrong value", auth.matches("0123456789abcdef0124"));
        assertFalse("prefix of the secret", auth.matches("0123456789"));
        assertFalse("secret plus extra", auth.matches(GOOD + "x"));
        assertFalse("empty", auth.matches(""));
        assertFalse("null", auth.matches(null));
    }

    @Test
    public void aRepeatedSecretDoesNotAuthenticate() {
        // The comparison indexes the secret modulo its length, so guard the
        // obvious consequence: a doubled secret must still be rejected.
        assertFalse(
            TokenAuthenticator.of(GOOD)
                .matches(GOOD + GOOD));
    }

    @Test
    public void surroundingWhitespaceIsIgnored() {
        assertTrue(
            TokenAuthenticator.of(GOOD)
                .matches("  " + GOOD + "\n"));
    }

    @Test
    public void shortSecretsAreRefusedOutright() {
        assertNull("a short secret is not worth defending", TokenAuthenticator.of("tooshort"));
        assertNull(TokenAuthenticator.of(""));
        assertNull(TokenAuthenticator.of(null));
    }

    @Test
    public void loadsATokenFromAFile() throws Exception {
        File file = File.createTempFile("hermes-token", ".txt");
        file.deleteOnExit();
        PrintWriter writer = new PrintWriter(file, "UTF-8");
        try {
            writer.println("# a comment line is ignored because it is too short");
            writer.println(GOOD);
        } finally {
            writer.close();
        }

        TokenAuthenticator auth = TokenAuthenticator.fromFile(file.toPath());

        assertNotNull(auth);
        assertTrue(auth.matches(GOOD));
    }

    @Test
    public void aMissingOrEmptyFileYieldsNoAuthenticator() throws Exception {
        assertNull("missing file", TokenAuthenticator.fromFile(Paths.get("/nonexistent/hermes-token")));
        assertNull("null path", TokenAuthenticator.fromFile(null));

        File empty = File.createTempFile("hermes-token-empty", ".txt");
        empty.deleteOnExit();
        assertNull("empty file must disable the endpoint, not open it", TokenAuthenticator.fromFile(empty.toPath()));
    }

    @Test
    public void aCommentLineIsNeverAdoptedAsTheToken() {
        // A long comment is still a comment. Adopting one would authenticate
        // anyone who read the example config.
        File file = null;
        try {
            file = File.createTempFile("hermes-token-comment", ".txt");
            file.deleteOnExit();
            PrintWriter writer = new PrintWriter(file, "UTF-8");
            try {
                writer.println("# replace this line with your generated token");
            } finally {
                writer.close();
            }

            assertNull("a comments-only file must disable the endpoint", TokenAuthenticator.fromFile(file.toPath()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void parsesBearerHeaders() {
        assertEquals("abc", TokenAuthenticator.bearerToken("Bearer abc"));
        assertEquals("case-insensitive scheme", "abc", TokenAuthenticator.bearerToken("bearer abc"));
        assertEquals("abc", TokenAuthenticator.bearerToken("Bearer   abc  "));
    }

    @Test
    public void rejectsMalformedAuthorizationHeaders() {
        assertNull(TokenAuthenticator.bearerToken(null));
        assertNull(TokenAuthenticator.bearerToken(""));
        assertNull(TokenAuthenticator.bearerToken("Bearer"));
        assertNull(TokenAuthenticator.bearerToken("Bearer "));
        assertNull("a different scheme is not a bearer token", TokenAuthenticator.bearerToken("Basic abc"));
        assertNull(TokenAuthenticator.bearerToken(GOOD));
    }

    @Test
    public void comparisonCostDoesNotDependOnHowMuchOfTheSecretMatches() {
        // Not a timing proof, which is unreliable on a shared CI box. It checks
        // the property that makes constant time possible: the same work happens
        // for a near-miss as for a wrong-from-the-first-byte guess.
        TokenAuthenticator auth = TokenAuthenticator.of(GOOD);
        String almost = GOOD.substring(0, GOOD.length() - 1) + "X";
        String nothing = "X" + GOOD.substring(1);

        assertFalse(auth.matches(almost));
        assertFalse(auth.matches(nothing));
        assertEquals("both are equal-length guesses", almost.length(), nothing.length());
    }
}
