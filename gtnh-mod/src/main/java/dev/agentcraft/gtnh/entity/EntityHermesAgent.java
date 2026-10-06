package dev.agentcraft.gtnh.entity;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.state.AgentInfo;

/**
 * A Hermes agent in the world. Server-spawned, never saved with the chunk (the bridge re-creates it
 * from adapter state, so a restart can never duplicate it), invulnerable, not pushable, never
 * despawns, picks nothing up, drops nothing, triggers no blocks it walks over and makes no
 * walking sounds.
 *
 * <p>
 * Walking: {@link #walkTo} sets the spot of its station; vanilla pathfinding (PathNavigate over the
 * real blocks, no AI tasks) walks there. No path, no progress for {@code teleportAfterSeconds}, a
 * fall below the spot or a target more than 48 blocks away -> it is put on the spot. On arrival it
 * snaps to the spot and turns to the anchor's facing; a waiting agent turns to face the nearest player.
 *
 * <p>
 * Outfit = role: dyed leather in the agent colour; the lead wears a gold helmet, the scheduler an
 * iron one, reviewers chainmail. DataWatcher 20..23 carry id, state, activity, status colour as a
 * fallback for the SimpleNetworkWrapper sync.
 */
public class EntityHermesAgent extends EntityLiving {

    public static final int DW_ID = 20;
    public static final int DW_STATE = 21;
    public static final int DW_ACTIVITY = 22;
    public static final int DW_COLOR = 23;

    private double tx, ty, tz;
    private float tyaw;
    private boolean hasTarget, arrived;
    private int walkTicks, stuckTicks;
    private double bestDist = Double.MAX_VALUE;
    private boolean waiting;
    private String outfitKey = "";
    public int teleports, walks;

    public EntityHermesAgent(World world) {
        super(world);
        setSize(0.6F, 1.8F);
        this.isImmuneToFire = true;
        for (int i = 0; i < equipmentDropChances.length; i++) equipmentDropChances[i] = 0.0F;
        setCanPickUpLoot(false);
        getNavigator().setAvoidsWater(true);
        getNavigator().setBreakDoors(false);
        getNavigator().setCanSwim(true);
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        dataWatcher.addObject(DW_ID, "");
        dataWatcher.addObject(DW_STATE, "idle");
        dataWatcher.addObject(DW_ACTIVITY, "");
        dataWatcher.addObject(DW_COLOR, Integer.valueOf(0x9C9488));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(Config.walkSpeed);
        getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(48.0D);
    }

    /** Vanilla AI loop on (navigator, move/look/jump helpers) but with no AI tasks: we drive it. */
    @Override
    public boolean isAIEnabled() {
        return true;
    }

    public String getAgentId() {
        return dataWatcher.getWatchableObjectString(DW_ID);
    }

    public String getAgentState() {
        return dataWatcher.getWatchableObjectString(DW_STATE);
    }

    public String getAgentActivity() {
        return dataWatcher.getWatchableObjectString(DW_ACTIVITY);
    }

    public int getAgentColor() {
        return dataWatcher.getWatchableObjectInt(DW_COLOR);
    }

    public boolean isWalking() {
        return hasTarget && !arrived;
    }

    public double targetX() {
        return tx;
    }

    public double targetY() {
        return ty;
    }

    public double targetZ() {
        return tz;
    }

    /** Server side: push an agent's view into the entity (DataWatcher, outfit, vanilla name tag fallback). */
    public void applyInfo(AgentInfo a) {
        String family = a.family();
        waiting = a.waiting;
        dataWatcher.updateObject(DW_ID, a.id);
        dataWatcher.updateObject(DW_STATE, clip(a.state, 32));
        dataWatcher.updateObject(DW_ACTIVITY, clip(a.activity, 48));
        dataWatcher.updateObject(DW_COLOR, Integer.valueOf(a.color));
        setCustomNameTag(clip(a.name, 24) + " " + AgentInfo.familyChat(family) + "[" + a.state.replace('_', ' ') + "]");
        setAlwaysRenderNameTag(true);
        String key = a.role + "|" + a.id + "|" + a.color;
        if (!key.equals(outfitKey)) {
            outfitKey = key;
            dress(a);
        }
    }

    private void dress(AgentInfo a) {
        Item helmet;
        if ("lead".equals(a.role)) helmet = Items.golden_helmet;
        else if ("cron".equals(a.id)) helmet = Items.iron_helmet;
        else if (a.id.contains("review")) helmet = Items.chainmail_helmet;
        else helmet = Items.leather_helmet;
        setCurrentItemOrArmor(4, dyed(new ItemStack(helmet), a.color));
        setCurrentItemOrArmor(3, dyed(new ItemStack(Items.leather_chestplate), a.color));
        setCurrentItemOrArmor(2, dyed(new ItemStack(Items.leather_leggings), darker(a.color)));
        setCurrentItemOrArmor(1, dyed(new ItemStack(Items.leather_boots), 0x3B2F2A));
    }

    /** Leather colour lives in the stack's display.color NBT (what ItemArmor#func_82813_b writes). */
    private static ItemStack dyed(ItemStack s, int rgb) {
        if (s.getItem() != Items.leather_helmet && s.getItem() != Items.leather_chestplate
            && s.getItem() != Items.leather_leggings
            && s.getItem() != Items.leather_boots) return s;
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", rgb & 0xFFFFFF);
        tag.setTag("display", display);
        s.setTagCompound(tag);
        return s;
    }

