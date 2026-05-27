package com.plutonium.backbone.worldgen;

import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldgenProfiler {

    private static final Logger LOGGER = LogManager.getLogger("PlutoniumWorldgen");
    private static final long SUMMARY_INTERVAL_NS = 2_000_000_000L;
    private static final long SLOW_STAGE_NS = 25_000_000L;
    private static final ConcurrentHashMap<String, StageStats> STAGES = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_SUMMARY_NS = new AtomicLong(System.nanoTime());

    private WorldgenProfiler() {
    }

    public static void record(String stage, ChunkPos pos, long nanos, Throwable failure) {
        if (stage == null || nanos < 0L) {
            return;
        }
        StageStats stats = STAGES.computeIfAbsent(stage, ignored -> new StageStats());
        stats.count.incrementAndGet();
        stats.totalNanos.addAndGet(nanos);
        stats.maxNanos.accumulateAndGet(nanos, Math::max);
        if (failure != null) {
            stats.failures.incrementAndGet();
        }
        if (nanos >= SLOW_STAGE_NS || failure != null) {
            LOGGER.info("[Plutonium/WorldgenProfiler] stage={} chunk={},{} ms={} failure={}",
                    stage,
                    pos == null ? 0 : pos.x,
                    pos == null ? 0 : pos.z,
                    formatMs(nanos),
                    failure == null ? "none" : failure.getClass().getSimpleName());
        }
        emitSummaryIfDue();
    }

    public static void emitSummaryIfDue() {
        long now = System.nanoTime();
        long last = LAST_SUMMARY_NS.get();
        if ((now - last) < SUMMARY_INTERVAL_NS || !LAST_SUMMARY_NS.compareAndSet(last, now)) {
            return;
        }
        if (STAGES.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("[Plutonium/WorldgenProfiler] summary");
        STAGES.forEach((stage, stats) -> {
            long count = stats.count.getAndSet(0L);
            long total = stats.totalNanos.getAndSet(0L);
            long max = stats.maxNanos.getAndSet(0L);
            long failures = stats.failures.getAndSet(0L);
            if (count > 0L) {
                sb.append(' ')
                        .append(stage)
                        .append("{count=").append(count)
                        .append(",avgMs=").append(formatMs(total / count))
                        .append(",maxMs=").append(formatMs(max))
                        .append(",fail=").append(failures)
                        .append('}');
            }
        });
        LOGGER.info(sb.toString());
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static final class StageStats {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
    }
}
