package com.plutonium.backbone.common;

import com.plutonium.backbone.PlutoniumMod;
import com.plutonium.backbone.worldgen.GpuWorldgenState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerWorkEventHandler {

    private ServerWorkEventHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!Config.isGpuWorldgenEnabled()) {
            return;
        }
        if (event.phase == TickEvent.Phase.START && GpuWorldgenState.worldSeed() == 0L) {
            MinecraftServer server = event.getServer();
            if (server != null) {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    GpuWorldgenState.setWorldSeed(overworld.getSeed());
                }
            }
        }

        int cpuThreads = Math.max(1, Config.CLIENT.cpuThreads.get());
        int phaseBudget = Math.max(1, cpuThreads / 2);
        if (event.phase == TickEvent.Phase.START || event.phase == TickEvent.Phase.END) {
            PlutoniumWorkQueues.pumpWorldgenTasks(phaseBudget);
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!Config.isGpuWorldgenEnabled()) {
            return;
        }
        if (!event.getLevel().isClientSide() && event.getLevel() instanceof ServerLevel serverLevel) {
            GpuWorldgenState.setWorldSeed(serverLevel.getSeed());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            PlutoniumWorkQueues.clearWorldgenTasks();
            GpuWorldgenState.reset();
        }
    }
}
