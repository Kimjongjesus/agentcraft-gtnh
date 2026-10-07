package com.robertsnest.aifactory.telemetry.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.robertsnest.aifactory.telemetry.BaseDesignSurvey;
import com.robertsnest.aifactory.telemetry.BaseScope;
import com.robertsnest.aifactory.telemetry.Coverage;
import com.robertsnest.aifactory.telemetry.WorkBudget;
import com.robertsnest.aifactory.telemetry.WorkBudgetTest;

/**
 * Regressions for H1 (attempt budget covers unloaded positions; scan is
 * resumable across ticks), M6 (errors and unloaded counts propagate), M7
 * (stride/phase are reported and aliasing is real) and M8 (metadata is part
 * of identity). All against a fake world; no Forge.
 */
public class BaseDesignScannerTest {

    /** A synthetic world: a function from position to variant plus a loaded set. */
    static final class FakeWorld implements BlockAccess {

        interface Fn {

            String at(int x, int y, int z);
        }

        Fn fn;
        Set<String> unloadedChunks = new HashSet<String>();
        Set<String> readErrors = new HashSet<String>();
        Set<String> lightErrors = new HashSet<String>();
        int light = 15;
        int isLoadedCalls;
        int readCalls;

        FakeWorld(Fn fn) {
            this.fn = fn;
        }

        static String chunk(int x, int z) {
            return (x >> 4) + "," + (z >> 4);
        }

        @Override
        public boolean isLoaded(int x, int y, int z) {
            isLoadedCalls++;
            return !unloadedChunks.contains(chunk(x, z));
        }

        @Override
        public Sample read(int x, int y, int z) {
            readCalls++;
            if (readErrors.contains(x + "," + y + "," + z)) {
                return null;
            }
            String v = fn.at(x, y, z);
            if (v == null) {
                return new Sample("minecraft:air#0", "Air", true);
            }
            return new Sample(v, "name:" + v, false);
        }

        @Override
        public int blockLight(int x, int y, int z) {
            return lightErrors.contains(x + "," + y + "," + z) ? -1 : light;
        }
    }

    private static BaseScope scope(int radius, int height) {
        return new BaseScope(BaseScope.Kind.CONFIGURED, "t", 0, "Overworld", 0, 64, 0, radius, height, null);
    }

    private static WorkBudget unlimited() {
        return new WorkBudget(Long.MAX_VALUE, 1_000_000L, new WorkBudgetTest.StepClock(0L));
    }

    private static BaseDesignSurvey run(BaseDesignScanner scanner, WorkBudget budget) {
        while (!scanner.step(budget)) {
            budget = unlimited();
        }
        return scanner.result();
    }

