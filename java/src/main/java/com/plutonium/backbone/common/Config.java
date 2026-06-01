package com.plutonium.backbone.common;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {

    public enum ChunkBuilder {
        CPU,
        NATIVE
    }

    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        CLIENT = new Client(b);
        CLIENT_SPEC = b.build();
    }

    public static final class Client {

        public final ForgeConfigSpec.IntValue cpuThreads;
        public final ForgeConfigSpec.BooleanValue experimentalGpuChunkGen;
        public final ForgeConfigSpec.BooleanValue unsafeGpuWorldgen;
        public final ForgeConfigSpec.ConfigValue<String> chunkBuilder;

        Client(ForgeConfigSpec.Builder b) {
            b.push("plutonium");
            cpuThreads = b
                    .comment("Number of C++ worker threads used by the native backend.")
                    .defineInRange("cpuThreads", Math.max(2, Runtime.getRuntime().availableProcessors()), 1, 32);
            experimentalGpuChunkGen = b
                    .comment("""
                            GPU density assist for NoiseBasedChunkGenerator.fillFromNoise.
                            Native code evaluates the finalDensity cell lattice; vanilla still owns
                            ChunkAccess, aquifers, fluids, block selection, heightmaps, structures,
                            features, carvers, and biome decoration. If the density tree is unsupported,
                            Plutonium falls back to vanilla for the world.""")
                    .define("experimentalGpuChunkGen", true);
            unsafeGpuWorldgen = b
                    .comment("""
                            Developer-only override for the experimental GPU density assist.
                            Keep this false for normal gameplay. When false, vanilla worldgen is used
                            even if experimentalGpuChunkGen is enabled in an old config file.""")
                    .define("unsafeGpuWorldgen", false);
            chunkBuilder = b
                    .comment("""
                            Terrain renderer/mesher.
                            CPU - vanilla rendering (default).
                            NATIVE - Plutonium's C++ mesher + MultiDrawIndirect renderer (experimental).""")
                    .define("chunkBuilder", ChunkBuilder.CPU.name());
            b.pop();
        }
    }

    public static boolean isGpuWorldgenEnabled() {
        try {
            return CLIENT.experimentalGpuChunkGen.get() && CLIENT.unsafeGpuWorldgen.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isGpuWorldgenRequested() {
        try {
            return CLIENT.experimentalGpuChunkGen.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ChunkBuilder getChunkBuilder() {
        try {
            return ChunkBuilder.valueOf(CLIENT.chunkBuilder.get().toUpperCase());
        } catch (Throwable ignored) {
            return ChunkBuilder.CPU;
        }
    }

    public static void setChunkBuilder(ChunkBuilder mode) {
        CLIENT.chunkBuilder.set(mode.name());
    }

    private Config() {
    }
}
