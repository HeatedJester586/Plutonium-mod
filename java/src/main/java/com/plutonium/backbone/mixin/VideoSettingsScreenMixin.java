package com.plutonium.backbone.mixin;

import com.plutonium.backbone.client.PlutoniumConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Plutonium…" button to the Video Settings screen that opens the
 * Plutonium config screen. Sits next to the existing Done button.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {

    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void plutonium$addConfigButton(CallbackInfo ci) {
        VideoSettingsScreen self = (VideoSettingsScreen) (Object) this;
        addRenderableWidget(Button.builder(
                Component.literal("Plutonium…"),
                b -> this.minecraft.setScreen(new PlutoniumConfigScreen(self)))
                .bounds(this.width - 110, this.height - 27, 100, 20)
                .build());
    }
}
