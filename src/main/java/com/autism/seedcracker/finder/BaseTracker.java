package com.autism.seedcracker.finder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared base-detection registry for the Base Tracker HUD.
 *
 * Finder modules report their flagged chunks here (position + confidence + which finder raised
 * it) and the {@code BaseTrackerHud} reads a merged, distance-sorted view. Entries expire quickly
 * (finders re-report every tick), so when a finder un-flags a chunk or is disabled its entry
 * disappears on its own.
 */
public final class BaseTracker {

    // Finders report on a ~1s cadence (see StashFinder's reportTicks), so the TTL must outlive two
    // report intervals or every entry expires between reports and the HUD flickers.
    private static final long TTL_MS = 2_500L;

    public record Entry(int blockX, int blockZ, int confidence, String source, long lastMs) {}

    private record Key(int chunkX, int chunkZ, String source) {}
    private static final Map<Key, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    private static volatile ClusterCache cachedClusters;
    private static long lastPruneMs = Long.MIN_VALUE;
    static final int MAX_REPORTS = 8192;
    private record Cluster(int x, int z, int score, String sources) {}
    private record ClusterCache(long revision, List<Cluster> clusters) {}

    private BaseTracker() {}

    /** Report a flagged chunk. Call every tick while the chunk is flagged. */
    public static void report(int blockX, int blockZ, int confidence, String source) {
        if (storeReport(blockX, blockZ, confidence, source, System.currentTimeMillis())) BaseAlerts.onReport(blockX, blockZ);
    }

    static synchronized boolean storeReport(int blockX, int blockZ, int confidence, String source, long now) {
        if (source == null || source.isBlank()) return false;
        if (confidence <= 0) { withdraw(blockX, blockZ, source); return false; }
        if (lastPruneMs == Long.MIN_VALUE || now < lastPruneMs || now - lastPruneMs >= 1000) prune(now);
        String name = source.trim();
        Key key = new Key(blockX >> 4, blockZ >> 4, name);
        // ponytail: bound the live HUD ledger; existing sources can refresh while new reports wait for expiry at capacity.
        if (ENTRIES.size() >= MAX_REPORTS && !ENTRIES.containsKey(key)) return false;
        Entry next = new Entry(blockX, blockZ, Math.min(100, confidence), name, now);
        Entry previous = ENTRIES.put(key, next);
        if (!next.equals(previous)) REVISION.incrementAndGet();
        return true;
    }

    private static void prune(long now) {
        if (ENTRIES.values().removeIf(entry -> now < entry.lastMs() || now - entry.lastMs() >= TTL_MS)) REVISION.incrementAndGet();
        lastPruneMs = now;
    }

    static int retainedEntries() { return ENTRIES.size(); }

    public static void withdraw(int blockX, int blockZ, String source) {
        if (source == null) return;
        if (ENTRIES.remove(new Key(blockX >> 4, blockZ >> 4, source.trim())) != null) REVISION.incrementAndGet();
    }

    public static void removeSource(String source) {
        if (source == null) return;
        String name = source.trim();
        if (ENTRIES.keySet().removeIf(key -> key.source().equals(name))) REVISION.incrementAndGet();
    }

    /** A cluster of nearby finder reports fused into one row. */
    public record Fused(int blockX, int blockZ, int confidence, String sources, double distSq) {}

    /** Cluster radius: entries within this many blocks merge into one fused row. */
    static final int CLUSTER_RADIUS_BLOCKS = 48;

    /**
    * Live entries clustered by proximity (3-chunk radius), retaining the strongest score
    * and its member finder names, sorted nearest-first.
     */
    public static List<Fused> fusedNearest(double playerX, double playerZ, int limit) {
        return fusedNearestAt(playerX, playerZ, limit, System.currentTimeMillis());
    }

    public static Fused nearestCandidate(double playerX, double playerZ, int minimumScore, double radius) {
        return nearestCandidateAt(playerX, playerZ, minimumScore, radius, System.currentTimeMillis());
    }

    static Fused nearestCandidateAt(double playerX, double playerZ, int minimumScore, double radius, long now) {
        if (!Double.isFinite(radius) || radius < 0) return null;
        for (Fused candidate : fusedNearestAt(playerX, playerZ, Integer.MAX_VALUE, now)) {
            if (candidate.distSq() > radius * radius) break;
            if (candidate.confidence() >= minimumScore) return candidate;
        }
        return null;
    }

