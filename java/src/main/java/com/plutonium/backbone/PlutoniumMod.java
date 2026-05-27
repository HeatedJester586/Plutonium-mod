package com.plutonium.backbone;

import com.plutonium.backbone.common.Config;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(PlutoniumMod.MODID)
public class PlutoniumMod {
    public static final String MODID = "plutonium";
    private static final Logger LOGGER = LogManager.getLogger();

    public PlutoniumMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(PlutoniumMod::onClientConfigLoad);
        LOGGER.info("[Plutonium] Mod initialized. DLL will be loaded on first backend activation.");
    }

    private static void onClientConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(MODID)
                && event.getConfig().getType() == ModConfig.Type.CLIENT) {
            logGpuWorldgenIfEnabled();
        }
    }

    private static void logGpuWorldgenIfEnabled() {
        if (Config.isGpuWorldgenEnabled()) {
            LOGGER.info("[Plutonium] GPU density assist is ON. Vanilla terrain generation still owns blocks, fluids, structures, features, and carvers.");
        } else if (Config.isGpuWorldgenRequested()) {
            LOGGER.warn("[Plutonium] GPU density assist was requested, but is held in vanilla-safe mode because unsafeGpuWorldgen=false.");
        }
    }
}
