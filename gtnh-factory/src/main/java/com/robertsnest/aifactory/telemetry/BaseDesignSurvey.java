package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What a region of the base is built out of and how it is lit.
 *
 * <p>
 * Everything here is a measurement with a stated sampling scheme, not a
 * judgement. Whether a palette is muddled is an interpretation the copilot
 * makes downstream and has to justify; this reports which block variants were
 * counted, how many positions were examined, which positions were skipped, and
 * how the sample was taken.
 *
 * <p>
 * <b>Two claims the previous version made and this one does not.</b> It
 * reported its returned palette size as the number of distinct block types in
 * the base, so a capped palette said the base used exactly forty kinds of
 * block. And it called low block-light "spawnable space", which ignores
 * skylight, time, weather, the supporting surface and headroom. Both are now
 * reported as what they actually are: a returned-versus-seen count, and an
 * artificial-light profile.
 */
public final class BaseDesignSurvey {

    private final BaseScope scope;
    private final int stride;
    private final int phaseX;
    private final int phaseY;
    private final int phaseZ;
    private final long positionsAttempted;
    private final long blocksSampled;
    private final long solidBlocks;
    private final List<BlockUsage> palette;
    private final ArtificialLightProfile lighting;
    private final Coverage coverage;

    public BaseDesignSurvey(BaseScope scope, int stride, int phaseX, int phaseY, int phaseZ, long positionsAttempted,
        long blocksSampled, long solidBlocks, List<BlockUsage> palette, ArtificialLightProfile lighting,
        Coverage coverage) {
        this.scope = scope == null ? BaseScope.none("none") : scope;
        this.stride = Math.max(1, stride);
        this.phaseX = phaseX;
        this.phaseY = phaseY;
        this.phaseZ = phaseZ;
        this.positionsAttempted = positionsAttempted;
        this.blocksSampled = blocksSampled;
        this.solidBlocks = solidBlocks;
        List<BlockUsage> copy = new ArrayList<BlockUsage>(
            palette == null ? Collections.<BlockUsage>emptyList() : palette);
        // Most-used first: a palette review is about what dominates, and a
        // capped list should keep the variants that define the look.
        Collections.sort(copy, USAGE_ORDER);
        this.palette = Collections.unmodifiableList(copy);
        this.lighting = lighting == null ? ArtificialLightProfile.empty() : lighting;
        this.coverage = coverage == null ? Coverage.error("no coverage recorded") : coverage;
    }

    private static final Comparator<BlockUsage> USAGE_ORDER = new Comparator<BlockUsage>() {

        @Override
        public int compare(BlockUsage a, BlockUsage b) {
            int byCount = Long.compare(b.count(), a.count());
            return byCount != 0 ? byCount
                : a.blockId()
                    .compareTo(b.blockId());
        }
    };

    public BaseScope scope() {
        return scope;
    }

    /** Sample every Nth block on each axis. 1 means every block was visited. */
    public int stride() {
        return stride;
    }

    /**
     * Where the stride lattice starts on each axis.
     *
     * <p>
     * Reported because it changes the answer. With stride 2, a one-block-thick
     * wall lying on an omitted coordinate plane is invisible, and an
     * alternating two-material pattern can be sampled entirely as one material.
     * Moving the anchor by one block changes the phase and therefore the
     * apparent palette without anything being built. A consumer that knows the
     * phase can say "coarse sample" instead of "your base is all stone".
     */
    public int phaseX() {
        return phaseX;
    }

    public int phaseY() {
        return phaseY;
    }

    public int phaseZ() {
        return phaseZ;
    }

    /** Positions the scan tried, including ones in unloaded chunks. */
    public long positionsAttempted() {
        return positionsAttempted;
    }

    /** Positions actually read. */
    public long blocksSampled() {
        return blocksSampled;
    }

    /** Non-air positions among those read. */
    public long solidBlocks() {
        return solidBlocks;
    }

    /** Block variants and their counts, most used first. May be capped. */
    public List<BlockUsage> palette() {
        return palette;
    }

    public ArtificialLightProfile lighting() {
        return lighting;
    }

    public Coverage coverage() {
        return coverage;
    }

    /** Palette entries in this payload. Not the number of variants that exist. */
    public int paletteReturned() {
        return palette.size();
    }

    /** Distinct variants observed, which may exceed {@link #paletteReturned()}. */
    public long paletteDistinctSeen() {
        return coverage.distinctSeen();
    }

    /** Fraction of read positions that were not air, 0..1. */
    public double density() {
        if (blocksSampled <= 0L) {
            return 0.0d;
        }
        return (double) solidBlocks / (double) blocksSampled;
    }

