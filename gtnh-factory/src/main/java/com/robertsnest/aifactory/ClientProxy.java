package com.robertsnest.aifactory;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        com.robertsnest.aifactory.client.OracleClient.install(event.getModConfigurationDirectory());
    }
}
