package com.autism.seedcracker.corz;

import org.junit.jupiter.api.Test;
import static com.autism.seedcracker.corz.CorzGeodeMorphology.*;
import static com.autism.seedcracker.corz.CorzGeodeMorphologyTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CorzGeodeContextCacheTest {
    static void finish(CorzGeodeContextCache cache) {
        int steps = 0;
        while (cache.hasPending()) {
            int used = cache.advance(4096, Long.MAX_VALUE);
            assertTrue(used > 0 && used <= 4096); assertTrue(++steps < 100);
        }
    }
    @Test void completedContextsAreImmutableNumericResults() {
        var c = new CorzGeodeContextCache(); c.offer(one(-1, -2)); finish(c);
        var d = c.get(new Key(-1, -2)); assertNotNull(d); assertEquals(1, d.amethystCells());
        assertEquals(0, c.pendingCount()); assertEquals(1, c.size());
    }
    @Test void invalidationCancelsInFlightResultsBeforeTheyCanPublish() {
        var c = new CorzGeodeContextCache(); c.offer(one(0, 0)); c.advance(4096, Long.MAX_VALUE);
        c.invalidateAround(new Key(0, 0)); finish(c); assertEquals(0, c.size()); assertNull(c.get(new Key(0, 0)));
    }
    @Test void borderReplacementInvalidatesNeighboringCommittedAndQueuedResults() {
        var c = new CorzGeodeContextCache(); c.offer(one(-1, 0)); finish(c); c.offer(one(3, 0)); finish(c);
        c.offer(one(0, -1)); c.invalidateAround(new Key(0, 0)); finish(c);
        assertNull(c.get(new Key(-1, 0))); assertNull(c.get(new Key(0, -1))); assertNotNull(c.get(new Key(3, 0)));
    }
    @Test void newerSnapshotCannotPublishTheOldQueuedResult() {
        var c = new CorzGeodeContextCache(); c.offer(one(0, 0)); c.advance(4096, Long.MAX_VALUE);
        Builder b = air(0, 0); b.put(8, -12, 8, BUDDING); b.put(8, -11, 8, AMETHYST);
        c.offer(b.freeze()); finish(c);
        assertEquals(2, c.get(new Key(0, 0)).amethystCells()); assertEquals(1, c.get(new Key(0, 0)).buddingCells());
    }
    @Test void pendingLimitPreservesActiveProgressUnderContinuousProducerPressure() {
        var c = new CorzGeodeContextCache(); c.offer(one(0, 0)); c.advance(4096, Long.MAX_VALUE);
        for (int i = 2; i < 25 && c.get(new Key(0, 0)) == null; i++) {
            c.offer(one(i, 0)); assertTrue(c.pendingCount() <= CorzGeodeContextCache.MAX_PENDING);
            c.advance(4096, Long.MAX_VALUE);
        }
        assertNotNull(c.get(new Key(0, 0)), "Active job must not be continually evicted"); assertTrue(c.limited());
    }
    @Test void unchangedRescansCannotRepeatedlyRestartTheActiveAnalysis() {
        var c = new CorzGeodeContextCache();
        for (int i = 0; i < 20 && c.get(new Key(0, 0)) == null; i++) {
            c.offer(one(0, 0));
            c.advance(4096, Long.MAX_VALUE);
        }
        assertNotNull(c.get(new Key(0, 0)));
        assertEquals(1, c.get(new Key(0, 0)).amethystCells());
    }
    @Test void committedLimitEvictsWholeDescriptorsNotTheirMeasurements() {
        var c = new CorzGeodeContextCache(2, 2);
        for (int i = 0; i < 4; i++) { c.offer(one(i * 3, 0)); finish(c); }
        assertEquals(2, c.size()); assertNull(c.get(new Key(0, 0))); assertNull(c.get(new Key(3, 0)));
        assertEquals(1, c.get(new Key(6, 0)).amethystCells()); assertTrue(c.limited());
    }
    @Test void pruningClearsUnloadedOrOutOfRangeJobsAndResults() {
        var c = new CorzGeodeContextCache(); c.offer(one(-2, -2)); finish(c); c.offer(one(8, 0));
        c.prune(key -> key.x() >= 0); assertEquals(0, c.size()); assertEquals(1, c.pendingCount());
        c.prune(key -> false); finish(c); assertEquals(0, c.size()); assertEquals(0, c.pendingCount());
    }
    @Test void clearForDisableToggleOrWorldChangeCannotResurrectOldContext() {
        var c = new CorzGeodeContextCache(1, 1); c.offer(one(0, 0)); finish(c); c.offer(one(3, 0)); finish(c);
        assertTrue(c.limited()); c.offer(one(6, 0)); c.clear(); finish(c);
        assertEquals(0, c.size()); assertFalse(c.hasPending()); assertFalse(c.limited());
    }
    @Test void emptyReplacementRemovesOldContextWithoutLeavingStaleStats() {
        var c = new CorzGeodeContextCache(); c.offer(one(0, 0)); finish(c); c.offer(air(0, 0).freeze());
        assertNull(c.get(new Key(0, 0))); finish(c); assertEquals(0, c.size());
    }
    @Test void partialUnknownContextIsExplicitAndNotFormattedAsProof() {
        Builder b = new Builder(-1, -1); b.put(8, -12, 8, BUDDING);
        var c = new CorzGeodeContextCache(); c.offer(b.freeze()); finish(c);
        String text = c.summary(new Key(-1, -1));
        assertTrue(text.contains("partial; observed only")); assertTrue(text.contains("air/solid/unknown edges 0/0/6"));
        assertTrue(text.contains("budding 1")); assertFalse(text.contains("mined")); assertFalse(text.contains("base"));
        assertFalse(text.contains("crystals"));
    }
    @Test void budgetAndExpiredDeadlineNeverPerformUnboundedWork() {
        var c = new CorzGeodeContextCache(); c.offer(one(0, 0));
        assertEquals(0, c.advance(0, Long.MAX_VALUE)); assertEquals(0, c.advance(100, 0));
        assertEquals(5, c.advance(5, Long.MAX_VALUE)); assertEquals(0, c.size());
    }
    @Test void illegalCacheSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CorzGeodeContextCache(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CorzGeodeContextCache(129, 1));
        assertThrows(IllegalArgumentException.class, () -> new CorzGeodeContextCache(1, 3));
    }
}
