package com.autism.seedcracker.finder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Budgeted chunk-scan cursor.
 *
 * Spreads a full chunk-bubble scan across multiple ticks so the game never does a full-volume scan
 * in a single tick (the FPS spike / lag source). Keeps a queue of chunks around the player and an
 * index into it; each call to {@link #nextBatch} returns up to {@code perTick} chunks, refreshing
 * the queue once it's exhausted (and the rescan delay has passed).
 *
 * One instance per finder module. Usage:
 *   private final ScanCursor scan = new ScanCursor();
 *   // in tick():
 *   for (LevelChunk chunk : scan.nextBatch(mc, scanRadius.get(), rescanMs.get(), chunksPerTick.get())) {
 *       scanChunk(mc, chunk);
 *   }
 */
public final class ScanCursor {
    private List<ChunkPos> queue = List.of();
    private int index;
    private long lastRefreshMs;
    private boolean refreshed;
    private java.lang.ref.WeakReference<Object> world = new java.lang.ref.WeakReference<>(null);
    private ChunkPos centre;
    private int radius;
    private long generation;

    /**
     * Return the next batch of up to {@code perTick} chunks to scan this tick. Refreshes the queue
     * when the previous pass finished AND {@code rescanMs} has elapsed since the last refresh.
     */
    public List<LevelChunk> nextBatch(Minecraft mc, int radius, long rescanMs, int perTick) {
        if (mc == null || mc.level == null || mc.player == null) { reset(); return List.of(); }
        var level = mc.level;
        List<ChunkPos> positions = nextPositions(level, mc.player.chunkPosition(), radius, rescanMs, perTick,
            System.nanoTime() / 1_000_000L,
            () -> ChunkScanHelper.loadedChunksAround(mc, radius).stream().map(LevelChunk::getPos).toList(),
            pos -> level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null);
        List<LevelChunk> batch = new ArrayList<>(positions.size());
        for (ChunkPos pos : positions) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) batch.add(chunk);
        }
        return List.copyOf(batch);
    }

    List<ChunkPos> nextPositions(Object currentWorld, ChunkPos player, int requestedRadius, long rescanMs, int perTick,
                                long now, Supplier<List<ChunkPos>> loaded, Predicate<ChunkPos> stillLoaded) {
        if (currentWorld == null || player == null) { reset(); return List.of(); }
        int nextRadius = Math.clamp(requestedRadius, 0, 64);
        if (world.get() != currentWorld || radius != nextRadius || centre == null
            || distance(centre, player) > Math.max(1, nextRadius / 2)) {
            reset(); world = new java.lang.ref.WeakReference<>(currentWorld); radius = nextRadius; centre = player;
        }
        if (index >= queue.size() && (!refreshed || now < lastRefreshMs || now - lastRefreshMs >= Math.max(0, rescanMs))) {
            queue = loaded.get().stream().filter(pos -> pos != null && distance(pos, player) <= nextRadius)
                .distinct().sorted(Comparator.<ChunkPos>comparingLong(pos -> {
                    long dx = (long) pos.x() - player.x(), dz = (long) pos.z() - player.z();
                    return dx * dx + dz * dz;
                }).thenComparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z)).toList();
            index = 0; lastRefreshMs = now; refreshed = true; centre = player; generation++;
        }
        int budget = Math.clamp(perTick, 1, 128);
        List<ChunkPos> batch = new ArrayList<>(budget);
        int inspected = 0;
        while (index < queue.size() && batch.size() < budget && inspected++ < Math.max(64, budget * 8)) {
            ChunkPos pos = queue.get(index++);
            if (distance(pos, player) <= nextRadius && stillLoaded.test(pos)) batch.add(pos);
        }
        return List.copyOf(batch);
    }

    private static long distance(ChunkPos first, ChunkPos second) {
        return Math.max(Math.abs((long) first.x() - second.x()), Math.abs((long) first.z() - second.z()));
    }

    /** Progress through the current pass (0.0 - 1.0), for an optional info readout. */
    public double progress() {
        return queue.isEmpty() ? 1.0 : index / (double) queue.size();
    }

    /** Identifies a sweep, including automatic world/range/recenter invalidations. */
    public long generation() { return generation; }

    /** Reset (call on enable/disable or radius change). */
    public void reset() {
        generation++;
        queue = List.of(); index = 0; lastRefreshMs = 0; refreshed = false;
        world.clear(); centre = null; radius = 0;
    }
}
