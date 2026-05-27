package com.plutonium.backbone.client;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory client chunk cache for far LOD generation.
 *
 * <p>This is intentionally Bobby-like in shape, but not persistent yet: chunks
 * are snapshotted while the client has them, then the LOD renderer may keep using
 * those snapshots after the live chunk is no longer queried. The snapshot is a
 * coarse voxel volume, not just a heightmap, so cliffs, overhangs, caves, and
 * structures can survive into far LOD.
 */
public final class PlutoniumChunkCache {

    public static final int CELL_SIZE_XZ = 8;
    public static final int CELL_SIZE_Y = 8;
    public static final int CELLS_PER_CHUNK_XZ = 16 / CELL_SIZE_XZ;

    private static final int MAX_CACHED_CHUNKS = 8192;

    private static final LinkedHashMap<Long, ChunkSnapshot> snapshots = new LinkedHashMap<>(1024, 0.75f, true);

    private PlutoniumChunkCache() {
    }

    public static synchronized ChunkSnapshot getOrCapture(ClientLevel level, int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        ChunkSnapshot cached = snapshots.get(key);
        if (cached != null) {
            return cached;
        }
        ChunkSnapshot snapshot = capture(level, chunkX, chunkZ);
        if (snapshot == null) {
            return null;
        }
        snapshots.put(key, snapshot);
        pruneIfNeeded();
        return snapshot;
    }

    public static synchronized ChunkSnapshot getCached(int chunkX, int chunkZ) {
        return snapshots.get(chunkKey(chunkX, chunkZ));
    }

    public static synchronized void invalidate(int chunkX, int chunkZ) {
        snapshots.remove(chunkKey(chunkX, chunkZ));
    }

    public static synchronized void clear() {
        snapshots.clear();
    }

    public static synchronized int size() {
        return snapshots.size();
    }

    private static ChunkSnapshot capture(ClientLevel level, int chunkX, int chunkZ) {
        if (level == null || !level.hasChunk(chunkX, chunkZ)) {
            return null;
        }

        LevelChunk chunk;
        try {
            chunk = level.getChunk(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return null;
        }
        if (chunk == null) {
            return null;
        }

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int verticalCells = Math.max(1, ((maxY - minY) + CELL_SIZE_Y - 1) / CELL_SIZE_Y);
        BlockState[] cells = new BlockState[CELLS_PER_CHUNK_XZ * verticalCells * CELLS_PER_CHUNK_XZ];
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = minY >> 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cellY = 0; cellY < verticalCells; cellY++) {
            int baseY = minY + cellY * CELL_SIZE_Y;
            for (int cellZ = 0; cellZ < CELLS_PER_CHUNK_XZ; cellZ++) {
                int baseZ = (chunkZ << 4) + cellZ * CELL_SIZE_XZ;
                for (int cellX = 0; cellX < CELLS_PER_CHUNK_XZ; cellX++) {
                    int baseX = (chunkX << 4) + cellX * CELL_SIZE_XZ;
                    BlockState state = chooseRepresentativeState(sections, minSectionY, minY, maxY, baseX, baseY, baseZ, pos);
                    if (state != null) {
                        cells[index(cellX, cellY, cellZ, verticalCells)] = state;
                    }
                }
            }
        }

        return new ChunkSnapshot(chunkX, chunkZ, minY, maxY, verticalCells, cells);
    }

    /**
     * Total blocks in one cell — used as the divisor for the majority-solid test.
     * A cell is rendered as a solid LOD block only if MORE THAN HALF of it is solid.
     * Without this, a single block of stone resting on a sea of air made the entire
     * 8x8x8 cell render as solid stone — that's the "floating chunky blob" artifact
     * you see at LOD distance.
     */
    private static final int CELL_VOLUME = CELL_SIZE_XZ * CELL_SIZE_Y * CELL_SIZE_XZ;
    private static final int MIN_SOLID_FRACTION = CELL_VOLUME / 2; // strictly majority

    private static BlockState chooseRepresentativeState(LevelChunkSection[] sections, int minSectionY, int minY, int maxY,
                                                        int baseX, int baseY, int baseZ, BlockPos.MutableBlockPos pos) {
        HashMap<BlockState, Integer> counts = new HashMap<>(8);
        int totalSolid = 0;
        for (int dy = 0; dy < CELL_SIZE_Y; dy++) {
            int y = baseY + dy;
            if (y < minY || y >= maxY) {
                continue;
            }
            int sectionIndex = (y >> 4) - minSectionY;
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                continue;
            }
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            for (int dz = 0; dz < CELL_SIZE_XZ; dz++) {
                int z = baseZ + dz;
                for (int dx = 0; dx < CELL_SIZE_XZ; dx++) {
                    int x = baseX + dx;
                    pos.set(x, y, z);
                    BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                    if (isRenderableVoxel(state)) {
                        counts.merge(state, 1, Integer::sum);
                        totalSolid++;
                    }
                }
            }
        }

        // Majority-solid gate — a cell with mostly air should stay empty so the LOD
        // doesn't paint floating 8x8x8 chunks over thin terrain or surface layers.
        if (totalSolid < MIN_SOLID_FRACTION) {
            return null;
        }

        BlockState best = null;
        int bestCount = 0;
        int bestOpacity = -1;
        for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            int opacity = opacityRank(entry.getKey());
            if (opacity > bestOpacity || (opacity == bestOpacity && count > bestCount)) {
                best = entry.getKey();
                bestCount = count;
                bestOpacity = opacity;
            }
        }
        return best;
    }

    private static boolean isRenderableVoxel(BlockState state) {
        if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        try {
            return ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.solid();
        } catch (Throwable ignored) {
            return state.canOcclude();
        }
    }

    private static int opacityRank(BlockState state) {
        if (state.canOcclude()) {
            return 15;
        }
        try {
            return ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.solid() ? 8 : 0;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static void pruneIfNeeded() {
        while (snapshots.size() > MAX_CACHED_CHUNKS) {
            Long eldest = snapshots.keySet().iterator().next();
            snapshots.remove(eldest);
        }
    }

    private static int index(int cellX, int cellY, int cellZ, int verticalCells) {
        return cellX + cellZ * CELLS_PER_CHUNK_XZ + cellY * CELLS_PER_CHUNK_XZ * CELLS_PER_CHUNK_XZ;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public record ChunkSnapshot(int chunkX, int chunkZ, int minY, int maxY, int verticalCells, BlockState[] cells) {
        public BlockState cell(int cellX, int cellY, int cellZ) {
            if (cellX < 0 || cellX >= CELLS_PER_CHUNK_XZ
                    || cellY < 0 || cellY >= verticalCells
                    || cellZ < 0 || cellZ >= CELLS_PER_CHUNK_XZ) {
                return null;
            }
            return cells[index(cellX, cellY, cellZ, verticalCells)];
        }

        public int cellCount() {
            return cells.length;
        }
    }
}