    private static int darker(int rgb) {
        int r = ((rgb >> 16) & 0xFF) * 2 / 3, g = ((rgb >> 8) & 0xFF) * 2 / 3, b = (rgb & 0xFF) * 2 / 3;
        return (r << 16) | (g << 8) | b;
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Puts the NPC on a spot immediately (spawn, no path, stuck, void). */
    public void placeAt(double x, double y, double z, float yaw) {
        tx = x;
        ty = y;
        tz = z;
        tyaw = yaw;
        hasTarget = true;
        arrived = true;
        getNavigator().clearPathEntity();
        setLocationAndAngles(x, y, z, yaw, 0.0F);
        setPositionAndUpdate(x, y, z);
        rotationYawHead = renderYawOffset = yaw;
        motionX = motionY = motionZ = 0;
    }

    /** New station spot: walk there with vanilla pathfinding (or teleport if that cannot work). */
    public void walkTo(double x, double y, double z, float yaw) {
        boolean same = hasTarget && Math.abs(x - tx) < 0.01 && Math.abs(y - ty) < 0.01 && Math.abs(z - tz) < 0.01;
        tyaw = yaw;
        if (same) return;
        tx = x;
        ty = y;
        tz = z;
        hasTarget = true;
        arrived = false;
        walkTicks = 0;
        stuckTicks = 0;
        bestDist = Double.MAX_VALUE;
        if (getDistanceSq(x, y, z) > 48 * 48 || !getNavigator().tryMoveToXYZ(x, y, z, 1.0D)) {
            teleport("no path");
        } else {
            walks++;
        }
    }

    private void teleport(String why) {
        teleports++;
        if (Config.verboseLog) {
            AgentCraftGTNH.LOG.info(
                "NPC {} put on its spot {} {} {} ({})",
                getAgentId(),
                String.format("%.1f", tx),
                String.format("%.1f", ty),
                String.format("%.1f", tz),
                why);
        }
        placeAt(tx, ty, tz, tyaw);
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        fallDistance = 0.0F; // never tramples farmland or takes "fall" logic anywhere
        if (worldObj.isRemote || !hasTarget) return;
        double d = getDistanceSq(tx, ty, tz);
        // never lost in the void, never wandering off: a drop below the spot puts it back
        if (posY < 1.0D || posY < ty - 6.0D || d > 64 * 64) {
            teleport("fell or wandered");
            return;
        }
        if (!arrived) {
            walkTicks++;
            if (d < bestDist - 0.25D) {
                bestDist = d;
                stuckTicks = 0;
            } else {
                stuckTicks++;
            }
            if (d < 0.5D * 0.5D || (getNavigator().noPath() && d < 1.2D * 1.2D)) {
                arrived = true;
                getNavigator().clearPathEntity();
                setPositionAndUpdate(tx, ty, tz);
                rotationYaw = renderYawOffset = rotationYawHead = tyaw;
                if (Config.verboseLog) {
                    AgentCraftGTNH.LOG.info("NPC {} arrived after {} ticks", getAgentId(), walkTicks);
                }
            } else if (getNavigator().noPath() && walkTicks % 20 == 0) {
                if (!getNavigator().tryMoveToXYZ(tx, ty, tz, 1.0D)) teleport("path lost");
            } else if (stuckTicks > Config.teleportAfterSeconds * 20 || walkTicks > Config.teleportAfterSeconds * 20 * 4) {
                teleport("stuck");
            }
            return;
        }
        // standing on the spot: stay there (nudged off by players -> step back)
        if (d > 0.6D * 0.6D) {
            setPositionAndUpdate(tx, ty, tz);
        }
        motionX = motionZ = 0;
        float yaw = tyaw;
        if (waiting && ticksExisted % 5 == 0) {
            EntityPlayer p = worldObj.getClosestPlayerToEntity(this, 12.0D);
            if (p != null) {
                yaw = (float) (Math.atan2(p.posZ - posZ, p.posX - posX) * 180.0D / Math.PI) - 90.0F;
                getLookHelper().setLookPositionWithEntity(p, 30.0F, 30.0F);
            }
        } else if (!waiting && ticksExisted % 5 == 0) {
            EntityPlayer p = worldObj.getClosestPlayerToEntity(this, 4.0D);
            if (p != null) getLookHelper().setLookPositionWithEntity(p, 20.0F, 20.0F); // a glance, body stays put
        }
        rotationYaw = renderYawOffset = MathHelper.wrapAngleTo180_float(yaw);
    }

    /** No vanilla idle behaviour: movement and facing are handled in onLivingUpdate. */
    @Override
    protected void updateEntityActionState() {}

    @Override
    protected boolean canDespawn() {
        return false;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isEntityInvulnerable() {
        return true;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    protected void collideWithEntity(net.minecraft.entity.Entity e) {}

    @Override
    public boolean allowLeashing() {
        return false;
    }

    /** No step sounds and no Block#onEntityWalking (redstone ore, farmland...): walks without side effects. */
    @Override
    protected boolean canTriggerWalking() {
        return false;
    }

    @Override
    protected String getLivingSound() {
        return null;
    }

    @Override
    protected void dropEquipment(boolean recentlyHit, int looting) {}

    @Override
    protected void dropFewItems(boolean recentlyHit, int looting) {}

    /** Never persisted: the bridge owns these entities and recreates them after a restart. */
    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;
    }

    @Override
    public boolean writeMountToNBT(NBTTagCompound tag) {
        return false;
    }
}
