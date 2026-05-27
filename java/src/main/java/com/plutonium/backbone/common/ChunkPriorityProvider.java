package com.plutonium.backbone.common;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Provides real chunk priority based on Minecraft's ticket level system.
 * Priority scale: 0 (far/unloaded) to 9 (actively ticking at spawn chunks)
 */
public final class ChunkPriorityProvider {

    private ChunkPriorityProvider() {
    }

    /**
     * Get chunk priority from ticket level. Minecraft ticket levels: 24
     * (ticking) to 33 (unloaded) Priority: 9 (most urgent) to 0 (least urgent)
     */
    public static int getPriority(ServerLevel level, ChunkPos chunkPos) {
        try {
            // Try to get ticket level from chunk manager
            var chunkManager = level.getChunkSource().chunkMap;
            if (chunkManager != null) {
                // Use reflection to access distanceManager if available
                var distanceManager = getDistanceManager(chunkManager);
                if (distanceManager != null) {
                    int ticketLevel = getTicketLevel(distanceManager, chunkPos.toLong());
                    return Math.max(0, 33 - ticketLevel);
                }
            }
        } catch (Exception e) {
            // Silently fallback to distance-based priority
        }
        return getDistancePriority(chunkPos);
    }

    /**
     * Get distance-based priority fallback (inverse of manhattan distance from
     * origin). Closer chunks = higher priority (distance scores lower). Max
     * distance: 128 chunks (2048 blocks)
     */
    public static int getDistancePriority(ChunkPos chunkPos) {
        int distance = Math.abs(chunkPos.x) + Math.abs(chunkPos.z);
        return Math.max(0, 128 - Math.min(distance, 128));
    }

    private static Object getDistanceManager(Object chunkManager) {
        try {
            var field = chunkManager.getClass().getDeclaredField("distanceManager");
            field.setAccessible(true);
            return field.get(chunkManager);
        } catch (Exception e) {
            return null;
        }
    }

    private static int getTicketLevel(Object distanceManager, long chunkKey) {
        try {
            // Try to call getTicketLevel if it exists
            var method = distanceManager.getClass().getDeclaredMethod("getTicketLevel", long.class);
            method.setAccessible(true);
            Object result = method.invoke(distanceManager, chunkKey);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (Exception e) {
            // Fallback to default
        }
        return 33; // Unloaded default
    }
}
