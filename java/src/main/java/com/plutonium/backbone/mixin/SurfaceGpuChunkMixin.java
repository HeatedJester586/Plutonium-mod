package com.plutonium.backbone.mixin;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

// Both @Inject methods removed: ChunkGenerator.buildSurface is abstract in Forge
// 1.20.1, so mixin cannot inject bytecode into it (no insnNode → NPE on apply).
// This class is also removed from plutonium.mixins.json so it's no longer loaded.
@Mixin(ChunkGenerator.class)
public abstract class SurfaceGpuChunkMixin {
}
