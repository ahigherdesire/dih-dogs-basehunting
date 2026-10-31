// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmethystBoundsRegressionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void denseEvidenceMustNotBeTheFirst64Blocks() {
        var section = new LevelChunkSection(new PalettedContainer<>(Blocks.BUDDING_AMETHYST.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        var observed = AmethystScan.observe(section, new ChunkPos(-1, -1), -1);
        assertEquals(4096, observed.blocks().size());
        assertTrue(observed.blocks().contains(new BlockPos(-1, -1, -1)));
    }

    @Test void closedShellMustRetainAllSixFacesNotOnlyTheFirstSampledFace() {
        var section = new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15)
                section.setBlockState(x, y, z, Blocks.AMETHYST_BLOCK.defaultBlockState());
        var observed = AmethystScan.observe(section, new ChunkPos(2, -3), -2);
        assertEquals(4096 - 14 * 14 * 14, observed.blocks().size());
        assertTrue(observed.blocks().contains(new BlockPos(47, -17, -33)));
        assertTrue(observed.blocks().contains(new BlockPos(32, -17, -48)));
        assertFalse(observed.blocks().contains(new BlockPos(40, -24, -40)));
    }
}
