package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors vanilla baked cube-face UVs into native memory.
 *
 * Minecraft has one block atlas texture, but each BakedQuad stores the exact
 * four UV corners it should sample from that atlas. The native mesher uses this
 * table so it does not have to guess face orientation from a plain rectangle.
 */
@Mod.EventBusSubscriber(modid = "plutonium", value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class TextureAtlasMirror {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("PlutoniumMesh");

    private static final int FACES = 6;
    private static final int NATIVE_VERTS_PER_FACE = 6;
    private static final int UV_COMPONENTS = 2;
    private static final int FLOATS_PER_UV_ENTRY = FACES * NATIVE_VERTS_PER_FACE * UV_COMPONENTS;
    private static final int BYTES_PER_UV_ENTRY = FLOATS_PER_UV_ENTRY * Float.BYTES;
    private static final float QUAD_POS_EPS_SQ = 1.0e-6f;

    // v4 packed-face order: +X, -X, +Z, -Z, +Y, -Y.
    private static final Direction[] FACE_TO_DIRECTION = {
            Direction.EAST,
            Direction.WEST,
            Direction.SOUTH,
            Direction.NORTH,
            Direction.UP,
            Direction.DOWN
    };

    // Must match NativeChunkRenderer's FACE_VERTICES exactly.
    private static final float[][][] NATIVE_FACE_POS = {
            {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 0, 1}, {1, 1, 0}, {1, 1, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 0, 0}, {0, 1, 1}, {0, 1, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0}},
            {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 1}, {1, 1, 0}, {0, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 0}, {1, 0, 1}, {0, 0, 1}}
    };

    private static final float[][] FALLBACK_UV = {
            {0, 1},
            {1, 1},
            {1, 0},
            {0, 1},
            {1, 0},
            {0, 0}
    };

    private static final AtomicBoolean uploaded = new AtomicBoolean(false);
    private static volatile boolean[] nativeSimpleSolid = new boolean[0];

    private TextureAtlasMirror() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE) return;
        if (uploaded.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;
        BlockRenderDispatcher brd = mc.getBlockRenderer();
        if (brd == null) return;
        if (!uploaded.compareAndSet(false, true)) return;
        try {
            NativeInterface.ensureLoaded();
            if (!NativeInterface.isLoaded()) {
                LOGGER.error("[Plutonium] Native library not loaded; native mesher disabled.");
                return;
            }
            buildAndUpload(brd);
        } catch (Throwable t) {
            LOGGER.error("[Plutonium] Failed to mirror texture atlas to native renderer; chunk shader will emit missing UVs.", t);
        }
    }

    public static void invalidate() {
        uploaded.set(false);
    }

    private static void buildAndUpload(BlockRenderDispatcher brd) {
        int maxStateId = 0;
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                maxStateId = Math.max(maxStateId, Block.getId(state));
            }
        }

        int entryCount = Math.max(1, maxStateId + 1);
        ByteBuffer rendererAtlasLookup = ByteBuffer
                .allocateDirect(65536 * BYTES_PER_UV_ENTRY)
                .order(ByteOrder.LITTLE_ENDIAN);

        int filled = 0;
        int particleFallbacks = 0;
        int nativeSimple = 0;
        boolean[] simpleTable = new boolean[entryCount];
        RandomSource rng = RandomSource.create(42L);
        float[] uvEntry = new float[FLOATS_PER_UV_ENTRY];
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int rawId = Block.getId(state);
                if (rawId < 0 || rawId >= entryCount) continue;
                BakedModel model = modelFor(brd, state);
                if (model == null) continue;

                boolean hasFaceUv = fillVanillaFaceUvEntry(model, state, rng, uvEntry);
                if (hasFaceUv) {
                    putRendererAtlasLookup(rendererAtlasLookup, rawId, uvEntry);
                    filled++;
                } else {
                    // Fallback: use the block's particle icon UV bounds so the state
                    // gets *some* valid texture rect. Without this, complex blocks
                    // (grass overlay, leaves, stairs, slabs, fences, flowers, etc.)
                    // sample atlas[0,0] and render as pink/magenta missing-texture.
                    if (putParticleFallbackUv(rendererAtlasLookup, model, state, rawId)) {
                        particleFallbacks++;
                    }
                }

                if (hasFaceUv && isSimpleNativeSolid(model, state, rng)) {
                    simpleTable[rawId] = true;
                    nativeSimple++;
                }
            }
        }
        nativeSimpleSolid = simpleTable;

        rendererAtlasLookup.position(0);
        NativeChunkRenderer.uploadAtlasLookup(rendererAtlasLookup);
        LOGGER.info("[Plutonium] Uploaded baked quad UV mirror: {} face-UV entries, {} particle fallbacks ({} simple native, {} slots, {} atlas lookup bytes).",
                filled, particleFallbacks, nativeSimple, entryCount, rendererAtlasLookup.capacity());
    }

    /**
     * Fallback path: writes the particle-icon UV rect for a state that didn't
     * qualify for per-face baked-quad UVs. The particle icon is the texture MC
     * uses for break/punch particles, so every block has one — including all
     * the complex multi-quad models (grass, leaves, stairs, slabs, fences,
     * flowers, redstone, etc.). Without this fallback those states sample
     * atlas (0,0) and render as the top-left pixel (usually pink/magenta).
     */
    private static boolean putParticleFallbackUv(ByteBuffer out,
                                                 BakedModel model,
                                                 BlockState state,
                                                 int rawId) {
        if (rawId < 0 || rawId >= 65536 || model == null) {
            return false;
        }
        TextureAtlasSprite sprite;
        try {
            sprite = model.getParticleIcon(ModelData.EMPTY);
        } catch (Throwable ignored) {
            try {
                sprite = model.getParticleIcon();
            } catch (Throwable ignored2) {
                return false;
            }
        }
        if (sprite == null) {
            return false;
        }
        float minU = sprite.getU0();
        float minV = sprite.getV0();
        float maxU = sprite.getU1();
        float maxV = sprite.getV1();
        if (!Float.isFinite(minU) || !Float.isFinite(minV)
                || !Float.isFinite(maxU) || !Float.isFinite(maxV)
                || maxU <= minU || maxV <= minV) {
            return false;
        }
        out.position(rawId * BYTES_PER_UV_ENTRY);
        for (int face = 0; face < FACES; face++) {
            for (int vertex = 0; vertex < NATIVE_VERTS_PER_FACE; vertex++) {
                float fu = FALLBACK_UV[vertex][0];
                float fv = FALLBACK_UV[vertex][1];
                out.putFloat(minU + (maxU - minU) * fu);
                out.putFloat(minV + (maxV - minV) * fv);
            }
        }
        return true;
    }

    private static void putRendererAtlasLookup(ByteBuffer out, int rawId, float[] uvEntry) {
        if (rawId < 0 || rawId >= 65536 || uvEntry == null || uvEntry.length < FLOATS_PER_UV_ENTRY) {
            return;
        }

        out.position(rawId * BYTES_PER_UV_ENTRY);
        for (int i = 0; i < FLOATS_PER_UV_ENTRY; i++) {
            if (!Float.isFinite(uvEntry[i])) {
                return;
            }
            out.putFloat(uvEntry[i]);
        }
    }

    public static boolean isNativeSimpleSolid(BlockState state) {
        if (state == null) return false;
        int rawId = Block.getId(state);
        boolean[] table = nativeSimpleSolid;
        return rawId >= 0 && rawId < table.length && table[rawId];
    }

    private static BakedModel modelFor(BlockRenderDispatcher brd, BlockState state) {
        try {
            return brd.getBlockModel(state);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean fillVanillaFaceUvEntry(BakedModel model,
                                                  BlockState state,
                                                  RandomSource rng,
                                                  float[] out) {
        int cursor = 0;
        for (int face = 0; face < FACES; face++) {
            BakedQuad quad = singleFaceQuad(model, state, face, rng);
            if (quad == null) {
                return false;
            }
            int[] vertices = quad.getVertices();
            int stride = vertexStride(vertices);
            if (stride < 0) {
                return false;
            }
            for (int nativeVertex = 0; nativeVertex < NATIVE_VERTS_PER_FACE; nativeVertex++) {
                int bakedVertex = closestBakedVertex(vertices, stride, face, nativeVertex);
                if (bakedVertex < 0) {
                    return false;
                }
                out[cursor++] = vertexFloat(vertices, stride, bakedVertex, 4);
                out[cursor++] = vertexFloat(vertices, stride, bakedVertex, 5);
            }
        }
        return true;
    }

    private static BakedQuad singleFaceQuad(BakedModel model,
                                            BlockState state,
                                            int nativeFace,
                                            RandomSource rng) {
        rng.setSeed(42L);
        try {
            List<BakedQuad> quads = model.getQuads(state, FACE_TO_DIRECTION[nativeFace], rng, ModelData.EMPTY, null);
            if (quads == null || quads.size() != 1) {
                return null;
            }
            return quads.get(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSimpleNativeSolid(BakedModel model,
                                               BlockState state,
                                               RandomSource rng) {
        rng.setSeed(42L);
        try {
            List<BakedQuad> generalQuads = model.getQuads(state, null, rng, ModelData.EMPTY, null);
            if (generalQuads != null && !generalQuads.isEmpty()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        for (int face = 0; face < FACES; face++) {
            BakedQuad quad = singleFaceQuad(model, state, face, rng);
            if (quad == null || quad.isTinted() || !quadMatchesNativeCubeFace(quad, face)) {
                return false;
            }
        }
        return true;
    }

    private static boolean quadMatchesNativeCubeFace(BakedQuad quad, int nativeFace) {
        int[] vertices = quad.getVertices();
        int stride = vertexStride(vertices);
        if (stride < 0) {
            return false;
        }
        for (int nativeVertex = 0; nativeVertex < NATIVE_VERTS_PER_FACE; nativeVertex++) {
            if (closestBakedVertex(vertices, stride, nativeFace, nativeVertex) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int vertexStride(int[] vertices) {
        if (vertices == null || vertices.length < 24 || (vertices.length % 4) != 0) {
            return -1;
        }
        int stride = vertices.length / 4;
        return stride > 5 ? stride : -1;
    }

    private static int closestBakedVertex(int[] vertices, int stride, int nativeFace, int nativeVertex) {
        float tx = NATIVE_FACE_POS[nativeFace][nativeVertex][0];
        float ty = NATIVE_FACE_POS[nativeFace][nativeVertex][1];
        float tz = NATIVE_FACE_POS[nativeFace][nativeVertex][2];
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float dx = vertexFloat(vertices, stride, i, 0) - tx;
            float dy = vertexFloat(vertices, stride, i, 1) - ty;
            float dz = vertexFloat(vertices, stride, i, 2) - tz;
            float dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return bestDist <= QUAD_POS_EPS_SQ ? best : -1;
    }

    private static float vertexFloat(int[] vertices, int stride, int vertex, int offset) {
        return Float.intBitsToFloat(vertices[vertex * stride + offset]);
    }
}
