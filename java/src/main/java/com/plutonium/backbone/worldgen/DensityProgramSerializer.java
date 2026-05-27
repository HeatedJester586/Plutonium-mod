package com.plutonium.backbone.worldgen;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Walks a {@link DensityFunction} tree in post-order via reflection and emits a
 * flat {@link Instruction} list. The last instruction is the root.
 *
 * Package-private Minecraft node types are detected by simple class name and
 * accessed through record accessors reflectively.
 *
 * JNI invariant: Instruction.extraData must only contain Plutonium-owned records
 * whose components are primitives or primitive arrays. No Minecraft runtime
 * objects may cross this boundary.
 */
public final class DensityProgramSerializer {

    private final List<Instruction> instructions = new ArrayList<>();
    private final IdentityHashMap<DensityFunction, Integer> compiledNodes = new IdentityHashMap<>();

    private DensityProgramSerializer() {}

    public static List<Instruction> compile(DensityFunction root) {
        DensityProgramSerializer s = new DensityProgramSerializer();
        s.compileNode(root);
        return s.instructions;
    }

    @SuppressWarnings("unchecked")
    private int compileNode(DensityFunction df) {
        Integer cached = compiledNodes.get(df);
        if (cached != null) {
            return cached;
        }

        String name = df.getClass().getSimpleName();

        switch (name) {
            case "HolderHolder" -> {
                Holder<DensityFunction> holder = (Holder<DensityFunction>) invoke(df, "function");
                int child = compileNode(holder.value());
                compiledNodes.put(df, child);
                return child;
            }

            case "Constant" -> {
                double value = (double) invoke(df, "value");
                return emit(df, new Instruction(Opcode.CONSTANT, -1, -1, value, null));
            }

            case "BlendAlpha" -> {
                return emit(df, new Instruction(Opcode.BLEND_ALPHA, -1, -1, 0.0, null));
            }
            case "BlendOffset" -> {
                return emit(df, new Instruction(Opcode.BLEND_OFFSET, -1, -1, 0.0, null));
            }
            case "BeardifierMarker" -> {
                return emit(df, new Instruction(Opcode.BEARDIFIER_MARKER, -1, -1, 0.0, null));
            }

            case "Marker" -> {
                DensityFunction wrapped = (DensityFunction) invoke(df, "wrapped");
                int child = compileNode(wrapped);
                return emit(df, new Instruction(Opcode.MARKER, child, -1, 0.0, null));
            }

            case "Ap2" -> {
                DensityFunction arg1 = (DensityFunction) invoke(df, "argument1");
                DensityFunction arg2 = (DensityFunction) invoke(df, "argument2");
                Object type = invoke(df, "type");
                int l = compileNode(arg1);
                int r = compileNode(arg2);
                Opcode op = switch (((Enum<?>) type).name()) {
                    case "ADD" -> Opcode.ADD;
                    case "MUL" -> Opcode.MUL;
                    case "MIN" -> Opcode.MIN;
                    case "MAX" -> Opcode.MAX;
                    default -> throw new UnsupportedOperationException(
                            "Unknown Ap2 type: " + ((Enum<?>) type).name());
                };
                return emit(df, new Instruction(op, l, r, 0.0, null));
            }

            case "Mapped" -> {
                DensityFunction input = (DensityFunction) invoke(df, "input");
                Object type = invoke(df, "type");
                int child = compileNode(input);
                Opcode op = switch (((Enum<?>) type).name()) {
                    case "ABS" -> Opcode.ABS;
                    case "SQUARE" -> Opcode.SQUARE;
                    case "CUBE" -> Opcode.CUBE;
                    case "HALF_NEGATIVE" -> Opcode.HALF_NEGATIVE;
                    case "QUARTER_NEGATIVE" -> Opcode.QUARTER_NEGATIVE;
                    case "SQUEEZE" -> Opcode.SQUEEZE;
                    default -> throw new UnsupportedOperationException(
                            "Unknown Mapped type: " + ((Enum<?>) type).name());
                };
                return emit(df, new Instruction(op, child, -1, 0.0, null));
            }

            case "Clamp" -> {
                DensityFunction input = (DensityFunction) invoke(df, "input");
                double min = (double) invoke(df, "minValue");
                double max = (double) invoke(df, "maxValue");
                int child = compileNode(input);
                return emit(df, new Instruction(Opcode.CLAMP, child, -1, 0.0, new double[]{min, max}));
            }

            case "Noise" -> {
                DensityFunction.NoiseHolder noiseHolder =
                        (DensityFunction.NoiseHolder) invoke(df, "noise");
                double xzScale = (double) invoke(df, "xzScale");
                double yScale = (double) invoke(df, "yScale");
                return emit(df, new Instruction(
                        Opcode.NOISE, -1, -1, 0.0, noisePayload(noiseHolder, xzScale, yScale)));
            }
            case "Shift" -> {
                DensityFunction.NoiseHolder h = (DensityFunction.NoiseHolder) invoke(df, "offsetNoise");
                return emit(df, new Instruction(Opcode.SHIFT, -1, -1, 0.0, noisePayload(h, 0.0, 0.0)));
            }
            case "ShiftA" -> {
                DensityFunction.NoiseHolder h = (DensityFunction.NoiseHolder) invoke(df, "offsetNoise");
                return emit(df, new Instruction(Opcode.SHIFT_A, -1, -1, 0.0, noisePayload(h, 0.0, 0.0)));
            }
            case "ShiftB" -> {
                DensityFunction.NoiseHolder h = (DensityFunction.NoiseHolder) invoke(df, "offsetNoise");
                return emit(df, new Instruction(Opcode.SHIFT_B, -1, -1, 0.0, noisePayload(h, 0.0, 0.0)));
            }

            case "ShiftedNoise" -> {
                DensityFunction sx = (DensityFunction) invoke(df, "shiftX");
                DensityFunction sy = (DensityFunction) invoke(df, "shiftY");
                DensityFunction sz = (DensityFunction) invoke(df, "shiftZ");
                double xzScale = (double) invoke(df, "xzScale");
                double yScale = (double) invoke(df, "yScale");
                DensityFunction.NoiseHolder h = (DensityFunction.NoiseHolder) invoke(df, "noise");
                int ix = compileNode(sx);
                int iy = compileNode(sy);
                int iz = compileNode(sz);
                NoisePayload base = noisePayload(h, xzScale, yScale);
                ShiftedNoisePayload payload = new ShiftedNoisePayload(
                        base.firstOctave(),
                        base.amplitudes(),
                        base.xzScale(),
                        base.yScale(),
                        base.valueFactor(),
                        base.firstOctaves(),
                        base.secondOctaves(),
                        ix,
                        iy,
                        iz);
                return emit(df, new Instruction(Opcode.SHIFTED_NOISE, -1, -1, 0.0, payload));
            }

            case "BlendedNoise" -> {
                // Pull all three PerlinNoise instances + scalar params via reflection.
                // The xzMultiplier / yMultiplier fields are pre-baked 684.412 * scale,
                // so we capture them as-is and the GPU avoids recomputing.
                Object minLimit = getField(df, "minLimitNoise");
                Object maxLimit = getField(df, "maxLimitNoise");
                Object main     = getField(df, "mainNoise");
                double xzMul     = (double) getField(df, "xzMultiplier");
                double yMul      = (double) getField(df, "yMultiplier");
                double xzFactor  = (double) getField(df, "xzFactor");
                double yFactor   = (double) getField(df, "yFactor");
                double smearMult = (double) getField(df, "smearScaleMultiplier");
                BlendedNoisePayload payload = new BlendedNoisePayload(
                        xzMul, yMul, xzFactor, yFactor, smearMult,
                        extractAllOctaves(minLimit),
                        extractAllOctaves(maxLimit),
                        extractAllOctaves(main));
                return emit(df, new Instruction(Opcode.BLENDED_NOISE, -1, -1, 0.0, payload));
            }

            case "MulOrAdd" -> {
                DensityFunction input = (DensityFunction) invoke(df, "input");
                Object specificType = invoke(df, "specificType");
                double argument = (double) invoke(df, "argument");
                int child = compileNode(input);
                int typeId = "MUL".equals(((Enum<?>) specificType).name()) ? 0 : 1;
                return emit(df, new Instruction(Opcode.MUL_OR_ADD, child, typeId, argument, null));
            }

            case "WeirdScaledSampler" -> {
                DensityFunction input = (DensityFunction) invoke(df, "input");
                DensityFunction.NoiseHolder noise = (DensityFunction.NoiseHolder) invoke(df, "noise");
                Object rarityValueMapper = invoke(df, "rarityValueMapper");
                int child = compileNode(input);
                int mapperId = "TYPE1".equals(((Enum<?>) rarityValueMapper).name()) ? 1 : 2;
                NoisePayload base = noisePayload(noise, 0.0, 0.0);
                WeirdScaledSamplerPayload payload = new WeirdScaledSamplerPayload(
                        mapperId,
                        base.firstOctave(),
                        base.amplitudes(),
                        base.valueFactor(),
                        base.firstOctaves(),
                        base.secondOctaves());
                return emit(df, new Instruction(Opcode.WEIRD_SCALED_SAMPLER, child, -1, 0.0, payload));
            }

            case "BlendDensity" -> {
                DensityFunction input = (DensityFunction) invoke(df, "input");
                int child = compileNode(input);
                return emit(df, new Instruction(Opcode.BLEND_DENSITY, child, -1, 0.0, null));
            }

            case "RangeChoice" -> {
                DensityFunction input = (DensityFunction) invoke(df, "input");
                DensityFunction whenInRange = (DensityFunction) invoke(df, "whenInRange");
                DensityFunction whenOutOfRange = (DensityFunction) invoke(df, "whenOutOfRange");
                double minInclusive = (double) invoke(df, "minInclusive");
                double maxExclusive = (double) invoke(df, "maxExclusive");
                int inputIdx = compileNode(input);
                int inRangeIdx = compileNode(whenInRange);
                int outRangeIdx = compileNode(whenOutOfRange);
                RangeChoicePayload payload = new RangeChoicePayload(
                        inRangeIdx, outRangeIdx, minInclusive, maxExclusive);
                return emit(df, new Instruction(Opcode.RANGE_CHOICE, inputIdx, -1, 0.0, payload));
            }

            case "YClampedGradient" -> {
                int fromY = (int) invoke(df, "fromY");
                int toY = (int) invoke(df, "toY");
                double fromValue = (double) invoke(df, "fromValue");
                double toValue = (double) invoke(df, "toValue");
                YClampedGradientPayload payload =
                        new YClampedGradientPayload(fromY, toY, fromValue, toValue);
                return emit(df, new Instruction(Opcode.Y_CLAMPED_GRADIENT, -1, -1, 0.0, payload));
            }

            case "Spline" -> {
                CubicSpline<?, ?> root = (CubicSpline<?, ?>) invoke(df, "spline");
                FlatSpline flat = flattenSpline(root);
                return emit(df, new Instruction(Opcode.SPLINE, -1, -1, 0.0, flat));
            }

            default -> throw new UnsupportedOperationException(
                    "DensityProgramSerializer: unsupported node " + df.getClass().getName());
        }
    }

