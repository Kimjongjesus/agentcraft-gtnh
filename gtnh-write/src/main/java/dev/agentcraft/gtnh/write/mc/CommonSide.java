package dev.agentcraft.gtnh.write.mc;

import dev.agentcraft.gtnh.write.AgentCraftWrite;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/** Server side lifecycle (also the base of the client proxy). */
public class CommonSide {

    public void preInit(FMLPreInitializationEvent event) {
        WriteConfig.load(event.getSuggestedConfigurationFile());
        AgentCraftWrite.LOG.info("AgentCraft GTNH Write (channel {}, control {})", WriteNet.CHANNEL_NAME, WriteConfig.controlUrl);
    }

    public void init(FMLInitializationEvent event) {
        WriteNet.init();
        FMLCommonHandler.instance()
            .bus()
            .register(new WriteRuntime.Events());
    }

    public void serverStarting(FMLServerStartingEvent event) {
        WriteRuntime.start(event.getServer());
        event.registerServerCommand(new AskCommand());
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        WriteRuntime.stop();
    }
}
