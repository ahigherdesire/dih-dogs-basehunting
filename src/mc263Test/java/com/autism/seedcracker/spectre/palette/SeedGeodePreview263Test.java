package com.autism.seedcracker.spectre.palette;

import com.autism.seedcracker.spectre.chunks.seedscan.GeodePredictor;
import com.autism.seedcracker.spectre.chunks.seedscan.Native263TestSupport;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class SeedGeodePreview263Test {
    @BeforeAll static void bootstrap() { Native263TestSupport.bootstrap(); }

    @Test @Timeout(120)
    void detailedWorkerIncludesActualNativeBudsAndClustersAndStopsCleanly() throws Exception {
        Set<BlockPos> expected;
        var chunk = new ChunkPos(-8, 1);
        try (var predictor = new GeodePredictor(12345L)) {
            var generated = predictor.reconstruct(chunk.x(), chunk.z()).orElseThrow();
            assertEquals(GeodePredictor.Status.GENERATED_IN_SIMULATION, generated.status());
            assertTrue(generated.blocks().values().stream().anyMatch(AmethystScan::bud),
                "The real 26.3 fixture must generate crystals; equality must not be vacuous");
            expected = generated.blocks().entrySet().stream()
                .filter(entry -> AmethystScan.amethyst(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
            assertTrue(expected.size() > generated.amethyst().size() + generated.budding().size());
        }
        var preview = new SeedGeodePreview(12345L, new ChunkPos(0, 0), 1, false);
        try {
            preview.request(chunk, 1, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            SeedGeodePreview.View shown = null;
            while (System.nanoTime() < deadline && !preview.stopped()) {
                shown = preview.frame().views().stream().filter(view -> view.chunk().equals(chunk)
                    && view.status() == GeodePredictor.Status.GENERATED_IN_SIMULATION).findFirst().orElse(null);
                if (shown != null) break;
                Thread.sleep(20);
            }
            assertNotNull(shown, "Native worker did not publish complete detail: " + preview.frame().status());
            assertFalse(shown.limited());
            assertEquals(expected, shown.blocks(), "Detailed ESP must include every native amethyst-family write, including crystals");
            assertEquals(SeedGeodePreview.bounds(expected), shown.amethystBounds());
            assertThrows(UnsupportedOperationException.class, shown.blocks()::clear);
            preview.request(new ChunkPos(500, -500), 1, false);
            assertTrue(preview.frame().views().stream().allMatch(view -> Math.abs(view.chunk().x() - 500) <= 1
                && Math.abs(view.chunk().z() + 500) <= 1), "A replaced area cannot retain obsolete forecasts");
        } finally {
            preview.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!preview.stopped() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(preview.stopped(), "The native owner must close without leaking a worker");
            assertTrue(preview.frame().views().isEmpty());
        }
    }
}