    static List<Fused> fusedNearestAt(double playerX, double playerZ, int limit, long now) {
        if (limit <= 0 || !Double.isFinite(playerX) || !Double.isFinite(playerZ)) return List.of();
        prune(now);
        long revision = REVISION.get();
        ClusterCache cached = cachedClusters;
        if (cached == null || cached.revision() != revision) {
            cached = new ClusterCache(revision, clusters());
            cachedClusters = cached;
        }
        List<Fused> out = new java.util.ArrayList<>(cached.clusters().size());
        for (Cluster cluster : cached.clusters()) {
            double dx = cluster.x() - playerX, dz = cluster.z() - playerZ;
            out.add(new Fused(cluster.x(), cluster.z(), cluster.score(), cluster.sources(), dx * dx + dz * dz));
        }
        out.sort(java.util.Comparator.comparingDouble(Fused::distSq));
        return List.copyOf(out.size() > limit ? out.subList(0, limit) : out);
    }

    private static List<Cluster> clusters() {
        List<Entry> live = ENTRIES.values().stream()
            .sorted(java.util.Comparator.comparingInt(Entry::blockX).thenComparingInt(Entry::blockZ).thenComparing(Entry::source)).toList();
        Map<Long, List<Integer>> cells = new java.util.HashMap<>();
        for (int i = 0; i < live.size(); i++) {
            Entry entry = live.get(i);
            cells.computeIfAbsent(cellKey(Math.floorDiv(entry.blockX(), CLUSTER_RADIUS_BLOCKS),
                Math.floorDiv(entry.blockZ(), CLUSTER_RADIUS_BLOCKS)), ignored -> new java.util.ArrayList<>()).add(i);
        }
        List<Cluster> result = new java.util.ArrayList<>();
        boolean[] used = new boolean[live.size()];
        for (int i = 0; i < live.size(); i++) {
            if (used[i]) continue;
            Entry seed = live.get(i), strongest = seed;
            used[i] = true;
            int cellX = Math.floorDiv(seed.blockX(), CLUSTER_RADIUS_BLOCKS), cellZ = Math.floorDiv(seed.blockZ(), CLUSTER_RADIUS_BLOCKS);
            List<Integer> nearby = new java.util.ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                var indices = cells.get(cellKey(cellX + dx, cellZ + dz));
                if (indices != null) for (int index : indices) if (index > i && !used[index]) nearby.add(index);
            }
            nearby.sort(Integer::compare);
            var sources = new java.util.LinkedHashSet<String>();
            sources.add(seed.source());
            for (int index : nearby) {
                Entry entry = live.get(index);
                long dx = (long) entry.blockX() - seed.blockX(), dz = (long) entry.blockZ() - seed.blockZ();
                if (dx * dx + dz * dz > (long) CLUSTER_RADIUS_BLOCKS * CLUSTER_RADIUS_BLOCKS) continue;
                used[index] = true;
                sources.add(entry.source());
                if (entry.confidence() > strongest.confidence()) strongest = entry;
            }
            result.add(new Cluster(strongest.blockX(), strongest.blockZ(), strongest.confidence(), String.join("+", sources)));
        }
        return List.copyOf(result);
    }

    private static long cellKey(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }

    /** Snapshot of live entries, sorted nearest-first to the given point. */
    public static List<Entry> nearest(double playerX, double playerZ, int limit) {
        return nearestAt(playerX, playerZ, limit, System.currentTimeMillis());
    }

    static List<Entry> nearestAt(double playerX, double playerZ, int limit, long now) {
        if (limit <= 0 || !Double.isFinite(playerX) || !Double.isFinite(playerZ)) return List.of();
        prune(now);
        return ENTRIES.values().stream()
            .sorted(java.util.Comparator.comparingDouble((Entry entry) -> distSq(entry, playerX, playerZ))
                .thenComparingInt(Entry::blockX).thenComparingInt(Entry::blockZ).thenComparing(Entry::source))
            .limit(limit)
            .toList();
    }

    private static double distSq(Entry e, double px, double pz) {
        double dx = e.blockX() - px;
        double dz = e.blockZ() - pz;
        return dx * dx + dz * dz;
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        REVISION.incrementAndGet();
        cachedClusters = null;
        lastPruneMs = Long.MIN_VALUE;
    }
}
