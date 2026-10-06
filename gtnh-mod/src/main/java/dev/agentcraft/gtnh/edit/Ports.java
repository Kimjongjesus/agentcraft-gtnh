package dev.agentcraft.gtnh.edit;

import java.io.IOException;
import java.util.Map;

import dev.agentcraft.gtnh.hq.Anchor;

/**
 * The only ways the layout engine touches the game. The Forge side implements them over the HQ
 * world and {@code hq-anchors.json}; the headless checks implement them over a fake grid and record
 * every call. The engine checks a cell's kind before any call, and the Forge implementation checks
 * again (two independent gates: this mod's panel blocks only, never anything else).
 */
public final class Ports {

    private Ports() {}

    public interface World {

        int AIR = 0, PANEL = 1, OTHER = 2, UNLOADED = 3;

        /** AIR (no block, no tile entity), PANEL (a block registered in PanelTypes), OTHER, or UNLOADED. */
        int kind(Pos p);

        /** The panel at p (PANEL cells only), else null. */
        PanelSpec read(Pos p);

        /** "modid:name:meta" of whatever is at p, for the audit log. */
        String describe(Pos p);

        /** Put a panel at an AIR or PANEL cell (replacing that panel). */
        boolean place(Pos p, PanelSpec s);

        /** Change the binding / size / label / theme / facing of the same-type panel at a PANEL cell. */
        boolean update(Pos p, PanelSpec s);

        /** Remove the panel at a PANEL cell (no drops). */
        boolean remove(Pos p);
    }

    public interface Anchors {

        Map<String, Anchor> all();

        void put(Anchor a) throws IOException;

        void remove(String name) throws IOException;
    }

    /** Append-only audit sink: who, what, where, before/after block id:meta, detail. */
    public interface Audit {

        void record(String who, String action, String where, String before, String after, String detail);
    }

    /** Persistence of the layout, snapshots, lock and exports (atomic writes; see {@link FileStore}). */
    public interface Store {

        void saveLayout(String json) throws IOException;

        String loadLayout() throws IOException;

        void saveSnapshot(String name, String json) throws IOException;

        String loadSnapshot(String name) throws IOException;

        java.util.List<String> snapshots();

        void saveLock(String json) throws IOException;

        String loadLock() throws IOException;
    }
}
