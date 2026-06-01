package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirtyChunkBatcher {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_HEIGHT = NativeInterface.PIPELINE_CHUNK_HEIGHT;
    private static final int STATE_UPDATE_BUDGET = 4096;
    private static final int LIGHT_REFRESH_UPLOAD_BUDGET = 4;

    private static final ConcurrentHashMap<Long, Integer> pendingStateUpdates = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> pendingLightRefreshes = new ConcurrentHashMap<>();
    private static final AtomicInteger sequence = new AtomicInteger();
    private static int dirtyLogSamples;
    private static int lightLogSamples;

    private DirtyChunkBatcher() {
    }

    public static void markBlockDirty(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (cx < Short.MIN_VALUE || cx > Short.MAX_VALUE || cz < Short.MIN_VALUE || cz > Short.MAX_VALUE) {
            return;
        }

        int ly = pos.getY() - level.getMinBuildHeight();
        if (ly < 0 || ly >= CHUNK_HEIGHT) {
            return;
        }

        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        long blockPacked = ((long) (lx & 0x0F) << 22) | ((long) (ly & 0x1FF) << 4) | (long) (lz & 0x0F);
        long key = ((long) (cx & 0xFFFF))
                | (((long) (cz & 0xFFFF)) << 16)
                | (blockPacked << 32);

        pendingStateUpdates.put(key, sequence.incrementAndGet());
        scheduleLightRefresh(cx, cz);
    }

    public static void flushToNative(ClientLevel level) {
        if (level == null || !NativeInterface.isLoaded()) {
            return;
        }

        flushStateUpdates(level);
        flushLightRefreshes(level);
    }

    public static void clear() {
        pendingStateUpdates.clear();
        pendingLightRefreshes.clear();
        dirtyLogSamples = 0;
        lightLogSamples = 0;
    }

    private static void flushStateUpdates(ClientLevel level) {
        if (pendingStateUpdates.isEmpty()) {
            return;
        }

        ArrayList<Long> removedKeys = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : pendingStateUpdates.entrySet()) {
            if (removedKeys.size() >= STATE_UPDATE_BUDGET) {
                break;
            }
            Long key = entry.getKey();
            Integer marker = entry.getValue();
            if (pendingStateUpdates.remove(key, marker)) {
                removedKeys.add(key);
            }
        }

        if (removedKeys.isEmpty()) {
            return;
        }

        long[] keys = new long[removedKeys.size()];
        long[] values = new long[removedKeys.size()];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int count = 0;

        for (long key : removedKeys) {
            int cx = (int) (short) (key & 0xFFFFL);
            int cz = (int) (short) ((key >>> 16) & 0xFFFFL);
            long blockPacked = (key >>> 32) & 0x03FFFFFFL;
            int lx = (int) ((blockPacked >>> 22) & 0x0F);
            int ly = (int) ((blockPacked >>> 4) & 0x1FF);
            int lz = (int) (blockPacked & 0x0F);

            int worldX = (cx << 4) + lx;
            int worldY = minY + ly;
            int worldZ = (cz << 4) + lz;
            pos.set(worldX, worldY, worldZ);

            BlockState state = level.getBlockState(pos);
            int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
            int skyLight = level.getBrightness(LightLayer.SKY, pos);
            int blockLight = level.getBrightness(LightLayer.BLOCK, pos);

            keys[count] = key;
            values[count] = ((long) (stateId & 0xFFFF))
                    | (((long) (skyLight & 0x0F)) << 16)
                    | (((long) (blockLight & 0x0F)) << 20);
            count++;
        }

        if (count == 0) {
            return;
        }

        NativeInterface.nPipelineInvalidateChunks(keys, values, count);
        if (dirtyLogSamples++ < 8 || (dirtyLogSamples % 128) == 0) {
            LOGGER.info("[Plutonium/Pipeline] flushed {} dirty block updates.", count);
        }
    }

    private static void scheduleLightRefresh(int centerX, int centerZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                pendingLightRefreshes.put(chunkKey(centerX + dx, centerZ + dz), Boolean.TRUE);
            }
        }
    }

    public static void discardChunk(int chunkX, int chunkZ) {
        pendingLightRefreshes.remove(chunkKey(chunkX, chunkZ));

        long packedChunk = ((long) (chunkX & 0xFFFF)) | (((long) (chunkZ & 0xFFFF)) << 16);
        for (Long key : new ArrayList<>(pendingStateUpdates.keySet())) {
            if ((key & 0xFFFFFFFFL) == packedChunk) {
                pendingStateUpdates.remove(key);
            }
        }
    }

    private static void flushLightRefreshes(ClientLevel level) {
        if (pendingLightRefreshes.isEmpty()) {
            return;
        }

        ArrayList<Long> refreshes = new ArrayList<>(pendingLightRefreshes.keySet());
        int uploaded = 0;
        int skipped = 0;
        for (long key : refreshes) {
            int cx = unpackChunkX(key);
            int cz = unpackChunkZ(key);

            // Skip chunks that aren't registered in the native pipeline yet.
            // Without this filter, the 3x3 fan-out in scheduleLightRefresh
            // produces hundreds of unique chunks per dirty-block burst at
            // world load (vanilla decorates ~9 columns in one tick = ~2000
            // dirty blocks fanning out to ~458 unique chunks). compileLightPayload
            // is ~196k getBrightness() calls per chunk on the render thread,
            // which is ~90M brightness lookups per flush -> 583ms freeze.
            // Filtering to registered chunks cuts that ~50x.
            if (!CudaPipeline.isColumnUploaded(cx, cz)) {
                if (pendingLightRefreshes.remove(key, Boolean.TRUE)) {
                    skipped++;
                }
                continue;
            }
            if (uploaded >= LIGHT_REFRESH_UPLOAD_BUDGET) {
                break;
            }
            if (!pendingLightRefreshes.remove(key, Boolean.TRUE)) {
                continue;
            }

            byte[] lights = compileLightPayload(level, cx, cz);
            if (lights == null) {
                continue;
            }

            NativeInterface.nPipelineUploadChunkLight(cx, cz, lights);
            uploaded++;
        }

        if ((uploaded > 0 || skipped > 0)
                && (lightLogSamples++ < 8 || (lightLogSamples % 128) == 0)) {
            LOGGER.info("[Plutonium/Pipeline] refreshed {} chunk light payloads ({} skipped, {} pending).",
                    uploaded, skipped, pendingLightRefreshes.size());
        }
    }

    private static byte[] compileLightPayload(ClientLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (chunk == null) {
            return null;
        }

        byte[] lights = new byte[NativeInterface.PIPELINE_CHUNK_BLOCK_COUNT];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();

        for (int ly = 0; ly < CHUNK_HEIGHT; ly++) {
            int worldY = minY + ly;
            for (int z = 0; z < CHUNK_SIDE; z++) {
                int worldZ = (chunkZ << 4) + z;
                for (int x = 0; x < CHUNK_SIDE; x++) {
                    int worldX = (chunkX << 4) + x;
                    pos.set(worldX, worldY, worldZ);
                    int sky = level.getBrightness(LightLayer.SKY, pos);
                    int block = level.getBrightness(LightLayer.BLOCK, pos);
                    lights[index(x, ly, z)] = (byte) (((sky & 0x0F) << 4) | (block & 0x0F));
                }
            }
        }

        return lights;
    }

    private static int index(int x, int y, int z) {
        return x + (z * CHUNK_SIDE) + (y * CHUNK_SIDE * CHUNK_SIDE);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static int unpackChunkX(long key) {
        return (int) key;
    }

    private static int unpackChunkZ(long key) {
        return (int) (key >>> 32);
    }
}
