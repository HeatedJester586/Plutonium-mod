package com.plutonium.backbone.common;

import com.plutonium.backbone.PlutoniumMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Player-velocity-driven chunk prefetcher.
 *
 * Plane mods, elytra, /tp, dimension joins — anything that moves the player
 * faster than vanilla's worker pool can keep up — causes the classic fog and
 * pop-in. Vanilla's solution is to wait. Ours: a background thread watches
 * each online player's velocity vector, projects a few seconds ahead, and
 * issues getChunk(..., FULL, true) for the chunks they're about to fly into.
 *
 * The prefetcher thread blocks on each getChunk call (vanilla bounces the
 * request back to the main thread via mainThreadProcessor.supplyAsync.join()),
 * but only the prefetcher thread is blocked — the main thread and chunk
 * worker pool stay busy. By the time the player arrives, the chunks are at
 * FULL status and rendering picks them up instantly.
 *
 * Inspired by C2ME's chunk-ticket-driven async loading. We don't touch ticket
 * levels because that risks compatibility with other chunk-loading mods.
 */
@Mod.EventBusSubscriber(modid = PlutoniumMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChunkPrefetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlutoniumPrefetch");

    // 0.05 b/tick squared = 1 b/sec — anything slower than a sprint is "stationary"
    private static final double STATIONARY_SPEED_SQ = 0.0025;
    // Smooth the velocity heavily so a brief stop doesn't kill the lookahead.
    private static final double VELOCITY_SMOOTHING = 0.7;
    // How many ticks ahead to project. 60 ticks = 3 seconds at 20 TPS.
    private static final int LOOKAHEAD_TICKS = 60;
    // Half-width of the prefetch corridor perpendicular to velocity, in chunks.
    private static final int CORRIDOR_HALF_WIDTH = 2;
    // Per player, max chunks queued per tick of the prefetcher. Higher numbers
    // can starve the chunk worker pool for the player's own immediate chunks.
    private static final int PREFETCH_BUDGET_PER_PLAYER = 12;
    // Don't keep an unbounded backlog if the player keeps changing direction.
    private static final int MAX_QUEUED_PER_PLAYER = 96;
    // Soft cap: never prefetch a chunk further than this from the player.
    private static final int MAX_PREFETCH_RADIUS_CHUNKS = 48;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong PREFETCH_HITS = new AtomicLong();
    private static final AtomicLong PREFETCH_MISSES = new AtomicLong();
    private static Thread worker;
    private static volatile MinecraftServer server;

    private static final Map<UUID, PlayerKinematics> KINEMATICS = new HashMap<>();

    private ChunkPrefetcher() {
    }

    public static long getHits() { return PREFETCH_HITS.get(); }
    public static long getMisses() { return PREFETCH_MISSES.get(); }
    public static int getPlayerCount() {
        synchronized (KINEMATICS) { return KINEMATICS.size(); }
    }
    public static int getQueuedTotal() {
        synchronized (KINEMATICS) {
            int total = 0;
            for (PlayerKinematics k : KINEMATICS.values()) total += k.queue.size();
            return total;
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        if (RUNNING.compareAndSet(false, true)) {
            worker = new Thread(ChunkPrefetcher::runLoop, "Plutonium-Prefetcher");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
            LOGGER.info("[Plutonium] ChunkPrefetcher started.");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (RUNNING.compareAndSet(true, false)) {
            Thread w = worker;
            worker = null;
            if (w != null) w.interrupt();
            server = null;
            synchronized (KINEMATICS) { KINEMATICS.clear(); }
            LOGGER.info("[Plutonium] ChunkPrefetcher stopped. Hits={}, misses={}.",
                    PREFETCH_HITS.get(), PREFETCH_MISSES.get());
        }
    }

    /**
     * Server tick on the main thread: sample every online player's position
     * and update their smoothed velocity. Cheap work — just a few doubles
     * per player per tick.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer s = server;
        if (s == null) return;
        synchronized (KINEMATICS) {
            for (ServerLevel level : s.getAllLevels()) {
                for (ServerPlayer player : level.players()) {
                    PlayerKinematics k = KINEMATICS.computeIfAbsent(player.getUUID(),
                            id -> new PlayerKinematics(level.dimension().location().toString()));
                    k.update(level, player.getX(), player.getZ());
                }
            }
            // Evict offline players' state so it doesn't leak forever.
            KINEMATICS.keySet().removeIf(uuid -> s.getPlayerList().getPlayer(uuid) == null);
        }
    }

    /**
     * The prefetcher worker drains each player's pending chunk queue and
     * issues blocking getChunk calls. Blocking inside this thread is fine —
     * we want the chunk pipeline to run, just not on the main thread.
     */
    private static void runLoop() {
        while (RUNNING.get()) {
            try {
                MinecraftServer s = server;
                if (s == null) {
                    Thread.sleep(100L);
                    continue;
                }

                refillPlayerQueues(s);

                boolean did = false;
                for (PlayerKinematics k : snapshotKinematics()) {
                    int issued = 0;
                    while (issued < PREFETCH_BUDGET_PER_PLAYER) {
                        Long packed = k.queue.pollFirst();
                        if (packed == null) break;
                        ServerLevel level = s.getLevel(k.dimensionKey());
                        if (level == null) break;
                        if (issuePrefetch(level, packed)) {
                            did = true;
                            issued++;
                        }
                    }
                }

                Thread.sleep(did ? 1L : 25L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOGGER.warn("[Plutonium] ChunkPrefetcher loop error: {}", t.toString());
                try { Thread.sleep(100L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static PlayerKinematics[] snapshotKinematics() {
        synchronized (KINEMATICS) {
            return KINEMATICS.values().toArray(new PlayerKinematics[0]);
        }
    }

    private static void refillPlayerQueues(MinecraftServer s) {
        synchronized (KINEMATICS) {
            for (PlayerKinematics k : KINEMATICS.values()) {
                if (!k.hasMeaningfulVelocity()) continue;
                if (k.queue.size() >= MAX_QUEUED_PER_PLAYER) continue;
                k.populateCorridor();
            }
        }
    }

    private static boolean issuePrefetch(ServerLevel level, long packedChunk) {
        int cx = unpackX(packedChunk);
        int cz = unpackZ(packedChunk);
        long startNs = System.nanoTime();
        try {
            ServerChunkCache cache = level.getChunkSource();
            // getChunk(..., true) bounces this off-thread call through the
            // main thread processor and returns once the chunk reaches the
            // requested status. The chunk worker pool does the actual gen.
            ChunkAccess chunk = cache.getChunk(cx, cz, ChunkStatus.FULL, true);
            long elapsedNs = System.nanoTime() - startNs;
            if (chunk != null) {
                long hits = PREFETCH_HITS.incrementAndGet();
                // Verbose per-prefetch logging for the first few, then every 32nd
                // hit so we don't drown the log under a long flight.
                if (hits <= 8 || (hits & 31) == 0) {
                    LOGGER.info("[Plutonium/Prefetch] hit #{} chunk ({},{}) in {} ms (dim={}).",
                            hits, cx, cz,
                            String.format(java.util.Locale.ROOT, "%.2f", elapsedNs / 1_000_000.0),
                            level.dimension().location());
                }
                return true;
            }
            long misses = PREFETCH_MISSES.incrementAndGet();
            if (misses <= 8 || (misses & 31) == 0) {
                LOGGER.warn("[Plutonium/Prefetch] miss #{} chunk ({},{}) after {} ms (chunk returned null).",
                        misses, cx, cz,
                        String.format(java.util.Locale.ROOT, "%.2f", elapsedNs / 1_000_000.0));
            }
        } catch (Throwable t) {
            long misses = PREFETCH_MISSES.incrementAndGet();
            LOGGER.warn("[Plutonium/Prefetch] miss #{} chunk ({},{}) threw {}: {}",
                    misses, cx, cz, t.getClass().getSimpleName(), t.getMessage());
        }
        return false;
    }

    static long packChunk(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    static int unpackX(long key) { return (int) key; }
    static int unpackZ(long key) { return (int) (key >> 32); }

    private static final class PlayerKinematics {
        final String dimensionId;
        private double lastX;
        private double lastZ;
        private double velX;
        private double velZ;
        private double currentX;
        private double currentZ;
        private boolean haveLast;
        final LinkedBlockingDeque<Long> queue = new LinkedBlockingDeque<>();
        final LinkedHashSet<Long> inQueue = new LinkedHashSet<>();

        PlayerKinematics(String dimensionId) {
            this.dimensionId = dimensionId;
        }

        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey() {
            return net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    new net.minecraft.resources.ResourceLocation(dimensionId));
        }

        private long lastVelocityLogNanos = 0L;
        private static final long VELOCITY_LOG_INTERVAL_NS = 5_000_000_000L;

        synchronized void update(ServerLevel level, double x, double z) {
            currentX = x;
            currentZ = z;
            if (haveLast) {
                double dx = x - lastX;
                double dz = z - lastZ;
                velX = VELOCITY_SMOOTHING * velX + (1.0 - VELOCITY_SMOOTHING) * dx;
                velZ = VELOCITY_SMOOTHING * velZ + (1.0 - VELOCITY_SMOOTHING) * dz;
                // Log velocity once every 5s when moving fast enough to prefetch.
                if (hasMeaningfulVelocity()) {
                    long now = System.nanoTime();
                    if (now - lastVelocityLogNanos > VELOCITY_LOG_INTERVAL_NS) {
                        lastVelocityLogNanos = now;
                        double speed = Math.sqrt(velX * velX + velZ * velZ);
                        LOGGER.info("[Plutonium/Prefetch] velocity={} b/tick ({} b/s), queue={}, dim={}.",
                                String.format(java.util.Locale.ROOT, "%.2f", speed),
                                String.format(java.util.Locale.ROOT, "%.1f", speed * 20.0),
                                queue.size(),
                                dimensionId);
                    }
                }
            }
            lastX = x;
            lastZ = z;
            haveLast = true;
        }

        synchronized boolean hasMeaningfulVelocity() {
            return velX * velX + velZ * velZ > STATIONARY_SPEED_SQ;
        }

        synchronized void populateCorridor() {
            double speed = Math.sqrt(velX * velX + velZ * velZ);
            if (speed < 0.05) return;

            // Future position in LOOKAHEAD_TICKS ticks.
            double futureX = currentX + velX * LOOKAHEAD_TICKS;
            double futureZ = currentZ + velZ * LOOKAHEAD_TICKS;

            int playerCX = Math.floorDiv((int) Math.floor(currentX), 16);
            int playerCZ = Math.floorDiv((int) Math.floor(currentZ), 16);
            int futureCX = Math.floorDiv((int) Math.floor(futureX), 16);
            int futureCZ = Math.floorDiv((int) Math.floor(futureZ), 16);

            // Sample along the path from current to future.
            int travelDX = futureCX - playerCX;
            int travelDZ = futureCZ - playerCZ;
            int steps = Math.max(1, Math.max(Math.abs(travelDX), Math.abs(travelDZ)));

            // Perpendicular vector for the corridor width.
            double invSpeed = 1.0 / speed;
            double nx = velX * invSpeed;
            double nz = velZ * invSpeed;
            // Rotate 90deg: perp = (-nz, nx)
            double px = -nz;
            double pz = nx;

            for (int step = 1; step <= steps; step++) {
                double t = (double) step / (double) steps;
                int baseCX = playerCX + (int) Math.round(travelDX * t);
                int baseCZ = playerCZ + (int) Math.round(travelDZ * t);

                for (int w = -CORRIDOR_HALF_WIDTH; w <= CORRIDOR_HALF_WIDTH; w++) {
                    int wcx = baseCX + (int) Math.round(px * w);
                    int wcz = baseCZ + (int) Math.round(pz * w);
                    int dx = wcx - playerCX;
                    int dz = wcz - playerCZ;
                    if (dx * dx + dz * dz > MAX_PREFETCH_RADIUS_CHUNKS * MAX_PREFETCH_RADIUS_CHUNKS) continue;
                    long key = packChunk(wcx, wcz);
                    if (!inQueue.add(key)) continue;
                    queue.addLast(key);
                    if (queue.size() >= MAX_QUEUED_PER_PLAYER) return;
                }
            }
        }
    }
}
