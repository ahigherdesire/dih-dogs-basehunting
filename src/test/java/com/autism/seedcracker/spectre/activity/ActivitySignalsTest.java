package com.autism.seedcracker.spectre.activity;

import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivitySignalsTest {
    @Test void quantumRangeUsesTheOriginalCutCornersAndSignedChunkKeys() {
        assertEquals(265, QuantumRules.scanRange(-20, 31).size());
        assertTrue(QuantumRules.inScanRange(-12, 36, -20, 31));
        assertFalse(QuantumRules.inScanRange(-12, 37, -20, 31));
        assertTrue(QuantumRules.inScanRange(-15, 39, -20, 31));
        assertFalse(QuantumRules.inScanRange(-14, 39, -20, 31));
        assertFalse(QuantumRules.inScanRange(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
        long key = QuantumRules.key(-987, -654);
        assertEquals(-987, QuantumRules.x(key));
        assertEquals(-654, QuantumRules.z(key));
        assertEquals(new ChunkPos(-987, -654).pack(), key);
    }

    @Test void quantumIntersectsCandidateAreasButRestartsWhenSignalsDoNotOverlap() {
        Set<Long> first = QuantumRules.narrow(Set.of(), 0, 0);
        assertEquals(361, first.size());
        Set<Long> overlap = QuantumRules.narrow(first, 10, 0);
        assertEquals(171, overlap.size());
        assertTrue(overlap.contains(QuantumRules.key(1, 9)));
        assertFalse(overlap.contains(QuantumRules.key(0, 0)));
        Set<Long> restarted = QuantumRules.narrow(overlap, 40, -40);
        assertEquals(361, restarted.size());
        assertTrue(restarted.contains(QuantumRules.key(40, -40)));
    }

    @Test void quantumKeepsTheAsymmetricOriginalBlockExclusions() {
        assertTrue(QuantumRules.ignoredChange("minecraft:stone", "minecraft:chest"));
        assertTrue(QuantumRules.ignoredChange("minecraft:amethyst_block", "minecraft:air"));
        assertTrue(QuantumRules.ignoredChange("minecraft:dirt", "minecraft:deepslate_diamond_ore"));
        assertTrue(QuantumRules.ignoredChange("minecraft:dirt", "minecraft:observer"));
        assertFalse(QuantumRules.ignoredChange("minecraft:dirt", "minecraft:chest"));
        assertFalse(QuantumRules.ignoredChange("minecraft:observer", "minecraft:air"));
        assertFalse(QuantumRules.ignoredChange("minecraft:dirt", "example:stone"));
    }

    @Test void lightGradientPreservesFillAlphaAndUsesDarkOpaqueOutlines() {
        assertEquals(0x80ffffff, ActivityRender.lightFill(0x80ffcc00, 15));
        int dim = ActivityRender.lightFill(0x80ffcc00, 5);
        assertEquals(85, dim >>> 24);
        assertEquals(0xff, ActivityRender.lightLine(dim) >>> 24);
        assertEquals(0xff737373, ActivityRender.lightLine(0x80ffffff));
        assertEquals(0x00ffffff, ActivityRender.lightFill(0x00ffcc00, 15));
    }
}
