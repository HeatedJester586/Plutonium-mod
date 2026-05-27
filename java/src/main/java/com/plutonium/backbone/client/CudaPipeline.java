package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import com.plutonium.backbone.mixin.RenderChunkInfoAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Thin Java feeder for the v4 native chunk pipeline.
 *
 * This is intentionally not the renderer yet. Its first job is to prove the
 * retained native registry, 9-chunk snapshot copier, and isolated CPU mesher can
 * receive real Minecraft block/light payloads and emit native extraction logs.
 */
public final class CudaPipeline {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_HEIGHT = NativeInterface.PIPELINE_CHUNK_HEIGHT;
    private static final int FIRST_BOOT_RADIUS = 1;
    private static final int REQUEST_UPLOAD_BUDGET = 4;
    private static final int REQUEST_UPLOAD_BUDGET_FAST = 16;
    private static final int MAX_IN_FLIGHT_UPLOADS = 32;
    private static final int MAX_IN_FLIGHT_UPLOADS_FAST = 96;
    private static final double FAST_MODE_SPEED_THRESHOLD_SQ = 4.0;
    private static final int HANDOFF_STABLE_FRAMES = 3;
    private static final int VISIBLE_UPLOAD_HALO_RADIUS = 1;
    private static final int INACTIVE_VISIBLE_REQUEST_INTERVAL = 4;
    private static final int MIN_COMPILED_COLUMNS_FOR_HANDOFF = 64;
    private static final double COMPILED_COVERAGE_ON_THRESHOLD = 0.90;
    private static final double COMPILED_COVERAGE_OFF_THRESHOLD = 0.75;
    // Hysteresis: flip native ON when coverage >= ON, OFF when coverage < OFF.
    // This avoids per-frame thrashing when the player crosses a chunk boundary.
    private static final double COVERAGE_ON_THRESHOLD  = 0.95;
    private static final double COVERAGE_OFF_THRESHOLD = 0.80;
    private static final int MAX_MISSING_VISIBLE_COLUMNS = 64;
    private static final int PREDICTIVE_LOOKAHEAD_TICKS = 60;
    private static final int PREDICTIVE_CORRIDOR_RADIUS = 2;
    private static final int MAX_PREDICTIVE_ADDS_PER_TICK = 16;

    private static final Set<Long> uploadedColumns = new HashSet<>();
    private static final LinkedHashSet<Long> requestedColumns = new LinkedHashSet<>();
    // Chunks submitted to ChunkUploadWorker but not yet returned. Prevents
    // re-submitting the same chunk while a background extraction is in flight.
    private static final Set<Long> inFlightSubmissions = new HashSet<>();
    // Chunks scheduled for a delayed re-submit (chunkKey -> tick at which to
    // re-submit). The initial PalettedContainer snapshot is often taken while
    // MC's network thread is still populating the chunk, producing wrong/partial
    // block data. We re-submit after ~3 seconds when the chunk has stabilized.
    // This is the equivalent of the user manually breaking a block to "fix"
    // a chunk — automated for every newly-uploaded chunk.
    private static final java.util.Map<Long, Long> pendingResubmits = new java.util.HashMap<>();
    private static long currentTickNum = 0;
    private static final long RESUBMIT_DELAY_TICKS = 60;  // 3 seconds at 20 Hz
    private static final int  RESUBMIT_BUDGET_PER_TICK = 6;
    private static ClientLevel activeLevel;
    private static boolean initialized;
    private static int uploadLogSamples;
    private static int unregisterLogSamples;
    private static int uploadEpoch;
    private static int handoffStableFrames;
    private static int handoffWaitLogSamples;
    private static int visibleRequestCalls;
    private static volatile boolean nativeRenderActive;

    private static final class CompiledCoverage {
        final int compiled;
        final int total;
        final int missing;
        final double coverage;
        final boolean ready;
        final boolean lost;

