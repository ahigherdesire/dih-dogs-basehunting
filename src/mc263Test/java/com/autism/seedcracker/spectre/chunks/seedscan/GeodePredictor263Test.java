// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.chunks.seedscan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GeodePredictor263Test {
    private static NativeGeodeWorld263 referenceWorld;
    private static List<PlacementModifier> nativeModifiers;
    private static Feature nativeGeode;

    @BeforeAll static void bootstrap() throws Exception {
        Native263TestSupport.bootstrap();
        referenceWorld = new NativeGeodeWorld263(12345L);
        var modifiers = new ArrayList<PlacementModifier>();
        var placement = resource("placed_feature").getAsJsonArray("placement");
        // Every native overworld biome carries the geode. Omitting only BiomeFilter lets a standalone
        // native PlacedFeature use the resource-decoded configured feature without changing biome registries.
        for (int i = 0; i < 3; i++) modifiers.add(PlacementModifier.CODEC.parse(JsonOps.INSTANCE, placement.get(i)).getOrThrow());
        assertEquals("minecraft:biome", placement.get(3).getAsJsonObject().get("type").getAsString());
        nativeModifiers = List.copyOf(modifiers);
        var lookups = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.concat(
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).listRegistries(), referenceWorld.registries.listRegistries()));
        nativeGeode = Feature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, lookups), resource("feature")).getOrThrow();
        assertInstanceOf(GeodeFeature.class, nativeGeode);
    }

    @AfterAll static void cleanup() { if (referenceWorld != null) referenceWorld.endOperation(); referenceWorld = null; }

    @Test void originsAndDecorationRngMatchIndependentVanillaCalls() {
        int edge = GeodePredictor.MAX_CHUNK_COORD;
        int[][] coordinates = {{0, 0}, {-8, 1}, {-1, -1}, {0, 1}, {42, -91},
            {edge, edge}, {-edge, -edge}, {-edge, edge}, {edge, -edge}};
        for (long seed : new long[]{12345, 0, Long.MIN_VALUE, Long.MAX_VALUE}) {
            try (var predictor = new GeodePredictor(seed)) {
                for (int[] coordinate : coordinates) {
                    int x = coordinate[0], z = coordinate[1];
                    assertEquals(nativeAttempt(seed, x, z), predictor.candidate(x, z).map(GeodePredictor.Candidate::origin));
                    assertEquals(nativeAttempt(seed, x, z), predictor.candidate(x, z).map(GeodePredictor.Candidate::origin),
                        "repeat request must start with the same native decoration RNG");
                }
                var expected = new ArrayList<BlockPos>();
                for (int x = -edge; x <= -edge + 7; x++) for (int z = edge - 7; z <= edge; z++) {
                    nativeAttempt(seed, x, z).ifPresent(expected::add);
                }
                var actual = predictor.candidates(-edge, edge - 7, -edge + 7, edge);
                assertEquals(expected, actual.stream().map(GeodePredictor.Candidate::origin).toList(), "including every no-attempt chunk");
                for (var candidate : actual) {
                    assertEquals(2, candidate.decorationStep());
                    assertEquals(2, candidate.featureIndex());
                    assertTrue(candidate.possibleBounds().contains(candidate.origin()));
                }
            }
        }
        assertEquals(Optional.of(new BlockPos(-114, -3, 30)), nativeAttempt(12345, -8, 1));
    }

    @Test void absenceIsNotAConfirmedTerrainRejection() {
        try (var predictor = new GeodePredictor(12345)) {
            assertTrue(nativeAttempt(12345, 0, 0).isEmpty());
            assertTrue(predictor.candidate(0, 0).isEmpty());
            assertTrue(predictor.reconstruct(0, 0, p -> { fail("no attempt must not generate terrain"); return null; }).isEmpty());
            var attempt = predictor.candidate(-8, 1).orElseThrow();
            assertEquals(GeodePredictor.Status.PLACEMENT_ATTEMPT, attempt.status());
            for (Block invalid : List.of(Blocks.AIR, Blocks.WATER, Blocks.LAVA, Blocks.BEDROCK)) {
                var result = predictor.reconstruct(-8, 1, p -> uniform(p, invalid.defaultBlockState())).orElseThrow();
                assertEquals(GeodePredictor.Status.TERRAIN_REJECTED, result.status());
                assertEquals(attempt, result.candidate());
                assertTrue(result.blocks().isEmpty());
                assertTrue(result.bounds().isEmpty());
            }
        }
    }

    @Test void fullNativePlacedFeatureMatchesIncludingRandomConsumptionAndBounds() throws Exception {
        try (var predictor = new GeodePredictor(12345)) {
            Function<ChunkPos, ProtoChunk> solid = p -> uniform(p, Blocks.STONE.defaultBlockState());
            String[] nativeDigests = {
                "7a7a8bdd9da9a79dfd042372bbe2f1dcad9865c408bce93544096dc3e3d8f46a",
                "66d700d485fc4cd5b96d649ae584771fc9add96c89bf564099256ef92b8d9b31",
                "9211a802152fef65664f539dac88cadcfe9cbd80e3f21711a3863677cac1e012"
            };
            int fixture = 0;
            for (int[] coordinate : new int[][]{{-8, 1}, {0, 1}, {0, -8}}) {
                int x = coordinate[0], z = coordinate[1];
                Map<BlockPos, BlockState> expected = nativeBlocks(x, z, solid);
                var actual = predictor.reconstruct(x, z, solid).orElseThrow();
                assertEquals(expected, actual.blocks());
                assertEquals(GeodePredictor.Status.GENERATED_IN_SIMULATION, actual.status());
                assertFalse(actual.amethyst().isEmpty());
                assertFalse(actual.budding().isEmpty());
                assertTrue(actual.blocks().size() > actual.amethyst().size() + actual.budding().size());
                actual.blocks().forEach((p, state) -> {
                    assertTrue(actual.bounds().orElseThrow().contains(p));
                    assertTrue(actual.candidate().possibleBounds().contains(p));
                    assertFalse(state.isAir());
                    assertEquals(state.is(Blocks.AMETHYST_BLOCK), actual.amethyst().contains(p));
                    assertEquals(state.is(Blocks.BUDDING_AMETHYST), actual.budding().contains(p));
                });
                assertThrows(UnsupportedOperationException.class, () -> actual.blocks().clear());
                assertThrows(UnsupportedOperationException.class, () -> actual.amethyst().clear());
                assertThrows(UnsupportedOperationException.class, () -> actual.budding().clear());
                assertTrue(actual.blocks().keySet().stream().noneMatch(BlockPos.MutableBlockPos.class::isInstance));
                assertEquals(nativeDigests[fixture++], digest(expected), "independent native placed-feature fixture");
            }
        }
    }

    @Test void noiseSurfaceCarversProduceReproducibleBoundedNativeShapes() throws Exception {
        var cache = new HashMap<ChunkPos, ProtoChunk>();
        Function<ChunkPos, ProtoChunk> terrain = p -> cache.computeIfAbsent(p, key -> referenceWorld.generateTerrain(key.x(), key.z()));
        try (var predictor = new GeodePredictor(12345)) {
            var expected = nativeBlocks(-8, 1, terrain);
            var actual = predictor.reconstruct(-8, 1).orElseThrow();
            assertEquals(expected, actual.blocks());
            assertTrue(actual.terrainChunks() > 0 && actual.terrainChunks() <= GeodePredictor.MAX_TERRAIN_CHUNKS);
            assertEquals(951, actual.blocks().size());
            assertEquals(237, actual.amethyst().size());
            assertEquals(28, actual.budding().size());
            assertEquals(new GeodePredictor.Bounds(-114, -4, 29, -102, 9, 42), actual.bounds().orElseThrow());
            assertEquals("7a7a8bdd9da9a79dfd042372bbe2f1dcad9865c408bce93544096dc3e3d8f46a", digest(expected));
        } finally { cache.clear(); referenceWorld.endOperation(); }
    }

    @Test void unknownOrIncompleteTerrainIsNeverAir() {
        try (var predictor = new GeodePredictor(12345)) {
            assertThrows(UnsupportedOperationException.class, () -> predictor.reconstruct(-8, 1, p -> null));
            assertThrows(UnsupportedOperationException.class, () -> predictor.reconstruct(-8, 1,
                p -> uniform(new ChunkPos(p.x() + 1, p.z()), Blocks.STONE.defaultBlockState())));
            assertThrows(UnsupportedOperationException.class, () -> predictor.reconstruct(-8, 1, p -> {
                var chunk = uniform(p, Blocks.STONE.defaultBlockState());
                chunk.setPersistedStatus(ChunkStatus.EMPTY);
                return chunk;
            }));
            assertThrows(UnsupportedOperationException.class, () -> predictor.reconstruct(-8, 1, p ->
                new ProtoChunk(p, UpgradeData.EMPTY, LevelHeightAccessor.create(0, 256), containers(Blocks.STONE.defaultBlockState()), null)));
            assertEquals(GeodePredictor.Status.GENERATED_IN_SIMULATION,
                predictor.reconstruct(-8, 1, p -> uniform(p, Blocks.STONE.defaultBlockState())).orElseThrow().status());
        }
    }

    @Test void fluidLevelsBudPropertiesAndUnknownNativeCallsArePreservedOrRejected() {
        var flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 7);
        var region = region(p -> uniform(p, flowing));
        assertEquals(flowing, region.read(region.origin));
        assertEquals(flowing.getFluidState(), region.level.getFluidState(region.origin));
        var bud = Blocks.LARGE_AMETHYST_BUD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH)
            .setValue(BlockStateProperties.WATERLOGGED, true);
        var mutable = new BlockPos.MutableBlockPos().set(region.origin);
        assertTrue(region.level.setBlock(mutable, bud, 2));
        mutable.move(Direction.UP);
        assertEquals(bud, region.read(region.origin));
        assertTrue(region.level.getFluidState(region.origin).isSource());
        assertFalse(region.writes.containsKey(mutable));
        assertThrows(UnsupportedOperationException.class, () -> region.level.getSeaLevel());
        assertThrows(UnsupportedOperationException.class, () -> region.level.setBlock(region.origin, bud, 3));
        region.origin = new BlockPos(0, -58, 0);
        BlockPos outsideHeight = new BlockPos(0, -65, 0);
        assertEquals(uniform(new ChunkPos(0, 0), flowing).getBlockState(outsideHeight), region.read(outsideHeight));
        assertFalse(region.write(outsideHeight, bud));
    }

    @Test void nativeFluidTickRequestsAreObservedButNotExecuted() {
        BlockPos origin = nativeAttempt(12345, -8, 1).orElseThrow();
        Function<ChunkPos, ProtoChunk> wet = p -> {
            var chunk = uniform(p, Blocks.WATER.defaultBlockState());
            // Keep the native distribution-point sampling cube solid; surround it with real water states.
            for (int x = 4; x <= 6; x++) for (int y = 4; y <= 6; y++) for (int z = 4; z <= 6; z++) {
                BlockPos point = origin.offset(x, y, z);
                if (point.getX() >> 4 == p.x() && point.getZ() >> 4 == p.z()) {
                    chunk.setBlockState(point, Blocks.STONE.defaultBlockState(), 2);
                }
            }
            return chunk;
        };
        var nativeRegion = new Native263TestSupport.ReferenceRegion(referenceWorld, 12345L, -8, 1, wet);
        assertTrue(new PlacedFeature(Holder.direct(nativeGeode), nativeModifiers).place(nativeRegion.level,
            referenceWorld.generator, random(12345, -8, 1), new BlockPos(-128, -64, 16)));
        assertTrue(nativeRegion.fluidTicks > 0);
        var expected = new HashMap<BlockPos, BlockState>();
        nativeRegion.writes.forEach((p, state) -> { if (!state.isAir()) expected.put(p, state); });
        try (var predictor = new GeodePredictor(12345)) {
            var actual = predictor.reconstruct(-8, 1, wet).orElseThrow();
            assertEquals(expected, actual.blocks());
            assertEquals(nativeRegion.fluidTicks, actual.deferredFluidTickRequests());
        }
    }

    @Test void interruptionMidGenerationNeverPublishesPartialResults() {
        try (var predictor = new GeodePredictor(12345)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> predictor.candidate(-8, 1));
                assertThrows(CancellationException.class, () -> predictor.reconstruct(-8, 1));
                assertThrows(CancellationException.class, () -> new GeodePredictor(1));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            try {
                assertThrows(CancellationException.class, () -> predictor.reconstruct(-8, 1, p -> {
                    var chunk = uniform(p, Blocks.STONE.defaultBlockState());
                    Thread.currentThread().interrupt();
                    return chunk;
                }));
            } finally { Thread.interrupted(); }
            assertTrue(predictor.candidate(-8, 1).isPresent());
        }
    }

    @Test void inputWorkAndLifetimeLimitsFailExplicitly() throws Exception {
        var predictor = new GeodePredictor(12345);
        try {
            for (int bad : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, GeodePredictor.MAX_CHUNK_COORD + 1}) {
                assertThrows(IllegalArgumentException.class, () -> predictor.candidate(bad, 0));
                assertThrows(IllegalArgumentException.class, () -> predictor.candidate(0, bad));
            }
            assertThrows(IllegalArgumentException.class, () -> predictor.candidates(0, 0, 64, 64));
            assertThrows(IllegalArgumentException.class, () -> predictor.candidates(1, 0, 0, 0));
            var failures = new ArrayList<Throwable>();
            var other = new Thread(() -> {
                for (Runnable call : List.<Runnable>of(() -> predictor.candidate(0, 0), () -> predictor.candidates(0, 0, 0, 0),
                        () -> predictor.reconstruct(0, 0), predictor::close)) {
                    try { call.run(); } catch (Throwable error) { failures.add(error); }
                }
            });
            other.start(); other.join(5000);
            assertFalse(other.isAlive());
            assertEquals(4, failures.size());
            failures.forEach(error -> assertInstanceOf(IllegalStateException.class, error));
            var region = region(p -> uniform(p, Blocks.STONE.defaultBlockState()));
            assertEquals(1, region.writeRadius);
            assertTrue(region.canWrite(new BlockPos(16, 0, 16)));
            assertFalse(region.canWrite(new BlockPos(32, 0, 0)));
            region.fluidTicks = GeodePredictor.MAX_BLOCK_WRITES;
            assertThrows(UnsupportedOperationException.class, () -> region.level.scheduleTick(region.origin,
                net.minecraft.world.level.material.Fluids.WATER, 0));
            region.reads = GeodePredictor.MAX_BLOCK_READS;
            assertThrows(UnsupportedOperationException.class, () -> region.read(region.origin));
            region.writeCalls = GeodePredictor.MAX_BLOCK_WRITES;
            assertThrows(UnsupportedOperationException.class, () -> region.write(region.origin, Blocks.STONE.defaultBlockState()));
            region.reads = 0;
            for (int i = 0; i < GeodePredictor.MAX_TERRAIN_CHUNKS; i++) region.chunks.put(Long.MIN_VALUE + i, uniform(new ChunkPos(0, 0), Blocks.STONE.defaultBlockState()));
            assertThrows(UnsupportedOperationException.class, () -> region.read(region.origin));
            assertThrows(UnsupportedOperationException.class, () -> region.read(region.origin.offset(18, 0, 0)));
        } finally { predictor.close(); }
        predictor.close();
        assertThrows(IllegalStateException.class, () -> predictor.candidate(0, 0));
    }

    @Test void publicNativeTerrainEntrypointAndStoredBiomeOracleAgree() throws Exception {
        var biomeChunks = new HashMap<ChunkPos, ProtoChunk>();
        Function<ChunkPos, ProtoChunk> nativeBiomes = p -> biomeChunks.computeIfAbsent(p, key -> {
            assertTrue(biomeChunks.size() < 64, "bounded reference-only biome cache");
            var chunk = referenceWorld.newChunk(key);
            chunk.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
            referenceWorld.generator.createBiomes(referenceWorld.randomState,
                net.minecraft.world.level.levelgen.blending.Blender.empty(), referenceWorld.structuresFor(chunk), chunk).join();
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            return chunk;
        });
        var storedBiomes = new net.minecraft.world.level.biome.BiomeManager((x, y, z) ->
            nativeBiomes.apply(new ChunkPos(x >> 2, z >> 2)).getNoiseBiome(x, y, z),
            net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(12345L));
        var hashes = new java.util.LinkedHashMap<String, String>();
        try {
            for (int[] at : new int[][]{{0, 0}, {-8, 1}, {24, -33}}) {
                var pos = new ChunkPos(at[0], at[1]);
                var expected = nativeBiomes.apply(pos);
                referenceWorld.generator.buildTerrain(expected, net.minecraft.world.level.levelgen.blending.Blender.empty(),
                    referenceWorld.randomState, referenceWorld.structuresFor(expected), storedBiomes, null, java.util.Set.of()).join();
                expected.setPersistedStatus(ChunkStatus.TERRAIN);
                var actual = referenceWorld.generateTerrain(at[0], at[1]);
                var hash = MessageDigest.getInstance("SHA-256");
                int nonAir = 0;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = -64; y <= 319; y++) {
                    var block = new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                    var state = actual.getBlockState(block);
                    assertEquals(expected.getBlockState(block), state);
                    if (!state.isAir()) nonAir++;
                    hash.update((state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(nonAir > 10_000, "missing terrain must not become an air chunk");
                assertEquals(Blocks.BEDROCK, actual.getBlockState(new BlockPos(pos.getMinBlockX(), -64, pos.getMinBlockZ())).getBlock());
                for (int y : new int[]{-80, -64, -3, 63, 319, 336}) {
                    var block = new BlockPos(pos.getMinBlockX(), y, pos.getMinBlockZ());
                    assertEquals(storedBiomes.getBiome(block), referenceWorld.biomeAt(block), "zoomed/clamped biome lookup");
                }
                hashes.put(at[0] + "," + at[1], HexFormat.of().formatHex(hash.digest()));
            }
            String evidence = System.getProperty("geode263.evidenceDir");
            if (evidence != null) java.nio.file.Files.writeString(java.nio.file.Path.of(evidence, "native-terrain-fixtures.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hashes));
        } finally { biomeChunks.clear(); referenceWorld.endOperation(); }
    }

    @Test void nativeFeaturePlacerWithBiomeCheckMatchesAndDoesNotRebindGlobalTags() {
        var before = blockTags();
        var placed = referenceWorld.registries.lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE)
            .getOrThrow(net.minecraft.data.worldgen.placement.CavePlacements.AMETHYST_GEODE).value();
        var oracle = new Native263TestSupport.ReferenceRegion(referenceWorld, 12345L, -8, 1,
            p -> uniform(p, Blocks.STONE.defaultBlockState()));
        assertTrue(new net.minecraft.world.level.levelgen.placement.FeaturePlacer(oracle.level, referenceWorld.generator)
            .placeWithBiomeCheck(placed, random(12345L, -8, 1), new BlockPos(-128, -64, 16)));
        var expected = new HashMap<BlockPos, BlockState>();
        oracle.writes.forEach((p, state) -> { if (!state.isAir()) expected.put(p, state); });
        try (var predictor = new GeodePredictor(12345L)) {
            var result = predictor.reconstruct(-8, 1, p -> uniform(p, Blocks.STONE.defaultBlockState())).orElseThrow();
            assertEquals(expected, result.blocks());
            assertEquals(oracle.fluidTicks, result.deferredFluidTickRequests());
        }
        assertEquals(before, blockTags());
    }

    @Test void snapshotsAndMaximumSizedScanAreImmutableAndOrdered() {
        var mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        var bounds = new GeodePredictor.Bounds(1, 2, 3, 1, 2, 3);
        var candidate = new GeodePredictor.Candidate(0, 0, 0, mutable, bounds, 2, 2);
        var reconstruction = new GeodePredictor.Reconstruction(candidate, GeodePredictor.Status.GENERATED_IN_SIMULATION,
            Optional.of(bounds), Map.of(mutable, Blocks.AMETHYST_BLOCK.defaultBlockState()), java.util.Set.of(mutable), java.util.Set.of(), 1, 0);
        mutable.move(Direction.UP);
        assertEquals(new BlockPos(1, 2, 3), candidate.origin());
        assertTrue(reconstruction.blocks().containsKey(new BlockPos(1, 2, 3)));
        assertTrue(reconstruction.amethyst().contains(new BlockPos(1, 2, 3)));
        assertFalse(bounds.contains(mutable));
        assertThrows(IllegalArgumentException.class, () -> new GeodePredictor.Bounds(1, 0, 0, 0, 0, 0));
        try (var predictor = new GeodePredictor(12345L)) {
            var results = predictor.candidates(-32, -32, 31, 31);
            assertFalse(results.isEmpty());
            assertEquals(results.stream().sorted(Comparator.comparingInt(GeodePredictor.Candidate::chunkX)
                .thenComparingInt(GeodePredictor.Candidate::chunkZ)).toList(), results);
            assertThrows(UnsupportedOperationException.class, results::clear);
            assertThrows(IllegalArgumentException.class, () -> predictor.candidates(-32, -32, 32, 31));
        }
    }

    @Test void cancellationDuringNativeBlockReadsAndInterruptedCloseDoNotPublishOrRetainState() throws Exception {
        try (var predictor = new GeodePredictor(12345L)) {
            int[] reads = {0};
            try {
                assertThrows(CancellationException.class, () -> predictor.reconstruct(-8, 1, p -> {
                    var chunk = new ProtoChunk(p, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384),
                        containers(Blocks.STONE.defaultBlockState()), null) {
                        @Override public BlockState getBlockState(BlockPos pos) {
                            if (++reads[0] == 128) Thread.currentThread().interrupt();
                            return super.getBlockState(pos);
                        }
                    };
                    for (var section : chunk.getSections()) section.recalcBlockCounts();
                    chunk.setPersistedStatus(ChunkStatus.TERRAIN);
                    return chunk;
                }));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(128, reads[0]);
            } finally { Thread.interrupted(); }
            try {
                assertEquals(951, predictor.reconstruct(-8, 1, p -> uniform(p, Blocks.STONE.defaultBlockState())).orElseThrow().blocks().size());
                Thread.currentThread().interrupt();
                predictor.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            for (String fieldName : List.of("world", "feature", "configured")) {
                var field = GeodePredictor.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                assertNull(field.get(predictor), "closed predictor must release native state");
            }
            assertThrows(IllegalStateException.class, () -> predictor.reconstruct(-8, 1));
        }
    }

    private static Map<String, java.util.Set<Block>> blockTags() {
        return BuiltInRegistries.BLOCK.getTags().collect(java.util.stream.Collectors.toMap(
            tag -> tag.key().location().toString(), tag -> tag.stream().map(Holder::value).collect(java.util.stream.Collectors.toSet())));
    }

    private static GeodePredictor.Region region(Function<ChunkPos, ProtoChunk> terrain) {
        var region = new GeodePredictor.Region(referenceWorld, 0, 0, terrain);
        region.origin = new BlockPos(0, 0, 0);
        return region;
    }

    private static WorldgenRandom random(long seed, int x, int z) {
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        random.setFeatureSeed(random.setDecorationSeed(seed, x * 16, z * 16), 2, 2);
        return random;
    }

    private static Optional<BlockPos> nativeAttempt(long seed, int x, int z) {
        var result = new AtomicReference<BlockPos>();
        var recorder = new Feature() {
            @Override public MapCodec<? extends Feature> codec() { throw new UnsupportedOperationException("test recorder"); }
            @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
                assertNull(result.get(), "only one native attempt per chunk");
                result.set(origin.immutable());
                return true;
            }
        };
        var placed = new PlacedFeature(Holder.direct(recorder), nativeModifiers);
        var region = new Native263TestSupport.ReferenceRegion(referenceWorld, seed, x, z, null);
        placed.place(region.level, referenceWorld.generator, random(seed, x, z), new BlockPos(x * 16, -64, z * 16));
        return Optional.ofNullable(result.get());
    }

    private static Map<BlockPos, BlockState> nativeBlocks(int x, int z, Function<ChunkPos, ProtoChunk> terrain) {
        var region = new Native263TestSupport.ReferenceRegion(referenceWorld, 12345L, x, z, terrain);
        var placed = new PlacedFeature(Holder.direct(nativeGeode), nativeModifiers);
        assertTrue(placed.place(region.level, referenceWorld.generator, random(12345, x, z), new BlockPos(x * 16, -64, z * 16)));
        var blocks = new HashMap<BlockPos, BlockState>();
        region.writes.forEach((p, state) -> { if (!state.isAir()) blocks.put(p, state); });
        return Map.copyOf(blocks);
    }

    private static ProtoChunk uniform(ChunkPos pos, BlockState state) {
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), containers(state), null);
        for (var section : chunk.getSections()) section.recalcBlockCounts();
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        return chunk;
    }

    private static PalettedContainerFactory containers(BlockState state) {
        var biome = referenceWorld.biomeSource.possibleBiomes().iterator().next();
        var ids = new IdMapper<Holder<Biome>>();
        ids.add(biome);
        return new PalettedContainerFactory(Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), state, null,
            Strategy.createForBiomes(ids), biome, null);
    }

    private static JsonObject resource(String type) throws Exception {
        try (var input = GeodePredictor263Test.class.getResourceAsStream("/data/minecraft/worldgen/" + type + "/amethyst_geode.json")) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String digest(Map<BlockPos, BlockState> blocks) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        blocks.keySet().stream().sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))
            .forEach(p -> hash.update((p.getX() + "," + p.getY() + "," + p.getZ() + "=" + blocks.get(p) + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(hash.digest());
    }
}
