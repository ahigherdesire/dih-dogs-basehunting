package com.autism.seedcracker.spectre;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectreIntegrationTest {
    private static final Set<String> PORTS = Set.of(
        "SpawnerProtectModule", "NetheriteFinderModule", "SpawnerFinderModule", "AmethystEspModule",
        "ChunkFinderModule", "SusChunkFinderModule", "PrimeChunkFinderModule", "PlayerChunksModule",
        "LightSourceFinderModule", "QuantumModule", "RenderUtilModule", "SpectatorDetectorModule",
        "AntiTrapModule", "AutoRenderModule", "HomeSetterModule", "RegionMapModule", "StaffListModule", "BlockEntityDebugModule");

    @Test
    void selectedSpectrePortsAreRegisteredExactlyOnce() throws Exception {
        var entrypoint = load("com/autism/seedcracker/SeedcrackerAddon");
        var constructors = new HashMap<String, Integer>();
        var initialize = entrypoint.methods().stream().filter(m -> m.methodName().equalsString("onInitialize")).findFirst().orElseThrow();
        for (var code : initialize.code().orElseThrow()) {
            if (code instanceof InvokeInstruction invoke && invoke.name().equalsString("<init>")) {
                String owner = invoke.owner().name().stringValue();
                constructors.merge(owner.substring(owner.lastIndexOf('/') + 1), 1, Integer::sum);
            }
        }
        assertEquals(18, PORTS.size());
        for (String port : PORTS) assertEquals(1, constructors.getOrDefault(port, 0), port);
        assertFalse(constructors.containsKey("FakeScoreboardHud"));
    }

    @Test
    void excludedBridgeRuntimeIsNotRegistered() throws Exception {
        Set<String> calls = calls(load("com/autism/seedcracker/SeedcrackerAddon"));
        assertFalse(calls.contains("com/autism/seedcracker/bridge/GameBridge.start"));
        assertFalse(calls.contains("com/autism/seedcracker/bridge/GameBridge.stop"));
    }

    @Test
    void appliedPacketHooksReachEveryPacketDrivenFinder() throws Exception {
        Set<String> calls = calls(load("kaptainwutax/seedcrackerX/mixin/ClientPacketListenerMixin"));
        for (String module : Set.of("ChunkFinderModule", "SusChunkFinderModule", "PrimeChunkFinderModule",
                "PlayerChunksModule", "QuantumModule", "LightSourceFinderModule")) {
            assertTrue(calls.contains("com/autism/seedcracker/modules/" + module + ".onPacketApplied"), module);
        }
        assertTrue(calls.contains("com/autism/seedcracker/spectre/palette/BlockEntityCapture.onPacketApplied"));
        assertTrue(calls.contains("com/autism/seedcracker/modules/NetheriteFinderModule.onBlockUpdate"));
        assertTrue(calls.contains("com/autism/seedcracker/modules/AmethystEspModule.onBlockUpdate"));
        assertTrue(calls.contains("com/autism/seedcracker/modules/AmethystEspModule.onLightApplied"));
        Set<String> worldCalls = calls(load("kaptainwutax/seedcrackerX/mixin/ClientLevelMixin"));
        assertTrue(worldCalls.contains("com/autism/seedcracker/modules/PrimeChunkFinderModule.onBlockChange"));
        assertTrue(worldCalls.contains("com/autism/seedcracker/modules/QuantumModule.onBlockUpdate"));
    }

    private static Set<String> calls(ClassModel model) {
        Set<String> calls = new HashSet<>();
        for (var entry : model.constantPool()) if (entry instanceof MemberRefEntry ref) {
            calls.add(ref.owner().name().stringValue() + "." + ref.name().stringValue());
        }
        return calls;
    }

    private static ClassModel load(String name) throws Exception {
        try (var input = SpectreIntegrationTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
