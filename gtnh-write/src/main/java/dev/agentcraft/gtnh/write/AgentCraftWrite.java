package dev.agentcraft.gtnh.write;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.agentcraft.gtnh.write.mc.CommonSide;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * AgentCraft GTNH Write: the opt-in write path (docs/write-path.md, docs/action-protocol.md). A separate
 * jar that needs the core mod; without it the office stays read-only. It arms only on a dedicated,
 * online-mode, owner-only whitelisted server, talks to one control service over signed frames, audits
 * every request and obeys a one-click lock. See gtnh-write/README.md.
 */
@Mod(
    modid = AgentCraftWrite.MODID,
    version = Tags.VERSION,
    name = "AgentCraft GTNH Write",
    dependencies = "required-after:agentcraftgtnh",
    acceptedMinecraftVersions = "[1.7.10]")
public class AgentCraftWrite {

    public static final String MODID = "agentcraftgtnhwrite";
    public static final Logger LOG = LogManager.getLogger("AgentCraftWrite");

    @SidedProxy(clientSide = "dev.agentcraft.gtnh.write.client.ClientSide", serverSide = "dev.agentcraft.gtnh.write.mc.CommonSide")
    public static CommonSide proxy;

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
