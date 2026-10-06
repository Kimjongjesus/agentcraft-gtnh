package dev.agentcraft.gtnh.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.ui.PlateDeclutter;

/**
 * Card 4: nameplates that never pile up. Recomputed whenever the camera or the world tick changes
 * (so at most once per frame), from the agent NPCs near the player:
 * <ul>
 * <li>agents standing on the same spot (closer than {@link #SAME_SPOT} blocks, e.g. when the server
 * found no free cell) collapse into ONE plate, the lowest entity id's, with a "+N here" count pill;
 * the others draw no plate, and the "!" marker shows how many of the group wait on Eli;</li>
 * <li>the remaining plates are laid out in screen space ({@link PlateDeclutter}): the plate under
 * the crosshair and then the nearest keep their spot, farther ones that would cover them shrink
 * (name + state, then name only) and/or rise by one plate height (up to {@link #TIERS} tiers), and
 * only when nothing fits does a plate shrink to its bare marker ("!" or status dot). Look at an
 * agent to read its full plate.</li>
 * </ul>
 */
public final class PlateLayout {

    public static final double SAME_SPOT = 0.75;
    public static final int TIERS = 4;
    /** World height of one plate tier (blocks). */
    public static final float TIER_STEP = 0.62F;

    /** Per entity: tier, plate mode (PlateDeclutter.FULL..DOT), hidden (no plate), group size, waiting in group. */
    public static final class Info {

        public int tier, mode, groupSize = 1, waiting;
        public boolean hidden;
    }

    /** Plate size in blocks for the layout. */
    public interface Sizer {

        /** @return {left[], right[], height[]}: one value per plate mode, in blocks */
        float[][] size(EntityHermesAgent e, Info i);

        /** Height of the plate's bottom above the entity's feet (blocks). */
        float base(EntityHermesAgent e);
    }

    private static long lastTick = Long.MIN_VALUE, freshTick = Long.MIN_VALUE;
    private static double lastCx = Double.NaN, lastCy, lastCz, lastYaw, lastPitch;
    private static Map<Integer, Info> info = new HashMap<>();
    private static final Info NONE = new Info();

    private PlateLayout() {}

    public static Info of(EntityHermesAgent e, Sizer sizer) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return NONE;
        long tick = mc.theWorld.getTotalWorldTime();
        RenderManager rm = RenderManager.instance;
        double cx = RenderManager.renderPosX, cy = RenderManager.renderPosY, cz = RenderManager.renderPosZ;
        if (tick != lastTick || cx != lastCx || cy != lastCy || cz != lastCz || rm.playerViewY != lastYaw || rm.playerViewX != lastPitch) {
            lastTick = tick;
            lastCx = cx;
            lastCy = cy;
            lastCz = cz;
            lastYaw = rm.playerViewY;
            lastPitch = rm.playerViewX;
            info = compute(mc, sizer, tick, cx, cy, cz, rm.playerViewY, rm.playerViewX, info);
        }
        Info i = info.get(e.getEntityId());
        return i == null ? NONE : i;
    }

    private static Map<Integer, Info> compute(Minecraft mc, Sizer sizer, long tickNow, double cx, double cy, double cz, double yaw,
        double pitch, Map<Integer, Info> prev) {
        List<EntityHermesAgent> npcs = new ArrayList<>();
        Entity me = mc.renderViewEntity;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (o instanceof EntityHermesAgent && (me == null || ((Entity) o).getDistanceSqToEntity(me) < 64 * 64)) npcs.add((EntityHermesAgent) o);
        }
        Collections.sort(npcs, (a, b) -> Integer.compare(a.getEntityId(), b.getEntityId()));
        Map<Integer, Info> out = new HashMap<>();
        List<EntityHermesAgent> leaders = new ArrayList<>();
        for (EntityHermesAgent e : npcs) {
            Info i = new Info();
            out.put(e.getEntityId(), i);
            EntityHermesAgent lead = null;
            for (EntityHermesAgent l : leaders) {
                if (flat(e, l) < SAME_SPOT && Math.abs(e.posY - l.posY) < 1.0) {
                    lead = l;
                    break;
                }
            }
            if (lead != null) {
                i.hidden = true;
                Info li = out.get(lead.getEntityId());
                li.groupSize++;
                if (waiting(e)) li.waiting++;
            } else {
                leaders.add(e);
                if (waiting(e)) i.waiting++;
            }
        }
        // screen-space layout of the visible plates; last frame's choices are kept for stability,
        // but forgotten every 2 s so plates grow back once there is room again
        boolean keep = tickNow - freshTick < 40;
        if (!keep) freshTick = tickNow;
        Entity looked = mc.objectMouseOver != null ? mc.objectMouseOver.entityHit : null;
        List<PlateDeclutter.Plate> plates = new ArrayList<>();
        for (EntityHermesAgent e : leaders) {
            Info i = out.get(e.getEntityId());
            float[][] s = sizer.size(e, i);
            PlateDeclutter.Plate p = new PlateDeclutter.Plate(
                e.getEntityId(),
                e.posX - cx,
                e.posY + sizer.base(e) - cy,
                e.posZ - cz,
                s[0],
                s[1],
                s[2]);
            p.priority = e == looked;
            Info old = prev.get(e.getEntityId());
            if (keep && old != null && !old.hidden) {
                p.prevTier = old.tier;
                p.prevMode = old.mode;
            }
            plates.add(p);
        }
        PlateDeclutter.layout(plates, yaw, pitch, TIER_STEP, TIERS);
        for (PlateDeclutter.Plate p : plates) {
            Info i = out.get(p.id);
            i.tier = p.tier;
            i.mode = p.mode;
        }
        return out;
    }

    private static boolean waiting(EntityHermesAgent e) {
        AgentInfo a = ClientAgentCache.forEntity(e.getEntityId());
        return a != null && a.waiting && ClientAgentCache.linkUp;
    }

    private static double flat(Entity a, Entity b) {
        double dx = a.posX - b.posX, dz = a.posZ - b.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
