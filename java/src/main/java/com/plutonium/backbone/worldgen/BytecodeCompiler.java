package com.plutonium.backbone.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Packs sanitized density bytecode into a direct, little-endian binary buffer for JNI.
 *
 * Buffer layout:
 *   Header, fixed instruction table, then variable-size data pool.
 *
 * All data offsets stored in instructions are byte offsets from the start of the data pool,
 * not from the start of the full buffer. The header provides the absolute data-pool start.
 */
public final class BytecodeCompiler {

    public static final int MAGIC = 0x504C544E; // 'PLTN'
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 24;
    public static final int INSTRUCTION_BYTES = 24;
    public static final int NO_DATA = -1;
    public static final int OCTAVE_NOISE_BYTES = 288;

    private BytecodeCompiler() {}

    public static ByteBuffer packForGPU(List<Instruction> instructions) {
        if (instructions == null) {
            throw new NullPointerException("instructions");
        }

        DataPool pool = new DataPool();
        PackedInstruction[] fixed = new PackedInstruction[instructions.size()];

        for (int i = 0; i < instructions.size(); i++) {
            Instruction ins = instructions.get(i);
            int arg1 = ins.arg1();
            int arg2 = ins.arg2();
            double value = ins.value();
            int dataOffset = writePayload(ins, pool);
            fixed[i] = new PackedInstruction(ins.op().ordinal(), arg1, arg2, dataOffset, value);
        }

        byte[] data = pool.toByteArray();
        int instructionTableBytes = instructions.size() * INSTRUCTION_BYTES;
        int dataPoolOffset = HEADER_BYTES + instructionTableBytes;
        int totalBytes = dataPoolOffset + data.length;

        ByteBuffer out = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC);
        out.putInt(VERSION);
        out.putInt(instructions.size());
        out.putInt(INSTRUCTION_BYTES);
        out.putInt(dataPoolOffset);
        out.putInt(data.length);

        for (PackedInstruction pi : fixed) {
            out.putInt(pi.opcodeId());
            out.putInt(pi.arg1());
            out.putInt(pi.arg2());
            out.putInt(pi.dataOffset());
            out.putDouble(pi.value());
        }

