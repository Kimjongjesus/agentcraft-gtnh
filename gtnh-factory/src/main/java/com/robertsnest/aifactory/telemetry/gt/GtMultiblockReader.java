package com.robertsnest.aifactory.telemetry.gt;

import java.util.ArrayList;
import java.util.List;

import com.robertsnest.aifactory.AiFactoryMod;
import com.robertsnest.aifactory.telemetry.MultiblockPart;
import com.robertsnest.aifactory.telemetry.MultiblockStatus;
import com.robertsnest.aifactory.telemetry.ae2.Ae2ChannelProbe;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;
import gregtech.common.tileentities.machines.MTEHatchInputME;

/**
 * Reads a GregTech multiblock's run state and the parts it is assembled from.
 *
 * <p>
 * <b>Verified against the jar GTNH 2.9.0-beta-3 actually ships</b>
 * ({@code gregtech-5.09.54.133.jar}) with {@code javap}, not assumed from
 * documentation. GTNH renamed these classes ({@code MTEMultiBlockBase},
 * {@code MTEHatch*}); hatches carry no coordinates themselves and position
 * comes from {@code getBaseMetaTileEntity()}; {@code mTier} and {@code mName}
 * are inherited fields.
 *
 * <p>
 * <b>AE2 is touched only through {@link Ae2ChannelProbe}</b>, in its own
 * guarded call, so a server with GregTech but without AE2 still reads every
 * hatch and simply reports {@code meChannelActive = null}.
 *
 * <p>
 * Every read is bounded: at most {@link #MAX_PARTS} parts are described per
 * controller, and the reader reports how many it skipped. Call on the server
 * thread; this reads live tile-entity state.
 */
public final class GtMultiblockReader {

    /** Parts described per controller before the list is cut. */
    public static final int MAX_PARTS = 64;

    private GtMultiblockReader() {}

    /** Outcome of one read, including whether parts were dropped. */
    public static final class Result {

        public final MultiblockStatus status;
        public final int partsSkipped;
        public final int partErrors;

        Result(MultiblockStatus status, int partsSkipped, int partErrors) {
            this.status = status;
            this.partsSkipped = partsSkipped;
            this.partErrors = partErrors;
        }
    }

    /**
     * Describe one multiblock controller.
     *
     * @param controller the multiblock's meta tile entity
     * @return its status, or null when the argument is not readable
     */
    public static Result read(MTEMultiBlockBase controller) {
        if (controller == null) {
            return null;
        }
        IGregTechTileEntity base;
        try {
            base = controller.getBaseMetaTileEntity();
        } catch (Throwable t) {
            AiFactoryMod.LOG.warn("AI Factory could not reach a multiblock's tile entity", t);
            return null;
        }
        // Not isInvalid(): that is vanilla TileEntity's name. GregTech's
        // IHasWorldObjectAndCoords calls it isInvalidTileEntity().
        if (base == null || base.isInvalidTileEntity()) {
            return null;
        }

        int x = base.getXCoord();
        int y = base.getYCoord();
        int z = base.getZCoord();
        int dimension = dimensionOf(base);
        String typeKey = metaName(controller);
        String name = safeName(controller, typeKey);

        int[] skipped = new int[2];
        List<MultiblockPart> parts = new ArrayList<MultiblockPart>();
        collect(parts, controller.mInputHatches, MultiblockPart.PartType.INPUT_HATCH, skipped);
        collect(parts, controller.mInputBusses, MultiblockPart.PartType.INPUT_BUS, skipped);
        collect(parts, controller.mOutputHatches, MultiblockPart.PartType.OUTPUT_HATCH, skipped);
        collect(parts, controller.mOutputBusses, MultiblockPart.PartType.OUTPUT_BUS, skipped);
        collect(parts, controller.mEnergyHatches, MultiblockPart.PartType.ENERGY_HATCH, skipped);
        collect(parts, controller.mDynamoHatches, MultiblockPart.PartType.DYNAMO_HATCH, skipped);
        collect(parts, controller.mMaintenanceHatches, MultiblockPart.PartType.MAINTENANCE_HATCH, skipped);
        collect(parts, controller.mMufflerHatches, MultiblockPart.PartType.MUFFLER_HATCH, skipped);

        Boolean needsRepair;
        List<String> issues = new ArrayList<String>();
        try {
            needsRepair = Boolean.valueOf(controller.getRepairStatus() < controller.getIdealStatus());
            if (needsRepair.booleanValue()) {
                // Each flag is true when that tool's maintenance is FINE.
                if (!controller.mWrench) issues.add("wrench");
                if (!controller.mScrewdriver) issues.add("screwdriver");
                if (!controller.mSoftMallet) issues.add("soft_mallet");
                if (!controller.mHardHammer) issues.add("hard_hammer");
                if (!controller.mSolderingTool) issues.add("soldering");
                if (!controller.mCrowbar) issues.add("crowbar");
            }
        } catch (Throwable t) {
            // Unknown, not healthy: the old code returned false here.
            needsRepair = null;
        }

        Long stored = null;
        Long capacity = null;
        try {
            stored = Long.valueOf(base.getStoredEU());
            capacity = Long.valueOf(base.getEUCapacity());
        } catch (Throwable t) {
            stored = null;
            capacity = null;
        }

        Integer efficiency = null;
        try {
            efficiency = Integer.valueOf(Math.max(0, Math.min(100, controller.mEfficiency / 100)));
        } catch (Throwable t) {
            efficiency = null;
        }

        MultiblockStatus status = MultiblockStatus.builder(typeKey, dimension, x, y, z)
            .name(name)
            .formed(controller.mMachine)
            .active(base.isActive())
            .needsMaintenance(needsRepair)
            .maintenanceIssues(issues)
            .euPerTick(controller.mEUt)
            .progress(controller.mProgresstime, controller.mMaxProgresstime)
            .energy(stored, capacity)
            .efficiencyPercent(efficiency)
            .parts(parts)
            .build();
        return new Result(status, skipped[0], skipped[1]);
    }

