package com.autism.seedcracker.spectre.chunks;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimeConfirmationTest {
    @Test void onePacketAndItsManySectionsCannotCorroborateEachOther() {
        var evidence = new PrimeConfirmation();
        assertFalse(evidence.observe(1, 1, 0));
        assertFalse(evidence.observe(1, 1, 1_000_000_000L));
        assertTrue(evidence.flagged(1_000_000_000L).isEmpty());
        assertTrue(evidence.observe(1, 2, 1_000_000_000L));
        assertEquals(Set.of(1L), evidence.flagged(1_000_000_000L));
        assertFalse(evidence.observe(1, 3, 2_000_000_000L));
    }
    @Test void sameTickBurstAndOldPacketIdsDoNotCountTwice() {
        var evidence = new PrimeConfirmation();
        evidence.observe(1, 1, 0);
        assertFalse(evidence.observe(1, 2, 1));
        assertFalse(evidence.observe(1, 2, 1_000_000_000L));
        assertFalse(evidence.observe(1, 1, 1_000_000_000L));
        assertTrue(evidence.flagged(1_000_000_000L).isEmpty());
        assertTrue(evidence.observe(1, 3, 1_000_000_000L));
    }
    @Test void spacedOutEventsAndExpiredMarkersAreNotPermanentProof() {
        var evidence = new PrimeConfirmation();
        evidence.observe(1, 1, 0);
        assertFalse(evidence.observe(1, 2, PrimeConfirmation.WINDOW + 1));
        assertTrue(evidence.flagged(PrimeConfirmation.WINDOW + 1).isEmpty());
        evidence.observe(1, 3, PrimeConfirmation.WINDOW + 1_000_000_000L);
        assertFalse(evidence.flagged(PrimeConfirmation.WINDOW + 1_000_000_000L).isEmpty());
        assertTrue(evidence.flagged(PrimeConfirmation.WINDOW + PrimeConfirmation.LIFETIME + 2_000_000_000L).isEmpty());
    }
    @Test void unloadWorldChangeAndOwnAreaRemoveOldCandidates() {
        var evidence = new PrimeConfirmation();
        evidence.observe(1, 1, 0); evidence.observe(1, 2, 1_000_000_000L);
        evidence.forget(1); assertTrue(evidence.flagged(2_000_000_000L).isEmpty());
        evidence.observe(1, 3, 2_000_000_000L); evidence.observe(1, 4, 3_000_000_000L);
        evidence.retain(ignored -> false); assertTrue(evidence.flagged(4_000_000_000L).isEmpty());
        evidence.observe(1, 5, 4_000_000_000L); evidence.observe(1, 6, 5_000_000_000L);
        evidence.clear(); assertTrue(evidence.flagged(6_000_000_000L).isEmpty());
    }
    @Test void candidateMemoryHasAHardLimit() {
        var evidence = new PrimeConfirmation();
        for (int i = 0; i < 300; i++) {
            evidence.observe(i, 1, 0); evidence.observe(i, 2, 1_000_000_000L);
        }
        assertEquals(256, evidence.flagged(1_000_000_000L).size());
        assertFalse(evidence.flagged(1_000_000_000L).contains(0L));
    }
}
