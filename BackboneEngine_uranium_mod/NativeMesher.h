// ─────────────────────────────────────────────────────────────────────────────
// NativeMesher — native C++ multi-threaded chunk section mesher.
//
// Phase 2 (current):
//   - Dedicated worker pool (independent of CUDA compute pool).
//   - Face-culled cube geometry.
//   - Vertex format matches vanilla DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
//     (28 bytes: 3 floats pos + 4 bytes color + 2 floats UV + 2 shorts lightmap).
//     This pairs with GameRenderer.getPositionColorTexLightmapShader on the Java side
//     so our output is sampler-compatible with vanilla's block texture atlas.
//   - UV rect per full 32-bit block-state ID populated from the Java side via
//     TextureAtlasMirror → JNI nUploadTextureTable.
//   - Lightmap currently hardcoded to "full daylight" (Phase 3 wires real light layers).
//
// Header-only so we don't need to update the .vcxproj. PlutoniumCore.cpp
// includes this and exposes the JNI surface.
// ─────────────────────────────────────────────────────────────────────────────
#pragma once

#include "ThreadPool.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <future>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

namespace plutonium {

// 28-byte vertex matching vanilla's POSITION_COLOR_TEX_LIGHTMAP layout.
// IMPORTANT: byte order and packing must match what OpenGL sees, so we use
// `#pragma pack(push, 1)` to defeat any compiler padding.
#pragma pack(push, 1)
struct MeshVertex {
    float x, y, z;                 // 12 - POSITION
    uint32_t color;                //  4 - COLOR bytes in RGBA order
    float u, v;                    //  8 - UV0 (block atlas)
    uint16_t blockLight, skyLight; //  4 - UV2 (lightmap; full bright = 0x00F0 each)
};
#pragma pack(pop)
static_assert(sizeof(MeshVertex) == 28, "MeshVertex must be 28 bytes (matches vanilla POSITION_COLOR_TEX_LIGHTMAP)");
static_assert(offsetof(MeshVertex, x) == 0, "MeshVertex.x offset must be 0");
static_assert(offsetof(MeshVertex, color) == 12, "MeshVertex.color offset must be 12");
static_assert(offsetof(MeshVertex, u) == 16, "MeshVertex.u offset must be 16");
static_assert(offsetof(MeshVertex, blockLight) == 24, "MeshVertex.lightmap offset must be 24");

// ── UV table ────────────────────────────────────────────────────────────────
// One UV set per full block-state ID. Java mirrors vanilla BakedQuad UVs for
// every face and expands them to this mesher's six triangle vertices.
struct FaceUVSet {
    float uv[6][6][2];
};
static_assert(sizeof(FaceUVSet) == 288, "FaceUVSet must be 288 bytes");

namespace detail {
    using UVTable = std::vector<FaceUVSet>;

