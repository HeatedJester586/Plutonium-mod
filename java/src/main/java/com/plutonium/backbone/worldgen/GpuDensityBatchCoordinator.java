package com.plutonium.backbone.worldgen;

import com.plutonium.backbone.bridge.NativeInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates per-chunk GPU density evaluation calls into batched JNI launches.
 *
 * The vanilla worldgen worker pool calls into FastGpuChunkMixin per-chunk. If
 * each call individually hands a 1225-thread kernel to CUDA, the GPU sits at
 * ~2% utilization no matter how many cores it has. The fix is to wait briefly
 * for sibling worker threads to also queue requests, then dispatch all of them
 * in one launch sized chunkCount * 1225 threads.
 *
 * Worker threads enter requestDensity() and either:
 *   - find the batch full → kick off the dispatch immediately and wait for it
 *   - find a partial batch with room → join it, then either get pulled along
 *     for the ride or trigger the dispatch themselves when the timeout fires.
 *
 * The coordinator NEVER blocks indefinitely: if no sibling joins within the
 * timeout, a single-chunk batch dispatches (degrades gracefully to the
 * original behavior, just with one extra synchronization). Anything that
 * actually wants throughput tends to have multiple workers calling at once.
 */
public final class GpuDensityBatchCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlutoniumWorldgen");

    private static final int MAX_BATCH = 64;
    private static final long BATCH_WAIT_NANOS = 500_000L;     // 0.5 ms — small jitter window
    private static final int DENSITY_CELLS_PER_CHUNK = GpuDensityCellCache.DENSITY_CELL_COUNT;

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Condition BATCH_READY = LOCK.newCondition();
    private static final Condition BATCH_FULL = LOCK.newCondition();

    private static long enginePtr;
    private static long currentSeed;

    private static Batch pending = new Batch();
    private static boolean dispatching = false;

    private GpuDensityBatchCoordinator() {
    }

    public static void configure(long enginePtr, long seed) {
        LOCK.lock();
        try {
            GpuDensityBatchCoordinator.enginePtr = enginePtr;
            GpuDensityBatchCoordinator.currentSeed = seed;
        } finally {
            LOCK.unlock();
        }
    }

    public static boolean requestDensity(int chunkX, int chunkZ, ByteBuffer outBuffer) {
        if (enginePtr == 0L || outBuffer == null
                || outBuffer.capacity() < DENSITY_CELLS_PER_CHUNK * Double.BYTES) {
            return false;
        }

        Slot slot;
        boolean iAmDispatcher = false;
        LOCK.lock();
        try {
            while (dispatching) {
                // Another thread is currently flushing the previous batch.
                // Wait until the new batch is open for entries.
                BATCH_READY.awaitUninterruptibly();
            }
            slot = pending.add(chunkX, chunkZ);
            if (pending.size() >= MAX_BATCH) {
                iAmDispatcher = true;
                dispatching = true;
            } else {
                long deadline = System.nanoTime() + BATCH_WAIT_NANOS;
                while (!slot.completed && !dispatching) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        iAmDispatcher = true;
                        dispatching = true;
                        break;
                    }
                    try {
                        BATCH_FULL.awaitNanos(remaining);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            LOCK.unlock();
        }

        if (iAmDispatcher) {
            Batch toFlush;
            LOCK.lock();
            try {
                toFlush = pending;
                pending = new Batch();
            } finally {
                LOCK.unlock();
            }
            boolean ok = dispatch(toFlush);
            LOCK.lock();
            try {
                toFlush.completed = true;
                for (Slot s : toFlush.slots) s.completed = true;
                toFlush.success = ok;
                dispatching = false;
                BATCH_READY.signalAll();
                BATCH_FULL.signalAll();
            } finally {
                LOCK.unlock();
            }
            if (ok) {
                copyResult(toFlush, slot, outBuffer);
            }
            return ok;
        }

        LOCK.lock();
        try {
            while (!slot.batch.completed) {
                BATCH_FULL.awaitUninterruptibly();
            }
        } finally {
            LOCK.unlock();
        }
        if (slot.batch.success) {
            copyResult(slot.batch, slot, outBuffer);
            return true;
        }
        return false;
    }

    private static boolean dispatch(Batch batch) {
        int count = batch.size();
        if (count == 0) return true;

        ByteBuffer coords = ByteBuffer.allocateDirect(count * 2 * Integer.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer out = ByteBuffer.allocateDirect(count * DENSITY_CELLS_PER_CHUNK * Double.BYTES).order(ByteOrder.nativeOrder());
        for (Slot s : batch.slots) {
            coords.putInt(s.chunkX);
            coords.putInt(s.chunkZ);
        }
        coords.flip();
        out.clear();

        long startNs = System.nanoTime();
        boolean ok = NativeInterface.nEvaluateChunkDensityCellsBatch(
                enginePtr, coords, out, count, currentSeed);
        long elapsedNs = System.nanoTime() - startNs;

        if (!ok) {
            LOGGER.warn("[Plutonium] Batched density dispatch failed (count={}, {} ms).",
                    count, elapsedNs / 1_000_000.0);
            return false;
        }
        batch.results = out;
        if (count >= 4) {
            // Only worth logging when batching actually fired up.
            LOGGER.info("[Plutonium] Batched density: {} chunks in {} ms ({} us/chunk).",
                    count,
                    String.format(java.util.Locale.ROOT, "%.3f", elapsedNs / 1_000_000.0),
                    String.format(java.util.Locale.ROOT, "%.1f", (elapsedNs / 1_000.0) / count));
        }
        return true;
    }

    private static void copyResult(Batch batch, Slot slot, ByteBuffer outBuffer) {
        if (batch.results == null) return;
        int srcOffset = slot.batchIndex * DENSITY_CELLS_PER_CHUNK * Double.BYTES;
        int bytes = DENSITY_CELLS_PER_CHUNK * Double.BYTES;
        outBuffer.clear();
        ByteBuffer dup = batch.results.duplicate();
        dup.order(batch.results.order());
        dup.position(srcOffset);
        dup.limit(srcOffset + bytes);
        outBuffer.put(dup);
        outBuffer.position(0);
        outBuffer.limit(bytes);
    }

    private static final class Batch {
        final ArrayList<Slot> slots = new ArrayList<>(MAX_BATCH);
        ByteBuffer results;
        volatile boolean completed;
        volatile boolean success;

        Slot add(int chunkX, int chunkZ) {
            Slot s = new Slot(chunkX, chunkZ, slots.size(), this);
            slots.add(s);
            return s;
        }

        int size() {
            return slots.size();
        }
    }

    private static final class Slot {
        final int chunkX;
        final int chunkZ;
        final int batchIndex;
        final Batch batch;
        volatile boolean completed;

        Slot(int chunkX, int chunkZ, int batchIndex, Batch batch) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.batchIndex = batchIndex;
            this.batch = batch;
        }
    }
}
