package com.plutonium.backbone.mixin;

import com.plutonium.backbone.client.PlutoniumCompositor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void plutonium$redirectAI(CallbackInfo ci) {
        if (PlutoniumCompositor.getBackendPtr() != 0) {
            // CANCEL the Java AI calculation
            ci.cancel();
            // The C++ background threads will now handle this
            // via the nUpdateEntityLogic call we set up.
        }
    }
}
