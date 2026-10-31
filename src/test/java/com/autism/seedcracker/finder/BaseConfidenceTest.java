package com.autism.seedcracker.finder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseConfidenceTest {
    @Test void measuresFullHeightNaturalChunkScanCost() {
        var sections = new LevelChunkSection[24];
        for (int index = 0; index < sections.length; index++) sections[index] = new LevelChunkSection(
            new PalettedContainer<>(Blocks.STONE.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        for (int iteration = 0; iteration < 100; iteration++) BaseConfidence.scoreSections(sections);
        var elapsed = new long[100];
        for (int iteration = 0; iteration < elapsed.length; iteration++) {
            long start = System.nanoTime();
            assertEquals(0, BaseConfidence.scoreSections(sections).score());
            elapsed[iteration] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(elapsed);
        System.out.printf(java.util.Locale.ROOT, "BaseConfidence fixture: 24 stone sections, median=%.3fms p95=%.3fms%n",
            elapsed[50] / 1_000_000.0, elapsed[94] / 1_000_000.0);
    }

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static LevelChunkSection section(Block... blocks) {
        var states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        var section = new LevelChunkSection(states, null);
        for (int index = 0; index < blocks.length; index++) section.setBlockState(index & 15, index >> 8, (index >> 4) & 15, blocks[index].defaultBlockState());
        return section;
    }

    @Test void nonEntityWorkstationsAndAnvilVariantsAreCountedOnce() {
        assertEquals(15, BaseConfidence.scoreSections(new LevelChunkSection[] {
            section(Blocks.CRAFTING_TABLE, Blocks.GRINDSTONE, Blocks.DAMAGED_ANVIL)
        }).score());
        assertEquals(5, BaseConfidence.scoreSections(new LevelChunkSection[] { section(Blocks.BREWING_STAND) }).score());
    }

    @Test void dyedStorageHasTheSameEvidenceAsUncolouredStorage() {
        assertEquals(24, BaseConfidence.scoreSections(new LevelChunkSection[] {
            section(Blocks.SHULKER_BOX, Blocks.DYED_SHULKER_BOX.red(), Blocks.DYED_SHULKER_BOX.black())
        }).score());
        assertEquals(8, BaseConfidence.scoreSections(new LevelChunkSection[] { section(Blocks.CHEST) }).score());
    }

    @Test void plainStoneAndSparseMineshaftSignalsStayBelowTheAlertThreshold() {
        var stone = new LevelChunkSection(new PalettedContainer<>(Blocks.STONE.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        assertEquals(0, BaseConfidence.scoreSections(new LevelChunkSection[] { stone }).score());
        assertTrue(BaseConfidence.scoreSections(new LevelChunkSection[] {
            section(Blocks.CHEST, Blocks.TORCH, Blocks.RAIL, Blocks.OAK_PLANKS)
        }).score() < 80);
    }

    @Test void lavaGlowingTerrainAndAmethystNeverBecomeBuildingEvidence() {
        for (Block block : new Block[] {Blocks.LAVA, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT,
                Blocks.SEA_LANTERN, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER}) {
            var section = new LevelChunkSection(new PalettedContainer<>(block.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
            assertFalse(BaseConfidence.buildEvidenceSections(new LevelChunkSection[] {section}), block.toString());
            assertEquals(0, BaseConfidence.scoreSections(new LevelChunkSection[] {section}).score(), block.toString());
        }
    }

    @Test void naturalCompatibleVillageTempleAndCityFixturesCannotReachHighEvidenceScores() {
        var blocks = new java.util.ArrayList<Block>();
        for (int i = 0; i < 12; i++) blocks.add(Blocks.CHEST);
        for (int i = 0; i < 30; i++) blocks.add(Blocks.TORCH);
        for (int i = 0; i < 8; i++) { blocks.add(Blocks.FURNACE); blocks.add(Blocks.BREWING_STAND); blocks.add(Blocks.REDSTONE_WIRE); }
        for (int i = 0; i < 4; i++) { blocks.add(Blocks.COMPARATOR); blocks.add(Blocks.DISPENSER); blocks.add(Blocks.STICKY_PISTON); }
        var sections = new LevelChunkSection[] {section(blocks.toArray(Block[]::new))};
        assertFalse(BaseConfidence.buildEvidenceSections(sections));
        assertTrue(BaseConfidence.scoreSections(sections).score() <= 35);
        assertTrue(BaseConfidence.scoreSections(sections).topReason().startsWith("Natural-compatible:"));
    }

    @Test void distinctStorageAndAutomationSupportInvestigationWithoutRequiringNaturalLight() {
        for (Block block : new Block[] {Blocks.ENDER_CHEST, Blocks.DYED_SHULKER_BOX.blue(), Blocks.BEACON, Blocks.CRAFTER}) {
            assertTrue(BaseConfidence.buildEvidenceSections(new LevelChunkSection[] {section(block)}), block.toString());
        }
        assertFalse(BaseConfidence.buildEvidenceSections(new LevelChunkSection[] {section(Blocks.SPAWNER, Blocks.CHEST, Blocks.LAVA)}));
        assertFalse(BaseConfidence.buildEvidenceSections(new LevelChunkSection[] {section(Blocks.HOPPER, Blocks.CHEST)}));
        assertTrue(BaseConfidence.buildEvidenceSections(new LevelChunkSection[] {section(Blocks.HOPPER, Blocks.OBSERVER, Blocks.CHEST, Blocks.LAVA)}));
    }

    @Test void mixedStorageRoomRetainsStrongEvidence() {
        var result = BaseConfidence.scoreSections(new LevelChunkSection[] { section(
            Blocks.CHEST, Blocks.CHEST, Blocks.BARREL, Blocks.DYED_SHULKER_BOX.red(), Blocks.DYED_SHULKER_BOX.black(), Blocks.HOPPER,
            Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.BREWING_STAND,
            Blocks.TORCH, Blocks.TORCH, Blocks.LANTERN, Blocks.LANTERN, Blocks.REDSTONE_LAMP) });
        assertEquals(83, result.score());
        assertEquals("6 storage", result.topReason());
    }

    @Test void everyDistinctDeviceHasModestExplainedEvidenceEvenInStone() {
        for (Block block : new Block[] {Blocks.ENDER_CHEST, Blocks.SHULKER_BOX, Blocks.DYED_SHULKER_BOX.blue(),
                Blocks.ENCHANTING_TABLE, Blocks.CRAFTER, Blocks.BEACON, Blocks.CONDUIT, Blocks.RESPAWN_ANCHOR}) {
            for (Block fill : new Block[] {Blocks.AIR, Blocks.STONE}) {
                var sections = new LevelChunkSection[] {FinderEvidenceEvaluationTest.section(fill, block)};
                var result = BaseConfidence.scoreSections(sections);
                assertTrue(BaseConfidence.buildEvidenceSections(sections), block.toString());
                assertEquals(BaseConfidence.Evidence.BUILDING, result.evidence());
                assertTrue(result.score() > 0 && result.score() <= 8, block + ": " + result);
                assertFalse(result.topReason().isBlank());
            }
        }
    }

    @Test void unavailableOrPartialDataAbstainsInsteadOfMasqueradingAsObservedAir() {
        for (LevelChunkSection[] sections : new LevelChunkSection[][] {null, {}, {null}, {section(Blocks.BEACON), null}}) {
            assertFalse(BaseConfidence.buildEvidenceSections(sections));
            var result = BaseConfidence.scoreSections(sections);
            assertEquals(0, result.score());
            assertEquals(BaseConfidence.Evidence.UNKNOWN, result.evidence());
        }
        assertEquals(BaseConfidence.Evidence.UNKNOWN, BaseConfidence.score(null).evidence());
        assertEquals(BaseConfidence.Evidence.NATURAL_COMPATIBLE,
            BaseConfidence.scoreSections(new LevelChunkSection[] {section(Blocks.AIR)}).evidence());
        var legacy = new BaseConfidence.Result(80, "explicit numeric score");
        assertEquals(80, legacy.score());
        assertEquals(BaseConfidence.Evidence.UNSPECIFIED, legacy.evidence());
    }

    @Test void lowNonBuildingScoresAlsoExplainTheObservedClue() {
        for (Block block : new Block[] {Blocks.CRAFTING_TABLE, Blocks.TORCH, Blocks.OBSERVER}) {
            var result = BaseConfidence.scoreSections(new LevelChunkSection[] {section(block)});
            assertEquals(BaseConfidence.Evidence.NATURAL_COMPATIBLE, result.evidence());
            assertTrue(result.score() > 0 && result.score() <= BaseConfidence.NATURAL_COMPATIBLE_LIMIT);
            assertFalse(result.topReason().isBlank());
        }
    }
}