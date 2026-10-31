// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmethystGeometryTest {
    private static Set<BlockPos> cube(int min, int max) {
        var result = new HashSet<BlockPos>();
        for (int x = min; x <= max; x++) for (int y = min; y <= max; y++) for (int z = min; z <= max; z++)
            result.add(new BlockPos(x, y, z));
        return result;
    }

    @Test void fullShellAndDenseFormationHaveTightBoundsBeyondBothOldCaps() {
        Set<BlockPos> dense = cube(-16, 15);
        var geometry = AmethystGeometry.build(dense, Set.of());
        assertEquals(32768, geometry.observed().size());
        assertEquals(1, geometry.formations());
        assertEquals(List.of(new AABB(-16, -16, -16, 16, 16, 16)), geometry.boxes());
        assertEquals(AmethystGeometry.MAX_OUTLINES, geometry.outlines().size());
        assertTrue(dense.containsAll(geometry.outlines()));
        var shell = new HashSet<>(dense);
        shell.removeAll(cube(-15, 14));
        var shellGeometry = AmethystGeometry.build(shell, Set.of());
        assertEquals(1, shellGeometry.formations());
        assertEquals(geometry.boxes(), shellGeometry.boxes());
    }

    @Test void disjointAndOnlyDiagonallyTouchingBlocksStaySeparate() {
        var cells = new HashSet<>(cube(0, 2));
        cells.addAll(cube(10, 12));
        cells.add(new BlockPos(3, 3, 3));
        var geometry = AmethystGeometry.build(cells, Set.of());
        assertEquals(3, geometry.formations());
        assertTrue(geometry.boxes().contains(new AABB(0, 0, 0, 3, 3, 3)));
        assertTrue(geometry.boxes().contains(new AABB(10, 10, 10, 13, 13, 13)));
        assertTrue(geometry.boxes().contains(new AABB(3, 3, 3, 4, 4, 4)));
    }

    @Test void genuineCrossChunkAndVerticalSectionConnectionsMergeAtNegativeCoordinates() {
        var cache = new AmethystCache();
        Set<BlockPos> left = Set.of(new BlockPos(-17, -17, -1), new BlockPos(-17, -16, -1));
        Set<BlockPos> right = Set.of(new BlockPos(-16, -16, -1), new BlockPos(-15, -16, -1));
        cache.store(new ChunkPos(-2, -1), new AmethystScan.Result(left, Set.of()));
        cache.store(new ChunkPos(-1, -1), new AmethystScan.Result(right, Set.of()));
        assertEquals(List.of(new AABB(-17, -17, -1, -14, -15, 0)), cache.geometry().boxes());
        cache.unload(new ChunkPos(-2, -1));
        assertEquals(List.of(new AABB(-16, -16, -1, -14, -15, 0)), cache.geometry().boxes());
    }

    @Test void noGeometryRebuildOnIdenticalRescanOrLightOnlyInvalidationAndHintsAreOptIn() {
        var cache = new AmethystCache();
        var result = new AmethystScan.Result(cube(0, 2), Set.of(0));
        var cp = new ChunkPos(0, 0);
        cache.store(cp, result);
        var geometry = cache.geometry();
        assertFalse(AmethystGeometry.DEFAULT_SHOW_HINTS);
        assertFalse(AmethystGeometry.DEFAULT_BLOCK_OUTLINES);
        assertTrue(cache.hints(AmethystGeometry.DEFAULT_SHOW_HINTS).isEmpty());
        assertEquals(List.of(new AABB(0, 0, 0, 16, 16, 16)), cache.hints(true));
        assertEquals(List.of(new AABB(0, 0, 0, 3, 3, 3)), geometry.boxes());
        cache.store(cp, new AmethystScan.Result(new HashSet<>(result.blocks()), Set.of(0)));
        assertSame(geometry, cache.geometry());
        cache.invalidateAround(cp);
        assertSame(geometry, cache.geometry());
        assertTrue(cache.hints(true).isEmpty());
    }

    @Test void notificationsFollowConnectedObservationsNotChunksOrLightAndRearmAfterDisappearance() {
        var cache = new AmethystCache();
        var left = new ChunkPos(-1, 0);
        var right = new ChunkPos(0, 0);
        cache.store(left, new AmethystScan.Result(Set.of(new BlockPos(-1, 0, 0)), Set.of()));
        assertEquals(1, cache.takeNotifications());
        cache.store(right, new AmethystScan.Result(Set.of(BlockPos.ZERO), Set.of()));
        assertEquals(0, cache.takeNotifications());
        cache.invalidateAround(right);
        assertEquals(0, cache.takeNotifications());
        cache.block(BlockPos.ZERO, false);
        assertEquals(0, cache.takeNotifications());
        cache.block(new BlockPos(-1, 0, 0), false);
        assertEquals(0, cache.geometry().formations());
        assertEquals(0, cache.takeNotifications());
        cache.store(right, new AmethystScan.Result(Set.of(BlockPos.ZERO), Set.of()));
        assertEquals(1, cache.takeNotifications());
        assertEquals(0, cache.takeNotifications());
    }

    @Test void renderBoxCapNeverChangesAnyRetainedComponentsBoundsOrEvidenceCount() {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x < AmethystGeometry.MAX_BOXES + 2; x++) cells.add(new BlockPos(x * 2, 0, 0));
        var geometry = AmethystGeometry.build(cells, Set.of());
        assertEquals(cells.size(), geometry.formations());
        assertEquals(cells.size(), geometry.observed().size());
        assertEquals(AmethystGeometry.MAX_BOXES, geometry.boxes().size());
        for (AABB box : geometry.boxes()) {
            assertEquals(1, box.getXsize()); assertEquals(1, box.getYsize()); assertEquals(1, box.getZsize());
        }
    }
}
