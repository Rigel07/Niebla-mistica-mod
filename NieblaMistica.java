package com.nieblamistica;

import com.nieblamistica.network.ModNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(NieblaMistica.MOD_ID)
public class NieblaMistica {
    public static final String MOD_ID = "nieblamistica";

    public NieblaMistica() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MistConfig.SPEC);
        // Los eventos del servidor (MysticMistManager) y del cliente (MistClientEvents)
        // se registran solos con @Mod.EventBusSubscriber.
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }
}
