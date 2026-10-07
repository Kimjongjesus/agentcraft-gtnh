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
        // card 5b: ops panels (source "ops" = this client's ops view; the binding filters it)
        PanelRegistry.registerSource("ops", b -> dev.agentcraft.gtnh.state.ClientOps.view);
        PanelRegistry.register(new dev.agentcraft.gtnh.client.panels.OpsPanelRenderers.Fleet());
        PanelRegistry.register(new dev.agentcraft.gtnh.client.panels.OpsPanelRenderers.Cron());
        PanelRegistry.register(new dev.agentcraft.gtnh.client.panels.OpsPanelRenderers.Usage());
        PanelRegistry.register(new dev.agentcraft.gtnh.client.panels.OpsPanelRenderers.Alerts());
        dev.agentcraft.gtnh.edit.PanelTypes.registerChoices(dev.agentcraft.gtnh.edit.PanelType.OPS, new OpsChoices());
        dev.agentcraft.gtnh.client.DecisionToast.INSTANCE.init(Loader.instance()
            .getConfigDir());
        FMLCommonHandler.instance()
            .bus()
            .register(dev.agentcraft.gtnh.client.DecisionToast.INSTANCE);
        MinecraftForge.EVENT_BUS.register(dev.agentcraft.gtnh.client.DecisionToast.INSTANCE);
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
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.OpsFleet.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.OpsCron.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.OpsUsage.class, tiles);
        ClientRegistry.bindTileEntitySpecialRenderer(TileAgentCraft.OpsAlerts.class, tiles);
        MinecraftForge.EVENT_BUS.register(new AnchorOverlayRenderer());
        // card 6: edit-mode overlay (anchor markers, panel outline, crosshair card)
        dev.agentcraft.gtnh.client.edit.EditOverlay overlay = new dev.agentcraft.gtnh.client.edit.EditOverlay();
        MinecraftForge.EVENT_BUS.register(overlay);
        FMLCommonHandler.instance()
            .bus()
            .register(overlay);
        FMLCommonHandler.instance()
            .bus()
            .register(new Disconnect());
        DevShots.registerIfRequested();
    }

    @Override
    public void toastControl(String action) {
        // netty thread -> handled on the client thread (next client tick)
        dev.agentcraft.gtnh.client.DecisionToast.INSTANCE.controls.add(action);
    }

    /** Card 5b: binding choices the inspector shows for the ops panels (from this client's ops view). */
    static final class OpsChoices implements dev.agentcraft.gtnh.edit.PanelTypes.BindingChoices {

        @Override
        public String hint() {
            return "binds to an ops filter (or all)";
        }

        @Override
        public java.util.List<Object[]> choices() {
            java.util.List<Object[]> out = new java.util.ArrayList<>();
            out.add(new Object[] { "all", "Every ops source", 0xC9A227 });
            dev.agentcraft.gtnh.ops.OpsData.View v = dev.agentcraft.gtnh.state.ClientOps.view;
            java.util.Set<String> groups = new java.util.TreeSet<>(), sources = new java.util.TreeSet<>(), providers = new java.util.TreeSet<>();
            for (dev.agentcraft.gtnh.ops.OpsData.Service s : v.services) groups.add(s.group);
            for (dev.agentcraft.gtnh.ops.OpsData.Source s : v.sources) sources.add(s.id);
            for (dev.agentcraft.gtnh.ops.OpsData.Usage u : v.usage) providers.add(u.provider);
            for (String g : groups) if (!g.isEmpty()) out.add(new Object[] { g, "Group " + g, 0x2FA3A0 });
            for (String s : sources) if (!s.isEmpty()) out.add(new Object[] { s, "Source " + s, 0x7DA2F0 });
            for (String p : providers) if (!p.isEmpty()) out.add(new Object[] { p, "Provider " + p, 0x9B6FD6 });
            return out;
        }
    }

    @Override
    public void openHqScreen(BlockAgentCraft.Kind kind, String binding) {
        Minecraft.getMinecraft()
            .displayGuiScreen(kind == BlockAgentCraft.Kind.LIBRARY ? new GuiLibrary(binding, "") : new GuiTaskWall(binding, null));
    }

    @Override
    public void editToolUse(int[] hit, net.minecraft.entity.player.EntityPlayer player, boolean editMode) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!editMode) {
            player.addChatMessage(new net.minecraft.util.ChatComponentText("[AgentCraft] sneak + right-click to switch edit mode on"));
            return;
        }
        int[] mv = dev.agentcraft.gtnh.client.edit.ClientEdit.moving;
        if (mv != null) {
            moveTo(mv, hit, player);
            return;
        }
        String an = dev.agentcraft.gtnh.client.edit.ClientEdit.targetAnchor;
        if (an != null) {
            mc.displayGuiScreen(new dev.agentcraft.gtnh.client.edit.GuiEditor(dev.agentcraft.gtnh.client.edit.GuiEditor.ANCHORS, an, null, null));
            return;
        }
        int[] sign = hit != null && mc.theWorld.getTileEntity(hit[0], hit[1], hit[2]) instanceof net.minecraft.tileentity.TileEntitySign ? hit : null;
        mc.displayGuiScreen(new dev.agentcraft.gtnh.client.edit.GuiEditor(dev.agentcraft.gtnh.client.edit.GuiEditor.PALETTE, null, sign, null));
    }

    /** Move mode: the clicked face's neighbour cell, front turned towards the player. */
    private static void moveTo(int[] from, int[] hit, net.minecraft.entity.player.EntityPlayer player) {
        dev.agentcraft.gtnh.client.edit.ClientEdit.moving = null;
        if (hit == null) {
            player.addChatMessage(new net.minecraft.util.ChatComponentText("[AgentCraft] move cancelled"));
            return;
        }
        int x = hit[0], y = hit[1], z = hit[2];
        switch (hit[3]) {
            case 0: y--; break;
            case 1: y++; break;
            case 2: z--; break;
            case 3: z++; break;
            case 4: x--; break;
            case 5: x++; break;
            default:
        }
        int q = net.minecraft.util.MathHelper.floor_double(player.rotationYaw * 4.0F / 360.0F + 0.5D) & 3; // player faces 0 S, 1 W, 2 N, 3 E
        String facing = q == 0 ? "north" : q == 1 ? "east" : q == 2 ? "south" : "west";
        dev.agentcraft.gtnh.client.edit.ClientEdit.send("a", "panel.move", "pos", from[0] + "," + from[1] + "," + from[2], "to", x + "," + y + "," + z, "facing", facing);
    }

    @Override
    public void openInspector(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        int[] mv = dev.agentcraft.gtnh.client.edit.ClientEdit.moving;
        if (mv != null && mc.objectMouseOver != null) {
            moveTo(mv, new int[] { x, y, z, mc.objectMouseOver.sideHit }, mc.thePlayer);
            return;
        }
        mc.displayGuiScreen(new dev.agentcraft.gtnh.client.edit.GuiPanelInspector(x, y, z));
    }

    /** Card 3: drop the task wall / library data of a server the player left. */
    public static final class Disconnect {

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            ClientHq.reset();
            dev.agentcraft.gtnh.client.DecisionToast.INSTANCE.reset();
            dev.agentcraft.gtnh.client.edit.ClientEdit.reset();
            dev.agentcraft.gtnh.ui.panel.PanelLayout.serverDisplay("{}");
        }
    }
}
