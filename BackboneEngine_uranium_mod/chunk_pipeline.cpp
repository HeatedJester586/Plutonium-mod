#include "chunk_pipeline.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <shared_mutex>
#include <thread>
#include <unordered_map>
#include <utility>

#define PIPELINE_LOG(fmt, ...) \
    do { std::fprintf(stdout, "[Plutonium/Pipeline] " fmt "\n", ##__VA_ARGS__); std::fflush(stdout); } while (0)

namespace {

std::unordered_map<uint64_t, ChunkContext*> g_chunk_registry;
std::shared_mutex g_registry_mutex;
uint8_t g_block_face_properties[65536][6] = {};
std::atomic<bool> g_block_face_properties_uploaded{false};

using GLenumAlias = unsigned int;
using GLbitfieldAlias = unsigned int;
using GLuint64Alias = unsigned long long;
using GLsyncHandle = void*;

using PFN_glFenceSync = GLsyncHandle(APIENTRY*)(GLenumAlias, GLbitfieldAlias);
using PFN_glClientWaitSync = GLenumAlias(APIENTRY*)(GLsyncHandle, GLbitfieldAlias, GLuint64Alias);
using PFN_glDeleteSync = void(APIENTRY*)(GLsyncHandle);
using PFN_glMemoryBarrier = void(APIENTRY*)(GLbitfieldAlias);

PFN_glFenceSync p_glFenceSync = nullptr;
PFN_glClientWaitSync p_glClientWaitSync = nullptr;
PFN_glDeleteSync p_glDeleteSync = nullptr;
PFN_glMemoryBarrier p_glMemoryBarrier = nullptr;

static constexpr GLenumAlias GL_SYNC_GPU_COMMANDS_COMPLETE_VALUE = 0x9117u;
static constexpr GLbitfieldAlias GL_SYNC_FLUSH_COMMANDS_BIT_VALUE = 0x00000001u;
static constexpr GLenumAlias GL_ALREADY_SIGNALED_VALUE = 0x911Au;
static constexpr GLenumAlias GL_CONDITION_SATISFIED_VALUE = 0x911Cu;
static constexpr GLbitfieldAlias GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT_VALUE = 0x00004000u;
static constexpr GLuint64Alias FRAME_FENCE_TIMEOUT_NS = 1'000'000ull;

uint64_t pack_coords(int32_t x, int32_t z) {
    return static_cast<uint64_t>(static_cast<uint32_t>(x)) |
        (static_cast<uint64_t>(static_cast<uint32_t>(z)) << 32);
}

uint32_t default_worker_count() {
    uint32_t hw_threads = std::thread::hardware_concurrency();
    uint32_t count = hw_threads > 4 ? hw_threads - 2 : 2;
    return std::min(count, MAX_CONCURRENT_UPDATES);
}

uint32_t block_index(uint32_t lx, uint32_t ly, uint32_t lz) {
    return lx + (lz * CHUNK_WIDTH_CORE) + (ly * CHUNK_WIDTH_CORE * CHUNK_LENGTH_CORE);
}

bool ensure_gl_sync_loaded() {
    if (p_glFenceSync && p_glClientWaitSync && p_glDeleteSync && p_glMemoryBarrier) {
        return true;
    }

    p_glFenceSync = reinterpret_cast<PFN_glFenceSync>(wglGetProcAddress("glFenceSync"));
    p_glClientWaitSync = reinterpret_cast<PFN_glClientWaitSync>(wglGetProcAddress("glClientWaitSync"));
    p_glDeleteSync = reinterpret_cast<PFN_glDeleteSync>(wglGetProcAddress("glDeleteSync"));
    p_glMemoryBarrier = reinterpret_cast<PFN_glMemoryBarrier>(wglGetProcAddress("glMemoryBarrier"));

    const bool ok = p_glFenceSync && p_glClientWaitSync && p_glDeleteSync && p_glMemoryBarrier;
    if (!ok) {
        PIPELINE_LOG("OpenGL sync entry points unavailable; command page fencing disabled");
    }
    return ok;
}

uint32_t align_faces(uint32_t count) {
    return (count + 63u) & ~63u;
}

uint32_t acquire_pool_slot(PipelineHardwareContext* hw) {
    if (!hw) return INVALID_POOL_SLOT;
    std::lock_guard<std::mutex> lock(hw->pool_mutex);
    for (uint32_t i = 0; i < MAX_CONCURRENT_UPDATES; ++i) {
        if (!hw->pool_slot_busy[i]) {
            hw->pool_slot_busy[i] = true;
            return i;
        }
    }
    return INVALID_POOL_SLOT;
}

uint32_t geometry_allocate(PipelineHardwareContext* hw, uint32_t count) {
    if (!hw || count == 0 || !hw->persistent_geometry_faces_ptr) return INVALID_POOL_SLOT;
    const uint32_t size = align_faces(count);
    std::lock_guard<std::mutex> lock(hw->geometry_mutex);

    uint32_t best_offset = INVALID_POOL_SLOT;
    uint32_t best_size = UINT32_MAX;
    for (const auto& entry : hw->geometry_free_by_offset) {
        if (entry.second >= size && entry.second < best_size) {
            best_offset = entry.first;
            best_size = entry.second;
            if (best_size == size) break;
        }
    }

    if (best_offset != INVALID_POOL_SLOT) {
        hw->geometry_free_by_offset.erase(best_offset);
        if (best_size > size) {
            hw->geometry_free_by_offset.emplace(best_offset + size, best_size - size);
        }
        return best_offset;
    }

    if (hw->geometry_bump_head + size > hw->geometry_face_capacity) {
        PIPELINE_LOG(
            "geometry allocator full (requested=%u faces used=%u capacity=%u)",
            count,
            hw->geometry_bump_head,
            hw->geometry_face_capacity);
        return INVALID_POOL_SLOT;
    }

    uint32_t offset = hw->geometry_bump_head;
    hw->geometry_bump_head += size;
    return offset;
}

void geometry_free(PipelineHardwareContext* hw, uint32_t first_index, uint32_t count) {
    if (!hw || first_index == INVALID_POOL_SLOT || count == 0) return;
    uint32_t size = align_faces(count);

    std::lock_guard<std::mutex> lock(hw->geometry_mutex);
    uint32_t new_offset = first_index;
    uint32_t new_size = size;

    auto after = hw->geometry_free_by_offset.find(first_index + size);
    if (after != hw->geometry_free_by_offset.end()) {
        new_size += after->second;
        hw->geometry_free_by_offset.erase(after);
    }

    auto before = hw->geometry_free_by_offset.lower_bound(first_index);
    if (before != hw->geometry_free_by_offset.begin()) {
        --before;
        if (before->first + before->second == first_index) {
            new_offset = before->first;
            new_size += before->second;
            hw->geometry_free_by_offset.erase(before);
        }
    }

    if (new_offset + new_size == hw->geometry_bump_head) {
        hw->geometry_bump_head = new_offset;
        while (!hw->geometry_free_by_offset.empty()) {
            auto tail = hw->geometry_free_by_offset.end();
            --tail;
            if (tail->first + tail->second != hw->geometry_bump_head) break;
            hw->geometry_bump_head = tail->first;
            hw->geometry_free_by_offset.erase(tail);
        }
        return;
    }

    hw->geometry_free_by_offset[new_offset] = new_size;
}

void defer_geometry_free_if_needed(
    PipelineHardwareContext* hw,
    uint32_t first_index,
    uint32_t count) {
    if (!hw || first_index == INVALID_POOL_SLOT || count == 0) return;
    hw->current_frame_deferred_segments.push_back({first_index, count});
}

void zero_indirect_commands_for_slot(PipelineHardwareContext* hw, uint32_t slot) {
    if (!hw || slot >= hw->max_registered_chunks) return;
    for (uint32_t layer = 0; layer < PIPELINE_RENDER_LAYERS; ++layer) {
        DrawArraysIndirectCommand& cmd =
            hw->canonical_draw_commands[layer * hw->max_registered_chunks + slot];
        cmd.count = 0;
        cmd.instanceCount = 0;
        cmd.first = 0;
        cmd.baseInstance = slot;
    }
}

void note_active_draw_slot(PipelineHardwareContext* hw, uint32_t slot) {
    if (!hw || slot == INVALID_POOL_SLOT) return;

    uint32_t desired = slot + 1u;
    uint32_t current = hw->active_draw_command_count.load(std::memory_order_acquire);
    while (current < desired &&
           !hw->active_draw_command_count.compare_exchange_weak(
               current,
               desired,
               std::memory_order_acq_rel,
               std::memory_order_acquire)) {
    }
}

void recompute_active_draw_count_locked(PipelineHardwareContext* hw) {
    if (!hw) return;

    uint32_t count = 0;
    for (uint32_t slot = static_cast<uint32_t>(hw->slot_to_chunk_registry.size());
         slot > 0;
         --slot) {
        if (hw->slot_to_chunk_registry[slot - 1u] != nullptr) {
            count = slot;
            break;
        }
    }
    hw->active_draw_command_count.store(count, std::memory_order_release);
}

bool acquire_draw_slot_for_chunk(PipelineHardwareContext* hw, ChunkContext* ctx, uint32_t* out_slot) {
    if (!hw || !ctx || !out_slot || !hw->renderer_configured.load(std::memory_order_acquire)) {
        return false;
    }

    uint32_t existing = ctx->draw_slot_id.load(std::memory_order_acquire);
    if (existing != INVALID_POOL_SLOT) {
        *out_slot = existing;
        return existing < hw->max_registered_chunks;
    }

    std::lock_guard<std::mutex> lock(hw->renderer_mutex);
    existing = ctx->draw_slot_id.load(std::memory_order_acquire);
    if (existing != INVALID_POOL_SLOT) {
        *out_slot = existing;
        return existing < hw->max_registered_chunks;
    }

    for (uint32_t slot = 0; slot < hw->max_registered_chunks; ++slot) {
        if (hw->slot_to_chunk_registry[slot] != nullptr) continue;
        if (!pipeline_retain_chunk_context(ctx)) return false;

        hw->slot_to_chunk_registry[slot] = ctx;
        note_active_draw_slot(hw, slot);
        ctx->draw_slot_id.store(slot, std::memory_order_release);
        // Stage metadata in the canonical CPU mirror. The persistent SSBO is
        // FRAME_LAG-buffered and only written to the active frame page in
        // begin_frame, so in-flight GPU draws never see torn metadata.
        if (slot < hw->canonical_chunk_metadata.size()) {
            hw->canonical_chunk_metadata[slot] = ChunkMetadataGpu{
                ctx->x * CHUNK_WIDTH_CORE,
                hw->world_min_y,
                ctx->z * CHUNK_LENGTH_CORE,
                0u
            };
        }
        zero_indirect_commands_for_slot(hw, slot);
        *out_slot = slot;
        return true;
    }

    PIPELINE_LOG("draw slot allocator full (max=%u)", hw->max_registered_chunks);
    return false;
}

void enqueue_pending_swap(PipelineHardwareContext* hw, PendingCommandSwap&& swap) {
    if (!hw) return;
    std::lock_guard<std::mutex> lock(hw->swap_mutex);
    hw->pending_command_swaps.push_back(std::move(swap));
}

void invalidate_all_neighbors_after_unload(
    PipelineHardwareContext* hw,
    int32_t chunk_x,
    int32_t chunk_z) {
    if (!hw) return;

    for (int dz = -1; dz <= 1; ++dz) {
        for (int dx = -1; dx <= 1; ++dx) {
            if (dx == 0 && dz == 0) continue;
            ChunkContext* neighbor = pipeline_get_chunk_context_retained(chunk_x + dx, chunk_z + dz);
            if (!neighbor) continue;
            neighbor->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
            neighbor->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
            pipeline_try_dispatch_mesh_job(hw, neighbor);
            pipeline_release_chunk_context(neighbor);
        }
    }
}

void release_neighbors(MeshJob& job) {
    pipeline_release_chunk_context(job.n_px);
    pipeline_release_chunk_context(job.n_nx);
    pipeline_release_chunk_context(job.n_pz);
    pipeline_release_chunk_context(job.n_nz);
    pipeline_release_chunk_context(job.n_px_pz);
    pipeline_release_chunk_context(job.n_px_nz);
    pipeline_release_chunk_context(job.n_nx_pz);
    pipeline_release_chunk_context(job.n_nx_nz);

    job.n_px = nullptr;
    job.n_nx = nullptr;
    job.n_pz = nullptr;
    job.n_nz = nullptr;
    job.n_px_pz = nullptr;
    job.n_px_nz = nullptr;
    job.n_nx_pz = nullptr;
    job.n_nx_nz = nullptr;
}

const ChunkSnapshot* route_snapshot(
    WorkerWorkspace* ws,
    int32_t lx,
    int32_t lz,
    int32_t* routed_x,
    int32_t* routed_z) {
    if (!ws || !routed_x || !routed_z) return nullptr;

    if (lx >= 16 && lz >= 16) {
        *routed_x = lx - 16;
        *routed_z = lz - 16;
        return &ws->neighbor_snaps[4];
    }
    if (lx >= 16 && lz < 0) {
        *routed_x = lx - 16;
        *routed_z = lz + 16;
        return &ws->neighbor_snaps[5];
    }
    if (lx < 0 && lz >= 16) {
        *routed_x = lx + 16;
        *routed_z = lz - 16;
        return &ws->neighbor_snaps[6];
    }
    if (lx < 0 && lz < 0) {
        *routed_x = lx + 16;
        *routed_z = lz + 16;
        return &ws->neighbor_snaps[7];
    }
    if (lx >= 16) {
        *routed_x = lx - 16;
        *routed_z = lz;
        return &ws->neighbor_snaps[0];
    }
    if (lx < 0) {
        *routed_x = lx + 16;
        *routed_z = lz;
        return &ws->neighbor_snaps[1];
    }
    if (lz >= 16) {
        *routed_x = lx;
        *routed_z = lz - 16;
        return &ws->neighbor_snaps[2];
    }
    if (lz < 0) {
        *routed_x = lx;
        *routed_z = lz + 16;
        return &ws->neighbor_snaps[3];
    }

    *routed_x = lx;
    *routed_z = lz;
    return &ws->target_snap;
}

uint8_t get_light_byte_at_isolated(
    WorkerWorkspace* ws,
    int32_t lx,
    int32_t ly,
    int32_t lz,
    uint8_t missing_default) {
    if (ly < 0 || ly >= CHUNK_HEIGHT) return missing_default;

    int32_t routed_x = 0;
    int32_t routed_z = 0;
    const ChunkSnapshot* snap = route_snapshot(ws, lx, lz, &routed_x, &routed_z);
    if (!snap || !snap->allocated) return missing_default;
    if (routed_x < 0 || routed_x >= 16 || routed_z < 0 || routed_z >= 16) {
        return missing_default;
    }

    return snap->lights[block_index(
        static_cast<uint32_t>(routed_x),
        static_cast<uint32_t>(ly),
        static_cast<uint32_t>(routed_z))];
}

void append_opaque_face(
    WorkerWorkspace* ws,
    uint16_t block_id,
    uint32_t face,
    uint32_t x,
    uint32_t y,
    uint32_t z) {
    ws->opaque_count++;
    if (ws->opaque_buf.size() < MAX_FACES_PER_CHUNK) {
        ws->opaque_buf.push_back(PACK_FACE(block_id, face, x, y, z, 0x00FFu));
    }
}

uint8_t face_properties(uint16_t block_id, uint32_t face) {
    if (block_id == BLOCK_AIR || face >= 6) return 0;
    if (!g_block_face_properties_uploaded.load(std::memory_order_acquire)) {
        return PROP_PASS_OPAQUE | PROP_OCCLUDES;
    }
    return g_block_face_properties[block_id][face];
}

void append_face(
    WorkerWorkspace* ws,
    uint8_t props,
    uint16_t block_id,
    uint32_t face,
    uint32_t x,
    uint32_t y,
    uint32_t z) {
    uint64_t packed = PACK_FACE(block_id, face, x, y, z, 0x00FFu);
    if ((props & PROP_PASS_TRANSLUCENT) != 0) {
        ws->translucent_count++;
        if (ws->translucent_buf.size() < MAX_FACES_PER_CHUNK) {
            ws->translucent_buf.push_back(packed);
        }
    } else if ((props & PROP_PASS_CUTOUT) != 0) {
        ws->cutout_count++;
        if (ws->cutout_buf.size() < MAX_FACES_PER_CHUNK) {
            ws->cutout_buf.push_back(packed);
        }
    } else {
        append_opaque_face(ws, block_id, face, x, y, z);
    }
}

void execute_cpu_mesh_task(MeshJob job, PipelineHardwareContext* hw) {
    auto ws = std::make_unique<WorkerWorkspace>();
    uint32_t captured_generation = 0;

    load_snapshot_safe(job.target, ws->target_snap, &captured_generation);
    load_snapshot_safe(job.n_px, ws->neighbor_snaps[0]);
    load_snapshot_safe(job.n_nx, ws->neighbor_snaps[1]);
    load_snapshot_safe(job.n_pz, ws->neighbor_snaps[2]);
    load_snapshot_safe(job.n_nz, ws->neighbor_snaps[3]);
    load_snapshot_safe(job.n_px_pz, ws->neighbor_snaps[4]);
    load_snapshot_safe(job.n_px_nz, ws->neighbor_snaps[5]);
    load_snapshot_safe(job.n_nx_pz, ws->neighbor_snaps[6]);
    load_snapshot_safe(job.n_nx_nz, ws->neighbor_snaps[7]);

    release_neighbors(job);

    if (!job.target || !ws->target_snap.allocated) {
        release_pool_slot_if_valid(hw, job.pool_slot_idx);
        pipeline_release_chunk_context(job.target);
        return;
    }

    uint32_t opaque = 0;
    uint32_t cutout = 0;
    uint32_t translucent = 0;
    cpu_mesh_extract_isolated(ws.get(), &opaque, &cutout, &translucent);

    const bool stale =
        job.target->retired.load(std::memory_order_acquire) ||
        captured_generation != job.target->mesh_generation.load(std::memory_order_acquire);
    if (stale) {
        release_pool_slot_if_valid(hw, job.pool_slot_idx);
        pipeline_release_chunk_context(job.target);
        return;
    }

    const uint32_t total_faces = opaque + cutout + translucent;
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire)) {
        if (!job.target->retired.load(std::memory_order_acquire) &&
            captured_generation == job.target->mesh_generation.load(std::memory_order_acquire)) {
            job.target->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
        }
        release_pool_slot_if_valid(hw, job.pool_slot_idx);
        pipeline_release_chunk_context(job.target);
        return;
    }

    job.target->state.store(STATE_3_MESH_READY, std::memory_order_release);

    bool swap_enqueued = false;
    uint32_t slot = job.target->draw_slot_id.load(std::memory_order_acquire);
    if (total_faces == 0 && slot == INVALID_POOL_SLOT) {
        job.target->state.store(STATE_4_COMPILED, std::memory_order_release);
    } else if (acquire_draw_slot_for_chunk(hw, job.target, &slot)) {
        uint32_t new_offset = INVALID_POOL_SLOT;
        if (total_faces > 0) {
            new_offset = geometry_allocate(hw, total_faces);
            if (new_offset != INVALID_POOL_SLOT) {
                uint64_t* dst = hw->persistent_geometry_faces_ptr + new_offset;
                if (opaque > 0) {
                    std::memcpy(dst, ws->opaque_buf.data(), opaque * sizeof(uint64_t));
                    dst += opaque;
                }
                if (cutout > 0) {
                    std::memcpy(dst, ws->cutout_buf.data(), cutout * sizeof(uint64_t));
                    dst += cutout;
                }
                if (translucent > 0) {
                    std::memcpy(dst, ws->translucent_buf.data(), translucent * sizeof(uint64_t));
                }
            }
        }

        if (total_faces == 0 || new_offset != INVALID_POOL_SLOT) {
            PendingCommandSwap swap{};
            swap.chunk_ctx = job.target;
            swap.slot_id = slot;
            swap.new_opaque_count = opaque;
            swap.new_cutout_count = cutout;
            swap.new_translucent_count = translucent;
            swap.new_first_index = new_offset;
            swap.old_offset_to_free =
                job.target->geometry_resource_offset.load(std::memory_order_acquire);
            swap.old_size_to_free =
                job.target->geometry_resource_offset_size.load(std::memory_order_acquire);
            swap.pool_slot_idx = job.pool_slot_idx;
            swap.generation = captured_generation;
            swap.kind = SwapKind::MeshCommit;
            enqueue_pending_swap(hw, std::move(swap));
            swap_enqueued = true;
        }
    }

    PIPELINE_LOG(
        "extracted %u/%u/%u faces from chunk (%d, %d)",
        opaque,
        cutout,
        translucent,
        job.target->x,
        job.target->z);

    if (!swap_enqueued) {
        if (job.target->state.load(std::memory_order_acquire) == STATE_3_MESH_READY &&
            !job.target->retired.load(std::memory_order_acquire) &&
            captured_generation == job.target->mesh_generation.load(std::memory_order_acquire)) {
            job.target->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
        }
        release_pool_slot_if_valid(hw, job.pool_slot_idx);
        pipeline_release_chunk_context(job.target);
    }
}

bool pipeline_enqueue_mesh_job(PipelineHardwareContext* hw, MeshJob job) {
    if (!hw || hw->shutting_down.load(std::memory_order_acquire)) return false;

    hw->active_mesh_jobs.fetch_add(1, std::memory_order_acq_rel);
    if (!hw->mesh_pool) {
        execute_cpu_mesh_task(job, hw);
        hw->active_mesh_jobs.fetch_sub(1, std::memory_order_acq_rel);
        return true;
    }

    hw->mesh_pool->enqueue([hw, job]() mutable {
        execute_cpu_mesh_task(job, hw);
        hw->active_mesh_jobs.fetch_sub(1, std::memory_order_acq_rel);
    });
    return true;
}

} // namespace

