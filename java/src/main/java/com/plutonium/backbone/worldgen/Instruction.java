package com.plutonium.backbone.worldgen;

/**
 * One node of the compiled density-function bytecode.
 *
 * arg1 / arg2 are instruction indices or small opcode-specific primitive tags.
 * value carries the primary scalar for simple instructions.
 * extraData may only carry Plutonium-owned primitive payload records or primitive arrays.
 * It must never carry Minecraft runtime objects, DensityFunction nodes, NoiseHolder instances,
 * enums, Holders, or other objects that native code cannot serialize.
 */
public record Instruction(Opcode op, int arg1, int arg2, double value, Object extraData) {
}
