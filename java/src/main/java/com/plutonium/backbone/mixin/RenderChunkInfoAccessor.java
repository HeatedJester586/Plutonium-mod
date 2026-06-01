package com.plutonium.backbone.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the package-private {@code chunk} field of LevelRenderer$RenderChunkInfo
 * so the profiler can reach the RenderChunk (and its compiled buffers) for each
 * visible section when estimating drawn-section VBO bytes.
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo")
public interface RenderChunkInfoAccessor {

    @Accessor("chunk")
    ChunkRenderDispatcher.RenderChunk plutonium$getChunk();
}