    @SuppressWarnings("unchecked")
    private FlatSpline flattenSpline(CubicSpline<?, ?> spline) {
        if (spline instanceof CubicSpline.Constant<?, ?> c) {
            return new FlatSpline.Constant(c.value());
        }
        if (spline instanceof CubicSpline.Multipoint<?, ?> mp) {
            Object coord = mp.coordinate();
            Holder<DensityFunction> functionHolder = (Holder<DensityFunction>) invoke(coord, "function");
            int regIdx = compileNode(functionHolder.value());

            List<? extends CubicSpline<?, ?>> children = mp.values();
            FlatSpline[] flatChildren = new FlatSpline[children.size()];
            for (int i = 0; i < children.size(); i++) {
                flatChildren[i] = flattenSpline(children.get(i));
            }

            return new FlatSpline.Multipoint(
                    regIdx,
                    mp.locations().clone(),
                    flatChildren,
                    mp.derivatives().clone());
        }
        throw new IllegalStateException("Unknown CubicSpline subtype: " + spline.getClass().getName());
    }

    private static NoisePayload noisePayload(
            DensityFunction.NoiseHolder noiseHolder,
            double xzScale,
            double yScale) {
        NormalNoise.NoiseParameters params = noiseHolder.noiseData().value();
        NormalNoise normalNoise = noiseHolder.noise();
        if (normalNoise == null) {
            throw new IllegalStateException("NoiseHolder has no initialized NormalNoise instance");
        }
        double[] amplitudes = copyAmplitudes(params.amplitudes());
        Object first = getField(normalNoise, "first");
        Object second = getField(normalNoise, "second");
        return new NoisePayload(
                params.firstOctave(),
                amplitudes,
                xzScale,
                yScale,
                (double) getField(normalNoise, "valueFactor"),
                octavePayloads(first, amplitudes.length),
                octavePayloads(second, amplitudes.length));
    }

