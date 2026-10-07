package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** Regression for the false "largest stocks" claim and for M11/M12 semantics. */
public class StockCollectorTest {

    private static FactorySnapshot.ItemStock item(String id, long qty) {
        return new FactorySnapshot.ItemStock(id, id, qty, false);
    }

    private static WorkBudget budget(long attempts) {
        return new WorkBudget(attempts, 1_000_000L, new WorkBudgetTest.StepClock(0L));
    }

    @Test
    public void keepsTheGloballyLargestNotAPrefix() {
        StockCollector c = new StockCollector(3, budget(1000L));
        long[] quantities = { 5, 1, 900, 2, 3, 700, 4, 800, 6 };
        for (int i = 0; i < quantities.length; i++) {
            assertTrue(c.admit());
            c.offer(item("i" + i, quantities[i]));
        }
        List<FactorySnapshot.ItemStock> out = c.result();

        assertEquals(3, out.size());
        assertEquals(
            900L,
            out.get(0)
                .quantity());
        assertEquals(
            800L,
            out.get(1)
                .quantity());
        assertEquals(
            700L,
            out.get(2)
                .quantity());
        Coverage cov = c.coverage();
        assertEquals(9L, cov.distinctSeen());
        assertEquals(3L, cov.returned());
        assertTrue("cap cut the list, so partial", cov.truncated());
        assertEquals(Coverage.Status.PARTIAL, cov.status());
        assertFalse(cov.budgetExhausted());
    }

    @Test
    public void tiesAreBrokenByIdSoRepeatReadsAreStable() {
        StockCollector c = new StockCollector(2, budget(10L));
        c.admit();
        c.offer(item("b", 10L));
        c.admit();
        c.offer(item("a", 10L));
        c.admit();
        c.offer(item("c", 10L));
        List<FactorySnapshot.ItemStock> out = c.result();
        assertEquals(
            "a",
            out.get(0)
                .itemId());
        assertEquals(
            "b",
            out.get(1)
                .itemId());
    }

    @Test
    public void budgetStopsTraversalAndMarksPartial() {
        StockCollector c = new StockCollector(10, budget(3L));
        int admitted = 0;
        for (int i = 0; i < 10; i++) {
            if (!c.admit()) {
                break;
            }
            admitted++;
            c.offer(item("i" + i, i));
        }
        assertEquals(3, admitted);
        Coverage cov = c.coverage();
        assertTrue(cov.budgetExhausted());
        assertEquals("attempt_budget", cov.reason());
        assertEquals(Coverage.Status.PARTIAL, cov.status());
        assertEquals(3L, cov.attempted());
    }

    @Test
    public void undescribableEntriesAreErrorsNotSilentlyDropped() {
        StockCollector c = new StockCollector(10, budget(10L));
        c.admit();
        c.offer(null);
        c.admit();
        c.error();
        c.admit();
        c.offer(item("ok", 1L));
        Coverage cov = c.coverage();
        assertEquals(2L, cov.errors());
        assertEquals(1L, cov.succeeded());
        assertFalse(cov.complete());
        assertEquals(Coverage.Status.PARTIAL, cov.status());
    }

    @Test
    public void aCompleteSmallNetworkIsComplete() {
        StockCollector c = new StockCollector(10, budget(10L));
        c.admit();
        c.offer(item("a", 1L));
        c.admit();
        c.offer(item("b", 2L));
        Coverage cov = c.coverage();
        assertTrue(cov.complete());
        assertEquals(Coverage.Status.OK, cov.status());
    }

    @Test
    public void nbtVariantsAreDistinctIds() {
        // The AE2 adapter appends a tag hash; the collector must keep both
        // rows rather than merging them.
        StockCollector c = new StockCollector(10, budget(10L));
        c.admit();
        c.offer(item("mod:cell@0#aaaaaaaaaaaa", 1L));
        c.admit();
        c.offer(item("mod:cell@0#bbbbbbbbbbbb", 1L));
        assertEquals(
            2,
            c.result()
                .size());
    }
}
