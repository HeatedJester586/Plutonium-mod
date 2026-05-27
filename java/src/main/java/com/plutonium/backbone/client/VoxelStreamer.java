package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VoxelStreamer {

    private static ByteBuffer shadowWorldBuffer = null;
    private static int bufferWidth = 0, bufferHeight = 0;
    public static volatile boolean isEngineReady = false;
    public static final Object ENGINE_LOCK = new Object();
    private static final List<BlockPos> dirtyBlocks = Collections.synchronizedList(new ArrayList<>());

    // Persistent upload buffer for batching (16-byte stride: 3× int32 + id + pad — matches native nUpdateBlockBatch)
    private static final int MAX_BATCH_BLOCKS = 65536;
    private static final int BLOCK_BYTES = 16;
    private static final ByteBuffer uploadBuffer = ByteBuffer.allocateDirect(MAX_BATCH_BLOCKS * BLOCK_BYTES);

    static {
        uploadBuffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    private VoxelStreamer() {}

    public static void init(long pinnedAddress, int width, int height) {
        synchronized (ENGINE_LOCK) {
            if (pinnedAddress == 0) return;
            shadowWorldBuffer = MemoryUtil.memByteBuffer(pinnedAddress, width * height * 3);
            bufferWidth = width;
            bufferHeight = height;
            isEngineReady = true;
        }
    }

    /**
     * 2D prototype layout: matches {@code ComputeEngine::setBlockNative} — cell index {@code z * width + x}.
     * World Y is ignored until you move to a real 3D or chunked layout.
     */
    public static void sendBlockUpdate(int x, int y, int z, byte blockId) {
        if (!isEngineReady || shadowWorldBuffer == null) return;
        synchronized (ENGINE_LOCK) {
            int cell = z * bufferWidth + x;
            int index = cell * 3;
            if (index >= 0 && index + 2 < shadowWorldBuffer.capacity()) {
                shadowWorldBuffer.put(index, blockId);
                shadowWorldBuffer.put(index + 1, (byte) 15);
                shadowWorldBuffer.put(index + 2, (byte) 0);
            }
        }
    }

    public static void markDirty(BlockPos pos) {
        dirtyBlocks.add(pos.immutable());
    }

    public static boolean hasDirtyBlocks() {
        return !dirtyBlocks.isEmpty();
    }

    // Only flush dirty blocks, not the whole world
    public static void flushChanges(long backendPtr) {
        if (net.minecraft.client.Minecraft.getInstance().level == null) return;
        if (bufferWidth <= 0 || bufferHeight <= 0) return;
        synchronized(dirtyBlocks) {
            if (dirtyBlocks.isEmpty()) return;
            int totalBlocks = dirtyBlocks.size();
            int sent = 0;
            while (sent < totalBlocks) {
                int batchSize = Math.min(MAX_BATCH_BLOCKS, totalBlocks - sent);
                uploadBuffer.clear();
                for (int i = 0; i < batchSize; i++) {
                    BlockPos p = dirtyBlocks.get(sent + i);
                    BlockState state = net.minecraft.client.Minecraft.getInstance().level.getBlockState(p);
                    byte blockId = (byte) net.minecraft.world.level.block.Block.getId(state);
                    int wx = p.getX();
                    int wz = p.getZ();
                    // Map world columns into the 2D simulation slab [0, bufferWidth) × [0, bufferHeight)
                    int lx = Math.floorMod(wx, bufferWidth);
                    int lz = Math.floorMod(wz, bufferHeight);
                    uploadBuffer.putInt(lx);
                    uploadBuffer.putInt(p.getY());
                    uploadBuffer.putInt(lz);
                    uploadBuffer.put(blockId);
                    uploadBuffer.put((byte) 0);
                    uploadBuffer.put((byte) 0);
                    uploadBuffer.put((byte) 0);
                }
                uploadBuffer.flip();
                com.plutonium.backbone.bridge.NativeInterface.nUpdateBlockBatch(backendPtr, uploadBuffer, batchSize);
                sent += batchSize;
            }
            dirtyBlocks.clear();
        }
    }

    public static void update(long backendPtr, int width, int height) {
        // Deprecated: The triple for-loop and per-frame update logic are fully removed.
        // Use flushDirtyBlocks() and markDirty() for delta updates only.
    }

    public static void cleanup() {
        isEngineReady = false;
        synchronized (ENGINE_LOCK) {
            shadowWorldBuffer = null;
            bufferWidth = 0;
            bufferHeight = 0;
        }
    }
}