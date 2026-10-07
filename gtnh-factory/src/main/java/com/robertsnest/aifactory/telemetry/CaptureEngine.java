package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.robertsnest.aifactory.telemetry.world.BaseDesignScanner;
import com.robertsnest.aifactory.telemetry.world.BlockAccess;

/**
 * Builds telemetry captures incrementally, a bounded slice per server tick,
 * and publishes each finished capture as an immutable value.
 *
 * <p>
 * <b>This is the H1 fix.</b> Nothing HTTP-facing ever schedules world reads.
 * The engine is ticked by the server; each tick it spends at most one
 * {@link WorkBudget} (attempts and milliseconds) advancing a state machine:
 *
 * <pre>
 * IDLE -&gt; MACHINES -&gt; STOCK -&gt; DESIGN -&gt; SURROUNDINGS -&gt; publish -&gt; IDLE
 * </pre>
 *
 * A capture that takes forty ticks to assemble costs a fraction of each of
 * those ticks and none of any HTTP request. Serving telemetry is reading a
 * reference. Captures are paced by a minimum interval so an idle server does
 * nothing between them.
 *
 * <p>
 * The machine phase walks the tile list by index across ticks. The list can
 * change between ticks, so the walk is a bounded scan of what is loaded, and
 * a machine seen twice is deduplicated by ID; a machine that unloads mid-walk
 * is simply absent, and the coverage records the walk as partial if the list
 * shrank under it.
 *
 * <p>
 * Server thread only, except {@link #latest()} which any thread may call.
 */
public final class CaptureEngine {

    /** Default minimum spacing between captures. */
    public static final long DEFAULT_INTERVAL_MILLIS = 30_000L;

    /** Machines described per capture before the walk stops as truncated. */
    public static final int DEFAULT_MAX_MACHINES = 512;

    /** Tile candidates examined per capture across all ticks. */
    public static final long DEFAULT_MAX_TILE_ATTEMPTS = 200_000L;

    /** Ticks a single capture may span before it is abandoned as stale. */
    public static final int DEFAULT_MAX_CAPTURE_TICKS = 400;

    private enum Phase {
        IDLE,
        MACHINES,
        STOCK,
        DESIGN,
        SURROUNDINGS
    }

    /** Operator settings for one engine. */
    public static final class Settings {

        public long intervalMillis = DEFAULT_INTERVAL_MILLIS;
        public long tickBudgetMillis = WorkBudget.DEFAULT_BUDGET_MILLIS;
        public long tickBudgetAttempts = 4096L;
        public int maxMachines = DEFAULT_MAX_MACHINES;
        public long maxTileAttempts = DEFAULT_MAX_TILE_ATTEMPTS;
        public int stockCap = 500;
        public long stockMaxAttempts = 20_000L;
        public boolean designEnabled = true;
        public int designStride = BaseDesignScanner.DEFAULT_STRIDE;
        public long designMaxAttempts = BaseDesignScanner.DEFAULT_MAX_ATTEMPTS;
        public int designPaletteCap = BaseDesignScanner.DEFAULT_PALETTE_CAP;
        public int maxCaptureTicks = DEFAULT_MAX_CAPTURE_TICKS;
    }

    /** Supplies the scope for each new capture; player-relative scopes move. */
    public interface ScopeSource {

        BaseScope current();
    }

    private final CaptureSources sources;
    private final SourceSession session;
    private final Settings settings;
    private final ScopeSource scopeSource;
    private final WorkBudget.Clock clock;
    private volatile BaseScope scope;

    private final AtomicReference<TelemetryCapture> latest = new AtomicReference<TelemetryCapture>();
    private volatile long lastTickMillis;
    private volatile boolean closed;

    private Phase phase = Phase.IDLE;
    private long lastPublishedMillis;
    private long sequence;
    private int designPhase;

    // Per-capture working state.
    private long captureStartMillis;
    private int captureTicks;
    private int tileIndex;
    private int tileSizeAtStart;
    private long tileAttempts;
    private long machineErrors;
    private int partsSkipped;
    private int partErrors;
    private boolean machinesTruncated;
    private java.util.LinkedHashMap<String, MultiblockStatus> machines;
    private MeNetworkReader.StockRead stock;
    private BaseDesignScanner scanner;
    private BaseDesignSurvey design;
    private Coverage designCoverage;
    private Surroundings surroundings;
    private List<String> warnings;

    public CaptureEngine(CaptureSources sources, SourceSession session, final BaseScope scope, Settings settings,
        WorkBudget.Clock clock) {
        this(sources, session, new ScopeSource() {

            @Override
            public BaseScope current() {
                return scope;
            }
        }, settings, clock);
    }

