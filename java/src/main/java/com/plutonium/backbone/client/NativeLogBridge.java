package com.plutonium.backbone.client;

import com.plutonium.backbone.bridge.NativeInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains a thread-safe error/log queue maintained by the C++ DLL and forwards
 * each message into Minecraft's logger. Without this, native-side errors
 * (CUDA failures, JNI argument mismatches, mesh-pipeline assertion fails)
 * vanish into the C runtime's stderr buffer and never surface in latest.log.
 *
 * The C++ side must expose:
 *   JNIEXPORT jobjectArray JNICALL
 *   Java_com_plutonium_backbone_bridge_NativeInterface_nDrainNativeLogs(JNIEnv*, jclass);
 *
 * If the symbol is missing this class quietly stops polling — it's a
 * diagnostic, never load-bearing.
 */
public final class NativeLogBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plutonium/Native");

    private static volatile boolean disabled;

    private NativeLogBridge() {
    }

    public static void drain() {
        if (disabled || !NativeInterface.isLoaded()) {
            return;
        }
        try {
            String[] messages = NativeInterface.nDrainNativeLogs();
            if (messages == null || messages.length == 0) {
                return;
            }
            for (String raw : messages) {
                if (raw == null || raw.isEmpty()) continue;
                routeMessage(raw);
            }
        } catch (UnsatisfiedLinkError ule) {
            disabled = true;
            LOGGER.warn("[Plutonium/Native] nDrainNativeLogs not exported by DLL; native error capture disabled. "
                    + "Rebuild BackboneEngine with the JNI log bridge added to surface C++/CUDA errors in Minecraft logs.");
        } catch (Throwable t) {
            LOGGER.warn("[Plutonium/Native] log drain threw {}: {}", t.getClass().getSimpleName(), t.getMessage());
        }
    }

    private static void routeMessage(String raw) {
        char tag = raw.charAt(0);
        String body = raw.length() > 2 && raw.charAt(1) == '|' ? raw.substring(2) : raw;
        switch (tag) {
            case 'E' -> LOGGER.error(body);
            case 'W' -> LOGGER.warn(body);
            case 'D' -> LOGGER.debug(body);
            default  -> LOGGER.info(body);
        }
    }
}