        CompiledCoverage(int compiled, int total) {
            this.compiled = compiled;
            this.total = total;
            this.missing = Math.max(0, total - compiled);
            this.coverage = total == 0 ? 0.0 : (double) compiled / (double) total;
            this.ready = total >= MIN_COMPILED_COLUMNS_FOR_HANDOFF
                    && compiled > 0
                    && coverage >= COMPILED_COVERAGE_ON_THRESHOLD;
            this.lost = total > 0
                    && coverage < COMPILED_COVERAGE_OFF_THRESHOLD;
        }
    }

    private CudaPipeline() {
    }

    public static void tick(Minecraft mc) {
        if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE || mc == null || mc.level == null || mc.player == null) {
            return;
        }

        if (activeLevel != mc.level) {
            resetForLevel(mc.level);
        }

        NativeInterface.ensureLoaded();
        if (!NativeInterface.isLoaded()) {
            return;
        }

        if (!initialized) {
            NativeInterface.nPipelineInit(0);
            BlockPropertyTableCompiler.compileAndUpload();
            ChunkUploadWorker.ensureStarted();
            initialized = true;
        }

        currentTickNum++;

        NativeLogBridge.drain();
        VelocityChunkPrioritizer.updatePlayerKinematics(mc.player.getX(), mc.player.getZ());

        // 0. Process due re-submissions. The first extract for a chunk often
        // races with MC's bulk chunk population (network thread populating the
        // PalettedContainer mid-snapshot). The re-submit after a short delay
        // captures the stabilized chunk.
        processPendingResubmits(mc);

        // 1. Drain completed background extractions. JNI push + dispatch are
        // microsecond ops; the expensive 98K-block iteration already ran on
        // a worker thread.
        drainCompletedUploads();