ChunkSnapshot::ChunkSnapshot()
    : blocks(CHUNK_BLOCK_COUNT),
      lights(CHUNK_BLOCK_COUNT) {
}

ChunkContext* pipeline_create_chunk_context(int32_t chunk_x, int32_t chunk_z) {
    ChunkContext* ctx = new ChunkContext();
    ctx->x = chunk_x;
    ctx->z = chunk_z;
    ctx->refcount.store(1, std::memory_order_relaxed);
    ctx->retired.store(false, std::memory_order_relaxed);
    ctx->state.store(STATE_0_EMPTY, std::memory_order_relaxed);
    return ctx;
}

void pipeline_destroy_chunk_context_for_tests(ChunkContext* ctx) {
    pipeline_release_chunk_context(ctx);
}

bool pipeline_replace_chunk_payload(
    ChunkContext* ctx,
    const uint16_t* block_ids,
    const uint8_t* light_ids) {
    if (!ctx || !block_ids || !light_ids) return false;
    if (ctx->retired.load(std::memory_order_acquire)) return false;

    auto* next_blocks = new uint16_t[CHUNK_BLOCK_COUNT];
    auto* next_lights = new uint8_t[CHUNK_BLOCK_COUNT];
    std::memcpy(next_blocks, block_ids, CHUNK_BLOCK_COUNT * sizeof(uint16_t));
    std::memcpy(next_lights, light_ids, CHUNK_BLOCK_COUNT * sizeof(uint8_t));

    uint16_t* old_blocks = nullptr;
    uint8_t* old_lights = nullptr;
    {
        std::lock_guard<std::mutex> lock(ctx->update_mutex);
        old_blocks = ctx->h_block_ids.exchange(next_blocks, std::memory_order_acq_rel);
        old_lights = ctx->h_light_ids.exchange(next_lights, std::memory_order_acq_rel);
        ctx->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
        ctx->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
    }

    delete[] old_blocks;
    delete[] old_lights;
    return true;
}

