package com.plutonium.backbone.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo")
public interface RenderChunkInfoAccessor {
    @Accessor("chunk")
    ChunkRenderDispatcher.RenderChunk plutonium$getChunk();
}
