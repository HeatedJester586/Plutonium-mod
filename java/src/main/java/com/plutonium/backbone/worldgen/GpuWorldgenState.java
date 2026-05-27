package com.plutonium.backbone.worldgen;

import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GpuWorldgenState {

    private static final ConcurrentHashMap<Long, InFlightGpuGeneration> IN_FLIGHT_GENERATIONS =
            new ConcurrentHashMap<>();
    private static final AtomicLong WORLDGEN_EPOCH = new AtomicLong();
    private static volatile boolean astUploaded = false;
    /**
     * Once a chunk's density function tree fails to compile (e.g. worldgen mod
     * adds a custom DF class our serializer doesn't recognize), every subsequent
     * chunk in this world skips the GPU path and runs vanilla {@code doFill}
     * directly. Avoids retrying the same failure for every chunk.
     */
    private static volatile boolean astCompatible = true;
    private static volatile long worldSeed = 0L;

    private GpuWorldgenState() {
    }

    public static long worldSeed() {
        return worldSeed;
    }

    public static void setWorldSeed(long seed) {
        worldSeed = seed;
    }

    public static long currentEpoch() {
        return WORLDGEN_EPOCH.get();
    }

    public static boolean isAstUploaded() {
        return astUploaded;
    }

    public static void markAstUploaded() {
        astUploaded = true;
    }

    public static boolean isAstCompatible() {
        return astCompatible;
    }

    public static void markAstIncompatible() {
        astCompatible = false;
    }

    public static InFlightGpuGeneration getInFlight(long chunkKey) {
        return IN_FLIGHT_GENERATIONS.get(chunkKey);
    }

    public static InFlightGpuGeneration putInFlightIfAbsent(long chunkKey, InFlightGpuGeneration generation) {
        return IN_FLIGHT_GENERATIONS.putIfAbsent(chunkKey, generation);
    }

    public static void removeInFlight(long chunkKey, InFlightGpuGeneration generation) {
        IN_FLIGHT_GENERATIONS.remove(chunkKey, generation);
    }

    public static void reset() {
        astUploaded = false;
        astCompatible = true;
        worldSeed = 0L;
        WORLDGEN_EPOCH.incrementAndGet();
        IN_FLIGHT_GENERATIONS.forEach((chunkKey, generation) -> generation.future().complete(generation.chunk()));
        IN_FLIGHT_GENERATIONS.clear();
    }

    public record InFlightGpuGeneration(ChunkAccess chunk, CompletableFuture<ChunkAccess> future) {
    }
}
