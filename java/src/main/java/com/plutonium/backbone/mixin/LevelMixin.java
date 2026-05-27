package com.plutonium.backbone.mixin;

import com.plutonium.backbone.client.DirtyChunkBatcher;
import com.plutonium.backbone.client.VoxelStreamer;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts every client-side block change. Two responsibilities:
 *   1. Legacy: notify the old VoxelStreamer for backends that still need it.
 *   2. v4: feed DirtyChunkBatcher so the native pipeline can re-mesh the
 *      affected chunk + neighbors. Injected at RETURN so the change is
 *      committed and cir.getReturnValue() reflects whether it actually happened.
 */
@Mixin(Level.class)
public class LevelMixin {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN")
    )
    private void plutonium$onBlockChange(BlockPos pos, BlockState state, int flags, int recursion,
                                        CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!level.isClientSide()) {
            // Server / integrated-server worldgen — not our hook target.
            return;
        }

        // Legacy backends (CUDA shadow world etc.) keep their old dirty path.
        if (VoxelStreamer.isEngineReady) {
            VoxelStreamer.markDirty(pos);
        }

        // v4 native pipeline: only feed the batcher if the block change actually
        // committed AND we're running the NATIVE chunk builder.
        if (cir.getReturnValue() != Boolean.TRUE) {
            return;
        }
        if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE) {
            return;
        }
        if (level instanceof ClientLevel clientLevel) {
            DirtyChunkBatcher.markBlockDirty(clientLevel, pos);
        }
    }
}
