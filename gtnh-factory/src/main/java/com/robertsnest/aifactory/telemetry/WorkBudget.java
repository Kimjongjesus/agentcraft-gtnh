package com.robertsnest.aifactory.telemetry;

/**
 * A bound on how much work one capture may do on the server thread.
 *
 * <p>
 * <b>Why counting samples was not enough.</b> The previous release bounded
 * "positions successfully read", and incremented that counter only after the
 * loaded-chunk guard passed. In a sparsely loaded region the guard rejects a
 * position and the loop continues without ever approaching the ceiling, so a
 * maximum-radius survey could perform roughly seventeen million existence
 * checks while reporting a few thousand samples. The ceiling bounded the
 * payload, not the work.
 *
 * <p>
 * This bounds admission by attempts and elapsed time. An indivisible adapter
 * callback cannot be preempted, so one unit may overrun the deadline. The
 * default 8 ms slice is a target, not a hard real-world latency guarantee.
 *
 * <p>
 * The clock is checked before every unit. Amortizing across candidates let
 * expensive machine adapters consume an entire tick before noticing expiry.
 *
 * <p>
 * Not thread safe; one budget belongs to one capture on the server thread.
 */
public final class WorkBudget {

    /** Attempts between clock reads. */
    public static final int CLOCK_CHECK_INTERVAL = 1;

    /** Default share of a 50 ms tick one capture may consume. */
    public static final long DEFAULT_BUDGET_MILLIS = 8L;

    private final long maxAttempts;
    private final long deadlineNanos;
    private final long startNanos;
    private final Clock clock;

    private long attempts;

    private boolean exhausted;
    private String exhaustedReason;

    /** Time source. Exists so tests can exhaust a deadline without sleeping. */
    public interface Clock {

        long nanoTime();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    public static WorkBudget of(long maxAttempts, long budgetMillis) {
        return new WorkBudget(maxAttempts, budgetMillis, SYSTEM_CLOCK);
    }

    public WorkBudget(long maxAttempts, long budgetMillis, Clock clock) {
        if (maxAttempts < 1L) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (budgetMillis < 1L) {
            throw new IllegalArgumentException("budgetMillis must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.maxAttempts = maxAttempts;
        this.clock = clock;
        this.startNanos = clock.nanoTime();
        this.deadlineNanos = startNanos + budgetMillis * 1_000_000L;
    }

    /**
     * Record one unit of attempted work and report whether more is allowed.
     *
     * <p>
     * Call this <em>before</em> every candidate position, including ones that
     * turn out to be in an unloaded chunk. That is the whole point: the
     * existence check is itself the work being bounded.
     *
     * @return true when the caller may proceed with this unit of work
     */
    public boolean tryConsume() {
        if (exhausted) {
            return false;
        }
        attempts++;
        if (attempts > maxAttempts) {
            exhausted = true;
            exhaustedReason = "attempt_budget";
            return false;
        }
        return !deadlineReached();
    }

    /**
     * Check the deadline right now, regardless of the sampling interval.
     *
     * <p>
     * For coarse-grained work — one multiblock controller, one ME row — where
     * a single unit is expensive enough that amortising the clock read would
     * let a whole tick slip.
     */
    public boolean deadlineReached() {
        if (exhausted) {
            return true;
        }
        if (clock.nanoTime() >= deadlineNanos) {
            exhausted = true;
            exhaustedReason = "time_budget";
            return true;
        }
        return false;
    }

    public boolean exhausted() {
        return exhausted;
    }

    /** Which limit stopped the work: "attempt_budget", "time_budget", or null. */
    public String exhaustedReason() {
        return exhaustedReason;
    }

    /** Units of work attempted, including ones that could not be completed. */
    public long attempts() {
        return attempts;
    }

    public long elapsedMillis() {
        return (clock.nanoTime() - startNanos) / 1_000_000L;
    }
}
