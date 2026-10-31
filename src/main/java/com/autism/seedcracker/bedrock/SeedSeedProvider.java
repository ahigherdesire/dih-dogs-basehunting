package com.autism.seedcracker.bedrock;

import java.util.Set;
import java.util.OptionalLong;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.biome.BiomeManager;

import kaptainwutax.seedcrackerX.SeedCracker;

/** Reads local seed candidates and the current world's public biome-seed fingerprint. */
public final class SeedSeedProvider {
    private SeedSeedProvider() {}

    public static boolean matchesHashedSeed(long seed, long fingerprint) {
        return BiomeManager.obfuscateSeed(seed) == fingerprint;
    }

    public static OptionalLong hashedSeed(ClientLevel level) {
        if (level == null) return OptionalLong.empty();
        try {
            Field found = null;
            for (Field field : BiomeManager.class.getDeclaredFields()) {
                if (field.getType() == long.class && !Modifier.isStatic(field.getModifiers())) {
                    if (found != null) return OptionalLong.empty();
                    found = field;
                }
            }
            if (found == null) return OptionalLong.empty();
            found.setAccessible(true);
            return OptionalLong.of(found.getLong(level.getBiomeManager()));
        } catch (RuntimeException | ReflectiveOperationException unavailable) {
            return OptionalLong.empty();
        }
    }

    /** Returns the single cracked world seed, or null if not yet cracked. */
    static Long crackedSeed() {
        return crackedSeedPublic();
    }

    /** Public accessor for other modules (e.g. OreSim). */
    public static Long crackedSeedPublic() {
        try {
            SeedCracker sc = SeedCracker.get();
            if (sc == null || sc.getDataStorage() == null) return null;
            Set<Long> seeds = sc.getDataStorage().getTimeMachine().worldSeeds;
            if (seeds != null && seeds.size() == 1) {
                return seeds.iterator().next();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
