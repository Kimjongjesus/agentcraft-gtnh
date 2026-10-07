package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.*;

import org.junit.Test;

public class CaptureScaleTest {

    @Test
    public void defaultCapsCapture200And500DistinctMachinesWithoutTruncation() {
        for (int count : new int[] { 200, 500 }) {
            CaptureEngineTest.FakeSources sources = new CaptureEngineTest.FakeSources();
            for (int i = 0; i < count; i++)
                sources.dim0.add(new CaptureEngineTest.Tile(0, i % 25 - 12, 64, i / 25 - 10, true, false));
            CaptureEngine.Settings settings = new CaptureEngine.Settings();
            CaptureEngine engine = new CaptureEngine(
                sources,
                new SourceSession("scale-fixture", 0, "test"),
                CaptureEngineTest.SCOPE,
                settings,
                null);
            int ticks = 0;
            long maxNanos = 0;
            while (engine.latest() == null && ticks++ < settings.maxCaptureTicks) {
                long before = System.nanoTime();
                engine.tick();
                maxNanos = Math.max(maxNanos, System.nanoTime() - before);
            }
            assertNotNull(engine.latest());
            assertEquals(
                count,
                engine.latest()
                    .machines()
                    .size());
            assertTrue(
                engine.latest()
                    .machineCoverage()
                    .complete());
            System.out.println(
                "SYNTHETIC cheap-adapter fixture: machines=" + count
                    + " ticks="
                    + ticks
                    + " maxTickNanos="
                    + maxNanos
                    + " configuredBudgetMillis="
                    + settings.tickBudgetMillis);
            engine.close();
        }
    }
}
