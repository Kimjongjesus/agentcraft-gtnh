package com.robertsnest.aifactory.telemetry.world;

import java.util.HashMap;
import java.util.Map;

import com.robertsnest.aifactory.telemetry.BaseDesignSurvey;
import com.robertsnest.aifactory.telemetry.BaseScope;
import com.robertsnest.aifactory.telemetry.Coverage;
import com.robertsnest.aifactory.telemetry.WorkBudget;

/**
 * Samples a bounded region of the world, a slice per tick, to describe how a
 * base is built and lit.
 *
 * <p>
 * <b>What changed from the single-shot scanner and why.</b> The old scanner
 * finished its whole nested loop inside one server-thread callable, bounded
 * only by successfully-read positions. In an unloaded region the loop could
 * visit millions of candidates while the counter stayed near zero, and the
 * HTTP timeout did nothing to stop server-thread work already started. This
 * scanner is a resumable cursor: {@link #step(WorkBudget)} advances the
 * lattice until the budget says stop, then returns and the next tick calls it
 * again. Every candidate position — loaded or not — costs one attempt.
 *
 * <p>
 * Sampling is a stride lattice with a rotating phase: each full pass shifts
 * the lattice origin so successive surveys see different coordinate planes.
 * A single pass still aliases deliberately repetitive architecture, and the
 * survey says so: stride and phase are reported, and the palette is labelled a
 * sample, never a census.
 *
 * <p>
 * Server thread only.
 */
public final class BaseDesignScanner {

    /** Positions attempted across a whole survey before it stops as truncated. */
    public static final int DEFAULT_MAX_ATTEMPTS = 200_000;

    /** Sample every Nth block on each axis. */
    public static final int DEFAULT_STRIDE = 2;

    /** Distinct block variants kept in the reported palette. */
    public static final int DEFAULT_PALETTE_CAP = 40;

    /** Distinct variants tracked before new ones are counted but not named. */
    public static final int DEFAULT_DISTINCT_CAP = 4096;

    private final BlockAccess access;
    private final BaseScope scope;
    private final int stride;
    private final int phaseX;
    private final int phaseY;
    private final int phaseZ;
    private final long maxAttempts;
    private final int paletteCap;

    private final Map<String, Long> counts = new HashMap<String, Long>();
    private final Map<String, String> names = new HashMap<String, String>();
    private long distinctOverflow;

    private int x;
    private int y;
    private int z;
    private boolean started;
    private boolean finished;

    private long attempted;
    private long sampled;
    private long solid;
    private long errors;
    private long skippedUnloaded;
    private long open;
    private long lit;
    private long unlit;
    private long lightErrors;
    private int minLight = 15;
    private int maxLight = 0;
    private long elapsedMillis;
    private boolean budgetTruncated;

    public BaseDesignScanner(BlockAccess access, BaseScope scope, int stride, int phase, long maxAttempts,
        int paletteCap) {
        if (access == null) {
            throw new IllegalArgumentException("access is required");
        }
        if (scope == null || !scope.defined()) {
            throw new IllegalArgumentException("a defined scope is required");
        }
        this.access = access;
        this.scope = scope;
        this.stride = Math.max(1, stride);
        int p = Math.abs(phase) % this.stride;
        this.phaseX = p;
        this.phaseY = p;
        this.phaseZ = p;
        this.maxAttempts = maxAttempts < 1L ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        this.paletteCap = paletteCap < 1 ? DEFAULT_PALETTE_CAP : paletteCap;
    }

    public BaseScope scope() {
        return scope;
    }

    public boolean finished() {
        return finished;
    }

    /** Positions attempted so far, loaded or not. */
    public long attempted() {
        return attempted;
    }

    /**
     * Advance the scan until {@code budget} is exhausted or the lattice is
     * complete.
     *
     * @return true when the survey is finished and {@link #result()} is valid
     */
    public boolean step(WorkBudget budget) {
        if (finished) {
            return true;
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget is required");
        }
        if (!started) {
            started = true;
            x = scope.minX() + phaseX;
            y = scope.minY() + phaseY;
            z = scope.minZ() + phaseZ;
            if (x > scope.maxX() || y > scope.maxY() || z > scope.maxZ()) {
                finish();
                return true;
            }
        }
        while (true) {
            if (attempted >= maxAttempts) {
                budgetTruncated = true;
                finish();
                return true;
            }
            if (!budget.tryConsume()) {
                elapsedMillis += budget.elapsedMillis();
                return false;
            }
            attempted++;
            visit(x, y, z);
            if (!advance()) {
                elapsedMillis += budget.elapsedMillis();
                finish();
                return true;
            }
        }
    }