        out.put(data);
        out.flip();
        return out;
    }

    private static int writePayload(Instruction ins, DataPool pool) {
        return switch (ins.op()) {
            case CLAMP -> writeClamp((double[]) ins.extraData(), pool);
            case NOISE, SHIFT, SHIFT_A, SHIFT_B ->
                    writeNoise((DensityProgramSerializer.NoisePayload) ins.extraData(), pool);
            case SHIFTED_NOISE ->
                    writeShiftedNoise((DensityProgramSerializer.ShiftedNoisePayload) ins.extraData(), pool);
            case RANGE_CHOICE ->
                    writeRangeChoice((DensityProgramSerializer.RangeChoicePayload) ins.extraData(), pool);
            case Y_CLAMPED_GRADIENT ->
                    writeYClampedGradient((DensityProgramSerializer.YClampedGradientPayload) ins.extraData(), pool);
            case SPLINE ->
                    writeSpline((DensityProgramSerializer.FlatSpline) ins.extraData(), pool);
            case WEIRD_SCALED_SAMPLER ->
                    writeWeirdScaledSampler((DensityProgramSerializer.WeirdScaledSamplerPayload) ins.extraData(), pool);
            case BLENDED_NOISE ->
                    writeBlendedNoise((DensityProgramSerializer.BlendedNoisePayload) ins.extraData(), pool);
            default -> NO_DATA;
        };
    }

    private static int writeClamp(double[] bounds, DataPool pool) {
        int off = pool.beginPayload(8);
        pool.putDouble(bounds[0]);
        pool.putDouble(bounds[1]);
        return off;
    }

    private static int writeNoise(DensityProgramSerializer.NoisePayload p, DataPool pool) {
        int off = pool.beginPayload(8);
        writeNormalNoisePayload(p, pool);
        return off;
    }

    private static void writeNormalNoisePayload(DensityProgramSerializer.NoisePayload p, DataPool pool) {
        pool.putInt(p.firstOctave());
        pool.putInt(p.amplitudes().length);
        for (double amplitude : p.amplitudes()) {
            pool.putDouble(amplitude);
        }
        pool.putDouble(p.xzScale());
        pool.putDouble(p.yScale());
        pool.putDouble(p.valueFactor());
        writeOctaves(p.firstOctaves(), p.amplitudes().length, pool);
        writeOctaves(p.secondOctaves(), p.amplitudes().length, pool);
    }

    private static int writeShiftedNoise(DensityProgramSerializer.ShiftedNoisePayload p, DataPool pool) {
        int off = pool.beginPayload(8);
        writeNormalNoisePayload(new DensityProgramSerializer.NoisePayload(
                p.firstOctave(),
                p.amplitudes(),
                p.xzScale(),
                p.yScale(),
                p.valueFactor(),
                p.firstOctaves(),
                p.secondOctaves()), pool);
        pool.putInt(p.shiftXIdx());
        pool.putInt(p.shiftYIdx());
        pool.putInt(p.shiftZIdx());
        pool.putInt(0); // reserved / 8-byte alignment padding
        return off;
    }

    private static void writeOctaves(
            DensityProgramSerializer.OctaveNoisePayload[] octaves,
            int expectedCount,
            DataPool pool) {
        for (int i = 0; i < expectedCount; i++) {
            DensityProgramSerializer.OctaveNoisePayload octave =
                    i < octaves.length ? octaves[i] : DensityProgramSerializer.OctaveNoisePayload.empty();
            byte[] p = octave.permutations();
            if (p.length != 256) {
                throw new IllegalArgumentException("ImprovedNoise permutation table must be 256 bytes");
            }
            pool.putInt(octave.present() ? 1 : 0);
            pool.putInt(0);
            pool.putDouble(octave.xo());
            pool.putDouble(octave.yo());
            pool.putDouble(octave.zo());
            pool.putBytes(p);
        }
    }

    private static int writeRangeChoice(DensityProgramSerializer.RangeChoicePayload p, DataPool pool) {
        int off = pool.beginPayload(8);
        pool.putInt(p.inRangeIdx());
        pool.putInt(p.outOfRangeIdx());
        pool.putDouble(p.minInclusive());
        pool.putDouble(p.maxExclusive());
        return off;
    }

    private static int writeYClampedGradient(DensityProgramSerializer.YClampedGradientPayload p, DataPool pool) {
        int off = pool.beginPayload(8);
        pool.putInt(p.fromY());
        pool.putInt(p.toY());
        pool.putDouble(p.fromValue());
        pool.putDouble(p.toValue());
        return off;
    }

    private static int writeWeirdScaledSampler(
            DensityProgramSerializer.WeirdScaledSamplerPayload p,
            DataPool pool) {
        int off = pool.beginPayload(8);
        pool.putInt(p.mapperId());
        pool.putInt(0); // reserved / 8-byte alignment padding
        writeNormalNoisePayload(new DensityProgramSerializer.NoisePayload(
                p.firstOctave(),
                p.amplitudes(),
                0.0,
                0.0,
                p.valueFactor(),
                p.firstOctaves(),
                p.secondOctaves()), pool);
        return off;
    }

    /**
     * Pack a {@link DensityProgramSerializer.BlendedNoisePayload} for GPU consumption.
     * Layout:
     *   double xzMul, yMul, xzFactor, yFactor, smearMult
     *   int32  minCount, maxCount, mainCount, _pad
     *   OctaveNoisePayload[minCount]   (each 288B: present|_|xo|yo|zo|perm[256])
     *   OctaveNoisePayload[maxCount]
     *   OctaveNoisePayload[mainCount]
     */
    private static int writeBlendedNoise(DensityProgramSerializer.BlendedNoisePayload p, DataPool pool) {
        int off = pool.beginPayload(8);
        pool.putDouble(p.xzMultiplier());
        pool.putDouble(p.yMultiplier());
        pool.putDouble(p.xzFactor());
        pool.putDouble(p.yFactor());
        pool.putDouble(p.smearScaleMultiplier());
        pool.putInt(p.minLimitOctaves().length);
        pool.putInt(p.maxLimitOctaves().length);
        pool.putInt(p.mainOctaves().length);
        pool.putInt(0); // reserved / 8-byte alignment
        writeOctaves(p.minLimitOctaves(), p.minLimitOctaves().length, pool);
        writeOctaves(p.maxLimitOctaves(), p.maxLimitOctaves().length, pool);
        writeOctaves(p.mainOctaves(),     p.mainOctaves().length,     pool);
        return off;
    }

    private static int writeSpline(DensityProgramSerializer.FlatSpline node, DataPool pool) {
        if (node instanceof DensityProgramSerializer.FlatSpline.Constant c) {
            int off = pool.beginPayload(8);
            pool.putInt(0); // nodeType = Constant
            pool.putFloat(c.value());
            return off;
        }

        DensityProgramSerializer.FlatSpline.Multipoint mp =
                (DensityProgramSerializer.FlatSpline.Multipoint) node;

        int locationsOffset = writeFloatArray(mp.locations(), pool);
        int derivativesOffset = writeFloatArray(mp.derivatives(), pool);
        int[] childOffsets = new int[mp.values().length];
        for (int i = 0; i < childOffsets.length; i++) {
            childOffsets[i] = writeSpline(mp.values()[i], pool);
        }
        int childOffsetsOffset = writeIntArray(childOffsets, pool);

        int off = pool.beginPayload(8);
        pool.putInt(1); // nodeType = Multipoint
        pool.putInt(mp.regIdx());
        pool.putInt(mp.locations().length);
        pool.putInt(locationsOffset);
        pool.putInt(childOffsetsOffset);
        pool.putInt(derivativesOffset);
        pool.putInt(0); // reserved
        return off;
    }

    private static int writeFloatArray(float[] values, DataPool pool) {
        int off = pool.beginPayload(4);
        for (float value : values) {
            pool.putFloat(value);
        }
        return off;
    }

    private static int writeIntArray(int[] values, DataPool pool) {
        int off = pool.beginPayload(4);
        for (int value : values) {
            pool.putInt(value);
        }
        return off;
    }

    private record PackedInstruction(
            int opcodeId,
            int arg1,
            int arg2,
            int dataOffset,
            double value) {
    }

    private static final class DataPool {
        private byte[] data = new byte[4096];
        private int size;

        int beginPayload(int alignment) {
            align(alignment);
            return size;
        }

        void align(int alignment) {
            int mask = alignment - 1;
            while ((size & mask) != 0) {
                putByte(0);
            }
        }

        void putByte(int value) {
            ensure(1);
            data[size++] = (byte) value;
        }

        void putInt(int value) {
            ensure(4);
            ByteBuffer.wrap(data, size, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
            size += 4;
        }

        void putFloat(float value) {
            ensure(4);
            ByteBuffer.wrap(data, size, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value);
            size += 4;
        }

        void putDouble(double value) {
            ensure(8);
            ByteBuffer.wrap(data, size, 8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value);
            size += 8;
        }

        void putBytes(byte[] values) {
            ensure(values.length);
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int bytes) {
            int required = size + bytes;
            if (required <= data.length) {
                return;
            }
            int next = data.length;
            while (next < required) {
                next *= 2;
            }
            data = Arrays.copyOf(data, next);
        }
    }
}
