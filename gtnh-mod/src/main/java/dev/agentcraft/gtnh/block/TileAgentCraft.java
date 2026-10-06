package dev.agentcraft.gtnh.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

import dev.agentcraft.gtnh.Config;

/**
 * Block entity of the monitor / status lamp / beacon blocks Eli places. Holds only a binding
 * (agent id, or "fleet") and, for monitors, the screen size; saved with the block, synced to clients
 * through the vanilla description packet. It never ticks: renderers read the client agent cache.
 */
public class TileAgentCraft extends TileEntity {

    public String binding = "";
    public int screenW = 3, screenH = 2;
    /** Card 6: an optional label (edit tool inspector) and a per-panel theme ("" = layout default). */
    public String label = "", theme = "";

    /** Card 6: set label and theme (sanitized by the caller, re-capped here). */
    public void setLook(String l, String th) {
        label = l == null ? "" : l.length() > 32 ? l.substring(0, 32) : l;
        theme = "dark".equals(th) || "light".equals(th) ? th : "";
        markDirty();
        if (worldObj != null) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void setBinding(String b, int w, int h) {
        binding = b == null ? "" : b;
        if (w > 0) screenW = Math.min(Math.max(w, 1), 8);
        if (h > 0) screenH = Math.min(Math.max(h, 1), 6);
        markDirty();
        if (worldObj != null) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeCustom(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readCustom(tag);
    }

    private void writeCustom(NBTTagCompound tag) {
        tag.setString("binding", binding);
        tag.setInteger("screenW", screenW);
        tag.setInteger("screenH", screenH);
        if (!label.isEmpty()) tag.setString("label", label);
        if (!theme.isEmpty()) tag.setString("theme", theme);
    }

    private void readCustom(NBTTagCompound tag) {
        binding = tag.getString("binding");
        if (binding.length() > 64) binding = binding.substring(0, 64);
        if (tag.hasKey("screenW")) screenW = Math.min(Math.max(tag.getInteger("screenW"), 1), 8);
        if (tag.hasKey("screenH")) screenH = Math.min(Math.max(tag.getInteger("screenH"), 1), 6);
        label = dev.agentcraft.gtnh.edit.PanelSpec.cleanLabel(tag.getString("label"));
        String th = tag.getString("theme");
        theme = "dark".equals(th) || "light".equals(th) ? th : "";
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeCustom(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readCustom(pkt.func_148857_g());
    }

    @Override
    public double getMaxRenderDistanceSquared() {
        double d = Config.monitorRenderDistance + 8;
        return d * d;
    }

    public static class Monitor extends TileAgentCraft {

        @Override
        public AxisAlignedBB getRenderBoundingBox() {
            int r = Math.max(screenW, screenH);
            return AxisAlignedBB.getBoundingBox(xCoord - r, yCoord - 1, zCoord - r, xCoord + r + 1, yCoord + r + 1, zCoord + r + 1);
        }
    }

    public static class Lamp extends TileAgentCraft {}

    /** Task wall: a W x H screen (default 5 x 3) bound to a board slug, or "" / "all" = every board. */
    public static class TaskWall extends TileAgentCraft {

        public TaskWall() {
            screenW = 5;
            screenH = 3;
        }

        @Override
        public AxisAlignedBB getRenderBoundingBox() {
            int r = Math.max(screenW, screenH);
            return AxisAlignedBB.getBoundingBox(xCoord - r, yCoord - 1, zCoord - r, xCoord + r + 1, yCoord + r + 1, zCoord + r + 1);
        }

        @Override
        public double getMaxRenderDistanceSquared() {
            double d = Config.wallRenderDistance + 8;
            return d * d;
        }
    }

    /** Goal atrium: a W x H progress-ring panel (default 3 x 3) bound to a board, or every board. */
    public static class Atrium extends TaskWall {

        public Atrium() {
            screenW = 3;
            screenH = 3;
        }
    }

    /** Library: no screen in the world; right-click opens the reader (binding = board filter). */
    public static class Library extends TileAgentCraft {}

    public static class Beacon extends TileAgentCraft {

        @Override
        public AxisAlignedBB getRenderBoundingBox() {
            return INFINITE_EXTENT_AABB;
        }

        @Override
        public double getMaxRenderDistanceSquared() {
            return 256.0D * 256.0D;
        }
    }
}
