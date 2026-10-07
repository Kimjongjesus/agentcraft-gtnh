package com.robertsnest.aifactory.telemetry;

import java.util.List;

import com.robertsnest.aifactory.telemetry.world.BlockAccess;

/**
 * Everything the capture engine reads from the game, behind seams.
 *
 * <p>
 * The engine's scheduling, budgeting, deduplication and coverage arithmetic
 * are the parts that previously went untested because they were welded to
 * Forge types. With these seams the engine runs against fakes in unit tests
 * and against the real world in {@code ForgeCaptureSources}.
 *
 * <p>
 * All methods are called on the server thread.
 */
public interface CaptureSources {

    /** Index-addressable view of the loaded tile entities in one dimension. */
    interface TileList {

        int size();

        /** May return null if the list shrank since {@link #size()} was read. */
        Object get(int index);
    }

    /** Server identity and clock. */
    long worldRevision();

    String worldName();

    long nowMillis();

    /** The loaded tile list for a dimension, or null when it is not loaded. */
    TileList tiles(int dimensionId);

    /** Position of a tile candidate, or null when it is not a tile entity. */
    int[] tilePosition(Object tile);

    /**
     * Read a candidate as a GregTech multiblock.
     *
     * @return the read, or null when the candidate is not a multiblock
     *         controller; throws only for genuinely unexpected failures
     */
    MachineRead readMachine(Object tile);

    /** Outcome of one multiblock read. */
    final class MachineRead {

        public final MultiblockStatus status;
        public final int partsSkipped;
        public final int partErrors;

        public MachineRead(MultiblockStatus status, int partsSkipped, int partErrors) {
            this.status = status;
            this.partsSkipped = partsSkipped;
            this.partErrors = partErrors;
        }
    }

    /** True when GregTech is present in this runtime. */
    boolean gregTechAvailable();

    /** ME reader for the configured access point, or null when none configured. */
    MeNetworkReader stockReader();

    /** Block access for a dimension, or null when the dimension is not loaded. */
    BlockAccess blocks(int dimensionId);

    /** Environmental facts for the scope. */
    Surroundings surroundings(BaseScope scope, WorkBudget budget);

    /** Names of players in the scope box (for player-relative scopes), or empty. */
    List<int[]> playerPositions(String playerName, int dimensionId);
}
