package com.plutonium.backbone.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private fields the profiler reads (off the hot path, in dump()):
 * the visible-section list vanilla actually draws, and the ViewArea grid of all
 * in-range sections. Read-only.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("renderChunksInFrustum")
    ObjectArrayList<?> plutonium$getRenderChunksInFrustum();

    @Accessor("viewArea")
    ViewArea plutonium$getViewArea();
}
