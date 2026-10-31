package com.autism.seedcracker.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source contracts avoid booting the host client's global Fabric config in headless tests. */
class FinderSettingsTest {
    @Test void growthKeepsOrdinaryDefaultsAndExposesAllRaisedLimits() throws IOException {
        String source = source("GrowthFinderModule");
        setting(source, "threshold", 8, 1000);
        setting(source, "scan-radius", 4, 32);
        setting(source, "chunks-per-tick", 1, 128);
        assertTrue(source.contains("MAX_CELLS_PER_TICK - cells"));
        assertTrue(source.contains("System.nanoTime() < deadline"));
    }

    @Test void observationsAreOptInAndCannotInheritTheLegacyPanelSetting() throws IOException {
        String sus = source("SusChunkFinderModule"), observations = source("DogsObservationsModule");
        assertFalse(sus.contains("inspect-panel"));
        assertFalse(sus.contains("HudElementRegistry"));
        assertFalse(sus.contains("SuspectPanel"));
        assertTrue(observations.contains("\":dogs-observations\""));
        assertTrue(observations.contains("\"inspect-range\""));
        assertFalse(observations.contains("inspect-panel"));
        assertFalse(Pattern.compile("set(?:ConfiguredEnabled|Enabled|EnabledSilently)\\s*\\(\\s*true").matcher(observations).find());
        assertTrue(observations.contains("Enable Sus Chunk Finder"));
        assertTrue(observations.contains("HudElementRegistry.removeElement(HUD_ID)"));
        assertTrue(sus.contains("world != mc.level"));
    }

    @Test void observationsAreRegisteredOnceAndLightBypassUsesPacketEvidence() throws IOException {
        Path root = Path.of(System.getProperty("finder.sourceRoot", "src"));
        String entrypoint = Files.readString(root.resolve(Path.of("main", "java", "com", "autism", "seedcracker", "SeedcrackerAddon.java")));
        assertEquals(1, entrypoint.split("new com.autism.seedcracker.modules.DogsObservationsModule\\(\\)", -1).length - 1);
        String light = source("LightSourceFinderModule");
        assertTrue(light.contains("PlayerLightTracker"));
        assertTrue(light.contains("ClientboundLevelChunkWithLightPacket"));
        assertFalse(light.contains("LightSnapshot"));
        assertFalse(light.contains("Executors"));
    }

    @Test void naturalNoiseDefaultsRequireIndependentBuildingEvidence() throws IOException {
        for (String module : new String[] {"LightSourceFinderModule", "PrimeChunkFinderModule", "PlayerChunksModule", "ActivityFinderModule"}) {
            assertTrue(Pattern.compile("new BoolSetting\\(\\s*\"require-build-evidence\",\\s*\"[^\"]+\",\\s*true\\)").matcher(source(module)).find(), module);
        }
        assertTrue(Pattern.compile("new BoolSetting\\(\\s*\"natural-spawner-hints\",\\s*\"[^\"]+\",\\s*false\\)")
            .matcher(source("StashFinderModule")).find());
        assertTrue(source("LightSourceFinderModule").contains("reporter.tick(mc, requireBuild.get() ? flagged : Set.of())"));
        assertTrue(source("PrimeChunkFinderModule").contains("reporter.tick(mc, requireBuild.get() ? shown : Set.of())"));
        assertTrue(source("PlayerChunksModule").contains("mode.get() == Mode.LIGHT_DATA && !requireBuild.get() ? Set.of() : flagged"));
    }

    private static void setting(String source, String id, int defaultValue, int maximum) {
        assertTrue(Pattern.compile("new IntSetting\\(\\s*\"" + Pattern.quote(id)
            + "\",\\s*\"[^\"]+\",\\s*" + defaultValue + ",\\s*1,\\s*" + maximum + ",\\s*1\\)").matcher(source).find(), id);
    }

    private static String source(String name) throws IOException {
        Path root = Path.of(System.getProperty("finder.sourceRoot", "src"));
        return Files.readString(root.resolve(Path.of("main", "java", "com", "autism", "seedcracker", "modules", name + ".java")));
    }
}