bool pipeline_replace_chunk_lights(ChunkContext* ctx, const uint8_t* light_ids) {
    if (!ctx || !light_ids) return false;
    if (ctx->retired.load(std::memory_order_acquire)) return false;

    auto* next_lights = new uint8_t[CHUNK_BLOCK_COUNT];
    std::memcpy(next_lights, light_ids, CHUNK_BLOCK_COUNT * sizeof(uint8_t));

    uint8_t* old_lights = nullptr;
    {
        std::lock_guard<std::mutex> lock(ctx->update_mutex);
        if (!ctx->h_block_ids.load(std::memory_order_acquire)) {
            delete[] next_lights;
            return false;
        }
        old_lights = ctx->h_light_ids.exchange(next_lights, std::memory_order_acq_rel);
        ctx->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
        ctx->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
    }

    delete[] old_lights;
    return true;
}

void pipeline_hardware_init(PipelineHardwareContext* hw, uint32_t worker_count) {
    if (!hw) return;
    hw->shutting_down.store(false, std::memory_order_release);
    hw->active_mesh_jobs.store(0, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(hw->pool_mutex);
        for (bool& busy : hw->pool_slot_busy) busy = false;
    }
    if (!hw->mesh_pool) {
        uint32_t count = worker_count == 0 ? default_worker_count() : worker_count;
        count = std::min(std::max(count, 1u), MAX_CONCURRENT_UPDATES);
        hw->mesh_pool = std::make_unique<ThreadPool>(count);
        PIPELINE_LOG("native mesh worker pool ready (%u threads)", count);
    }
}

