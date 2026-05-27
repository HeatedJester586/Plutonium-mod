package com.plutonium.backbone.mixin;

import com.plutonium.backbone.common.PlutoniumWorkQueues;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @ModifyArg(method = "readChunk", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"), index = 1)
    private Executor plutonium$useDedicatedChunkIoExecutor(Executor original) {
        return PlutoniumWorkQueues.chunkIoExecutor();
    }
}
