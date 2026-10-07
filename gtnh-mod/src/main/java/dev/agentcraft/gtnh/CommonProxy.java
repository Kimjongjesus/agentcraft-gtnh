package dev.agentcraft.gtnh;

import dev.agentcraft.gtnh.block.BlockAgentCraft;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.bridge.ForemanBridge;
import dev.agentcraft.gtnh.command.CommandAgentCraft;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.item.ItemEditTool;
import dev.agentcraft.gtnh.net.Net;
import dev.agentcraft.gtnh.ops.OpsPanels;
import dev.agentcraft.gtnh.server.AgentWorldSync;
import dev.agentcraft.gtnh.server.EditService;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

public class CommonProxy {

    public static ForemanBridge bridge;
    public static AgentWorldSync sync;
    public static BlockAgentCraft monitor, lamp, beacon, taskWall, library, atrium;
    /** Card 5b: the four ops panels. */
    public static BlockAgentCraft fleetBoard, cronBoard, usagePanel, alertFeed;
    public static ItemEditTool editTool;

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        AgentCraftGTNH.LOG.info("AgentCraft GTNH {} (adapter {})", Tags.VERSION, Config.adapterUrl);
        // placeable HQ blocks (the player puts them where they build; the mod never places blocks itself)
        monitor = new BlockAgentCraft(BlockAgentCraft.Kind.MONITOR);
        lamp = new BlockAgentCraft(BlockAgentCraft.Kind.LAMP);
        beacon = new BlockAgentCraft(BlockAgentCraft.Kind.BEACON);
        taskWall = new BlockAgentCraft(BlockAgentCraft.Kind.TASKWALL);
        library = new BlockAgentCraft(BlockAgentCraft.Kind.LIBRARY);
        atrium = new BlockAgentCraft(BlockAgentCraft.Kind.ATRIUM);
        GameRegistry.registerBlock(monitor, "monitor");
        GameRegistry.registerBlock(lamp, "status_lamp");
        GameRegistry.registerBlock(beacon, "fleet_beacon");
        GameRegistry.registerBlock(taskWall, "task_wall");
        GameRegistry.registerBlock(library, "library");
        GameRegistry.registerBlock(atrium, "goal_atrium");
        GameRegistry.registerTileEntity(TileAgentCraft.Monitor.class, AgentCraftGTNH.MODID + ".monitor");
        GameRegistry.registerTileEntity(TileAgentCraft.Lamp.class, AgentCraftGTNH.MODID + ".status_lamp");
        GameRegistry.registerTileEntity(TileAgentCraft.Beacon.class, AgentCraftGTNH.MODID + ".fleet_beacon");
        GameRegistry.registerTileEntity(TileAgentCraft.TaskWall.class, AgentCraftGTNH.MODID + ".task_wall");
        GameRegistry.registerTileEntity(TileAgentCraft.Library.class, AgentCraftGTNH.MODID + ".library");
        GameRegistry.registerTileEntity(TileAgentCraft.Atrium.class, AgentCraftGTNH.MODID + ".goal_atrium");
        // card 6: the office edit tool + the panel kinds it may place/remove (this mod's blocks only)
        editTool = new ItemEditTool();
        GameRegistry.registerItem(editTool, "edit_tool");
        PanelTypes.registerBuiltins();
        EditService.registerBlock("monitor", monitor);
        EditService.registerBlock("status_lamp", lamp);
        EditService.registerBlock("fleet_beacon", beacon);
        EditService.registerBlock("task_wall", taskWall);
        EditService.registerBlock("library", library);
        EditService.registerBlock("goal_atrium", atrium);
        // card 5b: ops panels (fleet board, cron board, usage panel, alert feed), registered like the
        // card 2-4 blocks so the edit tool places, inspects and rebinds them with no editor changes
        fleetBoard = new BlockAgentCraft(BlockAgentCraft.Kind.FLEETBOARD);
        cronBoard = new BlockAgentCraft(BlockAgentCraft.Kind.CRONBOARD);
        usagePanel = new BlockAgentCraft(BlockAgentCraft.Kind.USAGE);
        alertFeed = new BlockAgentCraft(BlockAgentCraft.Kind.ALERTS);
        GameRegistry.registerBlock(fleetBoard, OpsPanels.FLEET);
        GameRegistry.registerBlock(cronBoard, OpsPanels.CRON);
        GameRegistry.registerBlock(usagePanel, OpsPanels.USAGE);
        GameRegistry.registerBlock(alertFeed, OpsPanels.ALERTS);
        GameRegistry.registerTileEntity(TileAgentCraft.OpsFleet.class, AgentCraftGTNH.MODID + "." + OpsPanels.FLEET);
        GameRegistry.registerTileEntity(TileAgentCraft.OpsCron.class, AgentCraftGTNH.MODID + "." + OpsPanels.CRON);
        GameRegistry.registerTileEntity(TileAgentCraft.OpsUsage.class, AgentCraftGTNH.MODID + "." + OpsPanels.USAGE);
        GameRegistry.registerTileEntity(TileAgentCraft.OpsAlerts.class, AgentCraftGTNH.MODID + "." + OpsPanels.ALERTS);
        OpsPanels.registerTypes();
        EditService.registerBlock(OpsPanels.FLEET, fleetBoard);
        EditService.registerBlock(OpsPanels.CRON, cronBoard);
        EditService.registerBlock(OpsPanels.USAGE, usagePanel);
        EditService.registerBlock(OpsPanels.ALERTS, alertFeed);
    }

    /** Client only (ClientProxy): /agentcraft toast mute|unmute|test|status for this player's client. */
    public void toastControl(String action) {}

    /** Client only (ClientProxy): edit tool right-click (hit = x, y, z, side of the block, or null for air). */
    public void editToolUse(int[] hit, net.minecraft.entity.player.EntityPlayer player, boolean editMode) {}

    /** Client only (ClientProxy): the edit tool's Panel Inspector for the panel at x, y, z. */
    public void openInspector(int x, int y, int z) {}

    /** Client only (ClientProxy): open the read-only task wall / library / atrium screen. */
    public void openHqScreen(BlockAgentCraft.Kind kind, String binding) {}

    public void init(FMLInitializationEvent event) {
        // 1.7.10 gotcha: registerModEntity with a tracking range/frequency, or clients never see it
        EntityRegistry.registerModEntity(
            EntityHermesAgent.class,
            "hermes_agent",
            0,
            AgentCraftGTNH.instance,
            80, // tracking range (blocks)
            3, // update frequency (ticks)
            true); // send velocity updates (walking NPCs)
        Net.init();
        sync = new AgentWorldSync();
        FMLCommonHandler.instance()
            .bus()
            .register(sync);
        EditService.instance = new EditService();
        FMLCommonHandler.instance()
            .bus()
            .register(EditService.instance);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(EditService.instance);
    }

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandAgentCraft());
        sync.reset();
        EditService.instance.start(event.getServer());
        // dev/QA only (screenshots): -Dagentcraft.dev.noon freezes the overworld at noon
        if (System.getProperty("agentcraft.dev.noon") != null) {
            net.minecraft.world.WorldServer w = event.getServer()
                .worldServerForDimension(0);
            w.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            w.setWorldTime(6000L);
            AgentCraftGTNH.LOG.info("dev: overworld frozen at noon");
        }
        if (Config.enabled) {
            bridge = new ForemanBridge(Config.adapterUrl, "gtnh-mod " + Tags.VERSION);
            bridge.start();
        } else {
            AgentCraftGTNH.LOG.info("bridge disabled in config");
        }
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        sync.despawnAll();
        EditService.instance.stop();
    }
}
