package com.autism.seedcracker.finder.activity;

import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityFlagsTest {
    private final ChunkPos first = new ChunkPos(1, 2);
    private final ChunkPos second = new ChunkPos(-1, -2);

    @Test void negativeScanRearmsAnAlertAtTheSameCoordinates() {
        var flags = new ActivityFlags();
        flags.record(first, true);
        assertTrue(flags.announce(first, 1));
        assertFalse(flags.announce(first, 1));
        flags.record(first, false);
        assertTrue(flags.current().isEmpty());
        flags.record(first, true);
        assertTrue(flags.announce(first, 1));
    }

    @Test void notificationCapDoesNotConsumeAnUndeliveredAlert() {
        var flags = new ActivityFlags();
        flags.record(first, true);
        flags.record(second, true);
        assertTrue(flags.announce(first, 1));
        assertFalse(flags.announce(second, 0));
        assertTrue(flags.announce(second, 1));
    }

    @Test void unloadAndWorldClearWithdrawMarkersAndRearm() {
        var flags = new ActivityFlags();
        flags.record(first, true);
        flags.announce(first, 1);
        flags.retain(pos -> false);
        assertTrue(flags.current().isEmpty());
        flags.record(first, true);
        assertTrue(flags.announce(first, 1));
        flags.clear();
        assertTrue(flags.current().isEmpty());
        flags.record(first, true);
        assertTrue(flags.announce(first, 1));
    }

    @Test void completedScoringPassOnlyRearmsDisappearedLeads() {
        var flags = new ActivityFlags();
        flags.replace(Set.of(first, second));
        flags.announce(first, 1);
        flags.announce(second, 1);
        flags.replace(Set.of(second));
        assertFalse(flags.announce(second, 1));
        flags.replace(Set.of(first, second));
        assertTrue(flags.announce(first, 1));
        assertFalse(flags.announce(second, 1));
    }
}
