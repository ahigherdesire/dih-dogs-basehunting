package com.autism.seedcracker.corz;

import java.util.OptionalDouble;

/**
 * Private authorized Corz-informed adaptation; donor metadata: All-Rights-Reserved.
 * SOURCE-ONLY / LIKELY provenance: gg.corz.IlIllIIillIllIIi.lililiilil,
 * numbered source 1614, lines 1195-1343 (count/density/radius/shell/air edges).
 * This is NOT the donor's verdict, sensitivity gates, UI strings, or native framework.
 * Budding amethyst is counted explicitly instead of guessing the broken early-result path.
 */
public final class CorzGeodeMorphology {
    public static final int MIN_Y = -64, MAX_Y = 48;
    public static final int SIDE = 18, HEIGHT = MAX_Y - MIN_Y + 3;
    public static final int SNAPSHOT_CELLS = SIDE * SIDE * HEIGHT;
    public static final int INTERIOR_CELLS = 16 * 16 * (MAX_Y - MIN_Y + 1);
    public static final byte UNKNOWN = 0, AMETHYST = 1, CALCITE = 2, BASALT = 3,
        AIR = 4, OTHER = 5, BUDDING = 6;
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private CorzGeodeMorphology() {}

    public record Key(int x, int z) {
        public Key {
            // Include the one-cell halo without overflowing world coordinates.
            if ((long) x * 16 - 1 < Integer.MIN_VALUE || (long) x * 16 + 16 > Integer.MAX_VALUE
                || (long) z * 16 - 1 < Integer.MIN_VALUE || (long) z * 16 + 16 > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Chunk coordinate overflow");
        }
        public boolean touches(Key other) {
            return Math.abs((long) x - other.x) <= 1 && Math.abs((long) z - other.z) <= 1;
        }
    }

    /** Includes a one-cell halo. Uncaptured/beyond-height cells stay UNKNOWN, never inferred air. */
    public static final class Snapshot {
        private final Key key;
        private final byte[] cells;
        private Snapshot(Key key, byte[] cells) {
            this.key = key;
            this.cells = cells.clone();
        }
        public Key key() { return key; }
        public byte kind(int x, int y, int z) {
            return inside(x, y, z) ? cells[slot(x, y, z)] : UNKNOWN;
        }
    }

    /** Mutable construction is confined to the client thread; freeze transfers no mutable aliases. */
    public static final class Builder {
        private final Key key;
        private final byte[] cells = new byte[SNAPSHOT_CELLS];
        private boolean frozen;
        public Builder(int chunkX, int chunkZ) { key = new Key(chunkX, chunkZ); }
        public Key key() { return key; }
        public void put(int x, int y, int z, byte kind) {
            if (frozen) throw new IllegalStateException("Snapshot is frozen");
            if (!inside(x, y, z) || kind < UNKNOWN || kind > BUDDING)
                throw new IllegalArgumentException("Invalid snapshot cell");
            cells[slot(x, y, z)] = kind;
        }
        public Snapshot freeze() {
            if (frozen) throw new IllegalStateException("Snapshot is frozen");
            frozen = true;
            return new Snapshot(key, cells);
        }
    }

    public record Descriptor(Key key, int amethystCells, int buddingCells, int verticalSpan,
                             double density, double centroidX, double centroidY, double centroidZ,
                             double meanRadius, int calciteCells, int basaltCells,
                             int airEdges, int solidEdges, int internalEdges, int unknownEdges,
                             int knownCells) {
        public boolean partial() { return knownCells != INTERIOR_CELLS || unknownEdges != 0; }
        public OptionalDouble knownAirFraction() {
            int knownBoundary = airEdges + solidEdges;
            return knownBoundary == 0 ? OptionalDouble.empty()
                : OptionalDouble.of((double) airEdges / knownBoundary);
        }
    }

    /** Two finite passes over immutable cells; at most six neighbor lookups per visited cell. */
    public static final class Analysis {
        private final Snapshot snapshot;
        private int phase, cursor, count, budding, calcite, basalt, known;
        private int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        private long sumX, sumY, sumZ;
        private double centerX, centerY, centerZ, radiusSum;
        private int air, solid, internal, unknown;
        private Descriptor result;

        public Analysis(Snapshot snapshot) { this.snapshot = java.util.Objects.requireNonNull(snapshot); }
        public boolean complete() { return result != null; }
        boolean matches(Snapshot other) {
            return other != null && snapshot.key.equals(other.key) && java.util.Arrays.equals(snapshot.cells, other.cells);
        }
        public Descriptor result() {
            if (!complete()) throw new IllegalStateException("Analysis is incomplete");
            return result;
        }
        public int advance(int maxCells, long deadlineNanos) {
            int used = 0;
            while (used < Math.max(0, maxCells) && !complete()) {
                if ((used & 63) == 0 && System.nanoTime() >= deadlineNanos) break;
                int x = cursor & 15, z = (cursor >> 4) & 15, y = MIN_Y + (cursor >> 8);
                byte kind = snapshot.kind(x, y, z);
                if (phase == 0) {
                    if (kind != UNKNOWN) known++;
                    if (kind == CALCITE) calcite++;
                    if (kind == BASALT) basalt++;
                    if (amethyst(kind)) {
                        count++;
                        if (kind == BUDDING) budding++;
                        sumX += x; sumY += y; sumZ += z;
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                } else if (amethyst(kind)) {
                    double dx = x - centerX, dy = y - centerY, dz = z - centerZ;
                    radiusSum += Math.sqrt(dx * dx + dy * dy + dz * dz);
                    for (int side = 0; side < 6; side++) {
                        byte adjacent = snapshot.kind(x + DX[side], y + DY[side], z + DZ[side]);
                        if (adjacent == UNKNOWN) unknown++;
                        else if (adjacent == AIR) air++;
                        else if (amethyst(adjacent)) internal++;
                        else solid++;
                    }
                }
                used++;
                if (++cursor == INTERIOR_CELLS) {
                    cursor = 0;
                    if (phase++ == 0 && count != 0) {
                        centerX = (double) sumX / count;
                        centerY = (double) sumY / count;
                        centerZ = (double) sumZ / count;
                    } else finish();
                }
            }
            return used;
        }
        private void finish() {
            int span = count == 0 ? 0 : maxY - minY + 1;
            result = new Descriptor(snapshot.key, count, budding, span,
                count == 0 ? 0 : count / (256.0 * span),
                snapshot.key.x * 16.0 + centerX + 0.5, centerY + 0.5,
                snapshot.key.z * 16.0 + centerZ + 0.5,
                count == 0 ? 0 : radiusSum / count, calcite, basalt, air, solid, internal, unknown, known);
        }
    }

    public static boolean intersectsSection(int sectionY) {
        long base = (long) sectionY * 16;
        return base <= MAX_Y + 1 && base + 15 >= MIN_Y - 1;
    }
    private static boolean amethyst(byte kind) { return kind == AMETHYST || kind == BUDDING; }
    private static boolean inside(int x, int y, int z) {
        return x >= -1 && x <= 16 && z >= -1 && z <= 16 && y >= MIN_Y - 1 && y <= MAX_Y + 1;
    }
    private static int slot(int x, int y, int z) {
        return ((y - (MIN_Y - 1)) * SIDE + z + 1) * SIDE + x + 1;
    }
}
