package com.plutonium.backbone.client;

import com.plutonium.backbone.PlutoniumMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drives the {@link TerrainProfiler} once per frame and feeds the F3 overlay.
 * FORGE-bus subscriber (runtime events). Frame timing uses RenderTickEvent.END
 * deltas, gated to in-world frames so menu time isn't counted.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, value = Dist.CLIENT)
public final class TerrainProfilerEvents {

    private static long lastFrameEndNs = 0L;

    private TerrainProfilerEvents() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TerrainProfiler.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            lastFrameEndNs = 0L; // don't bridge a frame delta across menu time
            return;
        }
        long now = System.nanoTime();
        if (lastFrameEndNs != 0L) {
            TerrainProfiler.onFrameEnd(now - lastFrameEndNs);
        }
        lastFrameEndNs = now;

        if (TerrainProfilerKeys.dumpKey != null) {
            while (TerrainProfilerKeys.dumpKey.consumeClick()) {
                TerrainProfiler.dump(now);
            }
        }
        TerrainProfiler.maybePeriodicDump(now);
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        // Forge only fires this while the debug overlay renders, so the lines are
        // already F3-gated (incl. reduced-debug mode). Guard kept explicit so the
        // "F3-style, only when debug is open" contract is visible at the call site.
        if (!TerrainProfiler.ENABLED || !Minecraft.getInstance().options.renderDebug) {
            return;
        }
        event.getRight().addAll(TerrainProfiler.overlayLines());
    }
}
