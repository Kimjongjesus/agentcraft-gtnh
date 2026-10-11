package dev.agentcraft.gtnh.write.core;

import java.util.List;

/**
 * Everything the arming gate (docs/write-path.md section 7.1) needs to know, as plain values, so
 * {@link Gate} is testable without Minecraft. The Minecraft implementation reads the live server.
 * {@link #whitelistEnforced()}, {@link #whitelist()} and {@link #ops()} are only called when
 * {@link #dedicated()} is true (the dedicated player list exists only there).
 */
public interface GateFacts {

    boolean dedicated();

    boolean onlineMode();

    boolean whitelistEnforced();

    /** Whitelist entries as lowercase canonical UUID strings ("?name" for an entry without a UUID). */
    List<String> whitelist();

    /** Ops as lowercase canonical UUID strings ("?name" for an entry without a UUID). */
    List<String> ops();

    /** write.owner as configured (may be empty or malformed). */
    String owner();

    /** server.properties server-ip ("" when unset). */
    String serverIp();

    /** Host part of write.controlUrl. */
    String controlHost();

    /** Value of -Dagentcraft.write.devOverride, or null. */
    String devOverrideProperty();

    /** The signed handshake completed (ack, action.policy and action.state received) on a live connection. */
    boolean linkReady();

    /** Why the link is not ready (key refused, connecting, ...); shown in status. */
    String linkNote();

    /** The control service's policy actors, or null when no policy has arrived. */
    List<String> policyActors();

    boolean policyDryRun();

    /** action.state armed flag of the control service. */
    boolean hermesArmed();

    boolean hermesLocked();

    String hermesLockReason();

    boolean gameLocked();

    String gameLockInfo();

    boolean auditWritable();

    String auditError();

    boolean clockFault();

    /** A result without dryRun was seen under the dev override: the override is dead until restart. */
    boolean overrideTainted();
}
