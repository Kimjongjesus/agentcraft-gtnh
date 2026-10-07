package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FactorySnapshotTest {

    private static FactorySnapshot.ItemStock stock(String id, long qty) {
        return new FactorySnapshot.ItemStock(id, id, qty, false);
    }

    @Test
    public void carriesTheWorldRevisionAnActionWillBeCheckedAgainst() {
        FactorySnapshot snapshot = new FactorySnapshot(42L, 1000L, null, null, false);

        assertEquals(42L, snapshot.worldRevision());
        assertFalse(snapshot.truncated());
    }

    @Test
    public void isImmutableOnceBuilt() {
        List<FactorySnapshot.ItemStock> source = new ArrayList<FactorySnapshot.ItemStock>();
        source.add(stock("gt:circuit", 64L));
        FactorySnapshot snapshot = new FactorySnapshot(1L, 0L, source, null, false);

        // Mutating the source must not change a snapshot already handed out.
        source.add(stock("gt:plate", 1L));
        assertEquals(
            "defensive copy taken",
            1,
            snapshot.stock()
                .size());

        try {
            snapshot.stock()
                .add(stock("gt:ingot", 1L));
            fail("snapshot contents must not be modifiable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void reportsTruncationRatherThanImplyingAnEmptyNetwork() {
        FactorySnapshot snapshot = new FactorySnapshot(1L, 0L, Arrays.asList(stock("gt:circuit", 5L)), null, true);

        assertTrue("caller must be able to tell a capped scan from a complete one", snapshot.truncated());
    }

    @Test
    public void rejectsANegativeWorldRevision() {
        try {
            new FactorySnapshot(-1L, 0L, null, null, false);
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("revision"));
        }
    }

    @Test
    public void rejectsMalformedStockEntries() {
        try {
            new FactorySnapshot.ItemStock("", "x", 1L, false);
            fail("expected a rejection for a blank item ID");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("item ID"));
        }
        try {
            new FactorySnapshot.ItemStock("gt:circuit", "x", -5L, false);
            fail("expected a rejection for a negative quantity");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("quantity"));
        }
    }

    @Test
    public void machineStatusCarriesItsFault() {
        FactorySnapshot.MachineStatus broken = new FactorySnapshot.MachineStatus(
            "gt:ebf-1",
            "Electric Blast Furnace",
            false,
            "missing input hatch");
        FactorySnapshot.MachineStatus healthy = new FactorySnapshot.MachineStatus(
            "gt:ebf-2",
            "Electric Blast Furnace",
            true,
            null);

        assertFalse(broken.active());
        assertEquals("missing input hatch", broken.problem());
        assertTrue(healthy.active());
        assertEquals("a healthy machine reports no problem", null, healthy.problem());
    }
}