        // 2. Submit the 3x3 chunk neighborhood around the player for extraction.
        // Workers do the heavy lifting; we only enqueue and bookkeep here.
        int centerX = Math.floorDiv(BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()).getX(), 16);
        int centerZ = Math.floorDiv(BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()).getZ(), 16);

        for (int dz = -FIRST_BOOT_RADIUS; dz <= FIRST_BOOT_RADIUS; dz++) {
            for (int dx = -FIRST_BOOT_RADIUS; dx <= FIRST_BOOT_RADIUS; dx++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                long key = chunkKey(cx, cz);
                if (uploadedColumns.contains(key) || inFlightSubmissions.contains(key)) {
                    continue;
                }
                if (inFlightSubmissions.size() >= currentInFlightLimit()) {
                    continue;
                }
                if (ChunkUploadWorker.submit(mc.level, uploadEpoch, cx, cz)) {
                    inFlightSubmissions.add(key);
                }
            }
        }

        // 3. At high velocity, queue chunks the player will reach soon but
        // can't see yet. Without this, FIFO + frustum-only requests means we
        // only start uploading a chunk once it enters the camera frustum —
        // at jet speed that's already too late.
        expandRequestsAlongVelocity(mc);

        // 4. Submit chunks the renderer marked as visible-but-missing.
        processRequestedColumns(mc, mc.level);

        // 4. Diagnostic: run the canonical-metadata audit and log via SLF4J.
        // Native fprintf-to-stdout is swallowed by Forge in this configuration,
        // so we must marshal the audit result back to Java for logging.
        runMetadataAudit();
    }

    private static final long[] AUDIT_BUF = new long[3 * 8];   // up to 8 mismatches
    private static final int[]  AUDIT_ACTIVE = new int[1];
    private static int auditTickCounter = 0;

    private static void runMetadataAudit() {
        // Rate-limit on the Java side: ~1 audit call per 2 seconds at 20 Hz.
        if ((auditTickCounter++ % 40) != 0) {
            return;
        }
        int mismatches = NativeInterface.nPipelineRunMetadataAudit(AUDIT_BUF, AUDIT_ACTIVE);
        int active = AUDIT_ACTIVE[0];
        if (mismatches == 0) {
            LOGGER.info("[Plutonium/Pipeline] AUDIT OK (active={}, mismatches=0)", active);
            return;
        }
        int reported = Math.min(mismatches, AUDIT_BUF.length / 3);
        LOGGER.warn("[Plutonium/Pipeline] AUDIT MISMATCH count={} active={} (showing first {})",
                mismatches, active, reported);
        for (int i = 0; i < reported; i++) {
            long slotData = AUDIT_BUF[3 * i];
            long ctxData = AUDIT_BUF[3 * i + 1];
            long metaData = AUDIT_BUF[3 * i + 2];
            int slot = (int) slotData;
            int ctxX = (int) (ctxData >> 32);
            int ctxZ = (int) ctxData;
            int metaX = (int) (metaData >> 32);
            int metaZ = (int) metaData;
            LOGGER.warn("[Plutonium/Pipeline]   slot={} ctx_chunk=({},{}) meta_chunk=({},{})",
                    slot, ctxX, ctxZ, metaX, metaZ);
        }
    }

    /**
     * Pull finished background extractions and push them to native. This is
     * the only render-thread work for chunk uploads now — the heavy data
     * extraction happened on a worker thread.
     */
    private static void drainCompletedUploads() {
        int processed = 0;
        int budget = currentUploadBudget();
        while (processed < budget) {
            ChunkUploadWorker.Result r = ChunkUploadWorker.pollCompleted();
            if (r == null) {
                break;
            }
            long key = chunkKey(r.chunkX, r.chunkZ);
            if (r.epoch != uploadEpoch || r.level != activeLevel) {
                continue;
            }
            boolean wasInFlight = inFlightSubmissions.remove(key);
            if (!wasInFlight) {
                continue;
            }
            if (activeLevel == null || activeLevel.getChunkSource().getChunk(r.chunkX, r.chunkZ, false) == null) {
                continue;
            }

            boolean ok = NativeInterface.nPipelineUploadChunkColumn(
                    r.chunkX, r.chunkZ, r.blocks, r.lights);
            if (ok) {
                boolean firstUpload = uploadedColumns.add(key);
                handoffStableFrames = 0;
                // nPipelineTryDispatchChunk also pokes the 8 neighbors,
                // unblocking any waiting dispatch on the load frontier.
                NativeInterface.nPipelineTryDispatchChunk(r.chunkX, r.chunkZ);
                if (uploadLogSamples++ < 9 || (uploadLogSamples % 128) == 0) {
                    LOGGER.info("[Plutonium/Pipeline] uploaded chunk column {},{} (nonAir={}).",
                            r.chunkX, r.chunkZ, r.nonAirCount);
                }
                // Schedule a stabilization re-extract. The first extract often
                // races with MC's network-thread chunk population; the second
                // extract (after RESUBMIT_DELAY_TICKS) sees the settled data.
                if (firstUpload) {
                    pendingResubmits.put(key, currentTickNum + RESUBMIT_DELAY_TICKS);
                }
            }
            processed++;
        }
    }

    /**
     * Drain due re-submits. For each chunk whose stabilization delay has
     * elapsed, submit it once more to ChunkUploadWorker so the worker reads
     * a fresh PalettedContainer snapshot (which by now reflects MC's fully-
     * populated chunk). Capped at RESUBMIT_BUDGET_PER_TICK so a burst of
     * newly-loaded chunks doesn't all re-submit on the same tick.
     */
    private static void processPendingResubmits(Minecraft mc) {
        if (pendingResubmits.isEmpty()) {
            return;
        }
        java.util.ArrayList<Long> due = new java.util.ArrayList<>(RESUBMIT_BUDGET_PER_TICK);
        java.util.Iterator<java.util.Map.Entry<Long, Long>> it = pendingResubmits.entrySet().iterator();
        while (it.hasNext() && due.size() < RESUBMIT_BUDGET_PER_TICK) {
            java.util.Map.Entry<Long, Long> e = it.next();
            if (currentTickNum < e.getValue()) {
                continue;
            }
            long key = e.getKey();
            it.remove();
            // Skip if the chunk has already been unregistered or is being
            // freshly re-extracted via some other path.
            if (!uploadedColumns.contains(key)) continue;
            if (inFlightSubmissions.contains(key)) continue;
            due.add(key);
        }
        for (long key : due) {
            int cx = unpackChunkX(key);
            int cz = unpackChunkZ(key);
            if (ChunkUploadWorker.submit(mc.level, uploadEpoch, cx, cz)) {
                inFlightSubmissions.add(key);
            }
        }
    }

    public static void shutdown() {
        if (NativeInterface.isLoaded() && initialized) {
            NativeInterface.nPipelineShutdown();
        }
        ChunkUploadWorker.shutdown();
        uploadEpoch++;
        initialized = false;
        uploadedColumns.clear();
        requestedColumns.clear();
        inFlightSubmissions.clear();
        pendingResubmits.clear();
        activeLevel = null;
        NativeChunkRenderer.resetNativeConfiguration();
        nativeRenderActive = false;
        handoffStableFrames = 0;
        handoffWaitLogSamples = 0;
        visibleRequestCalls = 0;
    }

    /**
     * Is this chunk column currently uploaded to the native pipeline? Other Java
     * subsystems (DirtyChunkBatcher's light refresh path) consult this to skip
     * work for chunks the native side isn't tracking — without this filter the
     * 3x3 light refresh fan-out compiles 98K-block light payloads for hundreds
     * of unrelated chunks every tick, stalling the render thread.
     */
    public static boolean isColumnUploaded(int chunkX, int chunkZ) {
        return uploadedColumns.contains(chunkKey(chunkX, chunkZ));
    }

    public static void unregisterChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        boolean wasUploaded = uploadedColumns.remove(key);
        requestedColumns.remove(key);
        inFlightSubmissions.remove(key);
        pendingResubmits.remove(key);
        if (wasUploaded) {
            handoffStableFrames = 0;
        }
        if (!wasUploaded || !initialized || !NativeInterface.isLoaded()) {
            return;
        }

        NativeInterface.nPipelineUnregisterChunk(chunkX, chunkZ);
        if (unregisterLogSamples++ < 8 || (unregisterLogSamples % 256) == 0) {
            LOGGER.info("[Plutonium/Pipeline] unregistered chunk column {},{}.", chunkX, chunkZ);
        }
    }

    private static void resetForLevel(ClientLevel level) {
        if (initialized && NativeInterface.isLoaded()) {
            NativeInterface.nPipelineShutdown();
        }
        ChunkUploadWorker.shutdown();
        uploadEpoch++;
        initialized = false;
        uploadedColumns.clear();
        requestedColumns.clear();
        inFlightSubmissions.clear();
        pendingResubmits.clear();
        activeLevel = level;
        uploadLogSamples = 0;
        unregisterLogSamples = 0;
        nativeRenderActive = false;
        handoffStableFrames = 0;
        handoffWaitLogSamples = 0;
        visibleRequestCalls = 0;
        VelocityChunkPrioritizer.reset();
        BlockPropertyTableCompiler.invalidate();
    }

    public static void requestVisibleColumns(ObjectArrayList<?> visibleChunks) {
        if (visibleChunks == null || visibleChunks.isEmpty()) {
            return;
        }
        if (!nativeRenderActive && (visibleRequestCalls++ % INACTIVE_VISIBLE_REQUEST_INTERVAL) != 0) {
            return;
        }

        synchronized (requestedColumns) {
            for (Object info : visibleChunks) {
                if (!(info instanceof RenderChunkInfoAccessor accessor)) {
                    continue;
                }
                ChunkRenderDispatcher.RenderChunk renderChunk = accessor.plutonium$getChunk();
                if (renderChunk == null) {
                    continue;
                }
                BlockPos origin = renderChunk.getOrigin();
                int cx = origin.getX() >> 4;
                int cz = origin.getZ() >> 4;
                for (int dz = -VISIBLE_UPLOAD_HALO_RADIUS; dz <= VISIBLE_UPLOAD_HALO_RADIUS; dz++) {
                    for (int dx = -VISIBLE_UPLOAD_HALO_RADIUS; dx <= VISIBLE_UPLOAD_HALO_RADIUS; dx++) {
                        long key = chunkKey(cx + dx, cz + dz);
                        if (!uploadedColumns.contains(key)) {
                            requestedColumns.add(key);
                        }
                    }
                }
            }
        }
    }

    /**
     * Decide whether the native renderer should own the solid layer this frame.
     * Hysteresis prevents per-frame flicker as the player crosses chunk boundaries:
     *   - Flip ON when coverage of visible chunks reaches COVERAGE_ON_THRESHOLD.
     *   - Flip OFF only if coverage drops below COVERAGE_OFF_THRESHOLD.
     *   - "Mostly there" (missing <= MAX_MISSING_VISIBLE_COLUMNS) also keeps us on.
     */
    public static boolean visibleColumnCoverageReady(ObjectArrayList<?> visibleChunks) {
        if (visibleChunks == null || visibleChunks.isEmpty() || uploadedColumns.isEmpty()) {
            nativeRenderActive = false;
            return false;
        }

        HashSet<Long> visibleColumns = new HashSet<>();
        for (Object info : visibleChunks) {
            if (!(info instanceof RenderChunkInfoAccessor accessor)) {
                continue;
            }
            ChunkRenderDispatcher.RenderChunk renderChunk = accessor.plutonium$getChunk();
            if (renderChunk == null) {
                continue;
            }
            BlockPos origin = renderChunk.getOrigin();
            visibleColumns.add(chunkKey(origin.getX() >> 4, origin.getZ() >> 4));
        }

        if (visibleColumns.isEmpty()) {
            // Nothing visible (paused / loading). Don't flip state — keep prior.
            return nativeRenderActive;
        }

        int covered = 0;
        for (long key : visibleColumns) {
            if (uploadedColumns.contains(key)) {
                covered++;
            }
        }
        int missing = visibleColumns.size() - covered;
        double coverage = (double) covered / (double) visibleColumns.size();

        boolean uploadCoverageReady = coverage >= COVERAGE_ON_THRESHOLD || missing <= MAX_MISSING_VISIBLE_COLUMNS;
        boolean coverageLost = coverage < COVERAGE_OFF_THRESHOLD && missing > MAX_MISSING_VISIBLE_COLUMNS;

        CompiledCoverage compiled = nativeCompiledCoverage(visibleColumns);
        boolean wasActive = nativeRenderActive;

        if (nativeRenderActive && (coverageLost || compiled.lost)) {
            nativeRenderActive = false;
            handoffStableFrames = 0;
        }

        if (nativeRenderActive) {
            return true;
        }

        if (!uploadCoverageReady) {
            handoffStableFrames = 0;
            logHandoffState(wasActive, nativeRenderActive, covered, visibleColumns.size(), compiled);
            return false;
        }

        if (compiled.ready && nativePipelineSettled()) {
            handoffStableFrames++;
        } else {
            handoffStableFrames = 0;
        }

        nativeRenderActive = handoffStableFrames >= HANDOFF_STABLE_FRAMES;
        logHandoffState(wasActive, nativeRenderActive, covered, visibleColumns.size(), compiled);
        return nativeRenderActive;
    }

    public static boolean isNativeRenderActive() {
        return nativeRenderActive;
    }

    public static void dropNativeRenderHandoff() {
        nativeRenderActive = false;
        handoffStableFrames = 0;
    }

    private static boolean nativePipelineSettled() {
        if (!NativeInterface.isLoaded()) {
            return false;
        }
        return NativeInterface.nPipelinePendingSwapCount() == 0
                && NativeInterface.nPipelineActiveMeshJobCount() == 0;
    }

    private static CompiledCoverage nativeCompiledCoverage(Set<Long> visibleColumns) {
        if (!NativeInterface.isLoaded() || visibleColumns.isEmpty()) {
            return new CompiledCoverage(0, 0);
        }

        long[] keys = new long[visibleColumns.size()];
        int index = 0;
        for (long key : visibleColumns) {
            keys[index++] = key;
        }

        int compiled = NativeInterface.nPipelineCountCompiledColumns(keys, keys.length);
        return new CompiledCoverage(compiled, keys.length);
    }

    private static void logHandoffState(
            boolean wasActive,
            boolean isActive,
            int uploaded,
            int total,
            CompiledCoverage compiled) {
        if (wasActive != isActive) {
            LOGGER.info("[Plutonium/Pipeline] native solid handoff {} (uploaded={}/{}, compiled={}/{}, pendingSwaps={}, activeJobs={}, queuedUploads={}).",
                    isActive ? "ON" : "OFF",
                    uploaded,
                    total,
                    compiled.compiled,
                    compiled.total,
                    NativeInterface.nPipelinePendingSwapCount(),
                    NativeInterface.nPipelineActiveMeshJobCount(),
                    ChunkUploadWorker.completedCount());
            handoffWaitLogSamples = 0;
            return;
        }

        if (!isActive && (handoffWaitLogSamples++ < 4 || (handoffWaitLogSamples % 240) == 0)) {
            LOGGER.info("[Plutonium/Pipeline] waiting for native solid handoff (uploaded={}/{}, compiled={}/{}, missingCompiled={}, pendingSwaps={}, activeJobs={}, stableFrames={}/{}).",
                    uploaded,
                    total,
                    compiled.compiled,
                    compiled.total,
                    compiled.missing,
                    NativeInterface.nPipelinePendingSwapCount(),
                    NativeInterface.nPipelineActiveMeshJobCount(),
                    handoffStableFrames,
                    HANDOFF_STABLE_FRAMES);
        }
    }

    /**
     * Add chunks along the player's projected flight path to the request
     * queue. Pre-loads what the renderer hasn't asked for yet so the upload
     * pipeline can start working before the chunk enters the frustum.
     *
     * Only enqueues chunks the client already has — we can't summon chunks
     * the server hasn't sent. ChunkUploadWorker.submit() rejects unloaded
     * ones anyway, but checking up front avoids wasted submissions and
     * pointless retries.
     */
    private static void expandRequestsAlongVelocity(Minecraft mc) {
        if (!VelocityChunkPrioritizer.hasMeaningfulVelocity()) {
            return;
        }

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double futureX = playerX + VelocityChunkPrioritizer.velocityX() * PREDICTIVE_LOOKAHEAD_TICKS;
        double futureZ = playerZ + VelocityChunkPrioritizer.velocityZ() * PREDICTIVE_LOOKAHEAD_TICKS;

        int playerCX = Math.floorDiv((int) Math.floor(playerX), 16);
        int playerCZ = Math.floorDiv((int) Math.floor(playerZ), 16);
        int futureCX = Math.floorDiv((int) Math.floor(futureX), 16);
        int futureCZ = Math.floorDiv((int) Math.floor(futureZ), 16);

        int travelDX = futureCX - playerCX;
        int travelDZ = futureCZ - playerCZ;
        int steps = Math.max(1, Math.max(Math.abs(travelDX), Math.abs(travelDZ)));
        int added = 0;

        synchronized (requestedColumns) {
            for (int step = 1; step <= steps && added < MAX_PREDICTIVE_ADDS_PER_TICK; step++) {
                double t = (double) step / (double) steps;
                int cx = playerCX + (int) Math.round(travelDX * t);
                int cz = playerCZ + (int) Math.round(travelDZ * t);

                for (int rz = -PREDICTIVE_CORRIDOR_RADIUS; rz <= PREDICTIVE_CORRIDOR_RADIUS && added < MAX_PREDICTIVE_ADDS_PER_TICK; rz++) {
                    for (int rx = -PREDICTIVE_CORRIDOR_RADIUS; rx <= PREDICTIVE_CORRIDOR_RADIUS && added < MAX_PREDICTIVE_ADDS_PER_TICK; rx++) {
                        int tcx = cx + rx;
                        int tcz = cz + rz;
                        long key = chunkKey(tcx, tcz);
                        if (uploadedColumns.contains(key) || inFlightSubmissions.contains(key)) {
                            continue;
                        }
                        if (mc.level.getChunkSource().getChunk(tcx, tcz, false) == null) {
                            continue;
                        }
                        if (requestedColumns.add(key)) {
                            added++;
                        }
                    }
                }
            }
        }
    }

    /**
     * Submit chunks the renderer flagged as visible-but-missing to the
     * background extractor. No render-thread work beyond the bookkeeping;
     * actual block iteration happens on a worker.
     *
     * Picks chunks by velocity-aware score: chunks ahead of the player's
     * flight path beat chunks behind it. Without this, fast movement (plane
     * mods, elytra, teleports) hits popin because vanilla FIFO ordering picks
     * whatever was queued first instead of what's most urgent.
     */
    private static void processRequestedColumns(Minecraft mc, ClientLevel level) {
        int available = Math.min(currentUploadBudget(), currentInFlightLimit() - inFlightSubmissions.size());
        if (available <= 0) {
            return;
        }

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        ArrayList<Long> batch = new ArrayList<>(available);
        synchronized (requestedColumns) {
            ArrayList<long[]> scored = new ArrayList<>(requestedColumns.size());
            Iterator<Long> iterator = requestedColumns.iterator();
            while (iterator.hasNext()) {
                long key = iterator.next();
                if (uploadedColumns.contains(key) || inFlightSubmissions.contains(key)) {
                    iterator.remove();
                    continue;
                }
                int cx = unpackChunkX(key);
                int cz = unpackChunkZ(key);
                double score = VelocityChunkPrioritizer.scoreChunk(playerX, playerZ, cx, cz);
                scored.add(new long[] { Double.doubleToRawLongBits(score), key });
            }
            scored.sort((a, b) -> Double.compare(
                    Double.longBitsToDouble(b[0]),
                    Double.longBitsToDouble(a[0])));

            for (int i = 0; i < scored.size() && batch.size() < available; i++) {
                long key = scored.get(i)[1];
                batch.add(key);
                requestedColumns.remove(key);
            }
        }

        for (long key : batch) {
            int cx = unpackChunkX(key);
            int cz = unpackChunkZ(key);
            if (ChunkUploadWorker.submit(level, uploadEpoch, cx, cz)) {
                inFlightSubmissions.add(key);
            }
        }
    }

    private static boolean isFastMode() {
        double vx = VelocityChunkPrioritizer.velocityX();
        double vz = VelocityChunkPrioritizer.velocityZ();
        return (vx * vx + vz * vz) > FAST_MODE_SPEED_THRESHOLD_SQ;
    }

    private static int currentUploadBudget() {
        return isFastMode() ? REQUEST_UPLOAD_BUDGET_FAST : REQUEST_UPLOAD_BUDGET;
    }

    private static int currentInFlightLimit() {
        return isFastMode() ? MAX_IN_FLIGHT_UPLOADS_FAST : MAX_IN_FLIGHT_UPLOADS;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static int unpackChunkX(long key) {
        return (int) key;
    }

    private static int unpackChunkZ(long key) {
        return (int) (key >>> 32);
    }
}
