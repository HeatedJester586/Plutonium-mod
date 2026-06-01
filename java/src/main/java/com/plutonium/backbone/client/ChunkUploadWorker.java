package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background extractor that moves the per-chunk 98,304 getBlockState +
 * 98,304 getBrightness loop off the render thread.
 *
 * Threading model:
 *   - submit() runs on the render thread, looks up the LevelChunk there,
 *     AND snapshots each LevelChunkSection's PalettedContainer via .copy().
 *     This is critical: PalettedContainer.get() is only thread-safe in the
 *     absence of concurrent writes. The client's network thread mutates a
 *     chunk's container while new chunks stream in, and during a palette
 *     bit-width promotion (4-bit -> 5-bit etc.) a concurrent reader sees
 *     torn indices that decode to RANDOM HIGH-ID block states (e.g. terracotta
 *     variants in a desert). Badlands biome is the worst case because it
 *     forces frequent palette resizes. The .copy() takes a thread-isolated
 *     snapshot that the worker can read without races.
 *   - Worker threads iterate the COPIED PalettedContainers. They still call
 *     level.getBrightness() live — DataLayer is plain byte arrays without
 *     palette resizing, so occasional stale reads at worst (light isn't
 *     load-bearing for block identity).
 *   - Completed extractions are pushed to a ConcurrentLinkedQueue; the
 *     render thread polls and hands the arrays to JNI in microseconds.
 *   - Each Result carries the (level, epoch) it was submitted under so the
 *     drain side can drop stale work after a dim switch.
 */
public final class ChunkUploadWorker {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static final class Result {
        public final ClientLevel level;
        public final int epoch;
        public final int chunkX;
        public final int chunkZ;
        public final short[] blocks;
        public final byte[] lights;
        public final int nonAirCount;

        public Result(ClientLevel level, int epoch, int cx, int cz,
                      short[] blocks, byte[] lights, int nonAir) {
            this.level = level;
            this.epoch = epoch;
            this.chunkX = cx;
            this.chunkZ = cz;
            this.blocks = blocks;
            this.lights = lights;
            this.nonAirCount = nonAir;
        }
    }

    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_HEIGHT = NativeInterface.PIPELINE_CHUNK_HEIGHT;

    private static volatile ExecutorService executor;
    private static final ConcurrentLinkedQueue<Result> completed = new ConcurrentLinkedQueue<>();

    private ChunkUploadWorker() {
    }

    public static synchronized void ensureStarted() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        AtomicInteger idGen = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "Plutonium-ChunkUpload-" + idGen.getAndIncrement());
            t.setDaemon(true);
            // Never preempt the render thread on a contended scheduler.
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
        executor = Executors.newFixedThreadPool(threads, factory);
        LOGGER.info("[Plutonium/Pipeline] chunk upload worker pool started ({} background threads).", threads);
    }

    public static synchronized void shutdown() {
        completed.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Submit a chunk for background extraction. Caller must be on the render
     * thread (we look up the LevelChunk here, before handing to a worker).
     * Returns true if the job was queued, false if the chunk isn't loaded yet.
     */
    public static boolean submit(ClientLevel level, int epoch, int chunkX, int chunkZ) {
        ensureStarted();
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (chunk == null) {
            return false;
        }
        int minY = level.getMinBuildHeight();

        // Snapshot each section's PalettedContainer on the render thread.
        // .copy() is thread-safe by design (Mojang implements it as a critical
        // section over the palette/data array). The resulting copy is fully
        // isolated, so the worker can iterate it without racing MC's network
        // thread updating the live chunk during streaming. THIS IS THE FIX
        // for the "tall floating terracotta columns" visual bug in badlands —
        // those were torn PalettedContainer.get() reads during 4-bit -> 5-bit
        // bit-width promotion as new block variants were added to the palette.
        LevelChunkSection[] sections = chunk.getSections();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] sectionStates = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                sectionStates[i] = null;
                continue;
            }
            try {
                sectionStates[i] = section.getStates().copy();
            } catch (Throwable t) {
                LOGGER.warn("[Plutonium/Pipeline] PalettedContainer.copy() failed for chunk ({}, {}) section {}; treating as air",
                        chunkX, chunkZ, i, t);
                sectionStates[i] = null;
            }
        }

        ExecutorService exec = executor;
        if (exec == null) {
            return false;
        }
        try {
            exec.submit(() -> {
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    Result r = extractChunkData(level, epoch, sectionStates, chunkX, chunkZ, minY);
                    if (r != null && !Thread.currentThread().isInterrupted()) {
                        completed.add(r);
                    }
                } catch (Throwable t) {
                    LOGGER.error("[Plutonium/Pipeline] chunk extraction failed for ({}, {})",
                            chunkX, chunkZ, t);
                }
            });
        } catch (RejectedExecutionException ignored) {
            return false;
        }
        return true;
    }

    public static Result pollCompleted() {
        return completed.poll();
    }

    public static int completedCount() {
        return completed.size();
    }

    private static Result extractChunkData(ClientLevel level, int epoch,
                                           PalettedContainer<BlockState>[] sectionStates,
                                           int chunkX, int chunkZ, int minY) {
        short[] blocks = new short[NativeInterface.PIPELINE_CHUNK_BLOCK_COUNT];
        byte[] lights = new byte[NativeInterface.PIPELINE_CHUNK_BLOCK_COUNT];

        BlockState airState = Blocks.AIR.defaultBlockState();
        int airStateId = Block.BLOCK_STATE_REGISTRY.getId(airState);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int nonAir = 0;

        for (int ly = 0; ly < CHUNK_HEIGHT; ly++) {
            if ((ly & 15) == 0 && Thread.currentThread().isInterrupted()) {
                return null;
            }
            int worldY = minY + ly;

            // Read block states from the per-section snapshot we cloned on
            // the render thread. sectionStates[i] == null means section i is
            // all air (we cheaply tested with hasOnlyAir() before copying).
            int sectionIdx = ly >> 4;
            int localY = ly & 15;
            PalettedContainer<BlockState> container =
                    sectionIdx < sectionStates.length ? sectionStates[sectionIdx] : null;

            for (int z = 0; z < CHUNK_SIDE; z++) {
                int worldZ = (chunkZ << 4) + z;
                for (int x = 0; x < CHUNK_SIDE; x++) {
                    int worldX = (chunkX << 4) + x;
                    pos.set(worldX, worldY, worldZ);

                    BlockState state;
                    int stateId;
                    if (container == null) {
                        state = airState;
                        stateId = airStateId;
                    } else {
                        state = container.get(x, localY, z);
                        stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
                    }
                    int idx = index(x, ly, z);
                    blocks[idx] = (short) (stateId & 0xFFFF);
                    if (!state.isAir()) {
                        nonAir++;
                    }

                    int sky = level.getBrightness(LightLayer.SKY, pos);
                    int block = level.getBrightness(LightLayer.BLOCK, pos);
                    lights[idx] = (byte) (((sky & 0x0F) << 4) | (block & 0x0F));
                }
            }
        }

        return new Result(level, epoch, chunkX, chunkZ, blocks, lights, nonAir);
    }

    private static int index(int x, int y, int z) {
        return x + (z * CHUNK_SIDE) + (y * CHUNK_SIDE * CHUNK_SIDE);
    }
}
