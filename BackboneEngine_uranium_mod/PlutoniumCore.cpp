#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <GL/gl.h> // OpenGL must come after windows.h
// This line tells Visual Studio to find the OpenGL library automatically
#pragma comment(lib, "opengl32.lib")
#include <jni.h>
#include <iostream>
#include <vector>
#include <chrono>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <cuda_runtime.h>
#include <device_launch_parameters.h>
#include "ComputeEngine.h"
#include "NativeMesher.h"
#include "chunk_pipeline.h"
#include "plutonium_log_queue.h"
#include <deque>
#include <mutex>
#include <string>

namespace {
    constexpr size_t kLogQueueMaxEntries = 1024;
    std::mutex g_log_queue_mutex;
    std::deque<std::string> g_log_queue;
    bool g_log_queue_overflowed = false;
}

extern "C" void pluto_log_queue_push(const char* msg) {
    if (!msg) return;
    std::lock_guard<std::mutex> lk(g_log_queue_mutex);
    if (g_log_queue.size() >= kLogQueueMaxEntries) {
        g_log_queue.pop_front();
        g_log_queue_overflowed = true;
    }
    g_log_queue.emplace_back(msg);
}

extern "C" char** pluto_log_queue_drain(int* count) {
    std::lock_guard<std::mutex> lk(g_log_queue_mutex);
    if (g_log_queue.empty() && !g_log_queue_overflowed) {
        if (count) *count = 0;
        return nullptr;
    }
    size_t extra = g_log_queue_overflowed ? 1 : 0;
    size_t total = g_log_queue.size() + extra;
    char** arr = (char**)std::malloc(total * sizeof(char*));
    if (!arr) {
        if (count) *count = 0;
        return nullptr;
    }
    size_t i = 0;
    if (g_log_queue_overflowed) {
        const char* warn = "W|native log queue overflowed; some messages dropped";
        size_t len = std::strlen(warn);
        char* copy = (char*)std::malloc(len + 1);
        if (copy) { std::memcpy(copy, warn, len + 1); arr[i++] = copy; }
        g_log_queue_overflowed = false;
    }
    for (const std::string& s : g_log_queue) {
        char* copy = (char*)std::malloc(s.size() + 1);
        if (!copy) { arr[i++] = nullptr; continue; }
        std::memcpy(copy, s.c_str(), s.size() + 1);
        arr[i++] = copy;
    }
    g_log_queue.clear();
    if (count) *count = (int)i;
    return arr;
}

extern "C" void pluto_log_queue_free_string(char* s) { if (s) std::free(s); }
extern "C" void pluto_log_queue_free_array(char** arr) { if (arr) std::free(arr); }

// fprintf is preserved so users running the engine outside Minecraft still
// see logs on their terminal; the queue push is what surfaces them inside
// Forge via NativeLogBridge.drain().
static inline void pluto_log_impl(const char* tag, const char* fmt, ...) {
    char body[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);
    fprintf(stdout, "[%s] %s\n", tag, body);
    fflush(stdout);
    char queued[1100];
    snprintf(queued, sizeof(queued), "[%s] %s", tag, body);
    pluto_log_queue_push(queued);
}

#define PLUTO_LOG(fmt, ...) pluto_log_impl("Plutonium/JNI", fmt, ##__VA_ARGS__)

static ComputeEngine* g_engine = nullptr;
static PipelineHardwareContext g_chunk_pipeline_hw;
static std::atomic<bool> g_chunk_pipeline_ready{false};
static std::mutex g_chunk_pipeline_lifecycle_mutex;

extern void plutoniumCudaUploadTextureTable(const void* data, int rectCount);

static void ensure_chunk_pipeline_started(int workerCount) {
    std::lock_guard<std::mutex> lock(g_chunk_pipeline_lifecycle_mutex);
    if (g_chunk_pipeline_ready.load(std::memory_order_acquire)) return;
    pipeline_hardware_init(&g_chunk_pipeline_hw, workerCount < 0 ? 0u : (uint32_t)workerCount);
    g_chunk_pipeline_ready.store(true, std::memory_order_release);
}