    @Test
    public void unloadedPositionsCostAttemptsAndAreCountedNotSilentlySkipped() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return "minecraft:stone#0";
            }
        });
        // Everything unloaded: the old scanner would loop the whole lattice
        // with sampled==0 and never hit its ceiling.
        for (int cx = -8; cx <= 8; cx++) {
            for (int cz = -8; cz <= 8; cz++) {
                world.unloadedChunks.add(cx + "," + cz);
            }
        }
        BaseDesignScanner scanner = new BaseDesignScanner(world, scope(64, 32), 1, 0, 500L, 40);

        BaseDesignSurvey survey = run(scanner, unlimited());

        assertEquals("stopped at the attempt budget", 500L, survey.positionsAttempted());
        assertEquals(
            500L,
            survey.coverage()
                .skippedUnloaded());
        assertEquals(0L, survey.blocksSampled());
        assertTrue(
            survey.coverage()
                .budgetExhausted());
        assertEquals(
            Coverage.Status.PARTIAL,
            survey.coverage()
                .status());
        assertTrue("existence checks are bounded too", world.isLoadedCalls <= 500);
    }

    @Test
    public void scanResumesAcrossTicksAndCoversTheWholeLattice() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return (x + y + z) % 3 == 0 ? null : "minecraft:stone#0";
            }
        });
        BaseScope s = scope(5, 2);
        BaseDesignScanner scanner = new BaseDesignScanner(world, s, 1, 0, Long.MAX_VALUE, 40);
        long lattice = BaseDesignScanner.latticeSize(s, 1);
        int ticks = 0;
        while (!scanner.step(new WorkBudget(7L, 1000L, new WorkBudgetTest.StepClock(0L)))) {
            ticks++;
            assertTrue("no tick exceeds its attempt budget", scanner.attempted() <= 7L * (ticks + 1));
        }
        BaseDesignSurvey survey = scanner.result();

        assertEquals("every lattice position attempted exactly once", lattice, survey.positionsAttempted());
        assertEquals(lattice, world.readCalls);
        assertTrue("took several ticks: " + ticks, ticks > 10);
        assertTrue(
            survey.coverage()
                .complete());
        assertEquals(11L * 5L * 11L, lattice);
    }

    @Test
    public void metadataDistinguishesVariantsInThePalette() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return "minecraft:wool#" + (Math.abs(x) % 3);
            }
        });
        BaseDesignSurvey survey = run(new BaseDesignScanner(world, scope(4, 1), 1, 0, Long.MAX_VALUE, 40), unlimited());

        assertEquals("three wool colours are three palette entries", 3, survey.paletteReturned());
        assertEquals(3L, survey.paletteDistinctSeen());
        assertTrue(
            survey.palette()
                .get(0)
                .blockId()
                .startsWith("minecraft:wool#"));
    }

    @Test
    public void paletteCapReportsDistinctSeenAboveReturned() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return "mod:block#" + (Math.abs(x * 31 + z) % 50);
            }
        });
        BaseDesignSurvey survey = run(new BaseDesignScanner(world, scope(10, 0), 1, 0, Long.MAX_VALUE, 5), unlimited());

        assertEquals(5, survey.paletteReturned());
        assertTrue("saw more than it returned: " + survey.paletteDistinctSeen(), survey.paletteDistinctSeen() > 5L);
        assertTrue(
            survey.coverage()
                .truncated());
        assertEquals(
            Coverage.Status.PARTIAL,
            survey.coverage()
                .status());
    }

    @Test
    public void strideTwoAliasesAThinWallAndTheReportSaysSo() {
        // A one-block wall on x == 1: stride 2 phase 0 never samples x==1.
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return x == 1 ? "minecraft:obsidian#0" : null;
            }
        });
        BaseDesignSurvey phase0 = run(new BaseDesignScanner(world, scope(4, 1), 2, 0, Long.MAX_VALUE, 40), unlimited());
        BaseDesignSurvey phase1 = run(new BaseDesignScanner(world, scope(4, 1), 2, 1, Long.MAX_VALUE, 40), unlimited());

        assertEquals("phase 0 misses the wall entirely", 0L, phase0.solidBlocks());
        assertTrue("phase 1 sees it", phase1.solidBlocks() > 0L);
        assertEquals(2, phase0.stride());
        assertEquals(0, phase0.phaseX());
        assertEquals(1, phase1.phaseX());
    }

    @Test
    public void alternatingPatternCanReadAsOneMaterialUnderStride() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return ((x + z) & 1) == 0 ? "a:a#0" : "b:b#0";
            }
        });
        BaseDesignSurvey s = run(new BaseDesignScanner(world, scope(6, 0), 2, 0, Long.MAX_VALUE, 40), unlimited());
        assertEquals("stride 2 on a checkerboard samples one colour", 1, s.paletteReturned());
        BaseDesignSurvey census = run(new BaseDesignScanner(world, scope(6, 0), 1, 0, Long.MAX_VALUE, 40), unlimited());
        assertEquals(2, census.paletteReturned());
    }

    @Test
    public void failedBlockReadsAndFailedLightReadsAreErrorsNotData() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return x == 0 ? "minecraft:stone#0" : null;
            }
        });
        world.readErrors.add("0,64,0");
        world.lightErrors.add("1,64,0");
        world.light = 0;
        BaseDesignSurvey s = run(new BaseDesignScanner(world, scope(1, 0), 1, 0, Long.MAX_VALUE, 40), unlimited());

        assertEquals(
            2L,
            s.coverage()
                .errors());
        BaseDesignSurvey.ArtificialLightProfile light = s.lighting();
        // 3x1x3 = 9 positions; x==0 column is 3 stone (one errored -> 2 solid);
        // 6 air of which one light read failed -> 5 open.
        assertEquals(2L, s.solidBlocks());
        assertEquals("a failed light read is not counted as dark", 5L, light.openPositions());
        assertEquals(5L, light.unlitPositions());
        assertFalse(
            s.coverage()
                .complete());
    }

    @Test
    public void resultBeforeFinishIsRejected() {
        FakeWorld world = new FakeWorld(new FakeWorld.Fn() {

            @Override
            public String at(int x, int y, int z) {
                return null;
            }
        });
        BaseDesignScanner scanner = new BaseDesignScanner(world, scope(10, 10), 1, 0, Long.MAX_VALUE, 40);
        assertFalse(scanner.step(new WorkBudget(1L, 1000L, new WorkBudgetTest.StepClock(0L))));
        try {
            scanner.result();
            assertTrue(false);
        } catch (IllegalStateException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void requiresADefinedScope() {
        try {
            new BaseDesignScanner(new FakeWorld(null), BaseScope.none("x"), 1, 0, 10L, 40);
            assertTrue(false);
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
