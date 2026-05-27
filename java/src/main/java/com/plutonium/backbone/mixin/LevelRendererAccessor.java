package com.plutonium.backbone.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code renderChunksInFrustum} list so the solid-layer
 * replacement mixin can iterate the same set vanilla would have drawn.
 *
 * <p>Each entry is a {@code LevelRenderer$RenderChunkInfo} which holds a
 * {@code RenderChunk} reference — that's the key we use against the section
 * registry to find the mega-buffer slot.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("renderChunksInFrustum")
    ObjectArrayList<?> plutonium$getRenderChunksInFrustum();
}
