package com.autism.seedcracker.finder.landmark;

/** Resumable rows anchored in one chunk; only loaded cells supplied by the caller are evidence. */
public final class TunnelScan {
    public static final int UNKNOWN = 0, AIR = 1, SOLID = 2, PAVED = 4;
    @FunctionalInterface public interface Cells { int get(int x, int y, int z); }
    private final int baseX, baseZ, maxY, length;
    private final Cells cells;
    private final boolean[] context;
    private int y, axis, lane, along, streak, contextual;
    private boolean done;
    private Match match;
    public record Match(int x, int y, int z, int axis, int length) {}

    public TunnelScan(int baseX, int baseZ, int minY, int maxY, int length, Cells cells) {
        this.baseX = baseX;
        this.baseZ = baseZ;
        this.y = minY;
        this.maxY = maxY;
        this.length = Math.max(1, length);
        this.cells = cells;
        context = new boolean[this.length];
        done = minY > maxY;
    }

    /** At most budget row samples, plus one length-bounded verification before publishing a match. */
    public int step(int budget) {
        int used = 0;
        while (!done && used < budget) {
            boolean verificationFailed = false;
            int x = baseX + (axis == 0 ? along : lane);
            int z = baseZ + (axis == 0 ? lane : along);
            int floor = cells.get(x, y - 1, z);
            boolean walkable = (floor & SOLID) != 0 && cells.get(x, y, z) == AIR && cells.get(x, y + 1, z) == AIR;
            used++;
            if (walkable) {
                boolean bounded = (floor & PAVED) != 0 || enclosed(cells, x, y, z, axis);
                int slot = streak % length;
                if (streak >= length && context[slot]) contextual--;
                context[slot] = bounded;
                if (bounded) contextual++;
                streak++;
                if (streak >= length && contextual * 4 >= length * 3) {
                    var candidate = new Match(x - (axis == 0 ? length - 1 : 0), y,
                        z - (axis == 1 ? length - 1 : 0), axis, length);
                    // A run can span ticks: re-read its prefix before publishing live evidence.
                    if (valid(candidate, cells)) { match = candidate; done = true; }
                    else { streak = 0; contextual = 0; verificationFailed = true; }
                }
            } else {
                streak = 0;
                contextual = 0;
            }
            // Extend a run across a chunk seam, but never begin a new run outside this chunk.
            if (!done && (++along >= 16 + length - 1 || along >= 16 && !walkable)) nextRow();
            if (verificationFailed) break;
        }
        return used;
    }

    public static boolean valid(Match match, Cells cells) {
        int contextual = 0;
        for (int i = 0; i < match.length(); i++) {
            int x = match.x() + (match.axis() == 0 ? i : 0), z = match.z() + (match.axis() == 1 ? i : 0), y = match.y();
            int floor = cells.get(x, y - 1, z);
            if ((floor & SOLID) == 0 || cells.get(x, y, z) != AIR || cells.get(x, y + 1, z) != AIR) return false;
            if ((floor & PAVED) != 0 || enclosed(cells, x, y, z, match.axis())) contextual++;
        }
        return contextual * 4 >= match.length() * 3;
    }

    private static boolean enclosed(Cells cells, int x, int y, int z, int axis) {
        boolean roof = solid(cells, x, y + 2, z) || solid(cells, x, y + 3, z) || solid(cells, x, y + 4, z);
        if (!roof) return false;
        int dx = axis == 0 ? 0 : 1, dz = axis == 0 ? 1 : 0;
        return (solid(cells, x - dx, y, z - dz) || solid(cells, x - dx * 2, y, z - dz * 2))
            && (solid(cells, x + dx, y, z + dz) || solid(cells, x + dx * 2, y, z + dz * 2));
    }

    private static boolean solid(Cells cells, int x, int y, int z) { return (cells.get(x, y, z) & SOLID) != 0; }

    private void nextRow() {
        along = 0;
        streak = 0;
        contextual = 0;
        if (++lane == 16) {
            lane = 0;
            if (++axis == 2) { axis = 0; if (++y > maxY) done = true; }
        }
    }

    public boolean done() { return done; }
    public boolean found() { return match != null; }
    public Match match() { return match; }
}
