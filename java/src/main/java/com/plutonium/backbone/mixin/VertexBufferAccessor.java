package com.plutonium.backbone.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code indexCount} so the profiler can estimate a section's VBO byte
 * size (terrain draws quads: vertices = indexCount / 6 * 4). Read-only.
 */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {

    @Accessor("indexCount")
    int plutonium$getIndexCount();
}
