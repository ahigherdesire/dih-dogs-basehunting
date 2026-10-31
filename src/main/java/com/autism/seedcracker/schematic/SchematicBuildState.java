package com.autism.seedcracker.schematic;

/** Runtime-only consent: persisted module settings can never arm the builder. */
public final class SchematicBuildState {
    public enum Phase { PREVIEW, RUNNING, PAUSED, STOPPED, FINISHED }

    private Phase phase = Phase.PREVIEW;
    private Object world;
    private boolean waitingForRelease;
    private int releaseTicks;
    private String reason = "Choose a file in Open setup, then load its preview.";

    public Phase phase() { return phase; }
    public String reason() { return reason; }
    public boolean running() { return phase == Phase.RUNNING; }
    public boolean waitingForRelease() { return waitingForRelease; }

    public boolean start(Object currentWorld, boolean ready, boolean screenOpen) {
        if (currentWorld == null || !ready || screenOpen) {
            pause("Not ready. Load a preview, set the origin and confirm Start with menus closed.");
            return false;
        }
        world = currentWorld;
        phase = Phase.RUNNING;
        waitingForRelease = true;
        releaseTicks = 100;
        reason = "Release the Start click and movement keys to begin.";
        return true;
    }

    public boolean allowTick(Object currentWorld, boolean screenOpen) {
        if (!running()) return false;
        if (world != currentWorld || currentWorld == null) {
            stop("World changed. Set a new origin and press Start again.");
        } else if (screenOpen) {
            pause("Menu opened. Close it and press Start explicitly to continue.");
        }
        return running();
    }

    public boolean allowInputTick(boolean held) {
        if (!running()) return false;
        if (waitingForRelease) {
            if (--releaseTicks <= 0) pause("Controls held too long. Release them, then press Start again.");
            else if (!held) {
                waitingForRelease = false;
                reason = "Building. Open setup or use Pause to take control.";
            }
            return false; // One neutral tick prevents the Start-button click from becoming a world action.
        }
        if (held) pause("Manual input. Press Start when ready to continue.");
        return running();
    }

    public void preview(String message) { change(Phase.PREVIEW, message); }
    public void pause(String message) { change(Phase.PAUSED, message); }
    public void stop(String message) { change(Phase.STOPPED, message); }
    public void finish(String message) { change(Phase.FINISHED, message); }

    private void change(Phase next, String message) {
        phase = next;
        reason = message;
        world = null;
        waitingForRelease = false;
        releaseTicks = 0;
    }
}
