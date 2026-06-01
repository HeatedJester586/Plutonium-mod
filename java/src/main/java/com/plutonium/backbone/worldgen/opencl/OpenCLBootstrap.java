package com.plutonium.backbone.worldgen.opencl;

import com.plutonium.backbone.PlutoniumMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Runs the {@link OpenCLProbe} once during mod setup so the log shows which
 * OpenCL devices are available and whether a kernel runs — the M1 acceptance
 * check for the OpenCL worldgen port.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OpenCLBootstrap {

    private OpenCLBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(OpenCLProbe::probe);
    }
}
