package com.plutonium.backbone.client;

import com.plutonium.backbone.PlutoniumMod;
import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * Adds Plutonium status lines to the F3 debug overlay. Visible only when F3 is
 * already open so it doesn't add HUD clutter for normal gameplay.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, value = Dist.CLIENT)
public final class PlutoniumDebugOverlay {

    private PlutoniumDebugOverlay() {
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!event.getWindow().getClass().getSimpleName().isEmpty()) {
            // No good cheap "is F3 visible" hook on the right side; the event
            // itself only fires while the debug screen is open, so we don't
            // need to guard further.
        }

        var lines = event.getRight();
        lines.add("");
        lines.add(headerLine());

        Config.ChunkBuilder builder = Config.getChunkBuilder();
        lines.add(String.format(Locale.ROOT, "Builder: %s   Native render: %s",
                builder,
                CudaPipeline.isNativeRenderActive() ? "ACTIVE" : "fallback"));

        double vx = VelocityChunkPrioritizer.velocityX();
        double vz = VelocityChunkPrioritizer.velocityZ();
        double speed = Math.sqrt(vx * vx + vz * vz);
        boolean fastMode = (vx * vx + vz * vz) > 4.0;
        lines.add(String.format(Locale.ROOT, "Vel: %.2f b/tick (%.1f b/s)   FastMode: %s",
                speed,
                speed * 20.0,
                fastMode ? "ON" : "off"));

        if (NativeInterface.isLoaded()) {
            try {
                int draw = NativeInterface.nPipelineRendererDrawCount();
                int maxC = NativeInterface.nPipelineRendererMaxChunks();
                int swaps = NativeInterface.nPipelinePendingSwapCount();
                int jobs = NativeInterface.nPipelineActiveMeshJobCount();
                lines.add(String.format(Locale.ROOT, "Native: draws=%d maxChunks=%d pendingSwaps=%d activeJobs=%d",
                        draw, maxC, swaps, jobs));
            } catch (Throwable t) {
                lines.add("Native: <call failed: " + t.getClass().getSimpleName() + ">");
            }
        } else {
            lines.add("Native: DLL not loaded");
        }
    }

    private static String headerLine() {
        return "§e[Plutonium]";
    }
}
