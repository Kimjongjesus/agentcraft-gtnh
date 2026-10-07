package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One complete, immutable capture of everything the mod observed about a
 * scoped base.
 *
 * <p>
 * Built incrementally on the server thread across several ticks by the
 * capture engine, then published atomically. HTTP threads only ever hold a
 * reference to a finished capture and serialise it without touching game
 * state — that is what makes serving telemetry free for the tick.
 *
 * <p>
 * Every section carries its own {@link Coverage}. A section can be
 * unavailable (no GregTech, no configured ME access point), partial (budget
 * or cap) or failed; none of those is reported as empty-and-healthy.
 */
public final class TelemetryCapture {

    private final SourceSession session;
    private final String worldName;
    private final BaseScope scope;
    private final long worldRevision;
    private final long captureStartedAtMillis;
    private final long capturedAtMillis;
    private final int captureTicks;
    private final long captureSequence;
    private final List<MultiblockStatus> machines;
    private final Coverage machineCoverage;
    private final MeNetworkReader.StockRead stock;
    private final BaseDesignSurvey design;
    private final Coverage designCoverage;
    private final Surroundings surroundings;
    private final List<String> warnings;

    private TelemetryCapture(Builder b) {
        if (b.session == null) {
            throw new IllegalArgumentException("session is required");
        }
        if (b.scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        this.session = b.session;
        this.worldName = b.worldName == null ? "unknown" : b.worldName;
        this.scope = b.scope;
        this.worldRevision = Math.max(0L, b.worldRevision);
        this.captureStartedAtMillis = b.captureStartedAtMillis;
        this.capturedAtMillis = b.capturedAtMillis;
        this.captureTicks = b.captureTicks;
        this.captureSequence = b.captureSequence;
        this.machines = Collections.unmodifiableList(
            new ArrayList<MultiblockStatus>(
                b.machines == null ? Collections.<MultiblockStatus>emptyList() : b.machines));
        this.machineCoverage = b.machineCoverage == null ? Coverage.error("no coverage recorded") : b.machineCoverage;
        this.stock = b.stock == null ? MeNetworkReader.StockRead.unavailable("not_collected") : b.stock;
        this.design = b.design;
        this.designCoverage = b.designCoverage != null ? b.designCoverage
            : b.design != null ? b.design.coverage() : Coverage.unavailable("not_collected");
        this.surroundings = b.surroundings == null ? Surroundings.unavailable("not_collected") : b.surroundings;
        this.warnings = Collections
            .unmodifiableList(new ArrayList<String>(b.warnings == null ? Collections.<String>emptyList() : b.warnings));
    }

    public static Builder builder() {
        return new Builder();
    }

    public SourceSession session() {
        return session;
    }

    /** Save folder name; identity data, never a path. */
    public String worldName() {
        return worldName;
    }

    public BaseScope scope() {
        return scope;
    }

    /**
     * Stable identity of the observed base for history keying:
     * world name + scope identity.
     */
    public String baseId() {
        return worldName + "/" + scope.identity();
    }

    public long worldRevision() {
        return worldRevision;
    }

    public long captureStartedAtMillis() {
        return captureStartedAtMillis;
    }

    public long capturedAtMillis() {
        return capturedAtMillis;
    }

    /** Server ticks the incremental capture spanned. */
    public int captureTicks() {
        return captureTicks;
    }

    /** Monotonic per-session counter, so a consumer can detect skipped captures. */
    public long captureSequence() {
        return captureSequence;
    }

    public List<MultiblockStatus> machines() {
        return machines;
    }

    public Coverage machineCoverage() {
        return machineCoverage;
    }

    public MeNetworkReader.StockRead stock() {
        return stock;
    }

    /** Null when the survey is disabled or has not completed yet. */
    public BaseDesignSurvey design() {
        return design;
    }

    public Coverage designCoverage() {
        return designCoverage;
    }

    public Surroundings surroundings() {
        return surroundings;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Flat legacy form for the v1-shaped snapshot route. */
    public FactorySnapshot toFactorySnapshot() {
        List<FactorySnapshot.MachineStatus> flat = new ArrayList<FactorySnapshot.MachineStatus>();
        for (MultiblockStatus machine : machines) {
            flat.add(machine.toMachineStatus());
        }
        boolean truncated = !machineCoverage.complete() || !stock.coverage()
            .complete();
        return new FactorySnapshot(worldRevision, capturedAtMillis, stock.stock(), flat, truncated);
    }

    public static final class Builder {

        private SourceSession session;
        private String worldName;
        private BaseScope scope;
        private long worldRevision;
        private long captureStartedAtMillis;
        private long capturedAtMillis;
        private int captureTicks;
        private long captureSequence;
        private List<MultiblockStatus> machines;
        private Coverage machineCoverage;
        private MeNetworkReader.StockRead stock;
        private BaseDesignSurvey design;
        private Coverage designCoverage;
        private Surroundings surroundings;
        private List<String> warnings;

        public Builder session(SourceSession value) {
            this.session = value;
            return this;
        }

        public Builder worldName(String value) {
            this.worldName = value;
            return this;
        }

        public Builder scope(BaseScope value) {
            this.scope = value;
            return this;
        }

        public Builder worldRevision(long value) {
            this.worldRevision = value;
            return this;
        }

        public Builder captureStartedAtMillis(long value) {
            this.captureStartedAtMillis = value;
            return this;
        }

        public Builder capturedAtMillis(long value) {
            this.capturedAtMillis = value;
            return this;
        }

        public Builder captureTicks(int value) {
            this.captureTicks = value;
            return this;
        }

        public Builder captureSequence(long value) {
            this.captureSequence = value;
            return this;
        }

        public Builder machines(List<MultiblockStatus> value, Coverage coverage) {
            this.machines = value;
            this.machineCoverage = coverage;
            return this;
        }

        public Builder stock(MeNetworkReader.StockRead value) {
            this.stock = value;
            return this;
        }

        public Builder design(BaseDesignSurvey value, Coverage coverage) {
            this.design = value;
            this.designCoverage = coverage;
            return this;
        }

        public Builder surroundings(Surroundings value) {
            this.surroundings = value;
            return this;
        }

        public Builder warnings(List<String> value) {
            this.warnings = value;
            return this;
        }

        public TelemetryCapture build() {
            return new TelemetryCapture(this);
        }
    }
}
