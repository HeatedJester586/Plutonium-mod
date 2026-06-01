package com.plutonium.backbone.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.plutonium.backbone.PlutoniumMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the on-demand terrain-profile dump keybind (default F6, rebindable in
 * Controls). Registration is a mod-lifecycle event, so this subscriber is on the
 * MOD bus, unlike {@link TerrainProfilerEvents} which is on the FORGE bus.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TerrainProfilerKeys {

    public static KeyMapping dumpKey;

    private TerrainProfilerKeys() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        dumpKey = new KeyMapping(
                "key.plutonium.terrain_dump",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                "key.categories.plutonium");
        event.register(dumpKey);
    }
}
