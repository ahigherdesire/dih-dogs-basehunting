package com.autism.seedcracker.finder.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The existing stash cluster rules, evaluated incrementally rather than once per render bubble. */
public final class StashScoring {
    public record Result(int score, BlockPos centre, int storage, boolean completeEnvironment, Set<ChunkPos> chunks) {}
    private record Cell(int x, int y, int z) {}

    public static boolean donutCandidate(int deepStorage, int spawners, int bulkThreshold, boolean includeNaturalSpawner) {
        if (deepStorage < 0 || spawners < 0 || bulkThreshold < 1) throw new IllegalArgumentException("Invalid stash counts");
        return deepStorage >= bulkThreshold || spawners > 0 && (deepStorage >= 3 || includeNaturalSpawner);
    }

    /** Movement can restart the cursor, but must not restart this collection window. */
    public static final class CollectionSchedule {
        private int batches;

        public boolean afterBatch(boolean sampled, double sweepProgress) {
            if (!sampled) return false;
            if (++batches < 20 && sweepProgress < 1.0) return false;
            batches = 0;
            return true;
        }

        public void reset() { batches = 0; }
    }

    private final List<BlockPos> storage;
    private final Function<BlockPos, BlockState> blocks;
    private final int minY, maxY;
    private final int[] parent;
    private final byte[] rank;
    private final Block[] kinds;
    private final Map<Cell, List<Integer>> cells = new HashMap<>();
    private final Map<Integer, Cluster> clusters = new LinkedHashMap<>();
    private final List<Result> results = new ArrayList<>();
    private int phase, node, neighbour = -1, member;
    private Iterator<Cluster> pending;
    private Cluster active;
    private int offset, height, bottom, surface;
    private Env env;

    /** A null block sample means unloaded/unknown, not air. maxY is exclusive. */
    public StashScoring(List<BlockPos> storage, Function<BlockPos, BlockState> blocks, int minY, int maxY) {
        this.storage = List.copyOf(storage);
        this.blocks = blocks;
        this.minY = minY;
        this.maxY = maxY;
        parent = new int[storage.size()];
        rank = new byte[storage.size()];
        kinds = new Block[storage.size()];
    }

    /** Each unit visits one candidate neighbour, one storage point, or one environment block. */
    public int advance(int budget) {
        int used = 0;
        while (used < Math.max(0, budget) && !complete()) {
            step();
            used++;
        }
        return used;
    }

    public boolean complete() { return phase == 5; }
    public List<Result> results() { return complete() ? List.copyOf(results) : List.of(); }

