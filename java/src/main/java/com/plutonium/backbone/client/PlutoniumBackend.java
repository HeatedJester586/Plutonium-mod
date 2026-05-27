package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import com.plutonium.backbone.common.Config;

/**
 * Owns the native ComputeEngine pointer used by GPU worldgen. Initialization
 * is lazy — the engine is only created the first time worldgen actually needs
 * the GPU. Vanilla servers that never enable GPU worldgen pay zero cost.
 *
 * Replaces the old PlutoniumCompositor, which mixed engine lifecycle with
 * the (now removed) rendering compositor.
 */
public final class PlutoniumBackend {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static volatile long enginePtr;

    private PlutoniumBackend() {
    }

    public static long getBackendPtr() {
        return enginePtr;
    }

    public static synchronized long ensureBackendForWorldgen() {
        if (enginePtr != 0) {
            return enginePtr;
        }
        NativeInterface.ensureLoaded();
        if (!NativeInterface.isLoaded()) {
            LOGGER.warn("[Plutonium] Native DLL not loaded; GPU worldgen unavailable.");
            return 0L;
        }
        int cpuThreads = Math.max(1, Config.CLIENT.cpuThreads.get());
        enginePtr = NativeInterface.nInitBackend(1, 1, cpuThreads);
        if (enginePtr == 0L) {
            LOGGER.error("[Plutonium] nInitBackend returned 0; GPU worldgen unavailable.");
        } else {
            LOGGER.info("[Plutonium] Backend initialized for worldgen (ptr=0x{}).", Long.toHexString(enginePtr));
        }
        return enginePtr;
    }

    public static synchronized void shutdown() {
        if (enginePtr != 0L && NativeInterface.isLoaded()) {
            NativeInterface.nShutdownBackend(enginePtr);
            LOGGER.info("[Plutonium] Backend shut down.");
        }
        enginePtr = 0L;
    }
}
