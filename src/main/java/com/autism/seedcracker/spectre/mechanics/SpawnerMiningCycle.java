// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.mechanics;

/** One server-observed stack, possibly split across slots, followed by a real input-release gap. */
public final class SpawnerMiningCycle {
    public static final int STACK_SIZE = 64;
    public static final int RELEASE_TICKS = 6;
    private static final int SETTLE_TICKS = 4;
    private boolean active;
    private int baseline, current, goal;
    private long version, changedAt, releasedAt = -1;

    public void begin(int count, long revision, long tick, boolean bulk) {
        if (active || count < 0 || revision < 0 || tick < 0) throw new IllegalStateException("Invalid mining cycle start");
        active = true;
        baseline = current = count;
        goal = bulk ? STACK_SIZE : 1;
        version = revision;
        changedAt = tick;
        releasedAt = -1;
    }

    public void observe(int count, long revision, long tick) {
        if (!active || count < 0 || revision <= version) return;
        version = revision;
        if (current != count) {
            current = count;
            changedAt = tick;
        }
    }

    public boolean active() { return active; }
    public int collected() { return active ? Math.max(0, current - baseline) : 0; }
    public boolean full() { return active && collected() >= goal; }
    public boolean releasing() { return active && releasedAt >= 0; }
    public void release(long tick) { if (active && releasedAt < 0) releasedAt = tick; }

    public boolean ready(long tick, boolean sneaking, boolean droppedItems) {
        return releasing() && !sneaking && !droppedItems
            && tick - releasedAt >= RELEASE_TICKS && tick - changedAt >= SETTLE_TICKS;
    }

    public boolean complete(boolean serverRemovedTarget, boolean droppedItems, long tick) {
        return full() || active && serverRemovedTarget && !droppedItems
            && collected() > 0 && tick - changedAt >= SETTLE_TICKS;
    }

    public void resume() {
        if (!releasing()) throw new IllegalStateException("Mining was not released");
        releasedAt = -1;
    }

    public void clear() {
        active = false;
        baseline = current = goal = 0;
        version = changedAt = 0;
        releasedAt = -1;
    }
}
