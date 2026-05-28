package com.plutonium.backbone.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.plutonium.backbone.common.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

/**
 * /plutonium benchmark <chunkRadius> [centerChunkX] [centerChunkZ]
 *
 * Picks a chunk center far from spawn, or uses the provided center, and forces
 * every chunk inside the radius to ChunkStatus.FULL. Times the whole thing.
 *
 * Run once with experimentalGpuChunkGen + unsafeGpuWorldgen = false (vanilla),
 * once with both true (Plutonium). Same radius, same center, same seed, same machine.
 * The ratio is your real speedup.
 */
@Mod.EventBusSubscriber(modid = "plutonium", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldgenBenchmarkCommand {

    private WorldgenBenchmarkCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("plutonium")
                        .then(Commands.literal("benchmark")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> runBenchmark(ctx,
                                                IntegerArgumentType.getInteger(ctx, "chunkRadius")))
                                        .then(Commands.argument("centerChunkX", IntegerArgumentType.integer())
                                                .then(Commands.argument("centerChunkZ", IntegerArgumentType.integer())
                                                        .executes(ctx -> runBenchmark(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "chunkRadius"),
                                                                IntegerArgumentType.getInteger(ctx, "centerChunkX"),
                                                                IntegerArgumentType.getInteger(ctx, "centerChunkZ")))))))
                        .then(Commands.literal("prefetch_status")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            Locale.ROOT,
                                            "[Plutonium] Prefetcher: tracking %d players, %d chunks queued, %d hits, %d misses.",
                                            com.plutonium.backbone.common.ChunkPrefetcher.getPlayerCount(),
                                            com.plutonium.backbone.common.ChunkPrefetcher.getQueuedTotal(),
                                            com.plutonium.backbone.common.ChunkPrefetcher.getHits(),
                                            com.plutonium.backbone.common.ChunkPrefetcher.getMisses())), false);
                                    return 1;
                                }))
        );
    }

    private static int runBenchmark(CommandContext<CommandSourceStack> ctx, int chunkRadius) {
        long offsetSeed = System.nanoTime();
        int offsetX = 50_000 + (int)((offsetSeed >>> 1) % 10_000);
        int offsetZ = 50_000 + (int)((offsetSeed >>> 17) % 10_000);
        return runBenchmark(ctx, chunkRadius, offsetX, offsetZ);
    }

    private static int runBenchmark(CommandContext<CommandSourceStack> ctx, int chunkRadius, int offsetX, int offsetZ) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerChunkCache cache = level.getChunkSource();

        int diameter = chunkRadius * 2 + 1;
        int totalChunks = diameter * diameter;

        boolean gpuEnabled = Config.isGpuWorldgenEnabled();
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Plutonium/Bench] Forcing %d chunks (radius=%d) at center (%d,%d). GPU worldgen=%s",
                totalChunks, chunkRadius, offsetX, offsetZ, gpuEnabled ? "ON" : "OFF")),
                false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Plutonium/Bench] Repeat same area with: /plutonium benchmark %d %d %d",
                chunkRadius, offsetX, offsetZ)),
                false);

        AtomicLong slowestNs = new AtomicLong();
        AtomicLong fastestNs = new AtomicLong(Long.MAX_VALUE);
        long startNs = System.nanoTime();
        int generated = 0;

        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                int cx = offsetX + dx;
                int cz = offsetZ + dz;
                long chunkStart = System.nanoTime();
                ChunkAccess chunk = cache.getChunk(cx, cz, ChunkStatus.FULL, true);
                long chunkNs = System.nanoTime() - chunkStart;
                if (chunk == null) {
                    src.sendFailure(Component.literal(String.format(Locale.ROOT,
                            "[Plutonium/Bench] getChunk returned null at (%d,%d) after %d chunks.", cx, cz, generated)));
                    return generated;
                }
                generated++;
                slowestNs.accumulateAndGet(chunkNs, Math::max);
                fastestNs.accumulateAndGet(chunkNs, Math::min);
            }
        }
        long totalNs = System.nanoTime() - startNs;

        double totalMs = totalNs / 1_000_000.0;
        double avgMs = totalMs / generated;
        double chunksPerSec = generated / (totalNs / 1_000_000_000.0);

        int generatedFinal = generated;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Plutonium/Bench] %d chunks in %.2f ms (avg %.2f ms/chunk, %.1f chunks/sec). Min=%.2f ms Max=%.2f ms",
                generatedFinal,
                totalMs,
                avgMs,
                chunksPerSec,
                fastestNs.get() / 1_000_000.0,
                slowestNs.get() / 1_000_000.0)),
                false);
        return generated;
    }
}
