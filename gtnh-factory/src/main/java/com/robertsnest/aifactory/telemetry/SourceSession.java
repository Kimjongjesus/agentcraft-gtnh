package com.robertsnest.aifactory.telemetry;

import java.util.UUID;

/**
 * Identifies the running server instance a capture came from.
 *
 * <p>
 * <b>Why a consumer cannot do without this.</b> The world revision is a
 * server tick counter. It resets to zero every time a world is loaded, so a
 * history keyed on revision alone silently treats "the world restarted" as
 * "time went backwards", and a rate computed across that boundary is
 * arbitrary. The session ID changes on every server start, which is exactly
 * the signal needed to split history into runs instead of extrapolating
 * through a gap that never happened.
 */
public final class SourceSession {

    private final String sessionId;
    private final long startedAtMillis;
    private final String modVersion;

    /** A fresh identity for a newly started server. */
    public static SourceSession start(String modVersion, long nowMillis) {
        return new SourceSession(
            UUID.randomUUID()
                .toString(),
            nowMillis,
            modVersion);
    }

    public SourceSession(String sessionId, long startedAtMillis, String modVersion) {
        if (sessionId == null || sessionId.trim()
            .isEmpty()) {
            throw new IllegalArgumentException("session ID is required");
        }
        this.sessionId = sessionId;
        this.startedAtMillis = startedAtMillis;
        this.modVersion = modVersion == null ? "unknown" : modVersion;
    }

    public String sessionId() {
        return sessionId;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public String modVersion() {
        return modVersion;
    }
}
