package com.robertsnest.aifactory;

import java.io.File;
import java.util.EnumSet;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

import com.robertsnest.aifactory.http.TelemetryHttpServer;
import com.robertsnest.aifactory.safety.FactoryOperation;
import com.robertsnest.aifactory.telemetry.BaseScope;
import com.robertsnest.aifactory.telemetry.CaptureEngine;
import com.robertsnest.aifactory.telemetry.WorkBudget;
import com.robertsnest.aifactory.telemetry.world.BaseDesignScanner;

/**
 * Operator-facing settings.
 *
 * <p>
 * Defaults are chosen so that an admin who installs the mod and changes
 * nothing gets a server that is safe rather than convenient: telemetry bound to
 * loopback, no actions enabled, the endpoint disabled entirely until a token
 * file exists, and <b>no base in scope</b> until one is named. The mod never
 * guesses where the base is.
 */
public final class AiFactoryConfig {

    private static final String CATEGORY_HTTP = "http";
    private static final String CATEGORY_SAFETY = "safety";
    private static final String CATEGORY_BASE = "base";
    private static final String CATEGORY_CAPTURE = "capture";
    private static final String CATEGORY_SURVEY = "design_survey";

    private final boolean httpEnabled;
    private final String bindHost;
    private final int port;
    private final String tokenFilePath;
    private final Set<FactoryOperation> enabledOperations;

    private final boolean baseConfigured;
    private final String baseLabel;
    private final int baseDimension;
    private final int baseCenterX;
    private final int baseCenterY;
    private final int baseCenterZ;
    private final int baseRadius;
    private final int baseHeight;
    private final String playerScopeAllowed;
    private final int playerScopeRadius;
    private final int playerScopeHeight;
    private final boolean meAccessPointConfigured;
    private final int meAccessX;
    private final int meAccessY;
    private final int meAccessZ;

    private final CaptureEngine.Settings capture = new CaptureEngine.Settings();