    public CaptureEngine(CaptureSources sources, SourceSession session, ScopeSource scopeSource, Settings settings,
        WorkBudget.Clock clock) {
        if (sources == null || session == null || scopeSource == null) {
            throw new IllegalArgumentException("sources, session and scope are required");
        }
        this.sources = sources;
        this.session = session;
        this.scopeSource = scopeSource;
        BaseScope initial = scopeSource.current();
        this.scope = initial == null ? BaseScope.none("none") : initial;
        this.settings = settings == null ? new Settings() : settings;
        this.clock = clock == null ? new WorkBudget.Clock() {

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }
        } : clock;
    }

    public BaseScope scope() {
        return scope;
    }

    public SourceSession session() {
        return session;
    }

    /** The most recent finished capture, or null before the first completes. */
    public TelemetryCapture latest() {
        return latest.get();
    }

    /** Wall-clock of the last tick this engine saw; a freshness signal for health. */
    public long lastTickMillis() {
        return lastTickMillis;
    }

    public boolean closed() {
        return closed;
    }

    /** Stop doing work. Idempotent. The last capture stays readable. */
    public void close() {
        closed = true;
        phase = Phase.IDLE;
        scanner = null;
        machines = null;
    }

    /**
     * Do one tick's worth of work. Server thread only.
     *
     * @return true when a capture was published this tick
     */
    public boolean tick() {
        if (closed) {
            return false;
        }
        long now = sources.nowMillis();
        lastTickMillis = now;
        if (phase == Phase.IDLE) {
            if (now - lastPublishedMillis < settings.intervalMillis && latest.get() != null) {
                return false;
            }
            BaseScope next = scopeSource.current();
            scope = next == null ? BaseScope.none("none") : next;
            begin(now);
            if (!scope.defined()) {
                // Nothing is in scope: publish an honest empty capture so a
                // consumer sees "no base configured" rather than silence.
                warnings.add("no_scope_defined");
                publish(now);
                return true;
            }
        }
        captureTicks++;
        if (captureTicks > settings.maxCaptureTicks) {
            // Something is far too slow; publish what we have as partial and
            // start again next interval rather than looping forever.
            warnings.add("capture_exceeded_max_ticks");
            machinesTruncated = true;
            publish(now);
            return true;
        }
        WorkBudget budget = new WorkBudget(settings.tickBudgetAttempts, settings.tickBudgetMillis, clock);
        while (!budget.deadlineReached() && phase != Phase.IDLE) {
            switch (phase) {
                case MACHINES:
                    if (stepMachines(budget)) {
                        phase = Phase.STOCK;
                    }
                    break;
                case STOCK:
                    stepStock();
                    phase = Phase.DESIGN;
                    break;
                case DESIGN:
                    if (stepDesign(budget)) {
                        phase = Phase.SURROUNDINGS;
                    }
                    break;
                case SURROUNDINGS:
                    stepSurroundings(budget);
                    publish(sources.nowMillis());
                    return true;
                default:
                    return false;
            }
            if (phase == Phase.STOCK || phase == Phase.SURROUNDINGS) {
                // These phases are single-shot and each costs a real read;
                // give them their own tick so one tick never stacks two.
                return false;
            }
        }
        return false;
    }

    private void begin(long now) {
        phase = Phase.MACHINES;
        captureStartMillis = now;
        captureTicks = 0;
        tileIndex = 0;
        tileSizeAtStart = -1;
        tileAttempts = 0L;
        machineErrors = 0L;
        partsSkipped = 0;
        partErrors = 0;
        machinesTruncated = false;
        machines = new java.util.LinkedHashMap<String, MultiblockStatus>();
        stock = null;
        design = null;
        designCoverage = null;
        surroundings = null;
        warnings = new ArrayList<String>();
        scanner = null;
    }

    /** @return true when the machine walk is finished */
    private boolean stepMachines(WorkBudget budget) {
        if (!sources.gregTechAvailable()) {
            return true;
        }
        CaptureSources.TileList tiles = sources.tiles(scope.dimensionId());
        if (tiles == null) {
            warnings.add("scope_dimension_not_loaded");
            return true;
        }
        int size = tiles.size();
        if (tileSizeAtStart < 0) {
            tileSizeAtStart = size;
        }
        while (tileIndex < size) {
            if (tileAttempts >= settings.maxTileAttempts) {
                machinesTruncated = true;
                return true;
            }
            if (!budget.tryConsume()) {
                return false;
            }
            tileAttempts++;
            Object candidate = tiles.get(tileIndex++);
            if (candidate == null) {
                continue;
            }
            int[] pos;
            try {
                pos = sources.tilePosition(candidate);
            } catch (Throwable t) {
                machineErrors++;
                continue;
            }
            if (pos == null || !scope.contains(scope.dimensionId(), pos[0], pos[1], pos[2])) {
                continue;
            }
            CaptureSources.MachineRead read;
            try {
                read = sources.readMachine(candidate);
            } catch (Throwable t) {
                machineErrors++;
                continue;
            }
            if (read == null || read.status == null) {
                continue;
            }
            partsSkipped += read.partsSkipped;
            partErrors += read.partErrors;
            if (machines.size() >= settings.maxMachines && !machines.containsKey(read.status.machineId())) {
                machinesTruncated = true;
                return true;
            }
            machines.put(read.status.machineId(), read.status);
            // Re-read the size each iteration: the list may have shrunk.
            size = tiles.size();
        }
        return true;
    }

    private void stepStock() {
        MeNetworkReader reader = sources.stockReader();
        if (reader == null) {
            stock = MeNetworkReader.StockRead.unavailable("no_access_point_configured");
            return;
        }
        WorkBudget stockBudget = new WorkBudget(settings.stockMaxAttempts, settings.tickBudgetMillis, clock);
        try {
            stock = reader.read(settings.stockCap, stockBudget);
        } catch (NoClassDefFoundError e) {
            stock = MeNetworkReader.StockRead.unavailable("ae2_not_present");
        } catch (Throwable t) {
            stock = MeNetworkReader.StockRead.error("stock_read_failed");
        }
        if (stock == null) {
            stock = MeNetworkReader.StockRead.error("stock_read_null");
        }
    }

    /** @return true when the design phase is finished */
    private boolean stepDesign(WorkBudget budget) {
        if (!settings.designEnabled) {
            designCoverage = Coverage.unavailable("design_survey_disabled");
            return true;
        }
        if (scanner == null) {
            BlockAccess access = sources.blocks(scope.dimensionId());
            if (access == null) {
                designCoverage = Coverage.unavailable("scope_dimension_not_loaded");
                return true;
            }
            scanner = new BaseDesignScanner(
                access,
                scope,
                settings.designStride,
                designPhase,
                settings.designMaxAttempts,
                settings.designPaletteCap);
            designPhase = (designPhase + 1) % Math.max(1, settings.designStride);
        }
        boolean done;
        try {
            done = scanner.step(budget);
        } catch (Throwable t) {
            designCoverage = Coverage.error("design_scan_failed");
            scanner = null;
            return true;
        }
        if (!done) {
            return false;
        }
        design = scanner.result();
        designCoverage = design.coverage();
        scanner = null;
        return true;
    }

    private void stepSurroundings(WorkBudget budget) {
        try {
            surroundings = sources.surroundings(scope, budget);
        } catch (Throwable t) {
            surroundings = Surroundings.unavailable("surroundings_failed");
        }
        if (surroundings == null) {
            surroundings = Surroundings.unavailable("surroundings_null");
        }
    }

    private void publish(long now) {
        Coverage.Builder mc = Coverage.builder()
            .attempted(tileAttempts)
            .succeeded(machines.size())
            .errors(machineErrors + partErrors)
            .returned(machines.size())
            .distinctSeen(machines.size())
            .truncated(machinesTruncated || partsSkipped > 0)
            .budgetExhausted(machinesTruncated)
            .elapsedMillis(now - captureStartMillis);
        if (!scope.defined()) {
            mc.status(Coverage.Status.UNAVAILABLE)
                .reason("no_scope_defined");
        } else if (!sources.gregTechAvailable()) {
            mc.status(Coverage.Status.UNAVAILABLE)
                .reason("gregtech_not_present");
        } else if (sources.tiles(scope.dimensionId()) == null && machines.isEmpty()) {
            mc.status(Coverage.Status.UNAVAILABLE)
                .reason("scope_dimension_not_loaded");
        } else {
            mc.status(Coverage.Status.OK)
                .reason(machinesTruncated ? "machine_budget" : partsSkipped > 0 ? "parts_capped" : null)
                .settle();
        }
        sequence++;
        TelemetryCapture capture = TelemetryCapture.builder()
            .session(session)
            .worldName(sources.worldName())
            .scope(scope)
            .worldRevision(sources.worldRevision())
            .captureStartedAtMillis(captureStartMillis)
            .capturedAtMillis(now)
            .captureTicks(captureTicks)
            .captureSequence(sequence)
            .machines(new ArrayList<MultiblockStatus>(machines.values()), mc.build())
            .stock(stock)
            .design(design, designCoverage)
            .surroundings(surroundings)
            .warnings(warnings)
            .build();
        latest.set(capture);
        lastPublishedMillis = now;
        phase = Phase.IDLE;
        machines = null;
        scanner = null;
    }
}
