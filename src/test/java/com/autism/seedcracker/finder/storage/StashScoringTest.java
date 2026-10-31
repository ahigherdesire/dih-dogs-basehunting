package com.autism.seedcracker.finder.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.HashSet;
import com.autism.seedcracker.finder.ScanCursor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StashScoringTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Map<BlockPos, BlockState> room() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(1, -31, 1), Blocks.CHEST.defaultBlockState());
        blocks.put(new BlockPos(2, -31, 1), Blocks.BARREL.defaultBlockState());
        blocks.put(new BlockPos(3, -31, 1), Blocks.HOPPER.defaultBlockState());
        blocks.put(new BlockPos(1, -30, 2), Blocks.CRAFTING_TABLE.defaultBlockState());
        blocks.put(new BlockPos(2, -30, 2), Blocks.BREWING_STAND.defaultBlockState());
        blocks.put(new BlockPos(3, -30, 2), Blocks.FURNACE.defaultBlockState());
        blocks.put(new BlockPos(1, -30, 3), Blocks.BED.white().defaultBlockState());
        blocks.put(new BlockPos(2, 70, 1), Blocks.STONE.defaultBlockState());
        return blocks;
    }

    private static List<BlockPos> storage(Map<BlockPos, BlockState> blocks) {
        return blocks.entrySet().stream().filter(e -> StashScoring.isStorage(e.getValue())).map(Map.Entry::getKey).toList();
    }

    private static List<StashScoring.Result> finish(List<BlockPos> positions,
            Function<BlockPos, BlockState> blocks, int budget) {
        AtomicInteger reads = new AtomicInteger();
        var scan = new StashScoring(positions, pos -> { reads.incrementAndGet(); return blocks.apply(pos); }, -64, 320);
        int ticks = 0;
        while (!scan.complete()) {
            int before = reads.get();
            int used = scan.advance(budget);
            assertTrue(used > 0 && used <= budget);
            assertTrue(reads.get() - before <= budget, "world reads exceeded the analysis budget");
            assertTrue(++ticks < 200_000, "scan stalled");
        }
        assertEquals(0, scan.advance(budget));
        return scan.results();
    }

    @Test void smallestBudgetStillFindsTheSameMixedStorageRoom() {
        var blocks = room();
        var slow = finish(storage(blocks), p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), 1);
        var fast = finish(storage(blocks), p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), 8192);
        assertEquals(fast, slow);
        assertEquals(1, slow.size());
        assertEquals(3, slow.getFirst().storage());
        assertTrue(slow.getFirst().score() >= 40);
        assertTrue(slow.getFirst().completeEnvironment());
    }

    @Test void donutModeRejectsLoneNaturalSpawnersUnlessExplicitlyOptedIn() {
        assertFalse(StashScoring.donutCandidate(0, 1, 10, false));
        assertFalse(StashScoring.donutCandidate(2, 1, 10, false));
        assertTrue(StashScoring.donutCandidate(0, 1, 10, true));
        assertTrue(StashScoring.donutCandidate(3, 1, 10, false));
        assertTrue(StashScoring.donutCandidate(10, 0, 10, false));
        assertFalse(StashScoring.donutCandidate(9, 0, 10, false));
        assertThrows(IllegalArgumentException.class, () -> StashScoring.donutCandidate(-1, 1, 10, false));
    }

    @Test void naturalDungeonChestsDoNotBecomeStrongCandidates() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(1, -31, 1), Blocks.CHEST.defaultBlockState());
        blocks.put(new BlockPos(4, -31, 1), Blocks.CHEST.defaultBlockState());
        blocks.put(new BlockPos(2, -31, 2), Blocks.SPAWNER.defaultBlockState());
        for (int x = -3; x <= 5; x++) blocks.put(new BlockPos(x, -31, 4), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        var result = finish(storage(blocks), p -> blocks.getOrDefault(p, Blocks.STONE.defaultBlockState()), 97);
        assertEquals(1, result.size());
        assertTrue(result.getFirst().score() < 25, "even HIGH sensitivity must not call this strong evidence");
    }

    @Test void clusteringCrossesNegativeCellAndChunkBoundariesAtExactlyEightBlocks() {
        var a = new BlockPos(-1, 17, 0);
        var b = new BlockPos(7, 17, 0);
        Map<BlockPos, BlockState> blocks = Map.of(a, Blocks.CHEST.defaultBlockState(), b, Blocks.CHEST.defaultBlockState());
        var result = finish(List.of(a, b), p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), 43);
        assertEquals(1, result.size());
        assertEquals(2, result.getFirst().storage());
        assertEquals(new BlockPos(3, 17, 0), result.getFirst().centre());
    }

    @Test void nineBlockGapDoesNotMergeOrInventASingleChestStash() {
        var a = new BlockPos(-1, 17, 0);
        var b = new BlockPos(8, 17, 0);
        assertTrue(finish(List.of(a, b), p -> Blocks.CHEST.defaultBlockState(), 1).isEmpty());
    }

    @Test void removedStorageIsRevalidatedBeforeLinking() {
        var a = new BlockPos(0, 17, 0);
        var b = new BlockPos(1, 17, 0);
        assertTrue(finish(List.of(a, b), p -> p.equals(a) ? Blocks.CHEST.defaultBlockState()
            : Blocks.AIR.defaultBlockState(), 1).isEmpty());
    }

    @Test void missingChunksStayUnknownRatherThanCountingAsAir() {
        var blocks = room();
        var result = finish(storage(blocks), p -> p.getX() < 0 ? null
            : blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), 4096);
        assertEquals(1, result.size());
        assertFalse(result.getFirst().completeEnvironment());
    }

    @Test void sparseRadiusWideInputDoesNotPerformAllPairsClustering() {
        List<BlockPos> positions = new ArrayList<>();
        for (int index = 0; index < 2000; index++) positions.add(new BlockPos(index * 32, 17, 0));
        var scan = new StashScoring(positions, p -> Blocks.CHEST.defaultBlockState(), -64, 320);
        int work = 0;
        while (!scan.complete()) {
            work += scan.advance(127);
            assertTrue(work < positions.size() * 40, "sparse input should require local bucket checks, not all pairs");
        }
        assertTrue(scan.results().isEmpty());
    }

    @Test void heightPenaltyDoesNotDependOnWhichChunkWasScannedFirst() {
        var low = new BlockPos(15, 8, 0);
        var high = new BlockPos(16, 11, 0);
        Map<BlockPos, BlockState> blocks = Map.of(low, Blocks.CHEST.defaultBlockState(), high, Blocks.BARREL.defaultBlockState());
        Function<BlockPos, BlockState> reader = p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
        assertEquals(finish(List.of(low, high), reader, 4096), finish(List.of(high, low), reader, 4096));
    }

    @Test void incrementalGridTestPreservesTheTwentyPointEvenSpacingPenalty() {
        var regular = List.of(new BlockPos(0, 17, 0), new BlockPos(2, 17, 2),
            new BlockPos(4, 17, 0), new BlockPos(6, 17, 2));
        var irregular = List.of(new BlockPos(0, 17, 0), new BlockPos(2, 17, 2),
            new BlockPos(4, 17, 0), new BlockPos(5, 17, 2));
        int grid = finish(regular, p -> regular.contains(p) ? Blocks.CHEST.defaultBlockState()
            : Blocks.AIR.defaultBlockState(), 4096).getFirst().score();
        int notGrid = finish(irregular, p -> irregular.contains(p) ? Blocks.CHEST.defaultBlockState()
            : Blocks.AIR.defaultBlockState(), 4096).getFirst().score();
        assertEquals(20, notGrid - grid);
    }

    @Test void removingStorageDuringEnvironmentSamplingSuppressesItsCandidate() {
        var blocks = room();
        var positions = storage(blocks);
        AtomicInteger reads = new AtomicInteger();
        var scan = new StashScoring(positions, p -> {
            reads.incrementAndGet();
            return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
        }, -64, 320);
        scan.advance(512);
        assertTrue(reads.get() > positions.size(), "linking finished and environment sampling started");
        assertFalse(scan.complete());
        assertTrue(scan.results().isEmpty(), "unfinished candidates must not be exposed");
        blocks.remove(positions.getFirst());
        int ticks = 0;
        while (!scan.complete()) {
            int before = reads.get();
            assertTrue(scan.advance(7) <= 7);
            assertTrue(reads.get() - before <= 7, "final validation must share the work budget");
            assertTrue(++ticks < 20_000);
        }
        assertTrue(scan.results().isEmpty(), "a partly removed cluster must be recollected, not published with cached counts");
    }

    @Test void finalValidationRechecksEarlierClustersAfterLaterEnvironmentWork() {
        var blocks = room();
        var firstRoom = new ArrayList<>(blocks.entrySet());
        firstRoom.forEach(entry -> blocks.put(entry.getKey().offset(64, 0, 0), entry.getValue()));
        var positions = storage(blocks);
        boolean[] laterEnvironment = { false };
        var scan = new StashScoring(positions, p -> {
            if (p.getX() >= 48 && !positions.contains(p)) laterEnvironment[0] = true;
            return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
        }, -64, 320);
        int ticks = 0;
        while (!laterEnvironment[0]) {
            scan.advance(97);
            assertTrue(++ticks < 1000);
        }
        blocks.put(new BlockPos(3, -31, 1), Blocks.CHEST.defaultBlockState());
        while (!scan.complete()) {
            scan.advance(97);
            assertTrue(++ticks < 2000);
        }
        assertEquals(1, scan.results().size(), "discard changed composition without discarding the unchanged room");
        assertTrue(scan.results().getFirst().centre().getX() >= 64);
    }

    private record SchedulingRun(int starts, int completedSweeps, int visited) {}

    @SuppressWarnings("unchecked")
    private static SchedulingRun schedule(boolean travelling, int ticks) throws Exception {
        var cursor = new ScanCursor();
        var schedule = new StashScoring.CollectionSchedule();
        var next = ScanCursor.class.getDeclaredMethod("nextPositions", Object.class, ChunkPos.class, int.class,
            long.class, int.class, long.class, Supplier.class, Predicate.class);
        next.setAccessible(true);
        Object world = new Object();
        var visited = new HashSet<ChunkPos>();
        int starts = 0, completed = 0, analysing = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (analysing > 0) { analysing--; continue; }
            // 32 blocks/second, 20 ticks/second: one chunk every ten ticks.
            ChunkPos player = new ChunkPos(travelling ? tick / 10 : 0, 0);
            Supplier<List<ChunkPos>> loaded = () -> {
                List<ChunkPos> chunks = new ArrayList<>();
                for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                    chunks.add(new ChunkPos(player.x() + x, player.z() + z));
                }
                return chunks;
            };
            var batch = (List<ChunkPos>) next.invoke(cursor, world, player, 6, 400L, 2,
                tick * 50L, loaded, (Predicate<ChunkPos>) pos -> true);
            assertTrue(batch.size() <= 2);
            assertTrue(batch.stream().allMatch(pos -> Math.abs(pos.x() - player.x()) <= 6 && Math.abs(pos.z() - player.z()) <= 6));
            visited.addAll(batch);
            if (!batch.isEmpty() && cursor.progress() >= 1.0) completed++;
            if (schedule.afterBatch(!batch.isEmpty(), cursor.progress())) {
                starts++;
                analysing = 5;
            }
        }
        return new SchedulingRun(starts, completed, visited.size());
    }

    @Test void travellingStartsAnalysisEvenWhenTheMovementResetPreventsEveryFullSweep() throws Exception {
        var run = schedule(true, 2400);
        assertEquals(0, run.completedSweeps(), "reproduces the moving-cursor starvation fixture");
        assertTrue(run.starts() >= 80, "periodic analysis must not wait for a full sweep");
        assertTrue(run.visited() > 169);
        System.out.println("Travel regression: 2400 ticks at 32 blocks/s, " + run.starts()
            + " analysis starts, " + run.completedSweeps() + " completed sweeps");
    }

    @Test void periodicAnalysisPreservesTheCursorSoStationaryOuterChunksAreEventuallyVisited() throws Exception {
        var run = schedule(false, 200);
        assertEquals(169, run.visited());
        assertTrue(run.completedSweeps() >= 1);
        assertTrue(run.starts() >= 5);
    }

    @Test void zeroBudgetDoesNoWorkAndEmptyInputTerminates() {
        AtomicInteger reads = new AtomicInteger();
        var scan = new StashScoring(List.of(), p -> { reads.incrementAndGet(); return Blocks.AIR.defaultBlockState(); }, -64, 320);
        assertEquals(0, scan.advance(0));
        assertFalse(scan.complete());
        scan.advance(10);
        assertTrue(scan.complete());
        assertEquals(0, reads.get());
    }
}
