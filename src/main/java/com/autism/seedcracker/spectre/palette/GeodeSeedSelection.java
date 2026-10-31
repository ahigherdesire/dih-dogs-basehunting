package com.autism.seedcracker.spectre.palette;

import java.util.OptionalLong;
import com.autism.seedcracker.bedrock.SeedSeedProvider;

/** Seed fingerprints bind automatic/remembered values to the current world, not just its server address. */
public final class GeodeSeedSelection {
    public enum Source { NONE, MANUAL, CRACKED, SAVED }
    public enum Status { VERIFIED, UNVERIFIED_MANUAL, NO_SEED, INVALID_MANUAL, MISMATCH, WAITING_FOR_WORLD }
    public record Choice(Long seed, Source source, Status status, String remembered) {
        public boolean verified() { return status == Status.VERIFIED && seed != null; }
        public boolean usable() { return seed != null && (status == Status.VERIFIED || status == Status.UNVERIFIED_MANUAL); }
        @Override public String toString() { return source + ":" + status; }
    }
    private GeodeSeedSelection() {}

    public static Choice choose(String manual, Long cracked, String saved, OptionalLong fingerprint) {
        return choose(manual, cracked, saved, fingerprint, false);
    }

    public static Choice choose(String manual, Long cracked, String saved, OptionalLong fingerprint, boolean allowUnverifiedManual) {
        String text = manual == null ? "" : manual;
        if (text.length() > 64) return absent(Source.MANUAL, Status.INVALID_MANUAL);
        text = text.strip();
        Long entered = null;
        if (!text.isEmpty()) {
            try { entered = Long.parseLong(text); }
            catch (NumberFormatException invalid) { return absent(Source.MANUAL, Status.INVALID_MANUAL); }
        }
        if (fingerprint == null || fingerprint.isEmpty()) {
            if (entered != null && allowUnverifiedManual) return new Choice(entered, Source.MANUAL, Status.UNVERIFIED_MANUAL, "");
            return absent(entered == null ? Source.NONE : Source.MANUAL, Status.WAITING_FOR_WORLD);
        }
        long hash = fingerprint.getAsLong();
        if (entered != null) {
            if (matches(entered, hash)) return verified(entered, hash, Source.MANUAL);
            return allowUnverifiedManual ? new Choice(entered, Source.MANUAL, Status.UNVERIFIED_MANUAL, "") : absent(Source.MANUAL, Status.MISMATCH);
        }
        if (cracked != null && matches(cracked, hash)) return verified(cracked, hash, Source.CRACKED);
        Long remembered = rememberedSeed(saved, hash);
        if (remembered != null) return verified(remembered, hash, Source.SAVED);
        return absent(Source.NONE, cracked == null ? Status.NO_SEED : Status.MISMATCH);
    }

    private static Long rememberedSeed(String saved, long hash) {
        if (saved == null || saved.length() > 64) return null;
        int separator = saved.indexOf(':');
        if (separator < 1 || separator != saved.lastIndexOf(':')) return null;
        try {
            long previousHash = Long.parseLong(saved.substring(0, separator));
            long seed = Long.parseLong(saved.substring(separator + 1));
            return previousHash == hash && matches(seed, hash) ? seed : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    private static boolean matches(long seed, long hash) { return SeedSeedProvider.matchesHashedSeed(seed, hash); }
    private static Choice verified(long seed, long hash, Source source) {
        return new Choice(seed, source, Status.VERIFIED, hash + ":" + seed);
    }
    private static Choice absent(Source source, Status status) { return new Choice(null, source, status, ""); }
}
