package com.autism.seedcracker.finder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanCursorTest {
    @Test void startsImmediatelyNearestFirstAndHonorsTheRescanDelay() {
        var scan = new ScanCursor(); var world = new Object(); var centre = new ChunkPos(-4, -3);
        var near = new ChunkPos(-3, -3); var far = new ChunkPos(-1, -3);
        var loads = new AtomicInteger();
        java.util.function.Supplier<List<ChunkPos>> loaded = () -> { loads.incrementAndGet(); return List.of(far, near, centre); };
        assertEquals(List.of(centre), scan.nextPositions(world, centre, 4, 400, 1, 0, loaded, p -> true));
        assertEquals(List.of(near, far), scan.nextPositions(world, centre, 4, 400, 2, 1, loaded, p -> true));
        assertTrue(scan.nextPositions(world, centre, 4, 400, 2, 399, loaded, p -> true).isEmpty());
        assertEquals(1, loads.get());
        assertEquals(List.of(centre), scan.nextPositions(world, centre, 4, 400, 1, 400, loaded, p -> true));
        assertEquals(2, loads.get());
    }

    @Test void worldRadiusAndTeleportChangesDiscardOldPasses() {
        var scan = new ScanCursor(); var world = new Object(); var centre = new ChunkPos(0, 0);
        var old = List.of(centre, new ChunkPos(1, 0));
        scan.nextPositions(world, centre, 4, 1000, 1, 0, () -> old, p -> true);
        var fresh = new ChunkPos(0, 1);
        assertEquals(List.of(fresh), scan.nextPositions(new Object(), centre, 4, 1000, 1, 1, () -> List.of(fresh), p -> true));
        assertEquals(List.of(centre), scan.nextPositions(world, centre, 1, 1000, 1, 2, () -> old, p -> true));
        var distant = new ChunkPos(1000, -1000);
        assertEquals(List.of(distant), scan.nextPositions(world, distant, 1, 1000, 1, 3, () -> List.of(distant), p -> true));
    }

    @Test void unloadedOrOutOfRangeQueuedChunksAreNeverReturned() {
        var scan = new ScanCursor(); var world = new Object(); var centre = new ChunkPos(0, 0);
        var queued = List.of(centre, new ChunkPos(-2, 0), new ChunkPos(2, 0));
        scan.nextPositions(world, centre, 2, 1000, 1, 0, () -> queued, p -> true);
        assertTrue(scan.nextPositions(world, new ChunkPos(1, 0), 2, 1000, 10, 1, () -> queued,
            p -> p.x() != 2).isEmpty());
        assertEquals(1, scan.progress());
    }

    @Test void massUnloadHasBoundedChecksAndStillMakesProgress() {
        var scan = new ScanCursor(); var world = new Object(); var centre = new ChunkPos(0, 0);
        var positions = new ArrayList<ChunkPos>();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) positions.add(new ChunkPos(x, z));
        scan.nextPositions(world, centre, 32, 1000, 1, 0, () -> positions, p -> true);
        var checks = new AtomicInteger();
        double before = scan.progress();
        assertTrue(scan.nextPositions(world, centre, 32, 1000, 1, 1, () -> positions,
            p -> { checks.incrementAndGet(); return false; }).isEmpty());
        assertTrue(checks.get() <= 64);
        assertTrue(scan.progress() > before);
    }

    @Test void raisedGrowthRadiusAndBudgetRemainSupported() {
        var scan = new ScanCursor(); var positions = new ArrayList<ChunkPos>();
        for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) positions.add(new ChunkPos(x, z));
        assertEquals(128, scan.nextPositions(new Object(), new ChunkPos(0, 0), 32, 400, 128, 0, () -> positions, p -> true).size());
        scan.reset(); assertEquals(1, scan.progress());
    }

    @Test void nullWorldAndClockRollbackDoNotKeepStaleWork() {
        var scan = new ScanCursor(); var world = new Object(); var pos = new ChunkPos(0, 0);
        scan.nextPositions(world, pos, 1, 400, 1, 500, () -> List.of(pos), p -> true);
        assertEquals(List.of(pos), scan.nextPositions(world, pos, 1, 400, 1, 100, () -> List.of(pos), p -> true));
        assertTrue(scan.nextPositions(null, pos, 1, 400, 1, 101, () -> fail("No loading without a world"), p -> true).isEmpty());
    }

    @Test void sweepGenerationChangesOnRefreshNotWhileWaitingForTheRescanDelay() {
        var scan = new ScanCursor(); var world = new Object(); var pos = new ChunkPos(0, 0);
        long initial = scan.generation();
        scan.nextPositions(world, pos, 4, 400, 1, 0, () -> List.of(pos), p -> true);
        long first = scan.generation(); assertNotEquals(initial, first);
        scan.nextPositions(world, pos, 4, 400, 1, 1, () -> List.of(pos), p -> true);
        assertEquals(first, scan.generation());
        scan.nextPositions(world, pos, 4, 400, 1, 400, () -> List.of(pos), p -> true);
        assertNotEquals(first, scan.generation());
    }

    @Test void nativeLookupCannotCreateOrForceLoadChunks() throws Exception {
        try (var input = getClass().getResourceAsStream("/com/autism/seedcracker/finder/ChunkScanHelper.class")) {
            assertNotNull(input);
            var model = java.lang.classfile.ClassFile.of().parse(input.readAllBytes());
            var method = model.methods().stream().filter(m -> m.methodName().equalsString("loadedChunksAround")).findFirst().orElseThrow();
            boolean nonLoading = false;
            for (var code : method.code().orElseThrow()) if (code instanceof java.lang.classfile.instruction.InvokeInstruction call) {
                assertFalse(call.name().equalsString("getChunk"));
                nonLoading |= call.name().equalsString("getChunkNow");
            }
            assertTrue(nonLoading);
        }
    }
}
