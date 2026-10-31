package com.autism.seedcracker.corz;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.autism.seedcracker.corz.CorzGeodeMorphology.Analysis;
import com.autism.seedcracker.corz.CorzGeodeMorphology.Descriptor;
import com.autism.seedcracker.corz.CorzGeodeMorphology.Key;
import com.autism.seedcracker.corz.CorzGeodeMorphology.Snapshot;

/** Client-thread-only, bounded context; never feeds boxes, alerts, reports or confidence scores. */
public final class CorzGeodeContextCache {
    public static final int MAX_CONTEXTS = 128, MAX_PENDING = 2;
    public static final int EVALUATION_CELLS_PER_TICK = 4096;
    private final int maxContexts, maxPending;
    private final Map<Key, Descriptor> descriptors = new LinkedHashMap<>();
    private final Map<Key, Analysis> pending = new LinkedHashMap<>();
    private boolean limited;

    public CorzGeodeContextCache() { this(MAX_CONTEXTS, MAX_PENDING); }
    CorzGeodeContextCache(int maxContexts, int maxPending) {
        if (maxContexts < 1 || maxContexts > MAX_CONTEXTS || maxPending < 1 || maxPending > MAX_PENDING)
            throw new IllegalArgumentException("Invalid context limits");
        this.maxContexts = maxContexts; this.maxPending = maxPending;
    }
    public void offer(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        Key key = snapshot.key();
        Analysis active = pending.get(key);
        if (active != null && active.matches(snapshot)) return; // An unchanged rescan must not restart bounded work.
        descriptors.remove(key);
        pending.remove(key);
        pending.put(key, new Analysis(snapshot));
        while (pending.size() > maxPending) {
            // Preserve the active head: otherwise a busy scan can evict it forever before completion.
            var keys = pending.keySet().iterator();
            keys.next();
            pending.remove(keys.next());
            limited = true;
        }
    }
    public boolean hasPending() { return !pending.isEmpty(); }
    public int pendingCount() { return pending.size(); }
    public int size() { return descriptors.size(); }
    public boolean limited() { return limited; }
    public Descriptor get(Key key) { return descriptors.get(key); }

    /** Uses immutable snapshots only. World access belongs exclusively to the section sampler. */
    public int advance(int cellBudget, long deadlineNanos) {
        int used = 0;
        while (used < Math.max(0, cellBudget) && !pending.isEmpty() && System.nanoTime() < deadlineNanos) {
            Key key = pending.keySet().iterator().next();
            Analysis work = pending.get(key);
            int step = work.advance(cellBudget - used, deadlineNanos);
            used += step;
            if (work.complete()) {
                pending.remove(key);
                Descriptor descriptor = work.result();
                if (descriptor.amethystCells() != 0) {
                    descriptors.put(key, descriptor);
                    while (descriptors.size() > maxContexts) {
                        descriptors.remove(descriptors.keySet().iterator().next());
                        limited = true;
                    }
                }
            }
            if (step == 0) break;
        }
        return used;
    }
    /** Full replacements, block changes and unloads invalidate both sides of the sampled halo. */
    public void invalidateAround(Key changed) {
        descriptors.keySet().removeIf(changed::touches);
        pending.keySet().removeIf(changed::touches);
    }
    public void prune(Predicate<Key> keep) {
        Objects.requireNonNull(keep);
        descriptors.keySet().removeIf(key -> !keep.test(key));
        pending.keySet().removeIf(key -> !keep.test(key));
    }
    public void clear() { descriptors.clear(); pending.clear(); limited = false; }

    /** Native labels describing measurements, not recovered donor UI strings or a detector verdict. */
    public String summary(Key center) {
        Descriptor nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Descriptor candidate : descriptors.values()) {
            double dx = (double) candidate.key().x() - center.x(), dz = (double) candidate.key().z() - center.z();
            double d = dx * dx + dz * dz;
            if (d < distance) { distance = d; nearest = candidate; }
        }
        String suffix = limited ? " / morphology coverage limited" : "";
        if (nearest == null) return " / morphology context: " + pending.size() + " pending, no measured amethyst" + suffix;
        Descriptor d = nearest;
        return String.format(Locale.ROOT,
            " / morphology chunk %d,%d: cells %d (budding %d), span %d, density %.4f, radius %.2f, calcite/basalt %d/%d, air/solid/unknown edges %d/%d/%d, coverage %d/%d%s%s",
            d.key().x(), d.key().z(), d.amethystCells(), d.buddingCells(), d.verticalSpan(), d.density(), d.meanRadius(),
            d.calciteCells(), d.basaltCells(), d.airEdges(), d.solidEdges(), d.unknownEdges(), d.knownCells(),
            CorzGeodeMorphology.INTERIOR_CELLS, d.partial() ? " (partial; observed only)" : " (sampled context only)", suffix);
    }
}
