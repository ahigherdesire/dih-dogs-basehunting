// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.chunks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongPredicate;

/** Different cells in one light packet are one event, not independent corroboration. */
public final class PrimeConfirmation {
    public static final long WINDOW = 10_000_000_000L;
    public static final long LIFETIME = 60_000_000_000L;
    private static final long GAP = 250_000_000L;
    private record Evidence(long event, long first, long last, int count) {}
    private final LinkedHashMap<Long, Evidence> evidence = new LinkedHashMap<>();

    public boolean observe(long chunk, long event, long now) {
        Evidence old = evidence.get(chunk);
        if (old != null && event <= old.event) return false;
        if (old == null || now - old.last < 0 || now - old.last > WINDOW) {
            evidence.put(chunk, new Evidence(event, now, now, 1));
        } else if (now - old.last >= GAP) {
            evidence.put(chunk, new Evidence(event, old.first, now, Math.min(2, old.count + 1)));
        } else {
            evidence.put(chunk, new Evidence(event, old.first, old.last, old.count));
        }
        while (evidence.size() > 256) evidence.pollFirstEntry();
        Evidence current = evidence.get(chunk);
        return current != null && current.count >= 2 && (old == null || old.count < 2);
    }

    public Set<Long> flagged(long now) {
        evidence.values().removeIf(value -> now - value.last < 0 || now - value.last > LIFETIME);
        Set<Long> result = new LinkedHashSet<>();
        evidence.forEach((key, value) -> { if (value.count >= 2) result.add(key); });
        return Set.copyOf(result);
    }
    public void retain(LongPredicate keep) { evidence.keySet().removeIf(key -> !keep.test(key)); }
    public void forget(long key) { evidence.remove(key); }
    public void clear() { evidence.clear(); }
}
