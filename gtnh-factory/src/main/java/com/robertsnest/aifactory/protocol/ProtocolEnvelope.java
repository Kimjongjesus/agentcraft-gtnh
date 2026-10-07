package com.robertsnest.aifactory.protocol;

/**
 * Stable, dependency-free protocol-version gate shared by every telemetry
 * payload and every future action request.
 *
 * <p>
 * <b>v2 is a deliberate break from v1.</b> v1 payloads carried no scope, no
 * session identity, no dimension on machine IDs and no per-section coverage,
 * so a consumer could not tell an empty base from an unreadable one. Rather
 * than let the same field names quietly change meaning, the version string
 * changed and the Oracle refuses v1. {@code worldRevision},
 * {@code capturedAtMillis} and {@code protocol} keep their v1 names and
 * meanings.
 */
public final class ProtocolEnvelope {

    private static final String CURRENT_VERSION = "ai-factory/v2";

    private ProtocolEnvelope() {}

    public static String currentVersion() {
        return CURRENT_VERSION;
    }

    public static boolean isSupported(String version) {
        return CURRENT_VERSION.equals(version);
    }
}
