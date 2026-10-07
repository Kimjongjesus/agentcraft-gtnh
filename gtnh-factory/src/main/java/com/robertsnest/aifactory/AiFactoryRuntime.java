package com.robertsnest.aifactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.robertsnest.aifactory.http.SnapshotJson;
import com.robertsnest.aifactory.http.TelemetryHttpServer;
import com.robertsnest.aifactory.http.TokenAuthenticator;
import com.robertsnest.aifactory.safety.CapabilityPolicy;
import com.robertsnest.aifactory.safety.FactoryOperation;
import com.robertsnest.aifactory.telemetry.BaseScope;
import com.robertsnest.aifactory.telemetry.CaptureEngine;
import com.robertsnest.aifactory.telemetry.ForgeCaptureSources;
import com.robertsnest.aifactory.telemetry.MeNetworkReader;
import com.robertsnest.aifactory.telemetry.SourceSession;
import com.robertsnest.aifactory.telemetry.TelemetryCapture;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * One server's worth of runtime: capture engine, HTTP listener, session.
 *
 * <p>
 * <b>Per-server ownership (M1).</b> An instance is created in
 * {@code serverStarting} for exactly one {@link MinecraftServer} and torn
 * down in {@code serverStopping} <em>and</em> {@code serverStopped}, whichever
 * comes first, idempotently. Opening a second single-player world builds a
 * fresh instance with a fresh session ID; nothing from the old server can be
 * drained against the new one because the old engine is closed and
 * unregistered from the tick bus.
 *
 * <p>
 * <b>No HTTP request ever reaches the game thread.</b> The tick handler
 * advances the capture engine; HTTP handlers read {@link CaptureEngine
 * #latest()} and serialise it on their own thread.
 */
public final class AiFactoryRuntime {

    private final AiFactoryConfig config;
    private final MinecraftServer server;
    private final CapabilityPolicy policy;
    private final SourceSession session;
    private final ForgeCaptureSources sources;
    private final CaptureEngine engine;
    private TelemetryHttpServer http;
    private volatile boolean stopped;

    public AiFactoryRuntime(AiFactoryConfig config, MinecraftServer server) {
        if (config == null || server == null) {
            throw new IllegalArgumentException("config and server are required");
        }
        this.config = config;
        this.server = server;
        this.policy = new CapabilityPolicy(config.enabledOperations());
        this.session = SourceSession.start(Tags.VERSION, System.currentTimeMillis());
        this.sources = new ForgeCaptureSources(server, meReader());
        this.engine = new CaptureEngine(sources, session, new CaptureEngine.ScopeSource() {

            @Override
            public BaseScope current() {
                return resolveScope();
            }
        }, config.captureSettings(), null);
    }

    public CapabilityPolicy policy() {
        return policy;
    }

    public SourceSession session() {
        return session;
    }

    public CaptureEngine engine() {
        return engine;
    }

    /** Start the listener. Call once from serverStarting. */
    public void start() {
        AiFactoryMod.LOG
            .info("AI Factory session {} capability policy: {}", session.sessionId(), policy.enabledOperations());
        if (policy.allowsMutation()) {
            // Loud on purpose: an admin should never discover by accident that
            // this server would let an external agent change the world. No
            // route performs any of these in this release regardless.
            AiFactoryMod.LOG.warn(
                "AI Factory: action flags are set ({}) but NO action route exists in this build; they have no effect.",
                policy.enabledOperations());
        }
        if (!config.baseConfigured() && config.playerScopeAllowed() == null) {
            AiFactoryMod.LOG.warn(
                "AI Factory: no base configured (base.configured=false) and no player-relative scope allowed; "
                    + "captures will report 'no base configured'.");
        }
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        if (!config.httpEnabled()) {
            AiFactoryMod.LOG.info("AI Factory telemetry endpoint disabled by config.");
            return;
        }
        TokenAuthenticator auth = TokenAuthenticator.fromFile(Paths.get(config.tokenFilePath()));
        if (auth == null) {
            AiFactoryMod.LOG.warn(
                "AI Factory telemetry NOT started: no usable token in {}. Write a 16+ character secret there.",
                config.tokenFilePath());
            return;
        }
        Map<String, TelemetryHttpServer.JsonSource> routes = new LinkedHashMap<String, TelemetryHttpServer.JsonSource>();
        routes.put("/telemetry/capture", new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() {
                TelemetryCapture c = engine.latest();
                return c == null ? null : SnapshotJson.capture(c);
            }
        });
        routes.put("/telemetry/snapshot", new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() {
                TelemetryCapture c = engine.latest();
                return c == null ? null : SnapshotJson.snapshot(c);
            }
        });
        routes.put("/telemetry/multiblocks", new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() {
                TelemetryCapture c = engine.latest();
                return c == null ? null : SnapshotJson.multiblocks(c);
            }
        });
        routes.put("/telemetry/design", new TelemetryHttpServer.JsonSource() {

            @Override
            public String call() {
                TelemetryCapture c = engine.latest();
                return c == null ? null : SnapshotJson.design(c);
            }
        });
        http = TelemetryHttpServer
            .start(config.bindHost(), config.port(), auth, routes, new TelemetryHttpServer.JsonSource() {

                @Override
                public String call() {
                    TelemetryCapture c = engine.latest();
                    List<String> ops = new ArrayList<String>();
                    for (FactoryOperation op : policy.enabledOperations()) {
                        ops.add(op.name());
                    }
                    return SnapshotJson.health(
                        System.currentTimeMillis(),
                        engine.lastTickMillis(),
                        c == null ? null : Long.valueOf(c.capturedAtMillis()),
                        c == null ? 0L : c.captureSequence(),
                        http == null ? 0 : http.rejectedCount(),
                        sources.gregTechAvailable(),
                        sources.ae2Available(),
                        ops);
                }
            });
    }

    /** Tear everything down. Idempotent; called from both stop events. */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        engine.close();
        try {
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory tick handler was not registered", t);
        }
        if (http != null) {
            http.stop();
            http = null;
            AiFactoryMod.LOG.info("AI Factory telemetry endpoint stopped (session {}).", session.sessionId());
        }
    }

    public boolean stopped() {
        return stopped;
    }

    /** Drives the capture engine. Registered on the FML bus for this server only. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stopped) {
            return;
        }
        if (FMLCommonHandler.instance()
            .getMinecraftServerInstance() != server) {
            // A different server instance is ticking: this runtime belongs to
            // a stopped world and must not read the new one.
            return;
        }
        try {
            engine.tick();
        } catch (Throwable t) {
            // Never let telemetry take the server down.
            AiFactoryMod.LOG.error("AI Factory capture tick failed", t);
        }
    }

    private BaseScope resolveScope() {
        String dimName = dimensionName(config.baseDimension());
        BaseScope configured = config.configuredScope(dimName);
        if (configured.defined()) {
            return configured;
        }
        String player = config.playerScopeAllowed();
        if (player == null) {
            return configured;
        }
        // Player-relative is opt-in by name and labelled: this is a box
        // around a named player, never "wherever someone is".
        WorldServer[] worlds = server.worldServers;
        if (worlds == null) {
            return configured;
        }
        for (WorldServer w : worlds) {
            if (w == null || w.provider == null) {
                continue;
            }
            List<int[]> positions = sources.playerPositions(player, w.provider.dimensionId);
            if (!positions.isEmpty()) {
                int[] p = positions.get(0);
                return new BaseScope(
                    BaseScope.Kind.PLAYER_RELATIVE,
                    "around " + player,
                    w.provider.dimensionId,
                    dimensionName(w.provider.dimensionId),
                    p[0],
                    p[1],
                    p[2],
                    config.playerScopeRadius(),
                    config.playerScopeHeight(),
                    player);
            }
        }
        return BaseScope.none("around " + player + " (offline)");
    }

    private String dimensionName(int dimensionId) {
        WorldServer[] worlds = server.worldServers;
        if (worlds != null) {
            for (WorldServer w : worlds) {
                if (w != null && w.provider != null && w.provider.dimensionId == dimensionId) {
                    try {
                        return w.provider.getDimensionName();
                    } catch (Throwable t) {
                        return "dim" + dimensionId;
                    }
                }
            }
        }
        return "dim" + dimensionId;
    }

    /** The AE2 reader for the configured access point, or null. Reflection-free but link-guarded. */
    private MeNetworkReader meReader() {
        if (!config.meAccessPointConfigured()) {
            return null;
        }
        try {
            return buildAe2Reader();
        } catch (NoClassDefFoundError e) {
            AiFactoryMod.LOG.info("AI Factory: AE2 not present; stock will be reported unavailable.");
            return null;
        }
    }

    private MeNetworkReader buildAe2Reader() {
        return new com.robertsnest.aifactory.telemetry.ae2.Ae2NetworkReader(
            new com.robertsnest.aifactory.telemetry.ae2.Ae2NetworkReader.TileEntitySource() {

                @Override
                public TileEntity get() {
                    WorldServer[] worlds = server.worldServers;
                    if (worlds == null) {
                        return null;
                    }
                    for (WorldServer w : worlds) {
                        if (w == null || w.provider == null || w.provider.dimensionId != config.baseDimension()) {
                            continue;
                        }
                        // Loaded chunks only: never force a load to find the access point.
                        if (!w.blockExists(config.meAccessX(), config.meAccessY(), config.meAccessZ())) {
                            return null;
                        }
                        return w.getTileEntity(config.meAccessX(), config.meAccessY(), config.meAccessZ());
                    }
                    return null;
                }
            });
    }
}
