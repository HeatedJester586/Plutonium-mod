package com.plutonium.backbone.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.List;

/**
 * Linear interpreter for the non-noise bytecode produced by {@link DensityProgramSerializer}.
 *
 * Phase 2 sanitizes noise payloads into primitive parameters for native execution. Java no
 * longer keeps the NormalNoise instances needed for exact CPU parity, so noise-bearing opcodes
 * intentionally throw here instead of silently producing non-vanilla values.
 */
public final class DensityProgramEvaluator {

    private DensityProgramEvaluator() {}

    public static double compute(List<Instruction> program, DensityFunction.FunctionContext ctx) {
        final int n = program.size();
        if (n == 0) {
            throw new IllegalArgumentException("Empty program");
        }
        final double[] regs = new double[n];

        for (int i = 0; i < n; i++) {
            Instruction ins = program.get(i);
            switch (ins.op()) {
                case CONSTANT -> regs[i] = ins.value();
                case BLEND_ALPHA -> regs[i] = 1.0;
                case BLEND_OFFSET -> regs[i] = 0.0;
                case BEARDIFIER_MARKER -> regs[i] = 0.0;

                case ADD -> regs[i] = regs[ins.arg1()] + regs[ins.arg2()];
                case MUL -> regs[i] = regs[ins.arg1()] * regs[ins.arg2()];
                case MIN -> regs[i] = Math.min(regs[ins.arg1()], regs[ins.arg2()]);
                case MAX -> regs[i] = Math.max(regs[ins.arg1()], regs[ins.arg2()]);

                case ABS -> regs[i] = Math.abs(regs[ins.arg1()]);
                case SQUARE -> {
                    double v = regs[ins.arg1()];
                    regs[i] = v * v;
                }
                case CUBE -> {
                    double v = regs[ins.arg1()];
                    regs[i] = v * v * v;
                }
                case HALF_NEGATIVE -> {
                    double v = regs[ins.arg1()];
                    regs[i] = v > 0.0 ? v : v * 0.5;
                }
                case QUARTER_NEGATIVE -> {
                    double v = regs[ins.arg1()];
                    regs[i] = v > 0.0 ? v : v * 0.25;
                }
                case SQUEEZE -> {
                    double v = Mth.clamp(regs[ins.arg1()], -1.0, 1.0);
                    regs[i] = v / 2.0 - (v * v * v) / 24.0;
                }

                case CLAMP -> {
                    double[] mm = (double[]) ins.extraData();
                    regs[i] = Mth.clamp(regs[ins.arg1()], mm[0], mm[1]);
                }

                case MARKER -> regs[i] = regs[ins.arg1()];

                case BLEND_DENSITY -> regs[i] = ctx.getBlender().blendDensity(ctx, regs[ins.arg1()]);

                case RANGE_CHOICE -> {
                    DensityProgramSerializer.RangeChoicePayload p =
                            (DensityProgramSerializer.RangeChoicePayload) ins.extraData();
                    double d = regs[ins.arg1()];
                    regs[i] = (d >= p.minInclusive() && d < p.maxExclusive())
                            ? regs[p.inRangeIdx()]
                            : regs[p.outOfRangeIdx()];
                }

                case Y_CLAMPED_GRADIENT -> {
                    DensityProgramSerializer.YClampedGradientPayload p =
                            (DensityProgramSerializer.YClampedGradientPayload) ins.extraData();
                    regs[i] = Mth.clampedMap(
                            (double) ctx.blockY(),
                            (double) p.fromY(), (double) p.toY(),
                            p.fromValue(), p.toValue());
                }

                case MUL_OR_ADD -> regs[i] = ins.arg2() == 0
                        ? regs[ins.arg1()] * ins.value()
                        : regs[ins.arg1()] + ins.value();

                case SPLINE -> {
                    DensityProgramSerializer.FlatSpline root =
                            (DensityProgramSerializer.FlatSpline) ins.extraData();
                    regs[i] = evalFlatSpline(root, regs);
                }

                case NOISE,
                     SHIFT,
                     SHIFT_A,
                     SHIFT_B,
                     SHIFTED_NOISE,
                     WEIRD_SCALED_SAMPLER,
                     BLENDED_NOISE -> throw new UnsupportedOperationException(
                        "DensityProgramEvaluator cannot execute sanitized native-only opcode " + ins.op());
            }
        }

        return regs[n - 1];
    }

    private static double evalFlatSpline(DensityProgramSerializer.FlatSpline node, double[] regs) {
        if (node instanceof DensityProgramSerializer.FlatSpline.Constant c) {
            return c.value();
        }
        DensityProgramSerializer.FlatSpline.Multipoint mp =
                (DensityProgramSerializer.FlatSpline.Multipoint) node;
        return evalMultipoint(mp, regs);
    }

    private static float evalMultipoint(
            DensityProgramSerializer.FlatSpline.Multipoint mp, double[] regs) {

        float x = (float) regs[mp.regIdx()];
        float[] locations = mp.locations();
        float[] derivatives = mp.derivatives();
        DensityProgramSerializer.FlatSpline[] values = mp.values();

        int last = locations.length - 1;
        int idx = findIntervalStart(locations, x);

        if (idx < 0) {
            return (float) evalFlatSpline(values[0], regs)
                    + derivatives[0] * (x - locations[0]);
        }
        if (idx == last) {
            return (float) evalFlatSpline(values[last], regs)
                    + derivatives[last] * (x - locations[last]);
        }

        float xLow = locations[idx];
        float xHigh = locations[idx + 1];
        float k = (x - xLow) / (xHigh - xLow);

        float yLow = (float) evalFlatSpline(values[idx], regs);
        float yHigh = (float) evalFlatSpline(values[idx + 1], regs);

        float dLow = derivatives[idx];
        float dHigh = derivatives[idx + 1];

        float a = dLow * (xHigh - xLow) - (yHigh - yLow);
        float b = -dHigh * (xHigh - xLow) + (yHigh - yLow);

        return Mth.lerp(k, yLow, yHigh) + k * (1.0f - k) * Mth.lerp(k, a, b);
    }

    private static int findIntervalStart(float[] xs, float x) {
        return Mth.binarySearch(0, xs.length, idx -> x < xs[idx]) - 1;
    }
}
