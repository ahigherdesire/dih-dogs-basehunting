package com.autism.seedcracker.spectre.palette;

import com.autism.seedcracker.spectre.chunks.seedscan.GeodePredictor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** One isolated generator owner; the render/client thread receives immutable, seed-free view data. */
public final class SeedGeodePreview implements AutoCloseable {
    public static final int MAX_RADIUS = 8;
    public static final int MAX_DETAIL_BLOCKS = 65_536;
    public record View(ChunkPos chunk, BlockPos origin, GeodePredictor.Bounds envelope,
                       GeodePredictor.Status status, GeodePredictor.Bounds amethystBounds,
                       Set<BlockPos> blocks, boolean limited) {
        public View { origin = origin.immutable(); blocks = blocks.stream().map(BlockPos::immutable).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    }
    public record Frame(List<View> views, int scanned, int total, int pending, String status) {
        public Frame { views = List.copyOf(views); }
        public static final Frame EMPTY = new Frame(List.of(), 0, 0, 0, "");
    }
    private record Area(ChunkPos center, int radius, boolean detail, List<Long> chunks) {}
    private record Publication(Area area, Frame frame) {}
    private record Sample(View view, boolean detailed) {}
    private final long seed;
    private final AtomicReference<Area> wanted;
    private final Thread worker;
    private volatile Publication published;
    private volatile boolean closed, stopped;

    public SeedGeodePreview(long seed, ChunkPos center, int radius, boolean detail) {
        this.seed = seed;
        Area area = area(center, radius, detail);
        wanted = new AtomicReference<>(area);
        published = new Publication(area, new Frame(List.of(), 0, area.chunks.size(), area.chunks.size(), "Preparing vanilla generator"));
        worker = new Thread(this::run, "Dogs seed geode preview");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }
    public long seed() { return seed; }
    public boolean stopped() { return stopped; }
    public Frame frame() {
        if (closed) return Frame.EMPTY;
        Publication value = published;
        Area current = wanted.get();
        if (value.area == current) return value.frame;
        List<View> retained = value.frame.views.stream().filter(view -> current.chunks.contains(view.chunk.pack())).toList();
        return new Frame(retained, 0, current.chunks.size(), current.chunks.size(), "Updating prediction area");
    }

    public void request(ChunkPos center, int radius, boolean detail) {
        if (closed || stopped) return;
        Area previous = wanted.get();
        if (previous.center.equals(center) && previous.radius == radius && previous.detail == detail) return;
        wanted.set(area(center, radius, detail));
        LockSupport.unpark(worker);
    }
    @Override public void close() {
        closed = true;
        published = new Publication(wanted.get(), Frame.EMPTY);
        worker.interrupt();
        LockSupport.unpark(worker);
    }

    private static Area area(ChunkPos center, int radius, boolean detail) {
        if (center == null || radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Invalid prediction area");
        List<Long> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            long x = (long) center.x() + dx, z = (long) center.z() + dz;
            if (Math.abs(x) <= GeodePredictor.MAX_CHUNK_COORD && Math.abs(z) <= GeodePredictor.MAX_CHUNK_COORD)
                chunks.add(ChunkPos.pack((int) x, (int) z));
        }
        chunks.sort(Comparator.<Long>comparingLong(key -> {
            long dx = (long) ChunkPos.getX(key) - center.x(), dz = (long) ChunkPos.getZ(key) - center.z();
            return dx * dx + dz * dz;
        }).thenComparingLong(Long::longValue));
        return new Area(center, radius, detail, List.copyOf(chunks));
    }

    private void run() {
        Map<Long, Sample> cache = new HashMap<>();
        try (var predictor = new GeodePredictor(seed)) {
            Area previous = null;
            while (!closed && !Thread.currentThread().isInterrupted()) {
                Area area = wanted.get();
                if (area != previous) {
                    Set<Long> keep = new HashSet<>(area.chunks);
                    cache.keySet().removeIf(key -> !keep.contains(key));
                    previous = area;
                    publish(area, cache);
                }
                Long next = null;
                for (long key : area.chunks) {
                    Sample sample = cache.get(key);
                    if (sample == null || area.detail && !sample.detailed) { next = key; break; }
                }
                if (next == null) { LockSupport.parkNanos(50_000_000L); continue; }
                long key = next;
                var candidate = predictor.candidate(ChunkPos.getX(key), ChunkPos.getZ(key));
                Sample sample;
                if (candidate.isEmpty()) sample = new Sample(null, true);
                else {
                    var value = candidate.get();
                    View view = new View(new ChunkPos(value.chunkX(), value.chunkZ()), value.origin(), value.possibleBounds(),
                        GeodePredictor.Status.PLACEMENT_ATTEMPT, null, Set.of(), false);
                    if (area.detail) {
                        cache.put(key, new Sample(view, false));
                        publish(area, cache);
                        var generated = predictor.reconstruct(value.chunkX(), value.chunkZ());
                        if (generated.isEmpty()) throw new IllegalStateException("Placement changed during reconstruction");
                        var result = generated.get();
                        Set<BlockPos> blocks = result.blocks().entrySet().stream()
                            .filter(entry -> AmethystScan.amethyst(entry.getValue()))
                            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                        GeodePredictor.Bounds bounds = bounds(blocks);
                        int retained = cache.values().stream().filter(entry -> entry.view != null).mapToInt(entry -> entry.view.blocks.size()).sum();
                        boolean limited = blocks.size() > MAX_DETAIL_BLOCKS - retained;
                        // ponytail: cap retained detail; omitted blocks retry after the origin leaves/re-enters the area or preview restarts.
                        view = new View(view.chunk, view.origin, view.envelope, result.status(), bounds,
                            limited ? Set.of() : blocks, limited);
                    }
                    sample = new Sample(view, area.detail);
                }
                if (closed || Thread.currentThread().isInterrupted()) break;
                Area current = wanted.get();
                if (current.chunks.contains(key)) cache.put(key, sample);
                publish(current, cache);
            }
        } catch (CancellationException cancelled) {
            if (!closed) published = new Publication(wanted.get(), new Frame(List.of(), 0, 0, 0, "Prediction cancelled; toggle seed preview to retry"));
        } catch (RuntimeException | LinkageError failure) {
            published = new Publication(wanted.get(), new Frame(List.of(), 0, 0, 0, "Prediction unavailable (" + failure.getClass().getSimpleName() + "); toggle to retry"));
            org.slf4j.LoggerFactory.getLogger("DogsTools/SeedGeodes").warn("Isolated geode preview stopped; no client world was modified", failure);
        } finally {
            cache.clear();
            stopped = true;
        }
    }

    private void publish(Area area, Map<Long, Sample> cache) {
        if (closed) return;
        List<View> views = new ArrayList<>();
        int scanned = 0, pending = 0;
        for (long key : area.chunks) {
            Sample sample = cache.get(key);
            if (sample == null) { pending++; continue; }
            scanned++;
            if (area.detail && !sample.detailed) pending++;
            if (sample.view != null) views.add(sample.view);
        }
        String status = area.chunks.isEmpty() ? "Outside supported world-border range" : pending == 0 ? "Ready" : "Generating";
        published = new Publication(area, new Frame(views, scanned, area.chunks.size(), pending, status));
    }

    static GeodePredictor.Bounds bounds(Set<BlockPos> blocks) {
        if (blocks.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks) {
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }
        return new GeodePredictor.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
