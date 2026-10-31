package com.autism.seedcracker.modules;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests compiled control flow without booting the host, sending packets or touching saved config. */
class FindersControlsContractTest {
    private static final String HUB = "com/autism/seedcracker/modules/FindersModule";
    private static final String MODULE = "dihclient/modules/Module";

    @Test void failedActivationHasOneAttemptNoStoredIntentAndNoRetryLoop() throws IOException {
        var hub = model(HUB);
        var toggle = method(hub, "toggle");
        assertEquals(1, calls(toggle, MODULE, "isEnabled"));
        assertEquals(1, calls(toggle, MODULE, "setEnabled"));
        assertEquals(1, hub.methods().stream().mapToLong(m -> calls(m, MODULE, "setEnabled")).sum());
        assertEquals(0, calls(toggle, HUB, "toggle"));
        assertTrue(hub.fields().stream().allMatch(f -> f.flags().has(java.lang.reflect.AccessFlag.STATIC)),
            "No remembered wanted state can retry a refusal or overwrite a manual change");
        for (String name : Set.of("tick", "ticksWhenDisabled", "hasDisabledTickWork", "onEnable", "onOptionValueChanged", "onSettingsReset"))
            assertFalse(hub.methods().stream().anyMatch(m -> m.methodName().equalsString(name)), name);
        assertTrue(source("FindersModule").contains("if (finder != null) finder.setEnabled(!finder.isEnabled());"));
        String body = source("FindersModule").split("private static void toggle\\(Entry entry\\) \\{", 2)[1].split("\\n    }", 2)[0];
        assertFalse(body.contains("for ("));
        assertFalse(body.contains("while ("));
    }

    @Test void manualAndHostGateChangesAreReadDirectlyRatherThanMirroredOnTicks() throws IOException {
        var hub = model(HUB);
        for (String name : Set.of("externalSettingValue", "status")) {
            assertEquals(1, calls(method(hub, name), HUB, "target"));
            assertEquals(1, calls(method(hub, name), MODULE, "isEnabled"));
            assertEquals(0, calls(method(hub, name), MODULE, "setEnabled"));
            assertEquals(0, calls(method(hub, name), MODULE, "value"));
        }
        assertEquals(1, calls(method(hub, "target"), "com/autism/seedcracker/compat/ModuleLookup", "get"));
        assertEquals(0, calls(method(hub, "<init>"), HUB, "target"));
        assertEquals(0, calls(method(hub, "<init>"), "com/autism/seedcracker/compat/ModuleLookup", "get"));
    }

    @Test void persistedLegacyBoolsCannotEnableWebhooksOrObservationPanels() throws IOException {
        String source = source("FindersModule");
        assertTrue(source.contains("String statusId() { return \"on\" + shortId.replace(\":\", \"-\"); }"));
        assertTrue(source.contains("new BoolSetting(entry.statusId(), entry.name(), false).readonlySummary().keepOnReset()"));
        assertTrue(source.contains("return entry(settingId) != null;"));
        for (String id : Set.of(":sign-finder", ":portal-finder", ":structure-detector", ":base-webhook", ":finder-overlay",
                ":z-stash-finder", ":z-sus-chunk-finder", ":z-chunk-finder", ":player-chunks", ":quantum", ":light-finder"))
            assertTrue(source.contains("\"" + id + "\""), id);
        assertFalse(source.contains(":dogs-observations"));
        assertFalse(source.contains(":recent-dogs-observations"));
        assertTrue(source.contains("External delivery (explicit opt-in)"));
        var hub = model(HUB);
        var persistedWrite = method(hub, "setExternalSettingValue");
        assertEquals(1, calls(persistedWrite, HUB, "entry"));
        assertEquals(0, calls(persistedWrite, HUB, "toggle"));
        assertEquals(0, calls(persistedWrite, HUB, "target"));
        for (String setter : Set.of("setConfiguredEnabled", "setEnabledSilently"))
            assertEquals(0, hub.methods().stream().mapToLong(m -> calls(m, MODULE, setter)).sum());
    }

    @Test void nativeReadonlySummariesAndFormattingExistWithoutNewUiDependencies() throws IOException {
        var setting = model("dihclient/api/module/Setting");
        for (String name : Set.of("readonlySummary", "formatter", "visibleWhen", "keepOnReset"))
            assertNotNull(method(setting, name));
        assertNotNull(method(model("dihclient/api/module/ActionSetting"), "buttonLabel"));
        assertEquals(1, calls(method(setting, "format"), "java/util/function/Function", "apply"));
        for (String name : Set.of("externalSettingValue", "setExternalSettingValue"))
            assertNotNull(method(model(MODULE), name));
    }

    @Test void overlayResetReappliesItsDisplaySettingsWithoutChangingAnyFinder() throws IOException {
        var overlay = model("com/autism/seedcracker/modules/FinderOverlayModule");
        var reset = method(overlay, "onSettingsReset");
        assertEquals(1, calls(reset, "com/autism/seedcracker/finder/ChunkFlagRenderer", "configureSmartOverlap"));
        assertEquals(0, calls(reset, MODULE, "setEnabled"));
        assertEquals(2, calls(reset, "dihclient/api/module/IntSetting", "get"));
        String source = source("FinderOverlayModule");
        assertTrue(source.contains("configureSmartOverlap(isEnabled(), overlapModules.get(), floodChunks.get())"));
        assertTrue(source.contains("\"overlap-modules\", \"Finder sources in 5x5 chunks\", 2, 2, 6, 1"));
        assertTrue(source.contains("\"flood-chunks\", \"Flagged chunks in 5x5 chunks\", 9, 3, 50, 1"));
    }

    private static ClassModel model(String owner) throws IOException {
        try (var input = FindersControlsContractTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(input, owner);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }

    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst()
            .orElseThrow(() -> new AssertionError(model.thisClass().asInternalName() + "." + name));
    }

    private static long calls(MethodModel method, String owner, String name) {
        return method.code().stream().flatMap(c -> c.elementStream()).filter(e -> e instanceof InvokeInstruction call
            && call.owner().asInternalName().equals(owner) && call.name().equalsString(name)).count();
    }

    private static String source(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("finder.sourceRoot", "src"))
            .resolve(Path.of("main", "java", "com", "autism", "seedcracker", "modules", name + ".java")))
            .replace("\r\n", "\n");
    }
}