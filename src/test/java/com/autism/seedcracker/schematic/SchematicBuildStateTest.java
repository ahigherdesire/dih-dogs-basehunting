package com.autism.seedcracker.schematic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicBuildStateTest {
    @Test void previewCannotActWithoutExplicitStart() {
        var state = new SchematicBuildState();
        Object world = new Object();
        assertEquals(SchematicBuildState.Phase.PREVIEW, state.phase());
        assertFalse(state.allowTick(world, false));
        assertFalse(state.start(null, true, false));
        assertFalse(state.start(world, false, false));
        assertFalse(state.start(world, true, true));
        assertFalse(state.running());
        assertTrue(state.start(world, true, false));
        assertTrue(state.allowTick(world, false));
    }

    @Test void startClickMustBeReleasedBeforeActionsAndLaterInputPauses() {
        var state = new SchematicBuildState();
        Object world = new Object();
        state.start(world, true, false);
        assertTrue(state.waitingForRelease());
        assertFalse(state.allowInputTick(true));
        assertTrue(state.running());
        assertFalse(state.allowInputTick(false));
        assertFalse(state.waitingForRelease());
        assertTrue(state.allowInputTick(false));
        assertFalse(state.allowInputTick(true));
        assertFalse(state.allowInputTick(false));
        assertEquals(SchematicBuildState.Phase.PAUSED, state.phase());
    }

    @Test void holdingControlsCannotLeaveAnIndefinitelyArmedStart() {
        var state = new SchematicBuildState();
        state.start(new Object(), true, false);
        for (int i = 0; i < 100; i++) assertFalse(state.allowInputTick(true));
        assertFalse(state.running());
        assertFalse(state.waitingForRelease());
        assertFalse(state.allowInputTick(false));
    }

    @Test void openingSetupOrAnotherScreenPausesAndNeverResumesImplicitly() {
        var state = new SchematicBuildState();
        Object world = new Object();
        assertTrue(state.start(world, true, false));
        assertFalse(state.allowTick(world, true));
        assertEquals(SchematicBuildState.Phase.PAUSED, state.phase());
        assertFalse(state.allowTick(world, false));
        assertTrue(state.start(world, true, false));
        state.pause("Manual input");
        assertFalse(state.allowTick(world, false));
        assertEquals("Manual input", state.reason());
    }

    @Test void rejectedStartAlsoRevokesEarlierConsent() {
        var state = new SchematicBuildState();
        Object world = new Object();
        state.start(world, true, false);
        assertFalse(state.start(world, true, true));
        assertFalse(state.allowTick(world, false));
    }

    @Test void disconnectAndWorldChangesConsumeConsent() {
        var state = new SchematicBuildState();
        Object first = new Object(), next = new Object();
        state.start(first, true, false);
        assertFalse(state.allowTick(next, false));
        assertFalse(state.allowTick(first, false));
        assertEquals(SchematicBuildState.Phase.STOPPED, state.phase());
        state.start(first, true, false);
        assertFalse(state.allowTick(null, false));
        assertFalse(state.allowTick(first, false));
    }

    @Test void geometryLoadEnableResetAndStopAllRequireFreshStart() {
        var state = new SchematicBuildState();
        Object world = new Object();
        for (String reason : new String[]{"Origin changed", "Rotation changed", "Loaded file", "Enabled", "Settings reset"}) {
            state.start(world, true, false);
            state.preview(reason);
            assertFalse(state.allowTick(world, false), reason);
        }
        state.start(world, true, false);
        state.stop("Stopped");
        assertFalse(state.allowTick(world, false));
        state.start(world, true, false);
        state.finish("Finished");
        assertEquals(SchematicBuildState.Phase.FINISHED, state.phase());
        assertFalse(state.allowTick(world, false));
    }
}
