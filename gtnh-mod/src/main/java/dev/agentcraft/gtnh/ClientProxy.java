package dev.agentcraft.gtnh;

import net.minecraftforge.common.MinecraftForge;

import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.client.AnchorOverlayRenderer;
import dev.agentcraft.gtnh.client.DevShots;
import dev.agentcraft.gtnh.client.RenderAgentCraftTile;
import dev.agentcraft.gtnh.client.RenderHermesAgent;
import dev.agentcraft.gtnh.entity.EntityHermesAgent;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        RenderingRegistry.registerEntityRenderingHandler(EntityHermesAgent.class, new RenderHermesAgent());
        RenderAgentCraftTile tiles = new RenderAgentCraftTile();
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Monitor.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Lamp.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Beacon.class, tiles);
        MinecraftForge.EVENT_BUS.register(new AnchorOverlayRenderer());
        DevShots.registerIfRequested();
    }
}
