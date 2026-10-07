package com.robertsnest.aifactory.telemetry.ae2;

import java.nio.charset.Charset;
import java.security.MessageDigest;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.robertsnest.aifactory.AiFactoryMod;
import com.robertsnest.aifactory.telemetry.FactorySnapshot;
import com.robertsnest.aifactory.telemetry.MeNetworkReader;
import com.robertsnest.aifactory.telemetry.StockCollector;
import com.robertsnest.aifactory.telemetry.WorkBudget;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;

/**
 * Reads ME-network contents through AE2's public API.
 *
 * <p>
 * <b>Every API member used here was verified with {@code javap} against the
 * exact jar GTNH 2.9.0-beta-3 ships</b>
 * ({@code appliedenergistics2-rv3-beta-1050-GTNH.jar}): {@code IGridHost
 * .getGridNode(ForgeDirection)}, {@code IGridNode.isActive()/getGrid()},
 * {@code IGrid.getCache(Class)}, {@code IStorageGrid.getItemInventory()
 * .getStorageList()}, {@code IEnergyGrid} power accessors and
 * {@code ICraftingGrid.getCpus()}.
 *
 * <p>
 * This class is the only place that touches AE2 storage types, so a server
 * without AE2 fails to link exactly this class; the caller catches that and
 * reports the section UNAVAILABLE.
 *
 * <p>
 * <b>Identity includes NBT.</b> AE2 keeps stacks with different NBT as
 * distinct rows. The ID here is {@code registry@damage} plus, when the stack
 * has a tag, a short hash of that tag's canonical string form. The private
 * NBT contents are never exposed; only the fact that two rows differ.
 *
 * <p>
 * Must be invoked on the server thread; it reads live tile-entity state.
 */
public final class Ae2NetworkReader implements MeNetworkReader {

    private final TileEntitySource source;

    /** Supplies the grid-host tile entity to read from. */
    public interface TileEntitySource {

        /** The tile entity acting as the network access point, or null. */
        TileEntity get();
    }

    public Ae2NetworkReader(TileEntitySource source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        this.source = source;
    }

    @Override
    public StockRead read(int cap, WorkBudget budget) {
        if (cap < 1 || budget == null) {
            return StockRead.error("bad_arguments");
        }
        TileEntity tile = source.get();
        if (tile == null) {
            return StockRead.unavailable("access_point_not_loaded");
        }
        if (!(tile instanceof IGridHost)) {
            return StockRead.unavailable("access_point_not_grid_host");
        }
        if (tile.isInvalid()) {
            return StockRead.unavailable("access_point_invalid");
        }
        IGrid grid;
        try {
            IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            if (node == null) {
                return StockRead.unavailable("no_grid_node");
            }
            if (!node.isActive()) {
                // Unpowered or still booting: contents are not trustworthy.
                return StockRead.unavailable("grid_node_inactive");
            }
            grid = node.getGrid();
        } catch (Throwable t) {
            AiFactoryMod.LOG.warn("AI Factory could not reach the ME grid", t);
            return StockRead.error("grid_lookup_failed");
        }
        if (grid == null) {
            return StockRead.unavailable("no_grid");
        }

        IStorageGrid storage;
        try {
            storage = grid.getCache(IStorageGrid.class);
        } catch (Throwable t) {
            return StockRead.error("storage_cache_failed");
        }
        if (storage == null) {
            return StockRead.unavailable("no_storage_cache");
        }

        IItemList<IAEItemStack> items;
        try {
            items = storage.getItemInventory()
                .getStorageList();
        } catch (Throwable t) {
            AiFactoryMod.LOG.warn("AI Factory could not read the ME storage list", t);
            return StockRead.error("storage_list_failed");
        }
        if (items == null) {
            return StockRead.error("storage_list_null");
        }

        StockCollector collector = new StockCollector(cap, budget);
        try {
            for (IAEItemStack entry : items) {
                if (!collector.admit()) {
                    break;
                }
                if (entry == null) {
                    collector.error();
                    continue;
                }
                // AE2 keeps zero-count rows for items it merely knows about;
                // those are not stock and are not counted as distinct.
                if (entry.getStackSize() <= 0L && !entry.isCraftable()) {
                    continue;
                }
                collector.offer(describe(entry));
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.warn("AI Factory ME traversal failed mid-way", t);
            return new StockRead(
                collector.result(),
                collector.coverage()
                    .toBuilder()
                    .status(com.robertsnest.aifactory.telemetry.Coverage.Status.ERROR)
                    .reason("traversal_failed")
                    .build());
        }

        Double stored = null;
        Double max = null;
        Double usage = null;
        Double injection = null;
        Boolean powered = null;
        try {
            IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
            if (energy != null) {
                stored = Double.valueOf(energy.getStoredPower());
                max = Double.valueOf(energy.getMaxStoredPower());
                usage = Double.valueOf(energy.getAvgPowerUsage());
                injection = Double.valueOf(energy.getAvgPowerInjection());
                powered = Boolean.valueOf(energy.isNetworkPowered());
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not read ME energy", t);
        }

        Integer cpus = null;
        Integer busy = null;
        try {
            ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
            if (crafting != null) {
                int total = 0;
                int busyCount = 0;
                for (ICraftingCPU cpu : crafting.getCpus()) {
                    total++;
                    if (cpu != null && cpu.isBusy()) {
                        busyCount++;
                    }
                    if (total >= 256) {
                        break;
                    }
                }
                cpus = Integer.valueOf(total);
                busy = Integer.valueOf(busyCount);
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not read ME crafting CPUs", t);
        }

        return new StockRead(
            collector.result(),
            collector.coverage(),
            stored,
            max,
            usage,
            injection,
            powered,
            cpus,
            busy);
    }

    private static FactorySnapshot.ItemStock describe(IAEItemStack entry) {
        try {
            ItemStack sample = entry.getItemStack();
            if (sample == null || sample.getItem() == null) {
                return null;
            }
            String registry = String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(sample.getItem()));
            String id = registry + "@" + entry.getItemDamage();
            if (entry.hasTagCompound()) {
                id = id + "#" + tagHash(sample);
            }
            String displayName;
            try {
                displayName = sample.getDisplayName();
            } catch (Throwable t) {
                displayName = id;
            }
            return new FactorySnapshot.ItemStock(
                id,
                displayName,
                Math.max(0L, entry.getStackSize()),
                entry.isCraftable());
        } catch (Throwable t) {
            AiFactoryMod.LOG.warn("AI Factory skipped an unreadable ME entry", t);
            return null;
        }
    }

    /** A short stable digest of the tag so variants differ without leaking contents. */
    static String tagHash(ItemStack stack) {
        try {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag == null) {
                return "nbt";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                tag.toString()
                    .getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", Integer.valueOf(digest[i] & 0xff)));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "nbt";
        }
    }
}
