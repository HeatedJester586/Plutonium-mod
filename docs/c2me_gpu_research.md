# C2ME-gpu research — OpenCL GPU worldgen, and how to bring it into Plutonium

Deep read of `F:\…\resherch\proformace\plutunium\mods-dev\C2ME-gpu-latest\` (C2ME fabric
mc1.21.11 0.3.7+alpha.0.57). Goal: copy/adapt its GPU chunk-gen into Plutonium and improve it.

## What it is (the headline)

C2ME-gpu runs the **entire vanilla noise stage on the GPU** — biomes + aquifers + density +
block placement — and writes block states straight into chunk `PalettedContainer`s. Not just a
"density assist" like Plutonium's CUDA: it produces the actual blocks. Key properties:

- **OpenCL via LWJGL** (`org.lwjgl:lwjgl-opencl`) — pure Java, **no native DLL**, cross-vendor
  (NVIDIA/AMD/Intel; Windows/Linux/ARM natives bundled). *This eliminates the entire native-DLL /
  CRT-mismatch problem class Plutonium just hit.*
- **Runtime-compiled kernels**: the world's `NoiseRouter` density functions are compiled to OpenCL
  C at runtime (handles any datapack/dimension), not hardcoded.
- **16-chunk batches** (`BATCH_SIZE=4` → 4×4 chunks per dispatch).
- **Bit-exact vanilla parity**: `#pragma OPENCL FP_CONTRACT OFF` + careful codegen so GPU output
  matches CPU vanilla exactly (no "wrong blocks").

## Architecture (two modules)

**`c2me-opts-dfc`** — Density Function Compiler (the codegen):
- `ast/` `McToAst` + `AstNode` + `OptoPasses` — MC density function → AST → optimizer passes.
  (Same family as Plutonium's `BytecodeCompiler`/`DensityProgramEvaluator`, but more mature and
  with multiple backends: JVM-bytecode for CPU *and* OpenCL.)
- `gen/opencl/OpenCLGen.java` (650 ln) — `compile(noiseRouter, shapeConfig, …)` walks every router
  binding (continents, erosion, depth, ridges, final_density, vein toggle/ridged/gap, fluid levels,
  temperature, vegetation, biome tree…) → emits OpenCL C + const data + biome mapping table.
- `gen/opencl/GeneratedCLSource.java`, `CLBlockStateMappings.java` — generated source + block-index↔BlockState map.

**`c2me-opts-accel-opencl`** — the GPU dispatch + chunk-system integration:
- `common/gen/CLServerBatchedBiomeNoiseContext.java` (567 ln) — **the core dispatch.** Per 4×4 batch,
  chains kernels via CL events:
  1. `df_biome_multinoise_kernel` → biome indices
  2. `df_interpolator_buffer_prefill_kernel` → noise interpolation lattice
  3. `aquifer_data_prefill` (if `settings.hasAquifers()`)
  4. `df_cache2d_prefill_kernel` → 2D density caches
  5. `df_noise_kernel` → **block-state bytes** (bit 7 = needs fluid tick)
  Then async `clEnqueueReadBuffer` + `clSetEventCallback` on completion → `writeBiomes` / `writeBlocks`
  into the chunks' PalettedContainers, populate heightmaps, set `ChunkStatus.NOISE`. Skips chunks
  already ≥ NOISE/BIOMES (write-protect). CPU `genBiomesFallback` if needed.
- `common/gen/OpenCLDevice.java` (424), `CLServerWorldContext`, `CLServerGlobalContext` — device/context/
  program/queue management; `CLBufferCache` + `Stage1Cache` — buffer pooling + biome/surface-height cache.
