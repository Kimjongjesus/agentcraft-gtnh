package com.robertsnest.aifactory.http;

import java.util.List;
import java.util.Map;

import com.robertsnest.aifactory.protocol.ProtocolEnvelope;
import com.robertsnest.aifactory.telemetry.BaseDesignSurvey;
import com.robertsnest.aifactory.telemetry.BaseScope;
import com.robertsnest.aifactory.telemetry.Coverage;
import com.robertsnest.aifactory.telemetry.FactorySnapshot;
import com.robertsnest.aifactory.telemetry.MeNetworkReader;
import com.robertsnest.aifactory.telemetry.MultiblockPart;
import com.robertsnest.aifactory.telemetry.MultiblockStatus;
import com.robertsnest.aifactory.telemetry.SourceSession;
import com.robertsnest.aifactory.telemetry.Surroundings;
import com.robertsnest.aifactory.telemetry.TelemetryCapture;

/**
 * Renders telemetry as JSON (protocol v2).
 *
 * <p>
 * Every payload carries {@code protocol}, {@code worldRevision},
 * {@code capturedAtMillis}, a {@code session} block, a {@code scope} block and
 * per-section {@code coverage}. A consumer that reads a section without its
 * coverage is making an explicit mistake rather than an invisible one.
 */
public final class SnapshotJson {

    private SnapshotJson() {}

    private static Json envelope(TelemetryCapture c) {
        Json json = new Json().object()
            .put("protocol", ProtocolEnvelope.currentVersion())
            .put("worldRevision", c.worldRevision())
            .put("capturedAtMillis", c.capturedAtMillis())
            .put("captureStartedAtMillis", c.captureStartedAtMillis())
            .put("captureTicks", c.captureTicks())
            .put("captureSequence", c.captureSequence())
            .put("worldName", c.worldName())
            .put("baseId", c.baseId());
        session(json, c.session());
        scope(json, c.scope());
        json.array("warnings");
        for (String w : c.warnings()) {
            json.value(w);
        }
        json.endArray();
        return json;
    }

    private static void session(Json json, SourceSession s) {
        json.objectField("session")
            .put("id", s.sessionId())
            .put("startedAtMillis", s.startedAtMillis())
            .put("modVersion", s.modVersion())
            .endObject();
    }

    private static void scope(Json json, BaseScope s) {
        json.objectField("scope")
            .put(
                "kind",
                s.kind()
                    .name())
            .put("label", s.label())
            .put("identity", s.identity())
            .put("dimensionId", s.dimensionId())
            .put("dimension", s.dimensionName())
            .put("centerX", s.centerX())
            .put("centerY", s.centerY())
            .put("centerZ", s.centerZ())
            .put("radius", s.radius())
            .put("height", s.height())
            .put("minY", s.minY())
            .put("maxY", s.maxY())
            .put("anchor", s.anchor())
            .endObject();
    }

    static void coverage(Json json, String key, Coverage c) {
        json.objectField(key)
            .put(
                "status",
                c.status()
                    .name())
            .put("reason", c.reason())
            .put("attempted", c.attempted())
            .put("succeeded", c.succeeded())
            .put("errors", c.errors())
            .put("skippedUnloaded", c.skippedUnloaded())
            .put("returned", c.returned())
            .put("distinctSeen", c.distinctSeen())
            .put("truncated", c.truncated())
            .put("budgetExhausted", c.budgetExhausted())
            .put("elapsedMillis", c.elapsedMillis())
            .put("complete", c.complete())
            .endObject();
    }

    /** The full capture: every section with its coverage. */
    public static String capture(TelemetryCapture c) {
        Json json = envelope(c);
        machinesSection(json, c);
        stockSection(json, c.stock());
        designSection(json, c.design(), c.designCoverage());
        surroundingsSection(json, c.surroundings());
        return json.endObject()
            .toString();
    }

