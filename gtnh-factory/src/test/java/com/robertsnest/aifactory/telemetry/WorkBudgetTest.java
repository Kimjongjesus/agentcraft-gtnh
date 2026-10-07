package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkBudgetTest {

    @Test
    public void checksDeadlineBeforeEveryUnitIncludingTheFirst() {
        StepClock clock = new StepClock(0L);
        WorkBudget budget = new WorkBudget(4096L, 8L, clock);
        clock.now = 8_000_000L;
        assertFalse("an expired slice cannot admit even its first unit", budget.tryConsume());
        assertEquals("time_budget", budget.exhaustedReason());
    }

    /** Deterministic clock: each nanoTime() call advances by a fixed step. */
    public static final class StepClock implements WorkBudget.Clock {

        public long now;
        public long step;

        public StepClock(long step) {
            this.step = step;
        }

        @Override
        public long nanoTime() {
            long v = now;
            now += step;
            return v;
        }
    }

    @Test
    public void attemptBudgetCountsEveryAttemptIncludingUnloadedOnes() {
        WorkBudget b = new WorkBudget(3L, 1000L, new StepClock(0L));
        assertTrue(b.tryConsume());
        assertTrue(b.tryConsume());
        assertTrue(b.tryConsume());
        assertFalse("fourth attempt is refused", b.tryConsume());
        assertTrue(b.exhausted());
        assertEquals("attempt_budget", b.exhaustedReason());
        assertEquals(4L, b.attempts());
    }

    @Test
    public void timeBudgetStopsWorkWithoutSleeping() {
        // Each clock read advances 1 ms; the deadline is 2 ms; the clock is
        // read every CLOCK_CHECK_INTERVAL attempts, so exhaustion lands on the
        // check after the deadline passes.
        WorkBudget b = new WorkBudget(Long.MAX_VALUE, 2L, new StepClock(1_000_000L));
        int allowed = 0;
        while (b.tryConsume()) {
            allowed++;
            if (allowed > 10 * WorkBudget.CLOCK_CHECK_INTERVAL) {
                break;
            }
        }
        assertTrue(b.exhausted());
        assertEquals("time_budget", b.exhaustedReason());
        assertTrue("stopped near a clock check, allowed=" + allowed, allowed <= 3 * WorkBudget.CLOCK_CHECK_INTERVAL);
    }

    @Test
    public void deadlineReachedChecksImmediately() {
        StepClock clock = new StepClock(0L);
        WorkBudget b = new WorkBudget(10L, 5L, clock);
        assertFalse(b.deadlineReached());
        clock.now += 6_000_000L;
        assertTrue(b.deadlineReached());
        assertFalse("nothing more is allowed once the deadline passed", b.tryConsume());
        assertNull(null);
    }

    @Test
    public void rejectsNonPositiveBudgets() {
        try {
            new WorkBudget(0L, 1L, new StepClock(0L));
            assertTrue(false);
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
        try {
            new WorkBudget(1L, 0L, new StepClock(0L));
            assertTrue(false);
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