    private void visit(int px, int py, int pz) {
        if (!access.isLoaded(px, py, pz)) {
            skippedUnloaded++;
            return;
        }
        BlockAccess.Sample sample = access.read(px, py, pz);
        if (sample == null) {
            errors++;
            return;
        }
        sampled++;
        if (sample.air) {
            int light = access.blockLight(px, py, pz);
            if (light < 0) {
                // An unreadable light value is not darkness. The old code
                // counted it as light level 0, inventing dark corners.
                lightErrors++;
                return;
            }
            open++;
            if (light < BaseDesignSurvey.ArtificialLightProfile.BLOCK_LIGHT_SPAWN_FLOOR) {
                unlit++;
            } else {
                lit++;
            }
            if (light < minLight) {
                minLight = light;
            }
            if (light > maxLight) {
                maxLight = light;
            }
            return;
        }
        solid++;
        Long existing = counts.get(sample.variantId);
        if (existing != null) {
            counts.put(sample.variantId, Long.valueOf(existing.longValue() + 1L));
            return;
        }
        if (counts.size() >= DEFAULT_DISTINCT_CAP) {
            // Still counted as solid and as a distinct variant, but the map
            // stops growing: a griefed or extremely varied region must not
            // turn a bounded survey into an unbounded allocation.
            distinctOverflow++;
            return;
        }
        counts.put(sample.variantId, Long.valueOf(1L));
        names.put(sample.variantId, sample.displayName == null ? sample.variantId : sample.displayName);
    }

    /** Move the cursor. Returns false when the lattice is exhausted. */
    private boolean advance() {
        z += stride;
        if (z <= scope.maxZ()) {
            return true;
        }
        z = scope.minZ() + phaseZ;
        x += stride;
        if (x <= scope.maxX()) {
            return true;
        }
        x = scope.minX() + phaseX;
        y += stride;
        return y <= scope.maxY();
    }

    private void finish() {
        finished = true;
    }

    /**
     * The completed survey.
     *
     * @throws IllegalStateException before {@link #step} has returned true
     */
    public BaseDesignSurvey result() {
        if (!finished) {
            throw new IllegalStateException("survey not finished");
        }
        long distinct = counts.size() + distinctOverflow;
        Coverage coverage = Coverage.builder()
            .status(Coverage.Status.OK)
            .attempted(attempted)
            .succeeded(sampled)
            .errors(errors + lightErrors)
            .skippedUnloaded(skippedUnloaded)
            .returned(Math.min(distinct, paletteCap))
            .distinctSeen(distinct)
            .truncated(budgetTruncated || distinctOverflow > 0L || distinct > paletteCap)
            .budgetExhausted(budgetTruncated)
            .elapsedMillis(elapsedMillis)
            .reason(budgetTruncated ? "attempt_budget" : null)
            .settle()
            .build();
        BaseDesignSurvey.ArtificialLightProfile lighting = new BaseDesignSurvey.ArtificialLightProfile(
            open,
            lit,
            unlit,
            open == 0L ? 0 : minLight,
            maxLight);
        return new BaseDesignSurvey(
            scope,
            stride,
            phaseX,
            phaseY,
            phaseZ,
            attempted,
            sampled,
            solid,
            BaseDesignSurvey.paletteFrom(counts, names, paletteCap),
            lighting,
            coverage);
    }

    /** Candidate lattice positions in a scope for a stride, for budgeting. */
    public static long latticeSize(BaseScope scope, int stride) {
        int s = Math.max(1, stride);
        long nx = (scope.maxX() - scope.minX()) / s + 1;
        long ny = (scope.maxY() - scope.minY()) / s + 1;
        long nz = (scope.maxZ() - scope.minZ()) / s + 1;
        return nx * ny * nz;
    }
}