    /**
     * Legacy-shaped snapshot: flat machines plus stock. {@code truncated} is
     * derived from coverage and kept for consumers that only know v1's flag.
     */
    public static String snapshot(TelemetryCapture c) {
        FactorySnapshot snapshot = c.toFactorySnapshot();
        Json json = envelope(c).put("truncated", snapshot.truncated());
        json.array("stock");
        for (FactorySnapshot.ItemStock item : snapshot.stock()) {
            json.object()
                .put("id", item.itemId())
                .put("name", item.displayName())
                .put("quantity", item.quantity())
                .put("craftable", item.craftable())
                .endObject();
        }
        json.endArray();
        coverage(
            json,
            "stockCoverage",
            c.stock()
                .coverage());
        json.array("machines");
        for (FactorySnapshot.MachineStatus machine : snapshot.machines()) {
            json.object()
                .put("id", machine.machineId())
                .put("name", machine.name())
                .put("active", machine.active())
                .put("problem", machine.problem())
                .endObject();
        }
        json.endArray();
        coverage(json, "machineCoverage", c.machineCoverage());
        return json.endObject()
            .toString();
    }

    /** Multiblocks with parts, positions and faults. */
    public static String multiblocks(TelemetryCapture c) {
        Json json = envelope(c);
        machinesSection(json, c);
        return json.endObject()
            .toString();
    }

    private static void machinesSection(Json json, TelemetryCapture c) {
        json.array("multiblocks");
        for (MultiblockStatus machine : c.machines()) {
            machine(json, machine);
        }
        json.endArray();
        coverage(json, "machineCoverage", c.machineCoverage());
    }

    static void machine(Json json, MultiblockStatus machine) {
        json.object()
            .put("id", machine.machineId())
            .put("typeKey", machine.typeKey())
            .put("name", machine.name())
            .put("dimensionId", machine.dimensionId())
            .put("x", machine.x())
            .put("y", machine.y())
            .put("z", machine.z())
            .put("formed", machine.formed())
            .put("active", machine.active())
            .put("needsMaintenance", machine.needsMaintenance())
            .put("euPerTick", machine.euPerTick())
            .put("progressTicks", machine.progressTicks())
            .put("maxProgressTicks", machine.maxProgressTicks())
            .put("euStored", machine.euStored())
            .put("euCapacity", machine.euCapacity())
            .put("efficiencyPercent", machine.efficiencyPercent())
            .put("problem", machine.describeProblem());
        json.array("maintenanceIssues");
        for (String issue : machine.maintenanceIssues()) {
            json.value(issue);
        }
        json.endArray();
        json.array("parts");
        for (MultiblockPart part : machine.parts()) {
            json.object()
                .put(
                    "type",
                    part.type()
                        .name())
                .put("name", part.name())
                .put("x", part.x())
                .put("y", part.y())
                .put("z", part.z())
                .put("tier", part.tier())
                // Tri-state on purpose: null means "not ME-backed", which
                // is not the same as a channel being down.
                .put("meChannelActive", part.meChannelActive())
                .endObject();
        }
        json.endArray();
        json.endObject();
    }

    private static void stockSection(Json json, MeNetworkReader.StockRead read) {
        json.objectField("stock");
        json.array("items");
        for (FactorySnapshot.ItemStock item : read.stock()) {
            json.object()
                .put("id", item.itemId())
                .put("name", item.displayName())
                .put("quantity", item.quantity())
                .put("craftable", item.craftable())
                .endObject();
        }
        json.endArray();
        json.objectField("power")
            .put("stored", read.storedPower())
            .put("max", read.maxStoredPower())
            .put("avgUsage", read.avgPowerUsage())
            .put("avgInjection", read.avgPowerInjection())
            .put("powered", read.networkPowered())
            .put("unit", "AE")
            .endObject();
        json.objectField("crafting")
            .put("cpus", read.craftingCpus())
            .put("busyCpus", read.busyCraftingCpus())
            .endObject();
        coverage(json, "coverage", read.coverage());
        json.endObject();
    }

    /** Base-design survey alone, for the dedicated route. */
    public static String design(TelemetryCapture c) {
        Json json = envelope(c);
        designSection(json, c.design(), c.designCoverage());
        return json.endObject()
            .toString();
    }

