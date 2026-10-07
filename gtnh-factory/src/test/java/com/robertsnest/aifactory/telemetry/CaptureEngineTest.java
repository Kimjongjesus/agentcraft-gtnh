package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.robertsnest.aifactory.telemetry.world.BlockAccess;

/**
 * The engine's scheduling and coverage arithmetic against a fake world. No
 * Forge. Regressions for H1 (per-tick budget, resumable machine walk), M1
 * (close stops work), M5 (scope filtering by dimension), M6/M10/M11
 * (unavailable is not empty).
 */
public class CaptureEngineTest {

    @Test
    public void expiredStockSliceDoesNotStartDesignAdapter() {
        FakeSources fake = new FakeSources();
        long[] clock = { 0 };
        int[] blockCalls = { 0 };
        fake.reader = (cap, budget) -> {
            clock[0] += 8_000_000L;
            return MeNetworkReader.StockRead.unavailable("synthetic");
        };
        CaptureSources sources = (CaptureSources) java.lang.reflect.Proxy.newProxyInstance(
            CaptureSources.class.getClassLoader(),
            new Class<?>[] { CaptureSources.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("blocks")) blockCalls[0]++;
                return method.invoke(fake, args);
            });
        CaptureEngine engine = new CaptureEngine(
            sources,
            new SourceSession("synthetic", 0, "test"),
            SCOPE,
            new CaptureEngine.Settings(),
            () -> clock[0]);
        assertFalse(engine.tick()); // empty machine phase, yield to stock
        assertFalse(engine.tick()); // stock uses the entire slice
        assertEquals("no new adapter after elapsed deadline", 0, blockCalls[0]);
        assertFalse(engine.tick()); // design resumes with a fresh slice
        assertEquals(1, blockCalls[0]);
        assertTrue(engine.tick());
        engine.close();
    }

    static final BaseScope SCOPE = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 0, "Overworld", 0, 64, 0, 16, 8, null);

    /** A machine tile at a position in a dimension. */
    static final class Tile {

        final int dim;
        final int x;
        final int y;
        final int z;
        final boolean machine;
        final boolean throwsOnRead;

        Tile(int dim, int x, int y, int z, boolean machine, boolean throwsOnRead) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.machine = machine;
            this.throwsOnRead = throwsOnRead;
        }
    }

    static final class FakeSources implements CaptureSources {

        long now = 100_000L;
        long revision = 1L;
        boolean gregTech = true;
        List<Tile> dim0 = new ArrayList<Tile>();
        boolean dim0Loaded = true;
        MeNetworkReader reader;
        int readMachineCalls;
        int surroundingsCalls;
        BlockAccess blocks;

        @Override
        public long worldRevision() {
            return revision;
        }

        @Override
        public String worldName() {
            return "fixture-world";
        }

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public TileList tiles(int dimensionId) {
            if (dimensionId != 0 || !dim0Loaded) {
                return null;
            }
            return new TileList() {

                @Override
                public int size() {
                    return dim0.size();
                }

                @Override
                public Object get(int index) {
                    return index < dim0.size() ? dim0.get(index) : null;
                }
            };
        }

        @Override
        public int[] tilePosition(Object tile) {
            Tile t = (Tile) tile;
            return new int[] { t.x, t.y, t.z };
        }

        @Override
        public MachineRead readMachine(Object tile) {
            readMachineCalls++;
            Tile t = (Tile) tile;
            if (t.throwsOnRead) {
                throw new IllegalStateException("boom");
            }
            if (!t.machine) {
                return null;
            }
            return new MachineRead(
                MultiblockStatus.builder("ebf", t.dim, t.x, t.y, t.z)
                    .formed(true)
                    .active(true)
                    .needsMaintenance(Boolean.FALSE)
                    .build(),
                0,
                0);
        }

        @Override
        public boolean gregTechAvailable() {
            return gregTech;
        }

        @Override
        public MeNetworkReader stockReader() {
            return reader;
        }

        @Override
        public BlockAccess blocks(int dimensionId) {
            return dimensionId == 0 ? blocks : null;
        }

        @Override
        public Surroundings surroundings(BaseScope scope, WorkBudget budget) {
            surroundingsCalls++;
            return new Surroundings(
                Long.valueOf(6000L),
                Long.valueOf(1L),
                Boolean.FALSE,
                Boolean.FALSE,
                "Plains",
                null,
                0,
                null,
                Integer.valueOf(0),
                Coverage.builder()
                    .status(Coverage.Status.OK)
                    .settle()
                    .build());
        }

        @Override
        public List<int[]> playerPositions(String playerName, int dimensionId) {
            return new ArrayList<int[]>();
        }
    }

    private static CaptureEngine.Settings settings() {
        CaptureEngine.Settings s = new CaptureEngine.Settings();
        s.intervalMillis = 30_000L;
        s.tickBudgetAttempts = 4L;
        s.tickBudgetMillis = 1000L;
        s.designEnabled = false;
        return s;
    }

    private static CaptureEngine engine(FakeSources sources, CaptureEngine.Settings settings) {
        return new CaptureEngine(
            sources,
            new SourceSession("s", 0L, "t"),
            SCOPE,
            settings,
            new WorkBudgetTest.StepClock(0L));
    }

    private static TelemetryCapture runToCapture(CaptureEngine e, FakeSources sources, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (e.tick()) {
                return e.latest();
            }
            sources.now += 50L;
        }
        return null;
    }

    @Test
    public void machineWalkSpansTicksAndRespectsThePerTickBudget() {
        FakeSources sources = new FakeSources();
        for (int i = 0; i < 20; i++) {
            sources.dim0.add(new Tile(0, i - 10, 64, 0, i % 4 == 0, false));
        }
        CaptureEngine e = engine(sources, settings());

        int ticks = 0;
        while (!e.tick()) {
            ticks++;
            assertTrue("bounded", ticks < 100);
            assertNull("nothing published mid-capture", e.latest());
        }
        TelemetryCapture c = e.latest();

        assertNotNull(c);
        assertEquals(
            5,
            c.machines()
                .size());
        assertTrue("20 tiles at 4 per tick needs at least 5 ticks: " + ticks, ticks >= 5);
        assertEquals(
            20L,
            c.machineCoverage()
                .attempted());
        assertTrue(
            c.machineCoverage()
                .complete());
        assertEquals(
            "all machines dimension-qualified",
            "dim0:ebf@-10,64,0",
            c.machines()
                .get(0)
                .machineId());
        assertEquals("fixture-world/" + SCOPE.identity(), c.baseId());
        assertEquals(1L, c.captureSequence());
    }

    @Test
    public void tilesOutsideTheScopeBoxAreNotRead() {
        FakeSources sources = new FakeSources();
        sources.dim0.add(new Tile(0, 0, 64, 0, true, false));
        sources.dim0.add(new Tile(0, 500, 64, 0, true, false));
        sources.dim0.add(new Tile(0, 0, 200, 0, true, false));
        TelemetryCapture c = runToCapture(engine(sources, settings()), sources, 50);

        assertEquals(
            1,
            c.machines()
                .size());
        assertEquals("out-of-box tiles cost no machine read", 1, sources.readMachineCalls);
    }

    @Test
    public void readFailuresAreCountedNotHidden() {
        FakeSources sources = new FakeSources();
        sources.dim0.add(new Tile(0, 0, 64, 0, true, false));
        sources.dim0.add(new Tile(0, 1, 64, 0, true, true));
        TelemetryCapture c = runToCapture(engine(sources, settings()), sources, 50);

        assertEquals(
            1,
            c.machines()
                .size());
        assertEquals(
            1L,
            c.machineCoverage()
                .errors());
        assertEquals(
            Coverage.Status.PARTIAL,
            c.machineCoverage()
                .status());
    }

    @Test
    public void noGregTechMeansUnavailableNotZeroMachines() {
        FakeSources sources = new FakeSources();
        sources.gregTech = false;
        sources.dim0.add(new Tile(0, 0, 64, 0, true, false));
        TelemetryCapture c = runToCapture(engine(sources, settings()), sources, 50);

        assertTrue(
            c.machines()
                .isEmpty());
        assertEquals(
            Coverage.Status.UNAVAILABLE,
            c.machineCoverage()
                .status());
        assertEquals(
            "gregtech_not_present",
            c.machineCoverage()
                .reason());
        assertEquals(0, sources.readMachineCalls);
    }

    @Test
    public void unloadedScopeDimensionIsUnavailableWithAWarning() {
        FakeSources sources = new FakeSources();
        sources.dim0Loaded = false;
        TelemetryCapture c = runToCapture(engine(sources, settings()), sources, 50);

        assertEquals(
            Coverage.Status.UNAVAILABLE,
            c.machineCoverage()
                .status());
        assertTrue(
            c.warnings()
                .contains("scope_dimension_not_loaded"));
    }

    @Test
    public void noAccessPointMeansStockUnavailable() {
        FakeSources sources = new FakeSources();
        TelemetryCapture c = runToCapture(engine(sources, settings()), sources, 50);

        assertEquals(
            Coverage.Status.UNAVAILABLE,
            c.stock()
                .coverage()
                .status());
        assertEquals(
            "no_access_point_configured",
            c.stock()
                .coverage()
                .reason());
        assertTrue(
            c.toFactorySnapshot()
                .truncated());
    }

    @Test
    public void aThrowingStockReaderIsAnErrorNotAnEmptyNetwork() {
        FakeSources sources = new FakeSources();
        sources.reader = new MeNetworkReader() {

            @Override
            public StockRead read(int cap, WorkBudget budget) {
                throw new IllegalStateException("network mid-teardown");
            }
        };
        TelemetryCapture c = runToCapture(engine(sources, settings()), sources, 50);

        assertEquals(
            Coverage.Status.ERROR,
            c.stock()
                .coverage()
                .status());
        assertTrue(
            c.stock()
                .stock()
                .isEmpty());
    }

    @Test
    public void captureIntervalPacesRepeatedCaptures() {
        FakeSources sources = new FakeSources();
        sources.dim0.add(new Tile(0, 0, 64, 0, true, false));
        CaptureEngine e = engine(sources, settings());
        TelemetryCapture first = runToCapture(e, sources, 50);
        assertNotNull(first);

        int ticksWithoutCapture = 0;
        for (int i = 0; i < 100; i++) {
            sources.now += 50L;
            if (e.tick()) {
                break;
            }
            ticksWithoutCapture++;
        }
        assertTrue("no new capture for a while: " + ticksWithoutCapture, ticksWithoutCapture >= 50);
        sources.now += 30_000L;
        TelemetryCapture second = runToCapture(e, sources, 50);
        assertNotNull(second);
        assertEquals(2L, second.captureSequence());
        assertTrue(second.capturedAtMillis() > first.capturedAtMillis());
    }

    @Test
    public void closeStopsWorkButKeepsTheLastCapture() {
        FakeSources sources = new FakeSources();
        sources.dim0.add(new Tile(0, 0, 64, 0, true, false));
        CaptureEngine e = engine(sources, settings());
        TelemetryCapture c = runToCapture(e, sources, 50);
        assertNotNull(c);
        int reads = sources.readMachineCalls;

        e.close();
        sources.now += 100_000L;
        for (int i = 0; i < 50; i++) {
            assertFalse(e.tick());
        }
        assertTrue(e.closed());
        assertEquals("no reads after close", reads, sources.readMachineCalls);
        assertEquals("last capture still readable", c, e.latest());
    }

    @Test
    public void undefinedScopePublishesAnHonestEmptyCapture() {
        FakeSources sources = new FakeSources();
        sources.dim0.add(new Tile(0, 0, 64, 0, true, false));
        CaptureEngine e = new CaptureEngine(
            sources,
            new SourceSession("s", 0L, "t"),
            BaseScope.none("unset"),
            settings(),
            new WorkBudgetTest.StepClock(0L));
        assertTrue(e.tick());
        TelemetryCapture c = e.latest();
        assertNotNull(c);
        assertFalse(
            c.scope()
                .defined());
        assertTrue(
            c.warnings()
                .contains("no_scope_defined"));
        assertEquals(
            Coverage.Status.UNAVAILABLE,
            c.machineCoverage()
                .status());
        assertEquals(0, sources.readMachineCalls);
    }

    @Test
    public void machineCapTruncatesAndSaysSo() {
        FakeSources sources = new FakeSources();
        for (int i = 0; i < 10; i++) {
            sources.dim0.add(new Tile(0, i, 64, 0, true, false));
        }
        CaptureEngine.Settings s = settings();
        s.maxMachines = 3;
        TelemetryCapture c = runToCapture(engine(sources, s), sources, 50);

        assertEquals(
            3,
            c.machines()
                .size());
        assertTrue(
            c.machineCoverage()
                .truncated());
        assertEquals(
            "machine_budget",
            c.machineCoverage()
                .reason());
    }

    @Test
    public void aTileListThatShrinksMidWalkDoesNotCrash() {
        final FakeSources sources = new FakeSources();
        for (int i = 0; i < 12; i++) {
            sources.dim0.add(new Tile(0, i, 64, 0, true, false));
        }
        CaptureEngine e = engine(sources, settings());
        assertFalse(e.tick());
        // Unload most tiles between ticks.
        sources.dim0 = new ArrayList<Tile>(sources.dim0.subList(0, 2));
        TelemetryCapture c = runToCapture(e, sources, 50);
        assertNotNull(c);
        assertTrue(
            c.machines()
                .size() <= 4);
    }

    @Test
    public void designPhaseRunsWhenEnabledAndReportsItsOwnCoverage() {
        FakeSources sources = new FakeSources();
        sources.blocks = new BlockAccess() {

            @Override
            public boolean isLoaded(int x, int y, int z) {
                return true;
            }

            @Override
            public Sample read(int x, int y, int z) {
                return new Sample("minecraft:stone#0", "Stone", false);
            }

            @Override
            public int blockLight(int x, int y, int z) {
                return 15;
            }
        };
        CaptureEngine.Settings s = settings();
        s.designEnabled = true;
        s.designStride = 4;
        s.tickBudgetAttempts = 64L;
        TelemetryCapture c = runToCapture(engine(sources, s), sources, 2000);

        assertNotNull(c);
        assertNotNull(c.design());
        assertEquals(
            4,
            c.design()
                .stride());
        assertTrue(
            c.designCoverage()
                .complete());
        assertEquals(
            1,
            c.design()
                .paletteReturned());
        assertTrue(c.captureTicks() > 1);
    }

    @Test
    public void tooManyTicksPublishesPartialInsteadOfLoopingForever() {
        FakeSources sources = new FakeSources();
        for (int i = 0; i < 1000; i++) {
            sources.dim0.add(new Tile(0, 0, 64, 0, false, false));
        }
        CaptureEngine.Settings s = settings();
        s.tickBudgetAttempts = 1L;
        s.maxCaptureTicks = 20;
        TelemetryCapture c = runToCapture(engine(sources, s), sources, 100);

        assertNotNull(c);
        assertTrue(
            c.warnings()
                .contains("capture_exceeded_max_ticks"));
        assertTrue(
            c.machineCoverage()
                .truncated());
        assertEquals(Arrays.asList("capture_exceeded_max_ticks"), c.warnings());
    }
}
