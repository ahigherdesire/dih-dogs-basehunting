package com.autism.seedcracker.finder.landmark;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignEvidenceTest {
    private final ChunkPos chunk = new ChunkPos(0, 0);
    private final BlockPos sign = new BlockPos(1, 64, 1);

    @Test void changedRemovedAndReappearingTextCanNotifyAgain() {
        var evidence = new SignEvidence();
        assertEquals(1, evidence.replaceChunk(chunk, Map.of(sign, "shop"), true).size());
        assertTrue(evidence.replaceChunk(chunk, Map.of(sign, "shop"), true).isEmpty());
        assertEquals(1, evidence.replaceChunk(chunk, Map.of(sign, "new shop"), true).size());
        evidence.replaceChunk(chunk, Map.of(), true);
        assertTrue(evidence.positions().isEmpty());
        assertEquals(1, evidence.replaceChunk(chunk, Map.of(sign, "new shop"), true).size());
    }

    @Test void removingTextThenRestoringItResetsNotification() {
        var evidence = new SignEvidence();
        evidence.replaceChunk(chunk, Map.of(sign, "shop"), true);
        assertTrue(evidence.replaceChunk(chunk, Map.of(sign, ""), true).isEmpty());
        assertEquals(1, evidence.replaceChunk(chunk, Map.of(sign, "shop"), true).size());
    }

    @Test void chunkRefreshDoesNotEraseOtherChunksAndPruningForgetsReports() {
        var evidence = new SignEvidence();
        var other = new BlockPos(-1, 70, 0);
        var neighbor = new ChunkPos(-1, 0);
        evidence.replaceChunk(chunk, Map.of(sign, "one"), true);
        evidence.replaceChunk(neighbor, Map.of(other, "two"), true);
        evidence.replaceChunk(chunk, Map.of(), true);
        assertEquals(java.util.Set.of(other), evidence.positions());
        evidence.prune(pos -> false);
        assertTrue(evidence.positions().isEmpty());
        assertEquals(1, evidence.replaceChunk(neighbor, Map.of(other, "two"), true).size());
        evidence.clear();
        assertEquals(1, evidence.replaceChunk(neighbor, Map.of(other, "two"), true).size());
    }

    @Test void bothSidesIncludeBackOnlyKeywords() {
        var front = side("", "");
        var back = side("  shop  ", "");
        assertEquals("shop", SignEvidence.text(front, back));
        front = side("", "owner");
        assertEquals("owner / shop", SignEvidence.text(front, back));
    }

    private static net.minecraft.world.level.block.entity.SignText side(String first, String second) {
        var a = net.minecraft.network.chat.Component.literal(first);
        var b = net.minecraft.network.chat.Component.literal(second);
        //? if >=26.3 {
        /*var lines = java.util.List.<net.minecraft.network.chat.Component>of(a, b,
            net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty());
        return new net.minecraft.world.level.block.entity.SignText(lines, lines, net.minecraft.world.item.DyeColor.BLACK, false);
        *///?} else {
        return new net.minecraft.world.level.block.entity.SignText().setMessage(0, a).setMessage(1, b);
        //?}
    }

    @Test void readingDisabledDoesNotConsumeNotifications() {
        var evidence = new SignEvidence();
        assertTrue(evidence.replaceChunk(chunk, Map.of(sign, "back-side keyword"), false).isEmpty());
        assertEquals(1, evidence.replaceChunk(chunk, Map.of(sign, "back-side keyword"), true).size());
    }
}
