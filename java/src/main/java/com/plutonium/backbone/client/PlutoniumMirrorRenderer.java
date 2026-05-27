package com.plutonium.backbone.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.plutonium.backbone.common.Config;
import com.plutonium.backbone.mixin.RenderChunkInfoAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.EnumMap;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Renders one chunk layer (solid / cutout / cutoutMipped) using
 * {@link PlutoniumMegaBuffer#multiDrawElementsBaseVertex}.
 *
 * <p><b>Per-region batching.</b> We do NOT emit a single mega-batch for the whole
 * layer — that caused a 40 fps regression on cutout/cutoutMipped because the GPU
 * rasterizer/texture cache thrashed across the full render-distance footprint.
 * Instead we group visible sections by an {@link #REGION_SHIFT}-chunk region key
 * and emit one {@code glMultiDrawElementsBaseVertex} call per region. The shader,
 * VAO, and matrix uniforms are bound once per layer; only the MultiDraw arrays
 * change between region draws. Spatially-coherent batches are GPU-cache-friendly.
 *
 * <p>Coverage gating: we only take over a layer once at least
 * {@link #MIN_VISIBLE_MIRROR_COVERAGE} of the visible sections for that layer have
 * a registry entry; otherwise we let vanilla draw (no holes during warmup).
 */
public final class PlutoniumMirrorRenderer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final double MIN_VISIBLE_MIRROR_COVERAGE = 0.95;
    // At 32 RD with ~3500 visible sections, normal chunk reload can leave 50-150
    // sections temporarily un-mirrored. Old threshold of 16 was tuned for warmup
    // only and made us fall back to vanilla every time a few sections rebuilt.
    // Holes are invisible-for-a-frame; vanilla fallback costs 50+ fps.
    private static final int MAX_MISSING_VISIBLE_SECTIONS = 512;

    /** Chunk region size = 2^REGION_SHIFT chunks per side. 3 → 8×8 chunks = 64-chunk footprint. */
    private static final int REGION_SHIFT = 3;

    /** Vanilla shader supplier per layer. */
    private static final EnumMap<PlutoniumSectionRegistry.ChunkLayer, Supplier<ShaderInstance>> shadersByLayer =
            new EnumMap<>(PlutoniumSectionRegistry.ChunkLayer.class);

    private static final EnumMap<PlutoniumSectionRegistry.ChunkLayer, Long> lastDiagNs = new EnumMap<>(PlutoniumSectionRegistry.ChunkLayer.class);

    /** Reusable per-layer scratch state — avoids per-frame map allocations. */
    private static final EnumMap<PlutoniumSectionRegistry.ChunkLayer, LayerScratch> layerScratch = new EnumMap<>(PlutoniumSectionRegistry.ChunkLayer.class);

    static {
        shadersByLayer.put(PlutoniumSectionRegistry.ChunkLayer.SOLID,         GameRenderer::getRendertypeSolidShader);
        shadersByLayer.put(PlutoniumSectionRegistry.ChunkLayer.CUTOUT,        GameRenderer::getRendertypeCutoutShader);
        shadersByLayer.put(PlutoniumSectionRegistry.ChunkLayer.CUTOUT_MIPPED, GameRenderer::getRendertypeCutoutMippedShader);
        for (var layer : PlutoniumSectionRegistry.ChunkLayer.values()) {
            lastDiagNs.put(layer, System.nanoTime());
            layerScratch.put(layer, new LayerScratch());
        }
    }

    private PlutoniumMirrorRenderer() {}

    /**
     * Attempt to draw the given chunk layer from the mega buffer.
     * Returns true if we drew (caller should cancel vanilla's draw); false to let vanilla proceed.
     */
    public static boolean renderLayer(PlutoniumSectionRegistry.ChunkLayer layer,
                                      PoseStack poseStack,
                                      double cameraX, double cameraY, double cameraZ,
                                      Matrix4f projectionMatrix,
                                      ObjectArrayList<?> visibleChunks) {
        if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE) return false;
        if (poseStack == null || projectionMatrix == null || visibleChunks == null) return false;
        if (PlutoniumSectionRegistry.sectionCount(layer) == 0) return false;

        // LOD pipeline force-disabled. The cell-level LOD produced floating chunks
        // and texture-stretching artifacts; the snapshot capture path was also
        // allocating ~2 GB/s and dragging FPS below baseline. Re-enable only after
        // a real fix (proper voxel-band gating, less-aggressive cell heuristic,
        // and a sane allocation strategy). The Frame.disabled path costs nothing.
        int renderDistance = currentRenderDistance();
        int lodStartDistance = Config.getLodStartDistance(renderDistance);
        PlutoniumLodRenderer.Frame lodFrame = PlutoniumLodRenderer.beginFrame(
                false,
                renderDistance,
                lodStartDistance,
                cameraX,
                cameraZ);
        LayerScratch scratch = layerScratch.get(layer);
        BatchSummary summary = buildRegionalBatches(layer, visibleChunks, scratch, lodFrame);
        if (!summary.coverageReady) {
            emitDiagnosticsIfDue(layer, "skip-coverage", cameraX, cameraY, cameraZ, summary, GL11.GL_NO_ERROR);
            return false;
        }
        if (summary.totalDraws == 0 && lodFrame.drawCount() == 0) {
            emitDiagnosticsIfDue(layer, "skip-empty", cameraX, cameraY, cameraZ, summary, GL11.GL_NO_ERROR);
            return true; // coverage is correct (nothing to draw); cancel vanilla's empty loop
        }

        renderRegions(layer, poseStack, cameraX, cameraY, cameraZ, projectionMatrix, scratch, lodFrame, summary);
        return true;
    }

    /**
     * Group visible sections by their 2^REGION_SHIFT-chunk region key. Each region
     * accumulates its own counts[] / baseVerts[] arrays so we can issue per-region
     * MultiDraw calls. This is the Embeddium-style spatially-coherent batching.
     */
    private static BatchSummary buildRegionalBatches(PlutoniumSectionRegistry.ChunkLayer layer,
                                                     ObjectArrayList<?> visibleChunks,
                                                     LayerScratch scratch,
                                                     PlutoniumLodRenderer.Frame lodFrame) {
        scratch.reset();
        int visibleSections = 0;
        int lodSections = 0;
        int missing = 0;
        long totalIndices = 0L;
        int totalDraws = 0;
        RenderType vanillaType = layer.renderType();

        for (Object info : visibleChunks) {
            if (!(info instanceof RenderChunkInfoAccessor accessor)) continue;
            ChunkRenderDispatcher.RenderChunk renderChunk = accessor.plutonium$getChunk();
            if (renderChunk == null) continue;

            PlutoniumSectionRegistry.SectionAlloc alloc = PlutoniumSectionRegistry.lookup(layer, renderChunk);
            if (lodFrame.accept(renderChunk.getOrigin())) {
                lodSections++;
                continue;
            }
            if (alloc == null || alloc.vertexCount() < 4) {
                // Cold path — only ask vanilla if the section was actually empty.
                if (!isLayerEmpty(renderChunk, vanillaType)) missing++;
                continue;
            }
            BlockPos origin = renderChunk.getOrigin();
            if (!alloc.origin().equals(origin)) {
                missing++;
                continue;
            }

            int quads = alloc.vertexCount() / 4;
            int indexCount = quads * 6;
            if (indexCount <= 0) continue;

            long regionKey = regionKeyOf(origin);
            RegionBucket bucket = scratch.bucketFor(regionKey);
            bucket.add(indexCount, (int) (alloc.byteOffset() / PlutoniumMegaBuffer.VERTEX_BYTES));

            totalIndices += indexCount;
            visibleSections++;
            totalDraws++;
        }

        int totalVisible = visibleSections + lodSections + missing;
        int coveredVisible = visibleSections + lodSections;
        boolean coverageReady = totalVisible == 0
                || missing <= MAX_MISSING_VISIBLE_SECTIONS
                || coveredVisible >= Math.ceil(totalVisible * MIN_VISIBLE_MIRROR_COVERAGE);

        return new BatchSummary(totalDraws, totalIndices, scratch.bucketCount,
                PlutoniumSectionRegistry.sectionCount(layer), totalVisible, visibleSections, lodSections,
                lodFrame.columnCount(), missing, coverageReady, lodFrame.lodStartDistance(), lodFrame.renderDistance());
    }

    /**
     * Bind shader/uniforms/VAO ONCE per layer, then issue one MultiDraw per region.
     * Per-region rebinds would defeat the whole purpose — render state is invariant
     * across regions within a layer.
     */
    private static void renderRegions(PlutoniumSectionRegistry.ChunkLayer layer,
                                      PoseStack poseStack,
                                      double cameraX, double cameraY, double cameraZ,
                                      Matrix4f projectionMatrix,
                                      LayerScratch scratch,
                                      PlutoniumLodRenderer.Frame lodFrame,
                                      BatchSummary summary) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        TextureAtlas blockAtlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        int blockAtlasId = blockAtlas.getId();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, blockAtlasId);
        GlStateManager._bindTexture(blockAtlasId);
        RenderSystem.setShader(shadersByLayer.get(layer));
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            emitDiagnosticsIfDue(layer, "skip-null-shader", cameraX, cameraY, cameraZ, summary, GL11.GL_NO_ERROR);
            return;
        }

        Matrix4f modelView = new Matrix4f(poseStack.last().pose())
                .translate((float) -cameraX, (float) -cameraY, (float) -cameraZ);
        if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(modelView);
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projectionMatrix);
        if (shader.COLOR_MODULATOR != null)   shader.COLOR_MODULATOR.set(1f, 1f, 1f, 1f);
        shader.apply();

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        PlutoniumMegaBuffer.bindVao();

        // One MultiDraw per region. Each region's section set is spatially close on
        // the GPU side, which keeps the texture/depth cache hot.
        for (int i = 0; i < scratch.bucketCount; i++) {
            RegionBucket bucket = scratch.buckets[i];
            PlutoniumMegaBuffer.multiDrawElementsBaseVertex(bucket.counts, bucket.baseVerts, bucket.size);
        }
        lodFrame.renderQueued();

        // Minimal cleanup. No glGet* — those drain the driver pipeline.
        shader.clear();
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        emitDiagnosticsIfDue(layer, "draw", cameraX, cameraY, cameraZ, summary, GL11.GL_NO_ERROR);
    }

    private static boolean isLayerEmpty(ChunkRenderDispatcher.RenderChunk renderChunk, RenderType layer) {
        try {
            ChunkRenderDispatcher.CompiledChunk compiled = renderChunk.getCompiledChunk();
            return compiled == null
                    || compiled.hasNoRenderableLayers()
                    || compiled.isEmpty(layer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long regionKeyOf(BlockPos origin) {
        // 2D region key — group full vertical chunk columns horizontally. Most cache
        // wins come from horizontal coherence; vertical sections of the same chunk
        // column already share textures and adjacent depth values.
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        int rx = chunkX >> REGION_SHIFT;
        int rz = chunkZ >> REGION_SHIFT;
        return (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL);
    }

    private static int currentRenderDistance() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return 32;
        }
        int renderDistance = minecraft.options.getEffectiveRenderDistance();
        return Math.max(Config.LOD_MIN_DISTANCE, Math.min(Config.LOD_MAX_DISTANCE, renderDistance));
    }

    private static void emitDiagnosticsIfDue(PlutoniumSectionRegistry.ChunkLayer layer, String reason,
                                             double cameraX, double cameraY, double cameraZ,
                                             BatchSummary summary, int glErr) {
        long now = System.nanoTime();
        long last = lastDiagNs.getOrDefault(layer, 0L);
        if ((now - last) < 2_000_000_000L) return;
        lastDiagNs.put(layer, now);
        LOGGER.info("[Plutonium/MirrorDraw] layer={} reason={} mirrored={} visible={} nearDraws={} lodSections={} lodColumns={} regions={} missing={} indices={} lodStart={}/{} cam=({}, {}, {}) glErr=0x{}.",
                layer, reason,
                summary.totalSectionsInLayer,
                summary.totalVisibleSections,
                summary.totalDraws,
                summary.lodSections,
                summary.lodColumns,
                summary.regionCount,
                summary.missing,
                summary.totalIndices,
                summary.lodStartDistance,
                summary.renderDistance,
                String.format(Locale.ROOT, "%.2f", cameraX),
                String.format(Locale.ROOT, "%.2f", cameraY),
                String.format(Locale.ROOT, "%.2f", cameraZ),
                Integer.toHexString(glErr));
    }

    // ── per-region accumulator ───────────────────────────────────────────────

    /**
     * A single region's MultiDraw payload — counts[] + baseVerts[] for sections in
     * this region. Grows on demand to fit the region's section count.
     */
    private static final class RegionBucket {
        int[] counts = new int[64];
        int[] baseVerts = new int[64];
        int size = 0;

        void reset() { size = 0; }

        void add(int count, int baseVertex) {
            if (size == counts.length) {
                int newCap = counts.length * 2;
                int[] newCounts = new int[newCap];
                int[] newBases = new int[newCap];
                System.arraycopy(counts, 0, newCounts, 0, size);
                System.arraycopy(baseVerts, 0, newBases, 0, size);
                counts = newCounts;
                baseVerts = newBases;
            }
            counts[size] = count;
            baseVerts[size] = baseVertex;
            size++;
        }
    }

    /**
     * Per-layer scratch: a pool of {@link RegionBucket}s indexed by region key.
     * Pool is preserved across frames so we don't allocate buckets every frame —
     * we just reset their {@code size} to 0 between frames.
     */
    private static final class LayerScratch {
        final Long2ObjectMap<RegionBucket> bucketByKey = new Long2ObjectOpenHashMap<>();
        RegionBucket[] buckets = new RegionBucket[16];
        int bucketCount = 0;

        void reset() {
            for (int i = 0; i < bucketCount; i++) buckets[i].reset();
            bucketByKey.clear();
            bucketCount = 0;
        }

        RegionBucket bucketFor(long regionKey) {
            RegionBucket b = bucketByKey.get(regionKey);
            if (b != null) return b;
            if (bucketCount == buckets.length) {
                RegionBucket[] grown = new RegionBucket[buckets.length * 2];
                System.arraycopy(buckets, 0, grown, 0, bucketCount);
                buckets = grown;
            }
            b = new RegionBucket();
            buckets[bucketCount++] = b;
            bucketByKey.put(regionKey, b);
            return b;
        }
    }

    private record BatchSummary(int totalDraws, long totalIndices, int regionCount,
                                int totalSectionsInLayer, int totalVisibleSections, int visibleSections,
                                int lodSections, int lodColumns, int missing, boolean coverageReady,
                                int lodStartDistance, int renderDistance) {}
}
