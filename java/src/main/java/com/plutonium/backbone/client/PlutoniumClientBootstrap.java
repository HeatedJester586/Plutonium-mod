package com.plutonium.backbone.client;

import com.plutonium.backbone.PlutoniumMod;
import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client lifecycle: load native DLL + mesh pool when entering a world.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, value = Dist.CLIENT)
public final class PlutoniumClientBootstrap {

    private static final Logger LOGGER = LogManager.getLogger("Plutonium");

    private PlutoniumClientBootstrap() {
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        NativeInterface.ensureLoaded();
        if (!NativeInterface.isLoaded()) {
            LOGGER.error("[Plutonium] Native DLL not loaded — native meshing disabled. Build BackboneEngine_uranium_mod (Release|x64) and run gradlew copyNativeDll.");
            return;
        }
        if (Config.getChunkBuilder() == Config.ChunkBuilder.NATIVE) {
            TextureAtlasMirror.invalidate();
            LOGGER.info("[Plutonium] Native chunk builder armed.");
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        CudaPipeline.shutdown();
        DirtyChunkBatcher.clear();
        NativeChunkRenderer.shutdown();
    }
}
