package com.plutonium.backbone.client;

import com.plutonium.backbone.PlutoniumMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

/**
 * Kept as a stub so the class still compiles cleanly.
 * All rendering is handled by WorldCompositeEventHandler + PlutoniumCompositor.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, value = Dist.CLIENT)
public final class PlutoniumRenderer {
    private PlutoniumRenderer() {}
}