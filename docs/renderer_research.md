# Renderer research: Sodium / Embeddium / Voxy — and how to beat them

Deep-dive notes from reading the actual source (LGPL-3.0 — **reimplement techniques, do not
paste code**). Goal: rebuild a region-batched, GPU-driven, LOD-capable terrain renderer for
Plutonium that exploits our native C++ mesher + 64-bit packed faces + CUDA.

Source read under `F:\minecraft_mod-dev\resherch\proformace\plutunium\mods-dev\`:
`sodium-dev/`, `embeddium-21.4-neoforge/`, `voxy-dev/`.

Our current measured bottleneck (2026-05-30): **CPU terrain submission ~10 ms** at RD32
(vanilla's per-section `bind+uniform+draw` loop, ~17,500 calls/frame). GPU ~6.5 ms / 34% util.
Not VRAM-bound. Fix = collapse submission. Everything below is how.

---

## TIER 1 — Sodium/Embeddium: region-batched MultiDrawIndirect (the foundation)

### Integration (how it takes over vanilla — Forge)
`LevelRenderer` mixin **@Overwrites** the terrain methods → a custom `WorldRenderer` owning a
`RenderSectionManager`:
- `setupRender` → `setupTerrain(camera, viewport, frame, spectator)` — cull + visible set
- `renderChunkLayer`/`renderSectionLayer(RenderType,...)` → `drawChunkLayer(...)` — the draw
- `setSectionDirty` / `setBlocksDirty` / `setSectionDirtyWithNeighbors` / `setBlockDirty` → `scheduleRebuild*`
- `needsUpdate` → `scheduleTerrainUpdate`; `allChanged` → `reload`; `isSectionCompiled` → `isSectionReady`
- `countRenderedSections` / `getSectionStatistics` → F3 stats
- chunk load/unload via `ClientChunkCache` mixin → `onSectionAdded/Removed`
Our deleted `LevelRendererSolidLayerMixin` only did solid — partial. Full takeover replaces all of the above.

### Data layout (the core idea: flat native memory, not Java objects)
- **Region = 8×4×8 = 256 sections** (`RenderRegion`). One region → one growing geometry buffer.
- **`SectionRenderDataUnsafe`**: 48-byte off-heap struct **per section per pass**, in a flat native
  array (`MemoryUtil.nmemAlignedAlloc`, `MemoryIntrinsics`). Fields:
  `base_element(u32) | base_vertex(u32) | facing_list(u56)+is_local_index(u8) | slice_mask(u32) | vertex_count[7](u32)`.
  Geometry is split into **7 facing groups** (ModelQuadFacing: UNASSIGNED + 6 dirs); each group's
  verts are contiguous → enables **branchless per-slice face culling** at draw time.
  (Comment in source: a rant about how Java/Hotspot is unusable for this; they hand-roll native structs.)
- **`GlBufferArena`**: linked-list free-list allocator over ONE `GlMutableBuffer` per region.
  `alloc`=best-fit+split, `free`=coalesce. On overflow → allocate bigger buffer, **GPU-compact** used
  segments via `glCopyBufferSubData` (batched runs), recycle old buffers (static pool of 8). Max 4 GiB/arena.
  After resize → `SectionRenderDataStorage.onBufferResized()` rewrites every `base_vertex`.
- **Vertex = `CompactChunkVertex`, 20 bytes**: pos 20-bit/axis (2 ints, quantized over 32-block model
  range), color+AO (int), UV 16-bit each w/ sign-bias bleed trick (int), light+material+**local section
  index byte** (int). The section-index-in-vertex is how the shader gets per-section translation — **no
  per-section uniform**. One `setRegionOffset` uniform per region; shader does
  `world = regionOffset + sectionLocalOffset(sectionIndex) + quantizedPos`.

### Visibility (`OcclusionCuller.findVisible`) — CPU BFS flood-fill
- Double-buffered primitive queue, BFS by distance layer from the camera section.
- Per section: frustum test (`viewport.isBoxVisible`) + cylindrical render-distance test. If visible →
  `visitor.visit` (adds to render list + build-task queue).
- Occlusion: each section has a **face-to-face visibility bitmask** (computed at mesh time — vanilla's
  in-section flood-fill of which faces connect). You only traverse OUT through faces reachable from the
  faces you came IN through (`getConnections(visibilityData, incomingDirections)`), masked by an
  **angle mask** (steep view kills perpendicular paths) and **outward-only** directions. Result:
  frustum ∩ visibility-flood — exactly vanilla "smart cull", but on primitives, no GC.

### Async budgeted build (the anti-stutter, `RenderSectionManager.updateChunks`)
- EMA of frame duration → budgets: **upload budget = 10% of frame** (min 2 ms) + worker capacity.
- Tasks bucketed by priority: ZERO_FRAME_DEFER (important/blocking, awaited THIS frame),
  ONE_FRAME_DEFER (awaited next frame), ALWAYS_DEFER, INITIAL_BUILD. Only nearby/important sections
  block; everything else builds async across frames → **no upload spikes**.
- Estimators (`JobDurationEstimator`, `MeshTaskSizeEstimator`, `UploadDurationEstimator`) predict task
  cost to stay in budget. Nearby sections (≤64 blocks, visible-or-adjacent) force-presented to avoid holes.
- Build → `BuiltSectionMeshParts` (per-facing vertex segments) + `BuiltSectionInfo` (visibility bitmask,
  flags, animated sprites). Upload path (`RenderRegionManager.uploadResults`): batched per region, via a
  **persistent-mapped staging buffer** (`MappedStagingBuffer`) → `glCopyBufferSubData` into the arena
  (no `glBufferSubData` stalls), then writes the 48-byte struct.

### Draw (`DefaultChunkRenderer.render`) — one MDI call per region per pass
```
for each region in renderList (per pass):
  batch = region.cachedBatch(pass)           // cached! rebuilt only when visible set changes
  if !batch.isFilled: fillCommandBuffer(...)  // per visible section: face-cull slices via slice_mask
                                              //   & camera mask (getVisibleFaces, branchless), emit
                                              //   (elementCount, baseVertex, elementPtr) into native cmd buf
  setRegionOffset(uniform)                    // ONE uniform per region
  glMultiDrawElementsBaseVertex(batch)        // ONE draw call for ~256 sections × 7 slices