    private static int dimensionOf(IGregTechTileEntity base) {
        try {
            net.minecraft.world.World world = base.getWorld();
            if (world != null && world.provider != null) {
                return world.provider.dimensionId;
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not read a machine's dimension", t);
        }
        return Integer.MIN_VALUE;
    }

    private static void collect(List<MultiblockPart> out, List<? extends MTEHatch> hatches,
        MultiblockPart.PartType fallback, int[] skipped) {
        if (hatches == null) {
            return;
        }
        for (MTEHatch hatch : hatches) {
            if (out.size() >= MAX_PARTS) {
                skipped[0]++;
                continue;
            }
            MultiblockPart part = describe(hatch, fallback);
            if (part != null) {
                out.add(part);
            } else if (hatch != null) {
                skipped[1]++;
            }
        }
    }

    private static MultiblockPart describe(MTEHatch hatch, MultiblockPart.PartType fallback) {
        if (hatch == null) {
            return null;
        }
        try {
            IGregTechTileEntity base = hatch.getBaseMetaTileEntity();
            if (base == null || base.isInvalidTileEntity()) {
                return null;
            }
            MultiblockPart.PartType type = classify(hatch, fallback);
            Boolean channelActive = null;
            if (type == MultiblockPart.PartType.ME_INPUT_HATCH || type == MultiblockPart.PartType.ME_INPUT_BUS) {
                channelActive = probeChannel(hatch);
            }
            return new MultiblockPart(
                type,
                safeName(hatch, type.name()),
                base.getXCoord(),
                base.getYCoord(),
                base.getZCoord(),
                hatch.mTier,
                channelActive);
        } catch (Throwable t) {
            AiFactoryMod.LOG.warn("AI Factory skipped an unreadable multiblock part", t);
            return null;
        }
    }

    /** Isolated so a missing AE2 fails to link this one frame, not the read. */
    private static Boolean probeChannel(Object hatch) {
        try {
            return Ae2ChannelProbe.channelActive(hatch);
        } catch (NoClassDefFoundError e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static MultiblockPart.PartType classify(MTEHatch hatch, MultiblockPart.PartType fallback) {
        // ME variants extend the plain hatch types, so this check comes first.
        if (hatch instanceof MTEHatchInputME) {
            return MultiblockPart.PartType.ME_INPUT_HATCH;
        }
        if (hatch instanceof MTEHatchInputBusME) {
            return MultiblockPart.PartType.ME_INPUT_BUS;
        }
        return fallback;
    }

    private static String metaName(IMetaTileEntity metaTileEntity) {
        try {
            String meta = metaTileEntity.getMetaName();
            if (meta != null && !meta.trim()
                .isEmpty()) {
                return meta;
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not resolve a machine meta name", t);
        }
        return metaTileEntity.getClass()
            .getSimpleName();
    }

    private static String safeName(IMetaTileEntity metaTileEntity, String fallback) {
        try {
            String local = metaTileEntity.getLocalName();
            if (local != null && !local.trim()
                .isEmpty()) {
                return local;
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not resolve a machine name", t);
        }
        return fallback;
    }
}
