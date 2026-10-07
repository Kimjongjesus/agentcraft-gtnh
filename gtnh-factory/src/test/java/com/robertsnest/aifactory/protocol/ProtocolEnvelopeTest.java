package com.robertsnest.aifactory.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtocolEnvelopeTest {

    @Test
    public void reportsThePinnedV2ProtocolAsSupported() {
        assertEquals("ai-factory/v2", ProtocolEnvelope.currentVersion());
        assertTrue(ProtocolEnvelope.isSupported("ai-factory/v2"));
    }

    @Test
    public void rejectsMissingOldAndUnknownProtocolVersions() {
        assertFalse(ProtocolEnvelope.isSupported(null));
        assertFalse(ProtocolEnvelope.isSupported(""));
        assertFalse(ProtocolEnvelope.isSupported("ai-factory/v0"));
        assertFalse(
            "v1 payloads lack scope/session/coverage and must be refused",
            ProtocolEnvelope.isSupported("ai-factory/v1"));
        assertFalse(ProtocolEnvelope.isSupported("ai-factory/v3"));
    }
}
