package dev.agentcraft.gtnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * AgentCraft for GTNH (Forge 1.7.10): a read-only, in-world view of Hermes agents.
 *
 * <p>
 * The dedicated (or integrated) server connects to the Hermes adapter over the AgentCraft
 * WebSocket protocol, spawns an NPC per displayed agent and replicates agent state to clients
 * with a SimpleNetworkWrapper channel; the client renders the NPC with a two-line nameplate.
 * GTNH-native reimplementation of the idea of blendi-remade/agentcraft (MIT), not a port of its code.
 */
@Mod(
    modid = AgentCraftGTNH.MODID,
    version = Tags.VERSION,
    name = "AgentCraft GTNH",
    acceptedMinecraftVersions = "[1.7.10]")
public class AgentCraftGTNH {

    public static final String MODID = "agentcraftgtnh";
    public static final Logger LOG = LogManager.getLogger("AgentCraft");

    @Mod.Instance(MODID)
    public static AgentCraftGTNH instance;

    @SidedProxy(clientSide = "dev.agentcraft.gtnh.ClientProxy", serverSide = "dev.agentcraft.gtnh.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }
}
