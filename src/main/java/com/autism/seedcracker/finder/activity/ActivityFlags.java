package com.autism.seedcracker.finder.activity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.level.ChunkPos;

/** Current leads and their alert latch, shared only by the storage/activity scanners. */
public final class ActivityFlags {
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> announced = new HashSet<>();
    private final Set<ChunkPos> view = Collections.unmodifiableSet(flagged);

    public Set<ChunkPos> current() { return view; }

    public void record(ChunkPos pos, boolean matches) {
        if (matches) flagged.add(pos);
        else {
            flagged.remove(pos);
            announced.remove(pos);
        }
    }

    public boolean announce(ChunkPos pos, int remaining) {
        return remaining > 0 && flagged.contains(pos) && announced.add(pos);
    }

    public void replace(Set<ChunkPos> current) {
        announced.retainAll(current);
        flagged.clear();
        flagged.addAll(current);
    }

    public void retain(Predicate<ChunkPos> current) {
        flagged.removeIf(pos -> !current.test(pos));
        announced.retainAll(flagged);
    }

    public void clear() {
        flagged.clear();
        announced.clear();
    }
}
