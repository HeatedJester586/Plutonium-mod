package com.plutonium.backbone.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-local bridge between the CUDA density-cell evaluator and vanilla
 * NoiseChunk. Vanilla still performs interpolation, aquifers, fluids, material
 * rules, heightmaps, features, carvers, and structures.
 */
public final class GpuDensityCellCache {

    public static final int DENSITY_CELL_WIDTH = 4;
    public static final int DENSITY_CELL_HEIGHT = 8;
    public static final int DENSITY_GRID_X = 5;
    public static final int DENSITY_GRID_Z = 5;
    public static final int DENSITY_GRID_Y = 49;
    public static final int DENSITY_CELL_COUNT = DENSITY_GRID_X * DENSITY_GRID_Y * DENSITY_GRID_Z;

    private static final double MATCH_EPSILON = 1.0e-6D;
    private static final Logger LOGGER = LogManager.getLogger("PlutoniumWorldgen");
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();
    private static final Set<String> GPU_FILL_SIGNATURES = ConcurrentHashMap.newKeySet();
    private static final Set<String> CPU_FILL_SIGNATURES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger LOCK_LOGS = new AtomicInteger();

    private GpuDensityCellCache() {
    }

    public static void begin(int chunkX, int chunkZ, ByteBuffer densityCells) {
        ACTIVE.set(new Context(chunkX, chunkZ, densityCells, new IdentityHashMap<>()));
    }

    public static void end() {
        ACTIVE.remove();
    }

    public static boolean tryFillFromGpu(DensityFunction noiseFiller, double[] values,
                                         int cellStartBlockX, int cellStartBlockZ,
                                         int cellWidth, int cellHeight,
                                         int cellCountY, int cellNoiseMinY) {
        Context ctx = ACTIVE.get();
        if (ctx == null || !isUsable(values, cellWidth, cellHeight, cellCountY, cellNoiseMinY)) {
            return false;
        }

        Boolean local = ctx.localDecisions.get(noiseFiller);
        if (Boolean.TRUE.equals(local)) {
            return fillValues(ctx, values, cellStartBlockX, cellStartBlockZ, cellWidth);
        }
        if (Boolean.FALSE.equals(local)) {
            return false;
        }

        String signature = signature(noiseFiller);
        if (GPU_FILL_SIGNATURES.contains(signature)) {
            ctx.localDecisions.put(noiseFiller, Boolean.TRUE);
            return fillValues(ctx, values, cellStartBlockX, cellStartBlockZ, cellWidth);
        }
        if (CPU_FILL_SIGNATURES.contains(signature)) {
            ctx.localDecisions.put(noiseFiller, Boolean.FALSE);
        }
        return false;
    }

    public static void observeVanillaFill(DensityFunction noiseFiller, double[] values,
                                          int cellStartBlockX, int cellStartBlockZ,
                                          int cellWidth, int cellHeight,
                                          int cellCountY, int cellNoiseMinY) {
        Context ctx = ACTIVE.get();
        if (ctx == null || !isUsable(values, cellWidth, cellHeight, cellCountY, cellNoiseMinY)) {
            return;
        }

        String signature = signature(noiseFiller);
        if (GPU_FILL_SIGNATURES.contains(signature) || CPU_FILL_SIGNATURES.contains(signature)) {
            return;
        }

        CellCoord coord = cellCoord(ctx, cellStartBlockX, cellStartBlockZ, cellWidth);
        if (coord == null) {
            CPU_FILL_SIGNATURES.add(signature);
            ctx.localDecisions.put(noiseFiller, Boolean.FALSE);
            return;
        }

        double maxDelta = 0.0D;
        for (int y = 0; y < DENSITY_GRID_Y; y++) {
            double expected = ctx.cells.getDouble(cellIndex(coord.cellX, y, coord.cellZ) * Double.BYTES);
            maxDelta = Math.max(maxDelta, Math.abs(values[y] - expected));
            if (maxDelta > MATCH_EPSILON) {
                CPU_FILL_SIGNATURES.add(signature);
                ctx.localDecisions.put(noiseFiller, Boolean.FALSE);
                return;
            }
        }

        GPU_FILL_SIGNATURES.add(signature);
        ctx.localDecisions.put(noiseFiller, Boolean.TRUE);
        if (LOCK_LOGS.getAndIncrement() < 4) {
            LOGGER.info("[Plutonium] GPU density assist locked onto vanilla finalDensity interpolator: {}",
                    compactSignature(signature));
        }
    }

    private static boolean fillValues(Context ctx, double[] values,
                                      int cellStartBlockX, int cellStartBlockZ,
                                      int cellWidth) {
        CellCoord coord = cellCoord(ctx, cellStartBlockX, cellStartBlockZ, cellWidth);
        if (coord == null) {
            return false;
        }
        for (int y = 0; y < DENSITY_GRID_Y; y++) {
            values[y] = ctx.cells.getDouble(cellIndex(coord.cellX, y, coord.cellZ) * Double.BYTES);
        }
        return true;
    }

    private static boolean isUsable(double[] values, int cellWidth, int cellHeight,
                                    int cellCountY, int cellNoiseMinY) {
        return values != null
                && values.length >= DENSITY_GRID_Y
                && cellWidth == DENSITY_CELL_WIDTH
                && cellHeight == DENSITY_CELL_HEIGHT
                && cellCountY == DENSITY_GRID_Y - 1
                && cellNoiseMinY == -8;
    }

    private static CellCoord cellCoord(Context ctx, int cellStartBlockX,
                                       int cellStartBlockZ, int cellWidth) {
        int relX = cellStartBlockX - ctx.chunkX * 16;
        int relZ = cellStartBlockZ - ctx.chunkZ * 16;
        if (relX < 0 || relZ < 0 || relX > 16 || relZ > 16) {
            return null;
        }
        if (relX % cellWidth != 0 || relZ % cellWidth != 0) {
            return null;
        }
        int cellX = relX / cellWidth;
        int cellZ = relZ / cellWidth;
        if (cellX < 0 || cellX >= DENSITY_GRID_X || cellZ < 0 || cellZ >= DENSITY_GRID_Z) {
            return null;
        }
        return new CellCoord(cellX, cellZ);
    }

    private static int cellIndex(int cellX, int cellY, int cellZ) {
        return (cellY * DENSITY_GRID_Z + cellZ) * DENSITY_GRID_X + cellX;
    }

    private static String signature(DensityFunction function) {
        try {
            return function.getClass().getName() + "|" + function;
        } catch (Throwable t) {
            return function.getClass().getName();
        }
    }

    private static String compactSignature(String signature) {
        return signature.length() <= 220 ? signature : signature.substring(0, 220) + "...";
    }

    private record CellCoord(int cellX, int cellZ) {
    }

    private record Context(int chunkX, int chunkZ, ByteBuffer cells,
                           IdentityHashMap<DensityFunction, Boolean> localDecisions) {
    }
}
