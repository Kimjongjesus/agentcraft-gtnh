package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A GregTech multiblock: its run state, its faults, and the parts it is built
 * from.
 *
 * <p>
 * Structure faults are reported as data rather than prose so the oracle can
 * act on them: "formed=false" plus an empty energy-hatch list is a diagnosis,
 * whereas a rendered sentence is only a description.
 *
 * <p>
 * <b>Identity is dimension-qualified.</b> {@link #machineId()} is
 * {@code dim<id>:<typeKey>@x,y,z}. Two identically named machines at identical
 * coordinates in different dimensions are different machines; the previous
 * release merged them. {@code typeKey} is the GregTech meta-name (a stable,
 * untranslated identifier), not the localised display text.
 *
 * <p>
 * <b>Unknown is not healthy.</b> {@link #needsMaintenance()} is a tri-state;
 * null means the maintenance query failed, which the old code reported as
 * "no maintenance needed". Energy figures are nullable for the same reason.
 */
public final class MultiblockStatus {

    private final String machineId;
    private final String typeKey;
    private final String name;
    private final int dimensionId;
    private final int x;
    private final int y;
    private final int z;
    private final boolean formed;
    private final boolean active;
    private final Boolean needsMaintenance;
    private final List<String> maintenanceIssues;
    private final long euPerTick;
    private final int progressTicks;
    private final int maxProgressTicks;
    private final Long euStored;
    private final Long euCapacity;
    private final Integer efficiencyPercent;
    private final List<MultiblockPart> parts;

    private MultiblockStatus(Builder b) {
        if (b.typeKey == null || b.typeKey.trim()
            .isEmpty()) {
            throw new IllegalArgumentException("type key is required");
        }
        this.typeKey = b.typeKey;
        this.dimensionId = b.dimensionId;
        this.x = b.x;
        this.y = b.y;
        this.z = b.z;
        this.machineId = machineId(b.dimensionId, b.typeKey, b.x, b.y, b.z);
        this.name = b.name == null ? b.typeKey : b.name;
        this.formed = b.formed;
        this.active = b.active;
        this.needsMaintenance = b.needsMaintenance;
        this.maintenanceIssues = Collections.unmodifiableList(
            new ArrayList<String>(b.maintenanceIssues == null ? Collections.<String>emptyList() : b.maintenanceIssues));
        this.euPerTick = b.euPerTick;
        this.progressTicks = b.progressTicks;
        this.maxProgressTicks = b.maxProgressTicks;
        this.euStored = b.euStored;
        this.euCapacity = b.euCapacity;
        this.efficiencyPercent = b.efficiencyPercent;
        this.parts = Collections.unmodifiableList(
            new ArrayList<MultiblockPart>(b.parts == null ? Collections.<MultiblockPart>emptyList() : b.parts));
    }

    /** The canonical dimension-qualified identity string. */
    public static String machineId(int dimensionId, String typeKey, int x, int y, int z) {
        return "dim" + dimensionId + ":" + typeKey + "@" + x + "," + y + "," + z;
    }

    public static Builder builder(String typeKey, int dimensionId, int x, int y, int z) {
        return new Builder(typeKey, dimensionId, x, y, z);
    }

    public String machineId() {
        return machineId;
    }

    /** Stable untranslated type identifier (GregTech meta-name). */
    public String typeKey() {
        return typeKey;
    }

    public String name() {
        return name;
    }

    public int dimensionId() {
        return dimensionId;
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

    /** True when the structure check passed; false means it is not assembled. */
    public boolean formed() {
        return formed;
    }

    /** True when the machine is currently running a recipe. */
    public boolean active() {
        return active;
    }

    /** True/false when measured; null when the maintenance query failed. */
    public Boolean needsMaintenance() {
        return needsMaintenance;
    }

    /** Named maintenance problems (e.g. "wrench"), empty when none or unknown. */
    public List<String> maintenanceIssues() {
        return maintenanceIssues;
    }

    /** Negative while consuming power, positive while generating. */
    public long euPerTick() {
        return euPerTick;
    }

    public int progressTicks() {
        return progressTicks;
    }

    public int maxProgressTicks() {
        return maxProgressTicks;
    }

    /** Energy buffered in the controller, or null when not measured. */
    public Long euStored() {
        return euStored;
    }

    public Long euCapacity() {
        return euCapacity;
    }

    /** Recipe efficiency 0..100, or null when not measured. */
    public Integer efficiencyPercent() {
        return efficiencyPercent;
    }

    public List<MultiblockPart> parts() {
        return parts;
    }

    /** True when at least one part of this type is present. */
    public boolean hasPart(MultiblockPart.PartType type) {
        for (MultiblockPart part : parts) {
            if (part.type() == type) {
                return true;
            }
        }
        return false;
    }

    /** Parts of one type, in discovery order. */
    public List<MultiblockPart> partsOfType(MultiblockPart.PartType type) {
        List<MultiblockPart> out = new ArrayList<MultiblockPart>();
        for (MultiblockPart part : parts) {
            if (part.type() == type) {
                out.add(part);
            }
        }
        return out;
    }

    /** Count per part type, for a compact summary. */
    public Map<MultiblockPart.PartType, Integer> partCounts() {
        Map<MultiblockPart.PartType, Integer> counts = new EnumMap<MultiblockPart.PartType, Integer>(
            MultiblockPart.PartType.class);
        for (MultiblockPart part : parts) {
            Integer existing = counts.get(part.type());
            counts.put(part.type(), existing == null ? 1 : existing + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * An ME-backed part whose AE channel is down, or null.
     *
     * <p>
     * Worth surfacing on its own: a formed, powered multiblock that is idle
     * because its ME input hatch lost its channel looks like a GregTech problem
     * and is actually an AE2 one.
     */
    public MultiblockPart firstMeChannelFault() {
        for (MultiblockPart part : parts) {
            if (part.isMeBacked() && Boolean.FALSE.equals(part.meChannelActive())) {
                return part;
            }
        }
        return null;
    }

    /** Progress as 0..1, or 0 when idle. */
    public double progressFraction() {
        if (maxProgressTicks <= 0) {
            return 0.0d;
        }
        double fraction = (double) progressTicks / (double) maxProgressTicks;
        return fraction < 0.0d ? 0.0d : Math.min(fraction, 1.0d);
    }

    /** Converts to the flat form carried in a {@link FactorySnapshot}. */
    public FactorySnapshot.MachineStatus toMachineStatus() {
        return new FactorySnapshot.MachineStatus(machineId, name, active, describeProblem());
    }

    /** The most actionable fault, or null when healthy or unknown. */
    public String describeProblem() {
        if (!formed) {
            return "structure not formed";
        }
        if (Boolean.TRUE.equals(needsMaintenance)) {
            return maintenanceIssues.isEmpty() ? "needs maintenance" : "needs maintenance: " + join(maintenanceIssues);
        }
        if (!hasPart(MultiblockPart.PartType.ENERGY_HATCH) && !hasPart(MultiblockPart.PartType.DYNAMO_HATCH)) {
            return "no energy or dynamo hatch";
        }
        MultiblockPart meFault = firstMeChannelFault();
        if (meFault != null) {
            return "ME channel inactive at " + meFault.x() + "," + meFault.y() + "," + meFault.z();
        }
        if (needsMaintenance == null) {
            return "maintenance state unknown";
        }
        return null;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** Assembles a status; every field not set stays unknown rather than healthy. */
    public static final class Builder {

        private final String typeKey;
        private final int dimensionId;
        private final int x;
        private final int y;
        private final int z;
        private String name;
        private boolean formed;
        private boolean active;
        private Boolean needsMaintenance;
        private List<String> maintenanceIssues;
        private long euPerTick;
        private int progressTicks;
        private int maxProgressTicks;
        private Long euStored;
        private Long euCapacity;
        private Integer efficiencyPercent;
        private List<MultiblockPart> parts;

        private Builder(String typeKey, int dimensionId, int x, int y, int z) {
            this.typeKey = typeKey;
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder formed(boolean value) {
            this.formed = value;
            return this;
        }

        public Builder active(boolean value) {
            this.active = value;
            return this;
        }

        public Builder needsMaintenance(Boolean value) {
            this.needsMaintenance = value;
            return this;
        }

        public Builder maintenanceIssues(List<String> value) {
            this.maintenanceIssues = value;
            return this;
        }

        public Builder euPerTick(long value) {
            this.euPerTick = value;
            return this;
        }

        public Builder progress(int ticks, int maxTicks) {
            this.progressTicks = ticks;
            this.maxProgressTicks = maxTicks;
            return this;
        }

        public Builder energy(Long stored, Long capacity) {
            this.euStored = stored;
            this.euCapacity = capacity;
            return this;
        }

        public Builder efficiencyPercent(Integer value) {
            this.efficiencyPercent = value;
            return this;
        }

        public Builder parts(List<MultiblockPart> value) {
            this.parts = value;
            return this;
        }

        public MultiblockStatus build() {
            return new MultiblockStatus(this);
        }
    }
}
