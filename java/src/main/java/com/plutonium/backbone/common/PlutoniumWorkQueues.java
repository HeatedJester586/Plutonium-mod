package com.plutonium.backbone.common;

import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlutoniumWorkQueues {

    private static final AtomicReference<ExecutorService> WORLDGEN_EXECUTOR = new AtomicReference<>();
    private static final AtomicReference<ExecutorService> CHUNK_IO_EXECUTOR = new AtomicReference<>();
    private static final PriorityBlockingQueue<QueuedWorldgenTask> WORLDGEN_QUEUE = new PriorityBlockingQueue<>(128, Comparator
            .comparingInt(QueuedWorldgenTask::priority)
            .thenComparingLong(QueuedWorldgenTask::sequence));
    private static final ConcurrentHashMap<Long, QueuedWorldgenTask> QUEUED_WORLDGEN_TASKS = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE_WORLDGEN_TASKS = new AtomicInteger();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static volatile int configuredWorldgenThreads = -1;
    private static volatile int configuredChunkIoThreads = -1;
    private static final AtomicLong TELEMETRY_LAST_LOG_NS = new AtomicLong();
    private static final long TELEMETRY_INTERVAL_NS = 5_000_000_000L; // Log every 5 seconds

    private PlutoniumWorkQueues() {
    }

    public static Executor chunkIoExecutor() {
        return ensureChunkIoExecutor();
    }

    public static boolean enqueueWorldgenTask(ChunkPos chunkPos, int priority, Runnable runnable) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        Objects.requireNonNull(runnable, "runnable");
        ensureWorldgenExecutor();

        long chunkKey = chunkPos.toLong();
        QueuedWorldgenTask task = new QueuedWorldgenTask(chunkKey, Math.max(0, priority), SEQUENCE.incrementAndGet(), runnable);
        QueuedWorldgenTask existing = QUEUED_WORLDGEN_TASKS.putIfAbsent(chunkKey, task);
        if (existing != null) {
            return false; // Already queued
        }

        WORLDGEN_QUEUE.offer(task);
        telemetryLog();
        return true;
    }

    public static void pumpWorldgenTasks(int submissionBudget) {
        if (submissionBudget <= 0) {
            return;
        }

        ExecutorService executor = ensureWorldgenExecutor();
        int maxThreads = Math.max(1, configuredWorldgenThreads);
        int submitted = 0;
        while (submissionBudget-- > 0 && ACTIVE_WORLDGEN_TASKS.get() < maxThreads) {
            QueuedWorldgenTask task = WORLDGEN_QUEUE.poll();
            if (task == null) {
                break;
            }

            if (!QUEUED_WORLDGEN_TASKS.remove(task.chunkKey(), task)) {
                continue;
            }

            ACTIVE_WORLDGEN_TASKS.incrementAndGet();
            submitted++;
            executor.execute(() -> {
                try {
                    task.runnable().run();
                } finally {
                    ACTIVE_WORLDGEN_TASKS.decrementAndGet();
                }
            });
        }

        telemetryLog();
    }

    private static void telemetryLog() {
        long now = System.nanoTime();
        long last = TELEMETRY_LAST_LOG_NS.get();
        if (now - last < TELEMETRY_INTERVAL_NS) {
            return;
        }

        if (!TELEMETRY_LAST_LOG_NS.compareAndSet(last, now)) {
            return; // Someone else is logging
        }

        int queued = WORLDGEN_QUEUE.size();
        int active = ACTIVE_WORLDGEN_TASKS.get();
        int maxThreads = Math.max(1, configuredWorldgenThreads);
        System.out.printf("[Plutonium] Worldgen: %d queued, %d active/%d threads (%d%% utilized)%n",
                queued, active, maxThreads, (active * 100) / maxThreads);
    }

    public static int queuedWorldgenTasks() {
        return WORLDGEN_QUEUE.size();
    }

    public static void clearWorldgenTasks() {
        WORLDGEN_QUEUE.clear();
        QUEUED_WORLDGEN_TASKS.clear();
    }

    public static void shutdown() {
        clearWorldgenTasks();
        shutdownExecutor(WORLDGEN_EXECUTOR);
        shutdownExecutor(CHUNK_IO_EXECUTOR);
        configuredWorldgenThreads = -1;
        configuredChunkIoThreads = -1;
        ACTIVE_WORLDGEN_TASKS.set(0);
    }

    private static ExecutorService ensureWorldgenExecutor() {
        int targetThreads = Math.max(1, Math.min(Config.CLIENT.cpuThreads.get(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = WORLDGEN_EXECUTOR.get();
        if (executor != null && !executor.isShutdown() && configuredWorldgenThreads == targetThreads) {
            return executor;
        }

        synchronized (PlutoniumWorkQueues.class) {
            executor = WORLDGEN_EXECUTOR.get();
            if (executor != null && !executor.isShutdown() && configuredWorldgenThreads == targetThreads) {
                return executor;
            }

            ExecutorService replacement = Executors.newFixedThreadPool(targetThreads, daemonFactory("Pluto-Worldgen"));
            ExecutorService old = WORLDGEN_EXECUTOR.getAndSet(replacement);
            configuredWorldgenThreads = targetThreads;
            if (old != null) {
                old.shutdownNow();
            }
            return replacement;
        }
    }

    private static ExecutorService ensureChunkIoExecutor() {
        int targetThreads = Math.max(1, Math.min(Math.max(1, Config.CLIENT.cpuThreads.get() / 2), 4));
        ExecutorService executor = CHUNK_IO_EXECUTOR.get();
        if (executor != null && !executor.isShutdown() && configuredChunkIoThreads == targetThreads) {
            return executor;
        }

        synchronized (PlutoniumWorkQueues.class) {
            executor = CHUNK_IO_EXECUTOR.get();
            if (executor != null && !executor.isShutdown() && configuredChunkIoThreads == targetThreads) {
                return executor;
            }

            ExecutorService replacement = Executors.newFixedThreadPool(targetThreads, daemonFactory("Pluto-ChunkIO"));
            ExecutorService old = CHUNK_IO_EXECUTOR.getAndSet(replacement);
            configuredChunkIoThreads = targetThreads;
            if (old != null) {
                old.shutdownNow();
            }
            return replacement;
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void shutdownExecutor(AtomicReference<ExecutorService> reference) {
        ExecutorService executor = reference.getAndSet(null);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private record QueuedWorldgenTask(long chunkKey, int priority, long sequence, Runnable runnable) {
    }
}
