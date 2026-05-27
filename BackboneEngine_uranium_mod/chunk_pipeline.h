#pragma once

#include "ThreadPool.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

static constexpr int32_t CHUNK_WIDTH_CORE = 16;
static constexpr int32_t CHUNK_LENGTH_CORE = 16;
static constexpr int32_t CHUNK_HEIGHT = 384;
static constexpr uint32_t CHUNK_BLOCK_COUNT =
    CHUNK_WIDTH_CORE * CHUNK_LENGTH_CORE * CHUNK_HEIGHT;
static constexpr uint32_t MAX_FACES_PER_CHUNK =
    CHUNK_WIDTH_CORE * CHUNK_LENGTH_CORE * CHUNK_HEIGHT * 6;
static constexpr uint16_t BLOCK_AIR = 0;
static constexpr uint32_t INVALID_POOL_SLOT = 0xFFFFFFFFu;
static constexpr uint32_t MAX_CONCURRENT_UPDATES = 256;
static constexpr size_t BLOCK_FACE_PROPERTY_TABLE_BYTES = 65536ull * 6ull;
static constexpr uint32_t FRAME_LAG = 3;
static constexpr uint32_t PIPELINE_RENDER_LAYERS = 3;

static constexpr uint8_t PROP_PASS_OPAQUE = 0x01u;
static constexpr uint8_t PROP_PASS_CUTOUT = 0x02u;
static constexpr uint8_t PROP_PASS_TRANSLUCENT = 0x04u;
static constexpr uint8_t PROP_OCCLUDES = 0x08u;
static constexpr uint8_t PROP_PASS_MASK =
    PROP_PASS_OPAQUE | PROP_PASS_CUTOUT | PROP_PASS_TRANSLUCENT;

#define PACK_BLOCK_COORD(x, y, z) \
    (((uint32_t)(x) << 22) | ((uint32_t)(y) << 4) | (uint32_t)(z))
#define UNPACK_X(p) (((p) >> 22) & 0x0Fu)
#define UNPACK_Y(p) (((p) >> 4) & 0x1FFu)
#define UNPACK_Z(p) ((p) & 0x0Fu)

#define PACK_FACE(id, face, x, y, z, ao) ( \
    ((uint64_t)(id) << 48) | \
    ((uint64_t)(face) << 45) | \
    ((uint64_t)(y) << 35) | \
    ((uint64_t)(z) << 27) | \
    ((uint64_t)(x) << 19) | \
    ((uint64_t)(ao)) \
)

enum ChunkState : uint8_t {
    STATE_0_EMPTY = 0,
    STATE_1_RECONSTRUCTED = 1,
    STATE_2_HALO_READY = 2,
    STATE_3_MESH_READY = 3,
    STATE_4_COMPILED = 4
};

using ChunkStateAtomic = std::atomic<uint8_t>;
using ChunkUIntAtomic = std::atomic<uint32_t>;

struct ChunkContext {
    ChunkStateAtomic state{STATE_0_EMPTY};

    int32_t x = 0;
    int32_t z = 0;

    ChunkUIntAtomic draw_slot_id{INVALID_POOL_SLOT};
    ChunkUIntAtomic geometry_resource_offset{INVALID_POOL_SLOT};
    ChunkUIntAtomic geometry_resource_offset_size{0};

    std::atomic<uint16_t*> h_block_ids{nullptr};
    std::atomic<uint8_t*> h_light_ids{nullptr};

    std::mutex update_mutex;
    std::vector<uint32_t> dirty_block_coords;

    std::atomic<uint32_t> mesh_generation{0};
    std::atomic<uint32_t> refcount{1};
    std::atomic<bool> retired{false};
};

struct ChunkSnapshot {
    std::vector<uint16_t> blocks;
    std::vector<uint8_t> lights;
    bool allocated = false;

    ChunkSnapshot();
};

struct WorkerWorkspace {
    std::vector<uint64_t> opaque_buf;
    std::vector<uint64_t> cutout_buf;
    std::vector<uint64_t> translucent_buf;

    uint32_t opaque_count = 0;
    uint32_t cutout_count = 0;
    uint32_t translucent_count = 0;

    ChunkSnapshot target_snap;
    std::array<ChunkSnapshot, 8> neighbor_snaps;
};