void pipeline_hardware_destroy(PipelineHardwareContext* hw) {
    if (!hw) return;
    hw->shutting_down.store(true, std::memory_order_release);
    hw->mesh_pool.reset();
    hw->active_mesh_jobs.store(0, std::memory_order_release);

    std::vector<ChunkContext*> registered;
    {
        std::unique_lock<std::shared_mutex> lock(g_registry_mutex);
        registered.reserve(g_chunk_registry.size());
        for (auto& entry : g_chunk_registry) {
            registered.push_back(entry.second);
        }
        g_chunk_registry.clear();
    }

    for (ChunkContext* ctx : registered) {
        if (!ctx) continue;
        ctx->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
        ctx->retired.store(true, std::memory_order_release);
        ctx->state.store(STATE_0_EMPTY, std::memory_order_release);
        pipeline_release_chunk_context(ctx);
    }

    {
        std::lock_guard<std::mutex> lock(hw->renderer_mutex);
        for (ChunkContext* owner : hw->slot_to_chunk_registry) {
            pipeline_release_chunk_context(owner);
        }
        hw->slot_to_chunk_registry.clear();
        hw->canonical_draw_commands.clear();
        hw->canonical_chunk_metadata.clear();
        hw->persistent_geometry_faces_ptr = nullptr;
        hw->persistent_chunk_metadata_ptr = nullptr;
        hw->persistent_draw_commands_ptr = nullptr;
        hw->geometry_face_capacity = 0;
        hw->geometry_bump_head = 0;
        hw->geometry_free_by_offset.clear();
        hw->max_registered_chunks = 0;
        hw->active_draw_command_count.store(0, std::memory_order_release);
        hw->renderer_configured.store(false, std::memory_order_release);
    }

    {
        std::lock_guard<std::mutex> lock(hw->swap_mutex);
        while (!hw->pending_command_swaps.empty()) {
            PendingCommandSwap swap = hw->pending_command_swaps.front();
            hw->pending_command_swaps.pop_front();
            pipeline_release_chunk_context(swap.chunk_ctx);
        }
    }

    if (ensure_gl_sync_loaded()) {
        for (void*& fence : hw->frame_fences) {
            if (fence) {
                p_glDeleteSync(static_cast<GLsyncHandle>(fence));
                fence = nullptr;
            }
        }
        for (DeferredFreeBatch& batch : hw->deferred_free_batches) {
            if (batch.fence) {
                p_glDeleteSync(static_cast<GLsyncHandle>(batch.fence));
            }
        }
    }
    hw->deferred_free_batches.clear();
    hw->current_frame_deferred_segments.clear();
    hw->current_frame_index = 0;
    hw->last_renderable_frame_index.store(-1, std::memory_order_release);
    hw->frame_started = false;
}

bool pipeline_configure_renderer(
    PipelineHardwareContext* hw,
    uint64_t* geometry_faces_ptr,
    uint32_t geometry_face_capacity,
    ChunkMetadataGpu* chunk_metadata_ptr,
    DrawArraysIndirectCommand* draw_commands_ptr,
    uint32_t max_registered_chunks,
    int32_t world_min_y) {
    if (!hw || !geometry_faces_ptr || !chunk_metadata_ptr || !draw_commands_ptr ||
        geometry_face_capacity == 0 || max_registered_chunks == 0) {
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(hw->renderer_mutex);
        hw->persistent_geometry_faces_ptr = geometry_faces_ptr;
        hw->geometry_face_capacity = geometry_face_capacity;
        hw->geometry_bump_head = 0;
        hw->geometry_free_by_offset.clear();
        hw->persistent_chunk_metadata_ptr = chunk_metadata_ptr;
        hw->persistent_draw_commands_ptr = draw_commands_ptr;
        hw->max_registered_chunks = max_registered_chunks;
        hw->world_min_y = world_min_y;
        hw->slot_to_chunk_registry.assign(max_registered_chunks, nullptr);
        hw->active_draw_command_count.store(0, std::memory_order_release);
        hw->canonical_draw_commands.assign(
            PIPELINE_RENDER_LAYERS * max_registered_chunks,
            DrawArraysIndirectCommand{});
        hw->canonical_chunk_metadata.assign(max_registered_chunks, ChunkMetadataGpu{});
        for (uint32_t slot = 0; slot < max_registered_chunks; ++slot) {
            zero_indirect_commands_for_slot(hw, slot);
        }
        const size_t page_commands =
            static_cast<size_t>(FRAME_LAG) * PIPELINE_RENDER_LAYERS * max_registered_chunks;
        std::memset(draw_commands_ptr, 0, page_commands * sizeof(DrawArraysIndirectCommand));
        // Metadata SSBO is now FRAME_LAG-buffered just like commands. Java must
        // allocate (FRAME_LAG * max_registered_chunks * sizeof(ChunkMetadataGpu))
        // bytes and bind only the active page each frame.
        const size_t page_metadata =
            static_cast<size_t>(FRAME_LAG) * max_registered_chunks;
        std::memset(chunk_metadata_ptr, 0, page_metadata * sizeof(ChunkMetadataGpu));
        hw->renderer_configured.store(true, std::memory_order_release);
    }

    std::vector<ChunkContext*> registered;
    {
        std::shared_lock<std::shared_mutex> registry_lock(g_registry_mutex);
        registered.reserve(g_chunk_registry.size());
        for (auto& entry : g_chunk_registry) {
            ChunkContext* ctx = entry.second;
            if (pipeline_retain_chunk_context(ctx)) {
                registered.push_back(ctx);
            }
        }
    }

    for (ChunkContext* ctx : registered) {
        uint8_t state = ctx->state.load(std::memory_order_acquire);
        if (state == STATE_1_RECONSTRUCTED || state == STATE_3_MESH_READY) {
            if (state == STATE_3_MESH_READY) {
                ctx->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
            }
            pipeline_try_dispatch_mesh_job(hw, ctx);
        }
        pipeline_release_chunk_context(ctx);
    }

    PIPELINE_LOG(
        "renderer configured (geometry=%u faces, maxChunks=%u, minY=%d)",
        geometry_face_capacity,
        max_registered_chunks,
        world_min_y);
    return true;
}

