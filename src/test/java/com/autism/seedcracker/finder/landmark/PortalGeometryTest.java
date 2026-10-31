package com.autism.seedcracker.finder.landmark;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static com.autism.seedcracker.finder.landmark.PortalGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class PortalGeometryTest {
    private record Pos(int x, int y, int z) {}
    private final Map<Pos, Integer> blocks = new HashMap<>();
    private int cell(int x, int y, int z) { return blocks.getOrDefault(new Pos(x, y, z), OTHER); }
    private boolean complete(int x, int y, int z) { return PortalGeometry.complete(this::cell, x, y, z, (a, b, c) -> {}); }

    private void frame(int x, int y, int z, int width, int height, int axis) {
        int dx = axis == X ? 1 : 0, dz = axis == Z ? 1 : 0;
        for (int u = -1; u <= width; u++) for (int v = -1; v <= height; v++) {
            boolean edge = u == -1 || u == width || v == -1 || v == height;
            if ((u == -1 || u == width) && (v == -1 || v == height)) continue;
            blocks.put(new Pos(x + dx * u, y + v, z + dz * u), edge ? OBSIDIAN : axis);
        }
    }

    @Test void acceptsBothPlanesAcrossChunkAndSectionBoundariesWithoutCorners() {
        frame(15, 15, 4, 2, 3, X);
        assertTrue(complete(16, 17, 4));
        blocks.clear();
        frame(-1, 31, -17, 3, 4, Z);
        assertTrue(complete(-1, 33, -16));
    }

    @Test void obsidianAndIsolatedPortalBlocksAreNotActiveFrames() {
        blocks.put(new Pos(0, 0, 0), OBSIDIAN);
        assertFalse(complete(0, 0, 0));
        blocks.put(new Pos(0, 0, 0), X);
        assertFalse(complete(0, 0, 0));
    }

    @Test void holesMismatchedAxesMissingFramesAndUnloadedCellsRejectEvidence() {
        frame(0, 0, 0, 4, 5, X);
        blocks.remove(new Pos(2, 2, 0));
        assertFalse(complete(0, 0, 0));
        blocks.put(new Pos(2, 2, 0), Z);
        assertFalse(complete(0, 0, 0));
        blocks.put(new Pos(2, 2, 0), X);
        blocks.remove(new Pos(4, 2, 0));
        assertFalse(complete(0, 0, 0));
        blocks.put(new Pos(4, 2, 0), OBSIDIAN);
        assertTrue(complete(0, 0, 0));
    }

    @Test void maximumVanillaShapeHasBoundedWorkAndOversizeShapesFail() {
        frame(0, 0, 0, 21, 21, X);
        var reads = new AtomicInteger();
        assertTrue(PortalGeometry.complete((x, y, z) -> { reads.incrementAndGet(); return cell(x, y, z); }, 20, 20, 0, (x, y, z) -> {}));
        assertTrue(reads.get() < 700, "frame probe exceeded its bounded volume");
        blocks.clear();
        frame(0, 0, 0, 22, 21, X);
        assertFalse(complete(0, 0, 0));
    }
}
