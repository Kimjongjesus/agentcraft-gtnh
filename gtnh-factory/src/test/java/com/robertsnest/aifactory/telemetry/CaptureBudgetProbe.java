package com.robertsnest.aifactory.telemetry;

import java.lang.reflect.Proxy;

/** Explicit acceptance probe, separate from unit tests: never a live-world claim. */
public final class CaptureBudgetProbe {

    public static void main(String[] args) {
        CaptureEngineTest.FakeSources fake = new CaptureEngineTest.FakeSources();
        for (int i = 0; i < 500; i++)
            fake.dim0.add(new CaptureEngineTest.Tile(0, i % 25 - 12, 64, i / 25 - 10, true, false));
        long[] clock = { 0 };
        CaptureSources sources = (CaptureSources) Proxy.newProxyInstance(
            CaptureSources.class.getClassLoader(),
            new Class<?>[] { CaptureSources.class },
            (proxy, method, values) -> {
                if (method.getName()
                    .equals("readMachine")) clock[0] += 100000; // deterministic 0.1 ms per adapter read
                return method.invoke(fake, values);
            });
        CaptureEngine.Settings settings = new CaptureEngine.Settings();
        settings.designEnabled = false;
        CaptureEngine engine = new CaptureEngine(
            sources,
            new SourceSession("budget-fixture", 0, "test"),
            CaptureEngineTest.SCOPE,
            settings,
            () -> clock[0]);
        long max = 0;
        int ticks = 0;
        while (engine.latest() == null && ticks++ < 400) {
            long before = clock[0];
            engine.tick();
            max = Math.max(max, clock[0] - before);
        }
        System.out.println(
            "SYNTHETIC charged-cost probe: machines=" + engine.latest()
                .machines()
                .size()
                + " adapterCostNanos=100000 maxTickNanos="
                + max
                + " budgetMillis="
                + settings.tickBudgetMillis);
        engine.close();
        if (max > settings.tickBudgetMillis * 1000000L + 100000L)
            throw new AssertionError("Tick budget exceeded beyond one indivisible adapter read; release gate FAILED");
    }
}