void pipeline_process_deferred_frees(PipelineHardwareContext* hw) {
    if (!hw) return;
    if (!ensure_gl_sync_loaded()) {
        for (DeferredFreeBatch& batch : hw->deferred_free_batches) {
            for (const GeometryFreeSegment& seg : batch.segments) {
                geometry_free(hw, seg.first_index, seg.count);
            }
        }
        hw->deferred_free_batches.clear();
        return;
    }

    auto it = hw->deferred_free_batches.begin();
    while (it != hw->deferred_free_batches.end()) {
        if (!it->fence) {
            for (const GeometryFreeSegment& seg : it->segments) {
                geometry_free(hw, seg.first_index, seg.count);
            }
            it = hw->deferred_free_batches.erase(it);
            continue;
        }

        GLenumAlias status =
            p_glClientWaitSync(static_cast<GLsyncHandle>(it->fence), 0, 0);
        if (status == GL_ALREADY_SIGNALED_VALUE || status == GL_CONDITION_SATISFIED_VALUE) {
            for (const GeometryFreeSegment& seg : it->segments) {
                geometry_free(hw, seg.first_index, seg.count);
            }
            p_glDeleteSync(static_cast<GLsyncHandle>(it->fence));
            it = hw->deferred_free_batches.erase(it);
        } else {
            ++it;
        }
    }
}

void pipeline_flush_command_swaps(PipelineHardwareContext* hw) {
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire)) return;

    std::deque<PendingCommandSwap> swaps;
    {
        std::lock_guard<std::mutex> lock(hw->swap_mutex);
        swaps.swap(hw->pending_command_swaps);
    }

    for (PendingCommandSwap& swap : swaps) {
        if (!swap.chunk_ctx) {
            release_pool_slot_if_valid(hw, swap.pool_slot_idx);
            continue;
        }

        if (swap.kind == SwapKind::ClearSlot) {
            {
                std::lock_guard<std::mutex> lock(hw->renderer_mutex);
                if (swap.slot_id < hw->slot_to_chunk_registry.size() &&
                    hw->slot_to_chunk_registry[swap.slot_id] == swap.chunk_ctx) {
                    zero_indirect_commands_for_slot(hw, swap.slot_id);
                    hw->slot_to_chunk_registry[swap.slot_id] = nullptr;
                    recompute_active_draw_count_locked(hw);
                    swap.chunk_ctx->draw_slot_id.store(INVALID_POOL_SLOT, std::memory_order_release);
                    pipeline_release_chunk_context(swap.chunk_ctx);
                }
            }
            defer_geometry_free_if_needed(hw, swap.old_offset_to_free, swap.old_size_to_free);
            release_pool_slot_if_valid(hw, swap.pool_slot_idx);
            pipeline_release_chunk_context(swap.chunk_ctx);
            continue;
        }

        const bool stale =
            swap.chunk_ctx->retired.load(std::memory_order_acquire) ||
            swap.generation != swap.chunk_ctx->mesh_generation.load(std::memory_order_acquire);
        if (stale) {
            geometry_free(
                hw,
                swap.new_first_index,
                swap.new_opaque_count + swap.new_cutout_count + swap.new_translucent_count);
            release_pool_slot_if_valid(hw, swap.pool_slot_idx);
            pipeline_release_chunk_context(swap.chunk_ctx);
            continue;
        }

        const uint32_t total_faces =
            swap.new_opaque_count + swap.new_cutout_count + swap.new_translucent_count;
        {
            std::lock_guard<std::mutex> lock(hw->renderer_mutex);
            if (swap.slot_id >= hw->slot_to_chunk_registry.size() ||
                hw->slot_to_chunk_registry[swap.slot_id] != swap.chunk_ctx) {
                geometry_free(hw, swap.new_first_index, total_faces);
                release_pool_slot_if_valid(hw, swap.pool_slot_idx);
                pipeline_release_chunk_context(swap.chunk_ctx);
                continue;
            }

            // Stage metadata in the canonical mirror; begin_frame copies it
            // into the active per-frame page so in-flight GPU draws using
            // older command pages keep seeing the metadata they were issued with.
            if (swap.slot_id < hw->canonical_chunk_metadata.size()) {
                hw->canonical_chunk_metadata[swap.slot_id] = ChunkMetadataGpu{
                    swap.chunk_ctx->x * CHUNK_WIDTH_CORE,
                    hw->world_min_y,
                    swap.chunk_ctx->z * CHUNK_LENGTH_CORE,
                    swap.new_first_index == INVALID_POOL_SLOT ? 0u : swap.new_first_index
                };
            }

            const uint32_t first_cutout = swap.new_opaque_count * 6u;
            const uint32_t first_translucent =
                (swap.new_opaque_count + swap.new_cutout_count) * 6u;

            DrawArraysIndirectCommand& solid =
                hw->canonical_draw_commands[swap.slot_id];
            solid.count = swap.new_opaque_count * 6u;
            solid.instanceCount = swap.new_opaque_count > 0 ? 1u : 0u;
            solid.first = 0;
            solid.baseInstance = swap.slot_id;

            DrawArraysIndirectCommand& cutout =
                hw->canonical_draw_commands[hw->max_registered_chunks + swap.slot_id];
            cutout.count = swap.new_cutout_count * 6u;
            cutout.instanceCount = swap.new_cutout_count > 0 ? 1u : 0u;
            cutout.first = first_cutout;
            cutout.baseInstance = swap.slot_id;

            DrawArraysIndirectCommand& translucent =
                hw->canonical_draw_commands[(2u * hw->max_registered_chunks) + swap.slot_id];
            translucent.count = swap.new_translucent_count * 6u;
            translucent.instanceCount = swap.new_translucent_count > 0 ? 1u : 0u;
            translucent.first = first_translucent;
            translucent.baseInstance = swap.slot_id;

            swap.chunk_ctx->geometry_resource_offset.store(
                swap.new_first_index,
                std::memory_order_release);
            swap.chunk_ctx->geometry_resource_offset_size.store(
                total_faces,
                std::memory_order_release);
            swap.chunk_ctx->state.store(STATE_4_COMPILED, std::memory_order_release);
        }

        defer_geometry_free_if_needed(hw, swap.old_offset_to_free, swap.old_size_to_free);
        release_pool_slot_if_valid(hw, swap.pool_slot_idx);
        pipeline_release_chunk_context(swap.chunk_ctx);
    }
}

int32_t pipeline_begin_frame(PipelineHardwareContext* hw) {
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire)) return -1;
    if (!ensure_gl_sync_loaded()) return -1;

    pipeline_process_deferred_frees(hw);

    const uint32_t frame = hw->current_frame_index;
    void* fence = hw->frame_fences[frame];
    if (fence) {
        GLenumAlias status = p_glClientWaitSync(
            static_cast<GLsyncHandle>(fence),
            GL_SYNC_FLUSH_COMMANDS_BIT_VALUE,
            FRAME_FENCE_TIMEOUT_NS);
        if (status == GL_ALREADY_SIGNALED_VALUE || status == GL_CONDITION_SATISFIED_VALUE) {
            p_glDeleteSync(static_cast<GLsyncHandle>(fence));
            hw->frame_fences[frame] = nullptr;
        } else {
            PIPELINE_LOG("command page %u still busy; skipping native draw this frame", frame);
            hw->frame_started = false;
            return -1;
        }
    }

    pipeline_flush_command_swaps(hw);

    const size_t page_commands =
        static_cast<size_t>(PIPELINE_RENDER_LAYERS) * hw->max_registered_chunks;

    // Take renderer_mutex for the whole canonical -> page copy. Without this,
    // worker threads running acquire_draw_slot_for_chunk can write both
    // canonical_chunk_metadata[slot] AND canonical_draw_commands[slot]
    // (via zero_indirect_commands_for_slot) mid-memcpy, producing torn
    // 16-byte structs on the GPU page -> misplaced/giant quads.
    {
        std::lock_guard<std::mutex> lock(hw->renderer_mutex);

        std::memcpy(
            hw->persistent_draw_commands_ptr + (static_cast<size_t>(frame) * page_commands),
            hw->canonical_draw_commands.data(),
            page_commands * sizeof(DrawArraysIndirectCommand));

        if (hw->persistent_chunk_metadata_ptr) {
            std::memcpy(
                hw->persistent_chunk_metadata_ptr +
                    (static_cast<size_t>(frame) * hw->max_registered_chunks),
                hw->canonical_chunk_metadata.data(),
                hw->max_registered_chunks * sizeof(ChunkMetadataGpu));
        }
    }

    p_glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT_VALUE);

    hw->frame_started = true;
    hw->last_renderable_frame_index.store(static_cast<int32_t>(frame), std::memory_order_release);
    return static_cast<int32_t>(frame);
}

