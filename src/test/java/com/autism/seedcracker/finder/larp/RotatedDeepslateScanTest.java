package com.autism.seedcracker.finder.larp;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RotatedDeepslateScanTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static LevelChunkSection section(BlockState state) {
        return new LevelChunkSection(new PalettedContainer<>(state, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
    }
    private static BlockState horizontal() { return Blocks.DEEPSLATE.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X); }
    private static void finish(RotatedDeepslateScan scan) {
        int calls = 0;
        while (!scan.complete()) {
            int used = scan.step(83, Long.MAX_VALUE);
            assertTrue(used > 0 && used <= 83);
            assertTrue(++calls < 1000, "Scanner must make bounded progress");
        }
    }

    @Test void onlyHorizontalDeepslateMatches() {
        for (Direction.Axis axis : Direction.Axis.values())
            assertEquals(axis != Direction.Axis.Y, RotatedDeepslateScan.rotated(Blocks.DEEPSLATE.defaultBlockState().setValue(BlockStateProperties.AXIS, axis)));
        assertFalse(RotatedDeepslateScan.rotated(null));
        assertFalse(RotatedDeepslateScan.rotated(Blocks.STONE.defaultBlockState()));
        assertFalse(RotatedDeepslateScan.rotated(Blocks.BASALT.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
    }

    @Test void exactPositionsRespectNegativeChunksAndFullHeight() {
        var low = section(Blocks.AIR.defaultBlockState());
        low.setBlockState(15, 5, 9, horizontal());
        var high = section(Blocks.AIR.defaultBlockState());
        high.setBlockState(0, 15, 0, horizontal().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        var slices = new LevelChunkSection[12];
        slices[0] = low; slices[11] = high;
        var scan = new RotatedDeepslateScan(new ChunkPos(-2, -3), -4, slices);
        finish(scan);
        assertEquals(2, scan.count());
        assertEquals(java.util.Set.of(new BlockPos(-17, -59, -39), new BlockPos(-32, 127, -48)), scan.positions());
    }

    @Test void budgetsAndExpiredDeadlinesNeverConsumeUnboundedWork() {
        var scan = new RotatedDeepslateScan(new ChunkPos(0, 0), 0, new LevelChunkSection[] {section(horizontal())});
        assertEquals(0, scan.step(0, Long.MAX_VALUE));
        assertEquals(0, scan.step(-1, Long.MAX_VALUE));
        assertEquals(0, scan.step(100, System.nanoTime() - 1));
        assertEquals(0, scan.count());
        assertEquals(7, scan.step(7, Long.MAX_VALUE));
        assertEquals(7, scan.count());
        assertFalse(scan.complete());
        finish(scan);
        assertEquals(4096, scan.count(), "Outline limits must not truncate actual counting");
        assertEquals(RotatedDeepslateScan.MAX_MARKERS_PER_CHUNK, scan.positions().size());
        assertEquals(0, scan.step(100, Long.MAX_VALUE));
    }

    @Test void emptyAndNaturalSectionsAreCheapAndNeverFlagged() {
        var scan = new RotatedDeepslateScan(new ChunkPos(0, 0), 0, new LevelChunkSection[] {
            null, section(Blocks.AIR.defaultBlockState()), section(Blocks.DEEPSLATE.defaultBlockState())});
        assertEquals(2, scan.step(2, Long.MAX_VALUE));
        assertFalse(scan.complete());
        assertEquals(1, scan.step(2, Long.MAX_VALUE));
        assertTrue(scan.complete());
        assertEquals(0, scan.count());
        assertTrue(scan.positions().isEmpty());
        assertTrue(new RotatedDeepslateScan(new ChunkPos(0, 0), 0, new LevelChunkSection[0]).complete());
    }

    @Test void restartedScanDoesNotRetainReplacedOrRemovedEvidence() {
        var old = section(horizontal());
        var first = new RotatedDeepslateScan(new ChunkPos(0, 0), -4, new LevelChunkSection[] {old});
        assertEquals(8, first.step(8, Long.MAX_VALUE));
        var replacement = new RotatedDeepslateScan(new ChunkPos(0, 0), -4,
            new LevelChunkSection[] {section(Blocks.DEEPSLATE.defaultBlockState())});
        finish(replacement);
        assertEquals(0, replacement.count());
        assertTrue(replacement.positions().isEmpty());
    }
}
