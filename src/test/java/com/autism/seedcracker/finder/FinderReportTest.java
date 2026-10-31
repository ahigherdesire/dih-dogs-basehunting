package com.autism.seedcracker.finder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinderReportTest {
    @org.junit.jupiter.api.AfterEach void cleanup() { BaseTracker.clear(); }

    @Test void appliedPacketsInvalidateSharedScoresOncePerChangedChunk() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java", "kaptainwutax",
            "seedcrackerX", "mixin", "ClientPacketListenerMixin.java"));
        int start = source.indexOf("private void spectre$packetApplied(");
        String hook = source.substring(start, source.indexOf("private static void spectre$detectorFailed", start));
        for (String packet : new String[]{"ClientboundLevelChunkWithLightPacket", "ClientboundForgetLevelChunkPacket",
                "ClientboundBlockUpdatePacket", "ClientboundSectionBlocksUpdatePacket"}) assertTrue(hook.contains(packet), packet);
        assertEquals(1, hook.split(java.util.regex.Pattern.quote("FinderReport.invalidateAll("), -1).length - 1);
        assertTrue(hook.contains("FinderReport.invalidateAll(this.level, changed)"));
        assertTrue(hook.contains("if (sectionChunk[0] == null)"));
    }

    @Test void coverageSummariesCallUnassessedCandidatesOutRatherThanCallingThemNegative() {
        Set<ChunkPos> candidates = new LinkedHashSet<>();
        for (int x = 0; x < 225; x++) candidates.add(new ChunkPos(x, 0));
        var report = new FinderReport("Coverage", 0);
        var filter = new BuildEvidenceFilter();
        Object world = new Object(), chunk = new Object();
        report.tick(world, candidates, pos -> true, pos -> 50, (pos, score) -> {});
        filter.filter(world, candidates, pos -> chunk, pos -> true);
        assertTrue(report.coverageSuffix().contains("25 unassessed"));
        assertTrue(filter.coverageSuffix().contains("25 unassessed"));
        report.clear(); filter.clear();
        assertEquals("", report.coverageSuffix());
        assertEquals("", filter.coverageSuffix());
    }

    @Test void disappearanceDisableAndWorldChangeWithdrawImmediately() {
        var report = new FinderReport("Fixture", 0);
        var world = new Object(); var pos = new ChunkPos(0, 0);
        java.util.function.BiConsumer<ChunkPos, Integer> publish = (chunk, score) -> BaseTracker.storeReport(
            chunk.getMinBlockX() + 8, chunk.getMinBlockZ() + 8, score, "Fixture", 1000);
        for (int tick = 0; tick < 20; tick++) report.tick(world, Set.of(pos), p -> true, p -> 80, publish);
        BaseTracker.storeReport(200, 200, 70, "Other", 1000);
        assertEquals(2, BaseTracker.retainedEntries());
        report.tick(world, Set.of(pos), p -> false, p -> fail("Unloaded evidence scored"), publish);
        assertEquals(1, BaseTracker.retainedEntries());
        for (int tick = 0; tick < 20; tick++) report.tick(world, Set.of(pos), p -> true, p -> 80, publish);
        report.clear();
        assertEquals(java.util.List.of("Other"), BaseTracker.nearestAt(0, 0, 5, 1001).stream().map(BaseTracker.Entry::source).toList());
        for (int tick = 0; tick < 20; tick++) report.tick(world, Set.of(pos), p -> true, p -> 80, publish);
        report.tick(new Object(), Set.of(pos), p -> true, p -> 20, publish);
        assertEquals(1, BaseTracker.retainedEntries(), "Old-world reports disappear before the new reporting cadence");
    }

    @Test void aLargeFlagSetCannotStarveLaterChunks() {
        var report = new FinderReport("Fixture", 10);
        var world = new Object();
        var flagged = new LinkedHashSet<ChunkPos>();
        for (int chunk = 0; chunk < 150; chunk++) flagged.add(new ChunkPos(chunk, 0));
        var seen = new java.util.HashMap<ChunkPos, Integer>();
        for (int tick = 0; tick < 300; tick++) report.tick(world, flagged, pos -> true,
            pos -> { seen.merge(pos, 1, Integer::sum); return 25; }, (pos, value) -> {});
        assertEquals(150, seen.size());
        assertTrue(seen.values().stream().allMatch(count -> count == 2));
    }

    @Test void refreshesChangedScoresAndNeverReportsUnloadedEvidence() {
        var report = new FinderReport("Fixture", 10);
        var world = new Object();
        var flagged = Set.of(new ChunkPos(0, 0));
        var score = new AtomicInteger(90);
        var output = new ArrayList<Integer>();
        for (int tick = 0; tick < 20; tick++) report.tick(world, flagged, pos -> true, pos -> score.get(), (pos, value) -> output.add(value));
        assertEquals(90, output.getLast());
        score.set(20);
        for (int tick = 0; tick < 100; tick++) report.tick(world, flagged, pos -> true, pos -> score.get(), (pos, value) -> output.add(value));
        assertEquals(20, output.getLast());
        score.set(85);
        for (int tick = 0; tick < 100; tick++) report.tick(world, flagged, pos -> true, pos -> score.get(), (pos, value) -> output.add(value));
        assertEquals(85, output.getLast());
        output.clear();
        for (int tick = 0; tick < 40; tick++) report.tick(world, flagged, pos -> false, pos -> fail("Unloaded chunk scored"), (pos, value) -> output.add(value));
        assertTrue(output.isEmpty());
    }

    @Test void scansAtMostOneChunkPerTickAndResetsOnEmptyOrWorldChange() {
        var report = new FinderReport("Fixture", 10);
        var world = new Object();
        var flagged = new LinkedHashSet<ChunkPos>();
        for (int chunk = 0; chunk < 30; chunk++) flagged.add(new ChunkPos(chunk, 0));
        var scans = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) {
            int before = scans.get();
            report.tick(world, flagged, pos -> true, pos -> { scans.incrementAndGet(); return 90; }, (pos, value) -> {});
            assertEquals(1, scans.get() - before);
        }
        report.tick(world, Set.of(), pos -> true, pos -> 90, (pos, value) -> fail("Empty results reported"));
        var output = new ArrayList<Integer>();
        var single = Set.of(new ChunkPos(0, 0));
        for (int tick = 0; tick < 20; tick++) report.tick(world, single, pos -> true, pos -> 25, (pos, value) -> output.add(value));
        assertEquals(25, output.getLast());
        output.clear();
        var nextWorld = new Object();
        for (int tick = 0; tick < 20; tick++) report.tick(nextWorld, single, pos -> true, pos -> 15, (pos, value) -> output.add(value));
        assertEquals(15, output.getLast());
    }

    @Test void typedContextCapsHeuristicFloorsWithoutParsingReasonsOrWeakeningDirectScores() {
        var world = new Object(); var chunk = new Object(); var pos = new ChunkPos(0, 0);
        for (int floor : new int[] {0, 35, 40, 45, 55, 100}) {
            for (var result : java.util.List.of(
                    new BaseConfidence.Result(35, "looks absolutely built", BaseConfidence.Evidence.NATURAL_COMPATIBLE),
                    new BaseConfidence.Result(100, "not a policy token", BaseConfidence.Evidence.NATURAL_COMPATIBLE),
                    new BaseConfidence.Result(5, "one placed device", BaseConfidence.Evidence.BUILDING),
                    new BaseConfidence.Result(83, "Natural-compatible: deliberately misleading text", BaseConfidence.Evidence.BUILDING),
                    new BaseConfidence.Result(90, "direct numeric evidence"),
                    new BaseConfidence.Result(90, "unavailable data", BaseConfidence.Evidence.UNKNOWN))) {
                var report = new FinderReport("Fixture", floor); var output = new ArrayList<Integer>();
                for (int tick = 0; tick < 20; tick++) report.tickEvidence(world, Set.of(pos), p -> chunk, p -> result,
                    (p, score) -> output.add(score));
                int expected = result.evidence() == BaseConfidence.Evidence.UNKNOWN ? 0
                    : result.evidence() == BaseConfidence.Evidence.NATURAL_COMPATIBLE ? 35
                    : Math.max(result.score(), Math.min(floor, 35));
                assertEquals(expected, output.getLast());
                report.clear();
            }
        }
    }

    @Test void theFreshnessCeilingKeepsTwoHundredReportsStableAcrossActualLedgerExpiry() {
        var report = new FinderReport("Fixture", 0); var world = new Object(); var chunk = new Object();
        var flagged = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 200; i++) flagged.add(new ChunkPos(i, 0));
        var scans = new AtomicInteger();
        for (int tick = 1; tick <= 650; tick++) {
            long now = tick * 50L; int before = scans.get();
            report.tickEvidence(world, flagged, p -> chunk,
                p -> { scans.incrementAndGet(); return new BaseConfidence.Result(80, "verified"); },
                (p, score) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, score, "Fixture", now));
            assertTrue(scans.get() - before <= 1);
            assertEquals(0, report.deferredCandidates());
            if (tick >= 200) assertEquals(200, BaseTracker.nearestAt(0, 0, 201, now).size(), "tick=" + tick);
        }
    }

    @Test void replacementAndInvalidationWithdrawImmediatelyAndCannotPublishInFlightResults() {
        var report = new FinderReport("Fixture", 55); var world = new Object(); var pos = new ChunkPos(0, 0);
        var chunk = new Object[] {new Object()};
        java.util.function.BiConsumer<ChunkPos, Integer> publish = (p, score) -> BaseTracker.storeReport(8, 8, score, "Fixture", 1000);
        for (int tick = 0; tick < 20; tick++) report.tickEvidence(world, Set.of(pos), p -> chunk[0],
            p -> new BaseConfidence.Result(80, "verified"), publish);
        assertEquals(1, BaseTracker.retainedEntries());
        chunk[0] = new Object();
        report.tickEvidence(world, Set.of(pos), p -> chunk[0], p -> BaseConfidence.score(null), publish);
        assertEquals(0, BaseTracker.retainedEntries());
        report.invalidate(pos);
        report.tickEvidence(world, Set.of(pos), p -> chunk[0], p -> {
            report.invalidate(p); return new BaseConfidence.Result(80, "invalidated in flight");
        }, publish);
        for (int tick = 0; tick < 20; tick++) report.tickEvidence(world, Set.of(pos), p -> chunk[0],
            p -> BaseConfidence.score(null), publish);
        assertEquals(0, BaseTracker.retainedEntries());
        report.invalidate(pos);
        report.tickEvidence(world, Set.of(pos), p -> chunk[0], p -> {
            chunk[0] = new Object(); return new BaseConfidence.Result(80, "replaced in flight");
        }, publish);
        assertEquals(0, BaseTracker.retainedEntries());
        report.tickEvidence(world, Set.of(pos), p -> null, p -> fail("Missing chunk scored"), publish);
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void constantNewcomersDoNotTakeEveryRefreshTurn() {
        var report = new FinderReport("Fixture", 0); var world = new Object(); var stable = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 80; i++) stable.add(new ChunkPos(i, 0));
        var last = new java.util.HashMap<ChunkPos, Integer>();
        for (int tick = 0; tick < 500; tick++) {
            var flagged = new LinkedHashSet<ChunkPos>(); flagged.add(new ChunkPos(1000 + tick, 0)); flagged.addAll(stable);
            int now = tick;
            report.tick(world, flagged, p -> true, p -> { last.put(p, now); return 80; }, (p, value) -> {});
            if (tick >= 160) for (ChunkPos pos : stable) assertTrue(tick - last.get(pos) < 200);
        }
    }

    @Test void deferredCoverageIsExplicitAndOverloadClearsRatherThanTrustingOlderSamples() {
        var report = new FinderReport("Fixture", 0); var world = new Object(); var flagged = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 201; i++) flagged.add(new ChunkPos(i, 0));
        for (int tick = 0; tick < 420; tick++) {
            report.tick(world, flagged, p -> true, p -> 80,
                (p, score) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, score, "Fixture", 1000));
            assertEquals(1, report.deferredCandidates());
            assertEquals(Math.max(0, 199 - tick), report.pendingCandidates());
            if (tick >= 199) assertEquals(200, BaseTracker.retainedEntries());
        }
        flagged.remove(new ChunkPos(0, 0));
        for (int tick = 0; tick < 220; tick++) report.tick(world, flagged, p -> true, p -> 80,
            (p, score) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, score, "Fixture", 1000));
        assertEquals(0, report.deferredCandidates());
        assertTrue(BaseTracker.nearestAt(0, 0, 201, 1001).stream().anyMatch(e -> e.blockX() == 3208));
        for (int i = 201; i <= 4097; i++) flagged.add(new ChunkPos(i, 0));
        report.tick(world, flagged, p -> fail("Overloaded input examined"), p -> fail("Overloaded input scored"), (p, value) -> fail("Overloaded report"));
        assertEquals(4097, report.deferredCandidates());
        assertEquals(0, BaseTracker.retainedEntries());
        report.clear(); assertEquals(0, report.deferredCandidates());
    }

    @Test void aMidPassUnloadCannotRestoreAnOldReportWhenTheChunkObjectReturns() {
        var report = new FinderReport("Fixture", 0); var world = new Object();
        var first = new ChunkPos(0, 0); var second = new ChunkPos(1, 0); var firstChunk = new Object();
        var chunks = new java.util.HashMap<ChunkPos, Object>(); chunks.put(first, firstChunk); chunks.put(second, new Object());
        java.util.function.BiConsumer<ChunkPos, Integer> publish = (p, score) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, score, "Fixture", 1000);
        for (int tick = 0; tick < 20; tick++) report.tickEvidence(world, Set.of(first), chunks::get,
            p -> new BaseConfidence.Result(80, "verified"), publish);
        assertEquals(1, BaseTracker.retainedEntries());
        var candidates = new LinkedHashSet<ChunkPos>(); candidates.add(first); candidates.add(second);
        report.tickEvidence(world, candidates, chunks::get, p -> { chunks.remove(first); return BaseConfidence.score(null); }, publish);
        assertEquals(0, BaseTracker.retainedEntries());
        chunks.put(first, firstChunk);
        var rescanned = new AtomicInteger();
        report.tickEvidence(world, candidates, chunks::get, p -> { rescanned.incrementAndGet(); return BaseConfidence.score(null); }, publish);
        assertEquals(1, rescanned.get());
        for (int tick = 0; tick < 20; tick++) report.tickEvidence(world, candidates, chunks::get, p -> BaseConfidence.score(null), publish);
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void sharedInvalidationWithdrawsMatchingWorldSourcesButNotOtherWorldsChunksOrDirectSources() {
        var world = new String("world"); var otherWorld = new String("world");
        assertEquals(world, otherWorld);
        var pos = new ChunkPos(0, 0); var neighbour = new ChunkPos(8, 0);
        var first = new FinderReport("First", 0); var second = new FinderReport("Second", 0);
        var foreign = new FinderReport("Foreign", 0);
        for (int tick = 0; tick < 20; tick++) {
            first.tick(world, Set.of(pos, neighbour), p -> true, p -> 80,
                (p, score) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, score, "First", 1000));
            second.tick(world, Set.of(pos), p -> true, p -> 70,
                (p, score) -> BaseTracker.storeReport(8, 8, score, "Second", 1000));
            foreign.tick(otherWorld, Set.of(pos), p -> true, p -> 65,
                (p, score) -> BaseTracker.storeReport(8, 8, score, "Foreign", 1000));
        }
        BaseTracker.storeReport(8, 8, 90, "Direct", 1000);
        assertEquals(5, BaseTracker.retainedEntries());
        try {
            FinderReport.invalidateAll(world, pos);
            var retained = BaseTracker.nearestAt(0, 0, 10, 1001);
            assertEquals(3, retained.size());
            assertEquals(Set.of("First", "Foreign", "Direct"), retained.stream().map(BaseTracker.Entry::source).collect(java.util.stream.Collectors.toSet()));
            assertEquals(neighbour.getMinBlockX() + 8,
                retained.stream().filter(entry -> entry.source().equals("First")).findFirst().orElseThrow().blockX());
            foreign.tick(otherWorld, Set.of(pos), p -> true, p -> fail("Unmatched world's cache invalidated"), (p, score) -> {});
        } finally {
            // The fixtures, like real owning modules, must outlive the weak-registry dispatch.
            java.lang.ref.Reference.reachabilityFence(first);
            java.lang.ref.Reference.reachabilityFence(second);
            java.lang.ref.Reference.reachabilityFence(foreign);
        }
    }

    @Test void sharedInvalidationTracksWorldRebindingAndIgnoresMissingWorldOrPosition() {
        var report = new FinderReport("Reusable", 0); var oldWorld = new Object(); var newWorld = new Object();
        var pos = new ChunkPos(0, 0);
        java.util.function.BiConsumer<ChunkPos, Integer> publish = (p, score) -> BaseTracker.storeReport(8, 8, score, "Reusable", 1000);
        for (int tick = 0; tick < 20; tick++) report.tick(oldWorld, Set.of(pos), p -> true, p -> 90, publish);
        report.clear();
        for (int tick = 0; tick < 20; tick++) report.tick(newWorld, Set.of(pos), p -> true, p -> 80, publish);
        FinderReport.invalidateAll(oldWorld, pos);
        FinderReport.invalidateAll(null, pos);
        FinderReport.invalidateAll(newWorld, null);
        assertEquals(1, BaseTracker.retainedEntries());
        FinderReport.invalidateAll(newWorld, pos);
        assertEquals(0, BaseTracker.retainedEntries());
        var scans = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) report.tick(newWorld, Set.of(pos), p -> true,
            p -> { scans.incrementAndGet(); return 25; }, publish);
        assertEquals(1, scans.get());
        assertEquals(25, BaseTracker.nearestAt(0, 0, 1, 1001).getFirst().confidence());
    }

    @Test void sharedInvalidationCannotResurrectASampleAlreadyBeingScored() {
        var first = new FinderReport("First", 55); var second = new FinderReport("Second", 55);
        var world = new Object(); var chunk = new Object(); var pos = new ChunkPos(0, 0);
        var published = new ArrayList<Integer>();
        java.util.function.BiConsumer<ChunkPos, Integer> publishFirst = (p, score) -> {
            published.add(score); BaseTracker.storeReport(8, 8, score, "First", 1000);
        };
        java.util.function.BiConsumer<ChunkPos, Integer> publishSecond = (p, score) -> BaseTracker.storeReport(8, 8, score, "Second", 1000);
        for (int tick = 0; tick < 100; tick++) {
            first.tickEvidence(world, Set.of(pos), p -> chunk, p -> new BaseConfidence.Result(80, "verified"), publishFirst);
            second.tickEvidence(world, Set.of(pos), p -> chunk, p -> new BaseConfidence.Result(70, "verified"), publishSecond);
        }
        assertEquals(2, BaseTracker.retainedEntries());
        published.clear();
        first.tickEvidence(world, Set.of(pos), p -> chunk, p -> {
            FinderReport.invalidateAll(world, p);
            return new BaseConfidence.Result(99, "invalidated in flight");
        }, publishFirst);
        assertEquals(0, BaseTracker.retainedEntries());
        var rescans = new AtomicInteger();
        for (int tick = 0; tick < 40; tick++) {
            first.tickEvidence(world, Set.of(pos), p -> chunk,
                p -> { rescans.incrementAndGet(); return BaseConfidence.score(null); }, publishFirst);
            second.tickEvidence(world, Set.of(pos), p -> chunk,
                p -> { rescans.incrementAndGet(); return BaseConfidence.score(null); }, publishSecond);
        }
        assertEquals(2, rescans.get());
        assertFalse(published.isEmpty());
        assertTrue(published.stream().allMatch(score -> score == 0));
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void anUnknownOrFailedRefreshImmediatelyWithdrawsThePreviousStrongScore() {
        for (boolean failScan : new boolean[] {false, true}) {
            var report = new FinderReport("Fixture", 55); var world = new Object(); var pos = new ChunkPos(0, 0); var chunk = new Object();
            java.util.function.BiConsumer<ChunkPos, Integer> publish = (p, score) -> BaseTracker.storeReport(8, 8, score, "Fixture", 1000);
            for (int tick = 0; tick < 100; tick++) report.tickEvidence(world, Set.of(pos), p -> chunk,
                p -> new BaseConfidence.Result(80, "verified"), publish);
            assertEquals(1, BaseTracker.retainedEntries());
            if (failScan) assertThrows(IllegalStateException.class, () -> report.tickEvidence(world, Set.of(pos), p -> chunk,
                p -> { throw new IllegalStateException("fixture failure"); }, publish));
            else report.tickEvidence(world, Set.of(pos), p -> chunk, p -> BaseConfidence.score(null), publish);
            assertEquals(0, BaseTracker.retainedEntries());
            report.clear();
        }
    }
}