package com.plutonium.backbone.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.plutonium.backbone.mixin.LevelRendererAccessor;
import com.plutonium.backbone.mixin.RenderChunkInfoAccessor;
import com.plutonium.backbone.mixin.VertexBufferAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Read-only instrumentation for VANILLA terrain rendering. The custom SSBO+MDIA
 * renderer was deleted (commit dcd1854); terrain is drawn by vanilla now, so we
 * measure vanilla's section-layer path to find the binding constraint
 * (CPU-bound / GPU-bound / VRAM-limited / stutter-limited).
 *
 * <p>Design constraints (do not break):
 * <ul>
 *   <li>Never stalls the pipeline. GPU timer results are read back 4 frames late
 *       and guarded by GL_QUERY_RESULT_AVAILABLE — we never read the current
 *       frame's query and never call glFinish.</li>
 *   <li>The per-frame hot path only touches primitive counters / ring buffers.
 *       No string formatting, no logging per frame. Formatting happens in
 *       {@link #dump} (keybind + every 10 s) and the overlay rebuild (<=5 Hz).</li>
 *   <li>Everything is behind {@link #ENABLED}; when false the hooks early-return.</li>
 * </ul>
 *
 * <p>All methods here run on the render thread (the call sites — renderChunkLayer,
 * uploadAllPendingUploads, RenderTickEvent.END — are all render-thread), so the
 * counters need no synchronization. {@link #ENABLED} is volatile because it may
 * be flipped from another thread.
 */
public final class TerrainProfiler {

    /** Master toggle. Default on. When false, all hooks early-return. */
    public static volatile boolean ENABLED = true;

    private static final Logger LOGGER = LogManager.getLogger("PlutoniumTerrainProfiler");

    private TerrainProfiler() {}

    // ───────────────────────── GPU timer ring (4 frames × ≤8 layers) ─────────
    // Vanilla draws ≤5 terrain layers/frame (solid, cutoutMipped, cutout,
    // translucent, tripwire) and they are NOT consecutive. We bracket each
    // renderChunkLayer call with two GL_TIMESTAMP markers (glQueryCounter) and
    // sum (after-before) into the frame's terrain GPU total.
    //
    // We deliberately do NOT use GL_TIME_ELAPSED: Minecraft runs a frame-wide
    // GL_TIME_ELAPSED query for its F3 "GPU %" meter, and elapsed-queries cannot
    // nest — a nested glBeginQuery silently fails and the paired glEndQuery then
    // closes Minecraft's query. GL_TIMESTAMP markers have no active-query state,
    // so they never conflict. A ring of FRAME_LAG frames lets us read a slot's
    // markers back 4 frames after they were issued — never the current one.
    private static final int FRAME_LAG = 4;
    private static final int MAX_LAYERS = 8;
    // 2 timestamp queries per layer (before + after), flattened:
    //   before = queryIds[(slot*MAX_LAYERS + layer)*2], after = ...+1
    private static int[] queryIds = null;
    private static final int[] slotQueryCount = new int[FRAME_LAG];
    private static boolean glInited = false;
    private static boolean gpuBroken = false;          // driver refused queries → CPU-only

    private static int terrainFrameCounter = 0;
    private static int curSlot = 0;
    private static int curLayerIndex = 0;
    private static boolean layerInFlight = false;
    private static boolean markedThisLayer = false;
    private static long cpuLayerStartNs = 0L;

    // ───────────────────────── per-frame terrain accumulators ────────────────
    private static long curCpuFrameNs = 0L;            // sum of this frame's layer CPU times
    private static boolean haveCpuFrame = false;
    private static long lastCpuTerrainNs = 0L;         // latest finished frame (overlay)
    private static long lastGpuTerrainNs = 0L;         // latest read-back frame (overlay)

    // ───────────────────────── window stats (reset each dump) ────────────────
    private static long cpuWinSumNs = 0L, cpuWinMaxNs = 0L;
    private static int cpuFrameSamples = 0;
    private static long gpuWinSumNs = 0L, gpuWinMaxNs = 0L;
    private static int gpuFrameSamples = 0;

    // ───────────────────────── frame-time ring (rolling 1000) ────────────────
    private static final int FRAME_RING = 1000;
    private static final double[] frameMs = new double[FRAME_RING];
    private static int frameRingHead = 0;
    private static int frameRingCount = 0;

    // ───────────────────────── upload / stutter correlation ──────────────────
    private static int curFrameUploads = 0;
    private static int upWithFrames = 0, upWithoutFrames = 0;
    private static double upWithSumMs = 0, upWithMaxMs = 0;
    private static double upWithoutSumMs = 0, upWithoutMaxMs = 0;

    // ───────────────────────── dump / overlay throttles ──────────────────────
    private static final long DUMP_INTERVAL_NS = 10_000_000_000L;
    private static long lastDumpNs = 0L;
    private static final long OVERLAY_INTERVAL_NS = 200_000_000L; // ~5 Hz
    private static long lastOverlayBuildNs = 0L;
    private static List<String> overlayCache = Collections.emptyList();

    // ══════════════════════════ hot path: terrain layers ═════════════════════

    /** HEAD of LevelRenderer.renderChunkLayer. Begins this layer's GPU+CPU timers. */
    public static void onLayerStart(RenderType layer) {
        if (!ENABLED) {
            return;
        }
        ensureGl();
        if (layer == RenderType.solid()) {
            startTerrainFrame();
        }
        markedThisLayer = false;
        if (!gpuBroken && queryIds != null && curLayerIndex < MAX_LAYERS) {
            try {
                GL33.glQueryCounter(queryIds[(curSlot * MAX_LAYERS + curLayerIndex) * 2], GL33.GL_TIMESTAMP);
                markedThisLayer = true;
            } catch (Throwable t) {
                gpuBroken = true;
            }
        }
        cpuLayerStartNs = System.nanoTime();
        layerInFlight = true;
    }

    /** RETURN of LevelRenderer.renderChunkLayer. Closes this layer's timers. */
    public static void onLayerEnd(RenderType layer) {
        // Intentionally NOT guarded on ENABLED: if a layer placed its opening
        // timestamp we must place the closing one even if the toggle flipped
        // mid-layer, so the before/after markers stay paired.
        if (!layerInFlight) {
            return;
        }
        layerInFlight = false;
        long d = System.nanoTime() - cpuLayerStartNs;
        cpuWinSumNs += d;
        curCpuFrameNs += d;
        if (markedThisLayer) {
            try {
                GL33.glQueryCounter(queryIds[(curSlot * MAX_LAYERS + curLayerIndex) * 2 + 1], GL33.GL_TIMESTAMP);
            } catch (Throwable t) {
                gpuBroken = true;
            }
            curLayerIndex++;
            slotQueryCount[curSlot] = curLayerIndex;
        }
    }

    private static void startTerrainFrame() {
        // Rotate to this frame's ring slot (reused from FRAME_LAG frames ago).
        curSlot = terrainFrameCounter % FRAME_LAG;
        terrainFrameCounter++;

        // Finalize the previous frame's CPU terrain total.
        if (haveCpuFrame) {
            lastCpuTerrainNs = curCpuFrameNs;
            if (curCpuFrameNs > cpuWinMaxNs) {
                cpuWinMaxNs = curCpuFrameNs;
            }
            cpuFrameSamples++;
        }
        curCpuFrameNs = 0L;
        haveCpuFrame = true;

        // Deferred read-back of the slot's previous occupant (≈4 frames old).
        long ns = readbackSlot(curSlot);
        if (ns > 0L) {
            lastGpuTerrainNs = ns;
            gpuWinSumNs += ns;
            if (ns > gpuWinMaxNs) {
                gpuWinMaxNs = ns;
            }
            gpuFrameSamples++;
        }
        curLayerIndex = 0;
    }

    private static long readbackSlot(int slot) {
        if (gpuBroken || queryIds == null) {
            return 0L;
        }
        int n = slotQueryCount[slot];
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            int beforeId = queryIds[(slot * MAX_LAYERS + i) * 2];
            int afterId = queryIds[(slot * MAX_LAYERS + i) * 2 + 1];
            try {
                if (GL33.glGetQueryObjecti64(afterId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0L
                        || GL33.glGetQueryObjecti64(beforeId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0L) {
                    return 0L; // not ready yet (shouldn't happen at 4-frame lag) — skip, never block
                }
                long before = GL33.glGetQueryObjectui64(beforeId, GL15.GL_QUERY_RESULT);
                long after = GL33.glGetQueryObjectui64(afterId, GL15.GL_QUERY_RESULT);
                if (after > before) {
                    sum += after - before; // GPU ns spent inside this layer's draw
                }
            } catch (Throwable t) {
                gpuBroken = true;
                return 0L;
            }
        }
        return sum;
    }

    private static void ensureGl() {
        if (glInited) {
            return;
        }
        glInited = true;
        try {
            queryIds = new int[FRAME_LAG * MAX_LAYERS * 2]; // before+after timestamp per layer
            for (int i = 0; i < queryIds.length; i++) {
                queryIds[i] = GL15.glGenQueries();
            }
        } catch (Throwable t) {
            gpuBroken = true;
            LOGGER.warn("[Plutonium] GPU timer query init failed, GPU terrain timing disabled: {}", t.toString());
        }
    }

    // ══════════════════════════ hot path: frame + uploads ════════════════════

    /** Called once per in-world frame (RenderTickEvent.END) with the inter-frame ns. */
    public static void onFrameEnd(long frameNs) {
        if (!ENABLED) {
            return;
        }
        double ms = frameNs / 1_000_000.0;
        frameMs[frameRingHead] = ms;
        frameRingHead = (frameRingHead + 1) % FRAME_RING;
        if (frameRingCount < FRAME_RING) {
            frameRingCount++;
        }
        int uploads = curFrameUploads;
        curFrameUploads = 0;
        if (uploads > 0) {
            upWithFrames++;
            upWithSumMs += ms;
            if (ms > upWithMaxMs) {
                upWithMaxMs = ms;
            }
        } else {
            upWithoutFrames++;
            upWithoutSumMs += ms;
            if (ms > upWithoutMaxMs) {
                upWithoutMaxMs = ms;
            }
        }
    }

    /** HEAD of ChunkRenderDispatcher.uploadAllPendingUploads — sections uploaded this frame. */
    public static void recordUploads(int n) {
        if (!ENABLED) {
            return;
        }
        curFrameUploads = n;
    }

    // ══════════════════════════ dump (keybind + every 10 s) ══════════════════

    /** If 10 s have elapsed since the last dump, emit one. Cheap when not due. */
    public static void maybePeriodicDump(long now) {
        if (!ENABLED) {
            return;
        }
        if (lastDumpNs == 0L) {
            lastDumpNs = now; // first call seeds the window
            return;
        }
        if (now - lastDumpNs >= DUMP_INTERVAL_NS) {
            dump(now);
        }
    }

    /** Format and log the full profile, then reset the per-window accumulators. */
    public static void dump(long now) {
        if (!ENABLED) {
            return;
        }
        double windowSec = lastDumpNs == 0L ? 0.0 : (now - lastDumpNs) / 1_000_000_000.0;

        double cpuAvgMs = cpuFrameSamples > 0 ? (cpuWinSumNs / (double) cpuFrameSamples) / 1e6 : 0.0;
        double cpuMaxMs = cpuWinMaxNs / 1e6;
        double gpuAvgMs = gpuFrameSamples > 0 ? (gpuWinSumNs / (double) gpuFrameSamples) / 1e6 : 0.0;
        double gpuMaxMs = gpuWinMaxNs / 1e6;

        double[] f = frameStats();          // {avg, onePctLow, max}
        VramCull vc = sampleVramCull();

        String gpuLine = gpuBroken
                ? "  GPU terrain:  n/a (driver timer queries unavailable)"
                : String.format(Locale.ROOT, "  GPU terrain:  %6.2f ms  (fill, max %.2f)", gpuAvgMs, gpuMaxMs);
        String ratioVerdict;
        if (gpuBroken || gpuAvgMs <= 0.0) {
            ratioVerdict = "";
        } else {
            double ratio = cpuAvgMs / gpuAvgMs;
            ratioVerdict = String.format(Locale.ROOT, "   CPU/GPU = %.2f  -> %s", ratio, verdict(cpuAvgMs, gpuAvgMs, f[0]));
        }

        double upWithAvg = upWithFrames > 0 ? upWithSumMs / upWithFrames : 0.0;
        double upWithoutAvg = upWithoutFrames > 0 ? upWithoutSumMs / upWithoutFrames : 0.0;

        String msg = "\n[Plutonium] ===== TERRAIN PROFILE (window " + String.format(Locale.ROOT, "%.1f", windowSec) + "s) =====\n"
                + String.format(Locale.ROOT, "  CPU terrain:  %6.2f ms  (submission/iteration, max %.2f)%n", cpuAvgMs, cpuMaxMs)
                + gpuLine + ratioVerdict + "\n"
                + String.format(Locale.ROOT, "  FRAME   avg %.2f ms (%.0f fps) | 1%% low %.2f ms | max %.2f ms  (n=%d)%n",
                        f[0], f[0] > 0 ? 1000.0 / f[0] : 0.0, f[1], f[2], frameRingCount)
                + String.format(Locale.ROOT, "  VRAM    drawn %d / loaded %d sections (%.1f%%) of %d in-range | ~%s terrain VBO (drawn, est)%n",
                        vc.drawn, vc.loaded, vc.loaded > 0 ? 100.0 * vc.drawn / vc.loaded : 0.0, vc.total, humanBytes(vc.vboBytes))
                + String.format(Locale.ROOT, "  UPLOAD  with: %d fr avg %.2f / max %.2f ms | without: %d fr avg %.2f / max %.2f ms",
                        upWithFrames, upWithAvg, upWithMaxMs, upWithoutFrames, upWithoutAvg, upWithoutMaxMs);
        LOGGER.info(msg);

        // Reset per-window accumulators (terrain + uploads). The frame-time ring
        // is intentionally NOT reset — it is a rolling last-1000 for the stutter metrics.
        cpuWinSumNs = 0L;
        cpuWinMaxNs = 0L;
        cpuFrameSamples = 0;
        gpuWinSumNs = 0L;
        gpuWinMaxNs = 0L;
        gpuFrameSamples = 0;
        upWithFrames = 0;
        upWithoutFrames = 0;
        upWithSumMs = 0;
        upWithMaxMs = 0;
        upWithoutSumMs = 0;
        upWithoutMaxMs = 0;
        lastDumpNs = now;
    }

    private static String verdict(double cpuMs, double gpuMs, double frameAvgMs) {
        if (cpuMs >= gpuMs) {
            return "CPU-bound (fix submission, not fill)";
        }
        // GPU larger; if it dominates the whole frame, it's the fill limit.
        if (frameAvgMs > 0 && gpuMs >= 0.6 * frameAvgMs) {
            return "GPU-bound (overdraw / fill)";
        }
        return "GPU>CPU but neither dominates the frame";
    }

    // ══════════════════════════ overlay (F3, ≤5 Hz rebuild) ══════════════════

    public static List<String> overlayLines() {
        if (!ENABLED) {
            return Collections.emptyList();
        }
        long now = System.nanoTime();
        if (now - lastOverlayBuildNs < OVERLAY_INTERVAL_NS) {
            return overlayCache;
        }
        lastOverlayBuildNs = now;
        double cpuMs = lastCpuTerrainNs / 1e6;
        double gpuMs = lastGpuTerrainNs / 1e6;
        double onePctLow = frameStats()[1];
        List<String> l = new ArrayList<>(2);
        l.add(String.format(Locale.ROOT, "§e[Plutonium] terrain CPU %.2f / GPU %s ms",
                cpuMs, gpuBroken ? "n/a" : String.format(Locale.ROOT, "%.2f", gpuMs)));
        l.add(String.format(Locale.ROOT, "§e 1%% low %.2f ms (last %d frames)", onePctLow, frameRingCount));
        overlayCache = l;
        return l;
    }

    // ══════════════════════════ stats helpers ════════════════════════════════

    /** @return {avg, mean-of-worst-1%, max} over the rolling frame ring, in ms. */
    private static double[] frameStats() {
        int n = frameRingCount;
        if (n == 0) {
            return new double[]{0, 0, 0};
        }
        double[] arr = Arrays.copyOf(frameMs, n);
        double sum = 0, max = 0;
        for (double v : arr) {
            sum += v;
            if (v > max) {
                max = v;
            }
        }
        Arrays.sort(arr); // ascending
        int worst = Math.max(1, (int) Math.ceil(n * 0.01));
        double worstSum = 0;
        for (int i = n - worst; i < n; i++) {
            worstSum += arr[i];
        }
        return new double[]{sum / n, worstSum / worst, max};
    }

    private static final class VramCull {
        int drawn, loaded, total;
        long vboBytes;
    }

    /**
     * Samples section cull ratio + an estimated terrain VBO byte total. Runs only
     * inside {@link #dump} (off the per-frame path). Iterating the in-range grid is
     * a few thousand–tens-of-thousands of cheap getter calls, fine at a 10 s cadence.
     */
    private static VramCull sampleVramCull() {
        VramCull vc = new VramCull();
        Minecraft mc = Minecraft.getInstance();
        LevelRenderer lr = mc.levelRenderer;
        if (lr == null || mc.level == null) {
            return vc;
        }
        try {
            vc.drawn = lr.countRenderedChunks();
            LevelRendererAccessor acc = (LevelRendererAccessor) (Object) lr;
            ViewArea va = acc.plutonium$getViewArea();
            if (va != null && va.chunks != null) {
                vc.total = va.chunks.length;
                for (ChunkRenderDispatcher.RenderChunk rc : va.chunks) {
                    if (rc == null) {
                        continue;
                    }
                    ChunkRenderDispatcher.CompiledChunk cc = rc.getCompiledChunk();
                    if (cc != null && !cc.hasNoRenderableLayers()) {
                        vc.loaded++;
                    }
                }
            }
            // Estimated VBO bytes over the DRAWN (visible) sections.
            ObjectArrayList<?> vis = acc.plutonium$getRenderChunksInFrustum();
            if (vis != null) {
                for (Object info : vis) {
                    ChunkRenderDispatcher.RenderChunk rc = ((RenderChunkInfoAccessor) info).plutonium$getChunk();
                    if (rc == null) {
                        continue;
                    }
                    ChunkRenderDispatcher.CompiledChunk cc = rc.getCompiledChunk();
                    if (cc == null || cc.hasNoRenderableLayers()) {
                        continue;
                    }
                    for (RenderType rt : RenderType.chunkBufferLayers()) {
                        if (cc.isEmpty(rt)) {
                            continue;
                        }
                        VertexBuffer vb = rc.getBuffer(rt);
                        if (vb == null) {
                            continue;
                        }
                        int indexCount = ((VertexBufferAccessor) (Object) vb).plutonium$getIndexCount();
                        if (indexCount > 0) {
                            // QUADS: 6 indices per 4 vertices. + ~2 bytes/index (SHORT).
                            long verts = (long) indexCount / 6L * 4L;
                            vc.vboBytes += verts * vb.getFormat().getVertexSize() + (long) indexCount * 2L;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Plutonium] VRAM/cull sample failed: {}", t.toString());
        }
        return vc;
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
