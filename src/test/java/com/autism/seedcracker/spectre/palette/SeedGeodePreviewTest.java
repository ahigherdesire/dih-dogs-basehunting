package com.autism.seedcracker.spectre.palette;

import com.autism.seedcracker.spectre.chunks.seedscan.GeodePredictor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedGeodePreviewTest {
    @Test void boundsContainEveryAmethystCellWithoutIncludingTheWholeShell() {
        Set<BlockPos> cells = Set.of(new BlockPos(-33, -59, -2), new BlockPos(-31, -50, 3));
        var bounds = SeedGeodePreview.bounds(cells);
        assertEquals(new GeodePredictor.Bounds(-33, -59, -2, -31, -50, 3), bounds);
        assertTrue(cells.stream().allMatch(bounds::contains));
        assertNull(SeedGeodePreview.bounds(Set.of()));
    }

    @Test void renderDataCannotRetainMutablePositionOrCollectionAliases() {
        var position = new BlockPos.MutableBlockPos(-3, -20, 7);
        Set<BlockPos> cells = new HashSet<>(); cells.add(position);
        var bounds = new GeodePredictor.Bounds(-4, -21, 6, -2, -19, 8);
        var view = new SeedGeodePreview.View(new ChunkPos(-1, 0), position, bounds,
            GeodePredictor.Status.GENERATED_IN_SIMULATION, bounds, cells, false);
        position.set(100, 100, 100); cells.clear();
        assertEquals(new BlockPos(-3, -20, 7), view.origin());
        assertEquals(Set.of(new BlockPos(-3, -20, 7)), view.blocks());
        assertThrows(UnsupportedOperationException.class, () -> view.blocks().clear());
        var list = new ArrayList<SeedGeodePreview.View>(); list.add(view);
        var frame = new SeedGeodePreview.Frame(list, 1, 9, 8, "Generating");
        list.clear();
        assertEquals(1, frame.views().size());
        assertThrows(UnsupportedOperationException.class, () -> frame.views().clear());
    }

    @Test void invalidAreasAreRejectedBeforeCreatingAWorker() {
        assertThrows(IllegalArgumentException.class, () -> new SeedGeodePreview(0, new ChunkPos(0, 0), 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SeedGeodePreview(0, new ChunkPos(0, 0), SeedGeodePreview.MAX_RADIUS + 1, false));
        assertThrows(IllegalArgumentException.class, () -> new SeedGeodePreview(0, null, 1, false));
    }

    @Test void previewDefaultsAndSeedPrivacyRemainExplicit() throws Exception {
        String module = Files.readString(Path.of("src", "main", "java", "com", "autism", "seedcracker", "modules", "AmethystEspModule.java"));
        assertTrue(module.contains("new BoolSetting(\"seed-preview\", \"Seed-based preview (predictions)\", false)"));
        assertTrue(module.contains("new BoolSetting(\"seed-unverified-manual\", \"Allow unverified manual seed\", false)"));
        assertTrue(module.contains("settingsShareable() { return false; }"));
        assertTrue(module.contains("if (choice.verified() && !choice.remembered().equals(rememberedSeed.get()))"));
        assertTrue(module.contains("CommonLifecycleEvents.TAGS_LOADED.register"));
        assertTrue(module.contains("level.isDebug() || level.getLevelData().isFlat"));
        assertFalse(module.contains("BaseTracker."));
        assertFalse(module.contains("FinderReport"));
        assertTrue(Files.readString(Path.of("src", "main", "resources", "seedcrackerx.aw"))
            .contains("ClientLevel$ClientLevelData isFlat Z"));
    }

    @Test void backgroundOwnershipAndCurrentAreaPublicationAreNotLiveWorldReads() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "autism", "seedcracker", "spectre", "palette", "SeedGeodePreview.java"));
        assertFalse(source.contains("Minecraft.getInstance"));
        assertFalse(source.contains("ClientLevel"));
        assertFalse(source.contains("getBlockEntity("));
        assertTrue(source.contains("try (var predictor = new GeodePredictor(seed))"));
        assertTrue(source.contains("worker.setDaemon(true)"));
        assertTrue(source.contains("worker.interrupt()"));
        assertTrue(source.contains("if (closed || stopped) return"));
        assertTrue(source.contains("current.chunks.contains(view.chunk.pack())"));
    }
}