```
RD32 math: ~3,500 sections → ~14 regions × 5 passes ≈ **70 draws** vs vanilla's ~17,500.

---

## TIER 2 — Voxy: fully GPU-driven submission (beat Sodium)

CPU uploads geometry SSBO + per-section metadata SSBO once; the GPU does cull + command-gen + draw.
`MDICSectionRenderer.buildDrawCalls`:
1. **prep.comp** (1×1×1): sizes the indirect dispatch for later stages (GPU decides workgroup counts),
   writes `drawCountCallBuffer` (also used as `GL_DISPATCH_INDIRECT_BUFFER`).
2. **Occlusion = rasterize section AABBs vs depth**: `cullShader` (raster.vert/frag), depth-test on,
   color+depth writes OFF, `NV_representative_fragment_test` for early-out, one `glDrawElementsIndirect`
   of all boxes → writes `visibilityBuffer` (per-section visible flag). (Tests against Hi-Z / prev depth.)
3. **cmdgen.comp** (`glDispatchComputeIndirect`): reads `metadataBuffer` + `visibilityBuffer` → writes
   `drawCallBuffer` (indirect cmds) + count + per-section distances (`distanceCountBuffer`).
4. **Translucency**: `prefixSum` over distance buckets + `buildtranslucents.comp` → back-to-front cmds.
5. Render: **ONE** `glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOff,
   drawCountOff, maxDrawCount)` per pass. GPU reads its own command+count buffers.
- **Vertex pulling**: no VAO attributes (dummy `STATIC_VAO`); `quads3.vert` reads the geometry SSBO by
  `gl_VertexID`/draw id and expands packed quads. Shared u16 index buffer. **This is exactly how our
  packed 64-bit faces want to be drawn.**
- CPU per-frame cost ≈ 5 dispatches + 3 indirect draws, **independent of section count.**

---

## TIER 3 — Voxy: octree LOD + Hi-Z, GPU-driven traversal (beat Distant Horizons)

`HierarchicalOcclusionTraverser.doTraversal`:
- World = **GPU-resident octree** (`nodeBuffer`, 16 B/node). CPU `AsyncNodeManager` maintains Top-Level
  Nodes (roots) as the player moves.
- Traversal = BFS descent, **one compute dispatch per LOD level** (`MAX_LOD_LAYER+1` iterations,
  flip-flop source/sink scratch queues; `queueMetaBuffer` holds the indirect dispatch size for the next
  level → `glDispatchComputeIndirect`). Per node the `traversal_dev.comp` shader does:
  - **frustum cull** (6 planes in uniform),
  - **Hi-Z occlusion cull** (sample `hiZBuffer`, `GL_NEAREST_MIPMAP_NEAREST` sampler, at the node's
    screen AABB; cull if behind),
  - **screen-space-error LOD select**: project node size; if > threshold → write its 8 children to the
    sink queue (descend); else → add to render list at this LOD. (Automatic distance LOD.)
  - if geometry missing → write a **request** to `requestBuffer`.
- Output `renderList` feeds Tier-2 cmdgen → MDIC. GPU→CPU **request feedback**: download request queue →
  `nodeManager.submitRequestBatch` → async mesh gen, **throttled by mesher backpressure**
  (TARGET_COUNT=4000 vs current task count). Node eviction via `NodeCleaner` visibility tracking.

---

## How this maps onto OUR existing assets

| Their thing | Ours (mostly already built, in `chunk_pipeline.cpp/.h`) |
|---|---|
| Region geometry arena | `persistent_geometry_faces_ptr` (persistent SSBO of packed faces), triple-buffered (FRAME_LAG=3) |
| 48-byte per-section struct / metadata SSBO | `ChunkMetadataGpu{origin_xyz, face_base_offset}` |
| MDI command buffer | `DrawArraysIndirectCommand[]`, built in `pipeline_begin_frame` (CPU, Tier-1 style) |
| 20-byte vertex | **`PACK_FACE` 64-bit/quad = 8 B/quad → ~10× smaller than Sodium's 80 B/quad** (our edge) |
| Sodium CPU mesher | our native C++ thread-pool mesher (no JVM tax) |
| Voxy GL compute cull | we have **CUDA** + CUDA↔GL interop (deleted `CudaPipeline`) — hybrid no one else has |
| LevelRenderer takeover | deleted `LevelRendererSolidLayerMixin` (partial) → needs full @Overwrite set |

**Our differentiators:** 8 B/quad packed faces (vs 80), native mesher, CUDA noise + (potential) CUDA
cull/LOD building, GL compute for the draw-list. Submission itself is at parity (MDI is optimal) — we win
on the layers around it. Per-vertex smooth lighting needs the face payload → ~96 bits (12 B/quad, still
~7× smaller than Sodium).

## Proposed build order (each stacks on the previous)
1. **Tier 1 revive**: restore the region-MDI renderer (commit `18368f6`) + full LevelRenderer takeover;
   per-section offset via SSBO indexed by draw id (not uniform); cached per-region command buffers
   (rebuild only on visible-set change); branchless face-slice culling from our packed face direction;
   buffer-arena sub-allocator. → Sodium parity, fixes the measured 10 ms.
2. **Tier 2 GPU-driven**: move frustum/face cull + command-gen into a compute shader reading
   `ChunkMetadataGpu`; `glMultiDrawElementsIndirectCount`; vertex-pull the 64-bit faces. → beat Sodium.
3. **Tier 3 LOD**: octree of downsampled sections + GPU traversal w/ Hi-Z + screen-space LOD + request
   feedback. CUDA can build LOD nodes. → beat Distant Horizons.
   Worldgen axis (already in progress): C2ME parallelism + CUDA noise feeds high-RD streaming.

## Anti-stutter (must port regardless of tier)
EMA frame-time → bounded per-frame upload budget; important-vs-deferred build queues; persistent-mapped
staging buffer for uploads (no `glBufferSubData` / no sync). This is what kept Sodium smooth and what
our profiler flagged as the upload-frame spikes.
