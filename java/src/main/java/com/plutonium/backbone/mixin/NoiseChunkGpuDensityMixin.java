package com.plutonium.backbone.mixin;

import com.plutonium.backbone.common.Config;
import com.plutonium.backbone.worldgen.GpuDensityCellCache;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkGpuDensityMixin {

    @Shadow
    @Final
    int cellCountY;

    @Shadow
    @Final
    int cellNoiseMinY;

    @Shadow
    @Final
    int cellWidth;

    @Shadow
    @Final
    int cellHeight;

    @Shadow
    private int cellStartBlockX;

    @Shadow
    private int cellStartBlockZ;

    @Redirect(
            method = "fillSlice",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"
            )
    )
    private void plutonium$fillDensitySlice(NoiseChunk.NoiseInterpolator interpolator,
                                            double[] values,
                                            DensityFunction.ContextProvider provider) {
        if (!Config.isGpuWorldgenEnabled()) {
            interpolator.fillArray(values, provider);
            return;
        }

        DensityFunction noiseFiller =
                ((NoiseInterpolatorAccessor) (Object) interpolator).plutonium$getNoiseFiller();

        if (GpuDensityCellCache.tryFillFromGpu(
                noiseFiller, values, cellStartBlockX, cellStartBlockZ,
                cellWidth, cellHeight, cellCountY, cellNoiseMinY)) {
            return;
        }

        interpolator.fillArray(values, provider);
        GpuDensityCellCache.observeVanillaFill(
                noiseFiller, values, cellStartBlockX, cellStartBlockZ,
                cellWidth, cellHeight, cellCountY, cellNoiseMinY);
    }
}
