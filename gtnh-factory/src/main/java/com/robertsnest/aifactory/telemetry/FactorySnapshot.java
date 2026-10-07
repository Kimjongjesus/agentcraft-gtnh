package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable point-in-time view of factory state, assembled on the server
 * thread and handed to a reader thread.
 *
 * <p>
 * Carries {@code worldRevision} so a later action can state which snapshot it
 * was reasoning about. An oracle that decides "craft 64 circuits because stock
 * is 0" must not have that decision applied after someone already crafted them;
 * the revision is what makes that detectable.
 *
 * <p>
 * Snapshots are values: once built they never change, so a reader thread can
 * hold one safely while the game keeps ticking.
 */
public final class FactorySnapshot {

    private final long worldRevision;
    private final long capturedAtMillis;
    private final List<ItemStock> stock;
    private final List<MachineStatus> machines;
    private final boolean truncated;

    public FactorySnapshot(long worldRevision, long capturedAtMillis, List<ItemStock> stock,
        List<MachineStatus> machines, boolean truncated) {
        if (worldRevision < 0L) {
            throw new IllegalArgumentException("world revision cannot be negative");
        }
        this.worldRevision = worldRevision;
        this.capturedAtMillis = capturedAtMillis;
        this.stock = Collections
            .unmodifiableList(new ArrayList<ItemStock>(stock == null ? Collections.<ItemStock>emptyList() : stock));
        this.machines = Collections.unmodifiableList(
            new ArrayList<MachineStatus>(machines == null ? Collections.<MachineStatus>emptyList() : machines));
        this.truncated = truncated;
    }

    public long worldRevision() {
        return worldRevision;
    }

    public long capturedAtMillis() {
        return capturedAtMillis;
    }

    public List<ItemStock> stock() {
        return stock;
    }

    public List<MachineStatus> machines() {
        return machines;
    }

    /**
     * True when scan caps stopped this snapshot short of the full network.
     *
     * <p>
     * Reported rather than hidden: an oracle that believes it saw everything
     * will conclude an item has zero stock when it was simply past the cap, and
     * craft something that already exists.
     */
    public boolean truncated() {
        return truncated;
    }

    /** One item and its available quantity. */
    public static final class ItemStock {

        private final String itemId;
        private final String displayName;
        private final long quantity;
        private final boolean craftable;

        public ItemStock(String itemId, String displayName, long quantity, boolean craftable) {
            if (itemId == null || itemId.trim()
                .isEmpty()) {
                throw new IllegalArgumentException("item ID is required");
            }
            if (quantity < 0L) {
                throw new IllegalArgumentException("quantity cannot be negative");
            }
            this.itemId = itemId;
            this.displayName = displayName == null ? itemId : displayName;
            this.quantity = quantity;
            this.craftable = craftable;
        }

        public String itemId() {
            return itemId;
        }

        public String displayName() {
            return displayName;
        }

        public long quantity() {
            return quantity;
        }

        public boolean craftable() {
            return craftable;
        }
    }

    /** Status of one GregTech multiblock or machine. */
    public static final class MachineStatus {

        private final String machineId;
        private final String name;
        private final boolean active;
        private final String problem;

        public MachineStatus(String machineId, String name, boolean active, String problem) {
            if (machineId == null || machineId.trim()
                .isEmpty()) {
                throw new IllegalArgumentException("machine ID is required");
            }
            this.machineId = machineId;
            this.name = name == null ? machineId : name;
            this.active = active;
            this.problem = problem;
        }

        public String machineId() {
            return machineId;
        }

        public String name() {
            return name;
        }

        public boolean active() {
            return active;
        }

        /** Human-readable fault, or null when the machine is healthy. */
        public String problem() {
            return problem;
        }
    }
}
