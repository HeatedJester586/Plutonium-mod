package com.plutonium.backbone.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;

public final class NativeChunkRenderer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int MAX_REGISTERED_CHUNKS = 16_384;
    private static final int GEOMETRY_FACE_CAPACITY = 64 * 1024 * 1024;
    private static final int COMMAND_BYTES = 16;
    private static final int METADATA_BYTES = 16;
    private static final int SSBO_FACE_BINDING = 2;
    private static final int SSBO_META_BINDING = 3;
    private static final int SSBO_ATLAS_BINDING = 4;
    private static final int LAYER_SOLID = 0;
    private static final int FALLBACK_COVERAGE_CHECK_INTERVAL_FRAMES = 4;
    private static final int FALLBACK_PUMP_INTERVAL_FRAMES = 6;

    private static int vao;
    private static int geometryBuffer;
    private static int metadataBuffer;
    private static int commandBuffer;
    private static int atlasLookupBuffer;
    private static int shaderProgram;

    private static ByteBuffer geometryMap;
    private static ByteBuffer metadataMap;
    private static ByteBuffer commandMap;
    private static ByteBuffer pendingAtlasLookup;

    private static boolean nativeConfigured;
    private static boolean atlasReady;
    private static int configuredWorldMinY = Integer.MIN_VALUE;
    private static int fallbackCoverageCheckFrames;
    private static int fallbackPumpFrames;
    private static long renderLogSamples;

    private static int locProjection;
    private static int locModelView;
    private static int locCamera;
    private static int locBlockAtlas;

    private NativeChunkRenderer() {
    }

    public static boolean renderSolidLayer(
            PoseStack poseStack,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f projectionMatrix,
            ObjectArrayList<?> visibleChunks) {
        if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE) return false;
        if (poseStack == null || projectionMatrix == null || visibleChunks == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return false;
        if (!ensureCreated(mc.level.getMinBuildHeight())) {
            return false;
        }

        CudaPipeline.requestVisibleColumns(visibleChunks);
        if (!CudaPipeline.isNativeRenderActive() && !shouldCheckFallbackCoverage()) {
            pumpNativeFrameWhileFallingBack();
            return false;
        }
        if (!CudaPipeline.visibleColumnCoverageReady(visibleChunks)) {
            pumpNativeFrameWhileFallingBack();
            return false;
        }

        if (!atlasReady) {
            pumpNativeFrameWhileFallingBack();
            return false;
        }

        int frame = NativeInterface.nPipelineBeginFrame();
        if (frame < 0) {
            return false;
        }

        try {
            fallbackCoverageCheckFrames = 0;
            fallbackPumpFrames = 0;
            return drawSolid(frame, poseStack, cameraX, cameraY, cameraZ, projectionMatrix, mc);
        } finally {
            NativeInterface.nPipelineEndFrame();
        }
    }

    private static boolean shouldCheckFallbackCoverage() {
        return (fallbackCoverageCheckFrames++ % FALLBACK_COVERAGE_CHECK_INTERVAL_FRAMES) == 0;
    }

    private static void pumpNativeFrameWhileFallingBack() {
        if ((fallbackPumpFrames++ % FALLBACK_PUMP_INTERVAL_FRAMES) == 0) {
            pumpNativeFrame();
        }
    }

    private static void pumpNativeFrame() {
        if (!NativeInterface.isLoaded()) {
            return;
        }
        int frame = NativeInterface.nPipelineBeginFrame();
        if (frame >= 0) {
            NativeInterface.nPipelineEndFrame();
        }
    }

    public static synchronized void uploadAtlasLookup(ByteBuffer atlasLookup) {
        if (atlasLookup == null) return;
        ByteBuffer copy = ByteBuffer.allocateDirect(atlasLookup.remaining());
        copy.put(atlasLookup.duplicate());
        copy.flip();
        pendingAtlasLookup = copy;

        if (RenderSystem.isOnRenderThread()) {
            uploadAtlasLookupOnRenderThread();
        } else {
            RenderSystem.recordRenderCall(NativeChunkRenderer::uploadAtlasLookupOnRenderThread);
        }
    }

    public static synchronized void resetNativeConfiguration() {
        nativeConfigured = false;
        configuredWorldMinY = Integer.MIN_VALUE;
        fallbackCoverageCheckFrames = 0;
        fallbackPumpFrames = 0;
    }

    public static synchronized void shutdown() {
        resetNativeConfiguration();
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(NativeChunkRenderer::destroyGlObjects);
            return;
        }
        destroyGlObjects();
    }

    private static synchronized boolean ensureCreated(int worldMinY) {
        if (!RenderSystem.isOnRenderThread()) {
            return false;
        }

        NativeInterface.ensureLoaded();
        if (!NativeInterface.isLoaded()) {
            return false;
        }

        if (vao == 0) {
            vao = GL30C.glGenVertexArrays();
        }
        if (shaderProgram == 0) {
            shaderProgram = buildShaderProgram();
            if (shaderProgram == 0) {
                return false;
            }
        }
        if (geometryBuffer == 0) {
            createPersistentBuffers();
        }
        if (geometryMap == null || metadataMap == null || commandMap == null) {
            // Buffer creation failed; do NOT pass null to memAddress().
            return false;
        }
        uploadAtlasLookupOnRenderThread();

        if (!nativeConfigured || configuredWorldMinY != worldMinY) {
            boolean configured = NativeInterface.nPipelineConfigureRenderer(
                    MemoryUtil.memAddress(geometryMap),
                    GEOMETRY_FACE_CAPACITY,
                    MemoryUtil.memAddress(metadataMap),
                    MemoryUtil.memAddress(commandMap),
                    MAX_REGISTERED_CHUNKS,
                    worldMinY);
            nativeConfigured = configured;
            configuredWorldMinY = configured ? worldMinY : Integer.MIN_VALUE;
        }

        return nativeConfigured;
    }

    private static void createPersistentBuffers() {
        ByteBuffer[] mapHolder = new ByteBuffer[1];

        geometryBuffer = createPersistentMappedBuffer(
                GL43C.GL_SHADER_STORAGE_BUFFER,
                (long) GEOMETRY_FACE_CAPACITY * Long.BYTES,
                mapHolder);
        geometryMap = mapHolder[0];

        // Metadata SSBO is FRAME_LAG-buffered to match the command ring. Without
        // per-frame metadata pages, worker-thread writes to chunk origin /
        // face_base_offset can race with the GPU still reading older frames'
        // commands at the same slot, producing misplaced geometry that looks
        // like giant floating quads or wrong-position chunks.
        metadataBuffer = createPersistentMappedBuffer(
                GL43C.GL_SHADER_STORAGE_BUFFER,
                (long) NativeChunkRendererFrame.FRAME_LAG * MAX_REGISTERED_CHUNKS * METADATA_BYTES,
                mapHolder);
        metadataMap = mapHolder[0];

        commandBuffer = createPersistentMappedBuffer(
                GL43C.GL_DRAW_INDIRECT_BUFFER,
                (long) NativeChunkRendererFrame.FRAME_LAG * 3L * MAX_REGISTERED_CHUNKS * COMMAND_BYTES,
                mapHolder);
        commandMap = mapHolder[0];

        atlasLookupBuffer = GL15C.glGenBuffers();

        if (geometryMap == null || metadataMap == null || commandMap == null) {
            LOGGER.error("[Plutonium/Pipeline] failed to map native renderer buffers "
                    + "(geometry={}, metadata={}, commands={}).",
                    geometryMap != null,
                    metadataMap != null,
                    commandMap != null);
            destroyGlObjects();
            return;
        }

        LOGGER.info("[Plutonium/Pipeline] mapped renderer buffers (geometry={} MB, maxChunks={}).",
                ((long) GEOMETRY_FACE_CAPACITY * Long.BYTES) / (1024L * 1024L),
                MAX_REGISTERED_CHUNKS);
    }

    /**
     * Create a persistent-coherent mapped buffer in ONE operation. The buffer
     * must stay bound to its target across glBufferStorage AND glMapBufferRange;
     * unbinding between them causes glMapBufferRange to fail with
     * GL_INVALID_OPERATION ("Buffer must be bound and not mapped") and return null.
     */
    private static int createPersistentMappedBuffer(int target, long bytes, ByteBuffer[] outMap) {
        outMap[0] = null;
        int id = GL15C.glGenBuffers();
        GL15C.glBindBuffer(target, id);
        GL44C.glBufferStorage(
                target,
                bytes,
                GL30C.GL_MAP_WRITE_BIT
                        | GL44C.GL_MAP_PERSISTENT_BIT
                        | GL44C.GL_MAP_COHERENT_BIT);
        ByteBuffer mapped = GL30C.glMapBufferRange(
                target,
                0L,
                bytes,
                GL30C.GL_MAP_WRITE_BIT
                        | GL44C.GL_MAP_PERSISTENT_BIT
                        | GL44C.GL_MAP_COHERENT_BIT);
        GL15C.glBindBuffer(target, 0);
        outMap[0] = mapped;
        return id;
    }

    private static void uploadAtlasLookupOnRenderThread() {
        if (!RenderSystem.isOnRenderThread() || pendingAtlasLookup == null) return;
        if (atlasLookupBuffer == 0) {
            atlasLookupBuffer = GL15C.glGenBuffers();
        }
        ByteBuffer src = pendingAtlasLookup.duplicate();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, atlasLookupBuffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, src, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_ATLAS_BINDING, atlasLookupBuffer);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        atlasReady = true;
    }

    private static boolean drawSolid(
            int frame,
            PoseStack poseStack,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f projectionMatrix,
            Minecraft mc) {
        int drawCount = NativeInterface.nPipelineRendererDrawCount();
        if (drawCount <= 0) {
            CudaPipeline.dropNativeRenderHandoff();
            return false;
        }

        TextureAtlas blockAtlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        int blockAtlasId = blockAtlas.getId();

        GL20C.glUseProgram(shaderProgram);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            projectionMatrix.get(matrix);
            GL20C.glUniformMatrix4fv(locProjection, false, matrix);

            matrix.clear();
            poseStack.last().pose().get(matrix);
            GL20C.glUniformMatrix4fv(locModelView, false, matrix);
        }
        GL20C.glUniform3f(locCamera, (float) cameraX, (float) cameraY, (float) cameraZ);
        GL20C.glUniform1i(locBlockAtlas, 0);

        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, blockAtlasId);
        GlStateManager._bindTexture(blockAtlasId);

        GL30C.glBindVertexArray(vao);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_FACE_BINDING, geometryBuffer);
        // Metadata is FRAME_LAG-buffered: bind only the active frame's page so
        // the shader reads from the metadata that matches the command page we
        // just dispatched. glBindBufferBase would read from offset 0, which
        // would always be frame 0's stale page.
        long metaPageBytes = (long) MAX_REGISTERED_CHUNKS * METADATA_BYTES;
        long metaPageOffset = (long) frame * metaPageBytes;
        GL30C.glBindBufferRange(
                GL43C.GL_SHADER_STORAGE_BUFFER,
                SSBO_META_BINDING,
                metadataBuffer,
                metaPageOffset,
                metaPageBytes);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_ATLAS_BINDING, atlasLookupBuffer);
        GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, commandBuffer);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11C.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        long layerOffset = commandOffsetBytes(frame, LAYER_SOLID);
        GL43C.glMultiDrawArraysIndirect(GL11C.GL_TRIANGLES, layerOffset, drawCount, COMMAND_BYTES);

        GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30C.glBindVertexArray(0);
        GL20C.glUseProgram(0);

        if (renderLogSamples++ < 8 || (renderLogSamples % 240) == 0) {
            LOGGER.info("[Plutonium/Pipeline] rendered native solid MDI frame={} drawCount={} maxChunks={} pendingSwaps={} cam=({}, {}, {}).",
                    frame,
                    drawCount,
                    NativeInterface.nPipelineRendererMaxChunks(),
                    NativeInterface.nPipelinePendingSwapCount(),
                    String.format(Locale.ROOT, "%.1f", cameraX),
                    String.format(Locale.ROOT, "%.1f", cameraY),
                    String.format(Locale.ROOT, "%.1f", cameraZ));
        }
        return true;
    }

    private static long commandOffsetBytes(int frame, int layer) {
        return ((long) frame * 3L * MAX_REGISTERED_CHUNKS * COMMAND_BYTES)
                + ((long) layer * MAX_REGISTERED_CHUNKS * COMMAND_BYTES);
    }

    private static int buildShaderProgram() {
        int vertex = compileShader(GL20C.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertex == 0 || fragment == 0) {
            return 0;
        }

        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            LOGGER.error("[Plutonium/Pipeline] native chunk shader link failed: {}", GL20C.glGetProgramInfoLog(program));
            GL20C.glDeleteProgram(program);
            program = 0;
        }
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);

        if (program != 0) {
            locProjection = GL20C.glGetUniformLocation(program, "u_ProjectionMatrix");
            locModelView = GL20C.glGetUniformLocation(program, "u_ModelViewMatrix");
            locCamera = GL20C.glGetUniformLocation(program, "u_CameraOffset");
            locBlockAtlas = GL20C.glGetUniformLocation(program, "u_BlockAtlas");
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            LOGGER.error("[Plutonium/Pipeline] native chunk shader compile failed: {}", GL20C.glGetShaderInfoLog(shader));
            GL20C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static synchronized void destroyGlObjects() {
        if (geometryBuffer != 0 && geometryMap != null) {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, geometryBuffer);
            GL15C.glUnmapBuffer(GL43C.GL_SHADER_STORAGE_BUFFER);
        }
        if (metadataBuffer != 0 && metadataMap != null) {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, metadataBuffer);
            GL15C.glUnmapBuffer(GL43C.GL_SHADER_STORAGE_BUFFER);
        }
        if (commandBuffer != 0 && commandMap != null) {
            GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
            GL15C.glUnmapBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, 0);

        if (geometryBuffer != 0) GL15C.glDeleteBuffers(geometryBuffer);
        if (metadataBuffer != 0) GL15C.glDeleteBuffers(metadataBuffer);
        if (commandBuffer != 0) GL15C.glDeleteBuffers(commandBuffer);
        if (atlasLookupBuffer != 0) GL15C.glDeleteBuffers(atlasLookupBuffer);
        if (vao != 0) GL30C.glDeleteVertexArrays(vao);
        if (shaderProgram != 0) GL20C.glDeleteProgram(shaderProgram);

        vao = 0;
        geometryBuffer = 0;
        metadataBuffer = 0;
        commandBuffer = 0;
        atlasLookupBuffer = 0;
        shaderProgram = 0;
        geometryMap = null;
        metadataMap = null;
        commandMap = null;
        atlasReady = pendingAtlasLookup != null;
        renderLogSamples = 0;
    }

    private static final class NativeChunkRendererFrame {
        static final int FRAME_LAG = 3;
    }

    private static final String VERTEX_SHADER = """
            #version 460 core
            #extension GL_ARB_gpu_shader_int64 : enable

            struct ChunkMetadata {
                ivec3 origin;
                uint face_base_offset;
            };

            layout(std430, binding = 2) buffer RawFaceDataBuffer {
                uint64_t face_payload[];
            };

            layout(std430, binding = 3) buffer ChunkMetaBuffer {
                ChunkMetadata metadata[];
            };

            layout(std430, binding = 4) buffer AtlasLookupBuffer {
                vec2 atlas_uv[];
            };

            uniform mat4 u_ProjectionMatrix;
            uniform mat4 u_ModelViewMatrix;
            uniform vec3 u_CameraOffset;

            out vec2 v_TexCoord;
            out float v_AmbientOcclusion;

            void get_quad_vertex(uint vertex_idx, uint face_dir, out vec3 local_pos, out vec2 uv) {
                uint corner_map[6] = uint[](0u, 1u, 2u, 0u, 2u, 3u);
                uint corner = corner_map[vertex_idx];

                if (face_dir == 0u) {
                    if (corner == 0u) { local_pos = vec3(1,0,1); uv = vec2(0,1); }
                    if (corner == 1u) { local_pos = vec3(1,0,0); uv = vec2(1,1); }
                    if (corner == 2u) { local_pos = vec3(1,1,0); uv = vec2(1,0); }
                    if (corner == 3u) { local_pos = vec3(1,1,1); uv = vec2(0,0); }
                } else if (face_dir == 1u) {
                    if (corner == 0u) { local_pos = vec3(0,0,0); uv = vec2(0,1); }
                    if (corner == 1u) { local_pos = vec3(0,0,1); uv = vec2(1,1); }
                    if (corner == 2u) { local_pos = vec3(0,1,1); uv = vec2(1,0); }
                    if (corner == 3u) { local_pos = vec3(0,1,0); uv = vec2(0,0); }
                } else if (face_dir == 2u) {
                    if (corner == 0u) { local_pos = vec3(0,0,1); uv = vec2(0,1); }
                    if (corner == 1u) { local_pos = vec3(1,0,1); uv = vec2(1,1); }
                    if (corner == 2u) { local_pos = vec3(1,1,1); uv = vec2(1,0); }
                    if (corner == 3u) { local_pos = vec3(0,1,1); uv = vec2(0,0); }
                } else if (face_dir == 3u) {
                    if (corner == 0u) { local_pos = vec3(1,0,0); uv = vec2(0,1); }
                    if (corner == 1u) { local_pos = vec3(0,0,0); uv = vec2(1,1); }
                    if (corner == 2u) { local_pos = vec3(0,1,0); uv = vec2(1,0); }
                    if (corner == 3u) { local_pos = vec3(1,1,0); uv = vec2(0,0); }
                } else if (face_dir == 4u) {
                    if (corner == 0u) { local_pos = vec3(0,1,1); uv = vec2(0,1); }
                    if (corner == 1u) { local_pos = vec3(1,1,1); uv = vec2(1,1); }
                    if (corner == 2u) { local_pos = vec3(1,1,0); uv = vec2(1,0); }
                    if (corner == 3u) { local_pos = vec3(0,1,0); uv = vec2(0,0); }
                } else {
                    if (corner == 0u) { local_pos = vec3(0,0,0); uv = vec2(0,1); }
                    if (corner == 1u) { local_pos = vec3(1,0,0); uv = vec2(1,1); }
                    if (corner == 2u) { local_pos = vec3(1,0,1); uv = vec2(1,0); }
                    if (corner == 3u) { local_pos = vec3(0,0,1); uv = vec2(0,0); }
                }
            }

            void main() {
                ChunkMetadata meta = metadata[gl_BaseInstance];
                uint face_idx = (uint(gl_VertexID) / 6u) + meta.face_base_offset;
                uint vertex_idx = uint(gl_VertexID) % 6u;
                uint64_t raw_data = face_payload[face_idx];

                uint block_id = uint((raw_data >> 48) & uint64_t(0xFFFFu));
                uint face_dir = uint((raw_data >> 45) & uint64_t(0x7u));
                uint r_y = uint((raw_data >> 35) & uint64_t(0x3FFu));
                uint r_z = uint((raw_data >> 27) & uint64_t(0xFFu));
                uint r_x = uint((raw_data >> 19) & uint64_t(0xFFu));
                uint raw_ao = uint(raw_data & uint64_t(0xFFFFu));

                vec3 local_offset;
                vec2 uv;
                get_quad_vertex(vertex_idx, face_dir, local_offset, uv);

                vec3 block_world_pos = vec3(
                    float(meta.origin.x + int(r_x)),
                    float(meta.origin.y + int(r_y)),
                    float(meta.origin.z + int(r_z))
                ) + local_offset;

                v_TexCoord = atlas_uv[(block_id * 36u) + (face_dir * 6u) + vertex_idx];
                v_AmbientOcclusion = float(raw_ao & 0x3u) / 3.0;
                gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(block_world_pos - u_CameraOffset, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_TexCoord;
            in float v_AmbientOcclusion;

            uniform sampler2D u_BlockAtlas;

            out vec4 fragColor;

            void main() {
                vec4 texel = texture(u_BlockAtlas, v_TexCoord);
                if (texel.a < 0.1) discard;
                float ao_factor = mix(0.4, 1.0, v_AmbientOcclusion);
                fragColor = vec4(texel.rgb * ao_factor, texel.a);
            }
            """;
}
