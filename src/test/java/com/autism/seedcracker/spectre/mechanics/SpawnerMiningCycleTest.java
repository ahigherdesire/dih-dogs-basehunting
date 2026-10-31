package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnerMiningCycleTest {
    @Test void shiftMiningWaitsForTheWholeStackAcrossSeveralSlotPackets() {
        var cycle = new SpawnerMiningCycle();
        cycle.begin(60, 10, 0, true);
        cycle.observe(64, 11, 2);
        assertEquals(4, cycle.collected());
        assertFalse(cycle.complete(false, false, 20));
        cycle.observe(124, 12, 3);
        assertEquals(64, cycle.collected());
        assertTrue(cycle.complete(false, false, 3));
        cycle.release(3);
        assertFalse(cycle.ready(8, false, false));
        assertFalse(cycle.ready(9, true, false));
        assertTrue(cycle.ready(9, false, false));
    }

    @Test void eachStackGetsAnIndependentReleaseAndFreshBaseline() {
        var cycle = new SpawnerMiningCycle();
        for (int stack = 0; stack < 3; stack++) {
            long tick = stack * 20L;
            cycle.begin(stack * 64, stack + 1, tick, true);
            cycle.observe((stack + 1) * 64, stack + 2, tick + 3);
            assertTrue(cycle.full());
            cycle.release(tick + 3);
            cycle.release(tick + 4);
            assertTrue(cycle.ready(tick + 9, false, false));
            assertEquals(64, cycle.collected());
            cycle.clear();
            assertFalse(cycle.active());
        }
    }

    @Test void partialFinalStackRequiresServerRemovalAndSettledInventory() {
        var cycle = new SpawnerMiningCycle();
        cycle.begin(64, 1, 0, true);
        cycle.observe(65, 2, 5);
        assertFalse(cycle.complete(false, false, 20));
        assertFalse(cycle.complete(true, false, 8));
        assertFalse(cycle.complete(true, true, 20));
        assertTrue(cycle.complete(true, false, 9));
        cycle.release(5);
        assertTrue(cycle.ready(11, false, false));
        assertEquals(1, cycle.collected());
    }

    @Test void nativeMiningCompletionAndDroppedItemsNeverCountAsPickup() {
        var cycle = new SpawnerMiningCycle();
        cycle.begin(0, 10, 0, true);
        cycle.release(5);
        assertFalse(cycle.complete(true, false, 20));
        assertFalse(cycle.ready(20, false, true));
        cycle.observe(64, 11, 20);
        assertFalse(cycle.ready(20, false, false));
        assertTrue(cycle.ready(24, false, false));
    }

    @Test void stalePacketsAndInventoryCorrectionsCannotInventCollectedStacks() {
        var cycle = new SpawnerMiningCycle();
        cycle.begin(64, 10, 0, true);
        cycle.observe(128, 9, 2);
        cycle.observe(128, 10, 3);
        assertEquals(0, cycle.collected());
        cycle.observe(128, 11, 4);
        cycle.observe(64, 12, 5);
        assertFalse(cycle.full());
        assertEquals(0, cycle.collected());
    }

    @Test void retryKeepsThePartialStackCountAndRequiresRelease() {
        var cycle = new SpawnerMiningCycle();
        cycle.begin(0, 0, 0, true);
        cycle.observe(32, 1, 3);
        cycle.release(4);
        assertTrue(cycle.ready(10, false, false));
        cycle.resume();
        assertFalse(cycle.releasing());
        assertEquals(32, cycle.collected());
        cycle.observe(64, 2, 13);
        assertTrue(cycle.full());
    }

    @Test void disabledOrNewSessionClearsPendingCollection() {
        var cycle = new SpawnerMiningCycle();
        cycle.begin(64, 10, 0, true);
        cycle.observe(128, 11, 1);
        cycle.release(1);
        cycle.clear();
        assertFalse(cycle.ready(100, false, false));
        assertEquals(0, cycle.collected());
        cycle.observe(999, 99, 101);
        assertFalse(cycle.full());
        cycle.begin(0, 0, 102, false);
        cycle.observe(1, 1, 103);
        assertTrue(cycle.full());
    }
}
