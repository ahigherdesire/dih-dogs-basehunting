package com.autism.seedcracker.finder.landmark;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.SignText;

/** Live sign text, with notification memory limited to signs that still match. */
public final class SignEvidence {
    private final Map<BlockPos, String> found = new HashMap<>();
    private final Map<BlockPos, String> reported = new HashMap<>();

    public Map<BlockPos, String> replaceChunk(ChunkPos chunk, Map<BlockPos, String> signs, boolean readText) {
        prune(pos -> pos.getX() >> 4 != chunk.x() || pos.getZ() >> 4 != chunk.z() || signs.containsKey(pos));
        found.putAll(signs);
        signs.forEach((pos, text) -> { if (text.isBlank()) reported.remove(pos); });
        Map<BlockPos, String> fresh = new HashMap<>();
        if (readText) signs.forEach((pos, text) -> {
            if (!text.isBlank() && !text.equals(reported.put(pos, text))) fresh.put(pos, text);
        });
        return fresh;
    }

    public void prune(Predicate<BlockPos> keep) {
        found.keySet().removeIf(keep.negate());
        reported.keySet().retainAll(found.keySet());
    }

    public static String text(SignText front, SignText back) {
        StringBuilder text = new StringBuilder();
        for (var side : java.util.List.of(front, back)) {
            for (int i = 0; i < 4; i++) {
                String line = com.autism.seedcracker.compat.McCompat.signLine(side, i).getString().trim();
                if (line.isEmpty()) continue;
                if (!text.isEmpty()) text.append(" / ");
                text.append(line);
            }
        }
        return text.toString();
    }

    public Set<BlockPos> positions() { return found.keySet(); }

    public void clear() {
        found.clear();
        reported.clear();
    }
}
