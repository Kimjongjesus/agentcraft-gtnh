package com.robertsnest.aifactory.telemetry;

/**
 * One component of a GregTech multiblock, with where it sits in the world.
 *
 * <p>
 * Position matters to the oracle: "the Electric Blast Furnace is missing an
 * output hatch" is far less useful than being able to say which face of which
 * machine, and an operator debugging a broken structure needs coordinates, not
 * just a count.
 */
public final class MultiblockPart {

    /**
     * Component classes worth distinguishing.
     *
     * <p>
     * ME variants are called out separately from their plain counterparts
     * because they behave differently in a way that changes advice: an ME input
     * hatch pulls from the AE network, so "add more material to the hatch" is
     * wrong for it — the answer is to check the network or the channel.
     */
    public enum PartType {
        ENERGY_HATCH,
        DYNAMO_HATCH,
        INPUT_HATCH,
        OUTPUT_HATCH,
        INPUT_BUS,
        OUTPUT_BUS,
        /** Input hatch fed from an ME network. */
        ME_INPUT_HATCH,
        /** Input bus fed from an ME network. */
        ME_INPUT_BUS,
        MAINTENANCE_HATCH,
        MUFFLER_HATCH,
        /** A recognised hatch list this build exposes but we do not classify. */
        OTHER
    }

    private final PartType type;
    private final String name;
    private final int x;
    private final int y;
    private final int z;
    private final int tier;
    private final Boolean meChannelActive;

    public MultiblockPart(PartType type, String name, int x, int y, int z, int tier, Boolean meChannelActive) {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        this.type = type;
        this.name = name == null ? type.name() : name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tier = tier;
        this.meChannelActive = meChannelActive;
    }

    public PartType type() {
        return type;
    }

    public String name() {
        return name;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** GregTech voltage tier (0 = ULV, 1 = LV, ...), or -1 when not applicable. */
    public int tier() {
        return tier;
    }

    /**
     * For ME-backed parts: whether the AE channel is powered and active.
     *
     * <p>
     * Null for parts that are not ME-backed. Null is not "false": a plain
     * hatch has no channel to be unhappy about, and reporting it as inactive
     * would invent a fault.
     */
    public Boolean meChannelActive() {
        return meChannelActive;
    }

    /** True when this part is fed from an ME network rather than by hand. */
    public boolean isMeBacked() {
        return type == PartType.ME_INPUT_HATCH || type == PartType.ME_INPUT_BUS;
    }

    @Override
    public String toString() {
        return type + "{" + name + " @" + x + "," + y + "," + z + (tier >= 0 ? " tier=" + tier : "") + "}";
    }
}
