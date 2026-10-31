package com.autism.seedcracker.modules;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HoleScanLifecycleTest {
    private static ClassModel module() throws Exception {
        try (var input = HoleScanLifecycleTest.class.getResourceAsStream("/com/autism/seedcracker/modules/HoleTunnelStairsEspModule.class")) {
            assertNotNull(input);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
    private static long calls(ClassModel model, String method, String owner, String name) {
        long count = 0;
        for (var candidate : model.methods()) if (candidate.methodName().equalsString(method)) {
            for (var code : candidate.code().orElseThrow()) {
                if (code instanceof InvokeInstruction call && call.owner().name().equalsString(owner) && call.name().equalsString(name)) count++;
            }
        }
        return count;
    }
    @Test void allShapeFeedsAndQueuedScansAreClearedOnLifecycleChanges() throws Exception {
        var module = module();
        assertEquals(4, calls(module, "clearRender", "com/autism/seedcracker/render/BlockEspRenderer", "clear"));
        for (String lifecycle : new String[] {"onEnable", "onDisable", "onGameLeft", "onSettingsReset"}) {
            assertEquals(1, calls(module, lifecycle, "com/autism/seedcracker/modules/HoleTunnelStairsEspModule", "resetScan"), lifecycle);
        }
        assertEquals(1, calls(module, "resetScan", "com/autism/seedcracker/finder/ScanCursor", "reset"));
    }
    @Test void markersTrackExplicitSweepIdentityInsteadOfFlickeringAtCompletedProgress() throws Exception {
        var module = module();
        assertTrue(calls(module, "scan", "com/autism/seedcracker/finder/ScanCursor", "generation") > 0);
        assertEquals(0, calls(module, "scan", "com/autism/seedcracker/finder/ScanCursor", "progress"));
    }
}
