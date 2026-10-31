package com.autism.seedcracker.spectre.palette;

import java.util.OptionalLong;
import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeodeSeedSelectionTest {
    private static OptionalLong fingerprint(long seed) { return OptionalLong.of(BiomeManager.obfuscateSeed(seed)); }
    private static String saved(long seed) { return BiomeManager.obfuscateSeed(seed) + ":" + seed; }

    @Test void manualSeedWinsButCannotSilentlyFallBackWhenWrong() {
        var valid = GeodeSeedSelection.choose("  -12345  ", 9L, saved(9), fingerprint(-12345));
        assertTrue(valid.verified());
        assertEquals(-12345L, valid.seed());
        assertEquals(GeodeSeedSelection.Source.MANUAL, valid.source());
        var wrong = GeodeSeedSelection.choose("12345", 9L, saved(9), fingerprint(9));
        assertEquals(GeodeSeedSelection.Status.MISMATCH, wrong.status());
        assertFalse(wrong.usable());
        assertTrue(wrong.remembered().isEmpty());
    }

    @Test void automaticAndRememberedSeedsMustMatchTheCurrentWorld() {
        var cracked = GeodeSeedSelection.choose("", 12345L, saved(12345), fingerprint(12345));
        assertEquals(GeodeSeedSelection.Source.CRACKED, cracked.source());
        assertTrue(cracked.verified());
        var remembered = GeodeSeedSelection.choose("", 9L, saved(12345), fingerprint(12345));
        assertEquals(GeodeSeedSelection.Source.SAVED, remembered.source());
        assertEquals(12345L, remembered.seed());
        assertFalse(GeodeSeedSelection.choose("", null, saved(12345), fingerprint(9)).usable());
        assertFalse(GeodeSeedSelection.choose("", 12345L, saved(12345), OptionalLong.empty()).usable());
    }

    @Test void storedFingerprintAloneCannotAuthenticateTheWrongSeed() {
        String forged = BiomeManager.obfuscateSeed(9) + ":12345";
        assertFalse(GeodeSeedSelection.choose("", null, forged, fingerprint(9)).usable());
        for (String invalid : new String[]{"", "broken", "1:", ":1", "1:2:3", "x".repeat(100)})
            assertFalse(GeodeSeedSelection.choose("", null, invalid, fingerprint(9)).usable());
    }

    @Test void zeroAndBothSignedLongExtremesAreValidSeeds() {
        for (long seed : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE}) {
            var choice = GeodeSeedSelection.choose(Long.toString(seed), null, "", fingerprint(seed));
            assertTrue(choice.verified());
            assertEquals(seed, choice.seed());
            assertTrue(GeodeSeedSelection.choose("", null, choice.remembered(), fingerprint(seed)).verified());
        }
    }

    @Test void invalidInputDoesNotBecomeAnAccidentalStringHashSeed() {
        for (String value : new String[]{"Seed: 12345", "world-name", "9223372036854775808", "0".repeat(65)}) {
            var choice = GeodeSeedSelection.choose(value, 12345L, saved(12345), fingerprint(12345));
            assertEquals(GeodeSeedSelection.Status.INVALID_MANUAL, choice.status());
            assertFalse(choice.usable());
        }
    }

    @Test void manualOverrideIsExplicitNeverVerifiedAndNeverSavedAsWorldBound() {
        assertFalse(GeodeSeedSelection.choose("12345", null, "", OptionalLong.empty()).usable());
        for (OptionalLong hash : new OptionalLong[]{OptionalLong.empty(), fingerprint(9)}) {
            var choice = GeodeSeedSelection.choose("12345", null, "", hash, true);
            assertTrue(choice.usable());
            assertFalse(choice.verified());
            assertEquals(GeodeSeedSelection.Status.UNVERIFIED_MANUAL, choice.status());
            assertTrue(choice.remembered().isEmpty());
        }
        assertFalse(GeodeSeedSelection.choose("", 12345L, saved(12345), fingerprint(9), true).usable());
        assertFalse(GeodeSeedSelection.choose("", 12345L, saved(12345), OptionalLong.empty(), true).usable());
    }

    @Test void diagnosticsNeverPrintTheSeedOrStoredFingerprint() {
        var choice = GeodeSeedSelection.choose("12345", null, "", fingerprint(12345));
        assertFalse(choice.toString().contains("12345"));
        assertFalse(choice.toString().contains(Long.toString(BiomeManager.obfuscateSeed(12345))));
    }
}
