package com.autism.seedcracker.finder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * Client-thread BaseTracker reporter: scans at most one admitted chunk per tick with
 * {@link BaseConfidence}, publishing fresh observations every 20 ticks. Excess coverage
 * stays explicitly deferred rather than extending observation freshness indefinitely.
 */
public final class FinderReport {
    private static final Set<FinderReport> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());
    private final String source;
    /** A raw heuristic clue may retain a low score, but cannot manufacture stronger building evidence. */
    private final int floor;
    private record Sample(Object chunk, int confidence, long tick) {}
    private final Map<ChunkPos, Sample> cache = new LinkedHashMap<>();
    private Object world;
    private long age;
    private int ticks;
    private int deferred;
    private static final int REFRESH_TICKS = 100;
    private static final int MAX_CANDIDATES = 4096;
    // ponytail: one scan/tick covers at most 200 chunks within 200 ticks; raise the scan budget, not stale trust, to cover more.
    private static final int MAX_SAMPLE_AGE_TICKS = 200;

    public FinderReport(String source, int floor) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Finder source is required");
        this.source = source.trim();
        this.floor = Math.clamp(floor, 0, BaseConfidence.NATURAL_COMPATIBLE_LIMIT);
        synchronized (INSTANCES) { INSTANCES.add(this); }
    }

    /** Call once per affected chunk on the client thread, after packet data is applied. */
    public static void invalidateAll(Object currentWorld, ChunkPos pos) {
        if (currentWorld == null || pos == null) return;
        FinderReport[] snapshot;
        synchronized (INSTANCES) { snapshot = INSTANCES.toArray(FinderReport[]::new); }
        for (FinderReport reporter : snapshot) {
            if (reporter.world == currentWorld) reporter.invalidate(pos);
        }
    }

    /** Call every tick with the current flagged set. */
    public void tick(Minecraft mc, Set<ChunkPos> flagged) {
        var level = mc.level;
        tickEvidence(level, flagged, pos -> level.getChunkSource().getChunkNow(pos.x(), pos.z()),
            pos -> BaseConfidence.score(level.getChunkSource().getChunkNow(pos.x(), pos.z())),
            (pos, confidence) -> BaseTracker.report(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, confidence, source));
    }

    void tick(Object currentWorld, Set<ChunkPos> flagged, Predicate<ChunkPos> loaded,
              ToIntFunction<ChunkPos> scorer, BiConsumer<ChunkPos, Integer> reporter) {
        tickEvidence(currentWorld, flagged, pos -> loaded.test(pos) ? currentWorld : null,
            pos -> new BaseConfidence.Result(scorer.applyAsInt(pos), ""), reporter);
    }

    void tickEvidence(Object currentWorld, Set<ChunkPos> flagged, Function<ChunkPos, Object> chunkIdentity,
                      Function<ChunkPos, BaseConfidence.Result> scorer, BiConsumer<ChunkPos, Integer> reporter) {
        if (world != currentWorld) {
            clear();
            world = currentWorld;
        }
        if (currentWorld == null || flagged.isEmpty()) {
            clear();
            return;
        }
        if (flagged.size() > MAX_CANDIDATES) { clear(); deferred = flagged.size(); return; }
        age++;
        cache.entrySet().removeIf(entry -> {
            ChunkPos pos = entry.getKey();
            if (flagged.contains(pos) && chunkIdentity.apply(pos) == entry.getValue().chunk()) return false;
            withdraw(pos);
            return true;
        });
        int loaded = 0;
        for (ChunkPos pos : flagged) {
            Object chunk = chunkIdentity.apply(pos);
            if (chunk == null) continue;
            loaded++;
            if (!cache.containsKey(pos) && cache.size() < MAX_SAMPLE_AGE_TICKS)
                cache.put(pos, new Sample(chunk, 0, 0));
        }
        ChunkPos candidate = null;
        for (var entry : cache.entrySet()) {
            if (entry.getValue().tick() == 0 || age - entry.getValue().tick() >= REFRESH_TICKS) {
                candidate = entry.getKey();
                break;
            }
        }
        if (candidate != null) {
            Sample previous = cache.get(candidate), refreshed = null;
            try {
                var result = scorer.apply(candidate);
                if (flagged.contains(candidate) && chunkIdentity.apply(candidate) == previous.chunk())
                    refreshed = new Sample(previous.chunk(), reportedScore(result), age);
            } finally {
                if (cache.get(candidate) == previous) {
                    cache.remove(candidate);
                    if (refreshed != null) cache.put(candidate, refreshed);
                    if (refreshed == null || refreshed.confidence() < previous.confidence()) withdraw(candidate);
                }
            }
        }
        boolean publish = ++ticks >= 20;
        if (publish) ticks = 0;
        for (var iterator = cache.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            Sample sample = entry.getValue();
            if (!flagged.contains(entry.getKey()) || chunkIdentity.apply(entry.getKey()) != sample.chunk()) {
                iterator.remove();
                withdraw(entry.getKey());
            } else if (sample.tick() == 0 || age - sample.tick() >= MAX_SAMPLE_AGE_TICKS) withdraw(entry.getKey());
            else if (publish) reporter.accept(entry.getKey(), sample.confidence());
        }
        deferred = loaded - cache.size();
    }

    private int reportedScore(BaseConfidence.Result result) {
        if (result == null || result.evidence() == null || result.evidence() == BaseConfidence.Evidence.UNKNOWN) return 0;
        int limit = result.evidence() == BaseConfidence.Evidence.NATURAL_COMPATIBLE ? BaseConfidence.NATURAL_COMPATIBLE_LIMIT : 100;
        return Math.clamp(result.score(), floor, limit);
    }

    private void withdraw(ChunkPos pos) { BaseTracker.withdraw(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, source); }

    /** Loaded candidates outside the bounded admission budget; no building conclusion is available for them. */
    public int deferredCandidates() { return deferred; }
    public int pendingCandidates() { return (int) cache.values().stream().filter(sample -> sample.tick() == 0).count(); }
    public String coverageSuffix() {
        int pending = pendingCandidates();
        return pending == 0 && deferred == 0 ? "" : " [score checks: " + pending + " pending, " + deferred + " unassessed]";
    }
    public void invalidate(ChunkPos pos) { cache.remove(pos); withdraw(pos); }

    public void clear() {
        if (!cache.isEmpty()) BaseTracker.removeSource(source);
        cache.clear();
        ticks = 0;
        age = 0;
        deferred = 0;
        world = null;
    }
}
