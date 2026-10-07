package com.robertsnest.aifactory.telemetry.world;

/**
 * The narrow slice of a Minecraft world the design survey reads.
 *
 * <p>
 * Exists so the scanner's sampling, budgeting and coverage arithmetic can be
 * tested against a fake world. The old scanner read {@code World} directly and
 * the review found that every property it claimed (budget, truncation,
 * palette identity) was untested for exactly that reason.
 *
 * <p>
 * Implementations are called on the server thread only.
 */
public interface BlockAccess {

    /** One sampled position. */
    final class Sample {

        /** Registry name plus metadata, e.g. {@code minecraft:wool#11}. */
        public final String variantId;
        public final String displayName;
        public final boolean air;

        public Sample(String variantId, String displayName, boolean air) {
            this.variantId = variantId;
            this.displayName = displayName;
            this.air = air;
        }
    }

    /** True when the chunk holding this position is loaded. Must never load it. */
    boolean isLoaded(int x, int y, int z);

    /**
     * Read a loaded position.
     *
     * @return the sample, or null when the block could not be read
     */
    Sample read(int x, int y, int z);

    /**
     * Saved block-light at a loaded position.
     *
     * @return 0..15, or -1 when the value could not be read
     */
    int blockLight(int x, int y, int z);
}
