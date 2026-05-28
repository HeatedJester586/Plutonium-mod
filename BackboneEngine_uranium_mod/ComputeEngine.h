#pragma once
#include <cuda_runtime.h>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <functional>
#include <unordered_map>
#include <vector>
#include <queue>
#include <chrono>
#include "ThreadPool.h"

#define CHUNK_VOLUME (16 *384 *16)

#pragma pack(push,1)
struct VoxelBlock {
 unsigned char blockID;
 unsigned char lightLevel;
 unsigned char meta;
};
#pragma pack(pop)

#pragma pack(push, 1)
struct PlutoniumBytecodeHeader {
 int32_t magic;             // 0x504C544E
 int32_t version;           // 1
 int32_t instructionCount;
 int32_t instructionStride; // 24
 int32_t dataPoolOffset;
 int32_t dataPoolBytes;
};

struct PlutoniumInstruction {
 int32_t opcodeId;
 int32_t arg1;
 int32_t arg2;
 int32_t dataOffset;
 double  value;
};
#pragma pack(pop)

enum class PlutoniumOpcode : int32_t {
 CONSTANT = 0,
 ADD,
 MUL,
 MIN,
 MAX,
 ABS,
 SQUARE,
 CUBE,
 HALF_NEGATIVE,
 QUARTER_NEGATIVE,
 SQUEEZE,
 MARKER,
 CLAMP,
 NOISE,
 SHIFT,
 SHIFT_A,
 SHIFT_B,
 SHIFTED_NOISE,
 BLEND_ALPHA,
 BLEND_OFFSET,
 BLEND_DENSITY,
 BEARDIFIER_MARKER,
 SPLINE,
 RANGE_CHOICE,
 Y_CLAMPED_GRADIENT,
 MUL_OR_ADD,
 WEIRD_SCALED_SAMPLER,
 BLENDED_NOISE
};

// Add NativeEntity struct
struct NativeEntity {
 int id;
 float x, y, z, yaw, pitch;
};

// Mesh buffer pool entry
struct PooledMeshBuffer {
 void* hostBuffer;  // GpuVertex* - declared as void* to avoid forward reference
 bool inUse;
};

// Block batch buffer pool entry
struct PooledBlockBatchBuffer {
 unsigned char* hostBuffer;
 size_t capacity;
 bool inUse;
};

class ComputeEngine {
public:
 ComputeEngine();
 ~ComputeEngine();

 // Now accepts width, height, and threadCount
 bool init(int deviceIndex, int width, int height, int threadCount);
 void* getPinnedWorldPtr();
 void syncFromJava();
 void startPhysics();
 void stop();

 std::mutex& getMutex() { return bufferMutex; }

 // New method to easily offload tasks to the CPU cores
 void submitCpuTask(std::function<void()> task) {
 if (cpuPool) cpuPool->enqueue(task);
 }

 // New method for chunk noise generation
 void* generateChunkNoise(int chunkX, int chunkZ, long seed);
 bool generateChunkNoiseInto(int chunkX, int chunkZ, long seed, void* outBuffer, size_t outBytes);
 bool evaluateDensityPoints(const int32_t* coordsXYZ, double* outValues, int count);
 bool evaluateChunkDensityCells(int chunkX, int chunkZ, long seed, double* outValues, int count);

 /**
  * Batch variant: evaluate density cells for many chunks in a single CUDA
  * kernel launch. The per-chunk path used a 1225-thread kernel which barely
  * touched the GPU. This packs all chunks' cells into one launch sized
  * (chunkCount * 1225) threads — enough to actually saturate a modern GPU.
  *
  *   coordsXZ:    chunkCount * 2 int32_t pairs (chunkX, chunkZ)
  *   outValues:   chunkCount * PLUTONIUM_DENSITY_CELL_COUNT doubles, contiguous
  *
  * Scratch register memory is allocated lazily based on the active AST's real
  * instruction count, not the static MAX, so memory pressure stays sane.
  */
 bool evaluateChunkDensityCellsBatch(const int32_t* coordsXZ, double* outValues, int chunkCount, long seed);

 // Thread-safe block update (must stay consistent with d_front for physics kernel reads)
 void setBlockNative(int x, int y, int z, unsigned char id, unsigned char meta = 0, unsigned char light = 15);

 // Entity AI processing
 void processEntityAI(int id, float x, float y, float z, float yaw, float pitch);

 // Add this for JNI bridge
 void* generateChunkOnGpu(int cx, int cz, long seed);

 // Allow external terrain upload
 void uploadWorldData(int cx, int cz, void* data);
 void uploadAST(const void* buffer, size_t size);

 // C2ME-style state management
 std::atomic<bool> needsUpdate{false};

 // Declare updateBlockBatch in the public section
 void updateBlockBatch(const void* buffer, int count);

 /**
  * Copy payload then return; worker threads apply to pinned host buffer (one lock per batch).
  * Keeps JNI/render thread light — GPU sees updates on next physics H2D copy.
  */
 void enqueueJniBlockBatch(std::vector<unsigned char>&& payload, int count);

