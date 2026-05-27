package com.plutonium.backbone.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * First-pass far chunk LOD for the solid layer takeover.
 *
 * <p>Far chunks are snapshotted into a coarse voxel volume by
 * {@link PlutoniumChunkCache}. The renderer then emits a simple exposed-face mesh
 * for the whole chunk volume, not only the heightmap surface. This is deliberately
 * rough: it is meant to make the LOD boundary visible and prove the cache/slider
 * flow before lighting, tinting, fluids, and smarter decimation are polished.
 */
public final class PlutoniumLodRenderer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int CELL_SIZE_XZ = PlutoniumChunkCache.CELL_SIZE_XZ;
    private static final int CELL_SIZE_Y = PlutoniumChunkCache.CELL_SIZE_Y;
    private static final int CELLS_PER_CHUNK_XZ = PlutoniumChunkCache.CELLS_PER_CHUNK_XZ;
    private static final int VERTEX_BYTES = PlutoniumMegaBuffer.VERTEX_BYTES;
    private static final int LIGHT_FULL_BRIGHT = 0x00F0;
    private static final int MAX_BUILDS_PER_FRAME = 2;

    private static final Long2ObjectOpenHashMap<LodAlloc> cache = new Long2ObjectOpenHashMap<>();
    private static final Map<UvKey, UvRect> faceUvCache = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create(42L);
    private static long lastDiagnosticsNs = System.nanoTime();
    private static int buildsThisFrame;

    private PlutoniumLodRenderer() {
    }

    public static Frame beginFrame(boolean enabled, int renderDistance, int lodStartDistance, double cameraX, double cameraZ) {
        buildsThisFrame = 0;
        if (!enabled) {
            return Frame.disabled(renderDistance, lodStartDistance);
        }
        return new Frame(true, renderDistance, lodStartDistance, blockToChunk(cameraX), blockToChunk(cameraZ));
    }

    public static void invalidateChunk(int chunkX, int chunkZ) {
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(() -> invalidateChunk(chunkX, chunkZ));
            return;
        }

        PlutoniumChunkCache.invalidate(chunkX, chunkZ);
        invalidateMesh(chunkX, chunkZ);
        invalidateMesh(chunkX + 1, chunkZ);
        invalidateMesh(chunkX - 1, chunkZ);
        invalidateMesh(chunkX, chunkZ + 1);
        invalidateMesh(chunkX, chunkZ - 1);
    }

    public static void clear() {
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(PlutoniumLodRenderer::clear);
            return;
        }
        for (LodAlloc alloc : cache.values()) {
            PlutoniumMegaBuffer.free(alloc.byteOffset, alloc.byteSize);
        }
        cache.clear();
        faceUvCache.clear();
        PlutoniumChunkCache.clear();
    }

    private static void invalidateMesh(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        LodAlloc alloc = cache.remove(key);
        if (alloc != null) {
            PlutoniumMegaBuffer.free(alloc.byteOffset, alloc.byteSize);
        }
    }

    private static LodAlloc ensureColumnMesh(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        LodAlloc cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (buildsThisFrame >= MAX_BUILDS_PER_FRAME) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.getBlockRenderer() == null) {
            return null;
        }

        buildsThisFrame++;
        ByteBuffer mesh = buildColumnMesh(mc.level, mc.getBlockRenderer(), chunkX, chunkZ);
        if (mesh == null || mesh.remaining() < VERTEX_BYTES * 4) {
            return null;
        }

        int byteSize = mesh.remaining();
        long offset = PlutoniumMegaBuffer.alloc(mesh);
        if (offset < 0) {
            return null;
        }

        LodAlloc alloc = new LodAlloc(offset, byteSize, byteSize / VERTEX_BYTES);
        cache.put(key, alloc);
        return alloc;
    }

    private static ByteBuffer buildColumnMesh(ClientLevel level, BlockRenderDispatcher blockRenderer, int chunkX, int chunkZ) {
        PlutoniumChunkCache.ChunkSnapshot snapshot = PlutoniumChunkCache.getOrCapture(level, chunkX, chunkZ);
        if (snapshot == null) {
            return null;
        }

        int maxVerts = snapshot.cellCount() * 6 * 4;
        ByteBuffer out = ByteBuffer.allocateDirect(maxVerts * VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int cellY = 0; cellY < snapshot.verticalCells(); cellY++) {
            int y0i = snapshot.minY() + cellY * CELL_SIZE_Y;
            int y1i = Math.min(y0i + CELL_SIZE_Y, snapshot.maxY());
            if (y1i <= y0i) {
                continue;
            }

            for (int cellZ = 0; cellZ < CELLS_PER_CHUNK_XZ; cellZ++) {
                int z0i = baseZ + cellZ * CELL_SIZE_XZ;
                int z1i = z0i + CELL_SIZE_XZ;
                for (int cellX = 0; cellX < CELLS_PER_CHUNK_XZ; cellX++) {
                    BlockState state = snapshot.cell(cellX, cellY, cellZ);
                    if (state == null) {
                        continue;
                    }

                    int x0i = baseX + cellX * CELL_SIZE_XZ;
                    int x1i = x0i + CELL_SIZE_XZ;
                    float x0 = x0i;
                    float x1 = x1i;
                    float y0 = y0i;
                    float y1 = y1i;
                    float z0 = z0i;
                    float z1 = z1i;

                    if (neighborState(snapshot, cellX, cellY + 1, cellZ) == null) {
                        UvRect uv = faceUv(blockRenderer, state, Direction.UP);
                        if (uv == null) continue;
                        putQuad(out, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, uv, 0, 127, 0);
                    }
                    if (neighborState(snapshot, cellX, cellY - 1, cellZ) == null) {
                        UvRect uv = faceUv(blockRenderer, state, Direction.DOWN);
                        if (uv == null) continue;
                        putQuad(out, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, uv, 0, -127, 0);
                    }
                    if (neighborState(snapshot, cellX + 1, cellY, cellZ) == null) {
                        UvRect uv = faceUv(blockRenderer, state, Direction.EAST);
                        if (uv == null) continue;
                        putQuad(out, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, uv, 127, 0, 0);
                    }
                    if (neighborState(snapshot, cellX - 1, cellY, cellZ) == null) {
                        UvRect uv = faceUv(blockRenderer, state, Direction.WEST);
                        if (uv == null) continue;
                        putQuad(out, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, uv, -127, 0, 0);
                    }
                    if (neighborState(snapshot, cellX, cellY, cellZ + 1) == null) {
                        UvRect uv = faceUv(blockRenderer, state, Direction.SOUTH);
                        if (uv == null) continue;
                        putQuad(out, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, uv, 0, 0, 127);
                    }
                    if (neighborState(snapshot, cellX, cellY, cellZ - 1) == null) {
                        UvRect uv = faceUv(blockRenderer, state, Direction.NORTH);
                        if (uv == null) continue;
                        putQuad(out, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, uv, 0, 0, -127);
                    }
                }
            }
        }

        out.flip();
        return out;
    }

    private static BlockState neighborState(PlutoniumChunkCache.ChunkSnapshot snapshot, int cellX, int cellY, int cellZ) {
        if (cellY < 0 || cellY >= snapshot.verticalCells()) {
            return null;
        }
        if (cellX >= 0 && cellX < CELLS_PER_CHUNK_XZ && cellZ >= 0 && cellZ < CELLS_PER_CHUNK_XZ) {
            return snapshot.cell(cellX, cellY, cellZ);
        }

        int neighborChunkX = snapshot.chunkX();
        int neighborChunkZ = snapshot.chunkZ();
        int neighborCellX = cellX;
        int neighborCellZ = cellZ;
        if (cellX < 0) {
            neighborChunkX--;
            neighborCellX = CELLS_PER_CHUNK_XZ - 1;
        } else if (cellX >= CELLS_PER_CHUNK_XZ) {
            neighborChunkX++;
            neighborCellX = 0;
        } else if (cellZ < 0) {
            neighborChunkZ--;
            neighborCellZ = CELLS_PER_CHUNK_XZ - 1;
        } else if (cellZ >= CELLS_PER_CHUNK_XZ) {
            neighborChunkZ++;
            neighborCellZ = 0;
        }

        PlutoniumChunkCache.ChunkSnapshot neighbor = PlutoniumChunkCache.getCached(neighborChunkX, neighborChunkZ);
        if (neighbor == null) {
            return null;
        }
        return neighbor.cell(neighborCellX, cellY, neighborCellZ);
    }

    private static UvRect faceUv(BlockRenderDispatcher blockRenderer, BlockState state, Direction direction) {
        UvKey key = new UvKey(state, direction);
        UvRect cached = faceUvCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            RANDOM.setSeed(42L);
            BakedModel model = blockRenderer.getBlockModel(state);
            List<BakedQuad> quads = model.getQuads(state, direction, RANDOM, ModelData.EMPTY, null);
            if (quads == null || quads.isEmpty()) {
                RANDOM.setSeed(42L);
                quads = model.getQuads(state, Direction.UP, RANDOM, ModelData.EMPTY, null);
            }
            if (quads == null || quads.isEmpty()) {
                return null;
            }
            int[] vertices = quads.get(0).getVertices();
            int stride = vertices.length / 4;
            if (stride <= 5) {
                return null;
            }
            float minU = Float.POSITIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                float u = Float.intBitsToFloat(vertices[i * stride + 4]);
                float v = Float.intBitsToFloat(vertices[i * stride + 5]);
                minU = Math.min(minU, u);
                minV = Math.min(minV, v);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, v);
            }
            UvRect uv = new UvRect(minU, minV, maxU, maxV);
            faceUvCache.put(key, uv);
            return uv;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void putQuad(ByteBuffer out,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                UvRect uv, int nx, int ny, int nz) {
        putVertex(out, x0, y0, z0, uv.minU, uv.minV, nx, ny, nz);
        putVertex(out, x1, y1, z1, uv.minU, uv.maxV, nx, ny, nz);
        putVertex(out, x2, y2, z2, uv.maxU, uv.maxV, nx, ny, nz);
        putVertex(out, x3, y3, z3, uv.maxU, uv.minV, nx, ny, nz);
    }

    private static void putVertex(ByteBuffer out, float x, float y, float z, float u, float v, int nx, int ny, int nz) {
        out.putFloat(x);
        out.putFloat(y);
        out.putFloat(z);
        out.putInt(0xFFFFFFFF);
        out.putFloat(u);
        out.putFloat(v);
        out.putShort((short) LIGHT_FULL_BRIGHT);
        out.putShort((short) LIGHT_FULL_BRIGHT);
        out.put((byte) nx);
        out.put((byte) ny);
        out.put((byte) nz);
        out.put((byte) 0);
    }

    private static int blockToChunk(double blockCoordinate) {
        return (int) Math.floor(blockCoordinate / 16.0D);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private record LodAlloc(long byteOffset, int byteSize, int vertexCount) {
    }

    private record UvKey(BlockState state, Direction direction) {
    }

    private record UvRect(float minU, float minV, float maxU, float maxV) {
    }

    public static final class Frame {
        private final boolean enabled;
        private final int renderDistance;
        private final int lodStartDistance;
        private final int cameraChunkX;
        private final int cameraChunkZ;
        private final LongOpenHashSet acceptedColumns = new LongOpenHashSet();
        private int[] counts = new int[128];
        private int[] baseVerts = new int[128];
        private int drawCount;
        private int coveredSections;
        private int candidateSections;

        private Frame(boolean enabled, int renderDistance, int lodStartDistance, int cameraChunkX, int cameraChunkZ) {
            this.enabled = enabled;
            this.renderDistance = renderDistance;
            this.lodStartDistance = lodStartDistance;
            this.cameraChunkX = cameraChunkX;
            this.cameraChunkZ = cameraChunkZ;
        }

        private static Frame disabled(int renderDistance, int lodStartDistance) {
            return new Frame(false, renderDistance, lodStartDistance, 0, 0);
        }

        public boolean accept(BlockPos sectionOrigin) {
            if (!enabled) {
                return false;
            }
            int chunkX = sectionOrigin.getX() >> 4;
            int chunkZ = sectionOrigin.getZ() >> 4;
            int distance = Math.max(Math.abs(chunkX - cameraChunkX), Math.abs(chunkZ - cameraChunkZ));
            if (distance <= lodStartDistance) {
                return false;
            }
            candidateSections++;

            long key = chunkKey(chunkX, chunkZ);
            if (acceptedColumns.add(key)) {
                LodAlloc alloc = ensureColumnMesh(chunkX, chunkZ);
                if (alloc == null || alloc.vertexCount < 4) {
                    acceptedColumns.remove(key);
                    return false;
                }
                addDraw(alloc);
            }
            coveredSections++;
            return true;
        }

        private void addDraw(LodAlloc alloc) {
            if (drawCount == counts.length) {
                int newCap = counts.length * 2;
                int[] newCounts = new int[newCap];
                int[] newBaseVerts = new int[newCap];
                System.arraycopy(counts, 0, newCounts, 0, drawCount);
                System.arraycopy(baseVerts, 0, newBaseVerts, 0, drawCount);
                counts = newCounts;
                baseVerts = newBaseVerts;
            }
            counts[drawCount] = (alloc.vertexCount / 4) * 6;
            baseVerts[drawCount] = (int) (alloc.byteOffset / VERTEX_BYTES);
            drawCount++;
        }

        public void renderQueued() {
            if (drawCount <= 0) {
                return;
            }
            PlutoniumMegaBuffer.multiDrawElementsBaseVertex(counts, baseVerts, drawCount);
            emitDiagnosticsIfDue(this);
        }

        public int drawCount() {
            return drawCount;
        }

        public int coveredSections() {
            return coveredSections;
        }

        public int candidateSections() {
            return candidateSections;
        }

        public int columnCount() {
            return acceptedColumns.size();
        }

        public int renderDistance() {
            return renderDistance;
        }

        public int lodStartDistance() {
            return lodStartDistance;
        }
    }

    private static void emitDiagnosticsIfDue(Frame frame) {
        long now = System.nanoTime();
        if ((now - lastDiagnosticsNs) < 2_000_000_000L) {
            return;
        }
        lastDiagnosticsNs = now;
        LOGGER.info("[Plutonium/LOD] drawColumns={} coveredSections={} candidates={} lodStart={}/{} cachedColumns={} cachedSnapshots={} buildsThisFrame={}.",
                frame.drawCount(),
                frame.coveredSections(),
                frame.candidateSections(),
                frame.lodStartDistance(),
                frame.renderDistance(),
                cache.size(),
                PlutoniumChunkCache.size(),
                buildsThisFrame);
    }
}