void pipeline_end_frame(PipelineHardwareContext* hw) {
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire) || !hw->frame_started) {
        return;
    }
    if (!ensure_gl_sync_loaded()) {
        hw->frame_started = false;
        return;
    }

    const uint32_t frame = hw->current_frame_index;
    hw->frame_fences[frame] =
        p_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE_VALUE, 0);

    if (!hw->current_frame_deferred_segments.empty()) {
        DeferredFreeBatch batch{};
        batch.segments.swap(hw->current_frame_deferred_segments);
        batch.fence = p_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE_VALUE, 0);
        hw->deferred_free_batches.push_back(std::move(batch));
    }

    hw->current_frame_index = (frame + 1u) % FRAME_LAG;
    hw->frame_started = false;
}

uint32_t pipeline_renderer_max_chunks(PipelineHardwareContext* hw) {
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire)) return 0;
    return hw->max_registered_chunks;
}

uint32_t pipeline_renderer_draw_count(PipelineHardwareContext* hw) {
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire)) return 0;
    return hw->active_draw_command_count.load(std::memory_order_acquire);
}

/**
 * DIAGNOSTIC: scan active draw slots and report any slot whose canonical
 * metadata origin disagrees with its owning ChunkContext's (x,z).
 *
 * Forge swallows native stdout/stderr from JNI native code, so we can't just
 * fprintf here — we must return the audit data to Java so SLF4J logs it.
 *
 * out_data layout (3 longs per mismatch):
 *   [3*i+0]: slot id
 *   [3*i+1]: ((ctx_x << 32) | (ctx_z & 0xFFFFFFFF))
 *   [3*i+2]: ((meta_chunk_x << 32) | (meta_chunk_z & 0xFFFFFFFF))
 *
 * Returns total mismatch count (may exceed the number written into out_data).
 * Java rate-limits how often it calls this.
 */
uint32_t pipeline_run_metadata_audit(
    PipelineHardwareContext* hw,
    int64_t* out_data,
    uint32_t max_out,
    uint32_t* active_out_count) {
    if (active_out_count) *active_out_count = 0;
    if (!hw || !hw->renderer_configured.load(std::memory_order_acquire)) return 0;

    std::lock_guard<std::mutex> audit_lock(hw->renderer_mutex);
    uint32_t mismatches = 0;
    uint32_t written = 0;
    uint32_t active = 0;
    const uint32_t scan_limit =
        std::min<uint32_t>(200u,
                           static_cast<uint32_t>(hw->slot_to_chunk_registry.size()));
    for (uint32_t slot = 0; slot < scan_limit; ++slot) {
        ChunkContext* ctx = hw->slot_to_chunk_registry[slot];
        if (!ctx) continue;
        ++active;
        if (slot >= hw->canonical_chunk_metadata.size()) continue;
        const ChunkMetadataGpu& meta = hw->canonical_chunk_metadata[slot];
        const int32_t expected_x = ctx->x * CHUNK_WIDTH_CORE;
        const int32_t expected_z = ctx->z * CHUNK_LENGTH_CORE;
        if (meta.origin_x != expected_x || meta.origin_z != expected_z) {
            ++mismatches;
            if (out_data && written < max_out) {
                const int64_t ctx_packed =
                    (static_cast<int64_t>(ctx->x) << 32) |
                    (static_cast<int64_t>(ctx->z) & 0xFFFFFFFFLL);
                const int64_t meta_packed =
                    (static_cast<int64_t>(meta.origin_x / CHUNK_WIDTH_CORE) << 32) |
                    (static_cast<int64_t>(meta.origin_z / CHUNK_LENGTH_CORE) & 0xFFFFFFFFLL);
                out_data[3 * written + 0] = static_cast<int64_t>(slot);
                out_data[3 * written + 1] = ctx_packed;
                out_data[3 * written + 2] = meta_packed;
                ++written;
            }
        }
    }
    if (active_out_count) *active_out_count = active;
    return mismatches;
}

uint32_t pipeline_pending_swap_count(PipelineHardwareContext* hw) {
    if (!hw) return 0;
    std::lock_guard<std::mutex> lock(hw->swap_mutex);
    return static_cast<uint32_t>(hw->pending_command_swaps.size());
}

uint32_t pipeline_active_mesh_job_count(PipelineHardwareContext* hw) {
    if (!hw) return 0;
    return hw->active_mesh_jobs.load(std::memory_order_acquire);
}

uint32_t pipeline_count_compiled_chunks(const uint64_t* keys, size_t count) {
    if (!keys || count == 0) return 0;

    uint32_t compiled = 0;
    std::shared_lock<std::shared_mutex> lock(g_registry_mutex);
    for (size_t i = 0; i < count; ++i) {
        auto it = g_chunk_registry.find(keys[i]);
        if (it == g_chunk_registry.end()) continue;

        ChunkContext* ctx = it->second;
        if (!ctx || ctx->retired.load(std::memory_order_acquire)) continue;
        if (ctx->state.load(std::memory_order_acquire) >= STATE_4_COMPILED) {
            compiled++;
        }
    }
    return compiled;
}

bool pipeline_register_chunk(ChunkContext* ctx) {
    if (!ctx) return false;
    if (ctx->retired.load(std::memory_order_acquire)) return false;

    std::unique_lock<std::shared_mutex> lock(g_registry_mutex);
    const uint64_t key = pack_coords(ctx->x, ctx->z);
    if (g_chunk_registry.find(key) != g_chunk_registry.end()) {
        return false;
    }

    g_chunk_registry.emplace(key, ctx);
    return true;
}

void pipeline_unregister_chunk(PipelineHardwareContext* hw, int32_t chunk_x, int32_t chunk_z) {
    ChunkContext* removed = nullptr;
    {
        std::unique_lock<std::shared_mutex> lock(g_registry_mutex);
        auto it = g_chunk_registry.find(pack_coords(chunk_x, chunk_z));
        if (it == g_chunk_registry.end()) return;
        removed = it->second;
        g_chunk_registry.erase(it);
    }

    if (!removed) return;

    PendingCommandSwap clear_swap{};
    bool enqueue_clear = false;
    if (pipeline_retain_chunk_context(removed)) {
        clear_swap.chunk_ctx = removed;
        clear_swap.slot_id = removed->draw_slot_id.load(std::memory_order_acquire);
        clear_swap.old_offset_to_free =
            removed->geometry_resource_offset.load(std::memory_order_acquire);
        clear_swap.old_size_to_free =
            removed->geometry_resource_offset_size.load(std::memory_order_acquire);
        clear_swap.generation =
            removed->mesh_generation.load(std::memory_order_acquire) + 1u;
        clear_swap.kind = SwapKind::ClearSlot;
        enqueue_clear =
            clear_swap.slot_id != INVALID_POOL_SLOT ||
            clear_swap.old_offset_to_free != INVALID_POOL_SLOT;
    }

    removed->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
    removed->retired.store(true, std::memory_order_release);
    removed->state.store(STATE_0_EMPTY, std::memory_order_release);

    invalidate_all_neighbors_after_unload(hw, chunk_x, chunk_z);

    if (enqueue_clear && hw) {
        enqueue_pending_swap(hw, std::move(clear_swap));
    } else if (clear_swap.chunk_ctx) {
        pipeline_release_chunk_context(clear_swap.chunk_ctx);
    }

    pipeline_release_chunk_context(removed);
}

