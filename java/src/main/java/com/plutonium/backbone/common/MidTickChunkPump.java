package com.plutonium.backbone.common;

import com.plutonium.backbone.PlutoniumMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Port of C2ME's mid-tick chunk-task pumping technique.
 *
 * Vanilla only runs the chunk task executor once per server tick — that's a
 * 50ms wait between drain cycles. Under high MSPT (and during world loading,
 * teleports, or fast travel) the chunk task queue grows faster than it
 * drains, which means newly requested chunks queue up and the player sees fog.
 *
 * C2ME's fix: pump the main-thread chunk executor periodically *during* the
 * tick. They mixin into block-tick and fluid-tick returns and pump every
 * ~100us. We do the same thing on Forge by piggy-backing on Forge's
 * ServerTickEvent and draining for a bounded slice each tick. Less invasive
 * than C2ME's tick-injection because we don't touch vanilla's inner loops.
 *
 * Budget is configurable via constants below. Default 4ms per tick lets us
 * drain meaningful queue depth without pushing MSPT over the 50ms tick budget.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MidTickChunkPump {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlutoniumMidTick");

    /** Hard ceiling on time spent pumping per server tick per level (nanoseconds). */
    private static final long PUMP_BUDGET_NS_PER_LEVEL = 4_000_000L;
    /** Hard ceiling on tasks per pump pass. Prevents one giant task from blowing the budget. */
    private static final int PUMP_MAX_TASKS_PER_LEVEL = 4096;
    /** Log a summary every N ticks (~5s at 20 TPS). */
    private static final int LOG_INTERVAL_TICKS = 100;

    private static final AtomicLong TOTAL_TASKS_RUN = new AtomicLong();
    private static final AtomicLong TOTAL_TICKS = new AtomicLong();
    private static int ticksSinceLog = 0;
    private static long tasksSinceLog = 0;
    private static long timeSinceLogNs = 0;

    private MidTickChunkPump() {
    }

    @SubscribeEvent
    public static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;

        long startNs = System.nanoTime();
        long totalRanThisTick = 0;

        for (ServerLevel level : server.getAllLevels()) {
            totalRanThisTick += pumpLevel(level);
        }
        long elapsedNs = System.nanoTime() - startNs;

        TOTAL_TASKS_RUN.addAndGet(totalRanThisTick);
        TOTAL_TICKS.incrementAndGet();
        tasksSinceLog += totalRanThisTick;
        timeSinceLogNs += elapsedNs;
        ticksSinceLog++;
        if (ticksSinceLog >= LOG_INTERVAL_TICKS) {
            if (tasksSinceLog > 0) {
                LOGGER.info("[Plutonium/MidTick] last {} ticks: ran {} chunk tasks in {} ms total ({} tasks/tick avg).",
                        ticksSinceLog, tasksSinceLog,
                        String.format(java.util.Locale.ROOT, "%.2f", timeSinceLogNs / 1_000_000.0),
                        String.format(java.util.Locale.ROOT, "%.1f", (double) tasksSinceLog / ticksSinceLog));
            }
            ticksSinceLog = 0;
            tasksSinceLog = 0;
            timeSinceLogNs = 0;
        }
    }

    private static long pumpLevel(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        long endTime = System.nanoTime() + PUMP_BUDGET_NS_PER_LEVEL;
        long ran = 0;
        try {
            while (ran < PUMP_MAX_TASKS_PER_LEVEL && System.nanoTime() < endTime) {
                // ServerChunkCache.pollTask() delegates to its private
                // mainThreadProcessor's pollTask. Returns true if it ran a
                // pending task. Vanilla calls this exactly once per server tick
                // — we call it as many times as we have budget for.
                if (!cache.pollTask()) break;
                ran++;
            }
        } catch (Throwable t) {
            LOGGER.warn("[Plutonium/MidTick] pumpLevel error in {}: {}",
                    level.dimension().location(), t.toString());
        }
        return ran;
    }

    public static long getTotalTasksRun() { return TOTAL_TASKS_RUN.get(); }
    public static long getTotalTicks() { return TOTAL_TICKS.get(); }
}