    private void step() {
        if (phase == 3) {
            if (node == storage.size()) {
                pending = clusters.values().iterator();
                phase = 4;
            } else {
                if (kinds[node] != null) {
                    BlockState state = blocks.apply(storage.get(node));
                    if (state == null || state.getBlock() != kinds[node]) clusters.get(find(node)).valid = false;
                }
                node++;
            }
            return;
        }
        if (phase == 4) {
            if (!pending.hasNext()) { phase = 5; return; }
            Cluster cluster = pending.next();
            if (cluster.valid && cluster.result != null) results.add(cluster.result);
            return;
        }
        if (phase == 0) {
            if (node == storage.size()) {
                cells.clear();
                node = 0;
                phase = 1;
                return;
            }
            BlockPos p = storage.get(node);
            if (neighbour == -1) {
                BlockState state = blocks.apply(p);
                if (state == null || !isStorage(state)) { node++; return; }
                kinds[node] = state.getBlock();
                parent[node] = node;
                neighbour = 0;
                return;
            }
            Cell own = cell(p);
            if (neighbour == 27) {
                cells.computeIfAbsent(own, key -> new ArrayList<>()).add(node++);
                neighbour = -1;
                return;
            }
            Cell adjacent = new Cell(own.x + neighbour / 9 - 1, own.y + neighbour / 3 % 3 - 1,
                own.z + neighbour % 3 - 1);
            List<Integer> nearby = cells.get(adjacent);
            if (nearby == null || member == nearby.size()) { neighbour++; member = 0; return; }
            int other = nearby.get(member++);
            BlockPos q = storage.get(other);
            if (Math.abs(p.getX() - q.getX()) + Math.abs(p.getY() - q.getY()) + Math.abs(p.getZ() - q.getZ()) <= 8) {
                union(node, other);
            }
            return;
        }
        if (phase == 1) {
            if (node == storage.size()) {
                pending = clusters.values().iterator();
                phase = 2;
            } else {
                if (kinds[node] != null) clusters.computeIfAbsent(find(node), key -> new Cluster()).add(storage.get(node), kinds[node]);
                node++;
            }
            return;
        }
        if (active == null) {
            if (!pending.hasNext()) { node = 0; phase = 3; return; }
            Cluster candidate = pending.next();
            if (candidate.size < 2) return;
            active = candidate;
            active.centre = new BlockPos((int) Math.floorDiv(active.sx, active.size),
                (int) Math.floorDiv(active.sy, active.size), (int) Math.floorDiv(active.sz, active.size));
            bottom = Math.max(minY, active.centre.getY() - 16);
            height = Math.max(0, Math.min(maxY - 1, active.centre.getY() + 16) - bottom + 1);
            offset = 0;
            surface = maxY - 1;
            env = new Env();
            return;
        }
        if (offset < 33 * 33 * height) {
            BlockPos p = new BlockPos(active.centre.getX() - 16 + offset / (33 * height),
                bottom + offset % height, active.centre.getZ() - 16 + offset / height % 33);
            offset++;
            active.chunks.add(new ChunkPos(p.getX() >> 4, p.getZ() >> 4));
            BlockState state = blocks.apply(p);
            if (state == null) { env.complete = false; return; }
            Block b = state.getBlock();
            if (b instanceof net.minecraft.world.level.block.BedBlock && p.distManhattan(active.centre) <= 32) env.beds++;
            if (isWorkstation(b)) env.workstations++;
            if (isRedstone(b)) env.redstone = true;
            if (isRailPortal(b)) env.railPortal = true;
            if (!state.getFluidState().isEmpty()) env.fluid = true;
            if (isArtificialWall(b)) env.wall++;
            if (isNatural(b)) env.natural++;
            env.total++;
            return;
        }
        if (surface >= minY) {
            BlockState state = blocks.apply(new BlockPos(active.centre.getX(), surface, active.centre.getZ()));
            if (state == null) env.complete = false;
            else if (!state.isAir() && state.getFluidState().isEmpty()) {
                env.shallow = surface - active.centre.getY() < 8;
                surface = minY - 1;
                return;
            }
            surface--;
            return;
        }
        active.result = new Result(score(active, env), active.centre, active.size, env.complete, Set.copyOf(active.chunks));
        active = null;
    }

    private static Cell cell(BlockPos p) {
        return new Cell(Math.floorDiv(p.getX(), 8), Math.floorDiv(p.getY(), 8), Math.floorDiv(p.getZ(), 8));
    }