struct MeshJob {
    ChunkContext* target = nullptr;
    ChunkContext* n_px = nullptr;
    ChunkContext* n_nx = nullptr;
    ChunkContext* n_pz = nullptr;
    ChunkContext* n_nz = nullptr;
    ChunkContext* n_px_pz = nullptr;
    ChunkContext* n_px_nz = nullptr;
    ChunkContext* n_nx_pz = nullptr;
    ChunkContext* n_nx_nz = nullptr;
    uint32_t generation = 0;
    uint32_t pool_slot_idx = INVALID_POOL_SLOT;
};

struct DrawArraysIndirectCommand {
    uint32_t count = 0;
    uint32_t instanceCount = 0;
    uint32_t first = 0;
    uint32_t baseInstance = 0;
};

struct ChunkMetadataGpu {
    int32_t origin_x = 0;
    int32_t origin_y = 0;
    int32_t origin_z = 0;
    uint32_t face_base_offset = 0;
};

enum class SwapKind : uint8_t {
    MeshCommit = 0,
    ClearSlot = 1
};

struct PendingCommandSwap {
    ChunkContext* chunk_ctx = nullptr;
    uint32_t slot_id = INVALID_POOL_SLOT;

    uint32_t new_opaque_count = 0;
    uint32_t new_cutout_count = 0;
    uint32_t new_translucent_count = 0;
    uint32_t new_first_index = INVALID_POOL_SLOT;

    uint32_t old_offset_to_free = INVALID_POOL_SLOT;
    uint32_t old_size_to_free = 0;

    uint32_t pool_slot_idx = INVALID_POOL_SLOT;
    uint32_t generation = 0;
    SwapKind kind = SwapKind::MeshCommit;
};

struct GeometryFreeSegment {
    uint32_t first_index = INVALID_POOL_SLOT;
    uint32_t count = 0;
};

struct DeferredFreeBatch {
    std::vector<GeometryFreeSegment> segments;
    void* fence = nullptr;
};

struct PipelineHardwareContext {
    std::unique_ptr<ThreadPool> mesh_pool;
    std::atomic<uint32_t> active_mesh_jobs{0};
    bool pool_slot_busy[MAX_CONCURRENT_UPDATES]{};
    std::mutex pool_mutex;
    std::atomic<bool> shutting_down{false};

    std::atomic<bool> renderer_configured{false};
    uint64_t* persistent_geometry_faces_ptr = nullptr;
    uint32_t geometry_face_capacity = 0;
    uint32_t geometry_bump_head = 0;
    std::map<uint32_t, uint32_t> geometry_free_by_offset;
    std::mutex geometry_mutex;

    ChunkMetadataGpu* persistent_chunk_metadata_ptr = nullptr;
    DrawArraysIndirectCommand* persistent_draw_commands_ptr = nullptr;
    uint32_t max_registered_chunks = 0;
    int32_t world_min_y = -64;

    std::vector<ChunkContext*> slot_to_chunk_registry;
    std::vector<DrawArraysIndirectCommand> canonical_draw_commands;
    // Canonical CPU mirror of per-slot chunk metadata. Mutated by worker threads
    // (under renderer_mutex) and copied to the active frame page in begin_frame.
    // The persistent SSBO is FRAME_LAG-buffered so writes don't race with the
    // GPU still reading older frames' commands at the same slot.
    std::vector<ChunkMetadataGpu> canonical_chunk_metadata;
    std::mutex renderer_mutex;
    std::atomic<uint32_t> active_draw_command_count{0};

    std::deque<PendingCommandSwap> pending_command_swaps;
    std::mutex swap_mutex;

    std::vector<DeferredFreeBatch> deferred_free_batches;
    std::vector<GeometryFreeSegment> current_frame_deferred_segments;
    void* frame_fences[FRAME_LAG]{};
    uint32_t current_frame_index = 0;
    std::atomic<int32_t> last_renderable_frame_index{-1};
    bool frame_started = false;
};

ChunkContext* pipeline_create_chunk_context(int32_t chunk_x, int32_t chunk_z);
void pipeline_destroy_chunk_context_for_tests(ChunkContext* ctx);
bool pipeline_replace_chunk_payload(
    ChunkContext* ctx,
    const uint16_t* block_ids,
    const uint8_t* light_ids);
