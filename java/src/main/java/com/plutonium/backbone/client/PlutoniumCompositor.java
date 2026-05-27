package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

public final class PlutoniumCompositor {

    private static volatile long backendPtr = 0;
    // Triple buffering arrays
    private static final int BUFFER_COUNT = 3;
    private static int[] glTextureIds = new int[BUFFER_COUNT];
    private static int[] glMemoryObjects = new int[BUFFER_COUNT];
    private static int[] glSemaphores = new int[BUFFER_COUNT];
    private static long[] fenceValues = new long[BUFFER_COUNT];
    private static int currentBuffer = 0;
    private static int  fbWidth        = 0;
    private static int  fbHeight       = 0;

    private static BackendMode          currentMode       = BackendMode.OPENGL;
    private static volatile BackendMode pendingMode       = null;
    private static volatile boolean     shutdownRequested = false;
    private static boolean              linked            = false;

    // Ensure these are static to persist across class reloads
    private static int lastWidth = -1;
    private static int lastHeight = -1;

    private PlutoniumCompositor() {}

    public static long getBackendPtr() { return backendPtr; }

    public static long ensureBackendForWorldgen() {
        long ptr = backendPtr;
        if (ptr != 0) {
            return ptr;
        }

        synchronized (PlutoniumCompositor.class) {
            if (backendPtr != 0) {
                return backendPtr;
            }

            Minecraft mc = Minecraft.getInstance();
            int width = 1;
            int height = 1;
            if (mc != null && mc.getWindow() != null) {
                width = Math.max(1, mc.getWindow().getWidth());
                height = Math.max(1, mc.getWindow().getHeight());
            }

            System.out.println("[Plutonium] Bootstrapping native backend for GPU worldgen before render link...");
            relink(width, height, BackendMode.fromString(com.plutonium.backbone.common.Config.CLIENT.backend.get()));
            return backendPtr;
        }
    }

    public static void requestShutdown() { shutdownRequested = true; }

    public static void requestSwitch(BackendMode newMode) { pendingMode = newMode; }