 // GPU mesh building — 28-byte vertex matching vanilla's
 // DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP so the kernel output drops
 // straight into the textured chunk shader used by GpuMeshManager.
#pragma pack(push, 1)
 struct GpuVertex {
  float x, y, z;
  uint32_t color;
  float u, v;
  uint16_t blockLight, skyLight;
 };
#pragma pack(pop)
 static_assert(sizeof(GpuVertex) == 28, "GpuVertex must be 28 bytes");
 static_assert(offsetof(GpuVertex, x) == 0, "GpuVertex.x offset must be 0");
 static_assert(offsetof(GpuVertex, color) == 12, "GpuVertex.color offset must be 12");
 static_assert(offsetof(GpuVertex, u) == 16, "GpuVertex.u offset must be 16");
 static_assert(offsetof(GpuVertex, blockLight) == 24, "GpuVertex.lightmap offset must be 24");
 static const int MAX_MESH_VERTS = 65536; // bounded section mesh output to avoid VRAM spikes
 static const int MESH_POOL_SIZE = 16; // Pool 16 mesh output buffers
 static const int BLOCK_BATCH_POOL_SIZE = 8; // Pool 8 block batch buffers
 int buildSectionMesh(
 const uint32_t* blockData18, // caller:18x18x18 uint32 block-state IDs, CPU
 GpuVertex* outVertsCPU, // caller: MAX_MESH_VERTS * sizeof(GpuVertex) bytes, CPU
 int maxVerts,
 float ox, float oy, float oz);
 
 // Mesh pool management
 ComputeEngine::GpuVertex* acquireMeshBuffer();
 void releaseMeshBuffer(ComputeEngine::GpuVertex* buffer);
 
 // Block batch pool management
 unsigned char* acquireBlockBatchBuffer(size_t size);
 void releaseBlockBatchBuffer(unsigned char* buffer);
 
 // Direct block batch application (called from JNI)
 void applyJniBlockBatch(const unsigned char* data, int count);

private:

 void physicsLoop();

 // Internal helper functions to organize the GPU work
 void runPhysicsKernel();
 void syncToPinnedMemory();
 
 // Pool management
 void initMeshPool();
 void initBlockBatchPool();
 void cleanupMeshPool();
 void cleanupBlockBatchPool();

 VoxelBlock* d_pinned_world;
 VoxelBlock* d_front;
 VoxelBlock* d_back;
 std::atomic<bool> running;
 std::thread physicsThread;
 std::mutex bufferMutex;

 ThreadPool* cpuPool; // Added ThreadPool

 // New members for chunk noise
 VoxelBlock* d_chunk_buffer;
 VoxelBlock* h_chunk_buffer;
 double* d_densityCells = nullptr;
 void* d_astBuffer = nullptr;
 double* d_astRegisterBuffer = nullptr;
 std::mutex chunkMutex;
 std::shared_mutex astBufferMutex;

 // Real instruction count for the currently uploaded AST. Captured from the
 // header at uploadAST time so the batch density kernel can size scratch
 // registers per-thread at the actual count rather than the static MAX.
 int32_t currentAstInstructionCount = 0;

 // Lazy-allocated scratch for evaluateChunkDensityCellsBatch. Re-sized when
 // the requested batch size grows. One big slab of doubles, indexed by the
 // kernel via (globalThreadIdx * instructionCount).
 double* d_batchAstRegisters = nullptr;
 size_t d_batchAstRegistersBytes = 0;
 int32_t* d_batchChunkCoords = nullptr;
 size_t d_batchChunkCoordsBytes = 0;
 double* d_batchDensityOut = nullptr;
 size_t d_batchDensityOutBytes = 0;
 cudaStream_t batchStream = nullptr;
 std::mutex batchMutex;

 struct DeviceChunkGenContext {
  VoxelBlock* d_chunk;
  double* d_densityCells;
  double* d_astRegisters;
  cudaStream_t stream;
  bool inUse;
 };
 static const int CHUNK_GEN_POOL_SIZE = 8;
 std::vector<DeviceChunkGenContext> chunkGenPool;
 std::mutex chunkGenPoolMutex;
 DeviceChunkGenContext* acquireChunkGenContext();
 void releaseChunkGenContext(DeviceChunkGenContext* ctx);

 // Dynamic stream dimensions
 int streamWidth;
 int streamHeight;

 // Mutex for pinned world buffer
 std::mutex worldMutex;

 // Entity storage
 std::unordered_map<int, NativeEntity> activeEntities;
 std::mutex entityMutex;

 // CUDA stream for async operations
 cudaStream_t m_stream;

 // Mesh building members
 unsigned char* d_meshBlocks;
 GpuVertex* d_meshVerts;
 int* d_meshVertCount;
 std::mutex meshBuildMutex;  // Separate from chunkMutex to reduce contention
 cudaEvent_t meshKernelCompleteEvent;  // For async mesh kernel completion

 // Zero-allocation device mesh pool: pre-allocated VRAM avoids the NVIDIA
 // driver's global allocation lock under concurrent worker threads.
 struct DeviceMeshContext {
  uint32_t* d_blocks;
  GpuVertex* d_verts;
  int* d_vertCount;
  bool inUse;
 };
 std::vector<DeviceMeshContext> deviceMeshPool;
 std::mutex deviceMeshPoolMutex;
 DeviceMeshContext* acquireDeviceMeshContext();
 void releaseDeviceMeshContext(DeviceMeshContext* ctx);
 
 // Buffer pools
 std::vector<PooledMeshBuffer> meshBufferPool;
 std::vector<PooledBlockBatchBuffer> blockBatchBufferPool;
 std::mutex meshPoolMutex;
 std::mutex blockBatchPoolMutex;
 
 // Timing and budget tracking
 std::atomic<long long> totalMeshBuildNanos{0};
 std::atomic<int> activeMeshTasks{0};
 const int MAX_CONCURRENT_MESH_TASKS = 4;  // Prevent queue overflow
 const long long MAX_MESH_BUILD_TIME_PER_FRAME_NS = 8000000LL;  // 8ms budget
};
