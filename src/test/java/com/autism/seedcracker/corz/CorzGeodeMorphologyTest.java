package com.autism.seedcracker.corz;

import org.junit.jupiter.api.Test;
import static com.autism.seedcracker.corz.CorzGeodeMorphology.*;
import static org.junit.jupiter.api.Assertions.*;

class CorzGeodeMorphologyTest {
    static Builder air(int cx, int cz) {
        Builder b = new Builder(cx, cz);
        for (int y = MIN_Y - 1; y <= MAX_Y + 1; y++)
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) b.put(x, y, z, AIR);
        return b;
    }
    static Descriptor analyze(Builder builder) { return analyze(builder.freeze()); }
    static Descriptor analyze(Snapshot snapshot) {
        Analysis work = new Analysis(snapshot);
        int total = 0;
        while (!work.complete()) {
            int used = work.advance(1024, Long.MAX_VALUE);
            assertTrue(used > 0 && used <= 1024);
            total += used;
            assertTrue(total <= INTERIOR_CELLS * 2);
        }
        return work.result();
    }
    static Snapshot one(int cx, int cz) {
        Builder b = air(cx, cz); b.put(8, -12, 8, AMETHYST); return b.freeze();
    }

    @Test void unknownInputDoesNotBecomeAirOrSyntheticCrystals() {
        Descriptor d = analyze(new Builder(0, 0));
        assertEquals(0, d.amethystCells()); assertEquals(0, d.knownCells());
        assertEquals(0, d.airEdges()); assertTrue(d.knownAirFraction().isEmpty()); assertTrue(d.partial());
    }
    @Test void emptyKnownWindowHasNoFormationAndFiniteEmptyMetrics() {
        Descriptor d = analyze(air(0, 0));
        assertEquals(0, d.amethystCells()); assertEquals(0, d.verticalSpan());
        assertEquals(0, d.density()); assertEquals(0, d.meanRadius());
        assertEquals(INTERIOR_CELLS, d.knownCells()); assertFalse(d.partial());
    }
    @Test void oneObservedCellHasSixKnownAirEdgesNotASyntheticExtent() {
        Descriptor d = analyze(one(0, 0));
        assertEquals(1, d.amethystCells()); assertEquals(1, d.verticalSpan());
        assertEquals(1.0 / 256, d.density()); assertEquals(0, d.meanRadius());
        assertEquals(6, d.airEdges()); assertEquals(0, d.unknownEdges());
        assertEquals(1, d.knownAirFraction().orElseThrow()); assertFalse(d.partial());
    }
    @Test void naturalLayeredShellRemainsMeasurementsNotAPlayerOrMiningVerdict() {
        Builder b = air(0, 0);
        for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++) {
            int radius = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (radius >= 2) b.put(8 + dx, -12 + dy, 8 + dz, radius == 2 ? AMETHYST : radius == 3 ? CALCITE : BASALT);
        }
        Descriptor d = analyze(b);
        assertEquals(5 * 5 * 5 - 3 * 3 * 3, d.amethystCells());
        assertEquals(7 * 7 * 7 - 5 * 5 * 5, d.calciteCells());
        assertEquals(9 * 9 * 9 - 7 * 7 * 7, d.basaltCells());
        assertEquals(5, d.verticalSpan()); assertEquals(d.amethystCells() / (256.0 * 5), d.density());
        assertTrue(d.airEdges() > 0); assertTrue(d.solidEdges() > 0); assertEquals(0, d.unknownEdges());
        assertEquals(6 * d.amethystCells(), d.airEdges() + d.solidEdges() + d.internalEdges());
        assertFalse(java.util.Arrays.stream(Descriptor.class.getRecordComponents()).anyMatch(c -> c.getName().equals("hit")));
    }
    @Test void flatPlacementGetsASpanOfOneWithoutInventedThickness() {
        Builder b = air(0, 0);
        for (int x = 7; x <= 8; x++) for (int z = 7; z <= 8; z++) b.put(x, -10, z, AMETHYST);
        Descriptor d = analyze(b);
        assertEquals(4, d.amethystCells()); assertEquals(1, d.verticalSpan());
        assertEquals(4.0 / 256, d.density()); assertEquals(Math.sqrt(0.5), d.meanRadius(), 1e-12);
        assertEquals(8, d.centroidX()); assertEquals(-9.5, d.centroidY()); assertEquals(8, d.centroidZ());
    }
    @Test void buddingAtFirstCellDoesNotReproduceTheUnverifiedDonorEarlyReturn() {
        Builder b = air(0, 0); b.put(0, MIN_Y, 0, BUDDING); b.put(10, 10, 10, AMETHYST);
        Descriptor d = analyze(b);
        assertEquals(2, d.amethystCells()); assertEquals(1, d.buddingCells());
        assertEquals(10 - MIN_Y + 1, d.verticalSpan()); assertTrue(d.meanRadius() > 0);
    }
    @Test void missingChunkBorderIsUnknownNotAirOrAnInternalCrystal() {
        Builder b = air(0, 0); b.put(15, -10, 8, AMETHYST); b.put(16, -10, 8, UNKNOWN);
        Descriptor d = analyze(b);
        assertEquals(5, d.airEdges()); assertEquals(1, d.unknownEdges()); assertEquals(0, d.solidEdges());
        assertEquals(1.0, d.knownAirFraction().orElseThrow()); assertTrue(d.partial());
    }
    @Test void loadedAdjacentAmethystIsAnInternalEdgeNotAnExtraObservedCell() {
        Builder b = air(-1, 0); b.put(15, -10, 8, AMETHYST); b.put(16, -10, 8, BUDDING);
        Descriptor d = analyze(b);
        assertEquals(1, d.amethystCells()); assertEquals(0, d.buddingCells());
        assertEquals(1, d.internalEdges()); assertEquals(5, d.airEdges());
    }
    @Test void missingHeightNeighborsRemainUnknownAtBothEnds() {
        Builder b = air(0, 0);
        b.put(8, MIN_Y, 8, AMETHYST); b.put(8, MIN_Y - 1, 8, UNKNOWN);
        b.put(8, MAX_Y, 8, AMETHYST); b.put(8, MAX_Y + 1, 8, UNKNOWN);
        Descriptor d = analyze(b);
        assertEquals(2, d.unknownEdges()); assertEquals(10, d.airEdges()); assertTrue(d.partial());
    }
    @Test void unknownInteriorCellReducesCoverageWithoutChangingTheObservedCount() {
        Builder b = air(0, 0); b.put(8, -12, 8, AMETHYST); b.put(8, -11, 8, UNKNOWN);
        Descriptor d = analyze(b);
        assertEquals(INTERIOR_CELLS - 1, d.knownCells()); assertEquals(1, d.amethystCells()); assertEquals(1, d.unknownEdges());
    }
    @Test void allUnknownNeighborsHaveNoKnownAirFraction() {
        Builder b = new Builder(0, 0); b.put(8, -12, 8, AMETHYST);
        Descriptor d = analyze(b);
        assertEquals(6, d.unknownEdges()); assertEquals(0, d.airEdges()); assertTrue(d.knownAirFraction().isEmpty());
    }
    @Test void negativeChunkCoordinatesOnlyTranslateCentroidNotShape() {
        Builder a = air(0, 0), b = air(-2, -3);
        for (Builder value : new Builder[]{a, b}) { value.put(2, -15, 4, AMETHYST); value.put(4, -13, 6, BUDDING); }
        Descriptor x = analyze(a), y = analyze(b);
        assertEquals(x.centroidX() - 32, y.centroidX()); assertEquals(x.centroidZ() - 48, y.centroidZ());
        assertEquals(x.centroidY(), y.centroidY()); assertEquals(x.meanRadius(), y.meanRadius());
        assertEquals(x.density(), y.density()); assertEquals(x.airEdges(), y.airEdges());
    }
    @Test void compactAndDispersedSamplesWithEqualCountsHaveDifferentRadius() {
        Builder near = air(0, 0), far = air(0, 0);
        near.put(7, -10, 8, AMETHYST); near.put(8, -10, 8, AMETHYST);
        far.put(1, -10, 8, AMETHYST); far.put(14, -10, 8, AMETHYST);
        Descriptor a = analyze(near), b = analyze(far);
        assertEquals(a.amethystCells(), b.amethystCells()); assertEquals(a.density(), b.density());
        assertTrue(a.meanRadius() < b.meanRadius());
    }
    @Test void shellCountsDoNotIncludeHaloCellsOrUnseenMaterials() {
        Builder b = air(0, 0); b.put(8, -10, 8, AMETHYST); b.put(7, -10, 8, CALCITE);
        b.put(9, -10, 8, BASALT); b.put(-1, -10, 8, CALCITE); b.put(16, -10, 8, BASALT);
        Descriptor d = analyze(b);
        assertEquals(1, d.calciteCells()); assertEquals(1, d.basaltCells()); assertEquals(2, d.solidEdges());
    }
    @Test void immutableSnapshotsRejectFurtherBuilderMutation() {
        Builder b = air(0, 0); b.put(8, -10, 8, AMETHYST); Snapshot s = b.freeze();
        assertThrows(IllegalStateException.class, () -> b.put(8, -10, 8, AIR));
        assertThrows(IllegalStateException.class, b::freeze); assertEquals(1, analyze(s).amethystCells());
    }
    @Test void invalidCellsAndOverflowCoordinatesFailClosed() {
        Builder b = new Builder(0, 0);
        assertThrows(IllegalArgumentException.class, () -> b.put(-2, 0, 0, AIR));
        assertThrows(IllegalArgumentException.class, () -> b.put(0, 0, 0, (byte) 7));
        assertThrows(IllegalArgumentException.class, () -> new Key(Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Key(0, Integer.MIN_VALUE));
        assertEquals(UNKNOWN, b.freeze().kind(17, 0, 0));
    }
    @Test void evaluationHonorsCellBudgetDeadlineAndCompletionBoundary() {
        Analysis a = new Analysis(one(0, 0));
        assertEquals(0, a.advance(0, Long.MAX_VALUE)); assertEquals(0, a.advance(10, 0));
        assertEquals(7, a.advance(7, Long.MAX_VALUE)); assertFalse(a.complete());
        assertThrows(IllegalStateException.class, a::result);
        int total = 7;
        while (!a.complete()) { int used = a.advance(61, Long.MAX_VALUE); assertTrue(used > 0 && used <= 61); total += used; }
        assertEquals(INTERIOR_CELLS * 2, total); assertEquals(0, a.advance(100, Long.MAX_VALUE));
    }
    @Test void samplingWindowIncludesOnlyItsOneCellVerticalHalo() {
        assertTrue(intersectsSection(-5)); assertTrue(intersectsSection(-4)); assertTrue(intersectsSection(3));
        assertFalse(intersectsSection(-6)); assertFalse(intersectsSection(4));
        assertFalse(intersectsSection(Integer.MAX_VALUE));
    }
}
