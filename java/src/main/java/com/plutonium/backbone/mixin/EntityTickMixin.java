package com.plutonium.backbone.mixin;

import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.client.PlutoniumCompositor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void plutonium$offloadAI(CallbackInfo ci) {
        long ptr = PlutoniumCompositor.getBackendPtr();
        if (ptr != 0) {
            Entity self = (Entity)(Object)this;
            // Send the current state to C++
            NativeInterface.nUpdateEntityLogic(
                ptr,
                self.getId(),
                (float)self.getX(), (float)self.getY(), (float)self.getZ(),
                self.getYRot(), self.getXRot(),
                (float)self.getDeltaMovement().x, (float)self.getDeltaMovement().y, (float)self.getDeltaMovement().z
            );
            // We let the native engine handle the 'heavy' logic.
            // For now, we don't cancel the tick yet so we don't break the game,
            // but we've successfully moved the data to the C++ side for processing.
        }
    }
}
