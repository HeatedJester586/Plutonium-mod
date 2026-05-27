package com.plutonium.backbone.client;

/**
 * Scores candidate chunks by how urgently the native pipeline should load them
 * given the player's recent velocity. The intent is "fly fast in a plane mod
 * without seeing fog ahead" — chunks in the velocity vector get priority, chunks
 * behind get deprioritized.
 *
 * Velocity is exponentially smoothed in the horizontal plane (Y is ignored —
 * chunks are columns). Without smoothing, a single jittery client tick can flip
 * the priority direction.
 */
public final class VelocityChunkPrioritizer {

    private static final double SMOOTHING = 0.7;
    private static final double STATIONARY_SPEED_SQ = 0.0025;
    private static final double DIRECTIONAL_WEIGHT = 0.5;

    private static double velX;
    private static double velZ;
    private static double lastX;
    private static double lastZ;
    private static boolean haveLast;

    private VelocityChunkPrioritizer() {
    }

    public static void updatePlayerKinematics(double x, double z) {
        if (haveLast) {
            double dx = x - lastX;
            double dz = z - lastZ;
            velX = SMOOTHING * velX + (1.0 - SMOOTHING) * dx;
            velZ = SMOOTHING * velZ + (1.0 - SMOOTHING) * dz;
        }
        lastX = x;
        lastZ = z;
        haveLast = true;
    }

    public static void reset() {
        velX = 0.0;
        velZ = 0.0;
        haveLast = false;
    }

    public static double scoreChunk(double playerX, double playerZ, int chunkX, int chunkZ) {
        double chunkCenterX = chunkX * 16.0 + 8.0;
        double chunkCenterZ = chunkZ * 16.0 + 8.0;
        double dx = chunkCenterX - playerX;
        double dz = chunkCenterZ - playerZ;
        double distSq = dx * dx + dz * dz;
        if (distSq < 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        double dist = Math.sqrt(distSq);

        double speedSq = velX * velX + velZ * velZ;
        double directionalBoost = 1.0;
        if (speedSq > STATIONARY_SPEED_SQ) {
            double speed = Math.sqrt(speedSq);
            double forwardness = (dx * velX + dz * velZ) / (dist * speed);
            directionalBoost = 1.0 + forwardness * DIRECTIONAL_WEIGHT;
        }
        return directionalBoost / dist;
    }

    public static boolean hasMeaningfulVelocity() {
        return velX * velX + velZ * velZ > STATIONARY_SPEED_SQ;
    }

    public static double velocityX() {
        return velX;
    }

    public static double velocityZ() {
        return velZ;
    }
}
