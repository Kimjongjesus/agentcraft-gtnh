package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;

import com.robertsnest.aifactory.AiFactoryMod;
import com.robertsnest.aifactory.telemetry.gt.GtMultiblockReader;
import com.robertsnest.aifactory.telemetry.world.BlockAccess;
import com.robertsnest.aifactory.telemetry.world.WorldBlockAccess;

import cpw.mods.fml.common.Loader;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * {@link CaptureSources} over a live Forge server.
 *
 * <p>
 * Bound to one {@link MinecraftServer} instance for its lifetime (M1). Any
 * call after that server has stopped sees a null world list and reports
 * nothing rather than reading a successor server's state.
 *
 * <p>
 * GregTech and AE2 are optional: presence is checked through the Forge mod
 * list once, and the readers are reached only through guarded frames.
 */
public final class ForgeCaptureSources implements CaptureSources {

    /** Entities examined per capture for the surroundings section. */
    public static final int MAX_ENTITY_ATTEMPTS = 4096;

    private final MinecraftServer server;
    private final boolean gregTech;
    private final boolean ae2;
    private final MeNetworkReader stockReader;

    public ForgeCaptureSources(MinecraftServer server, MeNetworkReader stockReader) {
        if (server == null) {
            throw new IllegalArgumentException("server is required");
        }
        this.server = server;
        this.gregTech = modLoaded("gregtech");
        this.ae2 = modLoaded("appliedenergistics2");
        this.stockReader = stockReader;
    }

    private static boolean modLoaded(String id) {
        try {
            return Loader.isModLoaded(id);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean ae2Available() {
        return ae2;
    }

    @Override
    public long worldRevision() {
        return Math.max(0L, server.getTickCounter());
    }

    @Override
    public String worldName() {
        try {
            String name = server.getFolderName();
            return name == null ? "unknown" : name;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    private WorldServer world(int dimensionId) {
        WorldServer[] worlds = server.worldServers;
        if (worlds == null) {
            return null;
        }
        for (WorldServer w : worlds) {
            if (w != null && w.provider != null && w.provider.dimensionId == dimensionId) {
                return w;
            }
        }
        return null;
    }

    @Override
    public TileList tiles(int dimensionId) {
        final WorldServer world = world(dimensionId);
        if (world == null || world.loadedTileEntityList == null) {
            return null;
        }
        final List<?> list = world.loadedTileEntityList;
        return new TileList() {

            @Override
            public int size() {
                return list.size();
            }

            @Override
            public Object get(int index) {
                try {
                    return index < list.size() ? list.get(index) : null;
                } catch (IndexOutOfBoundsException e) {
                    return null;
                }
            }
        };
    }

    @Override
    public int[] tilePosition(Object tile) {
        if (!(tile instanceof TileEntity)) {
            return null;
        }
        TileEntity te = (TileEntity) tile;
        return new int[] { te.xCoord, te.yCoord, te.zCoord };
    }

    @Override
    public MachineRead readMachine(Object tile) {
        if (!gregTech) {
            return null;
        }
        try {
            return readGt(tile);
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    /** Isolated so GregTech types are linked in this frame only. */
    private static MachineRead readGt(Object candidate) {
        if (!(candidate instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity)) {
            return null;
        }
        Object meta = ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) candidate).getMetaTileEntity();
        if (!(meta instanceof MTEMultiBlockBase)) {
            return null;
        }
        GtMultiblockReader.Result result = GtMultiblockReader.read((MTEMultiBlockBase) meta);
        if (result == null) {
            return null;
        }
        return new MachineRead(result.status, result.partsSkipped, result.partErrors);
    }

    @Override
    public boolean gregTechAvailable() {
        return gregTech;
    }

    @Override
    public MeNetworkReader stockReader() {
        return ae2 ? stockReader : null;
    }

    @Override
    public BlockAccess blocks(int dimensionId) {
        WorldServer world = world(dimensionId);
        return world == null ? null : new WorldBlockAccess(world);
    }

    @Override
    public Surroundings surroundings(BaseScope scope, WorkBudget budget) {
        WorldServer world = world(scope.dimensionId());
        if (world == null) {
            return Surroundings.unavailable("scope_dimension_not_loaded");
        }
        Long time = null;
        Long total = null;
        Boolean raining = null;
        Boolean thundering = null;
        String biome = null;
        try {
            time = Long.valueOf(world.getWorldTime() % 24000L);
            total = Long.valueOf(world.getTotalWorldTime());
            raining = Boolean.valueOf(world.isRaining());
            thundering = Boolean.valueOf(world.isThundering());
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not read world time/weather", t);
        }
        try {
            if (world.blockExists(scope.centerX(), scope.centerY(), scope.centerZ())) {
                BiomeGenBase b = world.getBiomeGenForCoords(scope.centerX(), scope.centerZ());
                biome = b == null ? null : b.biomeName;
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not read the centre biome", t);
        }

        List<String> players = new ArrayList<String>();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        long attempted = 0L;
        long errors = 0L;
        int hostile = 0;
        boolean truncated = false;
        int online = 0;
        try {
            online = world.playerEntities == null ? 0 : world.playerEntities.size();
        } catch (Throwable t) {
            online = 0;
        }
        try {
            List<?> entities = new ArrayList<Object>(world.loadedEntityList);
            for (Object o : entities) {
                if (attempted >= MAX_ENTITY_ATTEMPTS || !budget.tryConsume()) {
                    truncated = true;
                    break;
                }
                attempted++;
                if (!(o instanceof Entity)) {
                    continue;
                }
                Entity e = (Entity) o;
                int ex = (int) Math.floor(e.posX);
                int ey = (int) Math.floor(e.posY);
                int ez = (int) Math.floor(e.posZ);
                if (!scope.contains(scope.dimensionId(), ex, ey, ez)) {
                    continue;
                }
                if (e instanceof EntityPlayer) {
                    String name = ((EntityPlayer) e).getCommandSenderName();
                    players.add(name == null ? "?" : name);
                    continue;
                }
                if (e instanceof IMob) {
                    hostile++;
                }
                String key = e.getClass()
                    .getSimpleName();
                if (counts.size() >= 64 && !counts.containsKey(key)) {
                    key = "Other";
                }
                Integer existing = counts.get(key);
                counts.put(key, Integer.valueOf(existing == null ? 1 : existing.intValue() + 1));
            }
        } catch (Throwable t) {
            errors++;
            AiFactoryMod.LOG.debug("AI Factory entity survey failed", t);
        }
        Coverage coverage = Coverage.builder()
            .status(Coverage.Status.OK)
            .attempted(attempted)
            .succeeded(attempted - errors)
            .errors(errors)
            .truncated(truncated)
            .budgetExhausted(truncated)
            .elapsedMillis(budget.elapsedMillis())
            .settle()
            .build();
        return new Surroundings(
            time,
            total,
            raining,
            thundering,
            biome,
            players,
            online,
            counts,
            Integer.valueOf(hostile),
            coverage);
    }

    @Override
    public List<int[]> playerPositions(String playerName, int dimensionId) {
        List<int[]> out = new ArrayList<int[]>();
        WorldServer world = world(dimensionId);
        if (world == null || playerName == null || world.playerEntities == null) {
            return out;
        }
        try {
            for (Object o : new ArrayList<Object>(world.playerEntities)) {
                if (o instanceof EntityPlayer && playerName.equals(((EntityPlayer) o).getCommandSenderName())) {
                    EntityPlayer p = (EntityPlayer) o;
                    out.add(new int[] { (int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ) });
                }
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not locate a player", t);
        }
        return out;
    }
}
