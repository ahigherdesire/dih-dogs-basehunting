// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Face-connected received amethyst, not inferred geodes or player activity. */
public final class AmethystGeometry {
    public static final int MAX_OUTLINES = 4096;
    public static final int MAX_BOXES = 4096;
    public static final boolean DEFAULT_SHOW_HINTS = false;
    public static final boolean DEFAULT_BLOCK_OUTLINES = false;
    private static final Direction[] SIDES = Direction.values();

    public record Snapshot(List<AABB> boxes, Set<BlockPos> outlines, Set<BlockPos> anchors,
                           Set<BlockPos> observed, int formations, int newFormations) {}
    public static final Snapshot EMPTY = new Snapshot(List.of(), Set.of(), Set.of(), Set.of(), 0, 0);

    private AmethystGeometry() {}

    public static Snapshot build(Set<BlockPos> observed, Set<BlockPos> previous) {
        var remaining = new HashSet<>(observed);
        var queue = new ArrayDeque<BlockPos>();
        var boxes = new ArrayList<AABB>();
        var anchors = new LinkedHashSet<BlockPos>();
        var outlines = new LinkedHashSet<BlockPos>();
        int formations = 0, fresh = 0;
        // ponytail: linear in retained evidence, capped by AmethystCache; incremental components can replace this if changes become frequent.
        for (BlockPos start : observed) {
            if (!remaining.remove(start)) continue;
            queue.add(start);
            int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
            int maxX = minX, maxY = minY, maxZ = minZ;
            boolean existed = false;
            while (!queue.isEmpty()) {
                BlockPos pos = queue.removeFirst();
                existed |= previous.contains(pos);
                minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
                if (outlines.size() < MAX_OUTLINES) outlines.add(pos);
                for (Direction side : SIDES) {
                    BlockPos next = pos.relative(side);
                    if (remaining.remove(next)) queue.addLast(next);
                }
            }
            formations++;
            if (!existed) fresh++;
            if (boxes.size() < MAX_BOXES) {
                boxes.add(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
                anchors.add(start);
            }
        }
        return new Snapshot(List.copyOf(boxes), Collections.unmodifiableSet(outlines), Collections.unmodifiableSet(anchors),
            Collections.unmodifiableSet(new LinkedHashSet<>(observed)), formations, fresh);
    }
}
