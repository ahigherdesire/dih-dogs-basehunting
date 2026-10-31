// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.chunks.seedscan;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.material.Fluid;

/** Single-worker, default-overworld 26.2 prediction; never reads a client world. */
@Environment(EnvType.CLIENT)
public final class GeodePredictor implements AutoCloseable {
    public static final int MAX_CHUNK_COORD = 1_874_990;
    public static final int MAX_SCAN_CHUNKS = 4096;
    public static final int MAX_TERRAIN_CHUNKS = 16;
    public static final int MAX_BLOCK_READS = 131_072;
    public static final int MAX_BLOCK_WRITES = 65_536;
    public static final String ASSUMPTIONS = "Default 26.2 overworld and vanilla block tags. Placement origins are not "
        + "geode centers or confirmed geodes. Detail is one native geode on native noise/surface/carvers, "
        + "before fluid ticks; structures, other decoration, neighboring geodes, blending, datapacks, older "
        + "generation and player edits are not reproduced. Neither result proves current-server blocks or a base.";

    private final Thread owner = Thread.currentThread();
    private SeedWorldgen world;
    private PlacedFeature feature;
    private ConfiguredFeature<?, ?> configured;
    private final int step;
    private final int index;

    /** Construct on the owning worker, after native registries and vanilla block tags are ready. */
    public GeodePredictor(long seed) {
        interrupted();
        world = SeedWorldgen.create(seed);
        interrupted();
        int foundStep = -1;
        int foundIndex = -1;
        for (int s = 0; s < world.featureSteps.size(); s++) {
            var features = world.featureSteps.get(s).features();
            for (int i = 0; i < features.size(); i++) {
                var placed = features.get(i);
                if (placed.feature().is(CaveFeatures.AMETHYST_GEODE)) {
                    if (feature != null) throw unsupported("multiple amethyst geode features");
                    feature = placed;
                    foundStep = s;
                    foundIndex = i;
                }
            }
        }
        step = foundStep;
        index = foundIndex;
        validateFeature();
    }

    public enum Status { PLACEMENT_ATTEMPT, TERRAIN_REJECTED, GENERATED_IN_SIMULATION }

    /** Inclusive block bounds, not an entity/render AABB. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("inverted bounds");
        }
        public boolean contains(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
        }
    }

    /** Origin is the vanilla placement anchor; possibleBounds is only a conservative search envelope. */
    public record Candidate(long seed, int chunkX, int chunkZ, BlockPos origin, Bounds possibleBounds,
                            int decorationStep, int featureIndex) {
        public Candidate {
            origin = origin.immutable();
            Objects.requireNonNull(possibleBounds);
        }
        public Status status() { return Status.PLACEMENT_ATTEMPT; }
    }

    /** Final non-air writes, including complete bud/cluster states; fluid tick requests are neither deduplicated nor executed. */
    public record Reconstruction(Candidate candidate, Status status, Optional<Bounds> bounds,
                                 Map<BlockPos, BlockState> blocks, Set<BlockPos> amethyst,
                                 Set<BlockPos> budding, int terrainChunks, int deferredFluidTickRequests) {
        public Reconstruction {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(status);
            Objects.requireNonNull(bounds);
            blocks = Map.copyOf(blocks);
            amethyst = Set.copyOf(amethyst);
            budding = Set.copyOf(budding);
        }
    }

    /** Empty means the placement chain produced no attempt, not that terrain was checked. */
    public Optional<Candidate> candidate(int chunkX, int chunkZ) {
        checkOpen();
        checkChunk(chunkX, chunkZ);
        try {
            return locate(chunkX, chunkZ, random(chunkX, chunkZ), new Region(world, chunkX, chunkZ, null));
        } finally {
            world.clearCaches();
        }
    }