    public static void renderFullscreenReplace(BackendMode mode) {
        if (pendingMode != null) {
            BackendMode pm = pendingMode;
            pendingMode = null;
            if (pm == BackendMode.OPENGL) {
                shutdownRequested = true;
            } else {
                forceShutdown();
            }
        }
        if (shutdownRequested) {
            shutdownRequested = false;
            forceShutdown();
        }

        if (mode == BackendMode.OPENGL) {
            if (backendPtr != 0) {
                if (com.plutonium.backbone.common.Config.getChunkBuilder() != com.plutonium.backbone.common.Config.ChunkBuilder.NATIVE &&
                    !com.plutonium.backbone.common.Config.isGpuWorldgenEnabled()) {
                    forceShutdown();
                }
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int curW = mc.getWindow().getWidth();
        int curH = mc.getWindow().getHeight();
        boolean sizeChanged = Math.abs(curW - lastWidth) > 5 || Math.abs(curH - lastHeight) > 5;
        if (backendPtr == 0 || sizeChanged) {
            relink(curW, curH, mode);
            return;
        }
        if (backendPtr != 0 && !linked) {
            setupGraphicsInterop();
        }
        if (linked && backendPtr != 0) {
            currentBuffer = (currentBuffer + 1) % BUFFER_COUNT;
            float t = System.nanoTime() / 1_000_000_000.0f;
            long fenceValue = NativeInterface.nRenderFrame(backendPtr, t); // removed currentBuffer
            fenceValues[currentBuffer] = fenceValue;
            if (fenceValue != 0) {
                waitOnFenceValue(currentBuffer);
                drawFullscreenQuad(currentBuffer);
            }
        }
    }

    private static synchronized void relink(int newW, int newH, BackendMode mode) {
        if (newW <= 0 || newH <= 0) return;
        System.out.println("[Plutonium] Relinking GPU highway... " + newW + "x" + newH);

        forceShutdown(); // Clean up old state

        fbWidth = lastWidth = newW;
        fbHeight = lastHeight = newH;

        NativeInterface.ensureLoaded();
        int threads = com.plutonium.backbone.common.Config.CLIENT.cpuThreads.get();
        backendPtr = NativeInterface.nInitBackend(fbWidth, fbHeight, threads);

        if (backendPtr != 0) {
            long pinnedAddress = NativeInterface.nGetPinnedWorldAddress(backendPtr);
            VoxelStreamer.init(pinnedAddress, fbWidth, fbHeight);
            NativeInterface.nStartPhysics(backendPtr);
            // We don't call setupGraphicsInterop here; we let the next frame try it.
        }
    }

    private static void setupGraphicsInterop() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            long texHandle = NativeInterface.nGetSharedTextureHandle(backendPtr); // removed i
            if (texHandle == 0) {
                // System.out.println("[Plutonium] Waiting for Native Shared Handle...");
                return;
            }
            try {
                long texSize = NativeInterface.nGetSharedTextureAllocationSize(backendPtr); // removed i
                long fenceHandle = NativeInterface.nGetSharedFenceHandle(backendPtr); // removed i
                IntBuffer memObjBuf = BufferUtils.createIntBuffer(1);
                EXTMemoryObject.glCreateMemoryObjectsEXT(memObjBuf);
                glMemoryObjects[i] = memObjBuf.get(0);
                EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(glMemoryObjects[i], texSize,
                        EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT, texHandle);
                glTextureIds[i] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureIds[i]);
                EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, fbWidth, fbHeight, glMemoryObjects[i], 0);
                IntBuffer semBuf = BufferUtils.createIntBuffer(1);
                EXTSemaphore.glGenSemaphoresEXT(semBuf);
                glSemaphores[i] = semBuf.get(0);
                EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glSemaphores[i], EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT, fenceHandle);
            } catch (Exception e) {
                System.err.println("[Plutonium] CRITICAL: Graphics Interop Failed!");
                // e.printStackTrace();
                linked = false;
                return;
            }
        }
        linked = true;
        System.out.println("[Plutonium] Graphics Interop Linked Successfully (Triple Buffering).");
    }

    private static void forceShutdown() {
        linked = false;
        VoxelStreamer.cleanup();
        if (backendPtr != 0) {
            NativeInterface.nStopPhysics(backendPtr);
            NativeInterface.nShutdownBackend(backendPtr);
            backendPtr = 0;
        }
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (glTextureIds[i] > 0) GL11.glDeleteTextures(glTextureIds[i]);
            glTextureIds[i] = -1;
            glMemoryObjects[i] = -1;
            glSemaphores[i] = -1;
            fenceValues[i] = 0;
        }
    }

    private static void waitOnFenceValue(int bufferIdx) {
        LongBuffer valueBuf = BufferUtils.createLongBuffer(1).put(0, fenceValues[bufferIdx]);
        EXTSemaphore.glSemaphoreParameterui64vEXT(
                glSemaphores[bufferIdx], EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, valueBuf);

        IntBuffer textures = BufferUtils.createIntBuffer(1).put(0, glTextureIds[bufferIdx]);
        IntBuffer layouts  = BufferUtils.createIntBuffer(1).put(0, 0);
        EXTSemaphore.glWaitSemaphoreEXT(glSemaphores[bufferIdx], textures, layouts, layouts);
    }

    private static void drawFullscreenQuad(int bufferIdx) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0, w, h, 0, -1000, 1000),
                VertexSorting.ORTHOGRAPHIC_Z);

        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.setIdentity();
        RenderSystem.applyModelViewMatrix();
        // Enable depth test and set depth function before drawing the quad
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.setShaderTexture(0, glTextureIds[bufferIdx]);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        Tesselator    t = Tesselator.getInstance();
        BufferBuilder b = t.getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        Matrix4f mat = mv.last().pose();
        b.vertex(mat, 0, h, 0).uv(0, 1).endVertex();
        b.vertex(mat, w, h, 0).uv(1, 1).endVertex();
        b.vertex(mat, w, 0, 0).uv(1, 0).endVertex();
        b.vertex(mat, 0, 0, 0).uv(0, 0).endVertex();
        t.end();

        mv.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.ORTHOGRAPHIC_Z);
    }
}
