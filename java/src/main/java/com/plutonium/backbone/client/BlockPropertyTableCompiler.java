package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

public final class BlockPropertyTableCompiler {

    public static final int FACES = 6;
    public static final int TABLE_SIZE = 65536 * FACES;

    public static final byte PROP_PASS_OPAQUE = 0x01;
    public static final byte PROP_PASS_CUTOUT = 0x02;
    public static final byte PROP_PASS_TRANSLUCENT = 0x04;
    public static final byte PROP_OCCLUDES = 0x08;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final Direction[] FACE_TO_DIRECTION = {
            Direction.EAST,
            Direction.WEST,
            Direction.SOUTH,
            Direction.NORTH,
            Direction.UP,
            Direction.DOWN
    };

    private static boolean uploaded;

    private BlockPropertyTableCompiler() {
    }

    public static void compileAndUpload() {
        if (uploaded) {
            return;
        }

        byte[] table = new byte[TABLE_SIZE];
        int states = 0;
        int renderable = 0;
        int occludingFaces = 0;

        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                if (id < 0 || id >= 65536) {
                    continue;
                }

                states++;
                byte pass = passMaskFor(state);
                if (pass == 0) {
                    continue;
                }

                renderable++;
                for (int face = 0; face < FACES; face++) {
                    byte props = pass;
                    if (occludesFace(state, FACE_TO_DIRECTION[face])) {
                        props |= PROP_OCCLUDES;
                        occludingFaces++;
                    }
                    table[id * FACES + face] = props;
                }
            }
        }

        NativeInterface.nPipelineUploadBlockProperties(table);
        uploaded = true;
        LOGGER.info("[Plutonium/Pipeline] uploaded block property table (states={}, renderable={}, occludingFaces={}).",
                states, renderable, occludingFaces);
    }

    public static void invalidate() {
        uploaded = false;
    }

    private static byte passMaskFor(BlockState state) {
        if (state == null || state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) {
            return 0;
        }

        RenderType type = ItemBlockRenderTypes.getChunkRenderType(state);
        if (type == RenderType.translucent()) {
            return PROP_PASS_TRANSLUCENT;
        }
        if (type == RenderType.cutout() || type == RenderType.cutoutMipped() || type == RenderType.tripwire()) {
            return PROP_PASS_CUTOUT;
        }
        return PROP_PASS_OPAQUE;
    }

    private static boolean occludesFace(BlockState state, Direction direction) {
        if (state == null || !state.canOcclude()) {
            return false;
        }
        try {
            return state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction);
        } catch (Throwable ignored) {
            return state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        }
    }
}