    inline std::shared_ptr<const UVTable>& uvTable() {
        static std::shared_ptr<const UVTable> table =
            std::make_shared<const UVTable>(1, FaceUVSet{});
        return table;
    }
    inline std::mutex& uvTableMutex() {
        static std::mutex m;
        return m;
    }
    inline std::atomic<bool>& uvTablePopulated() {
        static std::atomic<bool> populated{false};
        return populated;
    }
}

// Replace the entire UV table with the supplied data. Mesh jobs take a
// shared_ptr snapshot once per section, so reload-time swaps cannot invalidate
// a running worker.
inline void uploadTextureTable(const FaceUVSet* data, int rectCount) {
    if (!data || rectCount <= 0) return;
    auto next = std::make_shared<detail::UVTable>(data, data + rectCount);
    std::lock_guard<std::mutex> lock(detail::uvTableMutex());
    detail::uvTable() = std::move(next);
    detail::uvTablePopulated().store(true, std::memory_order_release);
}

inline bool hasTextureTable() {
    return detail::uvTablePopulated().load(std::memory_order_acquire);
}

inline std::shared_ptr<const detail::UVTable> textureTableSnapshot() {
    std::lock_guard<std::mutex> lock(detail::uvTableMutex());
    return detail::uvTable();
}

inline const FaceUVSet& lookupUV(const std::shared_ptr<const detail::UVTable>& table, uint32_t id) {
    static const FaceUVSet missing{};
    if (!table || id >= table->size()) {
        return missing;
    }
    return (*table)[id];
}

// ── Singleton thread pool ────────────────────────────────────────────────────
namespace detail {
    inline std::unique_ptr<ThreadPool>& meshPool() {
        static std::unique_ptr<ThreadPool> pool;
        return pool;
    }
    inline std::mutex& meshPoolMutex() {
        static std::mutex m;
        return m;
    }
}

inline void initNativeMesher(int threadCount = 0) {
    std::lock_guard<std::mutex> lock(detail::meshPoolMutex());
    if (detail::meshPool()) return;
    if (threadCount <= 0) {
        unsigned int hw = std::thread::hardware_concurrency();
        if (hw == 0) hw = 4;
        unsigned int half = hw / 2u;
        threadCount = (int)((half < 2u) ? 2u : half);
    }
    detail::meshPool() = std::make_unique<ThreadPool>((size_t)threadCount);
}

inline void shutdownNativeMesher() {
    std::lock_guard<std::mutex> lock(detail::meshPoolMutex());
    detail::meshPool().reset();
}

inline bool isMeshPoolReady() {
    std::lock_guard<std::mutex> lock(detail::meshPoolMutex());
    return (bool)detail::meshPool();
}

// ── Face culling primitives ──────────────────────────────────────────────────
// Index into the padded 18^3 block buffer. dx,dy,dz are in [-1, 16].
// blockData[(dx+1) + (dy+1)*18 + (dz+1)*18*18]
static inline int idx18(int dx, int dy, int dz) {
    return (dx + 1) + (dy + 1) * 18 + (dz + 1) * 18 * 18;
}

static constexpr uint32_t CULL_ONLY_FLAG = 0x80000000u;
static constexpr uint32_t BLOCK_ID_MASK = 0x7FFFFFFFu;

static inline uint32_t blockStateId(uint32_t id) { return id & BLOCK_ID_MASK; }
static inline bool isAir(uint32_t id) { return blockStateId(id) == 0; }
static inline bool isCullOnly(uint32_t id) { return (id & CULL_ONLY_FLAG) != 0; }

// Lightmap "full bright" constant. Vanilla packs sky/block light each as 0..15,
// stored in the UV2 attribute as `(skyLight * 16, blockLight * 16)` → values up
// to 240 (0xF0). Phase 3 will compute real per-vertex light from the chunk's
// light layers; for Phase 2 we render everything at noon.
static constexpr unsigned short LIGHT_FULL_BRIGHT = 0x00F0;

static inline uint32_t packColorRGBA(uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    return (uint32_t)r
        | ((uint32_t)g << 8)
        | ((uint32_t)b << 16)
        | ((uint32_t)a << 24);
}

// Per-face vertex POSITIONS (6 verts = 2 triangles, CCW from outside).
// face: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
static const float FACES_POS[6][6][3] = {
    {{1,0,0},{1,1,0},{1,0,1}, {1,1,0},{1,1,1},{1,0,1}}, // +X
    {{0,0,0},{0,0,1},{0,1,0}, {0,1,0},{0,0,1},{0,1,1}}, // -X
    {{0,1,0},{0,1,1},{1,1,0}, {1,1,0},{0,1,1},{1,1,1}}, // +Y
    {{0,0,0},{1,0,0},{0,0,1}, {0,0,1},{1,0,0},{1,0,1}}, // -Y
    {{0,0,1},{1,0,1},{0,1,1}, {0,1,1},{1,0,1},{1,1,1}}, // +Z
    {{0,0,0},{0,1,0},{1,0,0}, {1,0,0},{0,1,0},{1,1,0}}, // -Z
};

// Per-face vertex UV CORNERS, in normalized [0..1] over the texture rect.
// Each entry is (u_frac, v_frac). Minecraft texture v=0 is the TOP of the
// sprite, v=1 is the bottom — same convention as the vanilla mesher.
// These were derived to match FACES_POS so the texture orientation is upright
// on every face (no upside-down sand, no sideways dirt).
// Vanilla-style directional face shading: +Y bright, -Y dark, sides mid.
// Matches BlockModelRenderer's constant per-face light multipliers so our
// terrain blends visually with vanilla-rendered blocks in the same scene.
static constexpr float FACE_SHADE[6] = { 0.6f, 0.6f, 1.0f, 0.5f, 0.8f, 0.8f }; // +X -X +Y -Y +Z -Z

// Write 6 verts for one face at dest[0..5]. Caller owns the slot.
static inline void emitFaceAt(MeshVertex* dest,
                               float bx, float by, float bz, int face,
                               const FaceUVSet& uv) {
    const unsigned char shade = (unsigned char)(FACE_SHADE[face] * 255.0f + 0.5f);
    const uint32_t color = packColorRGBA(shade, shade, shade, 0xFF);
    for (int i = 0; i < 6; ++i) {
        MeshVertex& vv = dest[i];
        vv.x = bx + FACES_POS[face][i][0];
        vv.y = by + FACES_POS[face][i][1];
        vv.z = bz + FACES_POS[face][i][2];
        vv.color = color;
        vv.u = uv.uv[face][i][0];
        vv.v = uv.uv[face][i][1];
        vv.blockLight = LIGHT_FULL_BRIGHT;
        vv.skyLight = LIGHT_FULL_BRIGHT;
    }
}

// Legacy sequential emitFace kept for meshSlab (single-threaded path).
static inline int emitFace(MeshVertex* out, int outIdx, int maxVerts,
                           float bx, float by, float bz, int face,
                           const FaceUVSet& uv) {
    if (outIdx + 6 > maxVerts) return -1;
    emitFaceAt(out + outIdx, bx, by, bz, face, uv);
    return outIdx + 6;
}

// Mesh a Y-slab of the section [yBegin, yEnd). Writes into outVerts starting
// at outStart, never past outLimit. Returns the new write index or -1 on
// overflow.
static inline int meshSlab(
    const uint32_t* blockData,
    const std::shared_ptr<const detail::UVTable>& uvTable,
    MeshVertex* outVerts,
    int outStart, int outLimit,
    int yBegin, int yEnd,
    float originX, float originY, float originZ)
{
    int outIdx = outStart;
    for (int y = yBegin; y < yEnd; ++y) {
        for (int z = 0; z < 16; ++z) {
            for (int x = 0; x < 16; ++x) {
                uint32_t id = blockData[idx18(x, y, z)];
                if (isAir(id) || isCullOnly(id)) continue;
                const FaceUVSet& uv = lookupUV(uvTable, blockStateId(id));
                float bx = originX + (float)x;
                float by = originY + (float)y;
                float bz = originZ + (float)z;

                if (isAir(blockData[idx18(x+1, y, z)])) {
                    outIdx = emitFace(outVerts, outIdx, outLimit, bx, by, bz, 0, uv);
                    if (outIdx < 0) return -1;
                }
                if (isAir(blockData[idx18(x-1, y, z)])) {
                    outIdx = emitFace(outVerts, outIdx, outLimit, bx, by, bz, 1, uv);
                    if (outIdx < 0) return -1;
                }
                if (isAir(blockData[idx18(x, y+1, z)])) {
                    outIdx = emitFace(outVerts, outIdx, outLimit, bx, by, bz, 2, uv);
                    if (outIdx < 0) return -1;
                }
                if (isAir(blockData[idx18(x, y-1, z)])) {
                    outIdx = emitFace(outVerts, outIdx, outLimit, bx, by, bz, 3, uv);
                    if (outIdx < 0) return -1;
                }
                if (isAir(blockData[idx18(x, y, z+1)])) {
                    outIdx = emitFace(outVerts, outIdx, outLimit, bx, by, bz, 4, uv);
                    if (outIdx < 0) return -1;
                }
                if (isAir(blockData[idx18(x, y, z-1)])) {
                    outIdx = emitFace(outVerts, outIdx, outLimit, bx, by, bz, 5, uv);
                    if (outIdx < 0) return -1;
                }
            }
        }
    }
    return outIdx;
}

inline int meshSection(
    const uint32_t* blockData, MeshVertex* outVerts, int maxVerts,
    float originX, float originY, float originZ)
{
    auto uvTable = textureTableSnapshot();
    return meshSlab(blockData, uvTable, outVerts, 0, maxVerts, 0, 16, originX, originY, originZ);
}

// Atomic-write parallel mesher: all Y-slab workers share one write pointer.
// Each worker atomically claims a 6-vert slot before writing, so output is
// naturally packed — no memmove needed after all slabs finish.
inline int meshSectionParallel(
    const uint32_t* blockData, MeshVertex* outVerts, int maxVerts,
    float originX, float originY, float originZ)
{
    if (!isMeshPoolReady()) {
        return meshSection(blockData, outVerts, maxVerts, originX, originY, originZ);
    }

    constexpr int SLAB_COUNT = 4;
    constexpr int ROWS_PER_SLAB = 16 / SLAB_COUNT;

    std::atomic<int>  writePos{0};
    std::atomic<bool> overflow{false};

    std::promise<void> promises[SLAB_COUNT];
    std::future<void>  futures[SLAB_COUNT];
    auto uvTable = textureTableSnapshot();
    for (int i = 0; i < SLAB_COUNT; ++i) {
        futures[i] = promises[i].get_future();
    }

    {
        std::lock_guard<std::mutex> lock(detail::meshPoolMutex());
        auto& pool = detail::meshPool();
        if (!pool) {
            return meshSection(blockData, outVerts, maxVerts, originX, originY, originZ);
        }
        for (int i = 0; i < SLAB_COUNT; ++i) {
            const int yBegin = i * ROWS_PER_SLAB;
            const int yEnd   = yBegin + ROWS_PER_SLAB;
            std::promise<void>* p = &promises[i];
            pool->enqueue([blockData, uvTable, outVerts, maxVerts,
                            yBegin, yEnd, originX, originY, originZ,
                            p, &writePos, &overflow]() {
                for (int y = yBegin; y < yEnd; ++y) {
                    if (overflow.load(std::memory_order_relaxed)) break;
                    for (int z = 0; z < 16; ++z) {
                        for (int x = 0; x < 16; ++x) {
                            uint32_t id = blockData[idx18(x, y, z)];
                            if (isAir(id) || isCullOnly(id)) continue;
                            const FaceUVSet& uv = lookupUV(uvTable, blockStateId(id));
                            float bx = originX + (float)x;
                            float by = originY + (float)y;
                            float bz = originZ + (float)z;
                            // neighbour offsets: +X -X +Y -Y +Z -Z
                            static const int ndx[6] = {1,-1,0,0,0,0};
                            static const int ndy[6] = {0,0,1,-1,0,0};
                            static const int ndz[6] = {0,0,0,0,1,-1};
                            for (int face = 0; face < 6; ++face) {
                                if (!isAir(blockData[idx18(x+ndx[face], y+ndy[face], z+ndz[face])])) continue;
                                // Atomically claim a 6-vert slot.
                                int slot = writePos.fetch_add(6, std::memory_order_relaxed);
                                if (slot + 6 > maxVerts) {
                                    writePos.fetch_sub(6, std::memory_order_relaxed);
                                    overflow.store(true, std::memory_order_relaxed);
                                    goto slab_done;
                                }
                                emitFaceAt(outVerts + slot, bx, by, bz, face, uv);
                            }
                        }
                    }
                }
                slab_done:
                p->set_value();
            });
        }
    }

    for (int i = 0; i < SLAB_COUNT; ++i) futures[i].get();
    if (overflow.load(std::memory_order_relaxed)) return -1;
    return writePos.load(std::memory_order_relaxed);
}

} // namespace plutonium
