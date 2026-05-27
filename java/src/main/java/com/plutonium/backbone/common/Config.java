package com.plutonium.backbone.common;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {

    public static final int LOD_MIN_DISTANCE = 2;
    public static final int LOD_MAX_DISTANCE = 1024;

    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    /**
     * Controls who builds chunk section meshes.
     * CPU    - vanilla ChunkRenderDispatcher (safe, always compatible with world gen mods).
     * NATIVE - Plutonium's experimental C++ mesher/renderer path. This is separate from
     *          GPU world generation acceleration.
     */
    public enum ChunkBuilder {
        CPU,
        NATIVE
    }

    public enum MeshProfile {
        COMPATIBILITY,
        BALANCED,
        AGGRESSIVE
    }

    public enum NativeLighting {
        ORIGINAL,
        VANILLA
    }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        CLIENT = new Client(b);
        CLIENT_SPEC = b.build();
    }

    public static final class Client {

        public final ForgeConfigSpec.ConfigValue<String> backend;
        public final ForgeConfigSpec.ConfigValue<String> chunkBuilder;
        public final ForgeConfigSpec.IntValue cpuThreads;
        public final ForgeConfigSpec.BooleanValue experimentalGpuChunkGen;
        public final ForgeConfigSpec.BooleanValue unsafeGpuWorldgen;
        public final ForgeConfigSpec.ConfigValue<String> nativeLighting;
        public final ForgeConfigSpec.ConfigValue<String> meshProfile;
        public final ForgeConfigSpec.IntValue lodStartDistance;
        public final ForgeConfigSpec.IntValue meshSectionsPerTick;
        public final ForgeConfigSpec.IntValue meshUploadBudgetMicros;
        public final ForgeConfigSpec.IntValue meshMaxUploadsPerFrame;
        public final ForgeConfigSpec.IntValue meshUploadDrainPerFrame;
        public final ForgeConfigSpec.BooleanValue meshFrustumCulling;
        public final ForgeConfigSpec.BooleanValue meshDistancePriority;
        public final ForgeConfigSpec.BooleanValue meshCameraDirectionCull;
        public final ForgeConfigSpec.BooleanValue meshFastSectionRead;
        public final ForgeConfigSpec.BooleanValue meshTerrainOcclusion;
        public final ForgeConfigSpec.IntValue meshOcclusionRayStep;
        public final ForgeConfigSpec.IntValue meshOcclusionMaxSamples;
        public final ForgeConfigSpec.BooleanValue meshRegionBatching;
        public final ForgeConfigSpec.IntValue meshRegionBatchChunkSize;
        public final ForgeConfigSpec.BooleanValue meshFrontierOrdering;
        public final ForgeConfigSpec.BooleanValue meshUploadRegionStaging;
        public final ForgeConfigSpec.IntValue meshUploadRegionsPerFrame;
        public final ForgeConfigSpec.BooleanValue meshDrawListCompaction;
        public final ForgeConfigSpec.BooleanValue meshTemporalCoherence;
        public final ForgeConfigSpec.IntValue meshTemporalReuseFrames;
        public final ForgeConfigSpec.IntValue meshTemporalCameraMoveThreshold;
        public final ForgeConfigSpec.BooleanValue meshAdaptiveBudget;
        public final ForgeConfigSpec.IntValue meshAdaptiveMinSectionsPerTick;
        public final ForgeConfigSpec.IntValue meshAdaptiveMaxSectionsPerTick;
        public final ForgeConfigSpec.IntValue meshAdaptiveMinUploadDrain;
        public final ForgeConfigSpec.IntValue meshAdaptiveMaxUploadDrain;

        Client(ForgeConfigSpec.Builder b) {
            b.push("plutonium");
            backend = b
                    .comment("Backend renderer: OPENGL (vanilla), DX12, VULKAN.")
                    .define("backend", "OPENGL");
            chunkBuilder = b
                    .comment("""
                            Who builds chunk section meshes.
                            CPU    - vanilla ChunkRenderDispatcher, always safe.
                            NATIVE - experimental Plutonium C++ mesher/renderer path.
                            This does not control GPU world generation acceleration.""")
                    .define("chunkBuilder", ChunkBuilder.CPU.name());
            cpuThreads = b
                    .comment("Number of C++ worker threads used when Chunk Builder is set to NATIVE.")
                    .defineInRange("cpuThreads", Math.max(2, Runtime.getRuntime().availableProcessors()), 1, 32);
            experimentalGpuChunkGen = b
                    .comment("""
                            GPU density assist for NoiseBasedChunkGenerator.fillFromNoise.
                            Native code evaluates the finalDensity cell lattice; vanilla still owns
                            ChunkAccess, aquifers, fluids, block selection, heightmaps, structures,
                            features, carvers, and biome decoration. If the density tree is unsupported,
                            Plutonium falls back to vanilla for the world.""")
                    .define("experimentalGpuChunkGen", false);
            unsafeGpuWorldgen = b
                    .comment("""
                            Developer-only override for the experimental GPU density assist.
                            Keep this false for normal gameplay. When false, vanilla worldgen is used
                            even if experimentalGpuChunkGen is enabled in an old config file.""")
                    .define("unsafeGpuWorldgen", false);
            nativeLighting = b
                    .comment("""
                            Lighting style for native-built solid chunk buffers.
                            VANILLA samples Minecraft's normal lightmap.
                            ORIGINAL is kept only for legacy developer testing.""")
                    .define("nativeLighting", NativeLighting.VANILLA.name());

            b.push("mesh");
            meshProfile = b
                    .comment("Quick preset for mesh scheduler behavior: COMPATIBILITY, BALANCED, AGGRESSIVE")
                    .define("profile", MeshProfile.BALANCED.name());
            lodStartDistance = b
                    .comment("""
                            Full-detail chunk radius before far terrain LOD begins.
                            Values at or above the current Minecraft render distance disable LOD.
                            The video-settings slider clamps this value to the active render distance.""")
                    .defineInRange("lodStartDistance", 32, LOD_MIN_DISTANCE, LOD_MAX_DISTANCE);
            meshSectionsPerTick = b
                    .comment("Sodium/Embeddium-style section scheduling budget: max section meshes submitted each render tick")
                    .defineInRange("sectionsPerTick", 4, 1, 16);
            meshUploadBudgetMicros = b
                    .comment("Per-frame mesh upload budget in microseconds (2000 = 2ms, Sodium-like baseline)")
                    .defineInRange("uploadBudgetMicros", 2000, 500, 8000);
            meshMaxUploadsPerFrame = b
                    .comment("Max pending mesh uploads allowed per frame before deferring tasks")
                    .defineInRange("maxUploadsPerFrame", 64, 8, 512);
            meshUploadDrainPerFrame = b
                    .comment("How many completed mesh uploads are drained to GL each render frame")
                    .defineInRange("uploadDrainPerFrame", 4, 1, 32);
            meshFrustumCulling = b
                    .comment("Cull chunk sections outside relaxed camera frustum")
                    .define("frustumCulling", true);
            meshDistancePriority = b
                    .comment("Prioritize nearby chunk sections first and defer farther sections")
                    .define("distancePriority", true);
            meshCameraDirectionCull = b
                    .comment("Extra Embeddium-style directional culling for sections behind the camera")
                    .define("cameraDirectionCull", true);
            meshFastSectionRead = b
                    .comment("Use cached chunk-section block reads instead of per-voxel Level#getBlockState calls")
                    .define("fastSectionRead", true);
            meshTerrainOcclusion = b
                    .comment("Enable terrain-based occlusion checks for farther chunk sections")
                    .define("terrainOcclusion", true);
            meshOcclusionRayStep = b
                    .comment("Block step size for terrain occlusion ray checks (lower is stricter but more expensive)")
                    .defineInRange("occlusionRayStep", 4, 2, 8);
            meshOcclusionMaxSamples = b
                    .comment("Max samples per occlusion ray test")
                    .defineInRange("occlusionMaxSamples", 32, 8, 96);
            meshRegionBatching = b
                    .comment("Batch section submission by chunk-region to reduce submission churn")
                    .define("regionBatching", true);
            meshRegionBatchChunkSize = b
                    .comment("Chunk width/height of each submission region batch")
                    .defineInRange("regionBatchChunkSize", 8, 2, 16);
            meshFrontierOrdering = b
                    .comment("Use camera-frontier ordering (Sodium/Embeddium-like) instead of pure distance sort")
                    .define("frontierOrdering", true);
            meshUploadRegionStaging = b
                    .comment("Stage pending uploads by region before GL upload to reduce upload churn")
                    .define("uploadRegionStaging", true);
            meshUploadRegionsPerFrame = b
                    .comment("Maximum number of staged regions to drain uploads from per frame")
                    .defineInRange("uploadRegionsPerFrame", 4, 1, 16);
            meshDrawListCompaction = b
                    .comment("Use compact cached draw lists instead of iterating hash maps every frame")
                    .define("drawListCompaction", true);
            meshTemporalCoherence = b
                    .comment("Reuse section visibility eligibility across frames when camera movement is small")
                    .define("temporalCoherence", true);
            meshTemporalReuseFrames = b
                    .comment("Maximum frames to reuse section visibility decisions")
                    .defineInRange("temporalReuseFrames", 4, 1, 16);
            meshTemporalCameraMoveThreshold = b
                    .comment("Camera movement threshold in blocks before temporal cache is invalidated")
                    .defineInRange("temporalCameraMoveThreshold", 2, 1, 16);
            meshAdaptiveBudget = b
                    .comment("Automatically tune sections-per-tick and upload-drain from recent pressure")
                    .define("adaptiveBudget", true);
            meshAdaptiveMinSectionsPerTick = b
                    .comment("Lower bound for adaptive sections-per-tick")
                    .defineInRange("adaptiveMinSectionsPerTick", 1, 1, 16);
            meshAdaptiveMaxSectionsPerTick = b
                    .comment("Upper bound for adaptive sections-per-tick")
                    .defineInRange("adaptiveMaxSectionsPerTick", 6, 1, 16);
            meshAdaptiveMinUploadDrain = b
                    .comment("Lower bound for adaptive upload drain per frame")
                    .defineInRange("adaptiveMinUploadDrain", 2, 1, 32);
            meshAdaptiveMaxUploadDrain = b
                    .comment("Upper bound for adaptive upload drain per frame")
                    .defineInRange("adaptiveMaxUploadDrain", 10, 1, 32);
            b.pop();
            b.pop();
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

    public static NativeLighting getNativeLighting() {
        try {
            return NativeLighting.valueOf(CLIENT.nativeLighting.get().toUpperCase());
        } catch (Throwable ignored) {
            return NativeLighting.VANILLA;
        }
    }

    public static void setNativeLighting(NativeLighting lighting) {
        CLIENT.nativeLighting.set(lighting.name());
    }

    public static MeshProfile getMeshProfile() {
        try {
            return MeshProfile.valueOf(CLIENT.meshProfile.get().toUpperCase());
        } catch (Throwable ignored) {
            return MeshProfile.BALANCED;
        }
    }

    public static void setMeshProfile(MeshProfile profile) {
        CLIENT.meshProfile.set(profile.name());

        switch (profile) {
            case COMPATIBILITY -> {
                CLIENT.meshSectionsPerTick.set(1);
                CLIENT.meshUploadBudgetMicros.set(1200);
                CLIENT.meshMaxUploadsPerFrame.set(32);
                CLIENT.meshUploadDrainPerFrame.set(3);
                CLIENT.meshFrustumCulling.set(true);
                CLIENT.meshDistancePriority.set(true);
                CLIENT.meshCameraDirectionCull.set(false);
                CLIENT.meshFastSectionRead.set(true);
                CLIENT.meshTerrainOcclusion.set(false);
                CLIENT.meshOcclusionRayStep.set(6);
                CLIENT.meshOcclusionMaxSamples.set(20);
                CLIENT.meshRegionBatching.set(false);
                CLIENT.meshRegionBatchChunkSize.set(8);
                CLIENT.meshFrontierOrdering.set(false);
                CLIENT.meshUploadRegionStaging.set(false);
                CLIENT.meshUploadRegionsPerFrame.set(2);
                CLIENT.meshDrawListCompaction.set(false);
                CLIENT.meshTemporalCoherence.set(false);
                CLIENT.meshTemporalReuseFrames.set(2);
                CLIENT.meshTemporalCameraMoveThreshold.set(1);
                CLIENT.meshAdaptiveBudget.set(false);
                CLIENT.meshAdaptiveMinSectionsPerTick.set(1);
                CLIENT.meshAdaptiveMaxSectionsPerTick.set(2);
                CLIENT.meshAdaptiveMinUploadDrain.set(1);
                CLIENT.meshAdaptiveMaxUploadDrain.set(4);
            }
            case BALANCED -> {
                CLIENT.meshSectionsPerTick.set(2);
                CLIENT.meshUploadBudgetMicros.set(2000);
                CLIENT.meshMaxUploadsPerFrame.set(64);
                CLIENT.meshUploadDrainPerFrame.set(4);
                CLIENT.meshFrustumCulling.set(true);
                CLIENT.meshDistancePriority.set(true);
                CLIENT.meshCameraDirectionCull.set(true);
                CLIENT.meshFastSectionRead.set(true);
                CLIENT.meshTerrainOcclusion.set(true);
                CLIENT.meshOcclusionRayStep.set(4);
                CLIENT.meshOcclusionMaxSamples.set(32);
                CLIENT.meshRegionBatching.set(true);
                CLIENT.meshRegionBatchChunkSize.set(8);
                CLIENT.meshFrontierOrdering.set(true);
                CLIENT.meshUploadRegionStaging.set(true);
                CLIENT.meshUploadRegionsPerFrame.set(4);
                CLIENT.meshDrawListCompaction.set(true);
                CLIENT.meshTemporalCoherence.set(true);
                CLIENT.meshTemporalReuseFrames.set(4);
                CLIENT.meshTemporalCameraMoveThreshold.set(2);
                CLIENT.meshAdaptiveBudget.set(true);
                CLIENT.meshAdaptiveMinSectionsPerTick.set(1);
                CLIENT.meshAdaptiveMaxSectionsPerTick.set(4);
                CLIENT.meshAdaptiveMinUploadDrain.set(2);
                CLIENT.meshAdaptiveMaxUploadDrain.set(8);
            }
            case AGGRESSIVE -> {
                CLIENT.meshSectionsPerTick.set(4);
                CLIENT.meshUploadBudgetMicros.set(3000);
                CLIENT.meshMaxUploadsPerFrame.set(128);
                CLIENT.meshUploadDrainPerFrame.set(8);
                CLIENT.meshFrustumCulling.set(true);
                CLIENT.meshDistancePriority.set(true);
                CLIENT.meshCameraDirectionCull.set(true);
                CLIENT.meshFastSectionRead.set(true);
                CLIENT.meshTerrainOcclusion.set(true);
                CLIENT.meshOcclusionRayStep.set(3);
                CLIENT.meshOcclusionMaxSamples.set(48);
                CLIENT.meshRegionBatching.set(true);
                CLIENT.meshRegionBatchChunkSize.set(6);
                CLIENT.meshFrontierOrdering.set(true);
                CLIENT.meshUploadRegionStaging.set(true);
                CLIENT.meshUploadRegionsPerFrame.set(6);
                CLIENT.meshDrawListCompaction.set(true);
                CLIENT.meshTemporalCoherence.set(true);
                CLIENT.meshTemporalReuseFrames.set(8);
                CLIENT.meshTemporalCameraMoveThreshold.set(3);
                CLIENT.meshAdaptiveBudget.set(true);
                CLIENT.meshAdaptiveMinSectionsPerTick.set(2);
                CLIENT.meshAdaptiveMaxSectionsPerTick.set(8);
                CLIENT.meshAdaptiveMinUploadDrain.set(3);
                CLIENT.meshAdaptiveMaxUploadDrain.set(12);
            }
        }
    }

    public static int getLodStartDistance(int renderDistance) {
        int max = Math.max(LOD_MIN_DISTANCE, Math.min(LOD_MAX_DISTANCE, renderDistance));
        try {
            return clamp(CLIENT.lodStartDistance.get(), LOD_MIN_DISTANCE, max);
        } catch (Throwable ignored) {
            return max;
        }
    }

    public static boolean isLodEnabled(int renderDistance) {
        return getLodStartDistance(renderDistance) < Math.max(LOD_MIN_DISTANCE, renderDistance);
    }

    public static void setLodStartDistance(int chunks) {
        CLIENT.lodStartDistance.set(clamp(chunks, LOD_MIN_DISTANCE, LOD_MAX_DISTANCE));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Config() {
    }
}
