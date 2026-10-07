package com.robertsnest.aifactory.telemetry;

/**
 * How much of a section the capture actually managed to see, and why not more.
 *
 * <p>
 * <b>This type exists because the previous release lied by omission.</b> A
 * failed maintenance query became {@code needsMaintenance:false}; an
 * unreachable ME network became an empty stock list; a failed light read became
 * measured darkness. Every one of those reads to a consumer as a confident
 * fact. Missing data must be distinguishable from measured zero, so every
 * section of a capture carries one of these and a consumer that ignores it is
 * making an explicit mistake rather than an invisible one.
 */
public final class Coverage {

    /** Why a section's data is the way it is. */
    public enum Status {
        /** Everything in scope was read successfully. */
        OK,
        /** Read succeeded but stopped short: a budget, a cap, or unloaded data. */
        PARTIAL,
        /** The source does not exist here (no AE2, no GregTech, nothing configured). */
        UNAVAILABLE,
        /** The source exists but reading it failed. */
        ERROR
    }

    private final Status status;
    private final String reason;
    private final long attempted;
    private final long succeeded;
    private final long errors;
    private final long skippedUnloaded;
    private final long returned;
    private final long distinctSeen;
    private final boolean truncated;
    private final boolean budgetExhausted;
    private final long elapsedMillis;

    private Coverage(Builder b) {
        this.status = b.status == null ? Status.ERROR : b.status;
        this.reason = b.reason;
        this.attempted = b.attempted;
        this.succeeded = b.succeeded;
        this.errors = b.errors;
        this.skippedUnloaded = b.skippedUnloaded;
        this.returned = b.returned;
        this.distinctSeen = b.distinctSeen;
        this.truncated = b.truncated;
        this.budgetExhausted = b.budgetExhausted;
        this.elapsedMillis = b.elapsedMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The source is not present on this server. Not an error, and not zero. */
    public static Coverage unavailable(String reason) {
        return builder().status(Status.UNAVAILABLE)
            .reason(reason)
            .build();
    }

    /** The source is present but could not be read. */
    public static Coverage error(String reason) {
        return builder().status(Status.ERROR)
            .reason(reason)
            .build();
    }

    public Status status() {
        return status;
    }

    /**
     * Short machine-readable explanation, or null.
     *
     * <p>
     * Never carries an exception message: those leak file paths and internals
     * to a caller that has no business seeing them.
     */
    public String reason() {
        return reason;
    }

    public long attempted() {
        return attempted;
    }

    public long succeeded() {
        return succeeded;
    }

    public long errors() {
        return errors;
    }

    public long skippedUnloaded() {
        return skippedUnloaded;
    }

    /** Entries actually included in the payload. */
    public long returned() {
        return returned;
    }

    /**
     * Distinct entries observed, which can exceed {@link #returned()}.
     *
     * <p>
     * The old palette reported its own returned size as the number of types
     * seen, so a capped 40-entry palette claimed the base used exactly 40
     * kinds of block. Counting separately is the fix.
     */
    public long distinctSeen() {
        return distinctSeen;
    }

    public boolean truncated() {
        return truncated;
    }

    /** True when a position or elapsed-time budget stopped the work. */
    public boolean budgetExhausted() {
        return budgetExhausted;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }

    /** True when a consumer may treat the numbers as a complete census of the scope. */
    public boolean complete() {
        return status == Status.OK && !truncated && !budgetExhausted && errors == 0L && skippedUnloaded == 0L;
    }

    /** True when there is data worth reading at all. */
    public boolean usable() {
        return status == Status.OK || status == Status.PARTIAL;
    }

    public Builder toBuilder() {
        return builder().status(status)
            .reason(reason)
            .attempted(attempted)
            .succeeded(succeeded)
            .errors(errors)
            .skippedUnloaded(skippedUnloaded)
            .returned(returned)
            .distinctSeen(distinctSeen)
            .truncated(truncated)
            .budgetExhausted(budgetExhausted)
            .elapsedMillis(elapsedMillis);
    }

    /** Accumulates counts during a read and settles the status at the end. */
    public static final class Builder {

        private Status status;
        private String reason;
        private long attempted;
        private long succeeded;
        private long errors;
        private long skippedUnloaded;
        private long returned;
        private long distinctSeen;
        private boolean truncated;
        private boolean budgetExhausted;
        private long elapsedMillis;

        public Builder status(Status value) {
            this.status = value;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public Builder attempted(long value) {
            this.attempted = value;
            return this;
        }

        public Builder succeeded(long value) {
            this.succeeded = value;
            return this;
        }

        public Builder errors(long value) {
            this.errors = value;
            return this;
        }

        public Builder skippedUnloaded(long value) {
            this.skippedUnloaded = value;
            return this;
        }

        public Builder returned(long value) {
            this.returned = value;
            return this;
        }

        public Builder distinctSeen(long value) {
            this.distinctSeen = value;
            return this;
        }

        public Builder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        public Builder budgetExhausted(boolean value) {
            this.budgetExhausted = value;
            return this;
        }

        public Builder elapsedMillis(long value) {
            this.elapsedMillis = value;
            return this;
        }

        public Builder countAttempt() {
            this.attempted++;
            return this;
        }

        public Builder countSuccess() {
            this.succeeded++;
            return this;
        }

        public Builder countError() {
            this.errors++;
            return this;
        }

        public Builder countSkippedUnloaded() {
            this.skippedUnloaded++;
            return this;
        }

        /**
         * Settle OK versus PARTIAL from the counts actually recorded.
         *
         * <p>
         * Deriving it here rather than trusting a caller-supplied boolean is
         * deliberate: the old code set {@code truncated:false} while silently
         * dropping unreadable entries, and a derived status cannot drift from
         * the counts the same payload reports.
         */
        public Builder settle() {
            if (status == Status.UNAVAILABLE || status == Status.ERROR) {
                return this;
            }
            boolean incomplete = truncated || budgetExhausted
                || errors > 0L
                || skippedUnloaded > 0L
                || (distinctSeen > 0L && returned > 0L && distinctSeen > returned);
            this.status = incomplete ? Status.PARTIAL : Status.OK;
            return this;
        }

        public Coverage build() {
            return new Coverage(this);
        }
    }
}
