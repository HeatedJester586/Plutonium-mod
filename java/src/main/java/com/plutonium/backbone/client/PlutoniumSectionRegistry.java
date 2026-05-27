package com.plutonium.backbone.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Tracks which slot of the mega buffer holds a section's vertices, keyed by
 * {@code (RenderChunk identity, ChunkLayer)}. Each opaque chunk layer (solid,
 * cutout, cutoutMipped) has its own slot per section so the corresponding
 * vanilla layer draw can be replaced with one {@code glMultiDrawElementsBaseVertex}
 * call per layer.
 *
 * <p>{@code RenderChunk}s are pooled and reused with new origins as the player
 * moves, so we also stash the origin in the alloc record and verify it on
 * lookup — that's the cheap stale-slot guard.
 */
public final class PlutoniumSectionRegistry {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Chunk render layer we own. Only {@link #SOLID} is actually mirrored by the
     * pipeline right now — at 2560×1440 the per-fragment alpha-test cost of
     * {@code cutout} / {@code cutoutMipped} combined with mega-batch rasterization
     * cost ~40 fps vs vanilla's per-section flow (135 → 95 fps). Vanilla's
     * spatially-coherent small per-section draws turn out to be GPU-cache friendlier
     * for those layers. Translucent/tripwire stay vanilla regardless (sort order).
     *
     * <p>{@link #CUTOUT} and {@link #CUTOUT_MIPPED} stay as enum values so the
     * registry infrastructure is ready if/when we re-enable them via a different
     * batching strategy (e.g. per-region multi-draw, Phase 3 greedy meshing).
     */
    public enum ChunkLayer {
        SOLID,
        CUTOUT,
        CUTOUT_MIPPED;

        public static ChunkLayer fromRenderType(RenderType type) {
            if (type == RenderType.solid()) return SOLID;
            // Cutout layers stay on vanilla for now. The alpha-test shader path
            // regressed FPS when mirrored as huge batches, even with the same
            // visible geometry count. Keep the enum values for future regional
            // or greedy-meshed experiments, but do not capture/cancel them here.
            return null;
        }

        public RenderType renderType() {
            return switch (this) {
                case SOLID -> RenderType.solid();
                case CUTOUT -> RenderType.cutout();
                case CUTOUT_MIPPED -> RenderType.cutoutMipped();
            };
        }
    }

    /** Per-section allocation: byteOffset in the mega buffer + byteSize + vertexCount + origin guard. */
    public record SectionAlloc(long byteOffset, int byteSize, int vertexCount, BlockPos origin) {}

    private static final EnumMap<ChunkLayer, IdentityHashMap<ChunkRenderDispatcher.RenderChunk, SectionAlloc>> layerAllocs;
    private static final EnumMap<ChunkLayer, Long> mirroredUploadsPerLayer = new EnumMap<>(ChunkLayer.class);

    static {
        layerAllocs = new EnumMap<>(ChunkLayer.class);
        for (ChunkLayer layer : ChunkLayer.values()) {
            layerAllocs.put(layer, new IdentityHashMap<>());
            mirroredUploadsPerLayer.put(layer, 0L);
        }
    }

    private PlutoniumSectionRegistry() {}

    public static int sectionCount(ChunkLayer layer) {
        return layerAllocs.get(layer).size();
    }

    public static int totalSectionCount() {
        int sum = 0;
        for (IdentityHashMap<?, ?> m : layerAllocs.values()) sum += m.size();
        return sum;
    }

    public static long mirroredUploads(ChunkLayer layer) {
        return mirroredUploadsPerLayer.getOrDefault(layer, 0L);
    }

    /**
     * Capture a vanilla chunk-layer upload. Vertices are converted from section-local
     * to absolute world coords on the render thread and uploaded to the mega buffer.
     */
    public static void mirrorUpload(ChunkLayer layer,
                                    ChunkRenderDispatcher.RenderChunk renderChunk,
                                    ByteBuffer srcVertexBuffer,
                                    int vertexCount) {
        if (!RenderSystem.isOnRenderThread()) {
            ByteBuffer snapshot = copyToDirect(srcVertexBuffer);
            BlockPos origin = renderChunk.getOrigin().immutable();
            RenderSystem.recordRenderCall(() -> mirrorOnRenderThread(layer, renderChunk, snapshot, vertexCount, origin));
            return;
        }
        mirrorOnRenderThread(layer, renderChunk, copyToDirect(srcVertexBuffer), vertexCount, renderChunk.getOrigin().immutable());
    }

    private static void mirrorOnRenderThread(ChunkLayer layer,
                                             ChunkRenderDispatcher.RenderChunk renderChunk,
                                             ByteBuffer worldSpaceVerts,
                                             int vertexCount,
                                             BlockPos origin) {
        // Render-thread-only. No synchronization needed — reads (buildDrawBatch in
        // PlutoniumMirrorRenderer) also run on the render thread.
        try {
            transformToWorldSpace(worldSpaceVerts, vertexCount, origin);

            SectionAlloc old = layerAllocs.get(layer).remove(renderChunk);
            if (old != null) {
                PlutoniumMegaBuffer.free(old.byteOffset, old.byteSize);
            }

            int byteSize = worldSpaceVerts.remaining();
            long offset = PlutoniumMegaBuffer.alloc(worldSpaceVerts);
            if (offset < 0) {
                LOGGER.warn("[Plutonium/Registry] alloc failed for {} {} ({} bytes)", layer, origin, byteSize);
                return;
            }

            SectionAlloc alloc = new SectionAlloc(offset, byteSize, vertexCount, origin);
            layerAllocs.get(layer).put(renderChunk, alloc);
            mirroredUploadsPerLayer.merge(layer, 1L, Long::sum);
        } finally {
            // Return the staging buffer to the pool — GPU already has the bytes
            // via glBufferSubData inside PlutoniumMegaBuffer.alloc, so the buffer
            // is safe to reuse for the next upload.
            releaseBuffer(worldSpaceVerts);
        }
    }

    /** Remove this section from ALL layers (must run on render thread). */
    public static void removeSection(ChunkRenderDispatcher.RenderChunk renderChunk) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> removeSection(renderChunk));
            return;
        }
        for (ChunkLayer layer : ChunkLayer.values()) {
            SectionAlloc alloc = layerAllocs.get(layer).remove(renderChunk);
            if (alloc != null) {
                PlutoniumMegaBuffer.free(alloc.byteOffset, alloc.byteSize);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map.Entry<ChunkRenderDispatcher.RenderChunk, SectionAlloc>[] snapshot(ChunkLayer layer) {
        return layerAllocs.get(layer).entrySet().toArray(new Map.Entry[0]);
    }

    public static SectionAlloc lookup(ChunkLayer layer, ChunkRenderDispatcher.RenderChunk renderChunk) {
        return layerAllocs.get(layer).get(renderChunk);
    }

    public static void clear() {
        for (IdentityHashMap<?, ?> m : layerAllocs.values()) m.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Bounded direct-buffer pool. Mesh-worker threads acquire a buffer here, fill
     * it with vanilla's vertex bytes, and schedule a render-thread call that
     * consumes the buffer via {@link PlutoniumMegaBuffer#alloc(ByteBuffer)} and
     * then {@linkplain #releaseBuffer returns it to the pool}.
     *
     * <p>Without this pool, every section upload allocated a fresh direct buffer.
     * At 32 chunk render distance with fast camera movement, vanilla rebuilds
     * dozens of sections per frame — that was the {@code 2+ GB/s} allocation rate
     * spike, mostly from the on-heap {@code DirectByteBuffer} wrappers and their
     * {@code Cleaner} references piling up for GC. Pool reuses the same off-heap
     * region instead of allocating a new one each time.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> BUFFER_POOL =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger BUFFER_POOL_SIZE =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Cap the pool so unusually large sections don't permanently bloat off-heap memory. */
    private static final int BUFFER_POOL_MAX = 96;

    private static ByteBuffer acquireBuffer(int minSize) {
        ByteBuffer pooled = BUFFER_POOL.poll();
        if (pooled != null) {
            BUFFER_POOL_SIZE.decrementAndGet();
            if (pooled.capacity() >= minSize) {
                pooled.clear();
                return pooled;
            }
            // Too small — drop and allocate larger. Off-heap memory of the dropped
            // buffer is reclaimed when the wrapper is GC'd.
        }
        int cap = Math.max(64 * 1024, Integer.highestOneBit(Math.max(1, minSize - 1)) << 1);
        return ByteBuffer.allocateDirect(cap).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void releaseBuffer(ByteBuffer buf) {
        if (buf == null) return;
        if (BUFFER_POOL_SIZE.get() >= BUFFER_POOL_MAX) {
            return; // pool full — let GC reclaim this one
        }
        BUFFER_POOL.offer(buf);
        BUFFER_POOL_SIZE.incrementAndGet();
    }

    private static ByteBuffer copyToDirect(ByteBuffer src) {
        ByteBuffer dup = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int size = dup.remaining();
        ByteBuffer staging = acquireBuffer(size);
        staging.put(dup);
        staging.flip();
        return staging;
    }

    private static void transformToWorldSpace(ByteBuffer buf, int vertexCount, BlockPos origin) {
        float ox = origin.getX();
        float oy = origin.getY();
        float oz = origin.getZ();
        int stride = PlutoniumMegaBuffer.VERTEX_BYTES;
        for (int i = 0; i < vertexCount; i++) {
            int base = i * stride;
            float x = buf.getFloat(base);
            float y = buf.getFloat(base + 4);
            float z = buf.getFloat(base + 8);
            buf.putFloat(base,     x + ox);
            buf.putFloat(base + 4, y + oy);
            buf.putFloat(base + 8, z + oz);
        }
    }
}
