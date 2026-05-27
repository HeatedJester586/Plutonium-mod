package com.plutonium.backbone.mixin;

import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.client.PlutoniumBackend;
import com.plutonium.backbone.common.Config;
import com.plutonium.backbone.worldgen.BytecodeCompiler;
import com.plutonium.backbone.worldgen.DensityProgramSerializer;
import com.plutonium.backbone.worldgen.GpuDensityCellCache;
import com.plutonium.backbone.worldgen.GpuWorldgenState;
import com.plutonium.backbone.worldgen.WorldgenProfiler;
import net.minecraft.core.Holder;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hooks vanilla chunk terrain generation for optional CUDA offload.
 *
 * <p>Vanilla source reference (research tree):
 * {@code NoiseBasedChunkGenerator.fillFromNoise} ~line 241 schedules
 * {@code doFill} ~line 270 on {@code Util.backgroundExecutor()} - density cells,
 * aquifer, block placement into {@code LevelChunkSection}.
 *
 * <p>Default: {@link com.plutonium.backbone.common.Config.Client#experimentalGpuChunkGen}
 * is false - vanilla owns terrain. When true, native code evaluates the finalDensity
 * cell lattice, then vanilla {@link #doFill} consumes those values and writes the real
 * {@code ChunkAccess}. Unsupported worlds fall back to vanilla immediately.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class FastGpuChunkMixin {

    // Shadow of NoiseBasedChunkGenerator.doFill - vanilla parity fallback (see doFill ~line 270).

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    private ChunkAccess doFill(Blender blender, StructureManager structureManager,
                                RandomState randomState, ChunkAccess chunkAccess,
                                int minCellY, int cellCountY) {
        throw new AssertionError("@Shadow stub - mixin should replace this at runtime");
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("PlutoniumWorldgen");
    private static final int GPU_DENSITY_CELL_OUTPUT_BYTES =
            GpuDensityCellCache.DENSITY_CELL_COUNT * Double.BYTES;
    private static final AtomicInteger ACTIVE_GPU_WORLDGEN_TASKS = new AtomicInteger();
    private static final AtomicInteger GPU_WORLDGEN_AST_LOGS = new AtomicInteger();
    private static final AtomicInteger GPU_WORLDGEN_SUCCESS_LOGS = new AtomicInteger();
    private static final AtomicInteger GPU_WORLDGEN_FALLBACK_LOGS = new AtomicInteger();
    private static final AtomicInteger GPU_WORLDGEN_ERROR_LOGS = new AtomicInteger();
    private static final AtomicLong GPU_WORLDGEN_TOTAL_TASKS = new AtomicLong();
    private static final AtomicLong GPU_WORLDGEN_ACCEPTED_TASKS = new AtomicLong();
    private static final AtomicLong GPU_WORLDGEN_FALLBACK_TASKS = new AtomicLong();
    private static final AtomicLong GPU_WORLDGEN_NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong GPU_WORLDGEN_VANILLA_NANOS = new AtomicLong();
    private static final AtomicLong GPU_WORLDGEN_TASK_NANOS = new AtomicLong();
    private static final ThreadLocal<ByteBuffer> GPU_DENSITY_CELL_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(GPU_DENSITY_CELL_OUTPUT_BYTES).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<Long> VANILLA_NOISE_START_NS = new ThreadLocal<>();

    @Inject(method = "fillFromNoise", at = @At("RETURN"))
    private void plutonium$profileNoiseFuture(Executor executor, Blender blender, RandomState randomState,
                                              StructureManager structureManager, ChunkAccess chunkAccess,
                                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!Config.isGpuWorldgenEnabled()) {
            return;
        }
        Long start = VANILLA_NOISE_START_NS.get();
        VANILLA_NOISE_START_NS.remove();
        if (start == null) {
            return;
        }
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            return;
        }
        ChunkPos pos = chunkAccess.getPos();
        future.whenComplete((result, failure) ->
                WorldgenProfiler.record("NOISE", pos, System.nanoTime() - start, failure));
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void plutonium$asyncC2MELogic(Executor executor, Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunkAccess, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // Profiling start — merged in from the old plutonium$profileNoiseStart HEAD inject
        // so there's only ONE @Inject at HEAD on fillFromNoise.
        if (!Config.isGpuWorldgenEnabled()) {
            return;
        }

        VANILLA_NOISE_START_NS.set(System.nanoTime());
        if (executor == null) {
            return;
        }
        // If we've previously discovered this world's density tree contains a node
        // our serializer doesn't support, every chunk hits vanilla fillFromNoise
        // directly — no point spinning up an async task just to throw.
        if (!GpuWorldgenState.isAstCompatible()) {
            return;
        }
        long enginePtr = PlutoniumBackend.getBackendPtr();
        if (enginePtr == 0) {
            enginePtr = PlutoniumBackend.ensureBackendForWorldgen();
            if (enginePtr == 0) {
                return;
            }
        }

        ChunkPos chunkPos = chunkAccess.getPos();
        long chunkKey = chunkPos.toLong();
        GpuWorldgenState.InFlightGpuGeneration existing = GpuWorldgenState.getInFlight(chunkKey);
        if (existing != null) {
            if (existing.chunk() == chunkAccess) {
                cir.setReturnValue(existing.future());
            }
            return;
        }

        // Earlier code capped this at `cpuThreads` and silently returned when full,
        // which let chunks randomly fall through to fully vanilla density evaluation.
        // The executor already queues tasks; we don't need an outer cap.

        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        GpuWorldgenState.InFlightGpuGeneration generation =
                new GpuWorldgenState.InFlightGpuGeneration(chunkAccess, future);
        GpuWorldgenState.InFlightGpuGeneration raced = GpuWorldgenState.putInFlightIfAbsent(chunkKey, generation);
        if (raced != null) {
            if (raced.chunk() == chunkAccess) {
                cir.setReturnValue(raced.future());
            }
            return;
        }

        int x = chunkPos.x;
        int z = chunkPos.z;
        long seed = extractWorldSeed(chunkAccess);
        long worldgenEpoch = GpuWorldgenState.currentEpoch();
        final long gpuEnginePtr = enginePtr;

        Runnable gpuWorldgenTask = () -> {
            ACTIVE_GPU_WORLDGEN_TASKS.incrementAndGet();
            long taskStartNs = System.nanoTime();
            long nativeNs = 0L;
            long vanillaNs = 0L;
            try {
                if (worldgenEpoch != GpuWorldgenState.currentEpoch()) {
                    future.complete(chunkAccess);
                    return;
                }
                if (!GpuWorldgenState.isAstUploaded()) {
                    synchronized (GpuWorldgenState.class) {
                        if (!GpuWorldgenState.isAstUploaded() && GpuWorldgenState.isAstCompatible()) {
                            try {
                                var ins = DensityProgramSerializer.compile(randomState.router().finalDensity());
                                var buf = BytecodeCompiler.packForGPU(ins);
                                NativeInterface.nUploadAST(gpuEnginePtr, buf, buf.capacity());
                                GpuWorldgenState.markAstUploaded();
                                if (GPU_WORLDGEN_AST_LOGS.getAndIncrement() == 0) {
                                    LOGGER.info("[Plutonium] Uploaded GPU worldgen AST: {} instructions, {} bytes.",
                                            ins.size(), buf.capacity());
                                }
                            } catch (UnsupportedOperationException unsupported) {
                                // World contains a density function class our serializer doesn't
                                // know about (worldgen mod, custom datapack, etc.). Switch every
                                // chunk to vanilla generation from here on.
                                GpuWorldgenState.markAstIncompatible();
                                LOGGER.warn("[Plutonium] Density function not GPU-compilable ({}); reverting to vanilla worldgen for this world.",
                                        unsupported.getMessage());
                            }
                        }
                    }
                }
                if (!GpuWorldgenState.isAstCompatible()) {
                    // Compile failed in the synchronized block above; complete with vanilla.
                    ChunkAccess vanillaResult = plutonium$runVanillaFill(blender, randomState, structureManager, chunkAccess);
                    future.complete(vanillaResult);
                    return;
                }
                ByteBuffer densityCells = GPU_DENSITY_CELL_BUFFER.get();
                densityCells.clear();
                long nativeStartNs = System.nanoTime();
                boolean generated = NativeInterface.nEvaluateChunkDensityCells(
                        gpuEnginePtr, x, z, seed, densityCells, GpuDensityCellCache.DENSITY_CELL_COUNT);
                nativeNs = System.nanoTime() - nativeStartNs;
                if (!generated) {
                    if (GPU_WORLDGEN_FALLBACK_LOGS.getAndIncrement() < 32) {
                        LOGGER.warn("[Plutonium] GPU density cells failed for chunk {},{} (nativeMs={}); falling back to vanilla doFill.",
                                x, z, formatMs(nativeNs));
                    }
                    long vanillaStartNs = System.nanoTime();
                    ChunkAccess vanillaResult = plutonium$runVanillaFill(blender, randomState, structureManager, chunkAccess);
                    vanillaNs = System.nanoTime() - vanillaStartNs;
                    future.complete(vanillaResult);
                    recordWorldgenTelemetry(false, nativeNs, vanillaNs,
                            System.nanoTime() - taskStartNs, x, z);
                    return;
                }
                if (GPU_WORLDGEN_SUCCESS_LOGS.getAndIncrement() < 32) {
                    LOGGER.info("[Plutonium] CUDA density cells ready for chunk {},{} (cells={}, nativeMs={}).",
                            x, z, GpuDensityCellCache.DENSITY_CELL_COUNT, formatMs(nativeNs));
                }
                ChunkAccess result = chunkAccess;
                if (worldgenEpoch == GpuWorldgenState.currentEpoch()) {
                    long vanillaStartNs = System.nanoTime();
                    GpuDensityCellCache.begin(x, z, densityCells);
                    try {
                        result = plutonium$runVanillaFill(blender, randomState, structureManager, chunkAccess);
                    } finally {
                        GpuDensityCellCache.end();
                    }
                    vanillaNs = System.nanoTime() - vanillaStartNs;
                }
                future.complete(result);
                recordWorldgenTelemetry(true, nativeNs, vanillaNs,
                        System.nanoTime() - taskStartNs, x, z);
            } catch (Throwable t) {
                if (GPU_WORLDGEN_ERROR_LOGS.getAndIncrement() < 8) {
                    LOGGER.error("[Plutonium] GPU worldgen errored for chunk {},{}; falling back to vanilla doFill.",
                            x, z, t);
                }
                try {
                    long vanillaStartNs = System.nanoTime();
                    ChunkAccess vanillaResult = plutonium$runVanillaFill(blender, randomState, structureManager, chunkAccess);
                    vanillaNs = System.nanoTime() - vanillaStartNs;
                    future.complete(vanillaResult);
                    recordWorldgenTelemetry(false, nativeNs, vanillaNs,
                            System.nanoTime() - taskStartNs, x, z);
                } catch (Throwable vanillaErr) {
                    LOGGER.error("[Plutonium] Vanilla doFill fallback ALSO failed for chunk {},{}.", x, z, vanillaErr);
                    future.complete(chunkAccess);
                }
            } finally {
                GpuWorldgenState.removeInFlight(chunkKey, generation);
                ACTIVE_GPU_WORLDGEN_TASKS.decrementAndGet();
            }
        };

        try {
            Util.backgroundExecutor().execute(gpuWorldgenTask);
        } catch (RejectedExecutionException e) {
            GpuWorldgenState.removeInFlight(chunkKey, generation);
            return;
        }

        cir.setReturnValue(future);
    }

    private static void recordWorldgenTelemetry(boolean accepted, long nativeNs, long vanillaNs,
                                                long taskNs, int chunkX, int chunkZ) {
        long total = GPU_WORLDGEN_TOTAL_TASKS.incrementAndGet();
        long acceptedCount = accepted
                ? GPU_WORLDGEN_ACCEPTED_TASKS.incrementAndGet()
                : GPU_WORLDGEN_ACCEPTED_TASKS.get();
        long fallbackCount = accepted
                ? GPU_WORLDGEN_FALLBACK_TASKS.get()
                : GPU_WORLDGEN_FALLBACK_TASKS.incrementAndGet();

        GPU_WORLDGEN_NATIVE_NANOS.addAndGet(nativeNs);
        GPU_WORLDGEN_VANILLA_NANOS.addAndGet(vanillaNs);
        GPU_WORLDGEN_TASK_NANOS.addAndGet(taskNs);

        if (total <= 32 || (total & 63L) == 0L) {
            LOGGER.info(
                    "[Plutonium] worldgen telemetry chunk {},{} accepted={} active={} totals(total={}, gpu={}, fallback={}) avgMs(nativeDensity={}, vanillaFill={}, task={})",
                    chunkX, chunkZ, accepted, ACTIVE_GPU_WORLDGEN_TASKS.get(),
                    total, acceptedCount, fallbackCount,
                    avgMs(GPU_WORLDGEN_NATIVE_NANOS, total),
                    avgMs(GPU_WORLDGEN_VANILLA_NANOS, fallbackCount),
                    avgMs(GPU_WORLDGEN_TASK_NANOS, total));
        }
    }

    private static String avgMs(AtomicLong nanos, long count) {
        if (count <= 0L) {
            return "0.000";
        }
        return formatMs(nanos.get() / count);
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    /**
     * Invokes vanilla {@link NoiseBasedChunkGenerator#doFill} reflectively-via-mixin-shadow
     * so the chunk gets byte-correct vanilla terrain: density, aquifers, ore veins,
     * blender pass — the works. Used as our fallback whenever the GPU path can't
     * deliver a real chunk (worldgen mod density node, GPU empty output, exception
     * inside the async task). Replaces the old sin/cos placeholder that was
     * producing the swirly ribbed terrain.
     *
     * The cell math here mirrors {@link NoiseBasedChunkGenerator#fillFromNoise}
     * lines 242–245 in the 1.20.1 mapped source.
     */
    private ChunkAccess plutonium$runVanillaFill(Blender blender, RandomState randomState,
                                                 StructureManager structureManager, ChunkAccess chunkAccess) {
        NoiseSettings noiseSettings = this.settings.value()
                .noiseSettings()
                .clampToHeightAccessor(chunkAccess.getHeightAccessorForGeneration());
        int minY = noiseSettings.minY();
        int minCellY = Mth.floorDiv(minY, noiseSettings.getCellHeight());
        int cellCountY = Mth.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        if (cellCountY <= 0) {
            return chunkAccess;
        }
        return this.doFill(blender, structureManager, randomState, chunkAccess, minCellY, cellCountY);
    }

    /**
     * World seed for CUDA density evaluation. Seed 0 produces empty GPU chunks
     * (log: "GPU output empty ... solidBlocks=0").
     */
    private long extractWorldSeed(ChunkAccess chunkAccess) {
        long cached = GpuWorldgenState.worldSeed();
        if (cached != 0L) {
            return cached;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            var overworld = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (overworld != null) {
                long seed = overworld.getSeed();
                GpuWorldgenState.setWorldSeed(seed);
                return seed;
            }
        }
        try {
            Object current = this;
            Class<?> clazz = current.getClass();
            java.lang.reflect.Field levelField = findFieldByType(clazz, "net.minecraft.server.level.ServerLevel");
            if (levelField != null) {
                levelField.setAccessible(true);
                Object level = levelField.get(current);
                if (level != null) {
                    long seed = getSeedFromLevel(level);
                    if (seed != 0L) {
                        GpuWorldgenState.setWorldSeed(seed);
                        return seed;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        LOGGER.warn("[Plutonium] Could not resolve world seed for GPU worldgen (chunk {},{}); using 0.",
                chunkAccess.getPos().x, chunkAccess.getPos().z);
        return 0L;
    }

    private long getSeedFromLevel(Object level) {
        try {
            // Try level.getServer().getWorldData().seed() or similar
            java.lang.reflect.Method getServer = level.getClass().getMethod("getServer");
            Object server = getServer.invoke(level);
            
            java.lang.reflect.Method getWorldData = server.getClass().getMethod("getWorldData");
            Object worldData = getWorldData.invoke(server);
            
            // Try to call seed() on worldData
            try {
                java.lang.reflect.Method getSeed = worldData.getClass().getMethod("seed");
                Object result = getSeed.invoke(worldData);
                if (result instanceof Long) {
                    return (Long) result;
                }
            } catch (Exception e1) {
                // Try alternate: worldGenSettings().seed()
                try {
                    java.lang.reflect.Method getSettings = worldData.getClass().getMethod("worldGenSettings");
                    Object settings = getSettings.invoke(worldData);
                    java.lang.reflect.Method getSeed = settings.getClass().getMethod("seed");
                    Object result = getSeed.invoke(settings);
                    if (result instanceof Long) {
                        return (Long) result;
                    }
                } catch (Exception e2) {
                    // Fallback
                }
            }
        } catch (Exception e) {
            // Fallback to default
        }
        return 0L;
    }

    private java.lang.reflect.Field findFieldByType(Class<?> clazz, String typeName) {
        try {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) {
                    return field;
                }
            }
        } catch (Exception e) {
            // Ignored
        }
        return null;
    }

}
