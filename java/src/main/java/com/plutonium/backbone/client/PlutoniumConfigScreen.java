package com.plutonium.backbone.client;

import com.plutonium.backbone.common.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public final class PlutoniumConfigScreen extends Screen {

    private final Screen parent;
    private Button gpuWorldgenButton;

    public PlutoniumConfigScreen(Screen parent) {
        super(Component.literal("Plutonium"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 40;

        gpuWorldgenButton = Button.builder(gpuWorldgenLabel(), b -> {
            boolean newValue = !Config.isGpuWorldgenEnabled();
            Config.CLIENT.experimentalGpuChunkGen.set(newValue);
            Config.CLIENT.unsafeGpuWorldgen.set(newValue);
            Config.CLIENT.experimentalGpuChunkGen.save();
            b.setMessage(gpuWorldgenLabel());
        }).bounds(centerX - 100, y, 200, 20).build();
        addRenderableWidget(gpuWorldgenButton);

        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> minecraft.setScreen(parent))
                .bounds(centerX - 100, y + 60, 200, 20)
                .build());
    }

    private static Component gpuWorldgenLabel() {
        boolean on = Config.isGpuWorldgenEnabled();
        return Component.literal("GPU Worldgen: ").append(
                on ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
                   : Component.literal("OFF").withStyle(ChatFormatting.RED));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.literal("Toggles GPU-accelerated chunk generation.").withStyle(ChatFormatting.GRAY),
                this.width / 2, this.height / 2 - 70, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.literal("Changes apply to chunks generated after restart.").withStyle(ChatFormatting.GRAY),
                this.width / 2, this.height / 2 + 5, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
