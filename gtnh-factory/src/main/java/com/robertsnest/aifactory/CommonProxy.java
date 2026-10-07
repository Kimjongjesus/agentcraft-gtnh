package com.robertsnest.aifactory;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

public class CommonProxy {

    private AiFactoryConfig config;
    private AiFactoryRuntime runtime;

    public void preInit(FMLPreInitializationEvent event) {
        config = AiFactoryConfig.load(event.getSuggestedConfigurationFile(), event.getModConfigurationDirectory());
        AiFactoryMod.LOG.info(
            "AI Factory {} loaded in protocol mode {}",
            Tags.VERSION,
            com.robertsnest.aifactory.protocol.ProtocolEnvelope.currentVersion());
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    /**
     * Build a runtime bound to this server instance.
     *
     * <p>
     * If a previous runtime is somehow still alive (a crash skipped both
     * stop events), it is stopped first so its listener and tick handler
     * cannot outlive their server.
     */
    public void serverStarting(FMLServerStartingEvent event) {
        if (runtime != null && !runtime.stopped()) {
            AiFactoryMod.LOG.warn("AI Factory: stale runtime found at server start; stopping it first.");
            runtime.stop();
        }
        runtime = new AiFactoryRuntime(config, event.getServer());
        runtime.start();
    }

    /** Normal shutdown path. */
    public void serverStopping(FMLServerStoppingEvent event) {
        stopRuntime();
    }

    /**
     * Final cleanup path. Forge fires this even when the server loop exited
     * abnormally and {@code serverStopping} never ran, so the listener socket
     * is released on a crash too.
     */
    public void serverStopped(FMLServerStoppedEvent event) {
        stopRuntime();
    }

    private void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
            runtime = null;
        }
    }
}
