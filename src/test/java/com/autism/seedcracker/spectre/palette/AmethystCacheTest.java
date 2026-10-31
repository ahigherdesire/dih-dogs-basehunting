// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmethystCacheTest {
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);
    private static final PaletteScan.Result RAW = new PaletteScan.Result(true,
        List.of(new PaletteScan.Section(-4, true), new PaletteScan.Section(-2, true)));
    private static final AmethystScan.Result HIT = new AmethystScan.Result(Set.of(new BlockPos(1, -60, 1)), Set.of(-4));

    private static PaletteScan.Result wire(boolean used) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeShort(4096);
        out.writeShort(0);
        out.writeByte(4);
        out.writeByte(2);
        out.writeByte(1);
        out.writeByte(42);
        for (int i = 0; i < 256; i++) out.writeLong(used && i == 0 ? 1 : 0);
        out.writeByte(0);
        out.writeByte(0);
        return PaletteScan.read(ByteBuffer.wrap(bytes.toByteArray()), 1, -4, id -> id == 42);
    }

    @Test void rawUsedTargetsAreNotHiddenHintsAndNewPacketsReplaceOldEvidence() throws Exception {
        var cache = new AmethystCache();
        cache.packet(CHUNK, wire(false));
        assertEquals(List.of(-4), cache.palette(CHUNK));
        cache.store(CHUNK, HIT);
        cache.packet(CHUNK, wire(true));
        assertTrue(cache.palette(CHUNK).isEmpty());
        assertFalse(cache.results().containsKey(CHUNK));
        assertTrue(cache.geometry().boxes().isEmpty(), "Full replacement must not draw blocks from the old chunk");
        cache.store(CHUNK, new AmethystScan.Result(Set.of(), Set.of()));
        assertTrue(cache.results().isEmpty());
    }

    @Test void allUnusedSectionsSurviveButUsedPaletteEntriesDoNot() {
        var cache = new AmethystCache();
        cache.packet(CHUNK, new PaletteScan.Result(true, List.of(new PaletteScan.Section(-4, true),
            new PaletteScan.Section(-3, false), new PaletteScan.Section(1, true))));
        assertEquals(List.of(-4, 1), cache.palette(CHUNK));
    }

    @Test void aBlockDeltaExpiresOnlyItsRawSectionButInvalidatesNeighbouringLightResults() {
        var cache = new AmethystCache();
        ChunkPos neighbour = new ChunkPos(1, 0), distant = new ChunkPos(3, 0);
        cache.packet(CHUNK, RAW);
        cache.store(CHUNK, HIT);
        cache.store(neighbour, HIT);
        cache.store(distant, HIT);
        cache.block(new BlockPos(15, -60, 1));
        assertEquals(List.of(-2), cache.palette(CHUNK));
        assertEquals(HIT.blocks(), cache.results().get(CHUNK).blocks());
        assertEquals(HIT.blocks(), cache.results().get(neighbour).blocks());
        assertTrue(cache.results().get(CHUNK).hints().isEmpty());
        assertTrue(cache.results().get(neighbour).hints().isEmpty());
        assertEquals(HIT, cache.results().get(distant));
    }

    @Test void appliedLightInvalidatesBorderDependentResultsButDoesNotInventOrRefreshPalettes() {
        var cache = new AmethystCache();
        cache.packet(CHUNK, RAW);
        cache.store(CHUNK, HIT);
        cache.store(new ChunkPos(-1, 0), HIT);
        cache.invalidateAround(CHUNK);
        assertEquals(List.of(-4, -2), cache.palette(CHUNK));
        assertEquals(2, cache.results().size());
        assertTrue(cache.results().values().stream().allMatch(result -> result.hints().isEmpty()));
        assertTrue(cache.results().values().stream().allMatch(result -> result.blocks().equals(HIT.blocks())));
    }

    @Test void unloadRemovesRawAndVisibleEvidenceAndDoesNotQueueTheUnloadedChunk() {
        var cache = new AmethystCache();
        cache.packet(CHUNK, RAW);
        cache.store(CHUNK, HIT);
        cache.store(new ChunkPos(0, 1), HIT);
        cache.unload(CHUNK);
        assertTrue(cache.palette(CHUNK).isEmpty());
        assertFalse(cache.results().containsKey(CHUNK));
        assertEquals(HIT.blocks(), cache.results().get(new ChunkPos(0, 1)).blocks());
        assertTrue(cache.results().get(new ChunkPos(0, 1)).hints().isEmpty());
        for (ChunkPos dirty; (dirty = cache.pollDirty()) != null;) assertNotEquals(CHUNK, dirty);
    }

    @Test void malformedReplacementCannotLeaveAnOldHintBehind() {
        var cache = new AmethystCache();
        cache.packet(CHUNK, RAW);
        cache.store(CHUNK, HIT);
        cache.packet(CHUNK, new PaletteScan.Result(false, List.of()));
        assertTrue(cache.palette(CHUNK).isEmpty());
        assertFalse(cache.results().containsKey(CHUNK));
        assertTrue(cache.geometry().observed().isEmpty());
    }

    @Test void settingsResetPreservesWireEvidenceButSessionResetDropsEverything() {
        var cache = new AmethystCache();
        cache.packet(CHUNK, RAW);
        cache.store(CHUNK, HIT);
        cache.clearResults();
        assertFalse(cache.palette(CHUNK).isEmpty());
        assertTrue(cache.results().isEmpty());
        assertNull(cache.pollDirty());
        cache.store(CHUNK, HIT);
        cache.clear();
        assertTrue(cache.palette(CHUNK).isEmpty());
        assertTrue(cache.results().isEmpty());
        assertNull(cache.pollDirty());
    }

    @Test void movingOutOfRangePrunesEveryEvidenceQueue() {
        var cache = new AmethystCache();
        cache.packet(CHUNK, RAW);
        cache.store(CHUNK, HIT);
        cache.prune(pos -> false);
        assertTrue(cache.palette(CHUNK).isEmpty());
        assertTrue(cache.results().isEmpty());
        assertNull(cache.pollDirty());
    }

    @Test void repeatedDeltasCoalesceAndDoNotRepeatedlyRefreshUnrelatedRenderFeeds() {
        var cache = new AmethystCache();
        cache.store(CHUNK, HIT);
        assertTrue(cache.block(new BlockPos(1, -60, 1)));
        assertFalse(cache.block(new BlockPos(2, -60, 1)));
        Set<ChunkPos> dirty = new HashSet<>();
        for (ChunkPos pos; (pos = cache.pollDirty()) != null;) assertTrue(dirty.add(pos));
        assertEquals(9, dirty.size());
    }

    @Test void completedResultsDoNotAliasTheReusablePartialScanSets() {
        var blocks = new HashSet<>(HIT.blocks());
        var hints = new HashSet<>(HIT.hints());
        var result = new AmethystScan.Result(blocks, hints);
        blocks.clear();
        hints.clear();
        assertEquals(HIT, result);
        assertThrows(UnsupportedOperationException.class, () -> result.blocks().clear());
    }

    @Test void oversizedChunksAreRejectedWholeRatherThanPublishingTruncatedBounds() {
        var cache = new AmethystCache();
        cache.store(CHUNK, HIT);
        cache.geometry();
        Set<BlockPos> blocks = new HashSet<>();
        for (int i = 0; i <= AmethystScan.MAX_BLOCKS_PER_CHUNK; i++)
            blocks.add(new BlockPos(i & 15, i >> 8, (i >> 4) & 15));
        cache.store(CHUNK, new AmethystScan.Result(blocks, Set.of()));
        assertTrue(cache.limited());
        assertTrue(cache.results().isEmpty());
        assertTrue(cache.geometry().boxes().isEmpty());
        assertTrue(cache.geometry().observed().isEmpty());
        cache.clear();
        assertFalse(cache.limited());
    }

    @Test void addingAHintToAnAtCapacityChunkCannotLeaveRemovedCachedBoxes() {
        var cache = new AmethystCache();
        Set<BlockPos> blocks = new HashSet<>();
        for (int i = 0; i < AmethystCache.MAX_MARKERS; i++) blocks.add(new BlockPos(i & 15, i >> 8, (i >> 4) & 15));
        cache.store(CHUNK, new AmethystScan.Result(blocks, Set.of()));
        assertFalse(cache.geometry().boxes().isEmpty());
        cache.store(CHUNK, new AmethystScan.Result(blocks, Set.of(-4)));
        assertTrue(cache.limited());
        assertTrue(cache.results().isEmpty());
        assertTrue(cache.geometry().boxes().isEmpty());
        assertTrue(cache.geometry().observed().isEmpty());
        assertTrue(cache.hints(true).isEmpty());
    }

    @Test void actualBlockDeltasSplitAFormationWithoutDeletingUnchangedEvidence() {
        var cache = new AmethystCache();
        BlockPos middle = new BlockPos(1, 0, 0);
        var result = new AmethystScan.Result(Set.of(BlockPos.ZERO, middle, new BlockPos(2, 0, 0)), Set.of());
        cache.store(CHUNK, result);
        assertEquals(1, cache.geometry().formations());
        assertEquals(1, cache.takeNotifications());
        cache.block(middle, false);
        assertEquals(2, cache.geometry().formations());
        assertEquals(2, cache.geometry().observed().size());
        assertEquals(0, cache.takeNotifications());
        cache.block(middle, true);
        assertEquals(1, cache.geometry().formations());
        assertEquals(result.blocks(), cache.geometry().observed());
        assertEquals(0, cache.takeNotifications());
    }

    @Test void packetFloodsAndDenseResultsHaveHardMemoryCaps() {
        var cache = new AmethystCache();
        for (int x = 0; x <= AmethystCache.MAX_CHUNKS; x++) cache.packet(new ChunkPos(x, 0), RAW);
        assertTrue(cache.palette(CHUNK).isEmpty());
        int queued = 0;
        while (cache.pollDirty() != null) queued++;
        assertTrue(queued <= AmethystCache.MAX_CHUNKS);
        Set<BlockPos> blocks = new HashSet<>();
        for (int i = 0; i < 8192; i++) blocks.add(new BlockPos(i & 15, i >> 8, (i >> 4) & 15));
        for (int x = 0; x < 20; x++) {
            final int offset = x * 16;
            Set<BlockPos> moved = blocks.stream().map(pos -> pos.offset(offset, 0, 0)).collect(java.util.stream.Collectors.toSet());
            cache.store(new ChunkPos(x, 0), new AmethystScan.Result(moved, Set.of()));
        }
        assertTrue(cache.results().values().stream().mapToInt(AmethystScan.Result::size).sum() <= AmethystCache.MAX_MARKERS);
        assertFalse(cache.results().containsKey(CHUNK));
        assertTrue(cache.limited());
        assertTrue(cache.results().values().stream().allMatch(result -> result.blocks().size() == 8192));
    }
}
