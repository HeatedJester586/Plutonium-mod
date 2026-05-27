package com.plutonium.backbone.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public interface NoiseInterpolatorAccessor {
    @Accessor("noiseFiller")
    DensityFunction plutonium$getNoiseFiller();
}
