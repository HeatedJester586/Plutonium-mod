package com.plutonium.backbone.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.plutonium.backbone.client.TerrainProfiler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only timing around vanilla's per-layer terrain draw. Each frame vanilla
 * calls renderChunkLayer once per terrain layer (solid, cutoutMipped, cutout,
 * translucent, tripwire); the profiler sums them into the frame's CPU + GPU
 * terrain totals. Does NOT modify rendering — HEAD/RETURN injects only.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTerrainTimingMixin {

    @Inject(
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            at = @At("HEAD"))
    private void plutonium$terrainLayerStart(
            RenderType renderType, PoseStack poseStack,
            double camX, double camY, double camZ, Matrix4f projection, CallbackInfo ci) {
        if (TerrainProfiler.ENABLED) {
            TerrainProfiler.onLayerStart(renderType);
        }
    }

    @Inject(
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            at = @At("RETURN"))
    private void plutonium$terrainLayerEnd(
            RenderType renderType, PoseStack poseStack,
            double camX, double camY, double camZ, Matrix4f projection, CallbackInfo ci) {
        // onLayerEnd self-guards (it must close an already-open query even if the
        // toggle flipped mid-layer), so no ENABLED check here.
        TerrainProfiler.onLayerEnd(renderType);
    }
}
