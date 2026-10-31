package com.autism.seedcracker.finder.growth;

import java.util.function.IntUnaryOperator;

/** A resumable bottom-up section scan. Columns use x = cell >> 4 & 15, z = cell & 15. */
public final class GrowthScan {
    public static final int NONE = 0, VINE = 1, DRIPSTONE = 2, BERRY = 3;
    public record Result(int score, int maxVine, double sourceX, double sourceZ) {}

    private final int sections;
    private final int[] vineRuns = new int[256];
    private int section, cell, maxVine;
    private boolean pendingVines;
    private long vineBlocks, dripBlocks, berryBlocks;
    private double sumX, sumZ, sumWeight;

    public GrowthScan(int sections) {
        if (sections < 0 || sections > Integer.MAX_VALUE / 4096) throw new IllegalArgumentException("sections");
        this.sections = sections;
    }

    public int section() { return section; }
    public boolean atSectionStart() { return cell == 0; }
    public boolean complete() { return section == sections; }

    /** Reads at most maxCells states in this section, checking the deadline every 64 states. */
    public int step(IntUnaryOperator typeAt, int maxCells, long deadline) {
        int read = 0;
        while (!complete() && read < maxCells && cell < 4096) {
            if ((read & 63) == 0 && System.nanoTime() >= deadline) break;
            int column = cell & 255;
            int type = typeAt.applyAsInt(cell);
            if (type == VINE) { vineRuns[column]++; pendingVines = true; }
            else {
                finishVine(column);
                if (type == DRIPSTONE) { dripBlocks++; weight(column, 2.5); }
                else if (type == BERRY) { berryBlocks++; weight(column, 1); }
            }
            cell++;
            read++;
        }
        if (cell == 4096) {
            cell = 0;
            if (++section == sections) finishVines();
        }
        return read;
    }

    /** Breaks vine columns without reading air; returns the work charged to the tick budget. */
    public int skipEmptySection() {
        if (complete() || cell != 0) throw new IllegalStateException("Not at a section start");
        int work = pendingVines ? 256 : 1;
        finishVines();
        section++;
        return work;
    }

    public Result result() {
        if (!complete()) throw new IllegalStateException("Scan is incomplete");
        return new Result((int) Math.round(vineBlocks * 0.5 + dripBlocks * 1.875 + berryBlocks),
            maxVine, sumWeight == 0 ? 8 : sumX / sumWeight, sumWeight == 0 ? 8 : sumZ / sumWeight);
    }

    private void finishVines() {
        if (!pendingVines) return;
        for (int column = 0; column < vineRuns.length; column++) finishVine(column);
        pendingVines = false;
    }

    private void finishVine(int column) {
        int length = vineRuns[column];
        if (length >= 6) {
            vineBlocks += length;
            maxVine = Math.max(maxVine, length);
            weight(column, length);
        }
        vineRuns[column] = 0;
    }

    private void weight(int column, double weight) {
        sumX += (column >>> 4) * weight;
        sumZ += (column & 15) * weight;
        sumWeight += weight;
    }
}
