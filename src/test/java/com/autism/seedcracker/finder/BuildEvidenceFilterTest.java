package com.autism.seedcracker.finder;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildEvidenceFilterTest {
    @Test void naturalLightAloneIsSuppressedButIndependentBuildingCluesRemain() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
        var natural = new ChunkPos(0, 0); var built = new ChunkPos(1, 0);
        var candidates = new LinkedHashSet<ChunkPos>(); candidates.add(natural); candidates.add(built);
        assertTrue(filter.filter(world, candidates, p -> chunk, p -> p.equals(built)).isEmpty());
        assertEquals(Set.of(built), filter.filter(world, candidates, p -> chunk, p -> p.equals(built)));
    }

    @Test void evidenceWorkIsLimitedToOneChunkPerTickAndEventuallyReachesEveryCandidate() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
        var candidates = new LinkedHashSet<ChunkPos>(); for (int i = 0; i < 100; i++) candidates.add(new ChunkPos(i, 0));
        var calls = new AtomicInteger(); var seen = new java.util.HashSet<ChunkPos>();
        for (int tick = 0; tick < 120; tick++) {
            int before = calls.get();
            seen.addAll(filter.filter(world, candidates, p -> chunk, p -> { calls.incrementAndGet(); return true; }));
            assertTrue(calls.get() - before <= 1);
        }
        assertEquals(candidates, seen);
    }

    @Test void removedReplacedAndInvalidatedEvidenceCannotKeepPublishing() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var pos = new ChunkPos(-1, -1);
        var chunks = new HashMap<ChunkPos, Object>(); chunks.put(pos, new Object());
        assertEquals(Set.of(pos), filter.filter(world, Set.of(pos), chunks::get, p -> true));
        chunks.put(pos, new Object());
        assertTrue(filter.filter(world, Set.of(pos), chunks::get, p -> false).isEmpty());
        filter.invalidate(pos);
        assertEquals(Set.of(pos), filter.filter(world, Set.of(pos), chunks::get, p -> true));
        chunks.clear();
        assertTrue(filter.filter(world, Set.of(pos), chunks::get, p -> fail("Unloaded chunk scanned")).isEmpty());
    }

    @Test void rescanAndWorldChangesDiscardNaturalisedOrStaleClues() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object(); var pos = new ChunkPos(1, 2);
        assertEquals(Set.of(pos), filter.filter(world, Set.of(pos), p -> chunk, p -> true));
        Set<ChunkPos> output = Set.of(pos);
        for (int i = 0; i < 21; i++) output = filter.filter(world, Set.of(pos), p -> chunk, p -> false);
        assertTrue(output.isEmpty());
        assertTrue(filter.filter(new Object(), Set.of(pos), p -> chunk, p -> false).isEmpty());
        filter.clear();
        assertTrue(filter.filter(null, Set.of(pos), p -> chunk, p -> fail("No-world scan")).isEmpty());
    }

    @Test void emptyCandidatesClearStateAndResultsAreImmutable() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object(); var pos = new ChunkPos(1, 2);
        var shown = filter.filter(world, Set.of(pos), p -> chunk, p -> true);
        assertThrows(UnsupportedOperationException.class, shown::clear);
        assertTrue(filter.filter(world, Set.of(), p -> chunk, p -> true).isEmpty());
        assertTrue(filter.filter(world, Set.of(pos), p -> chunk, p -> false).isEmpty());
    }

    @Test void stableCandidatesDoNotFlickerAtTheOldExpiryOrTheNewBudgetBoundary() {
        for (int size : new int[] {1, 39, 40, 41, 100, 200}) {
            var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
            var candidates = new LinkedHashSet<ChunkPos>();
            for (int i = 0; i < size; i++) candidates.add(new ChunkPos(i, 0));
            var scans = new AtomicInteger();
            for (int tick = 0; tick < size + 450; tick++) {
                int before = scans.get();
                var shown = filter.filter(world, candidates, p -> chunk, p -> { scans.incrementAndGet(); return true; });
                assertTrue(scans.get() - before <= 1);
                if (tick >= size - 1) assertEquals(candidates, shown, "size=" + size + ", tick=" + tick);
                assertEquals(0, filter.deferredCandidates());
            }
        }
    }

    @Test void overBudgetCoverageIsExplicitAndVacanciesAreReusedWithoutStaleTrust() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
        var candidates = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 201; i++) candidates.add(new ChunkPos(i, 0));
        var scans = new AtomicInteger();
        for (int tick = 0; tick < 450; tick++) {
            var shown = filter.filter(world, candidates, p -> chunk, p -> { scans.incrementAndGet(); return true; });
            assertEquals(1, filter.deferredCandidates());
            assertEquals(Math.max(0, 199 - tick), filter.pendingCandidates());
            if (tick >= 199) assertEquals(200, shown.size());
            assertFalse(shown.contains(new ChunkPos(200, 0)), "Deferred is unassessed, not silently published");
        }
        candidates.remove(new ChunkPos(0, 0));
        Set<ChunkPos> shown = Set.of();
        for (int tick = 0; tick < 200; tick++) shown = filter.filter(world, candidates, p -> chunk, p -> true);
        assertEquals(candidates, shown);
        assertEquals(0, filter.deferredCandidates());
        var removedEvidence = new ChunkPos(1, 0);
        for (int tick = 0; tick < 200; tick++) shown = filter.filter(world, candidates, p -> chunk, p -> !p.equals(removedEvidence));
        assertFalse(shown.contains(removedEvidence), "Evidence must refresh within the fixed 200-tick ceiling");
        assertEquals(199, shown.size());
    }

    @Test void constantNewcomersCannotStarveStableCandidates() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
        var stable = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 60; i++) stable.add(new ChunkPos(i, 0));
        var lastScan = new HashMap<ChunkPos, Integer>();
        for (int tick = 0; tick < 500; tick++) {
            var candidates = new LinkedHashSet<ChunkPos>(); candidates.add(new ChunkPos(1000 + tick, 0)); candidates.addAll(stable);
            int now = tick;
            var shown = filter.filter(world, candidates, p -> chunk, p -> { lastScan.put(p, now); return true; });
            if (tick >= 100) {
                assertTrue(shown.containsAll(stable));
                for (ChunkPos pos : stable) assertTrue(tick - lastScan.get(pos) < 200);
            }
        }
    }

    @Test void replacementInvalidationAndFailedEvaluationCannotResurrectOldSupport() {
        for (int action = 0; action < 4; action++) {
            var filter = new BuildEvidenceFilter(); var world = new Object(); var pos = new ChunkPos(0, 0);
            var chunk = new Object[] {new Object()}; int mode = action;
            java.util.function.Predicate<ChunkPos> evaluate = p -> {
                if (mode == 0) filter.invalidate(p);
                if (mode == 1) chunk[0] = new Object();
                if (mode == 2) filter.clear();
                if (mode == 3) throw new IllegalStateException("fixture failure");
                return true;
            };
            if (mode == 3) assertThrows(IllegalStateException.class, () -> filter.filter(world, Set.of(pos), p -> chunk[0], evaluate));
            else assertTrue(filter.filter(world, Set.of(pos), p -> chunk[0], evaluate).isEmpty());
            assertTrue(filter.filter(world, Set.of(pos), p -> chunk[0], p -> false).isEmpty());
        }
    }

    @Test void anUnloadObservedDuringAnotherScanForcesFreshEvidenceEvenIfTheObjectReturns() {
        var filter = new BuildEvidenceFilter(); var world = new Object();
        var first = new ChunkPos(0, 0); var second = new ChunkPos(1, 0); var firstChunk = new Object();
        var chunks = new HashMap<ChunkPos, Object>(); chunks.put(first, firstChunk); chunks.put(second, new Object());
        assertEquals(Set.of(first), filter.filter(world, Set.of(first), chunks::get, p -> true));
        var candidates = new LinkedHashSet<ChunkPos>(); candidates.add(first); candidates.add(second);
        assertTrue(filter.filter(world, candidates, chunks::get, p -> { chunks.remove(first); return false; }).isEmpty());
        chunks.put(first, firstChunk);
        var rescanned = new AtomicInteger();
        assertTrue(filter.filter(world, candidates, chunks::get, p -> { rescanned.incrementAndGet(); return false; }).isEmpty());
        assertEquals(1, rescanned.get());
    }

    @Test void oversizedInputFailsClosedAndReportsItsUnassessedCoverage() {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
        var candidates = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 4097; i++) candidates.add(new ChunkPos(i, 0));
        assertTrue(filter.filter(world, candidates, p -> fail("Oversized input examined"), p -> fail("Oversized input scored")).isEmpty());
        assertEquals(4097, filter.deferredCandidates());
        filter.clear(); assertEquals(0, filter.deferredCandidates());
        var natural = new ChunkPos(0, 0); var neighbouringBuild = new ChunkPos(1, 0);
        assertTrue(filter.filter(world, Set.of(natural), p -> chunk, p -> p.equals(neighbouringBuild)).isEmpty());
    }
}
