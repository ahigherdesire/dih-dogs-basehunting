// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Client-thread-only evidence; light invalidation never erases observed formations. */
public final class AmethystCache {
    public static final int MAX_CHUNKS = 512;
    public static final int MAX_MARKERS = 131072;
    private final Map<ChunkPos, List<Integer>> palettes = new LinkedHashMap<>();
    private final Map<ChunkPos, AmethystScan.Result> results = new LinkedHashMap<>();
    private final Set<ChunkPos> dirty = new LinkedHashSet<>();
    private int markers;
    private boolean geometryDirty, hintsDirty, limited;
    private int notifications;
    private AmethystGeometry.Snapshot geometry = AmethystGeometry.EMPTY;
    private List<AABB> hintBoxes = List.of();

    public List<Integer> palette(ChunkPos pos) { return palettes.getOrDefault(pos, List.of()); }
    public Map<ChunkPos, AmethystScan.Result> results() { return Collections.unmodifiableMap(results); }
    public boolean limited() { return limited; }

    public boolean packet(ChunkPos pos, PaletteScan.Result result) {
        palettes.remove(pos);
        if (result.valid() && !result.hiddenSections().isEmpty()) palettes.put(pos, result.hiddenSections());
        // A full chunk replaces block data; only light-only updates may preserve old observed geometry.
        boolean changed = remove(pos);
        while (palettes.size() > MAX_CHUNKS) {
            ChunkPos oldest = palettes.keySet().iterator().next();
            palettes.remove(oldest);
            changed |= invalidateAround(oldest);
        }
        return invalidateAround(pos) || changed;
    }

    public boolean block(BlockPos block) { return block(block, false); }

    public boolean block(BlockPos block, boolean amethyst) {
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        List<Integer> remaining = palette(pos).stream().filter(y -> y != (block.getY() >> 4)).toList();
        if (remaining.isEmpty()) palettes.remove(pos);
        else palettes.put(pos, remaining);
        boolean changed = invalidateAround(pos);
        AmethystScan.Result previous = results.get(pos);
        if (previous != null && previous.blocks().contains(block) != amethyst) {
            var blocks = new LinkedHashSet<>(previous.blocks());
            if (amethyst) blocks.add(block.immutable()); else blocks.remove(block);
            store(pos, new AmethystScan.Result(blocks, Set.of()));
            changed = true;
        }
        return changed;
    }

    public boolean invalidateAround(ChunkPos pos) {
        boolean changed = false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            ChunkPos neighbour = new ChunkPos(pos.x() + dx, pos.z() + dz);
            AmethystScan.Result old = results.get(neighbour);
            if (old != null && !old.hints().isEmpty()) {
                store(neighbour, new AmethystScan.Result(old.blocks(), Set.of()));
                changed = true;
            }
            dirty.add(neighbour);
        }
        while (dirty.size() > MAX_CHUNKS) dirty.remove(dirty.iterator().next());
        return changed;
    }

    public ChunkPos pollDirty() {
        if (dirty.isEmpty()) return null;
        ChunkPos pos = dirty.iterator().next();
        dirty.remove(pos);
        return pos;
    }

    public void store(ChunkPos pos, AmethystScan.Result result) {
        if (result.size() > MAX_MARKERS || result.blocks().size() > AmethystScan.MAX_BLOCKS_PER_CHUNK) {
            remove(pos);
            limited = true;
            return; // Reject the whole over-budget chunk and invalidate any cached bounds.
        }
        AmethystScan.Result old = results.get(pos);
        if (result.equals(old)) return;
        Set<BlockPos> before = old == null ? Set.of() : old.blocks();
        Set<Integer> oldHints = old == null ? Set.of() : old.hints();
        geometryDirty |= !before.equals(result.blocks());
        hintsDirty |= !oldHints.equals(result.hints());
        if (old != null) markers -= old.size();
        results.remove(pos);
        if (result.size() > 0) { results.put(pos, result); markers += result.size(); }
        while (results.size() > MAX_CHUNKS || markers > MAX_MARKERS) {
            remove(results.keySet().iterator().next());
            limited = true;
        }
    }

    private boolean remove(ChunkPos pos) {
        AmethystScan.Result old = results.remove(pos);
        if (old == null) return false;
        markers -= old.size();
        geometryDirty |= !old.blocks().isEmpty();
        hintsDirty |= !old.hints().isEmpty();
        return true;
    }

    public void rejectOversized(ChunkPos pos) { remove(pos); limited = true; }

    public boolean unload(ChunkPos pos) {
        palettes.remove(pos);
        boolean changed = remove(pos);
        changed |= invalidateAround(pos);
        dirty.remove(pos);
        return changed;
    }

    public void prune(Predicate<ChunkPos> keep) {
        palettes.keySet().removeIf(pos -> !keep.test(pos));
        for (ChunkPos pos : List.copyOf(results.keySet())) if (!keep.test(pos)) remove(pos);
        dirty.removeIf(pos -> !keep.test(pos));
    }

    public AmethystGeometry.Snapshot geometry() {
        if (geometryDirty) {
            Set<BlockPos> blocks = new LinkedHashSet<>();
            for (var result : results.values()) blocks.addAll(result.blocks());
            geometry = AmethystGeometry.build(blocks, geometry.observed());
            notifications += geometry.newFormations();
            geometryDirty = false;
        }
        return geometry;
    }

    public List<AABB> hints(boolean show) {
        if (!show) return List.of();
        if (hintsDirty) {
            var boxes = new ArrayList<AABB>();
            results.forEach((pos, result) -> {
                for (int y : result.hints()) boxes.add(AmethystScan.hintBox(pos, y));
            });
            hintBoxes = List.copyOf(boxes);
            hintsDirty = false;
        }
        return hintBoxes;
    }

    public int takeNotifications() { geometry(); int count = notifications; notifications = 0; return count; }

    public void clearResults() {
        results.clear(); dirty.clear(); markers = 0; notifications = 0;
        geometry = AmethystGeometry.EMPTY; hintBoxes = List.of();
        geometryDirty = hintsDirty = limited = false;
    }
    public void clear() { palettes.clear(); clearResults(); }
}
