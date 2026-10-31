// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.chunks.seedscan;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Private seed-owned native state: no live level, global registry edits, or world-save access. */
final class NativeGeodeWorld263 {
    static final int MIN_Y = -64;
    static final int HEIGHT = 384;
    static final int MAX_Y = MIN_Y + HEIGHT - 1;
    final long seed;
    final RegistryAccess.Frozen registries;
    final NoiseBasedChunkGenerator generator;
    final RandomState randomState;
    final MultiNoiseBiomeSource biomeSource;
    final List<FeatureSorter.StepFeatureData> featureSteps;
    final PalettedContainerFactory containers;
    final BiomeManager biomeManager;
    private final LevelHeightAccessor height = LevelHeightAccessor.create(MIN_Y, HEIGHT);

    NativeGeodeWorld263(long seed) {
        this.seed = seed;
        GeodePredictor.interrupted();
        var builtins = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            registries = RegistryDataLoader.load(resources, builtins.listRegistries().toList(),
                RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
        }
        GeodePredictor.interrupted();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
        biomeSource = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        generator = new NoiseBasedChunkGenerator(biomeSource, settings);
        if (generator.getMinY() != MIN_Y || generator.getGenDepth() != HEIGHT) {
            throw new UnsupportedOperationException("Unsupported default 26.3 overworld height");
        }
        featureSteps = List.copyOf(FeatureSorter.buildFeaturesPerStep(List.copyOf(biomeSource.possibleBiomes()),
            b -> generator.getBiomeGenerationSettings(b).features(), true));
        containers = PalettedContainerFactory.create(registries);
        var resolver = biomeSource.createUncachedResolver(randomState);
        // Match ProtoChunk's clamping of biome quart Y, including surface lookups beyond world height.
        biomeManager = new BiomeManager((x, y, z) -> resolver.getNoiseBiome(x, Math.clamp(y, MIN_Y >> 2, MAX_Y >> 2), z),
            BiomeManager.obfuscateSeed(seed));
        GeodePredictor.interrupted();
    }

    Holder<Biome> biomeAt(BlockPos pos) {
        return biomeManager.getBiome(pos);
    }

    ProtoChunk newChunk(ChunkPos pos) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, height, containers, null);
    }

    ProtoChunk generateTerrain(int chunkX, int chunkZ) {
        GeodePredictor.interrupted();
        ProtoChunk chunk = newChunk(new ChunkPos(chunkX, chunkZ));
        // This model intentionally has no structure starts/references. Only this actual chunk is exposed.
        chunk.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
        var structures = structuresFor(chunk);
        if (generator.createBiomes(randomState, Blender.empty(), structures, chunk).join() != chunk) {
            throw new UnsupportedOperationException("Native biome generation returned another chunk");
        }
        GeodePredictor.interrupted();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        // 26.3's public entrypoint runs noise, materials/surface, aquifers AND carvers; null region
        // selects its native uncached carver-biome path, not missing terrain or skipped carving.
        if (generator.buildTerrain(chunk, Blender.empty(), randomState, structures, biomeManager, null, Set.of()).join() != chunk) {
            throw new UnsupportedOperationException("Native terrain generation returned another chunk");
        }
        GeodePredictor.interrupted();
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        return chunk;
    }

    StructureManager structuresFor(ProtoChunk chunk) {
        LevelAccessor level = (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
            new Class<?>[]{LevelAccessor.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "NativeGeodeWorld263.structureMetadata";
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                }
                if (method.getName().equals("getChunk") && args.length == 3
                    && args[0].equals(chunk.getPos().x()) && args[1].equals(chunk.getPos().z())
                    && args[2] == ChunkStatus.STRUCTURE_REFERENCES && chunk.getAllReferences().isEmpty()
                    && chunk.getAllStarts().isEmpty()) {
                    return chunk;
                }
                throw new UnsupportedOperationException("Structure-free metadata adapter: " + method);
            });
        return new StructureManager(level, new WorldOptions(seed, false, false), null);
    }

    void endOperation() {
        // Native density pools have their own fixed cap/age policy; all per-operation chunks live in Region.
        randomState.garbageCollect();
    }
}
