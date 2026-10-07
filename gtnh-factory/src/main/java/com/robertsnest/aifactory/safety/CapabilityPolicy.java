package com.robertsnest.aifactory.safety;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Deny-by-default capability gate for every operation the oracle may request.
 *
 * <p>
 * Two properties matter more than convenience here:
 *
 * <ol>
 * <li><b>Nothing is enabled implicitly.</b> An operation is permitted only if
 * an operator listed it. A default-constructed policy permits telemetry
 * and nothing else, so a misconfigured or partially-initialised mod fails
 * closed rather than open.</li>
 * <li><b>Some operations cannot be enabled at all yet.</b> {@link
 * FactoryOperation#MOVE_INVENTORY} and {@link
 * FactoryOperation#PLACE_BLUEPRINT} can destroy items or overwrite a
 * build irreversibly, and this mod has no undo. They are rejected even
 * when an operator names them, so a typo in a config file cannot arm
 * them. Lifting that requires a reviewed code change, which is the
 * point.</li>
 * </ol>
 */
public final class CapabilityPolicy {

    /**
     * Operations that no configuration may enable in this release.
     *
     * <p>
     * These mutate or destroy state with no reversal path. Crafting, by
     * contrast, consumes resources the player could have consumed anyway and
     * can be cancelled.
     */
    private static final Set<FactoryOperation> PERMANENTLY_DENIED = Collections
        .unmodifiableSet(EnumSet.of(FactoryOperation.MOVE_INVENTORY, FactoryOperation.PLACE_BLUEPRINT));

    private final Set<FactoryOperation> enabled;

    /** A telemetry-only policy: the safe default for an unconfigured server. */
    public static CapabilityPolicy readOnly() {
        return new CapabilityPolicy(EnumSet.of(FactoryOperation.TELEMETRY_SNAPSHOT));
    }

    /**
     * Build a policy from an operator's selection.
     *
     * <p>
     * Telemetry is always included: an action-capable server that cannot be
     * observed is strictly worse than one that can. Permanently denied entries
     * are dropped here rather than at request time so the effective policy is
     * inspectable and can be logged at boot.
     */
    public CapabilityPolicy(Set<FactoryOperation> requested) {
        EnumSet<FactoryOperation> effective = EnumSet.of(FactoryOperation.TELEMETRY_SNAPSHOT);
        if (requested != null) {
            for (FactoryOperation operation : requested) {
                if (operation != null && !PERMANENTLY_DENIED.contains(operation)) {
                    effective.add(operation);
                }
            }
        }
        this.enabled = Collections.unmodifiableSet(effective);
    }

    public boolean permits(FactoryOperation operation) {
        return operation != null && enabled.contains(operation);
    }

    /** True when this policy can change game state at all. */
    public boolean allowsMutation() {
        for (FactoryOperation operation : enabled) {
            if (operation != FactoryOperation.TELEMETRY_SNAPSHOT) {
                return true;
            }
        }
        return false;
    }

    /** The effective permitted set, for boot logging and the health endpoint. */
    public Set<FactoryOperation> enabledOperations() {
        return enabled;
    }

    /** True when no configuration may ever enable this operation in this build. */
    public static boolean isPermanentlyDenied(FactoryOperation operation) {
        return operation != null && PERMANENTLY_DENIED.contains(operation);
    }
}
