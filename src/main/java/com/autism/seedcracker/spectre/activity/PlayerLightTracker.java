// SPDX-License-Identifier: GPL-3.0-only
// Spectre PlayerBypassLightTracker/ChunkEntryGuard d5a7dd3, with conservative packet confirmation.
package com.autism.seedcracker.spectre.activity;

import com.autism.seedcracker.util.pure.PlayerFinderSignals;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

/** Explicit zero-filled skylight is a protocol clue, not proof of player activity. */
public final class PlayerLightTracker {
    public static final int MAX_CHUNKS = 4096;
    public static final long SETTLE_NANOS = 1_000_000_000L;
    public static final long MAX_AGE_NANOS = 60_000_000_000L;
    private static final long OBSERVATION_GAP_NANOS = 250_000_000L;
    private record Snapshot(int sections, int bottom, byte[] sky, boolean signal,
                            long first, long last, long confirmedAt, int observations) {}
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>();
    private final LinkedHashSet<Long> protectedChunks = new LinkedHashSet<>();
    private boolean entered;
    private long playerChunk;

    public void accept(int x, int z, int sections, int bottom, BitSet present, BitSet empty,
                       List<byte[]> updates, boolean belowZero, boolean baseline, long now) {
        long key = QuantumRules.key(x, z);
        if (!valid(sections, present, empty, updates)) { snapshots.remove(key); return; }
        Snapshot old = snapshots.get(key);
        boolean compatible = old != null && old.sections == sections && old.bottom == bottom;
        byte[] sky = compatible && !baseline ? old.sky.clone() : new byte[sections + 2];
        PlayerFinderSignals.apply(sky, present, empty, updates);
        boolean signal = coherentSignal(sky, bottom) && !belowZero && !protectedChunks.contains(key);
        boolean continuous = signal && compatible && old.signal && now - old.last >= 0 && now - old.last <= MAX_AGE_NANOS;
        long first = continuous ? old.first : now;
        long confirmedAt = continuous ? old.confirmedAt : now;
        int observations = signal ? continuous ? old.observations : 1 : 0;
        boolean darkUpdated = false;
        for (int bit = present.nextSetBit(1); bit > 0 && bit < sky.length - 1; bit = present.nextSetBit(bit + 1)) {
            darkUpdated |= sky[bit] == 1 && (bottom + bit - 1) * 16 + 15 < 62;
        }
        if (continuous && now - confirmedAt >= OBSERVATION_GAP_NANOS && darkUpdated) {
            observations = Math.min(4, observations + 1);
            confirmedAt = now;
        }
        snapshots.put(key, new Snapshot(sections, bottom, sky, signal, first, now, confirmedAt, observations));
        while (snapshots.size() > MAX_CHUNKS) snapshots.pollFirstEntry();
    }

    private static boolean valid(int sections, BitSet present, BitSet empty, List<byte[]> updates) {
        if (sections <= 0 || sections > 256 || present == null || empty == null || updates == null
            || present.length() > sections + 2 || empty.length() > sections + 2
            || present.intersects(empty) || present.cardinality() != updates.size()) return false;
        return updates.stream().allMatch(bytes -> bytes != null && bytes.length == 2048);
    }

    private static boolean coherentSignal(byte[] sky, int bottom) {
        int run = 0;
        boolean dark = false, lit = false;
        for (int index = 1; index < sky.length - 1; index++) {
            lit |= sky[index] == 2;
            int topY = (bottom + index - 1) * 16 + 15;
            run = topY < 62 && sky[index] == 1 ? run + 1 : 0;
            dark |= run >= 2;
        }
        return dark && lit;
    }

    public Set<Long> flagged(long now) {
        Set<Long> result = new LinkedHashSet<>();
        for (var entry : snapshots.entrySet()) {
            Snapshot value = entry.getValue();
            if (value.signal && value.observations >= 2 && now - value.first >= SETTLE_NANOS
                && fresh(value, now) && now - value.confirmedAt <= MAX_AGE_NANOS
                && !protectedChunks.contains(entry.getKey()) && unusual(entry.getKey(), now)) {
                result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    private boolean unusual(long key, long now) {
        int x = QuantumRules.x(key), z = QuantumRules.z(key), observed = 0, anomalous = 0;
        // ponytail: a 5x5 local baseline rejects servers encoding ordinary dark chunks this way; not a base classifier.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Snapshot neighbour = snapshots.get(QuantumRules.key(x + dx, z + dz));
            if (neighbour == null || !fresh(neighbour, now)) continue;
            int known = 0;
            for (int i = 1; i < neighbour.sky.length - 1; i++) if (neighbour.sky[i] != 0) known++;
            if (known < 3) continue;
            observed++;
            if (coherentSignal(neighbour.sky, neighbour.bottom)) anomalous++;
        }
        return observed >= 5 && anomalous * 2 < observed;
    }

    private static boolean fresh(Snapshot snapshot, long now) { return now - snapshot.last >= 0 && now - snapshot.last <= MAX_AGE_NANOS; }

    public void updateEntry(int x, int z, LongPredicate loaded) {
        long current = QuantumRules.key(x, z);
        if (!loaded.test(current) || entered && current == playerChunk) return;
        entered = true;
        playerChunk = current;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            protectedChunks.add(QuantumRules.key(x + dx, z + dz));
        }
        while (protectedChunks.size() > MAX_CHUNKS) protectedChunks.removeFirst();
    }

    public void retain(LongPredicate keep) { snapshots.keySet().removeIf(key -> !keep.test(key)); }
    public void forget(long key) { snapshots.remove(key); }
    public int size() { return snapshots.size(); }
    public void clear() { snapshots.clear(); protectedChunks.clear(); entered = false; }
}
