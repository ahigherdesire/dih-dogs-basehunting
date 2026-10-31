package com.autism.seedcracker.finder.growth;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrowthScanTest {
    @Test void denseWorldHeightScanResumesWithinEveryCellBudget() {
        GrowthScan scan = new GrowthScan(24);
        AtomicInteger reads = new AtomicInteger();
        int ticks = 0;
        while (!scan.complete()) {
            int before = reads.get();
            int used = scan.step(cell -> { reads.incrementAndGet(); return GrowthScan.DRIPSTONE; }, 127, Long.MAX_VALUE);
            assertTrue(used > 0 && used <= 127);
            assertEquals(used, reads.get() - before);
            assertTrue(++ticks < 1000, "A partial chunk must eventually finish");
        }
        assertEquals(24 * 4096, reads.get());
        assertEquals(Math.round(24 * 4096 * 1.875), scan.result().score());
        assertEquals(7.5, scan.result().sourceX());
        assertEquals(7.5, scan.result().sourceZ());
    }

    @Test void expiredDeadlineDoesNotReadOrResetThePartialSection() {
        GrowthScan scan = new GrowthScan(1);
        scan.step(cell -> GrowthScan.BERRY, 7, Long.MAX_VALUE);
        assertEquals(0, scan.step(cell -> { fail("Deadline already expired"); return 0; }, 4096, System.nanoTime() - 1));
        while (!scan.complete()) scan.step(cell -> GrowthScan.NONE, 4096, Long.MAX_VALUE);
        assertEquals(7, scan.result().score());
    }

    @Test void vinesCrossSectionAndTickBoundariesAndUseTheOriginalCentroidWeights() {
        GrowthScan scan = new GrowthScan(2);
        while (!scan.complete()) {
            int section = scan.section();
            scan.step(cell -> {
                int y = section * 16 + (cell >>> 8), x = (cell >>> 4) & 15, z = cell & 15;
                if (x == 2 && z == 3 && y >= 12 && y < 24) return GrowthScan.VINE;
                if (x == 1 && z == 1 && y < 5) return GrowthScan.VINE;
                if (x == 8 && z == 7 && y < 2) return GrowthScan.DRIPSTONE;
                if (x == 10 && z == 9 && y == 0) return GrowthScan.BERRY;
                return GrowthScan.NONE;
            }, 37, Long.MAX_VALUE);
        }
        var result = scan.result();
        assertEquals(11, result.score()); // 12 * .5 + 2 * 1.875 + 1, rounded once.
        assertEquals(12, result.maxVine());
        assertEquals((2 * 12 + 8 * 5 + 10) / 18.0, result.sourceX());
        assertEquals((3 * 12 + 7 * 5 + 9) / 18.0, result.sourceZ());
    }

    @Test void emptySectionsBreakVinesAndTopBoundaryFlushesTheLastRun() {
        GrowthScan scan = new GrowthScan(3);
        scan.step(cell -> (cell & 255) == 0 ? GrowthScan.VINE : GrowthScan.NONE, 4096, Long.MAX_VALUE);
        scan.skipEmptySection();
        scan.step(cell -> (cell & 255) == 0 ? GrowthScan.VINE : GrowthScan.NONE, 4096, Long.MAX_VALUE);
        assertTrue(scan.complete());
        assertEquals(16, scan.result().maxVine());
        assertEquals(16, scan.result().score());
    }

    @Test void emptyChunksNeedNoBlockReadsAndFallBackToTheChunkCenter() {
        GrowthScan scan = new GrowthScan(24);
        while (!scan.complete()) assertEquals(1, scan.skipEmptySection());
        assertEquals(new GrowthScan.Result(0, 0, 8, 8), scan.result());
        assertThrows(IllegalStateException.class, scan::skipEmptySection);
        assertThrows(IllegalArgumentException.class, () -> new GrowthScan(-1));
        assertThrows(IllegalArgumentException.class, () -> new GrowthScan(Integer.MAX_VALUE));
    }
}
