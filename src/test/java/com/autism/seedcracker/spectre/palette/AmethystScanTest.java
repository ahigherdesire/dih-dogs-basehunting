// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmethystScanTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static LevelChunkSection section(Block fill) {
        return new LevelChunkSection(new PalettedContainer<>(fill.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
    }

    @Test void markersAreActualAmethystBlocksIncludingClustersNotChosenAirOrSectionCentres() {
        var section = section(Blocks.AIR);
        Block[] blocks = {Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.AMETHYST_CLUSTER, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_BLOCK};
        for (int x = 0; x < blocks.length; x++) section.setBlockState(x, 3, 7, blocks[x].defaultBlockState());
        var result = AmethystScan.observe(section, new ChunkPos(-2, 3), -4);
        assertEquals(blocks.length, result.blocks().size());
        for (int x = 0; x < blocks.length; x++) assertTrue(result.blocks().contains(new BlockPos(-32 + x, -61, 55)));
        assertFalse(result.blocks().contains(new BlockPos(-24, -56, 56)));
        assertTrue(result.geode());
    }

    @Test void removedTargetsLeftInTheLocalPaletteNeverBecomeObservedBlocks() {
        var section = section(Blocks.STONE);
        section.setBlockState(2, 2, 2, Blocks.SMALL_AMETHYST_BUD.defaultBlockState());
        section.setBlockState(2, 2, 2, Blocks.STONE.defaultBlockState());
        assertTrue(section.getStates().maybeHas(AmethystScan::bud));
        var result = AmethystScan.observe(section, new ChunkPos(0, 0), -4);
        assertTrue(result.blocks().isEmpty());
        assertFalse(result.geode());
    }

    @Test void randomUnusedPaletteNoiseRequiresExplicitOptInWithoutObservedGeodeMaterial() {
        assertFalse(AmethystScan.paletteHint(true, false, false));
        assertTrue(AmethystScan.paletteHint(true, false, true));
        assertTrue(AmethystScan.paletteHint(true, true, false));
        assertFalse(AmethystScan.paletteHint(false, true, true));
        assertFalse(AmethystScan.paletteHint(false, false, true));
    }

    @Test void hintsCoverTheWholeSectionAndNeverClaimAnExactBlockOrMergeVerticalSections() {
        var first = AmethystScan.hintBox(new ChunkPos(-2, 3), -4);
        var second = AmethystScan.hintBox(new ChunkPos(-2, 3), -2);
        assertEquals(-32, first.minX);
        assertEquals(-16, first.maxX);
        assertEquals(-64, first.minY);
        assertEquals(-48, first.maxY);
        assertEquals(48, first.minZ);
        assertEquals(64, first.maxZ);
        assertEquals(16, first.getXsize());
        assertEquals(16, first.getYsize());
        assertEquals(16, first.getZsize());
        assertFalse(first.intersects(second));
    }

    @Test void denseShellsRetainEveryBlockAndTheFarCornerBud() {
        var section = section(Blocks.AMETHYST_BLOCK);
        section.setBlockState(15, 15, 15, Blocks.AMETHYST_CLUSTER.defaultBlockState());
        var result = AmethystScan.observe(section, new ChunkPos(0, 0), 0);
        assertEquals(4096, result.blocks().size());
        assertTrue(result.blocks().contains(new BlockPos(15, 15, 15)));
        assertTrue(result.blocks().contains(new BlockPos(15, 15, 14)));
        for (BlockPos pos : result.blocks()) assertTrue(AmethystScan.amethyst(section.getBlockState(pos.getX(), pos.getY(), pos.getZ())));
    }

    @Test void distantNaturalEmittersInAdjacentSectionsAndChunksVetoOtherwisePlausibleGlow() {
        var sections = new LevelChunkSection[27];
        var lights = new DataLayer[27];
        for (int i = 0; i < 27; i++) { sections[i] = section(Blocks.AIR); lights[i] = new DataLayer(); }
        sections[13].setBlockState(15, 15, 15, Blocks.BUDDING_AMETHYST.defaultBlockState());
        int[] edge = {0, 4, 0, 0, 0, 0};
        boolean[] openings = {false, true, false, false, false, false};
        assertTrue(AmethystScan.hiddenGlow(0, edge, openings, true, false));
        assertTrue(AmethystScan.clearEmitterHalo(sections, lights));
        for (int index : new int[]{14, 22, 16, 0, 26}) {
            for (Block natural : new Block[]{Blocks.LAVA, Blocks.GLOWSTONE, Blocks.GLOW_LICHEN, Blocks.SHROOMLIGHT}) {
                var state = natural.defaultBlockState();
                if (natural == Blocks.GLOW_LICHEN)
                    state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH, true);
                sections[index].setBlockState(4, 4, 4, state);
                assertFalse(AmethystScan.observe(sections[13], new ChunkPos(0, 0), 0).foreignEmitter());
                assertTrue(AmethystScan.hiddenGlow(0, edge, openings, true, false));
                assertFalse(AmethystScan.clearEmitterHalo(sections, lights), index + " " + natural);
                sections[index].setBlockState(4, 4, 4, Blocks.AIR.defaultBlockState());
            }
        }
        // A removed emitter left in a palette is not a current source.
        assertTrue(AmethystScan.clearEmitterHalo(sections, lights));
        sections[0] = null;
        assertFalse(AmethystScan.clearEmitterHalo(sections, lights));
        sections[0] = section(Blocks.AIR);
        lights[26] = null;
        assertFalse(AmethystScan.clearEmitterHalo(sections, lights));
    }

    @Test void knownLavaAndNaturalGlowAreNotAmethystAndVetoSectionLightInference() {
        for (Block natural : new Block[]{Blocks.LAVA, Blocks.GLOW_LICHEN, Blocks.GLOWSTONE, Blocks.TORCH}) {
            var section = section(Blocks.AIR);
            section.setBlockState(0, 0, 0, Blocks.BUDDING_AMETHYST.defaultBlockState());
            var state = natural.defaultBlockState();
            if (natural == Blocks.GLOW_LICHEN)
                state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH, true);
            section.setBlockState(1, 0, 0, state);
            var result = AmethystScan.observe(section, new ChunkPos(0, 0), 0);
            assertEquals(Set.of(BlockPos.ZERO), result.blocks());
            assertTrue(result.foreignEmitter(), natural.toString());
        }
    }

    @Test void lightPeaksNeedBuddingSupportKnownLightAndAnAttenuatingNeighbour() {
        int[] falling = {3, 3, 3, 0, 3, 3};
        assertTrue(AmethystScan.hiddenBudLight(0, 4, falling, true, false));
        assertFalse(AmethystScan.hiddenBudLight(0, 4, falling, false, false));
        assertFalse(AmethystScan.hiddenBudLight(0, 4, falling, true, true));
        assertFalse(AmethystScan.hiddenBudLight(1, 4, falling, true, false));
        assertFalse(AmethystScan.hiddenBudLight(0, 4, new int[6], true, false));
        assertFalse(AmethystScan.hiddenBudLight(0, 3, new int[6], true, false));
        assertFalse(AmethystScan.hiddenBudLight(0, 4, new int[]{3, 3, 5, 0, 3, 3}, true, false));
        assertFalse(AmethystScan.hiddenBudLight(0, 4, new int[]{3, 3, -1, 0, 3, 3}, true, false));
    }

    @Test void glowEdgesRejectOrdinaryGradientsUnknownBordersAndUncorroboratedLight() {
        int[] values = {0, 4, 0, 0, 0, 0};
        boolean[] open = {false, true, false, false, false, false};
        assertTrue(AmethystScan.hiddenGlow(0, values, open, true, false));
        assertFalse(AmethystScan.hiddenGlow(0, values, open, false, false));
        assertFalse(AmethystScan.hiddenGlow(0, values, open, true, true));
        assertFalse(AmethystScan.hiddenGlow(3, values, open, true, false));
        values[5] = -1;
        assertFalse(AmethystScan.hiddenGlow(0, values, open, true, false));
    }

    @Test void missingLightLayersStayUnknownButPresentEmptyLayersAreActuallyDark() {
        DataLayer[] layers = new DataLayer[27];
        ChunkPos chunk = new ChunkPos(-1, 1);
        assertEquals(-1, AmethystScan.lightAt(layers, chunk, -4, -16, -64, 16));
        layers[13] = new DataLayer();
        assertEquals(0, AmethystScan.lightAt(layers, chunk, -4, -16, -64, 16));
        layers[13].set(0, 0, 0, 4);
        assertEquals(4, AmethystScan.lightAt(layers, chunk, -4, -16, -64, 16));
        assertEquals(-1, AmethystScan.lightAt(layers, chunk, -4, -17, -64, 16));
        assertEquals(-1, AmethystScan.lightAt(layers, chunk, -4, -16, -81, 16));
    }
}
