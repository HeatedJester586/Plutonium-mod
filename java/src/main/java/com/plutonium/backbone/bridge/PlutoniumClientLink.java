package com.plutonium.backbone.bridge;

import com.plutonium.backbone.client.PlutoniumCompositor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "plutonium", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class PlutoniumClientLink {

    /** @deprecated Use {@link PlutoniumCompositor#getBackendPtr()} — kept for external tooling if needed */
    @Deprecated
    public static long backendPtr      = 0;
    public static long shadowWorldAddr = 0;
    public static int  simWidth        = 0;
    public static int  simHeight       = 0;

    private PlutoniumClientLink() {}
}