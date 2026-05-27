package com.plutonium.backbone.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;

public final class GpuMeshManager {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int BLOCK_BUF_BYTES = 18 * 18 * 18 * 4; // uint32 per block (required by nNativeMeshSection)
    private static final int MAX_VERTS = 65_536;
    // 28-byte vertex = vanilla DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
    // (ChunkRenderDispatcher.RebuildTask.compile — see research ChunkRenderDispatcher.java ~522).
    // Native Mesher.h MeshVertex must stay 28 bytes (pos 12 + color 4 + uv 8 + lightmap 4).
    private static final int VERTEX_BYTES = 28;
    private static final int OUT_BUF_BYTES = MAX_VERTS * VERTEX_BYTES;
    // Budget defaults tuned for stability first; raise gradually after profiling.
    private static final int MAX_IN_FLIGHT_JOBS = 256;
    private static final int MAX_LIVE_SECTION_MESHES = 48_000;
    private static final int MAX_DEFERRED_TASKS = 65_536;
    private static final int NATIVE_MIN_SECTIONS_PER_TICK = 64;
    private static final int NATIVE_MIN_UPLOAD_DRAIN_PER_FRAME = 48;
    private static final int NATIVE_MIN_PENDING_UPLOADS = 1024;
    private static final int MIN_SOLID_REPLACE_VBOS = 128;
    private static final int MAX_SOLID_REPLACE_DEFERRED = 256;
    private static final long TELEMETRY_EVERY_NS = 2_000_000_000L;
    // OPTIMIZATION 2: Frame budget — driven by config (defaults to 2ms Sodium baseline)
    private static final AtomicLong avgMeshBuildNanos = new AtomicLong(2_000_000L);  // ~2ms initial estimate
    // OPTIMIZATION 3: Sodium-style priority tiers, but in chunk-distance units.
    // 256 blocks ~= 16 chunks, 1024 blocks ~= 64 chunks.
    private static final int NEARBY_REBUILD_CHUNK_DIST_SQ = 16 * 16;
    private static final int PRIORITY_CHUNK_DIST_SQ = 64 * 64;
    private static final ConcurrentLinkedQueue<SectionTask> deferredTasks = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, Integer> sectionVbos = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> sectionVertCounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, ByteBuffer> sectionVertexData = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, BatchRange> sectionBatchRanges = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> sectionUploadComplete = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> sectionUploadFrame = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, OcclusionCacheEntry> occlusionCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, TemporalVisibilityEntry> temporalVisibilityCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, ConcurrentLinkedQueue<PendingUpload>> stagedUploadsByRegion = new ConcurrentHashMap<>();
    private static final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private static final Set<Long> processed = ConcurrentHashMap.newKeySet();
    private static final Set<Long> pendingUploadKeys = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<PendingUpload> pendingUploads = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pendingUploadCount = new AtomicInteger();
    private static final AtomicReference<ExecutorService> workersRef = new AtomicReference<>();
    private static volatile int workerCount = -1;
    private static final AtomicLong statScannedSections = new AtomicLong();
    private static final AtomicLong statSubmittedJobs = new AtomicLong();
    private static final AtomicLong statBuiltSections = new AtomicLong();
    private static final AtomicLong statBuildNanos = new AtomicLong();
    private static final AtomicLong statUploadBytes = new AtomicLong();
    private static final AtomicLong statOccludedSections = new AtomicLong();
    private static final AtomicLong statTemporalHits = new AtomicLong();
    private static final AtomicLong scanSequence = new AtomicLong();
    private static final AtomicLong renderFrameSequence = new AtomicLong();
    private static final AtomicLong statStagedUploads = new AtomicLong();
    private static final AtomicInteger statBuiltLogSamples = new AtomicInteger();
    private static final AtomicInteger statUploadLogSamples = new AtomicInteger();
    private static volatile long lastScanNs = System.nanoTime();
    private static volatile long lastTelemetryNs = System.nanoTime();
    private static volatile long lastDrawDiagnosticsNs = System.nanoTime();
    private static volatile long lastSolidReplaceDiagnosticsNs = System.nanoTime();
    private static volatile boolean solidLayerReplacing = false;
    private static volatile int adaptiveSectionsPerTick = 1;
    private static volatile int adaptiveUploadDrainPerFrame = 1;
    private static volatile double adaptivePressureEma = 0.0;
    // Reused worker-local scratch buffers avoid expensive per-job max-size allocations.
    private static final ThreadLocal<ByteBuffer> TL_BLOCK_BUF = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(BLOCK_BUF_BYTES).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> TL_OUT_SCRATCH = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(OUT_BUF_BYTES).order(ByteOrder.nativeOrder()));
    private static int sharedVao = -1;
    private static boolean glReady = false;
    private static volatile boolean drawListDirty = true;
    private static volatile long[] drawKeysCompact = new long[0];
    private static volatile int[] drawVbosCompact = new int[0];
    private static volatile int[] drawCountsCompact = new int[0];
    private static int batchedVbo = -1;
    private static int batchedVertexCapacity = 0;
    private static int batchedNextVertex = 0;
    private static volatile boolean multiDrawDirty = true;
    private static volatile IntBuffer multiDrawFirsts = emptyDirectIntBuffer();
    private static volatile IntBuffer multiDrawCounts = emptyDirectIntBuffer();
    private static volatile int multiDrawCommandCount = 0;
    private static volatile long multiDrawVertexCount = 0L;
    private static volatile boolean batchedNeedsCompactRebuild = false;
    private static volatile long activeRenderFrame = 0L;
    private static volatile double lastCameraX = Double.NaN;
    private static volatile double lastCameraY = Double.NaN;
    private static volatile double lastCameraZ = Double.NaN;
    private static volatile Level activeLevel = null;

    private GpuMeshManager() {
    }

    private static IntBuffer emptyDirectIntBuffer() {
        return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    public static int liveMeshCount() {
        return sectionVbos.size();
    }

    public static int pendingUploadCount() {
        return pendingUploadCount.get();
    }

    public static int inFlightCount() {
        return inFlight.size();
    }

    /**
     * True when the Plutonium solid terrain pass should be drawn instead of
     * vanilla's solid chunk layer.
     */
    public static boolean shouldDrawNativeMeshes() {
        return Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE;
    }

    public static boolean shouldSuppressVanillaRebuild(int cx, int sy, int cz) {
        // Suppress vanilla rebuild only when a native VBO is already live for the
        // section. Sections without a built VBO yet must fall through to vanilla
        // so they don't render as holes while native catches up.
        long k = key(cx, sy, cz);
        return sectionVbos.containsKey(k);
    }

    public static boolean shouldReplaceVanillaSolidLayer() {
        if (!shouldDrawNativeMeshes()) {
            solidLayerReplacing = false;
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        int renderDist = mc != null && mc.options != null ? mc.options.getEffectiveRenderDistance() : 12;
        int requiredVbos = Math.max(MIN_SOLID_REPLACE_VBOS, Math.min(1024, (renderDist * renderDist) / 2));
        int mirrored = PlutoniumSectionRegistry.sectionCount(PlutoniumSectionRegistry.ChunkLayer.SOLID);
        boolean ready = PlutoniumMegaBuffer.isReady() && mirrored >= requiredVbos;

        emitSolidReplaceDiagnosticsIfChangedOrDue(ready, mirrored, requiredVbos);
        solidLayerReplacing = ready;
        return ready;
    }

    private static int meshPipelineTick;

    /** Called once per client tick (20 Hz), not every render frame. */
    public static void tickMeshPipeline(Minecraft mc) {
        if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE || mc == null || mc.level == null || mc.player == null) {
            return;
        }
        meshPipelineTick++;
        scanNearbyChunks(mc);
    }

    private static void resetTransientStateForLevel(Level level) {
        if (activeLevel == level) {
            return;
        }
        synchronized (GpuMeshManager.class) {
            if (activeLevel == level) {
                return;
            }
            ArrayList<Integer> oldVbos = new ArrayList<>(sectionVbos.values());
            final int oldBatchedVbo = batchedVbo;
            activeLevel = level;
            sectionVbos.clear();
            sectionVertCounts.clear();
            sectionVertexData.clear();
            sectionBatchRanges.clear();
            sectionUploadComplete.clear();
            sectionUploadFrame.clear();
            occlusionCache.clear();
            temporalVisibilityCache.clear();
            stagedUploadsByRegion.clear();
            processed.clear();
            inFlight.clear();
            pendingUploads.clear();
            pendingUploadKeys.clear();
            pendingUploadCount.set(0);
            deferredTasks.clear();
            drawKeysCompact = new long[0];
            drawVbosCompact = new int[0];
            drawCountsCompact = new int[0];
            drawListDirty = true;
            batchedVbo = -1;
            batchedVertexCapacity = 0;
            batchedNextVertex = 0;
            batchedNeedsCompactRebuild = false;
            multiDrawDirty = true;
            multiDrawFirsts = emptyDirectIntBuffer();
            multiDrawCounts = emptyDirectIntBuffer();
            multiDrawCommandCount = 0;
            multiDrawVertexCount = 0L;
            activeRenderFrame = 0L;
            lastCameraX = Double.NaN;
            lastCameraY = Double.NaN;
            lastCameraZ = Double.NaN;
            solidLayerReplacing = false;
            lastSolidReplaceDiagnosticsNs = System.nanoTime();
            adaptiveSectionsPerTick = Math.max(1, Config.CLIENT.meshSectionsPerTick.get());
            adaptiveUploadDrainPerFrame = Math.max(1, Config.CLIENT.meshUploadDrainPerFrame.get());
            adaptivePressureEma = 0.0;
            statBuiltLogSamples.set(0);
            statUploadLogSamples.set(0);
            if (!oldVbos.isEmpty()) {
                RenderSystem.recordRenderCall(() -> oldVbos.forEach(GL15::glDeleteBuffers));
            }
            if (oldBatchedVbo != -1) {
                RenderSystem.recordRenderCall(() -> GL15.glDeleteBuffers(oldBatchedVbo));
            }
            LOGGER.info("[Plutonium/Mesh] Reset native mesh queues for level switch.");
        }
    }

    private static long key(int cx, int sy, int cz) {
        return ((long) (cx & 0x3FFFFF)) | ((long) (sy & 0xFFF) << 22) | ((long) (cz & 0x3FFFFF) << 34);
    }

    private static ExecutorService createWorkers(int count) {
        return Executors.newFixedThreadPool(count, r -> {
            Thread t = new Thread(r, "Pluto-MeshWorker");
            t.setDaemon(true);
            return t;
        });
    }

    private static boolean submitBuildTask(ExecutorService workers, Level level, SectionTask task, long enginePtr) {
        if (workers == null || workers.isShutdown()) {
            inFlight.remove(task.key());
            return false;
        }
        try {
            workers.submit(() -> buildAsync(level, task.cx(), task.sy(), task.cz(), task.si(), enginePtr, task.key()));
            return true;
        } catch (RejectedExecutionException ex) {
            // Worker pool can be torn down during level transitions; skip safely.
            inFlight.remove(task.key());
            return false;
        }
    }

    private static void ensureWorkerPool() {
        int configured = Math.max(1, Config.CLIENT.cpuThreads.get());
        if (configured == workerCount && workersRef.get() != null) {
            return;
        }
        synchronized (GpuMeshManager.class) {
            if (configured == workerCount && workersRef.get() != null) {
                return;
            }
            ExecutorService old = workersRef.getAndSet(createWorkers(configured));
            workerCount = configured;
            if (old != null) {
                old.shutdownNow();
            }
        }
    }

    private static int sectionsPerTick() {
        if (Config.CLIENT.meshAdaptiveBudget.get()) {
            int adaptive = Math.max(1, adaptiveSectionsPerTick);
            return shouldDrawNativeMeshes() ? Math.max(adaptive, NATIVE_MIN_SECTIONS_PER_TICK) : adaptive;
        }
        int configured = Math.max(1, Config.CLIENT.meshSectionsPerTick.get());
        return shouldDrawNativeMeshes() ? Math.max(configured, NATIVE_MIN_SECTIONS_PER_TICK) : configured;
    }

    private static int maxPendingUploads() {
        int configured = Math.max(8, Config.CLIENT.meshMaxUploadsPerFrame.get());
        return shouldDrawNativeMeshes() ? Math.max(configured, NATIVE_MIN_PENDING_UPLOADS) : configured;
    }

    private static long uploadBudgetNs() {
        return Math.max(500_000L, (long) Config.CLIENT.meshUploadBudgetMicros.get() * 1000L);
    }

    private static int uploadDrainPerFrame() {
        if (Config.CLIENT.meshAdaptiveBudget.get()) {
            int adaptive = Math.max(1, adaptiveUploadDrainPerFrame);
            return shouldDrawNativeMeshes() ? Math.max(adaptive, NATIVE_MIN_UPLOAD_DRAIN_PER_FRAME) : adaptive;
        }
        int configured = Math.max(1, Config.CLIENT.meshUploadDrainPerFrame.get());
        return shouldDrawNativeMeshes() ? Math.max(configured, NATIVE_MIN_UPLOAD_DRAIN_PER_FRAME) : configured;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int nativeSolidBlockId(Level level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) {
            return 0;
        }
        if (!state.getFluidState().isEmpty()) {
            return 0;
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return 0;
        }
        if (!state.canOcclude() || !state.isSolidRender(level, pos)) {
            return 0;
        }
        return Block.getId(state);
    }

    private static void updateAdaptiveBudgets() {
        int baseSections = Math.max(1, Config.CLIENT.meshSectionsPerTick.get());
        int baseDrain = Math.max(1, Config.CLIENT.meshUploadDrainPerFrame.get());

        if (!Config.CLIENT.meshAdaptiveBudget.get()) {
            adaptiveSectionsPerTick = baseSections;
            adaptiveUploadDrainPerFrame = baseDrain;
            adaptivePressureEma = 0.0;
            return;
        }

        int minSections = Math.max(1, Math.min(Config.CLIENT.meshAdaptiveMinSectionsPerTick.get(), Config.CLIENT.meshAdaptiveMaxSectionsPerTick.get()));
        int maxSections = Math.max(minSections, Math.max(Config.CLIENT.meshAdaptiveMinSectionsPerTick.get(), Config.CLIENT.meshAdaptiveMaxSectionsPerTick.get()));
        int minDrain = Math.max(1, Math.min(Config.CLIENT.meshAdaptiveMinUploadDrain.get(), Config.CLIENT.meshAdaptiveMaxUploadDrain.get()));
        int maxDrain = Math.max(minDrain, Math.max(Config.CLIENT.meshAdaptiveMinUploadDrain.get(), Config.CLIENT.meshAdaptiveMaxUploadDrain.get()));
        if (Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE) {
            maxSections = Math.max(maxSections, 8);
            maxDrain = Math.max(maxDrain, 12);
        }

        double pendingPressure = clamp01((double) pendingUploadCount.get() / (double) Math.max(1, maxPendingUploads()));
        double deferredPressure = clamp01((double) deferredTasks.size() / (double) MAX_DEFERRED_TASKS);
        double inFlightPressure = clamp01((double) inFlight.size() / (double) MAX_IN_FLIGHT_JOBS);
        double buildPressure = clamp01((double) avgMeshBuildNanos.get() / (double) uploadBudgetNs());

        double congestion = (pendingPressure * 0.50)
                + (inFlightPressure * 0.25)
                + (buildPressure * 0.25);
        double demand = Math.max(deferredPressure, sectionVbos.size() < MIN_SOLID_REPLACE_VBOS ? 1.0 : 0.0);
        double pressure = clamp01(demand * (1.0 - congestion));
        adaptivePressureEma = (adaptivePressureEma * 0.80) + (pressure * 0.20);

        int targetSections = clampInt(
                (int) Math.round(minSections + ((maxSections - minSections) * adaptivePressureEma)),
                minSections,
                maxSections);
        int targetDrain = clampInt(
                (int) Math.round(minDrain + ((maxDrain - minDrain) * Math.max(pendingPressure, deferredPressure * 0.5))),
                minDrain,
                maxDrain);

        adaptiveSectionsPerTick = clampInt(targetSections, 1, Math.max(baseSections, maxSections));
        adaptiveUploadDrainPerFrame = clampInt(targetDrain, 1, Math.max(baseDrain, maxDrain));
    }

    private static int uploadRegionsPerFrame() {
        return Math.max(1, Config.CLIENT.meshUploadRegionsPerFrame.get());
    }

    private static ByteBuffer copyVertexData(ByteBuffer src, int bytes) {
        ByteBuffer copy = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        ByteBuffer read = src.duplicate().order(ByteOrder.nativeOrder());
        read.position(0).limit(bytes);
        copy.put(read);
        copy.flip();
        return copy;
    }

    private static void ensureBatchedVboAllocated() {
        if (batchedVbo == -1) {
            batchedVbo = GL15.glGenBuffers();
        }
    }

    private static void allocateBatchedVbo(int vertexCapacity) {
        ensureBatchedVboAllocated();
        batchedVertexCapacity = Math.max(1, vertexCapacity);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchedVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) batchedVertexCapacity * VERTEX_BYTES, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static void uploadRangeToBatchedVbo(int firstVertex, ByteBuffer data, int count) {
        ensureBatchedVboAllocated();
        ByteBuffer src = data.duplicate().order(ByteOrder.nativeOrder());
        src.position(0).limit(count * VERTEX_BYTES);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchedVbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) firstVertex * VERTEX_BYTES, src);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static void rebuildBatchedVboFromCpuLocked() {
        rebuildBatchedVboFromCpuLocked(true);
    }

    private static void rebuildBatchedVboFromCpuLocked(boolean growForFutureUploads) {
        ArrayList<Map.Entry<Long, ByteBuffer>> entries = new ArrayList<>(sectionVertexData.entrySet());
        entries.removeIf(e -> {
            Integer count = sectionVertCounts.get(e.getKey());
            return count == null || count <= 0 || e.getValue() == null;
        });
        entries.sort(Comparator.comparingLong(Map.Entry::getKey));

        long totalVertsLong = 0L;
        for (Map.Entry<Long, ByteBuffer> entry : entries) {
            totalVertsLong += sectionVertCounts.getOrDefault(entry.getKey(), 0);
        }
        if (totalVertsLong > Integer.MAX_VALUE) {
            LOGGER.error("[Plutonium/Mesh] Batched VBO too large: {} vertices.", totalVertsLong);
            return;
        }

        int totalVerts = (int) totalVertsLong;
        int nextCapacity = growForFutureUploads
                ? Math.max(totalVerts, Math.max(1, batchedVertexCapacity * 2))
                : Math.max(1, totalVerts);
        allocateBatchedVbo(nextCapacity);
        sectionBatchRanges.clear();
        batchedNextVertex = 0;
        for (Map.Entry<Long, ByteBuffer> entry : entries) {
            int count = sectionVertCounts.get(entry.getKey());
            int first = batchedNextVertex;
            sectionBatchRanges.put(entry.getKey(), new BatchRange(first, count));
            uploadRangeToBatchedVbo(first, entry.getValue(), count);
            batchedNextVertex += count;
        }
        multiDrawDirty = true;
        batchedNeedsCompactRebuild = false;
    }

    private static void uploadToBatchedVbo(long key, ByteBuffer data, int count) {
        synchronized (GpuMeshManager.class) {
            sectionVertexData.put(key, data.asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
            BatchRange old = sectionBatchRanges.get(key);
            if (old != null && old.capacityVerts() >= count) {
                sectionBatchRanges.put(key, new BatchRange(old.firstVertex(), old.capacityVerts()));
                uploadRangeToBatchedVbo(old.firstVertex(), data, count);
                multiDrawDirty = true;
                return;
            }

            int required = batchedNextVertex + count;
            if (batchedVbo == -1 || required > batchedVertexCapacity) {
                rebuildBatchedVboFromCpuLocked();
                return;
            }

            int first = batchedNextVertex;
            batchedNextVertex += count;
            sectionBatchRanges.put(key, new BatchRange(first, count));
            uploadRangeToBatchedVbo(first, data, count);
            multiDrawDirty = true;
        }
    }

    private static void uploadToGl(PendingUpload u) {
        if (!pendingUploadKeys.contains(u.key)) {
            return;
        }
        int expectedBytes = u.count * VERTEX_BYTES;
        if (u.count <= 0 || u.count > MAX_VERTS || u.vertBuffer.limit() < expectedBytes) {
            LOGGER.error("[Plutonium/Mesh] Rejecting invalid upload key={} verts={} limit={} expectedBytes={}.",
                    u.key, u.count, u.vertBuffer.limit(), expectedBytes);
            pendingUploadKeys.remove(u.key);
            sectionUploadComplete.remove(u.key);
            sectionUploadFrame.remove(u.key);
            return;
        }
        if ((u.count % 3) != 0) {
            LOGGER.error("[Plutonium/Mesh] Rejecting non-triangle-aligned upload key={} verts={}.", u.key, u.count);
            pendingUploadKeys.remove(u.key);
            sectionUploadComplete.remove(u.key);
            sectionUploadFrame.remove(u.key);
            return;
        }
        if ((u.count % 6) != 0) {
            LOGGER.warn("[Plutonium/Mesh] Upload key={} verts={} is triangle-aligned but not face-aligned.", u.key, u.count);
        }
        ByteBuffer sampleBuf = u.vertBuffer.duplicate().order(ByteOrder.nativeOrder());
        float sx = sampleBuf.getFloat(0);
        float sy = sampleBuf.getFloat(4);
        float sz = sampleBuf.getFloat(8);
        float su = sampleBuf.getFloat(16);
        float sv = sampleBuf.getFloat(20);
        if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)
                || !Float.isFinite(su) || !Float.isFinite(sv)) {
            LOGGER.error("[Plutonium/Mesh] Rejecting non-finite upload key={} verts={} pos=({}, {}, {}) uv=({}, {}).",
                    u.key, u.count, sx, sy, sz, su, sv);
            pendingUploadKeys.remove(u.key);
            sectionUploadComplete.remove(u.key);
            sectionUploadFrame.remove(u.key);
            return;
        }
        sectionUploadComplete.put(u.key, Boolean.FALSE);
        ByteBuffer stored = copyVertexData(u.vertBuffer, expectedBytes);
        sectionVertCounts.put(u.key, u.count);
        uploadToBatchedVbo(u.key, stored, u.count);
        int glErr = GL11.glGetError();
        if (glErr != GL11.GL_NO_ERROR) {
            LOGGER.error("[Plutonium/Mesh] batched VBO upload failed key={} verts={} bytes={} glError=0x{}.",
                    u.key, u.count, expectedBytes, Integer.toHexString(glErr));
            pendingUploadKeys.remove(u.key);
            sectionUploadComplete.remove(u.key);
            sectionUploadFrame.remove(u.key);
            return;
        }
        sectionVbos.put(u.key, 0);
        sectionUploadComplete.put(u.key, Boolean.TRUE);
        sectionUploadFrame.put(u.key, activeRenderFrame);
        pendingUploadKeys.remove(u.key);
        multiDrawDirty = true;
        drawListDirty = true;
        int sample = statUploadLogSamples.incrementAndGet();
        if (sample <= 16 || (sample % 256) == 0) {
            LOGGER.info("[Plutonium/Mesh] Uploaded section mesh to GL (sample={}, key={}, chunk={},sy={},{} verts={}, bytes={}, firstPos=({}, {}, {}), firstUv=({}, {}), liveVbos={}).",
                    sample, u.key, unpackChunkX(u.key), (int) ((u.key >> 22) & 0xFFF), unpackChunkZ(u.key),
                    u.count, expectedBytes,
                    String.format(Locale.ROOT, "%.2f", sx),
                    String.format(Locale.ROOT, "%.2f", sy),
                    String.format(Locale.ROOT, "%.2f", sz),
                    String.format(Locale.ROOT, "%.5f", su),
                    String.format(Locale.ROOT, "%.5f", sv),
                    sectionVbos.size());
        }
    }

    private static void stagePendingUploadsByRegion(int intakeBudget) {
        int moved = 0;
        PendingUpload u;
        while (moved < intakeBudget && (u = pendingUploads.poll()) != null) {
            pendingUploadCount.decrementAndGet();
            stagedUploadsByRegion
                    .computeIfAbsent(u.regionKey, ignored -> new ConcurrentLinkedQueue<>())
                    .add(u);
            moved++;
        }
        if (moved > 0) {
            statStagedUploads.addAndGet(moved);
        }
    }

    private static int drainStagedUploads(int frameDrainBudget) {
        if (stagedUploadsByRegion.isEmpty()) {
            return 0;
        }

        ArrayList<Map.Entry<Long, ConcurrentLinkedQueue<PendingUpload>>> regions = new ArrayList<>(stagedUploadsByRegion.entrySet());
        regions.removeIf(e -> e.getValue().isEmpty());
        if (regions.isEmpty()) {
            return 0;
        }

        regions.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        int regionBudget = uploadRegionsPerFrame();
        int uploadsDone = 0;

        for (int i = 0; i < regions.size() && i < regionBudget && uploadsDone < frameDrainBudget; i++) {
            var entry = regions.get(i);
            var queue = entry.getValue();
            PendingUpload u;
            while (uploadsDone < frameDrainBudget && (u = queue.poll()) != null) {
                uploadToGl(u);
                uploadsDone++;
            }
            if (queue.isEmpty()) {
                stagedUploadsByRegion.remove(entry.getKey(), queue);
            }
        }

        return uploadsDone;
    }

    private static void evictFarSectionMeshes(int centerCx, int centerCz, int renderDist) {
        int keepRadius = Math.max(8, renderDist + 4);
        int keepRadiusSq = keepRadius * keepRadius;
        ArrayList<Long> removeKeys = new ArrayList<>();

        for (Long key : sectionVertCounts.keySet()) {
            int dx = unpackChunkX(key) - centerCx;
            int dz = unpackChunkZ(key) - centerCz;
            if (dx * dx + dz * dz > keepRadiusSq) {
                removeKeys.add(key);
            }
        }
        if (removeKeys.isEmpty()) {
            return;
        }

        synchronized (GpuMeshManager.class) {
            for (Long key : removeKeys) {
                sectionVbos.remove(key);
                sectionVertCounts.remove(key);
                sectionVertexData.remove(key);
                sectionBatchRanges.remove(key);
                sectionUploadComplete.remove(key);
                sectionUploadFrame.remove(key);
                temporalVisibilityCache.remove(key);
                occlusionCache.remove(key);
                pendingUploadKeys.remove(key);
                processed.remove(key);
            }
            drawListDirty = true;
            multiDrawDirty = true;
            batchedNeedsCompactRebuild = true;
        }

        LOGGER.info("[Plutonium/Mesh] Evicted {} native section meshes outside {} chunk radius (center={},{} liveVbos={}).",
                removeKeys.size(), keepRadius, centerCx, centerCz, sectionVbos.size());
    }

    private static int unpackSigned22(long value) {
        int v = (int) (value & 0x3FFFFF);
        if ((v & (1 << 21)) != 0) {
            v |= ~0x3FFFFF;
        }
        return v;
    }

    private static int unpackChunkX(long key) {
        return unpackSigned22(key);
    }

    private static int unpackChunkZ(long key) {
        return unpackSigned22(key >> 34);
    }

    private static void rebuildCompactDrawListIfNeeded() {
        if (!Config.CLIENT.meshDrawListCompaction.get() || !drawListDirty) {
            return;
        }
        synchronized (GpuMeshManager.class) {
            if (!drawListDirty) {
                return;
            }

            ArrayList<DrawEntry> entries = new ArrayList<>(sectionVbos.size());
            for (Map.Entry<Long, Integer> entry : sectionVbos.entrySet()) {
                if (!isSectionReadyToDraw(entry.getKey())) {
                    continue;
                }
                Integer count = sectionVertCounts.get(entry.getKey());
                if (count == null || count <= 0) {
                    continue;
                }
                entries.add(new DrawEntry(entry.getKey(), entry.getValue(), count));
            }

            if (Config.CLIENT.meshRegionBatching.get()) {
                entries.sort(Comparator
                        .comparingLong((DrawEntry d) -> regionKeyForChunk(unpackChunkX(d.key), unpackChunkZ(d.key)))
                        .thenComparingLong(d -> d.key));
            }

            long[] keys = new long[entries.size()];
            int[] vbos = new int[entries.size()];
            int[] counts = new int[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                DrawEntry d = entries.get(i);
                keys[i] = d.key;
                vbos[i] = d.vbo;
                counts[i] = d.count;
            }

            drawKeysCompact = keys;
            drawVbosCompact = vbos;
            drawCountsCompact = counts;
            drawListDirty = false;
        }
    }

    private static void rebuildMultiDrawArraysIfNeeded() {
        if (!multiDrawDirty) {
            return;
        }
        synchronized (GpuMeshManager.class) {
            if (!multiDrawDirty) {
                return;
            }

            ArrayList<DrawRange> ranges = new ArrayList<>(sectionBatchRanges.size());
            boolean skippedFreshUpload = false;
            long frame = activeRenderFrame;
            for (Map.Entry<Long, BatchRange> entry : sectionBatchRanges.entrySet()) {
                long key = entry.getKey();
                if (!isSectionReadyToDraw(key)) {
                    Long uploadedFrame = sectionUploadFrame.get(key);
                    if (Boolean.TRUE.equals(sectionUploadComplete.get(key))
                            && uploadedFrame != null
                            && uploadedFrame >= frame) {
                        skippedFreshUpload = true;
                    }
                    continue;
                }
                Integer count = sectionVertCounts.get(entry.getKey());
                if (count == null || count <= 0) {
                    continue;
                }
                ranges.add(new DrawRange(entry.getKey(), entry.getValue().firstVertex(), count));
            }
            if (Config.CLIENT.meshRegionBatching.get()) {
                ranges.sort(Comparator
                        .comparingLong((DrawRange d) -> regionKeyForChunk(unpackChunkX(d.key()), unpackChunkZ(d.key())))
                        .thenComparingLong(DrawRange::key));
            } else {
                ranges.sort(Comparator.comparingLong(DrawRange::key));
            }

            IntBuffer firsts = ByteBuffer.allocateDirect(ranges.size() * Integer.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            IntBuffer counts = ByteBuffer.allocateDirect(ranges.size() * Integer.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            long verts = 0L;
            for (DrawRange range : ranges) {
                firsts.put(range.firstVertex());
                counts.put(range.count());
                verts += range.count();
            }
            firsts.flip();
            counts.flip();

            multiDrawFirsts = firsts;
            multiDrawCounts = counts;
            multiDrawCommandCount = ranges.size();
            multiDrawVertexCount = verts;
            multiDrawDirty = skippedFreshUpload;
        }
    }

    private static void repairIdlePendingUploadState() {
        if (!pendingUploads.isEmpty() || !stagedUploadsByRegion.isEmpty()) {
            return;
        }

        int leakedCount = pendingUploadCount.getAndSet(0);
        int repairedKeys = 0;
        for (Long key : new ArrayList<>(pendingUploadKeys)) {
            if (sectionVbos.containsKey(key) || inFlight.contains(key)) {
                continue;
            }
            pendingUploadKeys.remove(key);
            sectionUploadComplete.remove(key);
            sectionUploadFrame.remove(key);
            processed.remove(key);
            repairedKeys++;
        }

        if (leakedCount != 0 || repairedKeys != 0) {
            drawListDirty = true;
            multiDrawDirty = true;
            LOGGER.warn("[Plutonium/Mesh] Repaired stale upload state (counter={}, keys={}).",
                    leakedCount, repairedKeys);
        }
    }

    private static boolean isSectionReadyToDraw(long key) {
        if (!Boolean.TRUE.equals(sectionUploadComplete.get(key))) {
            return false;
        }
        if (pendingUploadKeys.contains(key)) {
            return false;
        }
        Long uploadedFrame = sectionUploadFrame.get(key);
        return uploadedFrame != null && uploadedFrame < activeRenderFrame;
    }

    private static DrawStats renderCompactedDrawList() {
        rebuildMultiDrawArraysIfNeeded();
        if (batchedVbo == -1 || multiDrawCommandCount <= 0) {
            return new DrawStats(0, 0);
        }

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchedVbo);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT,         false, VERTEX_BYTES, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true,  VERTEX_BYTES, 12L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT,         false, VERTEX_BYTES, 16L);
        GL20.glEnableVertexAttribArray(2);
        GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, VERTEX_BYTES, 24L);
        GL20.glEnableVertexAttribArray(3);
        GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, multiDrawFirsts.duplicate(), multiDrawCounts.duplicate());
        return new DrawStats(1, multiDrawVertexCount);
    }

    private static boolean shouldUseTemporalCache(double playerX, double playerY, double playerZ) {
        if (!Config.CLIENT.meshTemporalCoherence.get()) {
            return false;
        }
        if (Double.isNaN(lastCameraX)) {
            return false;
        }
        double dx = playerX - lastCameraX;
        double dy = playerY - lastCameraY;
        double dz = playerZ - lastCameraZ;
        double threshold = Math.max(1, Config.CLIENT.meshTemporalCameraMoveThreshold.get());
        return (dx * dx + dy * dy + dz * dz) <= (threshold * threshold);
    }

    private static TemporalVisibilityDecision evaluateTemporalVisibility(long sectionKey, long currentScan, boolean temporalEnabled) {
        if (!temporalEnabled) {
            return TemporalVisibilityDecision.COMPUTE;
        }
        TemporalVisibilityEntry entry = temporalVisibilityCache.get(sectionKey);
        if (entry == null) {
            return TemporalVisibilityDecision.COMPUTE;
        }

        int maxReuse = Math.max(1, Config.CLIENT.meshTemporalReuseFrames.get());
        if ((currentScan - entry.scanId) > maxReuse) {
            return TemporalVisibilityDecision.COMPUTE;
        }

        statTemporalHits.incrementAndGet();
        return entry.visible ? TemporalVisibilityDecision.VISIBLE : TemporalVisibilityDecision.CULLED;
    }

    private static long regionKeyForChunk(int cx, int cz) {
        int batchSize = Math.max(2, Config.CLIENT.meshRegionBatchChunkSize.get());
        int rx = Math.floorDiv(cx, batchSize);
        int rz = Math.floorDiv(cz, batchSize);
        return (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL);
    }

    private static int frontierScore(Minecraft mc, double playerX, double playerY, double playerZ, int cx, int sy, int cz, int chunkDistSq) {
        if (!Config.CLIENT.meshFrontierOrdering.get() || mc.player == null) {
            return chunkDistSq * 1024;
        }

        double tx = (cx * 16.0 + 8.0) - playerX;
        double ty = (sy * 16.0 + 8.0) - playerY;
        double tz = (cz * 16.0 + 8.0) - playerZ;
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 0.0001) {
            return chunkDistSq * 1024;
        }

        var look = mc.player.getLookAngle();
        double align = ((look.x * tx) + (look.y * ty) + (look.z * tz)) / len; // [-1, 1]
        int alignBias = (int) Math.round(align * 256.0);

        return (chunkDistSq * 1024) - alignBias;
    }

    private static List<SectionTask> orderTasksForSubmission(List<SectionTask> tasks) {
        if (tasks.isEmpty()) {
            return tasks;
        }

        Comparator<SectionTask> perTaskOrder = Comparator
                .comparingInt(SectionTask::frontier)
                .thenComparingInt(SectionTask::dist2);

        if (!Config.CLIENT.meshRegionBatching.get()) {
            tasks.sort(perTaskOrder);
            return tasks;
        }

        HashMap<Long, ArrayList<SectionTask>> byRegion = new HashMap<>();
        for (SectionTask task : tasks) {
            byRegion.computeIfAbsent(task.regionKey(), ignored -> new ArrayList<>()).add(task);
        }

        ArrayList<ArrayList<SectionTask>> regions = new ArrayList<>(byRegion.values());
        for (ArrayList<SectionTask> regionTasks : regions) {
            regionTasks.sort(perTaskOrder);
        }

        regions.sort((a, b) -> {
            int af = a.get(0).frontier();
            int bf = b.get(0).frontier();
            if (af != bf) {
                return Integer.compare(af, bf);
            }
            return Integer.compare(b.size(), a.size());
        });

        ArrayList<SectionTask> ordered = new ArrayList<>(tasks.size());
        for (ArrayList<SectionTask> regionTasks : regions) {
            ordered.addAll(regionTasks);
        }

        return ordered;
    }

    private static boolean isOccludedWithCache(Level level, long sectionKey, double playerX, double playerY, double playerZ, int cx, int sy, int cz, int chunkDistSq, long scanId) {
        if (!Config.CLIENT.meshTerrainOcclusion.get()) {
            return false;
        }
        if (chunkDistSq <= NEARBY_REBUILD_CHUNK_DIST_SQ) {
            return false;
        }

        OcclusionCacheEntry cached = occlusionCache.get(sectionKey);
        if (cached != null && (scanId - cached.scanId) <= 4) {
            return cached.occluded;
        }

        boolean occluded = isTerrainOccluded(level, playerX, playerY, playerZ, cx, sy, cz);
        occlusionCache.put(sectionKey, new OcclusionCacheEntry(occluded, scanId));
        return occluded;
    }

    // Terrain ray occlusion test for farther sections.
    private static boolean isTerrainOccluded(Level level, double playerX, double playerY, double playerZ, int cx, int sy, int cz) {
        double targetX = cx * 16.0 + 8.0;
        double targetY = sy * 16.0 + 8.0;
        double targetZ = cz * 16.0 + 8.0;

        double dx = targetX - playerX;
        double dy = targetY - playerY;
        double dz = targetZ - playerZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 64.0) {
            return false;
        }

        int rayStep = Math.max(2, Config.CLIENT.meshOcclusionRayStep.get());
        int maxSamples = Math.max(8, Config.CLIENT.meshOcclusionMaxSamples.get());
        int samples = Math.min(maxSamples, Math.max(6, (int) (distance / rayStep)));

        double stepX = dx / samples;
        double stepY = dy / samples;
        double stepZ = dz / samples;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int solidRun = 0;

        for (int i = 2; i < samples - 2; i++) {
            int wx = (int) Math.floor(playerX + (stepX * i));
            int wy = (int) Math.floor(playerY + (stepY * i));
            int wz = (int) Math.floor(playerZ + (stepZ * i));
            probe.set(wx, wy, wz);

            var state = level.getBlockState(probe);
            if (!state.isAir() && state.canOcclude()) {
                solidRun++;
                if (solidRun >= 5) {
                    return true;
                }
            } else {
                solidRun = 0;
            }
        }
        return false;
    }

    // OPTIMIZATION 1: Simple frustum culling — test if chunk section is visible to camera
    // Chunk sections are 16x16x16; expand by 1 block for model extent safety
    private static boolean isChunkSectionVisible(Minecraft mc, double playerX, double playerY, double playerZ, int cx, int sy, int cz) {
        // Chunk center in block coords
        float centerX = cx * 16.0f + 8.0f;
        float centerY = sy * 16.0f + 8.0f;
        float centerZ = cz * 16.0f + 8.0f;

        // Vector from camera to chunk center
        float dx = (float) (centerX - playerX);
        float dy = (float) (centerY - playerY);
        float dz = (float) (centerZ - playerZ);

        // Chunk "radius" (half-size + margin for model extent)
        float radius = 8.0f + 1.125f;  // 16/2 + model extent + epsilon

        // Very close chunks: always include
        if (dx * dx + dy * dy + dz * dz < radius * radius) {
            return true;
        }

        // Rough Y-visibility: chunks more than 40 blocks above or below camera are less likely to be visible
        // This is a relaxed check; a proper frustum test would be more precise but also more expensive
        if (Math.abs(dy) > 40.0f) {
            return false;
        }

        // Chunks very far away horizontally: cull aggressively
        if (dx * dx + dz * dz > 512.0f * 512.0f) {  // ~500 block radius
            return false;
        }

        // Extra Embeddium-style directional culling for sections behind camera.
        if (Config.CLIENT.meshCameraDirectionCull.get()) {
            var look = mc.player.getLookAngle();
            double dot = (look.x * dx) + (look.y * dy) + (look.z * dz);
            if (dot < -24.0) {
                return false;
            }
        }

        return true;
    }

    // ── Render tick: queue nearby sections for GPU meshing ────────────────────
    public static void scanNearbyChunks(Minecraft mc) {
        try {
        long currentScan = scanSequence.incrementAndGet();
        updateAdaptiveBudgets();
        ensureWorkerPool();
        ExecutorService workers = workersRef.get();
        if (workers == null || workers.isShutdown()) {
            return;
        }
        final int maxUploads = maxPendingUploads();
        final int maxSectionsPerTick = sectionsPerTick();
        final long frameBudgetNs = uploadBudgetNs();
        // Meshing routes through the CUDA mesh kernel (nBuildSectionMesh), so we
        // need a live engine pointer here. ensureBackendForWorldgen() spins one
        // up if it isn't already initialised — getBackendPtr alone would return
        // 0 before the compositor first runs and starve the mesher.
        long ptr = PlutoniumCompositor.ensureBackendForWorldgen();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Level level = mc.level;
        resetTransientStateForLevel(level);
        repairIdlePendingUploadState();
        int pcx = (int) Math.floor(mc.player.getX() / 16);
        int pcz = (int) Math.floor(mc.player.getZ() / 16);
        int renderDist = Math.max(8, mc.options.getEffectiveRenderDistance());
        evictFarSectionMeshes(pcx, pcz, renderDist);
        if (inFlight.size() >= MAX_IN_FLIGHT_JOBS) {
            emitTelemetryIfDue();
            return;
        }
        if (pendingUploadCount.get() >= maxUploads) {
            emitTelemetryIfDue();
            return;
        }
        if (sectionVbos.size() >= MAX_LIVE_SECTION_MESHES) {
            emitTelemetryIfDue();
            return;
        }

        int minSectionY = level.getMinBuildHeight() >> 4;

        // Full-world scan every render frame was starving vanilla chunk meshing (millions
        // of section checks/sec). Cap discovery radius; deferred queue handles the rest.
        int scanRadius = Math.min(renderDist, shouldDrawNativeMeshes() ? renderDist : 10);

        int submitted = 0;
        long estimatedTimePerMesh = avgMeshBuildNanos.get();
        long remainingBudget = frameBudgetNs;
        boolean coverageFirst = shouldDrawNativeMeshes();

        // First, process deferred tasks from previous frames. This path must
        // always be able to make forward progress, otherwise a full deferred
        // queue starves native meshing after level switches or camera jumps.
        SectionTask deferred;
        while ((deferred = deferredTasks.poll()) != null) {
            if (submitted >= maxSectionsPerTick || inFlight.size() >= MAX_IN_FLIGHT_JOBS || pendingUploadCount.get() >= maxUploads) {
                deferredTasks.add(deferred);
                break;
            }
            if (!coverageFirst && submitted > 0 && remainingBudget < estimatedTimePerMesh) {
                deferredTasks.add(deferred);
                break;
            }
            if (processed.contains(deferred.key())) {
                continue;
            }
            if (!inFlight.add(deferred.key())) {
                continue;  // Already in flight (race condition), skip it
            }
            remainingBudget = Math.max(0L, remainingBudget - estimatedTimePerMesh);
            final Level finalLevel = level;
            final long fPtr = ptr;
            final SectionTask finalDeferred = deferred;
            if (!submitBuildTask(workers, finalLevel, finalDeferred, fPtr)) {
                if (deferredTasks.size() < MAX_DEFERRED_TASKS) {
                    deferredTasks.add(finalDeferred);
                }
                break;
            }
            statSubmittedJobs.incrementAndGet();
            submitted++;
        }

        // Scan nearby chunks. In native replacement mode vanilla is already
        // canceled, so correctness wins: queue full coverage instead of
        // frustum/occlusion-skipping sections that would become visible holes.
        ArrayList<SectionTask> nearbyTasks = new ArrayList<>(256);
        ArrayList<SectionTask> priorityTasks = new ArrayList<>(256);

        double playerX = mc.player.getX();
        double playerY = mc.player.getEyeY();
        double playerZ = mc.player.getZ();
        boolean temporalEnabled = !coverageFirst && shouldUseTemporalCache(playerX, playerY, playerZ);
        for (int dz = -scanRadius; dz <= scanRadius; dz++) {
            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                // Circular scan footprint
                int chunkDistSq = dx * dx + dz * dz;
                if (chunkDistSq > scanRadius * scanRadius) {
                    continue;
                }

                int cx = pcx + dx, cz = pcz + dz;
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }

                // OPTIMIZATION 1: Frustum cull off-screen chunks
                LevelChunk chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    if (sections[si] == null || sections[si].hasOnlyAir()) {
                        continue;
                    }

                    int sy = minSectionY + si;
                    long k = key(cx, sy, cz);
                    if (sectionVbos.containsKey(k) || processed.contains(k) || inFlight.contains(k)) {
                        continue;
                    }

                    statScannedSections.incrementAndGet();

                    // Frustum cull: skip chunks not visible to camera
                    if (!coverageFirst && Config.CLIENT.meshFrustumCulling.get()
                            && !isChunkSectionVisible(mc, playerX, playerY, playerZ, cx, sy, cz)) {
                        continue;
                    }

                    TemporalVisibilityDecision temporalDecision = evaluateTemporalVisibility(k, currentScan, temporalEnabled);
                    if (temporalDecision == TemporalVisibilityDecision.CULLED) {
                        continue;
                    }
                    if (temporalDecision == TemporalVisibilityDecision.VISIBLE) {
                        int frontier = frontierScore(mc, playerX, playerY, playerZ, cx, sy, cz, chunkDistSq);
                        long regionKey = regionKeyForChunk(cx, cz);
                        SectionTask task = new SectionTask(cx, sy, cz, si, k, chunkDistSq, frontier, regionKey);
                        if (!Config.CLIENT.meshDistancePriority.get() || chunkDistSq <= NEARBY_REBUILD_CHUNK_DIST_SQ) {
                            nearbyTasks.add(task);
                        } else if (chunkDistSq <= PRIORITY_CHUNK_DIST_SQ) {
                            priorityTasks.add(task);
                        } else if (deferredTasks.size() < MAX_DEFERRED_TASKS) {
                            deferredTasks.add(task);
                        }
                        continue;
                    }

                    if (!coverageFirst && isOccludedWithCache(level, k, playerX, playerY, playerZ, cx, sy, cz, chunkDistSq, currentScan)) {
                        temporalVisibilityCache.put(k, new TemporalVisibilityEntry(false, currentScan));
                        statOccludedSections.incrementAndGet();
                        continue;
                    }

                    temporalVisibilityCache.put(k, new TemporalVisibilityEntry(true, currentScan));

                    int frontier = frontierScore(mc, playerX, playerY, playerZ, cx, sy, cz, chunkDistSq);
                    long regionKey = regionKeyForChunk(cx, cz);
                    SectionTask task = new SectionTask(cx, sy, cz, si, k, chunkDistSq, frontier, regionKey);

                    // OPTIMIZATION 3: Prioritize by chunk distance (Sodium/Embeddium style queue tiers)
                    if (!Config.CLIENT.meshDistancePriority.get() || chunkDistSq <= NEARBY_REBUILD_CHUNK_DIST_SQ) {
                        nearbyTasks.add(task);
                    } else if (chunkDistSq <= PRIORITY_CHUNK_DIST_SQ) {
                        priorityTasks.add(task);
                    } else if (deferredTasks.size() < MAX_DEFERRED_TASKS) {
                        deferredTasks.add(task);
                    }
                }
            }
        }

        List<SectionTask> orderedNearbyTasks = orderTasksForSubmission(nearbyTasks);
        List<SectionTask> orderedPriorityTasks = orderTasksForSubmission(priorityTasks);

        // OPTIMIZATION 2: Budget-aware submission — nearby first, then priority, then defer
        for (SectionTask task : orderedNearbyTasks) {
            if (submitted >= maxSectionsPerTick) {
                break;
            }
            if (inFlight.size() >= MAX_IN_FLIGHT_JOBS || pendingUploadCount.get() >= maxUploads) {
                break;
            }
            if (!coverageFirst && submitted > 0 && remainingBudget < estimatedTimePerMesh) {
                break;
            }

            if (!inFlight.add(task.key())) {
                continue;
            }
            remainingBudget -= estimatedTimePerMesh;
            final long fPtr = ptr;
            if (!submitBuildTask(workers, level, task, fPtr)) {
                break;
            }
            statSubmittedJobs.incrementAndGet();
            submitted++;
        }

        // High-priority chunks: submit if budget remains
        int priorityIndex = 0;
        for (; priorityIndex < orderedPriorityTasks.size(); priorityIndex++) {
            SectionTask task = orderedPriorityTasks.get(priorityIndex);
            if (submitted >= maxSectionsPerTick) {
                break;
            }
            if (inFlight.size() >= MAX_IN_FLIGHT_JOBS || pendingUploadCount.get() >= maxUploads) {
                break;
            }
            if (!coverageFirst && submitted > 0 && remainingBudget < estimatedTimePerMesh) {
                // No budget left: defer this task for next frame
                if (deferredTasks.size() < MAX_DEFERRED_TASKS) {
                    deferredTasks.add(task);
                }
                priorityIndex++;
                break;
            }

            if (!inFlight.add(task.key())) {
                continue;
            }
            remainingBudget -= estimatedTimePerMesh;
            final long fPtr = ptr;
            if (!submitBuildTask(workers, level, task, fPtr)) {
                if (deferredTasks.size() < MAX_DEFERRED_TASKS) {
                    deferredTasks.add(task);
                }
                break;
            }
            statSubmittedJobs.incrementAndGet();
            submitted++;
        }

        // Defer remaining priority tasks if we hit the per-tick limit
        for (int i = priorityIndex; i < orderedPriorityTasks.size(); i++) {
            if (deferredTasks.size() >= MAX_DEFERRED_TASKS) {
                break;
            }
            deferredTasks.add(orderedPriorityTasks.get(i));
        }

        lastCameraX = playerX;
        lastCameraY = playerY;
        lastCameraZ = playerZ;

        lastScanNs = System.nanoTime();

        emitTelemetryIfDue();
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return;
        }
    }

    // ── Worker thread: CUDA meshing ───────────────────────────────────────────
    private static void buildAsync(Level level, int cx, int sy, int cz,
            int si, long enginePtr, long k) {
        long start = System.nanoTime();
        try {
            ByteBuffer blockBuf = TL_BLOCK_BUF.get();
            blockBuf.clear();
            extractBlocks(level, cx, sy, cz, blockBuf);
            blockBuf.rewind();
            ByteBuffer outScratch = TL_OUT_SCRATCH.get();
            outScratch.clear();
            // Build the section on CUDA. The block buffer is full uint32
            // block-state IDs and the native side now has a CUDA UV table, so
            // this is the real GPU chunk-builder path instead of the old 8-bit
            // placeholder kernel.
            boolean gpuPath = enginePtr != 0;
            int count = gpuPath
                    ? NativeInterface.nBuildSectionMesh(
                    enginePtr, blockBuf, outScratch, MAX_VERTS,
                    cx * 16f, sy * 16f, cz * 16f)
                    : NativeInterface.nNativeMeshSection(
                    blockBuf, outScratch, MAX_VERTS,
                    cx * 16f, sy * 16f, cz * 16f);
            if (count < 0) {
                LOGGER.error("[Plutonium/Mesh] {} mesh failed for section {},{},{} (enginePtr=0x{}).",
                        gpuPath ? "GPU" : "CPU fallback", cx, sy, cz, Long.toHexString(enginePtr));
                return;
            }
            if (count > 0) {
                int bytes = count * VERTEX_BYTES;
                outScratch.limit(bytes).rewind();
                // Keep worker scratch reusable: copy compact payload for render-thread upload.
                ByteBuffer compact = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
                compact.put(outScratch);
                compact.flip();
                sectionUploadComplete.put(k, Boolean.FALSE);
                pendingUploadKeys.add(k);
                pendingUploads.add(new PendingUpload(k, compact, count, regionKeyForChunk(cx, cz)));
                pendingUploadCount.incrementAndGet();
                statUploadBytes.addAndGet(bytes);
                int sample = statBuiltLogSamples.incrementAndGet();
                if (sample <= 16 || (sample % 256) == 0) {
                    LOGGER.info("[Plutonium/Mesh] {} mesh built section {},{},{} (sample={}, verts={}, bytes={}, pendingUploads={}, enginePtr=0x{}).",
                            gpuPath ? "GPU" : "CPU fallback",
                            cx, sy, cz, sample, count, bytes, pendingUploadCount.get(), Long.toHexString(enginePtr));
                }
            }
            processed.add(k);
            statBuiltSections.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.error("[Plutonium/Mesh] Mesh build error for section {},{},{}.", cx, sy, cz, t);
        } finally {
            long buildTimeNanos = System.nanoTime() - start;
            statBuildNanos.addAndGet(buildTimeNanos);
            // OPTIMIZATION 2: Update running average of build time for budget estimation
            long currentAvg = avgMeshBuildNanos.get();
            long newAvg = (currentAvg * 7 + buildTimeNanos) / 8;  // Exponential moving average
            avgMeshBuildNanos.set(newAvg);
            inFlight.remove(k);
        }
    }

    private static void extractBlocks(Level level, int cx, int sy, int cz, ByteBuffer out) {
        if (Config.CLIENT.meshFastSectionRead.get()) {
            extractBlocksFast(level, cx, sy, cz, out);
            return;
        }

        int originY = sy * 16;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        for (int dy = -1; dy < 17; dy++) {
            for (int dz = -1; dz < 17; dz++) {
                for (int dx = -1; dx < 17; dx++) {
                    int wy = originY + dy;
                    int id = 0;
                    if (wy >= minY && wy < maxY) {
                        try {
                            BlockPos pos = new BlockPos(cx * 16 + dx, wy, cz * 16 + dz);
                            id = nativeSolidBlockId(level, pos, level.getBlockState(pos));
                        } catch (Throwable ignored) {
                        }
                    }
                    out.putInt(((dx + 1) + (dy + 1) * 18 + (dz + 1) * 18 * 18) * 4, id);
                }
            }
        }
    }

    // Sodium/Embeddium-style fast section path: avoid per-voxel Level#getBlockState allocations.
    private static void extractBlocksFast(Level level, int cx, int sy, int cz, ByteBuffer out) {
        int originX = cx * 16;
        int originY = sy * 16;
        int originZ = cz * 16;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int minSectionY = minY >> 4;

        LevelChunk[][] chunkCache = new LevelChunk[3][3];
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                int ccx = cx + x;
                int ccz = cz + z;
                if (level.hasChunk(ccx, ccz)) {
                    chunkCache[z + 1][x + 1] = level.getChunk(ccx, ccz);
                }
            }
        }
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int dy = -1; dy < 17; dy++) {
            int wy = originY + dy;
            for (int dz = -1; dz < 17; dz++) {
                int wz = originZ + dz;
                for (int dx = -1; dx < 17; dx++) {
                    int wx = originX + dx;
                    int id = 0;

                    if (wy >= minY && wy < maxY) {
                        int localChunkX = Math.floorDiv(wx, 16) - cx;
                        int localChunkZ = Math.floorDiv(wz, 16) - cz;

                        if (localChunkX >= -1 && localChunkX <= 1 && localChunkZ >= -1 && localChunkZ <= 1) {
                            LevelChunk targetChunk = chunkCache[localChunkZ + 1][localChunkX + 1];
                            if (targetChunk != null) {
                                LevelChunkSection[] sections = targetChunk.getSections();
                                int sectionIdx = (wy >> 4) - minSectionY;
                                if (sectionIdx >= 0 && sectionIdx < sections.length) {
                                    LevelChunkSection section = sections[sectionIdx];
                                    if (section != null && !section.hasOnlyAir()) {
                                        int lx = wx & 15;
                                        int ly = wy & 15;
                                        int lz = wz & 15;
                                        mutablePos.set(wx, wy, wz);
                                        id = nativeSolidBlockId(level, mutablePos, section.getBlockState(lx, ly, lz));
                                    }
                                }
                            }
                        }
                    }

                    out.putInt(((dx + 1) + (dy + 1) * 18 + (dz + 1) * 18 * 18) * 4, id);
                }
            }
        }
    }

    // ── Render thread: upload pending mesh data to raw GL VBOs ────────────────
    public static void drainPendingUploads() {
        activeRenderFrame = renderFrameSequence.incrementAndGet();
        ensureVao();
        updateAdaptiveBudgets();
        int frameDrainBudget = uploadDrainPerFrame();
        if (batchedNeedsCompactRebuild) {
            synchronized (GpuMeshManager.class) {
                if (batchedNeedsCompactRebuild) {
                    rebuildBatchedVboFromCpuLocked(false);
                }
            }
        }

        if (Config.CLIENT.meshUploadRegionStaging.get()) {
            stagePendingUploadsByRegion(Math.max(frameDrainBudget * 2, 8));
            int stagedDone = drainStagedUploads(frameDrainBudget);
            int remaining = frameDrainBudget - stagedDone;
            PendingUpload u;
            while (remaining > 0 && (u = pendingUploads.poll()) != null) {
                pendingUploadCount.decrementAndGet();
                uploadToGl(u);
                remaining--;
            }
            repairIdlePendingUploadState();
            return;
        }

        PendingUpload u;
        int done = 0;
        while (done < frameDrainBudget && (u = pendingUploads.poll()) != null) {
            pendingUploadCount.decrementAndGet();
            uploadToGl(u);
            done++;
        }
        repairIdlePendingUploadState();
    }

    // ── Render thread: draw all native-built meshes ───────────────────────────
    public static void renderSolidPass(PoseStack poseStack, Camera camera, Matrix4f projMatrix) {
        var camPos = camera.getPosition();
        renderSolidPass(poseStack, camPos.x, camPos.y, camPos.z, projMatrix);
    }

    public static void renderSolidPass(PoseStack poseStack, double cameraX, double cameraY, double cameraZ, Matrix4f projMatrix) {
        if (activeRenderFrame == 0L) {
            activeRenderFrame = renderFrameSequence.incrementAndGet();
        }
        if (!glReady || sectionVbos.isEmpty()) {
            emitDrawDiagnosticsIfDue("skip-no-gl-or-vbos", cameraX, cameraY, cameraZ, 0, 0, GL11.GL_NO_ERROR);
            return;
        }
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        RenderSystem.activeTexture(previousActiveTexture);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean previousDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        ShaderInstance shader = null;
        DrawStats stats = new DrawStats(0, 0);
        int glErr = GL11.GL_NO_ERROR;
        try {
            // Draw with the same vertex contract the native builder emits:
            // POSITION_COLOR_TEX_LIGHTMAP, 28-byte stride.
            Minecraft mc = Minecraft.getInstance();
            TextureAtlas blockAtlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            int blockAtlasId = blockAtlas.getId();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.setShaderTexture(0, blockAtlasId);
            GlStateManager._bindTexture(blockAtlasId);
            RenderSystem.setShader(GameRenderer::getPositionColorTexLightmapShader);
            shader = RenderSystem.getShader();
            if (shader == null) {
                emitDrawDiagnosticsIfDue("skip-null-shader", cameraX, cameraY, cameraZ, 0, 0, GL11.GL_NO_ERROR);
                return;
            }

            // Native vertices are in absolute world space. Minecraft's pose stack at
            // AFTER_SOLID_BLOCKS has only camera rotation, so subtract the camera.
            Matrix4f mv = new Matrix4f(poseStack.last().pose())
                    .translate((float) -cameraX, (float) -cameraY, (float) -cameraZ);
            if (shader.MODEL_VIEW_MATRIX != null) {
                shader.MODEL_VIEW_MATRIX.set(mv);
            }
            if (shader.PROJECTION_MATRIX != null) {
                shader.PROJECTION_MATRIX.set(projMatrix);
            }
            if (shader.COLOR_MODULATOR != null) {
                shader.COLOR_MODULATOR.set(1f, 1f, 1f, 1f);
            }
            shader.apply();
            GlStateManager._glBindVertexArray(sharedVao);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            stats = renderCompactedDrawList();
            glErr = GL11.glGetError();
        } finally {
            GL20.glDisableVertexAttribArray(0);
            GL20.glDisableVertexAttribArray(1);
            GL20.glDisableVertexAttribArray(2);
            GL20.glDisableVertexAttribArray(3);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GlStateManager._glBindVertexArray(previousVertexArray);
            if (shader != null) {
                shader.clear();
            }
            GL20.glUseProgram(previousProgram);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.setShaderTexture(0, previousTexture0);
            GlStateManager._bindTexture(previousTexture0);
            RenderSystem.activeTexture(previousActiveTexture);
            if (previousDepthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.depthFunc(previousDepthFunc);
            RenderSystem.depthMask(previousDepthMask);
            if (previousCull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            RenderSystem.applyModelViewMatrix();
        }
        emitDrawDiagnosticsIfDue("draw", cameraX, cameraY, cameraZ, stats.calls(), stats.verts(), glErr);
    }

    private static void emitDrawDiagnosticsIfDue(String reason, double cameraX, double cameraY, double cameraZ,
                                                 int drawCalls, long drawVerts, int glErr) {
        long now = System.nanoTime();
        if ((now - lastDrawDiagnosticsNs) < 2_000_000_000L) {
            return;
        }
        lastDrawDiagnosticsNs = now;
        LOGGER.info("[Plutonium/Draw] reason={} vbos={} compact={} drawCalls={} drawVerts={} pending={} stagedRegions={} cam=({}, {}, {}) glReady={} glErr=0x{}.",
                reason,
                sectionVbos.size(),
                Config.CLIENT.meshDrawListCompaction.get(),
                drawCalls,
                drawVerts,
                pendingUploadCount.get(),
                stagedUploadsByRegion.size(),
                String.format(Locale.ROOT, "%.2f", cameraX),
                String.format(Locale.ROOT, "%.2f", cameraY),
                String.format(Locale.ROOT, "%.2f", cameraZ),
                glReady,
                Integer.toHexString(glErr));
    }

    // ── Flush cached section keys so they rebuild with updated data (e.g. after UV table upload) ──
    private static void emitSolidReplaceDiagnosticsIfChangedOrDue(boolean ready, int live, int required) {
        long now = System.nanoTime();
        boolean changed = ready != solidLayerReplacing;
        if (!changed && (now - lastSolidReplaceDiagnosticsNs) < 5_000_000_000L) {
            return;
        }
        lastSolidReplaceDiagnosticsNs = now;
        if (ready) {
            LOGGER.info("[Plutonium/Draw] Mirrored solid layer replacement enabled (mirrored={}/{} megaReady={}).",
                    live, required, PlutoniumMegaBuffer.isReady());
        } else {
            LOGGER.info("[Plutonium/Draw] Keeping vanilla solid layer as visual fallback until mirror catches up (mirrored={}/{} megaReady={}).",
                    live, required, PlutoniumMegaBuffer.isReady());
        }
    }

    public static void invalidateAllProcessed() {
        processed.clear();
        // Keep live VBOs — they'll be overwritten in place when the section rebuilds.
    }

    // ── Invalidate one section when its blocks change ─────────────────────────
    public static void invalidateSection(int cx, int sy, int cz) {
        long k = key(cx, sy, cz);
        processed.remove(k);
        Integer old = sectionVbos.remove(k);
        sectionVertCounts.remove(k);
        sectionVertexData.remove(k);
        sectionBatchRanges.remove(k);
        sectionUploadComplete.remove(k);
        sectionUploadFrame.remove(k);
        pendingUploadKeys.remove(k);
        drawListDirty = true;
        multiDrawDirty = true;
        if (old != null) {
            final int vbo = old;
            RenderSystem.recordRenderCall(() -> GL15.glDeleteBuffers(vbo));
        }
    }

    // ── Cleanup on level unload ───────────────────────────────────────────────
    public static void cleanup() {
        ExecutorService workers = workersRef.getAndSet(null);
        if (workers != null) {
            workers.shutdownNow();
        }
        workerCount = -1;
        RenderSystem.recordRenderCall(() -> {
            sectionVbos.values().forEach(GL15::glDeleteBuffers);
            if (sharedVao != -1) {
                GL30.glDeleteVertexArrays(sharedVao);
                sharedVao = -1;
            }
            if (batchedVbo != -1) {
                GL15.glDeleteBuffers(batchedVbo);
                batchedVbo = -1;
            }
            PlutoniumLodRenderer.clear();
            PlutoniumMegaBuffer.destroy();
            glReady = false;
        });
        PlutoniumSectionRegistry.clear();
        sectionVbos.clear();
        sectionVertCounts.clear();
        sectionVertexData.clear();
        sectionBatchRanges.clear();
        sectionUploadComplete.clear();
        sectionUploadFrame.clear();
        occlusionCache.clear();
        temporalVisibilityCache.clear();
        stagedUploadsByRegion.clear();
        processed.clear();
        inFlight.clear();
        pendingUploads.clear();
        pendingUploadKeys.clear();
        pendingUploadCount.set(0);
        deferredTasks.clear();
        drawKeysCompact = new long[0];
        drawVbosCompact = new int[0];
        drawCountsCompact = new int[0];
        drawListDirty = true;
        batchedVertexCapacity = 0;
        batchedNextVertex = 0;
        multiDrawDirty = true;
        multiDrawFirsts = emptyDirectIntBuffer();
        multiDrawCounts = emptyDirectIntBuffer();
        multiDrawCommandCount = 0;
        multiDrawVertexCount = 0L;
        activeLevel = null;
        lastCameraX = Double.NaN;
        lastCameraY = Double.NaN;
        lastCameraZ = Double.NaN;
        solidLayerReplacing = false;
        lastSolidReplaceDiagnosticsNs = System.nanoTime();
        adaptiveSectionsPerTick = 1;
        adaptiveUploadDrainPerFrame = 1;
        adaptivePressureEma = 0.0;
        statBuiltLogSamples.set(0);
        statUploadLogSamples.set(0);
    }

    private static void ensureVao() {
        if (sharedVao != -1) {
            return;
        }
        sharedVao = GL30.glGenVertexArrays();
        glReady = true;
    }

    private static void emitTelemetryIfDue() {
        long now = System.nanoTime();
        if ((now - lastTelemetryNs) < TELEMETRY_EVERY_NS) {
            return;
        }
        long scanned = statScannedSections.getAndSet(0);
        long submitted = statSubmittedJobs.getAndSet(0);
        long built = statBuiltSections.getAndSet(0);
        long buildNs = statBuildNanos.getAndSet(0);
        long uploadBytes = statUploadBytes.getAndSet(0);
        long occluded = statOccludedSections.getAndSet(0);
        long temporalHits = statTemporalHits.getAndSet(0);
        long staged = statStagedUploads.getAndSet(0);
        double avgMs = built > 0 ? (buildNs / 1_000_000.0) / built : 0.0;
        LOGGER.info("[Plutonium/Mesh] scanned={} temporalHits={} occluded={} submitted={} built={} avgBuildMs={} uploadKB={} staged={} stagedRegions={} inFlight={} pendingUploads={} deferred={} secBudget={} drainBudget={} pressure={} occCache={} temporalCache={} vbos={}",
                scanned, temporalHits, occluded, submitted, built,
                String.format(Locale.ROOT, "%.2f", avgMs),
                String.format(Locale.ROOT, "%.1f", uploadBytes / 1024.0),
                staged, stagedUploadsByRegion.size(), inFlight.size(), pendingUploadCount.get(), deferredTasks.size(),
                sectionsPerTick(), uploadDrainPerFrame(), String.format(Locale.ROOT, "%.2f", adaptivePressureEma),
                occlusionCache.size(), temporalVisibilityCache.size(), sectionVbos.size());

        // Keep occlusion cache bounded.
        if (occlusionCache.size() > 32_000) {
            long cutoff = Math.max(0, scanSequence.get() - 8);
            occlusionCache.entrySet().removeIf(e -> e.getValue().scanId < cutoff);
        }
        if (temporalVisibilityCache.size() > 64_000) {
            long cutoff = Math.max(0, scanSequence.get() - Math.max(2, Config.CLIENT.meshTemporalReuseFrames.get() + 1));
            temporalVisibilityCache.entrySet().removeIf(e -> e.getValue().scanId < cutoff);
        }
        lastTelemetryNs = now;
    }

    private record SectionTask(int cx, int sy, int cz, int si, long key, int dist2, int frontier, long regionKey) {
    }

    private record PendingUpload(long key, ByteBuffer vertBuffer, int count, long regionKey) {
    }

    private record OcclusionCacheEntry(boolean occluded, long scanId) {
    }

    private record DrawEntry(long key, int vbo, int count) {
    }

    private record BatchRange(int firstVertex, int capacityVerts) {
    }

    private record DrawRange(long key, int firstVertex, int count) {
    }

    private record DrawStats(int calls, long verts) {
    }

    private record TemporalVisibilityEntry(boolean visible, long scanId) {
    }

    private enum TemporalVisibilityDecision {
        COMPUTE,
        VISIBLE,
        CULLED
    }
}
