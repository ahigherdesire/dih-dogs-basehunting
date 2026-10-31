package com.autism.seedcracker.finder.larp;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Larp v9 RotatedDeepslateEsp.isRotated (source lines 142-144), adapted under user-supplied permission.
 * The original background world reads are replaced by bounded, client-thread section scans. */
public final class RotatedDeepslateScan {
    public static final int MAX_MARKERS_PER_CHUNK = 128;
    private final ChunkPos chunk;
    private final int bottom;
    private final LevelChunkSection[] sections;
    private final Set<BlockPos> positions = new LinkedHashSet<>();
    private int section, cell, count;

    public RotatedDeepslateScan(ChunkPos chunk, int bottom, LevelChunkSection[] sections) {
        this.chunk = java.util.Objects.requireNonNull(chunk);
        this.bottom = bottom;
        this.sections = sections.clone();
    }
    public boolean complete() { return section >= sections.length; }
    public int count() { return count; }
    public Set<BlockPos> positions() { return Set.copyOf(positions); }

    public static boolean rotated(BlockState state) {
        return state != null && state.is(Blocks.DEEPSLATE) && state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y;
    }

    public int step(int budget, long deadline) {
        int checked = 0;
        while (checked < Math.max(0, budget) && !complete() && System.nanoTime() < deadline) {
            var slice = sections[section];
            if (cell == 0 && (slice == null || slice.hasOnlyAir() || !slice.maybeHas(RotatedDeepslateScan::rotated))) {
                section++; checked++; continue;
            }
            int x = cell & 15, y = cell >>> 8, z = (cell >>> 4) & 15;
            if (rotated(slice.getBlockState(x, y, z))) {
                count++;
                if (positions.size() < MAX_MARKERS_PER_CHUNK) {
                    positions.add(new BlockPos(chunk.getMinBlockX() + x, (bottom + section) * 16 + y, chunk.getMinBlockZ() + z));
                }
            }
            checked++;
            if (++cell == 4096) { cell = 0; section++; }
        }
        return checked;
    }
}
