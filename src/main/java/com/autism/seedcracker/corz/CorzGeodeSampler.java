package com.autism.seedcracker.corz;

import com.autism.seedcracker.spectre.palette.AmethystScan;
import com.autism.seedcracker.spectre.palette.PaletteScan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Native client-thread adapter. Called only for a section already visited by Amethyst ESP.
 * A call reads at most 18*18*16 cells, including a one-cell halo, from loaded chunks only.
 * No background tasks, chunk loads, inferred air, palette guesses, or native donor calls.
 */
public final class CorzGeodeSampler {
    private CorzGeodeSampler() {}

    public static boolean captureSection(Minecraft client, ClientLevel level, LevelChunk chunk, int index,
                                         CorzGeodeMorphology.Builder target) {
        if (!client.isSameThread()) throw new IllegalStateException("Morphology sampling must run on the client thread");
        if (level != client.level || index < 0 || index >= chunk.getSections().length
            || level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) != chunk) return false;
        CorzGeodeMorphology.Key key = target.key();
        if (key.x() != chunk.getPos().x() || key.z() != chunk.getPos().z()) return false;
        int sectionY = chunk.getMinSectionY() + index;
        if (!CorzGeodeMorphology.intersectsSection(sectionY)) return true;
        LevelChunk[] neighbors = new LevelChunk[9];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int cx = key.x() + dx, cz = key.z() + dz;
            if (PaletteScan.validChunk(cx, cz)) neighbors[(dx + 1) * 3 + dz + 1] = level.getChunkSource().getChunkNow(cx, cz);
        }
        int firstY = Math.max(CorzGeodeMorphology.MIN_Y - 1, sectionY * 16);
        int lastY = Math.min(CorzGeodeMorphology.MAX_Y + 1, sectionY * 16 + 15);
        for (int y = firstY; y <= lastY; y++) for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
            byte kind = CorzGeodeMorphology.UNKNOWN;
            if (y >= level.getMinY() && y <= level.getMaxY()) {
                int dx = Math.floorDiv(x, 16), dz = Math.floorDiv(z, 16);
                LevelChunk neighbor = neighbors[(dx + 1) * 3 + dz + 1];
                if (neighbor != null) {
                    int sectionIndex = (y >> 4) - neighbor.getMinSectionY();
                    if (sectionIndex >= 0 && sectionIndex < neighbor.getSections().length) {
                        var section = neighbor.getSections()[sectionIndex];
                        if (section != null) kind = classify(section.getBlockState(x & 15, y & 15, z & 15));
                    }
                }
            }
            target.put(x, y, z, kind);
        }
        return true;
    }

    static byte classify(BlockState state) {
        if (state.is(Blocks.BUDDING_AMETHYST)) return CorzGeodeMorphology.BUDDING;
        if (AmethystScan.amethyst(state)) return CorzGeodeMorphology.AMETHYST;
        if (state.is(Blocks.CALCITE)) return CorzGeodeMorphology.CALCITE;
        if (state.is(Blocks.SMOOTH_BASALT)) return CorzGeodeMorphology.BASALT;
        if (state.isAir()) return CorzGeodeMorphology.AIR;
        return CorzGeodeMorphology.OTHER;
    }
}
