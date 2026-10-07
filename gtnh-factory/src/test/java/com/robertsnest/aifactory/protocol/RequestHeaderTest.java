package com.robertsnest.aifactory.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RequestHeaderTest {

    @Test
    public void acceptsAWellFormedReadOnlyTelemetryHeader() {
        RequestHeader header = new RequestHeader("ai-factory/v2", "req-123", "Player1", 42L);

        assertEquals("ai-factory/v2", header.protocolVersion());
        assertEquals("req-123", header.requestId());
        assertEquals("Player1", header.actor());
        assertEquals(42L, header.worldRevision());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnUnsupportedProtocolVersion() {
        new RequestHeader("ai-factory/v1", "req-123", "Player1", 42L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsABlankRequestId() {
        new RequestHeader("ai-factory/v2", " ", "Player1", 42L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANullRequestId() {
        new RequestHeader("ai-factory/v2", null, "Player1", 42L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsABlankActor() {
        new RequestHeader("ai-factory/v2", "req-123", " ", 42L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANullActor() {
        new RequestHeader("ai-factory/v2", "req-123", null, 42L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANegativeWorldRevision() {
        new RequestHeader("ai-factory/v2", "req-123", "Player1", -1L);
    }
}