    private static void designSection(Json json, BaseDesignSurvey survey, Coverage coverage) {
        json.objectField("design");
        if (survey != null) {
            json.put("stride", survey.stride())
                .put("phaseX", survey.phaseX())
                .put("phaseY", survey.phaseY())
                .put("phaseZ", survey.phaseZ())
                .put("samplingScheme", "stride lattice; a coarse sample, not a census")
                .put("positionsAttempted", survey.positionsAttempted())
                .put("blocksSampled", survey.blocksSampled())
                .put("solidBlocks", survey.solidBlocks())
                .put("paletteReturned", survey.paletteReturned())
                .put("paletteDistinctSeen", survey.paletteDistinctSeen())
                .put("density", survey.density())
                .put("dominantShare", survey.dominantShare());
            json.array("palette");
            for (BaseDesignSurvey.BlockUsage usage : survey.palette()) {
                json.object()
                    .put("id", usage.blockId())
                    .put("name", usage.displayName())
                    .put("count", usage.count())
                    .endObject();
            }
            json.endArray();
            BaseDesignSurvey.ArtificialLightProfile lighting = survey.lighting();
            json.objectField("artificialLight")
                .put("openPositions", lighting.openPositions())
                .put("lit", lighting.litPositions())
                .put("unlit", lighting.unlitPositions())
                .put("unlitFraction", lighting.unlitFraction())
                .put("minLight", lighting.minLight())
                .put("maxLight", lighting.maxLight())
                .put("blockLightSpawnFloor", BaseDesignSurvey.ArtificialLightProfile.BLOCK_LIGHT_SPAWN_FLOOR)
                .put("meaning", "saved block-light only; not a mob-spawn measurement")
                .endObject();
        }
        coverage(json, "coverage", coverage == null ? Coverage.unavailable("not_collected") : coverage);
        json.endObject();
    }

    private static void surroundingsSection(Json json, Surroundings s) {
        json.objectField("surroundings")
            .put("worldTime", s.worldTime())
            .put("totalWorldTime", s.totalWorldTime())
            .put("raining", s.raining())
            .put("thundering", s.thundering())
            .put("biomeAtCenter", s.biomeAtCenter())
            .put("playersOnline", s.playersOnline())
            .put("hostileCount", s.hostileCount());
        json.array("playersInScope");
        for (String p : s.playersInScope()) {
            json.value(p);
        }
        json.endArray();
        json.objectField("entityCounts");
        for (Map.Entry<String, Integer> e : s.entityCounts()
            .entrySet()) {
            json.put(
                e.getKey(),
                e.getValue()
                    .longValue());
        }
        json.endObject();
        coverage(json, "coverage", s.coverage());
        json.endObject();
    }

    /** Health: liveness plus freshness, never game state. */
    public static String health(long nowMillis, long lastTickMillis, Long lastCaptureMillis, long captureSequence,
        int rejected, boolean gregTech, boolean ae2, List<String> operations) {
        Json json = new Json().object()
            .put("protocol", ProtocolEnvelope.currentVersion())
            .put("status", "ok")
            .put("nowMillis", nowMillis)
            .put("lastTickMillis", lastTickMillis)
            .put("lastTickAgeMillis", lastTickMillis <= 0L ? -1L : nowMillis - lastTickMillis)
            .put("lastCaptureMillis", lastCaptureMillis == null ? null : lastCaptureMillis)
            .put("captureSequence", captureSequence)
            .put("httpRejected", rejected)
            .put("gregTech", gregTech)
            .put("ae2", ae2);
        json.array("enabledOperations");
        for (String op : operations) {
            json.value(op);
        }
        json.endArray();
        return json.endObject()
            .toString();
    }

    /** Render an error without leaking internals to the caller. */
    public static String error(String message) {
        return new Json().object()
            .put("protocol", ProtocolEnvelope.currentVersion())
            .put("error", message)
            .endObject()
            .toString();
    }
}