    private AiFactoryConfig(Configuration config, File configDir) {
        this.httpEnabled = config.getBoolean(
            "enabled",
            CATEGORY_HTTP,
            true,
            "Serve read-only telemetry over HTTP. The endpoint still refuses to start without a token file.");
        this.bindHost = config.getString(
            "bindHost",
            CATEGORY_HTTP,
            TelemetryHttpServer.DEFAULT_BIND,
            "Address to bind. Keep this on loopback and reach it through an operator-configured tunnel; "
                + "a Minecraft host is internet-facing and an open telemetry port lists your base contents.");
        this.port = config.getInt(
            "port",
            CATEGORY_HTTP,
            TelemetryHttpServer.DEFAULT_PORT,
            1,
            65535,
            "TCP port for the telemetry endpoint.");
        this.tokenFilePath = config.getString(
            "tokenFile",
            CATEGORY_HTTP,
            new File(configDir, "aifactory-token.txt").getAbsolutePath(),
            "Path to a file whose first non-comment line is the shared secret (16+ characters). "
                + "Keep it outside the world folder: world saves get copied and shared. "
                + "No token file means the endpoint does not start.");

        // Actions are opt-in, one at a time, and the irreversible ones cannot
        // be enabled here at all (see CapabilityPolicy). No production route
        // exists for any of them in this release; the flags only log.
        EnumSet<FactoryOperation> enabled = EnumSet.noneOf(FactoryOperation.class);
        for (FactoryOperation operation : FactoryOperation.values()) {
            if (operation == FactoryOperation.TELEMETRY_SNAPSHOT) {
                continue;
            }
            boolean allowed = config.getBoolean(
                operation.name(),
                CATEGORY_SAFETY,
                false,
                "Allow the oracle to request " + operation.name()
                    + ". MOVE_INVENTORY and PLACE_BLUEPRINT are refused by the mod regardless of this setting. "
                    + "No HTTP route performs any action in this release.");
            if (allowed) {
                enabled.add(operation);
            }
        }
        this.enabledOperations = enabled;

        this.baseConfigured = config.getBoolean(
            "configured",
            CATEGORY_BASE,
            false,
            "Set true after filling in the base region below. Until then nothing is in scope and "
                + "telemetry reports 'no base configured' rather than guessing from player positions.");
        this.baseLabel = config
            .getString("label", CATEGORY_BASE, "main base", "Operator name for this base, shown to the copilot.");
        this.baseDimension = config.getInt(
            "dimensionId",
            CATEGORY_BASE,
            0,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE,
            "Dimension ID the base is in (0 = Overworld).");
        this.baseCenterX = config.getInt("centerX", CATEGORY_BASE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "");
        this.baseCenterY = config.getInt("centerY", CATEGORY_BASE, 64, 0, 255, "");
        this.baseCenterZ = config.getInt("centerZ", CATEGORY_BASE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "");
        this.baseRadius = config
            .getInt("radius", CATEGORY_BASE, 64, 8, 256, "Horizontal half-width of the base box, in blocks.");
        this.baseHeight = config
            .getInt("height", CATEGORY_BASE, 32, 4, 128, "Vertical half-height of the base box, in blocks.");
        this.playerScopeAllowed = config.getString(
            "playerRelativeScopeFor",
            CATEGORY_BASE,
            "",
            "Optional. A single player name whose surroundings may be captured INSTEAD of the configured box, "
                + "clearly labelled PLAYER_RELATIVE. Empty disables it. Used only when the configured base is off.");
        this.playerScopeRadius = config.getInt("playerRelativeRadius", CATEGORY_BASE, 48, 8, 128, "");
        this.playerScopeHeight = config.getInt("playerRelativeHeight", CATEGORY_BASE, 24, 4, 128, "");
        this.meAccessPointConfigured = config.getBoolean(
            "meAccessPointConfigured",
            CATEGORY_BASE,
            false,
            "Set true after placing the coordinates of an AE2 grid block (controller, interface, cable) "
                + "the mod may read stock through. Unset means stock is reported UNAVAILABLE.");
        this.meAccessX = config.getInt("meAccessX", CATEGORY_BASE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "");
        this.meAccessY = config.getInt("meAccessY", CATEGORY_BASE, 64, 0, 255, "");
        this.meAccessZ = config.getInt("meAccessZ", CATEGORY_BASE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "");

        capture.intervalMillis = config.getInt(
            "intervalSeconds",
            CATEGORY_CAPTURE,
            (int) (CaptureEngine.DEFAULT_INTERVAL_MILLIS / 1000L),
            30,
            3600,
            "Minimum seconds between captures. Never below 30.") * 1000L;
        capture.tickBudgetMillis = config.getInt(
            "tickBudgetMillis",
            CATEGORY_CAPTURE,
            (int) WorkBudget.DEFAULT_BUDGET_MILLIS,
            1,
            20,
            "Milliseconds of one server tick a capture may spend. 50 ms is a whole tick.");
        capture.tickBudgetAttempts = config.getInt(
            "tickBudgetAttempts",
            CATEGORY_CAPTURE,
            4096,
            64,
            65536,
            "Positions/tiles examined per tick, whichever limit hits first.");
        capture.maxMachines = config.getInt(
            "maxMachines",
            CATEGORY_CAPTURE,
            CaptureEngine.DEFAULT_MAX_MACHINES,
            1,
            4096,
            "Multiblocks described per capture.");
        capture.maxTileAttempts = config.getInt(
            "maxTileAttempts",
            CATEGORY_CAPTURE,
            (int) CaptureEngine.DEFAULT_MAX_TILE_ATTEMPTS,
            1000,
            2_000_000,
            "Tile entities examined per capture across all ticks.");
        capture.stockCap = config.getInt("stockCap", CATEGORY_CAPTURE, 500, 1, 5000, "Largest stocks reported.");
        capture.stockMaxAttempts = config.getInt(
            "stockMaxAttempts",
            CATEGORY_CAPTURE,
            20_000,
            100,
            500_000,
            "ME storage rows examined per capture.");
        capture.maxCaptureTicks = config.getInt(
            "maxCaptureTicks",
            CATEGORY_CAPTURE,
            CaptureEngine.DEFAULT_MAX_CAPTURE_TICKS,
            10,
            6000,
            "Ticks a capture may span before it is published partial.");

        capture.designEnabled = config
            .getBoolean("enabled", CATEGORY_SURVEY, true, "Sample the base's block palette and artificial light.");
        capture.designStride = config.getInt(
            "stride",
            CATEGORY_SURVEY,
            BaseDesignScanner.DEFAULT_STRIDE,
            1,
            8,
            "Sample every Nth block. Cost falls with the cube of this value; 1 reads every block.");
        capture.designMaxAttempts = config.getInt(
            "maxAttempts",
            CATEGORY_SURVEY,
            BaseDesignScanner.DEFAULT_MAX_ATTEMPTS,
            1000,
            2_000_000,
            "Hard ceiling on positions attempted per survey, loaded or not.");
        capture.designPaletteCap = config
            .getInt("paletteCap", CATEGORY_SURVEY, BaseDesignScanner.DEFAULT_PALETTE_CAP, 5, 500, "");
    }

    public static AiFactoryConfig load(File configFile, File configDir) {
        Configuration config = new Configuration(configFile);
        try {
            config.load();
            return new AiFactoryConfig(config, configDir);
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }

    public boolean httpEnabled() {
        return httpEnabled;
    }

    public String bindHost() {
        return bindHost;
    }

    public int port() {
        return port;
    }

    public String tokenFilePath() {
        return tokenFilePath;
    }

    public Set<FactoryOperation> enabledOperations() {
        return enabledOperations;
    }

    public CaptureEngine.Settings captureSettings() {
        return capture;
    }

    /** The configured base box, or a NONE scope when not configured. */
    public BaseScope configuredScope(String dimensionName) {
        if (!baseConfigured) {
            return BaseScope.none(baseLabel);
        }
        return new BaseScope(
            BaseScope.Kind.CONFIGURED,
            baseLabel,
            baseDimension,
            dimensionName,
            baseCenterX,
            baseCenterY,
            baseCenterZ,
            baseRadius,
            baseHeight,
            null);
    }

    public boolean baseConfigured() {
        return baseConfigured;
    }

    public int baseDimension() {
        return baseDimension;
    }

    /** Player whose position may anchor a labelled scope, or null. */
    public String playerScopeAllowed() {
        return playerScopeAllowed == null || playerScopeAllowed.trim()
            .isEmpty() ? null : playerScopeAllowed.trim();
    }

    public int playerScopeRadius() {
        return playerScopeRadius;
    }

    public int playerScopeHeight() {
        return playerScopeHeight;
    }

    public boolean meAccessPointConfigured() {
        return meAccessPointConfigured;
    }

    public int meAccessX() {
        return meAccessX;
    }

    public int meAccessY() {
        return meAccessY;
    }

    public int meAccessZ() {
        return meAccessZ;
    }
}
