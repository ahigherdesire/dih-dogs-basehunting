package com.autism.seedcracker.finder.landmark;

/** Active rectangular portal interiors and their non-corner obsidian frame. */
public final class PortalGeometry {
    public static final int OTHER = 0, X = 1, Z = 2, OBSIDIAN = 3;
    @FunctionalInterface public interface Cells { int get(int x, int y, int z); }
    @FunctionalInterface public interface Visitor { void visit(int x, int y, int z); }
    private PortalGeometry() {}

    public static boolean complete(Cells cells, int x, int y, int z, Visitor visited) {
        int axis = cells.get(x, y, z);
        if (axis != X && axis != Z) return false;
        int dx = axis == X ? 1 : 0, dz = axis == Z ? 1 : 0;
        int steps = 0;
        while (steps < 21 && cells.get(x, y - 1, z) == axis) { y--; steps++; }
        if (steps == 21) return false;
        steps = 0;
        while (steps < 21 && cells.get(x - dx, y, z - dz) == axis) { x -= dx; z -= dz; steps++; }
        if (steps == 21) return false;
        int width = 0, height = 0;
        while (width <= 21 && cells.get(x + width * dx, y, z + width * dz) == axis) width++;
        while (height <= 21 && cells.get(x, y + height, z) == axis) height++;
        if (width < 2 || width > 21 || height < 3 || height > 21) {
            visited.visit(x, y, z);
            return false;
        }
        boolean complete = true;
        for (int u = 0; u < width; u++) {
            int px = x + u * dx, pz = z + u * dz;
            complete &= cells.get(px, y - 1, pz) == OBSIDIAN && cells.get(px, y + height, pz) == OBSIDIAN;
            for (int v = 0; v < height; v++) {
                if (cells.get(px, y + v, pz) == axis) visited.visit(px, y + v, pz);
                else complete = false;
            }
        }
        for (int v = 0; v < height; v++) {
            complete &= cells.get(x - dx, y + v, z - dz) == OBSIDIAN;
            complete &= cells.get(x + width * dx, y + v, z + width * dz) == OBSIDIAN;
        }
        return complete;
    }
}
