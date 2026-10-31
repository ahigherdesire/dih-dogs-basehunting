package com.autism.seedcracker.spectre.activity;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerLightTrackerTest {
    private static BitSet mask(int... bits) { BitSet mask = new BitSet(); for (int bit : bits) mask.set(bit); return mask; }
    private static byte[] lit() { byte[] sky = new byte[2048]; Arrays.fill(sky, (byte) 0xff); return sky; }
    private static void signal(PlayerLightTracker t, int x, int z, long now, boolean below) {
        t.accept(x, z, 24, -4, mask(1, 2, 8), mask(), List.of(new byte[2048], new byte[2048], lit()), below, true, now);
    }
    private static void normal(PlayerLightTracker t, int x, int z, long now) {
        t.accept(x, z, 24, -4, mask(8), mask(1, 2), List.of(lit()), false, true, now);
    }
    private static void context(PlayerLightTracker t, int x, int z) {
        normal(t, x - 1, z, 0); normal(t, x + 1, z, 0); normal(t, x, z - 1, 0); normal(t, x, z + 1, 0);
    }
    private static PlayerLightTracker confirmed(int x, int z) {
        var t = new PlayerLightTracker(); context(t, x, z);
        signal(t, x, z, 0, false); signal(t, x, z, 500_000_000L, false); return t;
    }

    @Test void onePacketAndImmediateDuplicatesNeverFlag() {
        var t = new PlayerLightTracker(); context(t, 10, 10);
        signal(t, 10, 10, 0, false);
        assertTrue(t.flagged(2_000_000_000L).isEmpty());
        signal(t, 10, 10, 1, false);
        assertTrue(t.flagged(2_000_000_000L).isEmpty());
        signal(t, 10, 10, 500_000_000L, false);
        assertTrue(t.flagged(900_000_000L).isEmpty());
        assertEquals(Set.of(QuantumRules.key(10, 10)), t.flagged(1_000_000_000L));
    }

    @Test void partialSkyAndOnlyOneDarkSectionAreNotCoherentEvidence() {
        var t = new PlayerLightTracker(); context(t, 10, 10);
        for (long now : new long[] {0, 500_000_000L}) {
            t.accept(10, 10, 24, -4, mask(1, 8), mask(), List.of(new byte[2048], lit()), false, true, now);
        }
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        for (long now : new long[] {0, 500_000_000L}) {
            t.accept(10, 10, 24, -4, mask(1, 2), mask(), List.of(new byte[2048], new byte[2048]), false, true, now);
        }
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
    }

    @Test void ordinaryEmptyMaskAndMalformedPacketsCannotFlag() {
        var t = confirmed(10, 10);
        t.accept(10, 10, 24, -4, mask(1), mask(1), List.of(new byte[2048]), false, false, 600_000_000L);
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        t.accept(10, 10, 24, -4, mask(1, 30), mask(), List.of(new byte[2048], lit()), false, false, 700_000_000L);
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        t.accept(10, 10, 24, -4, mask(1), mask(), List.of(new byte[2047]), false, false, 800_000_000L);
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        normal(t, 10, 10, 900_000_000L);
        assertTrue(t.flagged(2_000_000_000L).isEmpty());
    }

    @Test void clearedOrReloadedEvidenceDoesNotRemainSticky() {
        var t = confirmed(10, 10);
        assertFalse(t.flagged(1_000_000_000L).isEmpty());
        t.accept(10, 10, 24, -4, mask(), mask(1), List.of(), false, false, 2_000_000_000L);
        assertTrue(t.flagged(2_000_000_000L).isEmpty());
        t = confirmed(10, 10);
        t.accept(10, 10, 24, -4, mask(8), mask(), List.of(lit()), false, true, 2_000_000_000L);
        assertTrue(t.flagged(2_000_000_000L).isEmpty(), "A new full chunk cannot retain omitted old layers");
    }

    @Test void uniformServerEncodingAndMissingNeighbourContextAreSuppressed() {
        var t = new PlayerLightTracker();
        signal(t, 10, 10, 0, false); signal(t, 10, 10, 500_000_000L, false);
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        for (int x = 8; x <= 12; x++) for (int z = 8; z <= 12; z++) {
            signal(t, x, z, 0, false); signal(t, x, z, 500_000_000L, false);
        }
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
    }

    @Test void enteringTheAreaSuppressesExistingAndLaterOwnSignalsAcrossReload() {
        var t = confirmed(1, 1);
        t.updateEntry(0, 0, ignored -> true);
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        t.forget(QuantumRules.key(1, 1));
        signal(t, 1, 1, 2_000_000_000L, false); signal(t, 1, 1, 3_000_000_000L, false);
        assertTrue(t.flagged(4_000_000_000L).isEmpty());
        t.clear(); context(t, 1, 1);
        signal(t, 1, 1, 0, false); signal(t, 1, 1, 500_000_000L, false);
        assertFalse(t.flagged(1_000_000_000L).isEmpty());
    }

    @Test void undergroundEvidenceNeedsFreshAboveGroundConfirmation() {
        var t = new PlayerLightTracker(); context(t, 10, 10);
        signal(t, 10, 10, 0, true); signal(t, 10, 10, 500_000_000L, true);
        assertTrue(t.flagged(1_000_000_000L).isEmpty());
        signal(t, 10, 10, 1_000_000_000L, false);
        assertTrue(t.flagged(2_000_000_000L).isEmpty());
    }

    @Test void expiryUnloadAndHardCapacityPreventPermanentMarkers() {
        var t = confirmed(-10, -10);
        assertFalse(t.flagged(1_000_000_000L).isEmpty());
        assertTrue(t.flagged(PlayerLightTracker.MAX_AGE_NANOS + 1_000_000_000L).isEmpty());
        t.forget(QuantumRules.key(-10, -10)); assertTrue(t.flagged(1_000_000_000L).isEmpty());
        for (int i = 0; i <= PlayerLightTracker.MAX_CHUNKS; i++) normal(t, i, 20, 0);
        assertEquals(PlayerLightTracker.MAX_CHUNKS, t.size());
        t.retain(ignored -> false); assertEquals(0, t.size());
    }

    @Test void unrelatedSkyPacketsDoNotConfirmTheDarkEvidence() {
        var t = new PlayerLightTracker(); context(t, 10, 10);
        signal(t, 10, 10, 0, false);
        t.accept(10, 10, 24, -4, mask(8), mask(), List.of(lit()), false, false, 500_000_000L);
        assertTrue(t.flagged(2_000_000_000L).isEmpty());
    }
}
