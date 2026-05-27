package com.plutonium.backbone.mixin;

import com.mojang.serialization.Codec;
import com.plutonium.backbone.client.BackendMode;
import com.plutonium.backbone.client.GpuMeshManager;
import com.plutonium.backbone.client.PlutoniumCompositor;
import com.plutonium.backbone.common.Config;
import com.plutonium.backbone.worldgen.GpuWorldgenState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {

    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void plutonium$injectIntoOptionsList(CallbackInfo ci) {
        OptionsList list = findOptionsList();
        if (list == null) return;

        // ── Chunk Builder ─────────────────────────────────────────────────────
        // Single full-width cycle button: CPU (Vanilla) ↔ GPU Accelerated.
        // Completely independent from the backend renderer below.
        list.addBig(createChunkBuilderOption());
        list.addBig(createWorldgenAcceleratorOption());

        // When GPU Accelerated is active, show profile + thread count.
        if (Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE) {
            list.addBig(createMeshProfileOption());
            list.addBig(createLodStartSliderOption());
            list.addBig(createCpuThreadSliderOption());
        }

        // ── Backend Renderer ──────────────────────────────────────────────────
        // Separate setting — controls the render API (OpenGL / DX12 / Vulkan).
        list.addBig(createBackboneRendererOption());
    }

    private OptionsList findOptionsList() {
        for (var child : this.children()) {
            if (child instanceof OptionsList ol) return ol;
        }
        return null;
    }

    @Unique
    private static OptionInstance<Boolean> createWorldgenAcceleratorOption() {
        return OptionInstance.createBoolean(
                "plutonium.options.worldgen_accelerator",
                OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(value && Config.CLIENT.unsafeGpuWorldgen.get()
                        ? "World Generation: GPU Density Assist"
                        : value
                        ? "World Generation: Vanilla (GPU Assist Held)"
                        : "World Generation: Vanilla"),
                Config.CLIENT.experimentalGpuChunkGen.get(),
                enabled -> {
                    Config.CLIENT.experimentalGpuChunkGen.set(enabled);
                    GpuWorldgenState.reset();
                }
        );
    }

    @Unique
    private static OptionInstance<Config.ChunkBuilder> createChunkBuilderOption() {
        Codec<Config.ChunkBuilder> codec = Codec.STRING.xmap(
                s -> { try { return Config.ChunkBuilder.valueOf(s); } catch (Throwable t) { return Config.ChunkBuilder.CPU; } },
                Config.ChunkBuilder::name
        );
        return new OptionInstance<>(
                "plutonium.options.chunk_builder",
                OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(prettyChunkBuilder(value)),
                new OptionInstance.Enum<>(List.of(Config.ChunkBuilder.CPU, Config.ChunkBuilder.NATIVE), codec),
                Config.getChunkBuilder(),
                mode -> {
                    Config.setChunkBuilder(mode);
                    GpuMeshManager.invalidateAllProcessed();
                }
        );
    }

    @Unique
    private static OptionInstance<Config.MeshProfile> createMeshProfileOption() {
        Codec<Config.MeshProfile> codec = Codec.STRING.xmap(
                s -> { try { return Config.MeshProfile.valueOf(s); } catch (Throwable t) { return Config.MeshProfile.BALANCED; } },
                Config.MeshProfile::name
        );
        return new OptionInstance<>(
                "plutonium.options.mesh_profile",
                OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(prettyProfile(value)),
                new OptionInstance.Enum<>(List.of(Config.MeshProfile.COMPATIBILITY, Config.MeshProfile.BALANCED, Config.MeshProfile.AGGRESSIVE), codec),
                Config.getMeshProfile(),
                Config::setMeshProfile
        );
    }

    @Unique
    private static OptionInstance<Integer> createCpuThreadSliderOption() {
        int maxCores = Math.max(2, Math.min(32, Runtime.getRuntime().availableProcessors()));
        return new OptionInstance<>(
                "plutonium.options.cpu_threads",
                OptionInstance.noTooltip(),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(Integer.toString(value))),
                new OptionInstance.IntRange(1, maxCores),
                Config.CLIENT.cpuThreads.get(),
                value -> Config.CLIENT.cpuThreads.set(value)
        );
    }

    @Unique
    private static OptionInstance<Integer> createLodStartSliderOption() {
        int renderDistance = plutonium$getCurrentRenderDistance();
        int sliderMax = Math.max(Config.LOD_MIN_DISTANCE, renderDistance);
        int current = Config.getLodStartDistance(sliderMax);
        return new OptionInstance<>(
                "plutonium.options.lod_start_distance",
                OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(prettyLodStart(value, sliderMax)),
                new OptionInstance.IntRange(Config.LOD_MIN_DISTANCE, sliderMax),
                current,
                value -> Config.setLodStartDistance(Math.min(value, sliderMax))
        );
    }

    @Unique
    private static OptionInstance<BackendMode> createBackboneRendererOption() {
        Codec<BackendMode> codec = Codec.STRING.xmap(
                s -> { try { return BackendMode.valueOf(s); } catch (Throwable t) { return BackendMode.OPENGL; } },
                BackendMode::name
        );
        return new OptionInstance<>(
                "plutonium.options.backbone_renderer",
                OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(prettyBackend(value)),
                new OptionInstance.Enum<>(List.of(BackendMode.OPENGL, BackendMode.DX12, BackendMode.VULKAN), codec),
                BackendMode.fromString(Config.CLIENT.backend.get()),
                mode -> {
                    Config.CLIENT.backend.set(mode.name());
                    PlutoniumCompositor.requestSwitch(mode);
                }
        );
    }

    @Unique
    private static String prettyChunkBuilder(Config.ChunkBuilder mode) {
        return switch (mode) {
            case CPU    -> "Chunk Builder: CPU (Vanilla)";
            case NATIVE -> "Chunk Builder: GPU Accelerated";
        };
    }

    @Unique
    private static String prettyProfile(Config.MeshProfile profile) {
        return switch (profile) {
            case COMPATIBILITY -> "Compatibility";
            case BALANCED      -> "Balanced";
            case AGGRESSIVE    -> "Aggressive";
        };
    }

    @Unique
    private static String prettyLodStart(int chunks, int renderDistance) {
        if (chunks >= renderDistance) {
            return "LOD Start: Off";
        }
        return "LOD Start: " + chunks + " chunks";
    }

    @Unique
    private static int plutonium$getCurrentRenderDistance() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return 32;
        }
        int renderDistance = minecraft.options.getEffectiveRenderDistance();
        return Math.max(Config.LOD_MIN_DISTANCE, Math.min(Config.LOD_MAX_DISTANCE, renderDistance));
    }

    @Unique
    private static String prettyBackend(BackendMode m) {
        return switch (m) {
            case OPENGL  -> "Renderer: OpenGL";
            case DX12    -> "Renderer: DX12";
            case VULKAN  -> "Renderer: Vulkan";
        };
    }
}
