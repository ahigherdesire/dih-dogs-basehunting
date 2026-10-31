/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnerStorageProofTest {
    @Test void disappearanceWithoutConservedTransferNeverCompletesBatch() {
        var proof = new SpawnerStorageProof(); proof.begin(64);
        assertFalse(proof.complete()); proof.transferred(4); assertFalse(proof.complete());
        proof.transferred(60); assertTrue(proof.complete());
    }
    @Test void newBatchAndCancellationCannotReuseOldSuccess() {
        var proof = new SpawnerStorageProof(); proof.begin(1); proof.transferred(1); assertTrue(proof.complete());
        proof.begin(12); assertFalse(proof.complete()); proof.clear(); assertFalse(proof.complete());
        assertThrows(IllegalStateException.class, () -> proof.transferred(1));
        assertThrows(IllegalArgumentException.class, () -> proof.begin(0));
    }
    @Test void fullShulkerSealsOnlyItsConfirmedContentsBeforeRollingOver() {
        var proof = new SpawnerStorageProof();
        proof.begin(30 * 64);
        for (int slot = 0; slot < 27; slot++) proof.transferred(64);
        assertFalse(proof.complete());
        assertEquals(27 * 64, proof.confirmed());
        assertFalse(proof.sealPackedContents(26 * 64));
        assertFalse(proof.sealPackedContents(28 * 64));
        assertTrue(proof.sealPackedContents(27 * 64));
        assertTrue(proof.complete());
        proof.begin(1);
        assertFalse(proof.complete());
        proof.transferred(1);
        assertTrue(proof.complete());
        proof.begin(3 * 64);
        assertEquals(0, proof.confirmed());
        proof.transferred(3 * 64);
        assertTrue(proof.sealPackedContents(3 * 64));
    }
    @Test void unconfirmedOrEmptyShulkerCannotBeRecovered() {
        var proof = new SpawnerStorageProof();
        proof.begin(64);
        assertFalse(proof.sealPackedContents(64));
        assertFalse(proof.sealPackedContents(0));
        proof.transferred(64);
        proof.clear();
        assertFalse(proof.sealPackedContents(64));
    }
    @Test void placingRequiresBothServerBlockAndInventoryEvidenceForTheExactItem() {
        assertTrue(SpawnerStorageProof.placed(true, true, true, 1, 0));
        assertTrue(SpawnerStorageProof.placed(true, true, true, 4, 3));
        assertFalse(SpawnerStorageProof.placed(false, true, true, 1, 0));
        assertFalse(SpawnerStorageProof.placed(true, false, true, 1, 0));
        assertFalse(SpawnerStorageProof.placed(true, true, false, 4, 3));
        assertFalse(SpawnerStorageProof.placed(true, true, true, 1, 1));
        assertFalse(SpawnerStorageProof.placed(true, true, true, 0, -1));
    }
}