    /** Extract every ImprovedNoise from a PerlinNoise's noiseLevels array, preserving array length. */
    private static OctaveNoisePayload[] extractAllOctaves(Object perlinNoise) {
        Object[] noiseLevels = (Object[]) getField(perlinNoise, "noiseLevels");
        OctaveNoisePayload[] out = new OctaveNoisePayload[noiseLevels.length];
        for (int i = 0; i < out.length; i++) {
            Object improved = noiseLevels[i];
            if (improved == null) {
                out[i] = OctaveNoisePayload.empty();
                continue;
            }
            byte[] permutations = ((byte[]) getField(improved, "p")).clone();
            if (permutations.length != 256) {
                throw new IllegalStateException("ImprovedNoise permutation length was " + permutations.length);
            }
            out[i] = new OctaveNoisePayload(
                    true,
                    (double) getField(improved, "xo"),
                    (double) getField(improved, "yo"),
                    (double) getField(improved, "zo"),
                    permutations);
        }
        return out;
    }

    private static OctaveNoisePayload[] octavePayloads(Object perlinNoise, int expectedCount) {
        Object[] noiseLevels = (Object[]) getField(perlinNoise, "noiseLevels");
        OctaveNoisePayload[] out = new OctaveNoisePayload[expectedCount];
        for (int i = 0; i < out.length; i++) {
            Object improved = i < noiseLevels.length ? noiseLevels[i] : null;
            if (improved == null) {
                out[i] = OctaveNoisePayload.empty();
                continue;
            }

            byte[] permutations = ((byte[]) getField(improved, "p")).clone();
            if (permutations.length != 256) {
                throw new IllegalStateException("ImprovedNoise permutation length was " + permutations.length);
            }

            out[i] = new OctaveNoisePayload(
                    true,
                    (double) getField(improved, "xo"),
                    (double) getField(improved, "yo"),
                    (double) getField(improved, "zo"),
                    permutations);
        }
        return out;
    }

