package com.autism.seedcracker.util.pure;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Clear-key release latch and positions forgotten in the current recording session. */
public final class StorageClearState {
    private int lastBind = -1;
    private boolean armed;
    private final Set<Long> forgotten = new HashSet<>();

    /** Native key domains and -1000-button encoding; DIH excludes the primary mouse button. */
    public static boolean validBind(int bind) {
        //? if >=26.3 {
        /*return (bind >= org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_A && bind < org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_COUNT)
            || (bind >= -1008 && bind <= -1002);
        *///?} else {
        return (bind >= 32 && bind <= 348) || (bind >= -1007 && bind <= -1001);
        //?}
    }

    public boolean poll(int bind, boolean down, boolean gameplay) {
        if (bind != lastBind) {
            lastBind = bind;
            armed = false;
        }
        boolean allowed = gameplay && validBind(bind);
        boolean pressed = allowed && armed && down;
        armed = allowed && !down;
        return pressed;
    }

    public void disarm() {
        armed = false;
    }

    public void reset() {
        disarm();
        lastBind = -1;
        forgotten.clear();
    }

    public void clear(Map<Long, Byte> record) {
        forgotten.addAll(record.keySet());
        record.clear();
    }

    public boolean allowsRecord(long position) {
        return !forgotten.contains(position);
    }

    /** Only a scanned, loaded chunk can establish that a forgotten position is gone. */
    public void observeMissing(Predicate<Long> missing) {
        forgotten.removeIf(missing);
    }
}
