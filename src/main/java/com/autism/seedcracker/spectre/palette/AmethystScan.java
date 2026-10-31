// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/** Spectre AmethystBypass / GeodeGlowScan, with observed blocks separated from uncertain hints. */
public final class AmethystScan {
    public enum Method { BYPASS, LIGHT, ANBS_SCAN }
    public static final int MAX_BLOCKS_PER_CHUNK = 131072;
    private static final Direction[] SIDES = Direction.values();

    public record Result(Set<BlockPos> blocks, Set<Integer> hints) {
        public Result {
            // BlockPos hashes cluster densely; Set.copyOf's linear probing becomes quadratic for full chunks.
            blocks = Collections.unmodifiableSet(new LinkedHashSet<>(blocks));
            hints = Set.copyOf(hints);
        }
        public int size() { return blocks.size() + hints.size(); }
    }
    public record Observed(Set<BlockPos> blocks, boolean geode, boolean foreignEmitter) {
        public Observed { blocks = Collections.unmodifiableSet(new LinkedHashSet<>(blocks)); }
    }

    public static boolean bud(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
    }
    private static boolean geode(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST);
    }
    public static boolean amethyst(BlockState state) { return bud(state) || geode(state); }
    private static boolean open(BlockState state) { return state.isAir() || state.is(Blocks.WATER); }

    public static boolean paletteHint(boolean unused, boolean observedGeode, boolean uncorroborated) {
        return unused && (observedGeode || uncorroborated);
    }

    public static AABB hintBox(ChunkPos chunk, int sectionY) {
        return new AABB(chunk.getMinBlockX(), sectionY * 16, chunk.getMinBlockZ(),
            chunk.getMinBlockX() + 16, sectionY * 16 + 16, chunk.getMinBlockZ() + 16);
    }

    public static Observed observe(LevelChunkSection section, ChunkPos chunk, int sectionY) {
        if (section == null || !section.getStates().maybeHas(AmethystScan::amethyst))
            return new Observed(Set.of(), false, false);
        Set<BlockPos> blocks = new LinkedHashSet<>();
        boolean geode = false, foreignEmitter = false;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = section.getBlockState(x, y, z);
            geode |= geode(state);
            foreignEmitter |= state.getLightEmission() > 0 && !bud(state);
            if (amethyst(state))
                blocks.add(new BlockPos(chunk.getMinBlockX() + x, sectionY * 16 + y, chunk.getMinBlockZ() + z));
        }
        return new Observed(blocks, geode, foreignEmitter);
    }

    public Result section(ClientLevel level, LevelChunk chunk, int index, Method method,
                          boolean unusedPalette, boolean uncorroborated, int minimumCells) {
        return section(level, chunk, index, method, unusedPalette, uncorroborated, minimumCells, true);
    }

    public Result section(ClientLevel level, LevelChunk chunk, int index, Method method,
                          boolean unusedPalette, boolean uncorroborated, int minimumCells, boolean showHints) {
        int sectionY = chunk.getMinSectionY() + index;
        LevelChunkSection section = chunk.getSections()[index];
        Observed observed = observe(section, chunk.getPos(), sectionY);
        boolean hint = showHints && (method == Method.BYPASS
            ? paletteHint(unusedPalette, observed.geode(), uncorroborated)
            : section != null && observed.geode() && !observed.foreignEmitter()
                && lightHint(level, chunk, section, sectionY, method, unusedPalette, minimumCells));
        return new Result(observed.blocks(), hint ? Set.of(sectionY) : Set.of());
    }

    public static boolean hiddenBudLight(int emission, int value, int[] neighbours,
                                         boolean adjacentBudding, boolean knownEmitter) {
        if (knownEmitter || !PaletteScan.budLight(emission, value, neighbours, adjacentBudding)) return false;
        for (int neighbour : neighbours) if (neighbour == value - 1) return true;
        return false;
    }

    public static boolean hiddenGlow(int value, int[] neighbours, boolean[] openings,
                                     boolean corroborated, boolean knownEmitter) {
        return corroborated && !knownEmitter && PaletteScan.glowEdge(value, neighbours, openings);
    }

    private boolean lightHint(ClientLevel level, LevelChunk chunk, LevelChunkSection section, int sectionY,
                              Method method, boolean unusedPalette, int minimumCells) {
        DataLayer[] layers = new DataLayer[27];
        LevelChunkSection[] surroundings = new LevelChunkSection[27];
        var listener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        ChunkPos cp = chunk.getPos();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            LevelChunk neighbour = level.getChunkSource().getChunkNow(cp.x() + dx, cp.z() + dz);
            if (neighbour == null) return false;
            for (int dy = -1; dy <= 1; dy++) {
                int index = sectionY + dy - neighbour.getMinSectionY();
                if (index < 0 || index >= neighbour.getSections().length) return false;
                int slot = slot(dx, dy, dz);
                surroundings[slot] = neighbour.getSections()[index];
                layers[slot] = listener.getDataLayerData(SectionPos.of(cp.x() + dx, sectionY + dy, cp.z() + dz));
                if (surroundings[slot] == null || layers[slot] == null) return false;
            }
        }
        int[] neighbours = new int[6];
        boolean[] openings = new boolean[6];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean glow = method == Method.ANBS_SCAN;
        int count = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = section.getBlockState(x, y, z);
            if (!open(state)) continue;
            int wx = cp.getMinBlockX() + x, wy = sectionY * 16 + y, wz = cp.getMinBlockZ() + z;
            int value = lightAt(layers, cp, sectionY, wx, wy, wz);
            if (glow ? value != 0 : value != 1 && value != 2 && value != 4 && value != 5) continue;
            boolean adjacentGeode = false, adjacentBudding = false, knownEmitter = false;
            for (int side = 0; side < SIDES.length; side++) {
                Direction direction = SIDES[side];
                int nx = wx + direction.getStepX(), ny = wy + direction.getStepY(), nz = wz + direction.getStepZ();
                int light = ny < level.getMinY() || ny > level.getMaxY() ? -1 : lightAt(layers, cp, sectionY, nx, ny, nz);
                neighbours[side] = light;
                openings[side] = false;
                if (light < 0) continue;
                BlockState neighbour = level.getBlockState(cursor.set(nx, ny, nz));
                knownEmitter |= neighbour.getLightEmission() > 0;
                adjacentGeode |= geode(neighbour);
                adjacentBudding |= neighbour.is(Blocks.BUDDING_AMETHYST);
                openings[side] = open(neighbour);
            }
            boolean candidate = glow
                ? hiddenGlow(value, neighbours, openings, adjacentGeode && (unusedPalette || adjacentBudding), knownEmitter)
                : hiddenBudLight(state.getLightEmission(), value, neighbours, adjacentBudding, knownEmitter);
            if (candidate) {
                if (count == 0 && !clearEmitterHalo(surroundings, layers)) return false;
                if (++count >= (glow ? Math.max(1, minimumCells) : 1)) return true;
            }
        }
        return false;
    }

    /** The 27 sections cover every possible level-15 source for this section and its neighbours. */
    static boolean clearEmitterHalo(LevelChunkSection[] sections, DataLayer[] layers) {
        if (sections.length != 27 || layers.length != 27) return false;
        for (int i = 0; i < 27; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || layers[i] == null) return false;
            if (!section.getStates().maybeHas(state -> state.getLightEmission() > 0)) continue;
            // ponytail: conservative whole-section halo veto (at most 110592 cells); narrower source-distance tests can improve recall.
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                if (section.getBlockState(x, y, z).getLightEmission() > 0) return false;
        }
        return true;
    }

    static int lightAt(DataLayer[] layers, ChunkPos cp, int sectionY, int x, int y, int z) {
        int dx = (x >> 4) - cp.x(), dy = (y >> 4) - sectionY, dz = (z >> 4) - cp.z();
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1) return -1;
        DataLayer data = layers[slot(dx, dy, dz)];
        // Missing light is unknown, not a dark cell; never invent a discontinuity at an unloaded edge.
        return data == null ? -1 : data.get(x & 15, y & 15, z & 15);
    }

    private static int slot(int dx, int dy, int dz) { return ((dx + 1) * 3 + dz + 1) * 3 + dy + 1; }
}