    /** Inclusive rectangle; rejects oversized requests rather than returning a truncated scan. */
    public List<Candidate> candidates(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        checkOpen();
        checkChunk(minChunkX, minChunkZ);
        checkChunk(maxChunkX, maxChunkZ);
        long width = (long) maxChunkX - minChunkX + 1;
        long depth = (long) maxChunkZ - minChunkZ + 1;
        if (width <= 0 || depth <= 0 || width * depth > MAX_SCAN_CHUNKS) {
            throw new IllegalArgumentException("scan must contain 1.." + MAX_SCAN_CHUNKS + " chunks");
        }
        var results = new ArrayList<Candidate>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                interrupted();
                candidate(x, z).ifPresent(results::add);
            }
        }
        return List.copyOf(results);
    }

    /** Empty means no placement attempt. A rejected attempt has no bounds or generated blocks. */
    public Optional<Reconstruction> reconstruct(int chunkX, int chunkZ) {
        checkOpen();
        return reconstruct(chunkX, chunkZ, p -> world.generateTerrain(p.x(), p.z()));
    }

    Optional<Reconstruction> reconstruct(int chunkX, int chunkZ, Function<ChunkPos, ProtoChunk> terrain) {
        checkOpen();
        checkChunk(chunkX, chunkZ);
        Objects.requireNonNull(terrain);
        var region = new Region(world, chunkX, chunkZ, terrain);
        try {
            var random = random(chunkX, chunkZ);
            var attempt = locate(chunkX, chunkZ, random, region);
            if (attempt.isEmpty()) return Optional.empty();
            var candidate = attempt.orElseThrow();
            region.origin = candidate.origin();
            // The placement chain and configured feature share this RNG; do not reseed between them.
            boolean placed = configured.place(region.level, world.generator, random, candidate.origin());
            interrupted();
            if (!placed && !region.writes.isEmpty()) throw unsupported("a failed geode wrote blocks");
            var blocks = new LinkedHashMap<BlockPos, BlockState>();
            for (var entry : region.writes.entrySet()) {
                interrupted();
                if (!entry.getValue().isAir()) blocks.put(entry.getKey(), entry.getValue());
            }
            Set<BlockPos> amethyst = positions(blocks, Blocks.AMETHYST_BLOCK);
            Set<BlockPos> budding = positions(blocks, Blocks.BUDDING_AMETHYST);
            return Optional.of(new Reconstruction(candidate, placed ? Status.GENERATED_IN_SIMULATION : Status.TERRAIN_REJECTED,
                bounds(blocks.keySet()), blocks, amethyst, budding, region.chunks.size(), region.fluidTicks));
        } finally {
            region.chunks.clear();
            region.writes.clear();
            world.clearCaches();
        }
    }

    private Optional<Candidate> locate(int chunkX, int chunkZ, WorldgenRandom random, Region region) {
        var context = new PlacementContext(region.level, world.generator, Optional.of(feature));
        Stream<BlockPos> stream = Stream.of(new BlockPos(chunkX * 16, SeedWorldgen.MIN_Y, chunkZ * 16));
        for (var modifier : feature.placement()) {
            stream = stream.flatMap(pos -> modifier.getPositions(context, random, pos));
        }
        try (var positions = stream) {
            var iterator = positions.iterator();
            interrupted();
            boolean present = iterator.hasNext();
            interrupted();
            if (!present) return Optional.empty();
            BlockPos origin = iterator.next().immutable();
            if (iterator.hasNext()) throw unsupported("more than one geode attempt per chunk");
            interrupted();
            var box = new Bounds(origin.getX() - 17, Math.max(SeedWorldgen.MIN_Y, origin.getY() - 17), origin.getZ() - 17,
                origin.getX() + 17, Math.min(SeedWorldgen.MAX_Y, origin.getY() + 17), origin.getZ() + 17);
            return Optional.of(new Candidate(world.seed, chunkX, chunkZ, origin, box, step, index));
        }
    }

    private WorldgenRandom random(int chunkX, int chunkZ) {
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long decoration = random.setDecorationSeed(world.seed, chunkX * 16, chunkZ * 16);
        random.setFeatureSeed(decoration, index, step);
        return random;
    }

    private void validateFeature() {
        if (feature == null || step != GenerationStep.Decoration.LOCAL_MODIFICATIONS.ordinal()
            || feature.feature().value().feature() != Feature.GEODE
            || !(feature.feature().value().config() instanceof GeodeConfiguration config)
            || config.minGenOffset() != -16 || config.maxGenOffset() != 16
            || config.distributionPoints().maxInclusive() > 8 || config.outerWallDistance().maxInclusive() > 16) {
            throw unsupported("geode configuration or decoration step");
        }
        // All default overworld biomes carry this feature, so the 3x3 biome union cannot omit it.
        if (world.biomeSource.possibleBiomes().stream().anyMatch(b -> !world.generator.getBiomeGenerationSettings(b).hasFeature(feature))) {
            throw unsupported("geode absent from a possible overworld biome");
        }
        String[] expected = {
            "{\"type\":\"minecraft:rarity_filter\",\"chance\":24}",
            "{\"type\":\"minecraft:in_square\"}",
            "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:uniform\",\"min_inclusive\":{\"above_bottom\":6},\"max_inclusive\":{\"absolute\":30}}}",
            "{\"type\":\"minecraft:biome\"}"
        };
        if (feature.placement().size() != expected.length) throw unsupported("geode placement modifier count");
        for (int i = 0; i < expected.length; i++) {
            var actual = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, feature.placement().get(i)).getOrThrow();
            if (!actual.equals(JsonParser.parseString(expected[i]))) throw unsupported("geode placement modifier " + i);
        }
        // RegistrySetBuilder bootstrap tags are construction-only placeholders. Resolve with the native codec,
        // without rebinding or changing any global registries. Keep the original placed feature for BiomeFilter.
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var json = GeodeConfiguration.CODEC.encodeStart(ops, config).getOrThrow();
        var bound = GeodeConfiguration.CODEC.parse(ops, json).getOrThrow();
        requireBlocks(bound.geodeBlockSettings().invalidBlocks(), Set.of(Blocks.BEDROCK, Blocks.WATER, Blocks.LAVA,
            Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE));
        requireBlocks(bound.geodeBlockSettings().cannotReplace(), Set.of(Blocks.BEDROCK, Blocks.SPAWNER, Blocks.CHEST,
            Blocks.END_PORTAL_FRAME, Blocks.REINFORCED_DEEPSLATE, Blocks.TRIAL_SPAWNER, Blocks.VAULT));
        configured = new ConfiguredFeature<>(Feature.GEODE, bound);
    }

    private static void requireBlocks(HolderSet<Block> tag, Set<Block> expected) {
        if (!tag.stream().map(Holder::value).collect(Collectors.toSet()).equals(expected)) {
            throw unsupported("unbound or non-vanilla geode block tag: " + tag);
        }
    }

    private static Set<BlockPos> positions(Map<BlockPos, BlockState> blocks, Block block) {
        return blocks.entrySet().stream().filter(e -> e.getValue().is(block)).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<Bounds> bounds(Set<BlockPos> blocks) {
        if (blocks.isEmpty()) return Optional.empty();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var pos : blocks) {
            interrupted();
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }
        return Optional.of(new Bounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private void checkOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("GeodePredictor must stay on its creating worker");
        if (world == null) throw new IllegalStateException("GeodePredictor is closed");
        interrupted();
    }

    static void checkChunk(int x, int z) {
        if (Math.abs((long) x) > MAX_CHUNK_COORD || Math.abs((long) z) > MAX_CHUNK_COORD) {
            throw new IllegalArgumentException("chunk outside supported range +/-" + MAX_CHUNK_COORD);
        }
    }

    static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("geode prediction cancelled");
    }

    private static UnsupportedOperationException unsupported(String detail) {
        return new UnsupportedOperationException("Unsupported native geode worldgen: " + detail);
    }

    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("close on the creating worker");
        if (world != null) world.clearCaches();
        world = null;
        feature = null;
        configured = null;
    }

    /** Only native calls actually required by placement/GeodeFeature are supported. */
    static final class Region {
        final SeedWorldgen world;
        final int chunkX, chunkZ;
        final int writeRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
        final Function<ChunkPos, ProtoChunk> terrain;
        final Map<Long, ProtoChunk> chunks = new HashMap<>();
        final Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        final WorldGenLevel level;
        BlockPos origin;
        int reads, writeCalls, fluidTicks;

        Region(SeedWorldgen world, int chunkX, int chunkZ, Function<ChunkPos, ProtoChunk> terrain) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.terrain = terrain;
            InvocationHandler handler = (proxy, method, args) -> {
                interrupted();
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "GeodePredictor.Region";
                        default -> throw unsupported(method.toString());
                    };
                }
                return switch (method.getName()) {
                    case "getSeed" -> world.seed;
                    case "getMinY" -> SeedWorldgen.MIN_Y;
                    case "getHeight" -> {
                        if (method.getParameterCount() != 0) throw unsupported(method.toString());
                        yield SeedWorldgen.HEIGHT;
                    }
                    case "getBiome" -> world.biomeAt(((BlockPos) args[0]).getX(), ((BlockPos) args[0]).getY(), ((BlockPos) args[0]).getZ());
                    case "getBlockState" -> read((BlockPos) args[0]);
                    case "getFluidState" -> read((BlockPos) args[0]).getFluidState();
                    case "ensureCanWrite" -> canWrite((BlockPos) args[0]);
                    case "setBlock" -> {
                        if (args.length != 3 || !Integer.valueOf(2).equals(args[2])) throw unsupported(method.toString());
                        yield write((BlockPos) args[0], (BlockState) args[1]);
                    }
                    case "scheduleTick" -> {
                        if (args.length != 3 || !(args[1] instanceof Fluid)) throw unsupported(method.toString());
                        checkPosition((BlockPos) args[0]);
                        if (++fluidTicks > MAX_BLOCK_WRITES) throw unsupported("fluid tick limit");
                        // ponytail: placement snapshot only; a ticking world is needed for later fluid settling.
                        yield null;
                    }
                    default -> throw unsupported(method.toString());
                };
            };
            level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, handler);
        }

        private void checkPosition(BlockPos p) {
            interrupted();
            if (origin == null || Math.abs((long) p.getX() - origin.getX()) > 17
                || Math.abs((long) p.getY() - origin.getY()) > 17 || Math.abs((long) p.getZ() - origin.getZ()) > 17) {
                throw unsupported("read/write outside geode envelope: " + p);
            }
        }

        BlockState read(BlockPos p) {
            checkPosition(p);
            if (++reads > MAX_BLOCK_READS) throw unsupported("block read limit");
            BlockState written = writes.get(p);
            if (written != null) return written;
            if (terrain == null) throw unsupported("terrain requested during coarse placement");
            // These are known dimension limits, not missing terrain. ProtoChunk also returns VOID_AIR here.
            if (p.getY() < SeedWorldgen.MIN_Y || p.getY() > SeedWorldgen.MAX_Y) return Blocks.VOID_AIR.defaultBlockState();
            int x = p.getX() >> 4, z = p.getZ() >> 4;
            long key = ChunkPos.pack(x, z);
            ProtoChunk chunk = chunks.get(key);
            if (chunk == null) {
                if (chunks.size() >= MAX_TERRAIN_CHUNKS) throw unsupported("terrain chunk limit");
                chunk = terrain.apply(new ChunkPos(x, z));
                interrupted();
                if (chunk == null || chunk.getPos().x() != x || chunk.getPos().z() != z
                    || chunk.getMinY() != SeedWorldgen.MIN_Y || chunk.getHeight() != SeedWorldgen.HEIGHT
                    || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.CARVERS)) {
                    throw unsupported("missing, mismatched, or incomplete terrain at " + x + ", " + z);
                }
                chunks.put(key, chunk);
            }
            return Objects.requireNonNull(chunk.getBlockState(p), "null terrain state");
        }

        boolean canWrite(BlockPos p) {
            return p.getY() >= SeedWorldgen.MIN_Y && p.getY() <= SeedWorldgen.MAX_Y
                && Math.abs((long) (p.getX() >> 4) - chunkX) <= writeRadius && Math.abs((long) (p.getZ() >> 4) - chunkZ) <= writeRadius;
        }

        boolean write(BlockPos p, BlockState state) {
            checkPosition(p);
            if (++writeCalls > MAX_BLOCK_WRITES) throw unsupported("block write limit");
            Objects.requireNonNull(state, "null generated state");
            if (!canWrite(p)) return false;
            writes.put(p.immutable(), state);
            return true;
        }
    }
}
