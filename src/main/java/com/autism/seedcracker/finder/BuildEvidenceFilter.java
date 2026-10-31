package com.autism.seedcracker.finder;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/** Client-thread bounded corroboration for ambiguous light signals; never treats darkness or lava as a building. */
public final class BuildEvidenceFilter {
    private record Sample(Object chunk, boolean supported, long at) {}
    private final Map<ChunkPos, Sample> samples = new LinkedHashMap<>();
    private Object world;
    private long age;
    private int deferred;
    private static final int REFRESH_TICKS = 20;
    private static final int MAX_CANDIDATES = 4096;
    // ponytail: one scan/tick covers at most 200 chunks within 200 ticks; raise the scan budget, not stale trust, to cover more.
    private static final int MAX_SAMPLE_AGE_TICKS = 200;

    public Set<ChunkPos> filter(Minecraft mc, Set<ChunkPos> candidates, boolean requireEvidence) {
        if (mc.level == null || mc.player == null) { clear(); return Set.of(); }
        if (!requireEvidence) { clear(); return Set.copyOf(candidates); }
        var level = mc.level;
        return filter(level, candidates, pos -> level.getChunkSource().getChunkNow(pos.x(), pos.z()),
            pos -> BaseConfidence.hasBuildEvidence(level.getChunkSource().getChunkNow(pos.x(), pos.z())));
    }

    Set<ChunkPos> filter(Object currentWorld, Set<ChunkPos> candidates,
                        Function<ChunkPos, Object> chunkIdentity, Predicate<ChunkPos> supported) {
        if (world != currentWorld) { clear(); world = currentWorld; }
        if (currentWorld == null || candidates.isEmpty()) { clear(); return Set.of(); }
        if (candidates.size() > MAX_CANDIDATES) { clear(); deferred = candidates.size(); return Set.of(); }
        age++;
        samples.entrySet().removeIf(entry -> !candidates.contains(entry.getKey())
            || chunkIdentity.apply(entry.getKey()) != entry.getValue().chunk());
        int loaded = 0;
        for (ChunkPos pos : candidates) {
            Object chunk = chunkIdentity.apply(pos);
            if (chunk == null) continue;
            loaded++;
            if (!samples.containsKey(pos) && samples.size() < MAX_SAMPLE_AGE_TICKS)
                samples.put(pos, new Sample(chunk, false, 0));
        }
        ChunkPos next = null;
        for (var entry : samples.entrySet()) {
            if (entry.getValue().at() == 0 || age - entry.getValue().at() >= REFRESH_TICKS) {
                next = entry.getKey();
                break;
            }
        }
        if (next != null) {
            Sample previous = samples.get(next), refreshed = null;
            try {
                boolean evidence = supported.test(next);
                if (candidates.contains(next) && chunkIdentity.apply(next) == previous.chunk())
                    refreshed = new Sample(previous.chunk(), evidence, age);
            } finally {
                if (samples.get(next) == previous) {
                    samples.remove(next);
                    if (refreshed != null) samples.put(next, refreshed);
                }
            }
        }
        Set<ChunkPos> result = new HashSet<>();
        for (var iterator = samples.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            Sample sample = entry.getValue();
            if (!candidates.contains(entry.getKey()) || chunkIdentity.apply(entry.getKey()) != sample.chunk()) {
                iterator.remove();
                continue;
            }
            if (sample.supported() && age - sample.at() < MAX_SAMPLE_AGE_TICKS) result.add(entry.getKey());
        }
        deferred = loaded - samples.size();
        return Set.copyOf(result);
    }

    /** Loaded candidates not admitted by the last bounded pass; they are unassessed, not negative evidence. */
    public int deferredCandidates() { return deferred; }
    public int pendingCandidates() { return (int) samples.values().stream().filter(sample -> sample.at() == 0).count(); }
    public String coverageSuffix() {
        int pending = pendingCandidates();
        return pending == 0 && deferred == 0 ? "" : " [build checks: " + pending + " pending, " + deferred + " unassessed]";
    }
    public void invalidate(ChunkPos pos) { samples.remove(pos); }
    public void clear() { samples.clear(); world = null; age = 0; deferred = 0; }
}