extern "C" {

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nInitBackend(
            JNIEnv* env, jclass cls, jint width, jint height, jint cpuThreads) {
        PLUTO_LOG("nInitBackend called (%dx%d, %d threads)", (int)width, (int)height, (int)cpuThreads);
        if (g_engine) {
            PLUTO_LOG("Engine already exists, skipping re-init.");
            return (jlong)(uintptr_t)g_engine;
        }
        g_engine = new ComputeEngine();
        if (!g_engine->init(0, (int)width, (int)height, (int)cpuThreads)) {
            PLUTO_LOG("ERROR: ComputeEngine::init failed.");
            delete g_engine;
            g_engine = nullptr;
            return 0L;
        }
        PLUTO_LOG("nInitBackend complete, ptr=%p", (void*)g_engine);
        return (jlong)(uintptr_t)g_engine;
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nShutdownBackend(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        PLUTO_LOG("nShutdownBackend called.");
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (engine && engine == g_engine) {
            delete g_engine;
            g_engine = nullptr;
            PLUTO_LOG("Engine destroyed.");
        }
    }

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nGetPinnedWorldAddress(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (!engine) { PLUTO_LOG("ERROR: nGetPinnedWorldAddress — engine is null."); return 0L; }
        void* ptr = engine->getPinnedWorldPtr();
        PLUTO_LOG("nGetPinnedWorldAddress returning %p", ptr);
        return (jlong)(uintptr_t)ptr;
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nStartPhysics(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        PLUTO_LOG("nStartPhysics called.");
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (!engine) { PLUTO_LOG("ERROR: nStartPhysics — engine is null."); return; }
        engine->startPhysics();
        PLUTO_LOG("nStartPhysics returned.");
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nStopPhysics(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        PLUTO_LOG("nStopPhysics called.");
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (engine) { engine->stop(); PLUTO_LOG("nStopPhysics: engine stopped."); }
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nRegisterShadowWorld(
            JNIEnv* env, jclass cls, jlong enginePtr, jlong address) {
        PLUTO_LOG("nRegisterShadowWorld called (stub).");
    }

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nRenderFrame(
            JNIEnv* env, jclass cls, jlong enginePtr, jfloat timeSeconds) {
        return 0L;
    }

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nGetSharedTextureHandle(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        return 0L;
    }

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nGetSharedTextureAllocationSize(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        return 0L;
    }

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nGetSharedFenceHandle(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        return 0L;
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nTestCpuTask(
            JNIEnv* env, jclass cls, jlong enginePtr) {
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (engine) {
            PLUTO_LOG("Submitting test task to CPU Thread Pool...");
            engine->submitCpuTask([]() {
                PLUTO_LOG("Hello from a background C++ worker thread!");
                double result = 0.0;
                for (int i = 0; i < 1000000; i++) result += i * 0.001;
                PLUTO_LOG("Background task finished. Dummy result: %f", result);
                });
        }
    }

    JNIEXPORT jlong JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nGenerateChunkNoise(
            JNIEnv* env, jclass cls, jlong enginePtr, jint cx, jint cz, jlong seed) {
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (!engine) return 0L;
        PLUTO_LOG("GPU generating chunk (%d, %d)", (int)cx, (int)cz);
        void* result = engine->generateChunkNoise((int)cx, (int)cz, (long)seed);
        return (jlong)(uintptr_t)result;
    }

    JNIEXPORT jboolean JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nGenerateChunkNoiseInto(
            JNIEnv* env, jclass cls, jlong enginePtr, jint cx, jint cz, jlong seed, jobject outBuffer, jint size) {
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (!engine || !outBuffer || size <= 0) return JNI_FALSE;

        void* out = env->GetDirectBufferAddress(outBuffer);
        jlong capacity = env->GetDirectBufferCapacity(outBuffer);
        if (!out || capacity < size) {
            PLUTO_LOG("nGenerateChunkNoiseInto: invalid direct output buffer");
            return JNI_FALSE;
        }

        bool ok = engine->generateChunkNoiseInto((int)cx, (int)cz, (long)seed, out, (size_t)size);
        return ok ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nEvaluateDensityPoints(
            JNIEnv* env, jclass cls, jlong enginePtr, jobject coordsBuffer, jobject outBuffer, jint count) {
        (void)cls;
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (!engine || !coordsBuffer || !outBuffer || count <= 0) return JNI_FALSE;

        void* coords = env->GetDirectBufferAddress(coordsBuffer);
        void* out = env->GetDirectBufferAddress(outBuffer);
        jlong coordsCap = env->GetDirectBufferCapacity(coordsBuffer);
        jlong outCap = env->GetDirectBufferCapacity(outBuffer);
        const jlong requiredCoords = (jlong)count * 3LL * (jlong)sizeof(int32_t);
        const jlong requiredOut = (jlong)count * (jlong)sizeof(double);
        if (!coords || !out || coordsCap < requiredCoords || outCap < requiredOut) {
            PLUTO_LOG("nEvaluateDensityPoints: invalid buffers coordsCap=%lld outCap=%lld required=%lld/%lld",
                      (long long)coordsCap, (long long)outCap,
                      (long long)requiredCoords, (long long)requiredOut);
            return JNI_FALSE;
        }

        bool ok = engine->evaluateDensityPoints(
            (const int32_t*)coords, (double*)out, (int)count);
        return ok ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nEvaluateChunkDensityCells(
            JNIEnv* env, jclass cls, jlong enginePtr, jint cx, jint cz, jlong seed, jobject outBuffer, jint count) {
        (void)cls;
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (!engine || !outBuffer || count <= 0) return JNI_FALSE;

        void* out = env->GetDirectBufferAddress(outBuffer);
        jlong outCap = env->GetDirectBufferCapacity(outBuffer);
        const jlong requiredOut = (jlong)count * (jlong)sizeof(double);
        if (!out || outCap < requiredOut) {
            PLUTO_LOG("nEvaluateChunkDensityCells: invalid out buffer outCap=%lld required=%lld",
                      (long long)outCap, (long long)requiredOut);
            return JNI_FALSE;
        }

        bool ok = engine->evaluateChunkDensityCells(
            (int)cx, (int)cz, (long)seed, (double*)out, (int)count);
        return ok ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nUpdateBlock(
            JNIEnv* env, jclass cls, jlong enginePtr, jint x, jint y, jint z, jbyte id, jbyte meta, jbyte light) {
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (engine) {
            engine->setBlockNative(x, y, z, (unsigned char)id, (unsigned char)meta, (unsigned char)light);
        }
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nUpdateEntityLogic(
            JNIEnv* env, jclass cls, jlong enginePtr, jint id,
            jfloat x, jfloat y, jfloat z, jfloat yaw, jfloat pitch,
            jfloat vx, jfloat vy, jfloat vz) {
        ComputeEngine* engine = (ComputeEngine*)(uintptr_t)enginePtr;
        if (engine) {
            (void)vx; (void)vy; (void)vz;
            engine->submitCpuTask([engine, id, x, y, z, yaw, pitch]() {
                engine->processEntityAI(id, x, y, z, yaw, pitch);
                });
        }
    }

    JNIEXPORT void JNICALL Java_com_plutonium_backbone_bridge_NativeInterface_nUploadWorldData(
        JNIEnv* env, jclass, jlong enginePtr, jint cx, jint cz, jobject byteBuffer) {
        ComputeEngine* engine = reinterpret_cast<ComputeEngine*>(static_cast<uintptr_t>(enginePtr));
        if (!engine || !byteBuffer) return;
        void* data = env->GetDirectBufferAddress(byteBuffer);
        if (!data) {
            PLUTO_LOG("nUploadWorldData: direct buffer address is null");
            return;
        }
        engine->uploadWorldData((int)cx, (int)cz, data);
    }

    JNIEXPORT void JNICALL Java_com_plutonium_backbone_bridge_NativeInterface_nUploadAST(
        JNIEnv* env, jclass, jlong enginePtr, jobject byteBuffer, jint size) {
        ComputeEngine* engine = reinterpret_cast<ComputeEngine*>(static_cast<uintptr_t>(enginePtr));
        if (!engine || !byteBuffer || size <= 0) return;

        void* data = env->GetDirectBufferAddress(byteBuffer);
        if (!data) {
            PLUTO_LOG("nUploadAST: direct buffer address is null");
            return;
        }

        engine->uploadAST(data, (size_t)size);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nSignalTick(
            JNIEnv* env, jclass, jlong enginePtr) {
        ComputeEngine* engine = reinterpret_cast<ComputeEngine*>(enginePtr);
        if (engine) engine->needsUpdate.store(true);
    }

    JNIEXPORT void JNICALL Java_com_plutonium_backbone_bridge_NativeInterface_nUpdateBlockBatch(
        JNIEnv* env, jclass, jlong enginePtr, jobject byteBuffer, jint count) {
        ComputeEngine* engine = reinterpret_cast<ComputeEngine*>(enginePtr);
        if (!engine || !byteBuffer || count <= 0) return;

        unsigned char* data = (unsigned char*)env->GetDirectBufferAddress(byteBuffer);
        if (!data) return;

        const size_t nbytes = (size_t)count * 16u;
        if (nbytes / 16u != (size_t)count) return;

        // OPTIMIZATION 3: Apply directly from JNI buffer without copying to vector
        // The applyJniBlockBatch must complete before the JNI buffer is freed.
        // Since JNI calls are serialized per-thread, this is safe.
        engine->applyJniBlockBatch(data, count);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineInit(
            JNIEnv*, jclass, jint workerCount) {
        ensure_chunk_pipeline_started((int)workerCount);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineShutdown(
            JNIEnv*, jclass) {
        std::lock_guard<std::mutex> lock(g_chunk_pipeline_lifecycle_mutex);
        if (!g_chunk_pipeline_ready.load(std::memory_order_acquire)) return;
        pipeline_hardware_destroy(&g_chunk_pipeline_hw);
        g_chunk_pipeline_ready.store(false, std::memory_order_release);
        PLUTO_LOG("nPipelineShutdown: v4 chunk pipeline stopped");
    }

    JNIEXPORT jboolean JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineUploadChunkColumn(
            JNIEnv* env, jclass, jint chunkX, jint chunkZ, jshortArray blockArray, jbyteArray lightArray) {
        if (!blockArray || !lightArray) return JNI_FALSE;

        const jsize blockLen = env->GetArrayLength(blockArray);
        const jsize lightLen = env->GetArrayLength(lightArray);
        if (blockLen < (jsize)CHUNK_BLOCK_COUNT || lightLen < (jsize)CHUNK_BLOCK_COUNT) {
            PLUTO_LOG("nPipelineUploadChunkColumn: payload too small blocks=%d lights=%d required=%u",
                      (int)blockLen, (int)lightLen, CHUNK_BLOCK_COUNT);
            return JNI_FALSE;
        }

        ensure_chunk_pipeline_started(0);

        jshort* blocks = env->GetShortArrayElements(blockArray, nullptr);
        jbyte* lights = env->GetByteArrayElements(lightArray, nullptr);
        if (!blocks || !lights) {
            if (blocks) env->ReleaseShortArrayElements(blockArray, blocks, JNI_ABORT);
            if (lights) env->ReleaseByteArrayElements(lightArray, lights, JNI_ABORT);
            return JNI_FALSE;
        }

        const uint16_t* blockPtr = reinterpret_cast<const uint16_t*>(blocks);
        const uint8_t* lightPtr = reinterpret_cast<const uint8_t*>(lights);

        ChunkContext* ctx = pipeline_get_chunk_context_retained((int32_t)chunkX, (int32_t)chunkZ);
        if (!ctx) {
            ChunkContext* candidate = pipeline_create_chunk_context((int32_t)chunkX, (int32_t)chunkZ);
            if (pipeline_replace_chunk_payload(candidate, blockPtr, lightPtr) &&
                pipeline_register_chunk(candidate)) {
                ctx = candidate;
                pipeline_retain_chunk_context(ctx);
            } else {
                pipeline_release_chunk_context(candidate);
                ctx = pipeline_get_chunk_context_retained((int32_t)chunkX, (int32_t)chunkZ);
                if (ctx) {
                    pipeline_replace_chunk_payload(ctx, blockPtr, lightPtr);
                }
            }
        } else {
            pipeline_replace_chunk_payload(ctx, blockPtr, lightPtr);
        }

        env->ReleaseShortArrayElements(blockArray, blocks, JNI_ABORT);
        env->ReleaseByteArrayElements(lightArray, lights, JNI_ABORT);

        if (!ctx) return JNI_FALSE;

        pipeline_release_chunk_context(ctx);
        return JNI_TRUE;
    }

    JNIEXPORT jboolean JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineTryDispatchChunk(
            JNIEnv*, jclass, jint chunkX, jint chunkZ) {
        ensure_chunk_pipeline_started(0);

        // Try-dispatch target + all 8 neighbors. A neighbor chunk might have
        // already been at STATE_1_RECONSTRUCTED and tried to dispatch earlier,
        // but failed because THIS chunk wasn't loaded yet. Now that we are
        // loaded, the neighbor's blocked dispatch should re-evaluate. Without
        // this poke, distant chunks that arrive out-of-order can sit forever
        // in STATE_1 because nothing else nudges them, producing visible holes
        // in the rendered terrain along the chunk-load frontier.
        bool target_dispatched = false;
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                ChunkContext* ctx = pipeline_get_chunk_context_retained(
                    (int32_t)chunkX + dx,
                    (int32_t)chunkZ + dz);
                if (!ctx) continue;
                pipeline_try_dispatch_mesh_job(&g_chunk_pipeline_hw, ctx);
                if (dx == 0 && dz == 0) target_dispatched = true;
                pipeline_release_chunk_context(ctx);
            }
        }
        return target_dispatched ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineUploadBlockProperties(
            JNIEnv* env, jclass, jbyteArray properties) {
        if (!properties) return;

        jsize length = env->GetArrayLength(properties);
        if (length != (jsize)BLOCK_FACE_PROPERTY_TABLE_BYTES) {
            PLUTO_LOG("nPipelineUploadBlockProperties: bad size %d expected %llu",
                      (int)length,
                      (unsigned long long)BLOCK_FACE_PROPERTY_TABLE_BYTES);
            return;
        }

        jbyte* data = env->GetByteArrayElements(properties, nullptr);
        if (!data) return;
        pipeline_upload_block_properties(reinterpret_cast<const uint8_t*>(data), BLOCK_FACE_PROPERTY_TABLE_BYTES);
        env->ReleaseByteArrayElements(properties, data, JNI_ABORT);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineInvalidateChunks(
            JNIEnv* env, jclass, jlongArray keysArray, jlongArray valuesArray, jint count) {
        if (!keysArray || !valuesArray || count <= 0) return;
        jsize keyLen = env->GetArrayLength(keysArray);
        jsize valueLen = env->GetArrayLength(valuesArray);
        if (keyLen < count || valueLen < count) {
            PLUTO_LOG("nPipelineInvalidateChunks: bad array lengths keys=%d values=%d count=%d",
                      (int)keyLen, (int)valueLen, (int)count);
            return;
        }

        ensure_chunk_pipeline_started(0);

        jlong* keys = env->GetLongArrayElements(keysArray, nullptr);
        jlong* values = env->GetLongArrayElements(valuesArray, nullptr);
        if (!keys || !values) {
            if (keys) env->ReleaseLongArrayElements(keysArray, keys, JNI_ABORT);
            if (values) env->ReleaseLongArrayElements(valuesArray, values, JNI_ABORT);
            return;
        }

        pipeline_apply_dirty_block_updates(
            &g_chunk_pipeline_hw,
            reinterpret_cast<const uint64_t*>(keys),
            reinterpret_cast<const uint64_t*>(values),
            static_cast<size_t>(count));

        env->ReleaseLongArrayElements(keysArray, keys, JNI_ABORT);
        env->ReleaseLongArrayElements(valuesArray, values, JNI_ABORT);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineUploadChunkLight(
            JNIEnv* env, jclass, jint chunkX, jint chunkZ, jbyteArray lightArray) {
        if (!lightArray) return;
        jsize length = env->GetArrayLength(lightArray);
        if (length < (jsize)CHUNK_BLOCK_COUNT) {
            PLUTO_LOG("nPipelineUploadChunkLight: payload too small lights=%d required=%u",
                      (int)length, CHUNK_BLOCK_COUNT);
            return;
        }

        ensure_chunk_pipeline_started(0);

        jbyte* lights = env->GetByteArrayElements(lightArray, nullptr);
        if (!lights) return;
        pipeline_upload_chunk_light(
            &g_chunk_pipeline_hw,
            static_cast<int32_t>(chunkX),
            static_cast<int32_t>(chunkZ),
            reinterpret_cast<const uint8_t*>(lights));
        env->ReleaseByteArrayElements(lightArray, lights, JNI_ABORT);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineUnregisterChunk(
            JNIEnv*, jclass, jint chunkX, jint chunkZ) {
        pipeline_unregister_chunk(&g_chunk_pipeline_hw, (int32_t)chunkX, (int32_t)chunkZ);
    }

    JNIEXPORT jboolean JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineConfigureRenderer(
            JNIEnv*, jclass,
            jlong geometryFacesPtr,
            jint geometryFaceCapacity,
            jlong chunkMetadataPtr,
            jlong drawCommandsPtr,
            jint maxRegisteredChunks,
            jint worldMinY) {
        ensure_chunk_pipeline_started(0);
        bool ok = pipeline_configure_renderer(
            &g_chunk_pipeline_hw,
            reinterpret_cast<uint64_t*>(static_cast<uintptr_t>(geometryFacesPtr)),
            static_cast<uint32_t>(geometryFaceCapacity),
            reinterpret_cast<ChunkMetadataGpu*>(static_cast<uintptr_t>(chunkMetadataPtr)),
            reinterpret_cast<DrawArraysIndirectCommand*>(static_cast<uintptr_t>(drawCommandsPtr)),
            static_cast<uint32_t>(maxRegisteredChunks),
            static_cast<int32_t>(worldMinY));
        return ok ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineBeginFrame(
            JNIEnv*, jclass) {
        ensure_chunk_pipeline_started(0);
        return static_cast<jint>(pipeline_begin_frame(&g_chunk_pipeline_hw));
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineEndFrame(
            JNIEnv*, jclass) {
        pipeline_end_frame(&g_chunk_pipeline_hw);
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineRendererMaxChunks(
            JNIEnv*, jclass) {
        return static_cast<jint>(pipeline_renderer_max_chunks(&g_chunk_pipeline_hw));
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineRendererDrawCount(
            JNIEnv*, jclass) {
        return static_cast<jint>(pipeline_renderer_draw_count(&g_chunk_pipeline_hw));
    }

    // Returns mismatch count. Fills out_buf with packed mismatch records
    // (3 longs each: slot, ctx_xz_packed, meta_xz_packed) up to out_buf
    // capacity. active_out_buf[0], if length>=1, receives the active-slot
    // scanned count for logging context.
    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineRunMetadataAudit(
            JNIEnv* env, jclass, jlongArray out_buf, jintArray active_out_buf) {
        if (!out_buf) return 0;
        jsize buf_len = env->GetArrayLength(out_buf);
        uint32_t max_entries = static_cast<uint32_t>(buf_len / 3);
        if (max_entries == 0) return 0;

        std::vector<int64_t> tmp(static_cast<size_t>(max_entries) * 3, 0);
        uint32_t active = 0;
        uint32_t mismatches = pipeline_run_metadata_audit(
            &g_chunk_pipeline_hw,
            tmp.data(),
            max_entries,
            &active);

        env->SetLongArrayRegion(out_buf, 0,
                                static_cast<jsize>(std::min<uint32_t>(mismatches, max_entries) * 3u),
                                reinterpret_cast<const jlong*>(tmp.data()));
        if (active_out_buf && env->GetArrayLength(active_out_buf) >= 1) {
            jint a = static_cast<jint>(active);
            env->SetIntArrayRegion(active_out_buf, 0, 1, &a);
        }
        return static_cast<jint>(mismatches);
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelinePendingSwapCount(
            JNIEnv*, jclass) {
        return static_cast<jint>(pipeline_pending_swap_count(&g_chunk_pipeline_hw));
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineActiveMeshJobCount(
            JNIEnv*, jclass) {
        return static_cast<jint>(pipeline_active_mesh_job_count(&g_chunk_pipeline_hw));
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nPipelineCountCompiledColumns(
            JNIEnv* env, jclass, jlongArray keysArray, jint count) {
        if (!keysArray || count <= 0) return 0;
        jsize keyLen = env->GetArrayLength(keysArray);
        if (keyLen < count) return 0;

        jlong* keys = env->GetLongArrayElements(keysArray, nullptr);
        if (!keys) return 0;
        uint32_t compiled = pipeline_count_compiled_chunks(
            reinterpret_cast<const uint64_t*>(keys),
            static_cast<size_t>(count));
        env->ReleaseLongArrayElements(keysArray, keys, JNI_ABORT);
        return static_cast<jint>(compiled);
    }

    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nBuildSectionMesh(
            JNIEnv* env, jclass, jlong enginePtr,
            jobject blockDataBuf, jobject outVertsBuf, jint maxVerts,
            jfloat ox, jfloat oy, jfloat oz)
    {
        ComputeEngine* engine = reinterpret_cast<ComputeEngine*>((uintptr_t)enginePtr);
        if (!engine || !blockDataBuf || !outVertsBuf) return -1;
        const uint32_t* blockData = (const uint32_t*)env->GetDirectBufferAddress(blockDataBuf);
        ComputeEngine::GpuVertex* outVerts = (ComputeEngine::GpuVertex*)env->GetDirectBufferAddress(outVertsBuf);
        if (!blockData || !outVerts) { PLUTO_LOG("nBuildSectionMesh: null buffer address"); return -1; }
        jlong blockCap = env->GetDirectBufferCapacity(blockDataBuf);
        constexpr int REQUIRED_BLOCK_BYTES = 18 * 18 * 18 * (int)sizeof(uint32_t);
        if (blockCap < REQUIRED_BLOCK_BYTES) {
            PLUTO_LOG("nBuildSectionMesh: blockData buffer too small (%lld < %d)",
                      (long long)blockCap, REQUIRED_BLOCK_BYTES);
            return -1;
        }
        cudaSetDevice(0); // Establish CUDA context on this thread
        return (jint)engine->buildSectionMesh(blockData, outVerts, (int)maxVerts,
                                              (float)ox, (float)oy, (float)oz);
    }

    // ── Phase 1: Native multi-threaded CPU mesher ─────────────────────────────
    // Initializes a dedicated C++ ThreadPool for chunk meshing. Idempotent.
    // threadCount=0 ⇒ auto-size to (hardware_concurrency / 2).
    // Independent of CUDA — this path stays alive even if no GPU engine exists.
    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nInitNativeMesher(
            JNIEnv* env, jclass, jint threadCount)
    {
        (void)env;
        plutonium::initNativeMesher((int)threadCount);
        PLUTO_LOG("nInitNativeMesher: native mesher pool ready (requested threads=%d, auto-sized if 0)",
                  (int)threadCount);
    }

    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nShutdownNativeMesher(
            JNIEnv* env, jclass)
    {
        (void)env;
        plutonium::shutdownNativeMesher();
        PLUTO_LOG("nShutdownNativeMesher: pool torn down");
    }

    // Phase 2: upload the block-state-ID to UV table built by Java's TextureAtlasMirror.
    // Direct buffer must contain rectCount * sizeof(FaceUVSet) bytes.
    JNIEXPORT void JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nUploadTextureTable(
            JNIEnv* env, jclass, jobject uvBuffer, jint rectCount)
    {
        if (!uvBuffer) {
            PLUTO_LOG("nUploadTextureTable: null buffer");
            return;
        }
        const plutonium::FaceUVSet* data =
            (const plutonium::FaceUVSet*)env->GetDirectBufferAddress(uvBuffer);
        if (!data) {
            PLUTO_LOG("nUploadTextureTable: buffer not direct");
            return;
        }
        jlong cap = env->GetDirectBufferCapacity(uvBuffer);
        if (cap < (jlong)rectCount * (jlong)sizeof(plutonium::FaceUVSet)) {
            PLUTO_LOG("nUploadTextureTable: capacity %lld < required %lld",
                      (long long)cap,
                      (long long)((jlong)rectCount * (jlong)sizeof(plutonium::FaceUVSet)));
            return;
        }
        plutonium::uploadTextureTable(data, (int)rectCount);
        // The active native-vanilla builder uses the CPU mesher table above.
        // The old CUDA mesh kernel still has its legacy 4-float UVRect format,
        // so do not feed it this wider baked-quad table.
        PLUTO_LOG("nUploadTextureTable: %d baked face-UV entries installed", (int)rectCount);
    }

    // Build a face-culled section mesh on native CPU threads, NOT on the JVM.
    // blockData18: direct ByteBuffer, exactly 18x18x18 int32 block-state IDs.
    // outVerts:    direct ByteBuffer, capacity >= maxVerts * sizeof(MeshVertex)
    // Returns number of vertices written, 0 for empty section, -1 on error/overflow.
    JNIEXPORT jint JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nNativeMeshSection(
            JNIEnv* env, jclass,
            jobject blockData18, jobject outVerts, jint maxVerts,
            jfloat ox, jfloat oy, jfloat oz)
    {
        if (!blockData18 || !outVerts) {
            PLUTO_LOG("nNativeMeshSection: null buffer object");
            return -1;
        }
        const uint32_t* blocks = (const uint32_t*)env->GetDirectBufferAddress(blockData18);
        plutonium::MeshVertex* out = (plutonium::MeshVertex*)env->GetDirectBufferAddress(outVerts);
        if (!blocks || !out) {
            PLUTO_LOG("nNativeMeshSection: GetDirectBufferAddress returned null (buffer not direct?)");
            return -1;
        }
        jlong blockCap = env->GetDirectBufferCapacity(blockData18);
        jlong outCap   = env->GetDirectBufferCapacity(outVerts);
        constexpr int REQUIRED_BLOCK_BYTES = 18 * 18 * 18 * (int)sizeof(uint32_t);
        if (blockCap < REQUIRED_BLOCK_BYTES) {
            PLUTO_LOG("nNativeMeshSection: blockData buffer too small (%lld < %d)",
                      (long long)blockCap, REQUIRED_BLOCK_BYTES);
            return -1;
        }
        if (outCap < (jlong)maxVerts * (jlong)sizeof(plutonium::MeshVertex)) {
            PLUTO_LOG("nNativeMeshSection: outVerts capacity %lld < required %lld",
                      (long long)outCap,
                      (long long)((jlong)maxVerts * (jlong)sizeof(plutonium::MeshVertex)));
            return -1;
        }
        return (jint)plutonium::meshSectionParallel(
            blocks, out, (int)maxVerts, (float)ox, (float)oy, (float)oz);
    }

    JNIEXPORT jobjectArray JNICALL
        Java_com_plutonium_backbone_bridge_NativeInterface_nDrainNativeLogs(JNIEnv* env, jclass) {
        int count = 0;
        char** msgs = pluto_log_queue_drain(&count);
        if (!msgs || count <= 0) {
            if (msgs) pluto_log_queue_free_array(msgs);
            return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
        }
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
        for (int i = 0; i < count; ++i) {
            const char* s = msgs[i] ? msgs[i] : "";
            jstring js = env->NewStringUTF(s);
            env->SetObjectArrayElement(result, i, js);
            env->DeleteLocalRef(js);
            pluto_log_queue_free_string(msgs[i]);
        }
        pluto_log_queue_free_array(msgs);
        return result;
    }

} // extern "C"
