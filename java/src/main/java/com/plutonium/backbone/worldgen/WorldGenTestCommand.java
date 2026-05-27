package com.plutonium.backbone.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.client.PlutoniumBackend;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Mod.EventBusSubscriber(modid = "plutonium", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldGenTestCommand {

    private static final double PARITY_EPSILON = 1.0e-4;
    private static final int SAMPLE_COUNT = 10;
    private static final int HORIZONTAL_RADIUS = 64;
    private static final int GPU_COMPARE_DEFAULT_SAMPLES = 256;
    private static final int GPU_COMPARE_DEFAULT_RADIUS = 256;
    private static final int DENSITY_CELL_WIDTH = 4;
    private static final int DENSITY_CELL_HEIGHT = 8;
    private static final int DENSITY_GRID_X = 5;
    private static final int DENSITY_GRID_Z = 5;
    private static final int DENSITY_GRID_Y = 49;
    private static final int DENSITY_CELL_COUNT = DENSITY_GRID_X * DENSITY_GRID_Y * DENSITY_GRID_Z;
    private static final int GPU_CELL_COMPARE_DEFAULT_RADIUS = 0;

    private WorldGenTestCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("plutonium")
                        .then(Commands.literal("test_compiler")
                                .requires(src -> src.hasPermission(2))
                                .executes(WorldGenTestCommand::executeTestCompiler))
                        .then(Commands.literal("worldgen")
                                .then(Commands.literal("compare")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> executeGpuDensityCompare(
                                                ctx, GPU_COMPARE_DEFAULT_SAMPLES, GPU_COMPARE_DEFAULT_RADIUS))
                                        .then(Commands.argument("samples", IntegerArgumentType.integer(1, 2048))
                                                .executes(ctx -> executeGpuDensityCompare(
                                                        ctx,
                                                        IntegerArgumentType.getInteger(ctx, "samples"),
                                                        GPU_COMPARE_DEFAULT_RADIUS))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 4096))
                                                        .executes(ctx -> executeGpuDensityCompare(
                                                                ctx,
                                                                IntegerArgumentType.getInteger(ctx, "samples"),
                                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                                .then(Commands.literal("compare_cells")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> executeGpuDensityCellCompare(
                                                ctx, GPU_CELL_COMPARE_DEFAULT_RADIUS))
                                        .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 8))
                                                .executes(ctx -> executeGpuDensityCellCompare(
                                                        ctx,
                                                        IntegerArgumentType.getInteger(ctx, "chunkRadius"))))))
        );
    }

    private static int executeTestCompiler(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        RandomState randomState;
        try {
            randomState = reflectRandomState(level.getChunkSource());
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal(
                    "[Plutonium] Could not access randomState() via reflection: " + e.getMessage()));
            return 0;
        }

        NoiseRouter router = randomState.router();
        DensityFunction finalDensity = router.finalDensity();

        List<Instruction> program;
        try {
            program = DensityProgramSerializer.compile(finalDensity);
        } catch (UnsupportedOperationException e) {
            src.sendFailure(Component.literal(
                    "[Plutonium] Compile FAILED - unsupported node: " + e.getMessage()));
            return 0;
        } catch (Throwable t) {
            src.sendFailure(Component.literal(
                    "[Plutonium] Compile FAILED - " + t.getClass().getSimpleName()
                            + ": " + t.getMessage()));
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                "[Plutonium] Compiled finalDensity -> " + program.size()
                        + " instructions. Running " + SAMPLE_COUNT + " Java parity samples..."), false);

        Vec3 origin = src.getPosition();
        int originX = (int) Math.floor(origin.x);
        int originZ = (int) Math.floor(origin.z);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int passed = 0;
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            int x = originX + rng.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1);
            int z = originZ + rng.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1);
            int y = minY + rng.nextInt(Math.max(1, maxY - minY));

            DensityFunction.FunctionContext fctx = new DensityFunction.SinglePointContext(x, y, z);
            double vanilla = finalDensity.compute(fctx);
            double ours = DensityProgramEvaluator.compute(program, fctx);
            double delta = Math.abs(vanilla - ours);
            boolean ok = delta <= PARITY_EPSILON;
            if (ok) {
                passed++;
            }

            final int fx = x;
            final int fy = y;
            final int fz = z;
            final double fv = vanilla;
            final double fo = ours;
            final double fd = delta;
            final String tag = ok ? "PASSED" : "FAILED";
            src.sendSuccess(() -> Component.literal(String.format(
                    "[Plutonium] (%d,%d,%d) vanilla=%.6f ours=%.6f delta=%.3e %s",
                    fx, fy, fz, fv, fo, fd, tag)), false);
        }

        final int fPassed = passed;
        src.sendSuccess(() -> Component.literal(
                "[Plutonium] Java parity result: " + fPassed + "/" + SAMPLE_COUNT + " passed."), false);
        return passed;
    }

    private static int executeGpuDensityCompare(CommandContext<CommandSourceStack> ctx, int samples, int radius) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        RandomState randomState;
        try {
            randomState = reflectRandomState(level.getChunkSource());
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal(
                    "[Plutonium] Could not access randomState() via reflection: " + e.getMessage()));
            return 0;
        }

        DensityFunction finalDensity = randomState.router().finalDensity();
        List<Instruction> program;
        ByteBuffer ast;
        try {
            program = DensityProgramSerializer.compile(finalDensity);
            ast = BytecodeCompiler.packForGPU(program);
        } catch (UnsupportedOperationException e) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU density compare FAILED - unsupported node: " + e.getMessage()));
            return 0;
        } catch (Throwable t) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU density compare FAILED - " + t.getClass().getSimpleName()
                            + ": " + t.getMessage()));
            return 0;
        }

        long enginePtr = PlutoniumBackend.ensureBackendForWorldgen();
        if (enginePtr == 0L) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU density compare FAILED - native backend is not available."));
            return 0;
        }
        NativeInterface.nUploadAST(enginePtr, ast, ast.capacity());

        ByteBuffer coords = ByteBuffer.allocateDirect(samples * 3 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer gpuOut = ByteBuffer.allocateDirect(samples * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        double[] vanilla = new double[samples];
        int[] xs = new int[samples];
        int[] ys = new int[samples];
        int[] zs = new int[samples];

        Vec3 origin = src.getPosition();
        int originX = (int) Math.floor(origin.x);
        int originZ = (int) Math.floor(origin.z);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        long vanillaStartNs = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            int x = originX + rng.nextInt(-radius, radius + 1);
            int z = originZ + rng.nextInt(-radius, radius + 1);
            int y = minY + rng.nextInt(Math.max(1, maxY - minY));
            xs[i] = x;
            ys[i] = y;
            zs[i] = z;
            coords.putInt(i * 12, x);
            coords.putInt(i * 12 + 4, y);
            coords.putInt(i * 12 + 8, z);
            vanilla[i] = finalDensity.compute(new DensityFunction.SinglePointContext(x, y, z));
        }
        long vanillaNs = System.nanoTime() - vanillaStartNs;

        long gpuStartNs = System.nanoTime();
        boolean ok = NativeInterface.nEvaluateDensityPoints(enginePtr, coords, gpuOut, samples);
        long gpuNs = System.nanoTime() - gpuStartNs;
        if (!ok) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU density compare FAILED - native evaluator returned false."));
            return 0;
        }

        int passed = 0;
        int signMismatches = 0;
        double maxAbs = 0.0;
        double totalAbs = 0.0;
        int worstIndex = -1;
        for (int i = 0; i < samples; i++) {
            double gpu = gpuOut.getDouble(i * Double.BYTES);
            double delta = Math.abs(vanilla[i] - gpu);
            totalAbs += delta;
            if (delta > maxAbs) {
                maxAbs = delta;
                worstIndex = i;
            }
            if (delta <= PARITY_EPSILON) {
                passed++;
            }
            if ((vanilla[i] > 0.0) != (gpu > 0.0)) {
                signMismatches++;
            }
        }

        final int fPassed = passed;
        final int fSignMismatches = signMismatches;
        final int fWorst = worstIndex;
        final double fMaxAbs = maxAbs;
        final double fAvgAbs = totalAbs / Math.max(1, samples);
        final long fVanillaNs = vanillaNs;
        final long fGpuNs = gpuNs;
        final int fSamples = samples;
        final int fRadius = radius;
        final int fProgramSize = program.size();
        src.sendSuccess(() -> Component.literal(String.format(
                "[Plutonium] GPU density compare: samples=%d radius=%d instructions=%d pass=%d/%d signMismatch=%d avgDelta=%.3e maxDelta=%.3e vanillaMs=%.3f gpuMs=%.3f",
                fSamples, fRadius, fProgramSize, fPassed, fSamples, fSignMismatches,
                fAvgAbs, fMaxAbs, fVanillaNs / 1_000_000.0D, fGpuNs / 1_000_000.0D)), false);

        if (fWorst >= 0) {
            final double fVanilla = vanilla[fWorst];
            final double fGpu = gpuOut.getDouble(fWorst * Double.BYTES);
            src.sendSuccess(() -> Component.literal(String.format(
                    "[Plutonium] Worst sample: (%d,%d,%d) vanilla=%.12f gpu=%.12f delta=%.3e",
                    xs[fWorst], ys[fWorst], zs[fWorst], fVanilla, fGpu,
                    Math.abs(fVanilla - fGpu))), false);
        }
        return passed;
    }

    private static int executeGpuDensityCellCompare(CommandContext<CommandSourceStack> ctx, int chunkRadius) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        if (level.getMinBuildHeight() != -64 || level.getMaxBuildHeight() != 320) {
            src.sendFailure(Component.literal(String.format(
                    "[Plutonium] GPU cell compare currently targets Minecraft 1.20.1 overworld height -64..320; this level is %d..%d.",
                    level.getMinBuildHeight(), level.getMaxBuildHeight())));
            return 0;
        }

        RandomState randomState;
        try {
            randomState = reflectRandomState(level.getChunkSource());
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal(
                    "[Plutonium] Could not access randomState() via reflection: " + e.getMessage()));
            return 0;
        }

        DensityFunction finalDensity = randomState.router().finalDensity();
        List<Instruction> program;
        ByteBuffer ast;
        try {
            program = DensityProgramSerializer.compile(finalDensity);
            ast = BytecodeCompiler.packForGPU(program);
        } catch (UnsupportedOperationException e) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU cell compare FAILED - unsupported node: " + e.getMessage()));
            return 0;
        } catch (Throwable t) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU cell compare FAILED - " + t.getClass().getSimpleName()
                            + ": " + t.getMessage()));
            return 0;
        }

        long enginePtr = PlutoniumBackend.ensureBackendForWorldgen();
        if (enginePtr == 0L) {
            src.sendFailure(Component.literal(
                    "[Plutonium] GPU cell compare FAILED - native backend is not available."));
            return 0;
        }
        NativeInterface.nUploadAST(enginePtr, ast, ast.capacity());

        Vec3 origin = src.getPosition();
        int centerChunkX = Math.floorDiv((int) Math.floor(origin.x), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(origin.z), 16);
        int chunkCount = (chunkRadius * 2 + 1) * (chunkRadius * 2 + 1);
        int totalCells = chunkCount * DENSITY_CELL_COUNT;

        ByteBuffer gpuOut = ByteBuffer
                .allocateDirect(DENSITY_CELL_COUNT * Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        long vanillaNs = 0L;
        long gpuNs = 0L;
        int passed = 0;
        int signMismatches = 0;
        double totalAbs = 0.0D;
        double maxAbs = 0.0D;
        int worstX = 0;
        int worstY = 0;
        int worstZ = 0;
        int worstChunkX = 0;
        int worstChunkZ = 0;
        double worstVanilla = 0.0D;
        double worstGpu = 0.0D;

        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                long gpuStartNs = System.nanoTime();
                boolean ok = NativeInterface.nEvaluateChunkDensityCells(
                        enginePtr, chunkX, chunkZ, 0L, gpuOut, DENSITY_CELL_COUNT);
                gpuNs += System.nanoTime() - gpuStartNs;
                if (!ok) {
                    src.sendFailure(Component.literal(String.format(
                            "[Plutonium] GPU cell compare FAILED - native evaluator returned false for chunk %d,%d.",
                            chunkX, chunkZ)));
                    return 0;
                }

                long vanillaStartNs = System.nanoTime();
                for (int i = 0; i < DENSITY_CELL_COUNT; i++) {
                    int cellX = i % DENSITY_GRID_X;
                    int t = i / DENSITY_GRID_X;
                    int cellZ = t % DENSITY_GRID_Z;
                    int cellY = t / DENSITY_GRID_Z;

                    int worldX = chunkX * 16 + cellX * DENSITY_CELL_WIDTH;
                    int worldY = -64 + cellY * DENSITY_CELL_HEIGHT;
                    int worldZ = chunkZ * 16 + cellZ * DENSITY_CELL_WIDTH;

                    double vanilla = finalDensity.compute(
                            new DensityFunction.SinglePointContext(worldX, worldY, worldZ));
                    double gpu = gpuOut.getDouble(i * Double.BYTES);
                    double delta = Math.abs(vanilla - gpu);

                    if (delta <= PARITY_EPSILON) {
                        passed++;
                    }
                    if ((vanilla > 0.0D) != (gpu > 0.0D)) {
                        signMismatches++;
                    }
                    totalAbs += delta;
                    if (delta > maxAbs) {
                        maxAbs = delta;
                        worstX = worldX;
                        worstY = worldY;
                        worstZ = worldZ;
                        worstChunkX = chunkX;
                        worstChunkZ = chunkZ;
                        worstVanilla = vanilla;
                        worstGpu = gpu;
                    }
                }
                vanillaNs += System.nanoTime() - vanillaStartNs;
            }
        }

        final int fChunkRadius = chunkRadius;
        final int fChunkCount = chunkCount;
        final int fTotalCells = totalCells;
        final int fPassed = passed;
        final int fSignMismatches = signMismatches;
        final double fAvgAbs = totalAbs / Math.max(1, totalCells);
        final double fMaxAbs = maxAbs;
        final long fVanillaNs = vanillaNs;
        final long fGpuNs = gpuNs;
        final int fProgramSize = program.size();
        src.sendSuccess(() -> Component.literal(String.format(
                "[Plutonium] GPU density cells: radius=%d chunks=%d cells=%d instructions=%d pass=%d/%d signMismatch=%d avgDelta=%.3e maxDelta=%.3e vanillaMs=%.3f gpuMs=%.3f",
                fChunkRadius, fChunkCount, fTotalCells, fProgramSize, fPassed, fTotalCells,
                fSignMismatches, fAvgAbs, fMaxAbs,
                fVanillaNs / 1_000_000.0D, fGpuNs / 1_000_000.0D)), false);

        final int fWorstX = worstX;
        final int fWorstY = worstY;
        final int fWorstZ = worstZ;
        final int fWorstChunkX = worstChunkX;
        final int fWorstChunkZ = worstChunkZ;
        final double fWorstVanilla = worstVanilla;
        final double fWorstGpu = worstGpu;
        src.sendSuccess(() -> Component.literal(String.format(
                "[Plutonium] Worst cell: chunk=%d,%d pos=(%d,%d,%d) vanilla=%.12f gpu=%.12f delta=%.3e",
                fWorstChunkX, fWorstChunkZ, fWorstX, fWorstY, fWorstZ,
                fWorstVanilla, fWorstGpu, Math.abs(fWorstVanilla - fWorstGpu))), false);

        return passed;
    }

    private static RandomState reflectRandomState(ServerChunkCache cache) {
        Object chunkMap = cache.chunkMap;
        try {
            Method m = findMethod(chunkMap.getClass(), "randomState");
            m.setAccessible(true);
            return (RandomState) m.invoke(chunkMap);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("randomState() not found in ChunkMap hierarchy", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("randomState() inaccessible even after setAccessible", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("randomState() threw an exception", e.getCause());
        }
    }

    private static Method findMethod(Class<?> start, String name) throws NoSuchMethodException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // keep climbing
            }
        }
        throw new NoSuchMethodException("'" + name + "' not found in class hierarchy of " + start.getName());
    }
}
