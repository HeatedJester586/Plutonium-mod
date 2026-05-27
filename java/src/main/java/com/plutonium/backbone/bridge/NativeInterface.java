package com.plutonium.backbone.bridge;

import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class NativeInterface {

    private static volatile boolean loaded    = false;
    private static volatile boolean attempted = false;

    private NativeInterface() {}

    public static void ensureLoaded() {
        if (loaded) return;
        synchronized (NativeInterface.class) {
            if (loaded) return;
            if (attempted) {
                System.err.println("[Plutonium] Native library was not loaded (previous attempt failed).");
                return;
            }
            attempted = true;
            tryLoad();
        }
    }

    private static void tryLoad() {
        // 1) Dev override: -Dplutonium.native.path=<absolute path to DLL>
        String devPath = System.getProperty("plutonium.native.path");
        if (devPath != null && !devPath.isBlank()) {
            File f = new File(devPath);
            if (f.exists()) {
                try {
                    System.load(f.getAbsolutePath());
                    System.out.println("[Plutonium] Loaded DLL from dev path: " + f);
                    loaded = true;
                    return;
                } catch (Throwable t) {
                    System.err.println("[Plutonium] Failed to load DLL from dev path: " + f);
                    t.printStackTrace();
                }
            } else {
                System.err.println("[Plutonium] Dev path set but file does not exist: " + f.getAbsolutePath());
            }
        }

        // 2) Embedded DLL extracted from JAR resources
        try {
            String dllName = "plutonium_backend.dll";
            try (InputStream in = NativeInterface.class.getResourceAsStream("/" + dllName)) {
                if (in == null) {
                    System.err.println("[Plutonium] DLL not found in JAR: " + dllName);
                    return;
                }
                File outDir  = new File(System.getProperty("java.io.tmpdir"), "plutonium-natives");
                outDir.mkdirs();
                File outFile = new File(outDir, dllName);
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                System.load(outFile.getAbsolutePath());
                System.out.println("[Plutonium] Loaded DLL from temp: " + outFile);
                loaded = true;
            }
        } catch (Throwable t) {
            System.err.println("[Plutonium] Failed to load native DLL:");
            t.printStackTrace();
        }
    }

    public static boolean isLoaded() { return loaded; }

    public static ByteBuffer wrapPinnedBuffer(long address, int width, int height) {
        return MemoryUtil.memByteBuffer(address, width * height * 3);
    }

    // ---- Lifecycle ----
    public static native long nInitBackend(int width, int height, int cpuThreads);
    public static native void nShutdownBackend(long enginePtr);
    public static native void nTestCpuTask(long enginePtr);

    // ---- Zero-copy bridge ----
    public static native long nGetPinnedWorldAddress(long enginePtr);

    // ---- Chunk Generation ----
    // Returns a pointer to the generated buffer, or 0 if falling back to vanilla
    public static native long nGenerateChunkNoise(long enginePtr, int chunkX, int chunkZ, long seed);
    public static native boolean nGenerateChunkNoiseInto(
            long enginePtr, int chunkX, int chunkZ, long seed, java.nio.ByteBuffer outBuffer, int size);

    /**
     * Evaluates the currently uploaded density AST at arbitrary world-space points.
     * coordsBuffer: direct little-endian int triplets (x, y, z), count * 12 bytes.
     * outBuffer:    direct little-endian doubles, count * 8 bytes.
     */
    public static native boolean nEvaluateDensityPoints(
            long enginePtr, java.nio.ByteBuffer coordsBuffer, java.nio.ByteBuffer outBuffer, int count);

    /**
     * Evaluates the currently uploaded density AST at the exact 5x49x5 lattice
     * points used by Minecraft 1.20.1's overworld noise fill loop for one chunk.
     * outBuffer: direct little-endian doubles, count * 8 bytes.
     */
    public static native boolean nEvaluateChunkDensityCells(
            long enginePtr, int chunkX, int chunkZ, long seed, java.nio.ByteBuffer outBuffer, int count);

    // ---- Physics ----
    public static native void nStartPhysics(long enginePtr);
    public static native void nStopPhysics(long enginePtr);

    // ---- Block Update ----
    public static native void nUpdateBlock(long enginePtr, int x, int y, int z, byte id, byte meta, byte light);

    /**
     * Copies the direct buffer and returns immediately; native C++ thread pool applies records (16 bytes each:
     * LE x,y,z int, block id, 3 pad) to pinned host memory. GPU sees changes on the next physics H2D step.
     */
    public static native void nUpdateBlockBatch(long enginePtr, java.nio.ByteBuffer data, int count);

    // ---- Entity Logic ----
    public static native void nUpdateEntityLogic(long enginePtr, int entityId, float x, float y, float z, float yaw, float pitch, float vx, float vy, float vz);

    // ---- Graphics interop ----
    public static native void nRegisterShadowWorld(long enginePtr, long address);
    public static native long nRenderFrame(long enginePtr, float timeSeconds);
    public static native long nGetSharedTextureHandle(long enginePtr);
    public static native long nGetSharedTextureAllocationSize(long enginePtr);
    public static native long nGetSharedFenceHandle(long enginePtr);

    // ---- Tick Signal ----
    public static native void nSignalTick(long enginePtr);
    public static native void nUploadWorldData(long enginePtr, int cx, int cz, java.nio.ByteBuffer data);
    public static native void nUploadAST(long enginePtr, java.nio.ByteBuffer astBuffer, int size);

    // ---- v4 chunk pipeline driver loop ----
    public static final int PIPELINE_CHUNK_HEIGHT = 384;
    public static final int PIPELINE_CHUNK_BLOCK_COUNT = 16 * 16 * PIPELINE_CHUNK_HEIGHT;

    public static native void nPipelineInit(int workerCount);
    public static native void nPipelineShutdown();
    public static native void nPipelineUploadBlockProperties(byte[] properties);
    public static native boolean nPipelineUploadChunkColumn(int chunkX, int chunkZ, short[] blockIds, byte[] lightIds);
    public static native void nPipelineInvalidateChunks(long[] keys, long[] values, int count);
    public static native void nPipelineUploadChunkLight(int chunkX, int chunkZ, byte[] lightIds);
    public static native boolean nPipelineTryDispatchChunk(int chunkX, int chunkZ);
    public static native void nPipelineUnregisterChunk(int chunkX, int chunkZ);
    public static native boolean nPipelineConfigureRenderer(
            long geometryFacesPtr,
            int geometryFaceCapacity,
            long chunkMetadataPtr,
            long drawCommandsPtr,
            int maxRegisteredChunks,
            int worldMinY);
    public static native int nPipelineBeginFrame();
    public static native void nPipelineEndFrame();
    public static native int nPipelineRendererMaxChunks();
    public static native int nPipelineRendererDrawCount();
    /**
     * Scans up to 200 active draw slots for canonical-metadata vs chunk-position
     * mismatches. Returns mismatch count. Fills outBuf with up to outBuf.length/3
     * packed mismatch records (3 longs each: slot, (ctx_x<<32)|ctx_z,
     * (meta_x<<32)|meta_z). activeOut[0] receives the active-slot scan count.
     */
    public static native int nPipelineRunMetadataAudit(long[] outBuf, int[] activeOut);
    public static native int nPipelinePendingSwapCount();
    public static native int nPipelineActiveMeshJobCount();
    public static native int nPipelineCountCompiledColumns(long[] keys, int count);

    /**
     * Returns any error / log messages the C++ side has queued since the last
     * call. Messages may be tagged with a single-char severity followed by
     * '|' (E=error, W=warn, D=debug). Untagged messages route to info.
     *
     * The DLL is expected to keep these in a thread-safe ring buffer; one
     * call drains and clears it.
     */
    public static native String[] nDrainNativeLogs();

    /**
     * Build a GPU face-culled mesh for one 16x16x16 chunk section.
     * blockData18: direct ByteBuffer, exactly 5832 bytes (18x18x18 padded block IDs)
     * outVerts:    direct ByteBuffer, capacity >= maxVerts * 16 bytes
     * Returns number of vertices written, 0 for empty section, -1 on error.
     */
    public static native int nBuildSectionMesh(
        long enginePtr,
        java.nio.ByteBuffer blockData18,
        java.nio.ByteBuffer outVerts,
        int maxVerts,
        float originX, float originY, float originZ);

    // ── Phase 1: Native CPU multi-threaded mesher ────────────────────────────
    // Independent of CUDA — runs vanilla-style face-culled meshing on a
    // dedicated C++ ThreadPool. The point is to move mesh work OFF the JVM
    // worker threads (which is where the 32-chunk render-distance CPU pinning
    // comes from) without requiring a textured GPU mesh kernel.
    //
    // Phase 1 emits colored-cube geometry (placeholder palette). Phase 2 will
    // mirror vanilla's texture atlas and emit UV-textured quads.

    /** Spin up the native mesh thread pool. threadCount=0 ⇒ auto = HW/2. Idempotent. */
    public static native void nInitNativeMesher(int threadCount);

    /** Tear the pool down (call on world unload or mod shutdown). Idempotent. */
    public static native void nShutdownNativeMesher();

    /**
     * Phase 2 — push the block→UV table to native memory.
     * uvBuffer: direct ByteBuffer, LITTLE_ENDIAN, containing rectCount * 16 bytes.
     *           Layout per entry: float u0, float v0, float u1, float v1.
     *           Indexed by (byte)Block.getId(state) — so only the first 256
     *           block state IDs survive; uncommon blocks may share a slot.
     */
    public static native void nUploadTextureTable(java.nio.ByteBuffer uvBuffer, int rectCount);

    /**
     * Build a face-culled mesh on native CPU threads.
     * blockData18: direct ByteBuffer, exactly 5832 bytes (18×18×18 padded block IDs)
     * outVerts:    direct ByteBuffer, capacity >= maxVerts * 16 bytes
     *              (vertex layout: 3 floats position + 4 bytes RGBA = 16 bytes)
     * Returns number of vertices written, 0 for empty section, -1 on error/overflow.
     */
    public static native int nNativeMeshSection(
        java.nio.ByteBuffer blockData18,
        java.nio.ByteBuffer outVerts,
        int maxVerts,
        float originX, float originY, float originZ);
}