    private static double[] copyAmplitudes(DoubleList amplitudes) {
        double[] out = new double[amplitudes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = amplitudes.getDouble(i);
        }
        return out;
    }

    private static Object invoke(Object obj, String methodName) {
        try {
            Method m = findMethod(obj.getClass(), methodName);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Reflection: method '" + methodName + "' not found on "
                            + obj.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Reflection: cannot access '" + methodName + "' on "
                            + obj.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(
                    "Reflection: '" + methodName + "' threw on "
                            + obj.getClass().getName(), e.getCause());
        }
    }

    private static Object getField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(
                    "Reflection: field '" + fieldName + "' not found on "
                            + obj.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Reflection: cannot access field '" + fieldName + "' on "
                            + obj.getClass().getName(), e);
        }
    }

    private static Method findMethod(Class<?> start, String name) throws NoSuchMethodException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // climb
            }
        }
        throw new NoSuchMethodException(
                "'" + name + "' not found in class hierarchy of " + start.getName());
    }

    private static Field findField(Class<?> start, String name) throws NoSuchFieldException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // climb
            }
        }
        throw new NoSuchFieldException(
                "'" + name + "' not found in class hierarchy of " + start.getName());
    }

    private int emit(Instruction ins) {
        instructions.add(ins);
        return instructions.size() - 1;
    }

    private int emit(DensityFunction source, Instruction ins) {
        int index = emit(ins);
        compiledNodes.put(source, index);
        return index;
    }

    public record NoisePayload(
            int firstOctave,
            double[] amplitudes,
            double xzScale,
            double yScale,
            double valueFactor,
            OctaveNoisePayload[] firstOctaves,
            OctaveNoisePayload[] secondOctaves) {
    }

    public record ShiftedNoisePayload(
            int firstOctave,
            double[] amplitudes,
            double xzScale,
            double yScale,
            double valueFactor,
            OctaveNoisePayload[] firstOctaves,
            OctaveNoisePayload[] secondOctaves,
            int shiftXIdx,
            int shiftYIdx,
            int shiftZIdx) {
    }

    public record OctaveNoisePayload(
            boolean present,
            double xo,
            double yo,
            double zo,
            byte[] permutations) {

        private static final byte[] EMPTY_PERMUTATIONS = new byte[256];

        static OctaveNoisePayload empty() {
            return new OctaveNoisePayload(false, 0.0, 0.0, 0.0, EMPTY_PERMUTATIONS);
        }
    }

    public record RangeChoicePayload(
            int inRangeIdx,
            int outOfRangeIdx,
            double minInclusive,
            double maxExclusive) {
    }

    public record YClampedGradientPayload(
            int fromY,
            int toY,
            double fromValue,
            double toValue) {
    }

    public record WeirdScaledSamplerPayload(
            int mapperId,
            int firstOctave,
            double[] amplitudes,
            double valueFactor,
            OctaveNoisePayload[] firstOctaves,
            OctaveNoisePayload[] secondOctaves) {
    }

    /**
     * Full seeded state of a {@code BlendedNoise} node, ready for GPU evaluation.
     * xzMultiplier/yMultiplier are the already-baked {@code 684.412 * scale} factors
     * vanilla pre-computes in its constructor, so the GPU doesn't redo that math.
     * Octave arrays are NOT reversed — the GPU walks them with {@code len - 1 - i}
     * to match vanilla {@code PerlinNoise.getOctaveNoise(i)} semantics.
     */
    public record BlendedNoisePayload(
            double xzMultiplier,
            double yMultiplier,
            double xzFactor,
            double yFactor,
            double smearScaleMultiplier,
            OctaveNoisePayload[] minLimitOctaves,
            OctaveNoisePayload[] maxLimitOctaves,
            OctaveNoisePayload[] mainOctaves) {
    }

    public sealed interface FlatSpline permits FlatSpline.Constant, FlatSpline.Multipoint {

        record Constant(float value) implements FlatSpline {}

        record Multipoint(
                int regIdx,
                float[] locations,
                FlatSpline[] values,
                float[] derivatives) implements FlatSpline {}
    }
}
