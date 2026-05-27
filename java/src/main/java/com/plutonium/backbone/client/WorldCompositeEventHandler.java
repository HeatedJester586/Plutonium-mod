package com.plutonium.backbone.client;

import com.plutonium.backbone.PlutoniumMod;
import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, value = Dist.CLIENT)
public final class WorldCompositeEventHandler {

    private static boolean streamerInitialized = false;
    private static int logThrottle = 0;

    private WorldCompositeEventHandler() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }

        BackendMode mode;
        try {
            mode = BackendMode.fromString(Config.CLIENT.backend.get());
        } catch (Throwable t) {
            return;
        }

        if (mode == BackendMode.OPENGL) {
            if (Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE
                    && !Config.isGpuWorldgenEnabled()) {
                long ptr = PlutoniumCompositor.getBackendPtr();
                if (ptr != 0) {
                    NativeInterface.nStopPhysics(ptr);
                }
                PlutoniumCompositor.requestShutdown();
                streamerInitialized = false;
                PlutoniumCompositor.renderFullscreenReplace(mode);
            }
            return;
        }

        PlutoniumCompositor.renderFullscreenReplace(mode);
        long ptr = PlutoniumCompositor.getBackendPtr();
        if (ptr != 0) {
            if (!streamerInitialized) {
                streamerInitialized = true;
                System.out.println("[Plutonium] Backbone active (ptr=0x" + Long.toHexString(ptr) + ").");
            }
            if (logThrottle++ > 200) {
                System.out.println("[Plutonium] Active: ptr=0x" + Long.toHexString(ptr));
                logThrottle = 0;
            }
        } else if (logThrottle++ > 200) {
            System.err.println("[Plutonium] BackendPtr is NULL.");
            logThrottle = 0;
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        System.out.println("[Plutonium] Level unloading - shutting down.");
        long ptr = PlutoniumCompositor.getBackendPtr();
        if (ptr != 0) {
            NativeInterface.nStopPhysics(ptr);
        }
        PlutoniumCompositor.requestShutdown();
        streamerInitialized = false;
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide() || Config.getChunkBuilder() != Config.ChunkBuilder.NATIVE) {
            return;
        }

        ChunkPos pos = event.getChunk().getPos();
        DirtyChunkBatcher.discardChunk(pos.x, pos.z);
        CudaPipeline.unregisterChunk(pos.x, pos.z);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE && mc.level != null) {
            DirtyChunkBatcher.markBlockDirty(mc.level, event.getPos());
        } else {
            pushBlockToNative(event.getPos());
        }
        invalidateSectionAt(event.getPos());
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE && mc.level != null) {
            DirtyChunkBatcher.markBlockDirty(mc.level, event.getPos());
        } else {
            pushBlockToNative(event.getPos());
        }
        invalidateSectionAt(event.getPos());
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.level.isClientSide && event.phase == TickEvent.Phase.END) {
            long ptr = PlutoniumCompositor.getBackendPtr();
            if (ptr != 0) {
                NativeInterface.nSignalTick(ptr);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE) {
            PlutoniumCompositor.ensureBackendForWorldgen();
            CudaPipeline.tick(mc);
            DirtyChunkBatcher.flushToNative(mc.level);
            return;
        }
        GpuMeshManager.tickMeshPipeline(mc);
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (VoxelStreamer.hasDirtyBlocks()) {
            VoxelStreamer.flushChanges(PlutoniumCompositor.getBackendPtr());
        }
    }

    private static void pushBlockToNative(BlockPos pos) {
        int lx = Math.floorMod(pos.getX(), 1024);
        int lz = Math.floorMod(pos.getZ(), 1024);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        BlockState state = mc.level.getBlockState(pos);
        byte id = (byte) Block.getId(state);
        VoxelStreamer.sendBlockUpdate(lx, pos.getY(), lz, id);
    }

    private static void invalidateSectionAt(BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int sy = pos.getY() >> 4;
        GpuMeshManager.invalidateSection(cx, sy, cz);
        PlutoniumLodRenderer.invalidateChunk(cx, cz);
    }
}
