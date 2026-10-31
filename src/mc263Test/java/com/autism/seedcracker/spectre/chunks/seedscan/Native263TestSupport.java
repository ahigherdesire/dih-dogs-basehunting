// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.chunks.seedscan;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.Fluid;

/** Standalone registry initialization only; never constructs or starts a MinecraftServer/client. */
public final class Native263TestSupport {
    private Native263TestSupport() {}

    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!SharedConstants.getCurrentVersion().id().equals("26.3")) throw new IllegalStateException("Wrong Minecraft classpath");
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            TagLoader.loadTagsForExistingRegistries(resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
                .forEach(Registry.PendingTags::apply);
        }
    }

    /** Independent oracle writes actual native ProtoChunks, not the production overlay/read implementation. */
    static final class ReferenceRegion {
        final WorldGenLevel level;
        final Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
        final Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        final Function<ChunkPos, ProtoChunk> terrain;
        int fluidTicks;

        ReferenceRegion(NativeGeodeWorld263 world, long seed, int chunkX, int chunkZ, Function<ChunkPos, ProtoChunk> terrain) {
            this.terrain = terrain;
            level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "Native263.ReferenceRegion";
                            default -> throw new AssertionError(method);
                        };
                    }
                    return switch (method.getName()) {
                        case "getSeed" -> seed;
                        case "getMinY" -> world.generator.getMinY();
                        case "getHeight" -> {
                            if (args != null && args.length != 0) throw new AssertionError(method);
                            yield world.generator.getGenDepth();
                        }
                        case "getBiome" -> world.biomeAt((BlockPos) args[0]);
                        case "getBlockState" -> chunk((BlockPos) args[0]).getBlockState((BlockPos) args[0]);
                        case "getFluidState" -> chunk((BlockPos) args[0]).getFluidState((BlockPos) args[0]);
                        case "setBlock" -> {
                            if (args.length != 3 || !args[2].equals(2)) throw new AssertionError(method);
                            BlockPos pos = ((BlockPos) args[0]).immutable();
                            if (pos.getY() < -64 || pos.getY() > 319
                                || Math.abs((pos.getX() >> 4) - chunkX) > 1 || Math.abs((pos.getZ() >> 4) - chunkZ) > 1) yield false;
                            var state = (BlockState) args[1];
                            chunk(pos).setBlockState(pos, state, 2);
                            writes.put(pos, state);
                            yield true;
                        }
                        case "scheduleTick" -> {
                            if (args.length != 3 || !(args[1] instanceof Fluid)) throw new AssertionError(method);
                            fluidTicks++;
                            yield null;
                        }
                        default -> throw new AssertionError("Unmodeled oracle call: " + method);
                    };
                });
        }

        private ProtoChunk chunk(BlockPos pos) {
            if (terrain == null) throw new AssertionError("Coarse oracle requested terrain");
            var key = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            return chunks.computeIfAbsent(key, p -> Objects.requireNonNull(terrain.apply(p), "oracle terrain"));
        }
    }
}
