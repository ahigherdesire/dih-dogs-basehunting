// SPDX-License-Identifier: GPL-3.0-only
// Anubis 0.9.9.3 SimulationSignals (4ldenz): adapted fresh-entity, recent-self and ignored-block rules.
package com.autism.seedcracker.spectre.activity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/** Server-applied position evidence only; rendered movement never advances a baseline. */
public final class AnubisSimulationSignals {
    public static final int MAX_ENTITIES = 4096;
    public static final long FRESH_NANOS = 2_000_000_000L;
    public static final long ORIGIN_NANOS = 60_000_000_000L;
    private static final Set<String> IGNORED_BLOCKS = Set.of(
        "pointed_dripstone", "cave_vines", "cave_vines_plant", "sweet_berry_bush", "beehive", "bee_nest",
        "vine", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant");
    private final Map<Integer, Sample> entities = new HashMap<>();

    private static final class Sample {
        final UUID identity;
        final long first;
        long seen, chunk;
        Long inside;
        double x, y, z;
        boolean positioned;
        Sample(UUID identity, long chunk, long now) {
            this.identity = identity;
            this.chunk = chunk;
            first = seen = now;
        }
    }

    public static boolean ignoredBlock(String id) {
        return id != null && id.startsWith("minecraft:") && IGNORED_BLOCKS.contains(id.substring("minecraft:".length()));
    }

    public boolean observe(int id, UUID identity, long chunk, double x, double y, double z,
                           boolean insideOwnRange, boolean appliedPosition, long now) {
        if (identity == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            entities.remove(id);
            return false;
        }
        Sample sample = entities.get(id);
        if (sample == null || !identity.equals(sample.identity) || now < sample.seen) {
            if (sample == null && entities.size() >= MAX_ENTITIES) return false;
            sample = new Sample(identity, chunk, now);
            entities.put(id, sample);
        }
        sample.seen = now;
        if (insideOwnRange) sample.inside = now;
        if (!appliedPosition) return false;
        double dx = x - sample.x, dy = y - sample.y, dz = z - sample.z;
        double moved = dx * dx + dy * dy + dz * dz;
        boolean signal = sample.positioned && now - sample.first >= FRESH_NANOS
            && (sample.inside == null || now - sample.inside >= ORIGIN_NANOS)
            && moved >= 0.0001 && moved < 256.0;
        sample.chunk = chunk;
        sample.x = x;
        sample.y = y;
        sample.z = z;
        sample.positioned = true;
        return signal;
    }

    public Long chunk(int id) { Sample sample = entities.get(id); return sample == null ? null : sample.chunk; }
    public void forget(int id) { entities.remove(id); }
    public void forgetChunk(long chunk) { entities.values().removeIf(sample -> sample.chunk == chunk); }
    public void retain(IntPredicate live) { entities.keySet().removeIf(id -> !live.test(id)); }
    public void clear() { entities.clear(); }
    public int size() { return entities.size(); }
}
