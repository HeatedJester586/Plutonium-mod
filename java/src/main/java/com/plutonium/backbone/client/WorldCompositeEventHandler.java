package com.plutonium.backbone.client;

import com.plutonium.backbone.PlutoniumMod;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Native-MDI terrain lifecycle. When the NATIVE chunk builder is active this
 * drives per-tick chunk uploads ({@link CudaPipeline#tick}) and block-edit
 * invalidation ({@link DirtyChunkBatcher}). The old compositor / voxel-streamer /
 * GpuMeshManager paths were removed in the Tier-1 revive.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, value = Dist.CLIENT)
public final class WorldCompositeEventHandler {

    private WorldCompositeEventHandler() {
    }

    private static boolean nativeActive() {
        try {
            return Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE;
        } catch (Throwable t) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !nativeActive()) {
            return;
        }
        CudaPipeline.tick(mc);
        DirtyChunkBatcher.flushToNative(mc.level);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide() || !nativeActive()) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        DirtyChunkBatcher.discardChunk(pos.x, pos.z);
        CudaPipeline.unregisterChunk(pos.x, pos.z);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (nativeActive() && mc.level != null) {
            DirtyChunkBatcher.markBlockDirty(mc.level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (nativeActive() && mc.level != null) {
            DirtyChunkBatcher.markBlockDirty(mc.level, event.getPos());
        }
    }
}
