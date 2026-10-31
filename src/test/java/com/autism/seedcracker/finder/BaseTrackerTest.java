package com.autism.seedcracker.finder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseTrackerTest {
    @Test void weakNearestReportsCannotHideAQualifyingNinthCandidate() {
        for (int offsetX=-64; offsetX<=64; offsetX+=64) {
            for (int offsetZ=-64; offsetZ<=64; offsetZ+=64) {
                BaseTracker.storeReport(offsetX,offsetZ,offsetX==64 && offsetZ==64 ? 80 : 30,"Stash",1000);
            }
        }
        assertTrue(BaseTracker.fusedNearestAt(0,0,8,1001).stream().noneMatch(candidate->candidate.confidence()>=70));
        var candidate=BaseTracker.nearestCandidateAt(0,0,70,128,1001);
        assertNotNull(candidate);
        assertEquals(64,candidate.blockX()); assertEquals(64,candidate.blockZ());
        assertNull(BaseTracker.nearestCandidateAt(0,0,70,64,1001));
        assertNull(BaseTracker.nearestCandidateAt(0,0,90,128,1001));
        assertNull(BaseTracker.nearestCandidateAt(0,0,70,128,3500));
    }

    @Test void correlatedFinderNamesCannotInflateTheScoreOrMoveTheEvidence() {
        BaseTracker.storeReport(8, 8, 55, "Chunk", 1000);
        BaseTracker.storeReport(8, 8, 55, "Activity", 1000);
        BaseTracker.storeReport(8, 8, 55, "Growth", 1000);
        assertEquals(55, BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst().confidence());
        BaseTracker.storeReport(40, 8, 90, "Stash", 1000);
        var fused = BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst();
        assertEquals(90, fused.confidence());
        assertEquals(40, fused.blockX());
        assertEquals(8, fused.blockZ());
        assertTrue(fused.sources().contains("Stash"));
    }

    @Test void diagonalReportsOutsideTheRadiusStaySeparate() {
        BaseTracker.storeReport(8, 8, 55, "Chunk", 1000);
        BaseTracker.storeReport(40, 56, 80, "Stash", 1000);
        assertEquals(2, BaseTracker.fusedNearestAt(0, 0, 10, 1001).size());
    }

    @BeforeEach void before() { BaseTracker.clear(); }
    @AfterEach void after() { BaseTracker.clear(); }

    @Test void collidingJavaSourceHashesCannotOverwriteIndependentReports() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        BaseTracker.storeReport(8, 8, 20, "Aa", 1000);
        BaseTracker.storeReport(8, 8, 20, "BB", 1000);
        assertEquals(2, BaseTracker.nearestAt(0, 0, 10, 1001).size());
        assertEquals(2, BaseTracker.retainedEntries());
    }

    @Test void expiryRemovesStorageAndOutlivesTheOneSecondReportingCadence() {
        BaseTracker.storeReport(8, 8, 60, "Stash", 1000);
        assertEquals(1, BaseTracker.nearestAt(0, 0, 10, 2100).size());
        assertTrue(BaseTracker.nearestAt(0, 0, 10, 3500).isEmpty());
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void zeroLimitsInvalidSourcesAndClockRollbackFailClosed() {
        assertFalse(BaseTracker.storeReport(8, 8, 60, "", 1000));
        assertFalse(BaseTracker.storeReport(8, 8, 0, "Stash", 1000));
        BaseTracker.storeReport(8, 8, 150, "Stash", 1000);
        assertEquals(100, BaseTracker.nearestAt(0, 0, 10, 1001).get(0).confidence());
        assertTrue(BaseTracker.nearestAt(0, 0, 0, 1001).isEmpty());
        assertTrue(BaseTracker.fusedNearestAt(0, 0, 0, 1001).isEmpty());
        assertTrue(BaseTracker.nearestAt(0, 0, 10, 999).isEmpty());
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void withdrawalAndScoreRemovalInvalidateCachedClustersWithoutTouchingOtherFinders() {
        BaseTracker.storeReport(8, 8, 90, "Stash", 1000);
        BaseTracker.storeReport(8, 8, 35, "Growth", 1000);
        assertEquals(90, BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst().confidence());
        BaseTracker.withdraw(8, 8, "Stash");
        assertEquals(35, BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst().confidence());
        assertFalse(BaseTracker.storeReport(8, 8, 0, "Growth", 1001));
        assertTrue(BaseTracker.fusedNearestAt(0, 0, 10, 1001).isEmpty());
        BaseTracker.storeReport(8, 8, 60, "Stash", 1001);
        BaseTracker.storeReport(100, 100, 60, "Stash", 1001);
        BaseTracker.storeReport(200, 200, 50, "Other", 1001);
        BaseTracker.removeSource("Stash");
        assertEquals(java.util.List.of("Other"), BaseTracker.nearestAt(0, 0, 10, 1002).stream().map(BaseTracker.Entry::source).toList());
    }

    @Test void spatialFusionMatchesTheOriginalAnchoredAlgorithmAtNegativeAndBoundaryCoordinates() {
        var random = new java.util.Random(29);
        for (int i = 0; i < 600; i++) BaseTracker.storeReport(random.nextInt(1000) - 500,
            random.nextInt(1000) - 500, random.nextInt(100) + 1, "Finder" + random.nextInt(5), 1000);
        var live = BaseTracker.nearestAt(0, 0, 1000, 1001).stream().sorted(java.util.Comparator.comparingInt(BaseTracker.Entry::blockX)
            .thenComparingInt(BaseTracker.Entry::blockZ).thenComparing(BaseTracker.Entry::source)).toList();
        var expected = new java.util.ArrayList<BaseTracker.Fused>();
        boolean[] used = new boolean[live.size()];
        for (int i = 0; i < live.size(); i++) {
            if (used[i]) continue;
            var seed = live.get(i); var strongest = seed; used[i] = true;
            var sources = new java.util.LinkedHashSet<String>(); sources.add(seed.source());
            for (int j = i + 1; j < live.size(); j++) {
                if (used[j]) continue;
                var entry = live.get(j);
                long dx = (long) entry.blockX() - seed.blockX(), dz = (long) entry.blockZ() - seed.blockZ();
                if (dx * dx + dz * dz > 48L * 48) continue;
                used[j] = true; sources.add(entry.source());
                if (entry.confidence() > strongest.confidence()) strongest = entry;
            }
            double dx = strongest.blockX() + 17.25, dz = strongest.blockZ() - 81.5;
            expected.add(new BaseTracker.Fused(strongest.blockX(), strongest.blockZ(), strongest.confidence(),
                String.join("+", sources), dx * dx + dz * dz));
        }
        expected.sort(java.util.Comparator.comparingDouble(BaseTracker.Fused::distSq));
        assertEquals(expected, BaseTracker.fusedNearestAt(-17.25, 81.5, 1000, 1001));
        assertEquals(expected.subList(0, 6), BaseTracker.fusedNearestAt(-17.25, 81.5, 6, 1001));
        assertThrows(UnsupportedOperationException.class, () -> BaseTracker.fusedNearestAt(0, 0, 6, 1001).clear());
    }

    @Test void ledgerHasABoundedAdmissionLimitAndExpiredSlotsCanBeReused() {
        for (int i = 0; i < BaseTracker.MAX_REPORTS; i++) assertTrue(BaseTracker.storeReport(i * 16, 0, 50, "Fixture", 1000));
        assertFalse(BaseTracker.storeReport(-16, 0, 50, "Fixture", 1000));
        assertEquals(BaseTracker.MAX_REPORTS, BaseTracker.retainedEntries());
        assertTrue(BaseTracker.storeReport(0, 0, 80, "Fixture", 1001), "Existing reports may refresh at capacity");
        assertTrue(BaseTracker.storeReport(-16, 0, 50, "Fixture", 3501));
        assertEquals(1, BaseTracker.retainedEntries());
    }

    @Test void nonFiniteQueryCoordinatesCannotDriveNavigation() {
        BaseTracker.storeReport(8, 8, 80, "Stash", 1000);
        assertTrue(BaseTracker.fusedNearestAt(Double.NaN, 0, 5, 1001).isEmpty());
        assertTrue(BaseTracker.nearestAt(0, Double.POSITIVE_INFINITY, 5, 1001).isEmpty());
        assertNull(BaseTracker.nearestCandidateAt(Double.NaN, 0, 0, 128, 1001));
    }

    @Test void reportInsertionOrderCannotChangeTheFusedGroups() {
        BaseTracker.storeReport(8, 8, 50, "Stash", 1000);
        BaseTracker.storeReport(40, 8, 40, "Chunk", 1000);
        var first = BaseTracker.fusedNearestAt(0, 0, 10, 1001);
        BaseTracker.clear();
        BaseTracker.storeReport(40, 8, 40, "Chunk", 1000);
        BaseTracker.storeReport(8, 8, 50, "Stash", 1000);
        assertEquals(first, BaseTracker.fusedNearestAt(0, 0, 10, 1001));
    }
}