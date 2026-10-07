package com.robertsnest.aifactory.telemetry.world;

import net.minecraft.block.Block;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import com.robertsnest.aifactory.AiFactoryMod;

/**
 * {@link BlockAccess} over a live {@link World}. Server thread only.
 *
 * <p>
 * {@code blockExists} delegates to the chunk provider's loaded-chunk map, not
 * to disk, so a false answer here never triggers a load or generation (checked
 * against the generated Forge 10.13.4.1614 source in this build).
 */
public final class WorldBlockAccess implements BlockAccess {

    private final World world;

    public WorldBlockAccess(World world) {
        if (world == null) {
            throw new IllegalArgumentException("world is required");
        }
        this.world = world;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        try {
            return world.blockExists(x, y, z);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Sample read(int x, int y, int z) {
        Block block;
        int meta;
        try {
            block = world.getBlock(x, y, z);
            if (block == null) {
                return null;
            }
            meta = world.getBlockMetadata(x, y, z);
        } catch (Throwable t) {
            return null;
        }
        boolean air;
        try {
            air = block.getMaterial() == net.minecraft.block.material.Material.air;
        } catch (Throwable t) {
            return null;
        }
        if (air) {
            return new Sample("minecraft:air#0", "Air", true);
        }
        String registry = registryName(block);
        // Metadata is part of the identity: wool colours, stone variants and
        // most modded casings share one registry name and differ only here.
        String id = registry + "#" + meta;
        return new Sample(id, displayName(block, meta, registry), false);
    }

    @Override
    public int blockLight(int x, int y, int z) {
        try {
            return world.getSavedLightValue(EnumSkyBlock.Block, x, y, z);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String registryName(Block block) {
        try {
            Object name = Block.blockRegistry.getNameForObject(block);
            if (name != null) {
                return name.toString();
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not resolve a block registry name", t);
        }
        return block.getClass()
            .getName();
    }

    private static String displayName(Block block, int meta, String fallback) {
        // Variant-aware first: the item form of a block localises per metadata
        // (e.g. "Blue Wool"); the block's own localized name is the generic
        // "Wool". Fall through in order and never throw.
        try {
            net.minecraft.item.Item item = net.minecraft.item.Item.getItemFromBlock(block);
            if (item != null) {
                net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item, 1, meta);
                String name = stack.getDisplayName();
                if (name != null && !name.trim()
                    .isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not localise a block variant", t);
        }
        try {
            String localized = block.getLocalizedName();
            if (localized != null && !localized.trim()
                .isEmpty()) {
                return localized;
            }
        } catch (Throwable t) {
            AiFactoryMod.LOG.debug("AI Factory could not localise a block name", t);
        }
        return fallback;
    }
}