- `common/enumeration/OpenCLDeviceLocator` + `OpenCLDeviceMetadata` — device discovery/selection.
- `common/workarounds/{nvidia,amd,intel}` + `Blocklists` — per-vendor quirks + a built-in bad-device blocklist.
- `mixin/…/{MixinNewStatusHook, MixinVanillaWorldGenerationDelegate, MixinChunkNoiseSampler,
  MixinThreadedAnvilChunkStorage}` + `chunksystem_integration/BatchingBiomeNoiseStatus` — slots the
  batched GPU step into C2ME's chunk-status pipeline.
- `resources/clsources/c2me_opencl_ext_math.cl` (2165 ln) — hand-written OpenCL runtime: integer/float
  types, Perlin/simplex primitives, splines, the building blocks the generated kernels call.
- `common/Config.java` — `openclAccel.*`: maxConcurrentTasksPerDevice(32), lowPriorityQueues (cl_khr
  priority/throttle hints to avoid FPS drop), preferFastCompilation, allow CPU/GPU/Accelerator,
  device UUID black/whitelist, incompatibility fallback.

## vs Plutonium's current CUDA worldgen

| | Plutonium (CUDA) | C2ME-gpu (OpenCL) |
|---|---|---|
| Scope | density lattice only ("assist") | full noise stage: biomes + density + blocks + aquifers |
| API | CUDA, **NVIDIA-only**, native C++ DLL + JNI | LWJGL OpenCL, **cross-vendor**, no DLL |
| Build pain | VS/CUDA/CRT (just caused the msvcp140 crash) | none (Java + bundled natives) |
| Kernels | hand-written `.cu` + density AST→bytecode | density AST→OpenCL C, runtime-compiled |
| Batch | per-chunk / small batches | 16 chunks/dispatch |
| Parity | needs care (had metadata/geometry bugs) | FP_CONTRACT OFF, designed bit-exact |
| Maturity | in-house, partial | upstream C2ME, production, vendor-aware + fallback |

Plutonium already has the *bones* (a density AST compiler in `worldgen/`), so the concepts transfer.

## The integration reality (important)

**You cannot copy it directly.** C2ME-gpu is **Fabric + MC 1.21.11**; Plutonium is **Forge + MC 1.20.1**.
Gaps: different mod loader (Fabric mixins/entrypoints vs Forge), different MC APIs (1.21 density/biome/
chunk vs 1.20.1), different mappings (Yarn vs Mojang/SRG). And the OpenCL module is **coupled to C2ME's
chunk-system rewrite** (`c2me-base`, FlowSched scheduler, the status pipeline) + the `c2me-opts-dfc` AST.

What IS portable with modest change: the **`OpenCLGen` codegen** and **`ext_math.cl`** read the
`NoiseRouter` / density-function API, which exists in 1.20.1 too (density functions landed in 1.18).
The hard part is the chunk-system integration (Forge-port the status hooks) + the AST module.

## Recommended paths (pick one)

- **A — Switch Plutonium worldgen to OpenCL, porting C2ME's codegen + kernels to Forge 1.20.1.**
  Biggest win: kills the native-DLL/CRT problem forever, cross-vendor, full noise stage. Effort: high,
  but reuses `OpenCLGen` + `ext_math.cl` + Plutonium's existing AST. *Recommended given the DLL crash.*
- **B — Keep CUDA, extend it from "density assist" to "full noise stage"** (biomes+blocks+aquifers,
  16-chunk batches) using Plutonium's existing AST compiler. Stays NVIDIA-only + keeps the DLL.
- **C — Port the whole C2ME (base + dfc + opencl + chunk-system) to Forge 1.20.1.** Most complete
  ("become C2ME-gpu on Forge"), largest effort; you've already ported pieces (prefetcher, pumps).

## Plutonium pieces that already align
`worldgen/BytecodeCompiler.java`, `DensityProgramEvaluator.java`, `Instruction/Opcode`,
`GpuDensityBatchCoordinator`, `FastGpuChunkMixin`, `NoiseChunkGpuDensityMixin` — the density-AST +
GPU-batch-dispatch scaffolding is already here; C2ME-gpu shows the finished form to grow it into.
