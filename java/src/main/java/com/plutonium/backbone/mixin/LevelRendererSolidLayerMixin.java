package com.plutonium.backbone.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.plutonium.backbone.client.NativeChunkRenderer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's solid chunk layer only after native column coverage is high
 * enough to avoid holes during warmup. Other layers stay vanilla for v1.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSolidLayerMixin {

    @Inject(
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void plutonium$replaceLayerWithNative(
            RenderType renderType,
            PoseStack poseStack,
            double camX,
            double camY,
            double camZ,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        if (renderType != RenderType.solid()) {
            return;
        }

        ObjectArrayList<?> visibleChunks = ((LevelRendererAccessor) (Object) this).plutonium$getRenderChunksInFrustum();
        if (NativeChunkRenderer.renderSolidLayer(poseStack, camX, camY, camZ, projectionMatrix, visibleChunks)) {
            ci.cancel();
        }
    }
}