bool pipeline_replace_chunk_lights(ChunkContext* ctx, const uint8_t* light_ids);

void pipeline_hardware_init(PipelineHardwareContext* hw, uint32_t worker_count = 0);
void pipeline_hardware_destroy(PipelineHardwareContext* hw);
bool pipeline_configure_renderer(
    PipelineHardwareContext* hw,
    uint64_t* geometry_faces_ptr,
    uint32_t geometry_face_capacity,
    ChunkMetadataGpu* chunk_metadata_ptr,
    DrawArraysIndirectCommand* draw_commands_ptr,
    uint32_t max_registered_chunks,
    int32_t world_min_y);
int32_t pipeline_begin_frame(PipelineHardwareContext* hw);
void pipeline_end_frame(PipelineHardwareContext* hw);
uint32_t pipeline_renderer_max_chunks(PipelineHardwareContext* hw);
uint32_t pipeline_renderer_draw_count(PipelineHardwareContext* hw);

// Metadata audit. Scans the first 200 active draw slots, counts how many have
// canonical_chunk_metadata.origin that disagrees with their owning ChunkContext's
// (x,z). Writes up to `max_out` mismatches into out_data[] as 3 longs per entry:
//   [3*i+0]: slot id
//   [3*i+1]: ((int64)ctx_x << 32) | (ctx_z & 0xFFFFFFFF)
//   [3*i+2]: ((int64)meta_origin_x/16 << 32) | (meta_origin_z/16 & 0xFFFFFFFF)
// Returns total mismatch count (may exceed entries written if out_data is too small).
// active_out_count, if non-null, receives the count of non-null slots scanned.
uint32_t pipeline_run_metadata_audit(
    PipelineHardwareContext* hw,
    int64_t* out_data,
    uint32_t max_out,
    uint32_t* active_out_count);
uint32_t pipeline_pending_swap_count(PipelineHardwareContext* hw);
uint32_t pipeline_active_mesh_job_count(PipelineHardwareContext* hw);
uint32_t pipeline_count_compiled_chunks(const uint64_t* keys, size_t count);

bool pipeline_register_chunk(ChunkContext* ctx);
void pipeline_unregister_chunk(PipelineHardwareContext* hw, int32_t chunk_x, int32_t chunk_z);
ChunkContext* pipeline_get_chunk_context_retained(int32_t chunk_x, int32_t chunk_z);

bool pipeline_retain_chunk_context(ChunkContext* ctx);
void pipeline_release_chunk_context(ChunkContext* ctx);

bool is_neighbor_ready(ChunkContext* neighbor);
void pipeline_try_dispatch_mesh_job(PipelineHardwareContext* hw, ChunkContext* target);
void pipeline_cascade_and_try_dispatch_around(
    PipelineHardwareContext* hw,
    int32_t chunk_x,
    int32_t chunk_z,
    uint32_t block_packed);
void pipeline_apply_dirty_block_updates(
    PipelineHardwareContext* hw,
    const uint64_t* keys,
    const uint64_t* values,
    size_t count);
void pipeline_upload_chunk_light(
    PipelineHardwareContext* hw,
    int32_t chunk_x,
    int32_t chunk_z,
    const uint8_t* light_ids);
void pipeline_upload_block_properties(const uint8_t* data, size_t size);
bool should_render_face(uint16_t block_id, uint16_t neighbor_id, uint32_t face_dir);

void load_snapshot_safe(
    ChunkContext* src,
    ChunkSnapshot& dst,
    uint32_t* out_generation = nullptr);

uint16_t get_block_at_isolated(WorkerWorkspace* ws, int32_t lx, int32_t ly, int32_t lz);
uint8_t get_sky_light_at_isolated(WorkerWorkspace* ws, int32_t lx, int32_t ly, int32_t lz);
uint8_t get_block_light_at_isolated(WorkerWorkspace* ws, int32_t lx, int32_t ly, int32_t lz);

uint32_t cpu_mesh_extract_isolated(
    WorkerWorkspace* ws,
    uint32_t* out_opaque_count,
    uint32_t* out_cutout_count,
    uint32_t* out_translucent_count);

void release_pool_slot_if_valid(PipelineHardwareContext* hw, uint32_t slot);
