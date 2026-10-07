package com.robertsnest.aifactory;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;

/**
 * Mod entry point.
 *
 * <p>
 * <b>Server-only compatibility is declared explicitly (H2).</b> Without a
 * {@link NetworkCheckHandler}, Forge's default check rejects any client that
 * does not carry this mod. This mod adds no blocks, items, packets or GUI, so
 * a matching GTNH client without it must be able to join; and a client that
 * does carry it (for the optional overlay) must be able to join a server
 * without it. The handler therefore accepts every remote regardless of the
 * mod list.
 */
@Mod(modid = AiFactoryMod.MODID, version = Tags.VERSION, name = "AI Factory", acceptedMinecraftVersions = "[1.7.10]")
public class AiFactoryMod {

    public static final String MODID = "aifactory";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "com.robertsnest.aifactory.ClientProxy",
        serverSide = "com.robertsnest.aifactory.CommonProxy")
    public static CommonProxy proxy;

    /**
     * Accept every remote side. The mod has no networked game behaviour, so
     * neither its presence nor its version on the other end matters.
     */
    @NetworkCheckHandler
    public boolean networkCheck(Map<String, String> mods, Side remoteSide) {
        return true;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }
}