    /**
     * Share of sampled solid blocks that are the single most-used variant, 0..1.
     *
     * <p>
     * A sample statistic, not a census: near 1.0 says the sampled material is
     * overwhelmingly one thing, which is informative exactly as far as the
     * stride allows.
     */
    public double dominantShare() {
        if (palette.isEmpty() || solidBlocks <= 0L) {
            return 0.0d;
        }
        return (double) palette.get(0)
            .count() / (double) solidBlocks;
    }

    /** One block variant and how often it was sampled. */
    public static final class BlockUsage {

        private final String blockId;
        private final String displayName;
        private final long count;

        public BlockUsage(String blockId, String displayName, long count) {
            if (blockId == null || blockId.trim()
                .isEmpty()) {
                throw new IllegalArgumentException("block ID is required");
            }
            if (count < 0L) {
                throw new IllegalArgumentException("count cannot be negative");
            }
            this.blockId = blockId;
            this.displayName = displayName == null ? blockId : displayName;
            this.count = count;
        }

        /**
         * Registry name plus metadata, e.g. {@code minecraft:wool#11}.
         *
         * <p>
         * Metadata is part of the identity because 1.7.10 puts block variants
         * there: every wool colour, every stone type and most modded casing
         * variants share one registry name. Identifying by registry name alone
         * collapsed a deliberately multicoloured build into a single grey
         * entry, which is precisely the signal a design summary needs.
         */
        public String blockId() {
            return blockId;
        }

        public String displayName() {
            return displayName;
        }

        public long count() {
            return count;
        }
    }

    /**
     * Artificial (block-source) light measured across sampled open positions.
     *
     * <p>
     * <b>Read the name literally.</b> This is the saved block-light value and
     * nothing else. It is not a mob-spawn probability and not a count of
     * spawnable space: real spawning also depends on sky light, the time of
     * day, weather, a valid supporting surface, headroom and per-mob rules,
     * none of which are measured here. An open position under full daylight
     * with no torch nearby is counted "unlit" by this metric and is
     * nonetheless perfectly safe at noon.
     *
     * <p>
     * It remains a useful builder's signal — where the torches are not — which
     * is what it is reported as.
     */
    public static final class ArtificialLightProfile {

        /**
         * Block-light level below which 1.7.10 permits hostile spawning, given
         * every other spawn condition is also met. Reported as context for the
         * counts, not as a spawn verdict.
         */
        public static final int BLOCK_LIGHT_SPAWN_FLOOR = 8;

        private final long openPositions;
        private final long litPositions;
        private final long unlitPositions;
        private final int minLight;
        private final int maxLight;

        public static ArtificialLightProfile empty() {
            return new ArtificialLightProfile(0L, 0L, 0L, 0, 0);
        }

        public ArtificialLightProfile(long openPositions, long litPositions, long unlitPositions, int minLight,
            int maxLight) {
            this.openPositions = openPositions;
            this.litPositions = litPositions;
            this.unlitPositions = unlitPositions;
            this.minLight = minLight;
            this.maxLight = maxLight;
        }

        /** Open (air) positions whose light was read successfully. */
        public long openPositions() {
            return openPositions;
        }

        /** Open positions at or above the block-light spawn floor. */
        public long litPositions() {
            return litPositions;
        }

        /** Open positions below the block-light spawn floor. Not "spawnable". */
        public long unlitPositions() {
            return unlitPositions;
        }

        public int minLight() {
            return minLight;
        }

        public int maxLight() {
            return maxLight;
        }

        /** Fraction of measured open positions below the block-light floor, 0..1. */
        public double unlitFraction() {
            if (openPositions <= 0L) {
                return 0.0d;
            }
            return (double) unlitPositions / (double) openPositions;
        }
    }

    /** Builds a capped palette from raw counts, reporting how many were seen. */
    public static List<BlockUsage> paletteFrom(Map<String, Long> counts, Map<String, String> displayNames, int cap) {
        List<BlockUsage> out = new ArrayList<BlockUsage>();
        if (counts == null) {
            return out;
        }
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String id = entry.getKey();
            String name = displayNames == null ? null : displayNames.get(id);
            out.add(
                new BlockUsage(
                    id,
                    name,
                    entry.getValue()
                        .longValue()));
        }
        Collections.sort(out, USAGE_ORDER);
        if (cap > 0 && out.size() > cap) {
            return new ArrayList<BlockUsage>(out.subList(0, cap));
        }
        return out;
    }
}