    private int find(int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private void union(int a, int b) {
        a = find(a); b = find(b);
        if (a == b) return;
        if (rank[a] < rank[b]) parent[a] = b;
        else {
            parent[b] = a;
            if (rank[a] == rank[b]) rank[a]++;
        }
    }

    private static final class Cluster {
        int size, firstX, firstZ, minY, maxY, minX, maxX, minZ, maxZ, gcdX, gcdZ;
        long sx, sy, sz;
        BlockPos centre;
        Result result;
        boolean valid = true;
        final Set<Block> kinds = new HashSet<>();
        final Set<ChunkPos> chunks = new HashSet<>();
        final Set<Integer> xs = new HashSet<>(), zs = new HashSet<>();

        void add(BlockPos p, Block block) {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            if (size == 0) {
                firstX = minX = maxX = x; firstZ = minZ = maxZ = z; minY = maxY = y;
            }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            gcdX = gcd(gcdX, Math.abs(x - firstX)); gcdZ = gcd(gcdZ, Math.abs(z - firstZ));
            xs.add(x); zs.add(z); kinds.add(block);
            chunks.add(new ChunkPos(x >> 4, z >> 4));
            sx += x; sy += y; sz += z; size++;
        }

        boolean grid() {
            // Equal-spacing test without sorting/re-walking an arbitrarily large cluster.
            return size >= 4 && minY == maxY && xs.size() >= 2 && zs.size() >= 2
                && (long) gcdX * (xs.size() - 1) == (long) maxX - minX
                && (long) gcdZ * (zs.size() - 1) == (long) maxZ - minZ;
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return a;
    }

    private static final class Env {
        int beds, workstations, natural, wall, total;
        boolean redstone, railPortal, fluid, shallow;
        boolean complete = true;
    }

    private static int score(Cluster c, Env e) {
        int score = c.kinds.size() >= 2 ? 30 : 0;
        score += Math.min(40, e.beds * 20) + Math.min(45, e.workstations * 15);
        if (e.redstone) score += 25;
        if (e.railPortal) score += 20;
        if (e.fluid) score += 10;
        if (c.maxY - c.minY >= 3) score += 10;
        if (e.wall >= 8) score += 15;
        if (c.kinds.size() == 1 && c.size >= 6 && c.size <= 64) score -= 15;
        if (c.grid()) score -= 20;
        if (c.minY % 8 == 0 || c.minY == 64 || c.minY == -59) score -= 25;
        if (e.workstations == 0) score -= 20;
        if (e.beds == 0) score -= 15;
        if (e.total > 0 && (double) e.natural / e.total > 0.9 && e.wall < 4) score -= 20;
        if (e.shallow) score -= 15;
        return score;
    }

    public static boolean isStorage(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.CHEST || b == Blocks.BARREL || b instanceof ShulkerBoxBlock
            || b == Blocks.HOPPER || b == Blocks.ENDER_CHEST || b == Blocks.TRAPPED_CHEST;
    }

    private static boolean isWorkstation(Block b) {
        return b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE
            || b == Blocks.SMOKER || b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL
            || b == Blocks.DAMAGED_ANVIL || b == Blocks.ENCHANTING_TABLE || b == Blocks.BREWING_STAND
            || b == Blocks.GRINDSTONE || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.SMITHING_TABLE
            || b == Blocks.LOOM || b == Blocks.STONECUTTER || b == Blocks.FLETCHING_TABLE || b == Blocks.LECTERN;
    }

    private static boolean isRedstone(Block b) {
        return b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.OBSERVER
            || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.HOPPER
            || b == Blocks.DISPENSER || b == Blocks.DROPPER || b == Blocks.REDSTONE_WIRE
            || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_LAMP || b == Blocks.REDSTONE_BLOCK;
    }

    private static boolean isRailPortal(Block b) {
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL
            || b == Blocks.ACTIVATOR_RAIL || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL
            || b == Blocks.END_PORTAL_FRAME || b == Blocks.END_GATEWAY;
    }

    private static boolean isNatural(Block b) {
        return b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.DIRT || b == Blocks.GRASS_BLOCK
            || b == Blocks.PODZOL || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.SANDSTONE
            || b == Blocks.NETHERRACK || b == Blocks.BASALT || b == Blocks.BLACKSTONE || b == Blocks.END_STONE
            || b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR;
    }

    private static boolean isArtificialWall(Block b) {
        return b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE || b == Blocks.STONE_BRICKS
            || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS
            || b == Blocks.BIRCH_PLANKS || b == Blocks.JUNGLE_PLANKS || b == Blocks.ACACIA_PLANKS
            || b == Blocks.DARK_OAK_PLANKS || b == Blocks.MANGROVE_PLANKS || b == Blocks.CHERRY_PLANKS
            || b == Blocks.CRIMSON_PLANKS || b == Blocks.WARPED_PLANKS || b == Blocks.BAMBOO_PLANKS
            || b == Blocks.BRICKS || b == Blocks.DEEPSLATE_BRICKS || b == Blocks.DEEPSLATE_TILES
            || b == Blocks.POLISHED_DEEPSLATE || b == Blocks.SMOOTH_STONE || b == Blocks.QUARTZ_BLOCK
            || b == Blocks.OAK_LOG || b == Blocks.SPRUCE_LOG || b == Blocks.BIRCH_LOG;
    }
}
