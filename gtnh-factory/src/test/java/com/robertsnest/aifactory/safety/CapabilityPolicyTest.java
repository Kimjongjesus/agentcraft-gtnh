package com.robertsnest.aifactory.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

public class CapabilityPolicyTest {

    @Test
    public void theDefaultPolicyPermitsOnlyTelemetry() {
        CapabilityPolicy policy = CapabilityPolicy.readOnly();

        assertTrue(policy.permits(FactoryOperation.TELEMETRY_SNAPSHOT));
        for (FactoryOperation operation : FactoryOperation.values()) {
            if (operation != FactoryOperation.TELEMETRY_SNAPSHOT) {
                assertFalse("must deny " + operation, policy.permits(operation));
            }
        }
        assertFalse(policy.allowsMutation());
    }

    @Test
    public void anUnconfiguredPolicyFailsClosed() {
        // A null or empty selection is a misconfiguration, not consent.
        assertFalse(new CapabilityPolicy(null).allowsMutation());
        assertFalse(new CapabilityPolicy(Collections.<FactoryOperation>emptySet()).allowsMutation());
    }

    @Test
    public void irreversibleOperationsCannotBeEnabledByConfiguration() {
        Set<FactoryOperation> denied = EnumSet.of(FactoryOperation.MOVE_INVENTORY, FactoryOperation.PLACE_BLUEPRINT);
        CapabilityPolicy policy = new CapabilityPolicy(denied);

        for (FactoryOperation operation : denied) {
            assertTrue(operation + " must be permanently denied", CapabilityPolicy.isPermanentlyDenied(operation));
            assertFalse(policy.permits(operation));
        }
        assertFalse("enabling only denied operations grants nothing", policy.allowsMutation());
    }

    @Test
    public void reversibleOperationsCanBeEnabledDeliberately() {
        CapabilityPolicy policy = new CapabilityPolicy(
            EnumSet.of(FactoryOperation.SUBMIT_CRAFT, FactoryOperation.CANCEL_CRAFT));

        assertTrue(policy.permits(FactoryOperation.SUBMIT_CRAFT));
        assertTrue(policy.permits(FactoryOperation.CANCEL_CRAFT));
        assertFalse("unlisted operations stay denied", policy.permits(FactoryOperation.CONFIGURE_MACHINE));
        assertTrue(policy.allowsMutation());
    }

    @Test
    public void telemetryIsAlwaysAvailable() {
        // An action-capable server that cannot be observed is worse than one
        // that can, so telemetry is never opt-in.
        assertTrue(
            new CapabilityPolicy(EnumSet.of(FactoryOperation.SUBMIT_CRAFT))
                .permits(FactoryOperation.TELEMETRY_SNAPSHOT));
        assertTrue(new CapabilityPolicy(null).permits(FactoryOperation.TELEMETRY_SNAPSHOT));
    }

    @Test
    public void aNullOperationIsNeverPermitted() {
        assertFalse(
            CapabilityPolicy.readOnly()
                .permits(null));
        assertFalse(CapabilityPolicy.isPermanentlyDenied(null));
    }

    @Test
    public void theEffectivePolicyIsInspectableAndImmutable() {
        CapabilityPolicy policy = new CapabilityPolicy(EnumSet.of(FactoryOperation.SUBMIT_CRAFT));
        Set<FactoryOperation> enabled = policy.enabledOperations();

        assertEquals(EnumSet.of(FactoryOperation.TELEMETRY_SNAPSHOT, FactoryOperation.SUBMIT_CRAFT), enabled);
        try {
            enabled.add(FactoryOperation.MOVE_INVENTORY);
            fail("the effective policy must not be mutable after construction");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void nullEntriesInASelectionAreIgnored() {
        Set<FactoryOperation> requested = new java.util.HashSet<FactoryOperation>();
        requested.add(FactoryOperation.SUBMIT_CRAFT);
        requested.add(null);

        CapabilityPolicy policy = new CapabilityPolicy(requested);

        assertTrue(policy.permits(FactoryOperation.SUBMIT_CRAFT));
        assertFalse(policy.permits(null));
    }
}
