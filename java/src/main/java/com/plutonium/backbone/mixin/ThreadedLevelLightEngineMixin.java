package com.plutonium.backbone.mixin;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Port of C2ME's c2me-threading-lighting MixinServerLightingProvider technique.
 *
 * Vanilla's ThreadedLevelLightEngine.runUpdate processes a fixed batch of at
 * most 1,000 queued light tasks per scheduled run, then sets scheduled=false
 * and stops — it relies on EXTERNAL callers (ChunkMap.tick, chunk loading,
 * block change events) to schedule another runUpdate. Under high load
 * (worldgen burst, big block edits, fly-fast new chunks) the light queue can
 * grow faster than those external triggers fire and lighting visibly lags.
 *
 * C2ME's fix: after each runUpdate finishes, immediately call
 * tryScheduleUpdate() again. If more work is queued, another runUpdate gets
 * scheduled right away on the same light worker. The worker self-pumps until
 * the queue is empty.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin {

    @Shadow public abstract void tryScheduleUpdate();

    @Inject(method = "runUpdate", at = @At("RETURN"))
    private void plutonium$pumpAgainOnReturn(CallbackInfo ci) {
        this.tryScheduleUpdate();
    }
}
