package com.plutonium.backbone.worldgen;

public enum Opcode {
    // Constants
    CONSTANT,

    // Binary arithmetic (DensityFunctions.Ap2)
    ADD,
    MUL,
    MIN,
    MAX,

    // Unary transforms (DensityFunctions.Mapped)
    ABS,
    SQUARE,
    CUBE,
    HALF_NEGATIVE,
    QUARTER_NEGATIVE,
    SQUEEZE,

    // Caching / passthrough markers
    MARKER,

    // Bounded transform
    CLAMP,

    // Noise leaves
    NOISE,
    SHIFT,
    SHIFT_A,
    SHIFT_B,

    // Noise composites (have child DFs)
    SHIFTED_NOISE,

    // Blender hooks
    BLEND_ALPHA,
    BLEND_OFFSET,
    BLEND_DENSITY,

    // Structure hooks
    BEARDIFIER_MARKER,

    // Spline (flattened CubicSpline tree, evaluated via Hermite interpolation)
    SPLINE,

    // Branching
    RANGE_CHOICE,

    // Vertical gradient
    Y_CLAMPED_GRADIENT,

    // Scaled unary arithmetic (DensityFunctions.MulOrAdd)
    MUL_OR_ADD,

    // Weird-scaled noise sampler (DensityFunctions.WeirdScaledSampler)
    WEIRD_SCALED_SAMPLER,

    // Blended noise leaf (net.minecraft.world.level.levelgen.synth.BlendedNoise)
    BLENDED_NOISE
}
