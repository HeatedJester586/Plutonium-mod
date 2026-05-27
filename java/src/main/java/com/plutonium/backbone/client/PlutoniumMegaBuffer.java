package com.plutonium.backbone.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * One giant VBO with a free-list allocator. Section vertex data (copied from
 * vanilla's per-section solid VBO) gets slots in here, and the whole solid layer
 * draws with ONE {@link GL32#glMultiDrawElementsBaseVertex} call instead of
 * N per-section draw calls.
 *
 * <p>The vertex layout matches vanilla's {@link DefaultVertexFormat#BLOCK}
 * (32 bytes): Position(3 float) + Color(4 ubyte) + UV0(2 float) + UV2(2 short) +
 * Normal(3 byte) + Padding(1 byte). We render with vanilla's
 * {@code rendertype_solid} shader so fog/lighting/AO/color-tint all match.
 *
 * <p>Vertices are converted to absolute world coordinates on upload so all
 * sections share a single model-view matrix (subtract camera).
 */
public final class PlutoniumMegaBuffer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Vertex stride — matches {@link DefaultVertexFormat#BLOCK}. */
    public static final int VERTEX_BYTES = 32;

    /** Initial buffer size. ~512 MB is enough for the typical 32-RD working set. */
    private static final long INITIAL_CAPACITY = 512L * 1024L * 1024L;
    /** Grow step when we overflow. */
    private static final long GROW_BUMP        = 128L * 1024L * 1024L;
    /** Hard ceiling so we don't accidentally OOM the GPU. */
    private static final long MAX_CAPACITY     = 2L * 1024L * 1024L * 1024L;
    /** Byte alignment for allocations — keeps things friendly with GPU caches. */
    private static final long ALIGN            = 64L;

    private static int  vboId         = 0;
    private static int  vaoId         = 0;
    private static int  indexBufferId = 0;
    private static int  indexQuadCapacity = 0;
    private static int  multiDrawScratchCapacity = 0;
    private static long capacityBytes = 0;
    private static long bumpHead      = 0;
    private static IntBuffer multiDrawCountsScratch;
    private static PointerBuffer multiDrawIndicesScratch;
    private static IntBuffer multiDrawBaseVertsScratch;

    /** Free blocks, keyed by start offset. Value is block size. Kept sorted for coalescing. */
    private static final NavigableMap<Long, Long> freeByOffset = new TreeMap<>();

    private PlutoniumMegaBuffer() {}

    public static boolean isReady() { return vboId != 0; }

    public static long bytesInUse()    { return bumpHead; }
    public static long bytesCapacity() { return capacityBytes; }
    public static int  vaoIdForDebug() { return vaoId; }

    /** Render-thread only. Lazily allocates the VBO/VAO. */
    public static synchronized void ensureCreated() {
        if (vboId != 0) return;
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("PlutoniumMegaBuffer must be created on the render thread");
        }
        vaoId = GL30.glGenVertexArrays();
        vboId = GL15.glGenBuffers();
        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        capacityBytes = INITIAL_CAPACITY;
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, capacityBytes, GL15.GL_DYNAMIC_DRAW);
        configureBlockFormatAttribs();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        LOGGER.info("[Plutonium/Mega] VBO+VAO created — capacity={} MB stride={} bytes", capacityBytes / (1024L * 1024L), VERTEX_BYTES);
    }

    /**
     * Sets up vertex attribute pointers for vanilla's {@link DefaultVertexFormat#BLOCK} layout.
     * Attribute indices match the order Mojang declares elements (Position=0, Color=1, UV0=2,
     * UV2=3, Normal=4). The {@code rendertype_solid} shader binds inputs by these indices.
     *
     * <p>Offsets are derived from the live format object so we stay correct if Mojang ever
     * reorders the elements.
     */
    private static void configureBlockFormatAttribs() {
        VertexFormat fmt = DefaultVertexFormat.BLOCK;
        List<VertexFormatElement> elements = fmt.getElements();
        int offset = 0;
        for (int i = 0; i < elements.size(); i++) {
            VertexFormatElement el = elements.get(i);
            if (el.getUsage() == VertexFormatElement.Usage.PADDING) {
                offset += el.getByteSize();
                continue;
            }
            int glType = el.getType().getGlType();
            int count  = el.getCount();
            boolean isUV2 = el.getUsage() == VertexFormatElement.Usage.UV && el.getIndex() == 2;
            if (isUV2) {
                // UV2 is declared as `ivec2 UV2` in vanilla shaders — must use IPointer.
                GL30.glVertexAttribIPointer(i, count, glType, VERTEX_BYTES, offset);
            } else {
                boolean normalized = el.getUsage() == VertexFormatElement.Usage.COLOR
                        || el.getUsage() == VertexFormatElement.Usage.NORMAL;
                GL20.glVertexAttribPointer(i, count, glType, normalized, VERTEX_BYTES, offset);
            }
            GL20.glEnableVertexAttribArray(i);
            offset += el.getByteSize();
        }
    }

    /**
     * Allocate {@code data.remaining()} bytes in the mega buffer and copy {@code data}
     * into that slot. Returns the byte offset, or -1 on failure. Render-thread only.
     */
    public static synchronized long alloc(ByteBuffer data) {
        ensureCreated();
        long size = align(data.remaining());

        long offset = popFreeSlot(size);
        if (offset < 0) {
            if (bumpHead + size > capacityBytes) {
                grow(bumpHead + size);
                if (bumpHead + size > capacityBytes) {
                    LOGGER.error("[Plutonium/Mega] OOM — could not grow past {} bytes", capacityBytes);
                    return -1;
                }
            }
            offset = bumpHead;
            bumpHead += size;
        }

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, data);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return offset;
    }

    public static synchronized void free(long byteOffset, long byteSize) {
        if (byteOffset < 0 || byteSize <= 0) return;
        byteSize = align(byteSize);

        if (byteOffset + byteSize == bumpHead) {
            bumpHead -= byteSize;
            while (true) {
                Map.Entry<Long, Long> tail = freeByOffset.floorEntry(bumpHead - 1);
                if (tail != null && tail.getKey() + tail.getValue() == bumpHead) {
                    bumpHead = tail.getKey();
                    freeByOffset.remove(tail.getKey());
                } else {
                    break;
                }
            }
            return;
        }

        long newOffset = byteOffset;
        long newSize   = byteSize;
        Map.Entry<Long, Long> before = freeByOffset.floorEntry(byteOffset - 1);
        if (before != null && before.getKey() + before.getValue() == byteOffset) {
            newOffset = before.getKey();
            newSize  += before.getValue();
            freeByOffset.remove(before.getKey());
        }
        Long afterSize = freeByOffset.get(byteOffset + byteSize);
        if (afterSize != null) {
            newSize += afterSize;
            freeByOffset.remove(byteOffset + byteSize);
        }
        freeByOffset.put(newOffset, newSize);
    }

    private static long popFreeSlot(long size) {
        long bestOff  = -1;
        long bestSize = Long.MAX_VALUE;
        for (Map.Entry<Long, Long> e : freeByOffset.entrySet()) {
            long bs = e.getValue();
            if (bs >= size && bs < bestSize) {
                bestOff  = e.getKey();
                bestSize = bs;
                if (bs == size) break;
            }
        }
        if (bestOff < 0) return -1;
        freeByOffset.remove(bestOff);
        if (bestSize > size) {
            freeByOffset.put(bestOff + size, bestSize - size);
        }
        return bestOff;
    }

    private static void grow(long requiredCapacity) {
        long newCapacity = capacityBytes;
        while (newCapacity < requiredCapacity) newCapacity += GROW_BUMP;
        if (newCapacity > MAX_CAPACITY) {
            LOGGER.error("[Plutonium/Mega] Refused to grow past {} MB", MAX_CAPACITY / (1024L * 1024L));
            return;
        }

        int newVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newVbo);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, newCapacity, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vboId);
        if (bumpHead > 0) {
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, bumpHead);
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(vboId);
        vboId = newVbo;
        capacityBytes = newCapacity;

        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        configureBlockFormatAttribs();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        LOGGER.info("[Plutonium/Mega] grew VBO to {} MB", newCapacity / (1024L * 1024L));
    }

    public static void bindVao() {
        ensureCreated();
        GL30.glBindVertexArray(vaoId);
    }

    public static void unbindVao() {
        GL30.glBindVertexArray(0);
    }

    /**
     * The whole point of this class. Draws {@code drawCount} sections in ONE GL call.
     * Each section consists of {@code counts[i]} indices starting at index buffer offset 0,
     * with {@code baseVerts[i]} added to each index. The shared quad index buffer
     * (0,1,2,2,3,0 pattern) must already be bound to GL_ELEMENT_ARRAY_BUFFER.
     */
    public static void multiDrawElementsBaseVertex(int[] counts, int[] baseVerts, int drawCount) {
        if (drawCount <= 0) return;
        ensureIndexCapacity(counts, drawCount);
        ensureMultiDrawScratchCapacity(drawCount);
        multiDrawCountsScratch.clear();
        multiDrawIndicesScratch.clear();
        multiDrawBaseVertsScratch.clear();
        for (int i = 0; i < drawCount; i++) {
            multiDrawCountsScratch.put(counts[i]);
            multiDrawIndicesScratch.put(0L); // all sections start at offset 0 of the shared IBO
            multiDrawBaseVertsScratch.put(baseVerts[i]);
        }
        multiDrawCountsScratch.flip();
        multiDrawIndicesScratch.flip();
        multiDrawBaseVertsScratch.flip();
        GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, multiDrawCountsScratch, GL11.GL_UNSIGNED_INT,
                multiDrawIndicesScratch, multiDrawBaseVertsScratch);
    }

    private static void ensureMultiDrawScratchCapacity(int drawCount) {
        if (drawCount <= multiDrawScratchCapacity
                && multiDrawCountsScratch != null
                && multiDrawIndicesScratch != null
                && multiDrawBaseVertsScratch != null) {
            return;
        }

        int newCapacity = Math.max(1024, multiDrawScratchCapacity);
        while (newCapacity < drawCount) {
            newCapacity *= 2;
        }

        freeMultiDrawScratch();
        multiDrawCountsScratch = MemoryUtil.memAllocInt(newCapacity);
        multiDrawIndicesScratch = MemoryUtil.memAllocPointer(newCapacity);
        multiDrawBaseVertsScratch = MemoryUtil.memAllocInt(newCapacity);
        multiDrawScratchCapacity = newCapacity;
    }

    private static void freeMultiDrawScratch() {
        if (multiDrawCountsScratch != null) {
            MemoryUtil.memFree(multiDrawCountsScratch);
            multiDrawCountsScratch = null;
        }
        if (multiDrawIndicesScratch != null) {
            MemoryUtil.memFree(multiDrawIndicesScratch);
            multiDrawIndicesScratch = null;
        }
        if (multiDrawBaseVertsScratch != null) {
            MemoryUtil.memFree(multiDrawBaseVertsScratch);
            multiDrawBaseVertsScratch = null;
        }
        multiDrawScratchCapacity = 0;
    }

    private static void ensureIndexCapacity(int[] counts, int drawCount) {
        int maxQuads = 0;
        for (int i = 0; i < drawCount; i++) {
            maxQuads = Math.max(maxQuads, counts[i] / 6);
        }
        if (maxQuads <= indexQuadCapacity && indexBufferId != 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
            return;
        }

        if (indexBufferId == 0) {
            indexBufferId = GL15.glGenBuffers();
        }
        int newCapacity = Math.max(1024, indexQuadCapacity);
        while (newCapacity < maxQuads) {
            newCapacity *= 2;
        }

        IntBuffer indices = MemoryUtil.memAllocInt(newCapacity * 6);
        try {
            for (int quad = 0; quad < newCapacity; quad++) {
                int base = quad * 4;
                indices.put(base);
                indices.put(base + 1);
                indices.put(base + 2);
                indices.put(base + 2);
                indices.put(base + 3);
                indices.put(base);
            }
            indices.flip();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            indexQuadCapacity = newCapacity;
            LOGGER.info("[Plutonium/Mega] shared quad index buffer ready - quads={}", indexQuadCapacity);
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    public static synchronized void destroy() {
        if (vaoId != 0) { GL30.glDeleteVertexArrays(vaoId); vaoId = 0; }
        if (vboId != 0) { GL15.glDeleteBuffers(vboId);     vboId = 0; }
        if (indexBufferId != 0) { GL15.glDeleteBuffers(indexBufferId); indexBufferId = 0; }
        freeMultiDrawScratch();
        indexQuadCapacity = 0;
        capacityBytes = 0;
        bumpHead      = 0;
        freeByOffset.clear();
    }

    public static synchronized void resetAllocations() {
        bumpHead = 0;
        freeByOffset.clear();
    }

    private static long align(long v) {
        return (v + ALIGN - 1L) & ~(ALIGN - 1L);
    }
}
