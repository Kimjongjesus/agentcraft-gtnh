package dev.agentcraft.gtnh;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

import dev.agentcraft.gtnh.block.BlockAgentCraft;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.client.AnchorOverlayRenderer;
import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.client.DevShots;
import dev.agentcraft.gtnh.client.GuiLibrary;
import dev.agentcraft.gtnh.client.GuiTaskWall;
import dev.agentcraft.gtnh.client.RenderAgentCraftTile;
import dev.agentcraft.gtnh.client.RenderHermesAgent;
import dev.agentcraft.gtnh.client.panels.GoalPanel;
import dev.agentcraft.gtnh.client.panels.KanbanPanel;
import dev.agentcraft.gtnh.client.panels.MonitorPanel;
import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.ui.panel.PanelLayout;
import dev.agentcraft.gtnh.ui.panel.PanelRegistry;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // card 4: modular in-world panels (type id -> renderer, source id -> data) + the layout file
        PanelRegistry.registerSource("board", BoardView::of);
        PanelRegistry.registerSource(
            "agent",
            b -> b == null || b.isEmpty() || "fleet".equals(b) ? null : ClientAgentCache.get(b));
        PanelRegistry.register(new KanbanPanel());
        PanelRegistry.register(new GoalPanel());
        PanelRegistry.register(new MonitorPanel());
        PanelLayout.init(Loader.instance()
            .getConfigDir());
        RenderingRegistry.registerEntityRenderingHandler(EntityHermesAgent.class, new RenderHermesAgent());
        RenderAgentCraftTile tiles = new RenderAgentCraftTile();
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Monitor.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Lamp.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Beacon.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.TaskWall.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Atrium.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.Library.class, tiles);
        MinecraftForge.EVENT_BUS.register(new AnchorOverlayRenderer());
        FMLCommonHandler.instance()
            .bus()
            .register(new Disconnect());
        DevShots.registerIfRequested();
    }

    @Override
    public void openHqScreen(BlockAgentCraft.Kind kind, String binding) {
        Minecraft.getMinecraft()
            .displayGuiScreen(kind == BlockAgentCraft.Kind.LIBRARY ? new GuiLibrary(binding, "") : new GuiTaskWall(binding, null));
    }

    /** Card 3: drop the task wall / library data of a server the player left. */
    public static final class Disconnect {

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            ClientHq.reset();
        }
    }
}
