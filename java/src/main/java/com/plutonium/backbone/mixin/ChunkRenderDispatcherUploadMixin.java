package com.plutonium.backbone.mixin;

import com.plutonium.backbone.client.TerrainProfiler;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Counts how many section uploads run this frame, for the upload/stutter
 * correlation. uploadAllPendingUploads() is called once per frame from
 * compileChunks(); the size of the pending queue at HEAD is the upload count.
 * Read-only — does not touch the uploads.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class ChunkRenderDispatcherUploadMixin {

    @Shadow
    @Final
    private Queue<Runnable> toUpload;

    @Inject(method = "uploadAllPendingUploads()V", at = @At("HEAD"))
    private void plutonium$countUploads(CallbackInfo ci) {
        if (TerrainProfiler.ENABLED) {
            TerrainProfiler.recordUploads(this.toUpload.size());
        }
    }
}
