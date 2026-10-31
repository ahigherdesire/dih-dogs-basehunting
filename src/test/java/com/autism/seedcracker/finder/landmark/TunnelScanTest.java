package com.autism.seedcracker.finder.landmark;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static com.autism.seedcracker.finder.landmark.TunnelScan.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelScanTest {
    private static boolean finish(TunnelScan scan, int budget) {
        int calls = 0;
        while (!scan.done()) {
            assertTrue(scan.step(budget) <= budget);
            assertTrue(++calls < 100_000, "scan did not finish");
        }
        return scan.found();
    }

    private static Cells corridor(int first, int last, int feetY) {
        return (x, y, z) -> z == 8 && x >= first && x <= last && (y == feetY || y == feetY + 1) ? AIR : SOLID;
    }

    @Test void openNaturalFloorAndRoofWithoutSidewallsAreNotTunnels() {
        assertFalse(finish(new TunnelScan(0, 0, 16, 16, 12, (x, y, z) -> y < 16 ? SOLID : AIR), 8192));
        assertFalse(finish(new TunnelScan(0, 0, 16, 16, 12, (x, y, z) -> y == 15 || y == 18 ? SOLID : AIR), 8192));
    }

    @Test void constructedOpenHighwaysRemainSupported() {
        assertTrue(finish(new TunnelScan(0, 0, 16, 16, 12,
            (x, y, z) -> y == 15 ? SOLID | PAVED : AIR), 1));
    }

    @Test void longRunsCrossChunkAndVerticalSectionSeamsAtEverySensitivityLength() {
        for (int length : new int[] {6, 12, 24, 64, 128}) {
            assertTrue(finish(new TunnelScan(0, 0, 16, 16, length, corridor(10, 10 + length - 1, 16)), 7));
        }
        Cells zCorridor = (x, y, z) -> x == -8 && z >= -6 && z <= 17 && (y == 15 || y == 16) ? AIR : SOLID;
        assertTrue(finish(new TunnelScan(-16, -16, 15, 15, 24, zCorridor), 13));
    }

    @Test void missingHeadroomFloorOrNeighborChunkBreaksRun() {
        Cells valid = corridor(10, 33, 16);
        assertFalse(finish(new TunnelScan(0, 0, 16, 16, 24,
            (x, y, z) -> x >= 16 ? UNKNOWN : valid.get(x, y, z)), 8192));
        assertFalse(finish(new TunnelScan(0, 0, 16, 16, 24,
            (x, y, z) -> x == 20 && y == 15 && z == 8 ? AIR : valid.get(x, y, z)), 8192));
        assertFalse(finish(new TunnelScan(0, 0, 16, 16, 24,
            (x, y, z) -> x == 20 && y == 17 && z == 8 ? SOLID : valid.get(x, y, z)), 8192));
    }

    @Test void removedEvidenceCannotSurviveAResumedScanOrLiveRecheck() {
        Cells corridor = corridor(0, 11, 16);
        var removed = new java.util.concurrent.atomic.AtomicBoolean();
        Cells live = (x, y, z) -> removed.get() && x == 0 && y == 15 && z == 8 ? AIR : corridor.get(x, y, z);
        var scan = new TunnelScan(0, 0, 16, 16, 12, live);
        scan.step(16 * 8 + 6);
        assertFalse(scan.done());
        removed.set(true);
        assertFalse(finish(scan, 7));
        removed.set(false);
        var next = new TunnelScan(0, 0, 16, 16, 12, live);
        assertTrue(finish(next, 7));
        assertTrue(TunnelScan.valid(next.match(), live));
        removed.set(true);
        assertFalse(TunnelScan.valid(next.match(), live));
    }

    @Test void resumableWorkHasFixedReadCeilingAndMatchesOneShot() {
        Cells valid = corridor(10, 33, 32);
        var reads = new AtomicInteger();
        var scan = new TunnelScan(0, 0, 32, 32, 24, (x, y, z) -> { reads.incrementAndGet(); return valid.get(x, y, z); });
        assertEquals(3, scan.step(3));
        assertTrue(reads.get() <= 30);
        assertFalse(scan.done());
        assertTrue(finish(scan, 3));
        assertTrue(finish(new TunnelScan(0, 0, 32, 32, 24, valid), 8192));
        assertEquals(0, scan.step(100));
    }
}
