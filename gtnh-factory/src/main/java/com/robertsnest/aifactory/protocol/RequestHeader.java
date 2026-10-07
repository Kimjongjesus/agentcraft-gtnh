package com.robertsnest.aifactory.protocol;

/**
 * Metadata required on every request from the external oracle. This is not a
 * permission grant: Forge-side validators will later bind the actor to a real
 * Minecraft identity and validate the current world revision before an action.
 */
public final class RequestHeader {

    private final String protocolVersion;
    private final String requestId;
    private final String actor;
    private final long worldRevision;

    public RequestHeader(String protocolVersion, String requestId, String actor, long worldRevision) {
        if (!ProtocolEnvelope.isSupported(protocolVersion)) {
            throw new IllegalArgumentException("unsupported protocol version");
        }
        if (isBlank(requestId)) {
            throw new IllegalArgumentException("request ID is required");
        }
        if (isBlank(actor)) {
            throw new IllegalArgumentException("actor is required");
        }
        if (worldRevision < 0L) {
            throw new IllegalArgumentException("world revision cannot be negative");
        }

        this.protocolVersion = protocolVersion;
        this.requestId = requestId;
        this.actor = actor;
        this.worldRevision = worldRevision;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    public String requestId() {
        return requestId;
    }

    public String actor() {
        return actor;
    }

    public long worldRevision() {
        return worldRevision;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim()
            .isEmpty();
    }
}