ChunkContext* pipeline_get_chunk_context_retained(int32_t chunk_x, int32_t chunk_z) {
    std::shared_lock<std::shared_mutex> lock(g_registry_mutex);
    auto it = g_chunk_registry.find(pack_coords(chunk_x, chunk_z));
    if (it == g_chunk_registry.end()) return nullptr;

    ChunkContext* ctx = it->second;
    return pipeline_retain_chunk_context(ctx) ? ctx : nullptr;
}

bool pipeline_retain_chunk_context(ChunkContext* ctx) {
    if (!ctx) return false;

    uint32_t current = ctx->refcount.load(std::memory_order_acquire);
    while (true) {
        if (current == 0 || ctx->retired.load(std::memory_order_acquire)) {
            return false;
        }

        if (ctx->refcount.compare_exchange_weak(
                current,
                current + 1,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            return true;
        }
    }
}

void pipeline_release_chunk_context(ChunkContext* ctx) {
    if (!ctx) return;

    if (ctx->refcount.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        uint16_t* blocks = ctx->h_block_ids.exchange(nullptr, std::memory_order_acq_rel);
        uint8_t* lights = ctx->h_light_ids.exchange(nullptr, std::memory_order_acq_rel);

        delete[] blocks;
        delete[] lights;
        delete ctx;
    }
}

bool is_neighbor_ready(ChunkContext* neighbor) {
    if (!neighbor) return false;
    if (neighbor->retired.load(std::memory_order_acquire)) return false;
    return neighbor->state.load(std::memory_order_acquire) >= STATE_1_RECONSTRUCTED;
}

void pipeline_try_dispatch_mesh_job(PipelineHardwareContext* hw, ChunkContext* target) {
    if (!hw || !target) return;
    if (target->retired.load(std::memory_order_acquire)) return;
    if (target->state.load(std::memory_order_acquire) != STATE_1_RECONSTRUCTED) return;

    MeshJob job{};
    job.n_px = pipeline_get_chunk_context_retained(target->x + 1, target->z);
    job.n_nx = pipeline_get_chunk_context_retained(target->x - 1, target->z);
    job.n_pz = pipeline_get_chunk_context_retained(target->x, target->z + 1);
    job.n_nz = pipeline_get_chunk_context_retained(target->x, target->z - 1);
    job.n_px_pz = pipeline_get_chunk_context_retained(target->x + 1, target->z + 1);
    job.n_px_nz = pipeline_get_chunk_context_retained(target->x + 1, target->z - 1);
    job.n_nx_pz = pipeline_get_chunk_context_retained(target->x - 1, target->z + 1);
    job.n_nx_nz = pipeline_get_chunk_context_retained(target->x - 1, target->z - 1);

    const bool neighbors_ready =
        is_neighbor_ready(job.n_px) &&
        is_neighbor_ready(job.n_nx) &&
        is_neighbor_ready(job.n_pz) &&
        is_neighbor_ready(job.n_nz);

    if (!neighbors_ready) {
        release_neighbors(job);
        return;
    }

    if (!pipeline_retain_chunk_context(target)) {
        release_neighbors(job);
        return;
    }

    uint8_t expected = STATE_1_RECONSTRUCTED;
    if (!target->state.compare_exchange_strong(
            expected,
            STATE_2_HALO_READY,
            std::memory_order_acq_rel,
            std::memory_order_acquire)) {
        pipeline_release_chunk_context(target);
        release_neighbors(job);
        return;
    }

    job.pool_slot_idx = acquire_pool_slot(hw);
    if (job.pool_slot_idx == INVALID_POOL_SLOT) {
        target->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
        pipeline_release_chunk_context(target);
        release_neighbors(job);
        return;
    }

    job.target = target;
    if (!pipeline_enqueue_mesh_job(hw, job)) {
        target->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
        release_pool_slot_if_valid(hw, job.pool_slot_idx);
        pipeline_release_chunk_context(target);
        release_neighbors(job);
    }
}

void pipeline_cascade_and_try_dispatch_around(
    PipelineHardwareContext* hw,
    int32_t chunk_x,
    int32_t chunk_z,
    uint32_t block_packed) {
    if (!hw) return;

    ChunkContext* contexts[9] = {
        pipeline_get_chunk_context_retained(chunk_x, chunk_z),
        pipeline_get_chunk_context_retained(chunk_x + 1, chunk_z),
        pipeline_get_chunk_context_retained(chunk_x - 1, chunk_z),
        pipeline_get_chunk_context_retained(chunk_x, chunk_z + 1),
        pipeline_get_chunk_context_retained(chunk_x, chunk_z - 1),
        pipeline_get_chunk_context_retained(chunk_x + 1, chunk_z + 1),
        pipeline_get_chunk_context_retained(chunk_x + 1, chunk_z - 1),
        pipeline_get_chunk_context_retained(chunk_x - 1, chunk_z + 1),
        pipeline_get_chunk_context_retained(chunk_x - 1, chunk_z - 1)
    };

    const uint8_t local_x = static_cast<uint8_t>(UNPACK_X(block_packed));
    const uint8_t local_z = static_cast<uint8_t>(UNPACK_Z(block_packed));

    bool affected[9] = {true, false, false, false, false, false, false, false, false};
    affected[1] = local_x == 15;
    affected[2] = local_x == 0;
    affected[3] = local_z == 15;
    affected[4] = local_z == 0;
    affected[5] = local_x == 15 && local_z == 15;
    affected[6] = local_x == 15 && local_z == 0;
    affected[7] = local_x == 0 && local_z == 15;
    affected[8] = local_x == 0 && local_z == 0;

    for (int i = 0; i < 9; ++i) {
        ChunkContext* ctx = contexts[i];
        if (!ctx || !affected[i] || ctx->retired.load(std::memory_order_acquire)) continue;
        ctx->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
        ctx->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
    }

    for (ChunkContext* ctx : contexts) {
        if (ctx) pipeline_try_dispatch_mesh_job(hw, ctx);
    }

    for (ChunkContext* ctx : contexts) {
        pipeline_release_chunk_context(ctx);
    }
}

void pipeline_apply_dirty_block_updates(
    PipelineHardwareContext* hw,
    const uint64_t* keys,
    const uint64_t* values,
    size_t count) {
    if (!hw || !keys || !values || count == 0) return;

    struct AffectedUpdate {
        int32_t chunk_x;
        int32_t chunk_z;
        uint32_t block_packed;
    };

    std::vector<AffectedUpdate> affected;
    affected.reserve(count);

    for (size_t i = 0; i < count; ++i) {
        const uint64_t key = keys[i];
        const uint64_t value = values[i];

        int32_t chunk_x = static_cast<int32_t>(static_cast<int16_t>(key & 0xFFFFu));
        int32_t chunk_z = static_cast<int32_t>(static_cast<int16_t>((key >> 16) & 0xFFFFu));
        uint32_t block_packed = static_cast<uint32_t>((key >> 32) & 0x03FFFFFFu);

        uint16_t new_state_id = static_cast<uint16_t>(value & 0xFFFFu);
        uint8_t sky_light = static_cast<uint8_t>((value >> 16) & 0x0Fu);
        uint8_t block_light = static_cast<uint8_t>((value >> 20) & 0x0Fu);

        ChunkContext* ctx = pipeline_get_chunk_context_retained(chunk_x, chunk_z);
        if (!ctx) continue;

        bool updated = false;
        {
            std::lock_guard<std::mutex> lock(ctx->update_mutex);
            uint16_t* blocks = ctx->h_block_ids.load(std::memory_order_acquire);
            uint8_t* lights = ctx->h_light_ids.load(std::memory_order_acquire);
            if (blocks && lights) {
                const uint8_t lx = static_cast<uint8_t>(UNPACK_X(block_packed));
                const uint16_t ly = static_cast<uint16_t>(UNPACK_Y(block_packed));
                const uint8_t lz = static_cast<uint8_t>(UNPACK_Z(block_packed));
                if (lx < 16 && ly < CHUNK_HEIGHT && lz < 16) {
                    uint32_t idx = block_index(lx, ly, lz);
                    blocks[idx] = new_state_id;
                    lights[idx] = static_cast<uint8_t>((sky_light << 4) | block_light);
                    ctx->dirty_block_coords.push_back(block_packed);
                    ctx->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
                    ctx->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
                    updated = true;
                }
            }
        }

        pipeline_release_chunk_context(ctx);

        if (updated) {
            affected.push_back({chunk_x, chunk_z, block_packed});
        }
    }

    for (const AffectedUpdate& update : affected) {
        pipeline_cascade_and_try_dispatch_around(
            hw,
            update.chunk_x,
            update.chunk_z,
            update.block_packed);
    }
}

void pipeline_upload_chunk_light(
    PipelineHardwareContext* hw,
    int32_t chunk_x,
    int32_t chunk_z,
    const uint8_t* light_ids) {
    if (!hw || !light_ids) return;

    ChunkContext* ctx = pipeline_get_chunk_context_retained(chunk_x, chunk_z);
    if (!ctx) return;

    bool replaced = pipeline_replace_chunk_lights(ctx, light_ids);
    pipeline_release_chunk_context(ctx);
    if (!replaced) return;

    for (int dz = -1; dz <= 1; ++dz) {
        for (int dx = -1; dx <= 1; ++dx) {
            ChunkContext* affected = pipeline_get_chunk_context_retained(chunk_x + dx, chunk_z + dz);
            if (!affected) continue;
            affected->mesh_generation.fetch_add(1, std::memory_order_acq_rel);
            affected->state.store(STATE_1_RECONSTRUCTED, std::memory_order_release);
            pipeline_try_dispatch_mesh_job(hw, affected);
            pipeline_release_chunk_context(affected);
        }
    }
}

void pipeline_upload_block_properties(const uint8_t* data, size_t size) {
    if (!data || size != BLOCK_FACE_PROPERTY_TABLE_BYTES) {
        PIPELINE_LOG(
            "ignored bad block property table upload (size=%llu expected=%llu)",
            static_cast<unsigned long long>(size),
            static_cast<unsigned long long>(BLOCK_FACE_PROPERTY_TABLE_BYTES));
        return;
    }

    std::memcpy(g_block_face_properties, data, BLOCK_FACE_PROPERTY_TABLE_BYTES);
    g_block_face_properties_uploaded.store(true, std::memory_order_release);
    PIPELINE_LOG("block face property table uploaded (%llu bytes)",
                 static_cast<unsigned long long>(BLOCK_FACE_PROPERTY_TABLE_BYTES));
}

bool should_render_face(uint16_t block_id, uint16_t neighbor_id, uint32_t face_dir) {
    uint8_t props = face_properties(block_id, face_dir);
    if ((props & PROP_PASS_MASK) == 0) return false;

    uint8_t neighbor_props = face_properties(neighbor_id, face_dir ^ 1u);
    return (neighbor_props & PROP_OCCLUDES) == 0;
}

void load_snapshot_safe(ChunkContext* src, ChunkSnapshot& dst, uint32_t* out_generation) {
    dst.allocated = false;
    if (out_generation) *out_generation = 0;
    if (!src || src->retired.load(std::memory_order_acquire)) return;

    std::lock_guard<std::mutex> lock(src->update_mutex);

    uint16_t* src_blocks = src->h_block_ids.load(std::memory_order_acquire);
    uint8_t* src_lights = src->h_light_ids.load(std::memory_order_acquire);
    if (!src_blocks || !src_lights) return;

    if (dst.blocks.size() != CHUNK_BLOCK_COUNT) dst.blocks.resize(CHUNK_BLOCK_COUNT);
    if (dst.lights.size() != CHUNK_BLOCK_COUNT) dst.lights.resize(CHUNK_BLOCK_COUNT);

    std::memcpy(dst.blocks.data(), src_blocks, CHUNK_BLOCK_COUNT * sizeof(uint16_t));
    std::memcpy(dst.lights.data(), src_lights, CHUNK_BLOCK_COUNT * sizeof(uint8_t));
    if (out_generation) {
        *out_generation = src->mesh_generation.load(std::memory_order_acquire);
    }
    dst.allocated = true;
}

uint16_t get_block_at_isolated(WorkerWorkspace* ws, int32_t lx, int32_t ly, int32_t lz) {
    if (ly < 0 || ly >= CHUNK_HEIGHT) return BLOCK_AIR;

    int32_t routed_x = 0;
    int32_t routed_z = 0;
    const ChunkSnapshot* snap = route_snapshot(ws, lx, lz, &routed_x, &routed_z);
    if (!snap || !snap->allocated) return BLOCK_AIR;
    if (routed_x < 0 || routed_x >= 16 || routed_z < 0 || routed_z >= 16) {
        return BLOCK_AIR;
    }

    return snap->blocks[block_index(
        static_cast<uint32_t>(routed_x),
        static_cast<uint32_t>(ly),
        static_cast<uint32_t>(routed_z))];
}

uint8_t get_sky_light_at_isolated(WorkerWorkspace* ws, int32_t lx, int32_t ly, int32_t lz) {
    return static_cast<uint8_t>((get_light_byte_at_isolated(ws, lx, ly, lz, 0xF0u) >> 4) & 0x0Fu);
}

uint8_t get_block_light_at_isolated(WorkerWorkspace* ws, int32_t lx, int32_t ly, int32_t lz) {
    return static_cast<uint8_t>(get_light_byte_at_isolated(ws, lx, ly, lz, 0x00u) & 0x0Fu);
}

uint32_t cpu_mesh_extract_isolated(
    WorkerWorkspace* ws,
    uint32_t* out_opaque_count,
    uint32_t* out_cutout_count,
    uint32_t* out_translucent_count) {
    if (!ws || !ws->target_snap.allocated) {
        if (out_opaque_count) *out_opaque_count = 0;
        if (out_cutout_count) *out_cutout_count = 0;
        if (out_translucent_count) *out_translucent_count = 0;
        return 0;
    }

    ws->opaque_buf.clear();
    ws->cutout_buf.clear();
    ws->translucent_buf.clear();
    ws->opaque_count = 0;
    ws->cutout_count = 0;
    ws->translucent_count = 0;

    static constexpr int32_t dx[6] = {1, -1, 0, 0, 0, 0};
    static constexpr int32_t dy[6] = {0, 0, 0, 0, 1, -1};
    static constexpr int32_t dz[6] = {0, 0, 1, -1, 0, 0};

    for (int32_t y = 0; y < CHUNK_HEIGHT; ++y) {
        for (int32_t z = 0; z < 16; ++z) {
            for (int32_t x = 0; x < 16; ++x) {
                uint16_t id = get_block_at_isolated(ws, x, y, z);
                if (id == BLOCK_AIR) continue;

                for (uint32_t face = 0; face < 6; ++face) {
                    uint16_t neighbor = get_block_at_isolated(
                        ws,
                        x + dx[face],
                        y + dy[face],
                        z + dz[face]);
                    if (should_render_face(id, neighbor, face)) {
                        append_face(
                            ws,
                            face_properties(id, face),
                            id,
                            face,
                            static_cast<uint32_t>(x),
                            static_cast<uint32_t>(y),
                            static_cast<uint32_t>(z));
                    }
                }
            }
        }
    }

    if (out_opaque_count) *out_opaque_count = ws->opaque_count;
    if (out_cutout_count) *out_cutout_count = ws->cutout_count;
    if (out_translucent_count) *out_translucent_count = ws->translucent_count;
    return ws->opaque_count + ws->cutout_count + ws->translucent_count;
}

void release_pool_slot_if_valid(PipelineHardwareContext* hw, uint32_t slot) {
    if (!hw || slot == INVALID_POOL_SLOT) return;
    std::lock_guard<std::mutex> lock(hw->pool_mutex);
    if (slot < MAX_CONCURRENT_UPDATES) {
        hw->pool_slot_busy[slot] = false;
    }
}
