package com.autism.seedcracker.modules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Configuration-only contracts; never initializes Minecraft or the host's global config. */
class SusControlsContractTest {
    @Test void detectionWorldPacketAndObservationMethodsAreByteForByteUnchanged() throws Exception {
        String source = source();
        String runtime = source.substring(source.indexOf("    private void clear(ClientLevel level) {"),
            source.indexOf("    @Override public String info()"))
            .replace("com.autism.seedcracker.compat.McCompat.chunkX(chunk)", "chunk.getX()")
            .replace("com.autism.seedcracker.compat.McCompat.chunkZ(chunk)", "chunk.getZ()")
            .replace("com.autism.seedcracker.compat.McCompat.chunkX(light)", "light.getX()")
            .replace("com.autism.seedcracker.compat.McCompat.chunkZ(light)", "light.getZ()");
        assertEquals("3190434e67b2b6b63a493baced52e91918e83c9d4ed578c9e12b22359b16201c",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(runtime.getBytes(StandardCharsets.UTF_8))),
            "Only labels, visibility and summaries may change in this UX patch");
    }

    @Test void allExistingScalarSettingKeysDefaultsAndRangesSurvive() throws IOException {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("geode-heat", "Int:6,1,64,1"), Map.entry("geode-spread", "Int:5,0,16,1"),
            Map.entry("scan-radius", "Int:12,2,32,1"), Map.entry("chunks-per-tick", "Int:2,1,128,1"),
            Map.entry("notify", "Bool:true"), Map.entry("detect-mined-clusters", "Bool:false"),
            Map.entry("mined-color", "Color:0x60A64AFF"), Map.entry("color", "Color:0x96FF4444"),
            Map.entry("tracer", "Bool:false"), Map.entry("clue-threshold", "Int:2,1,8,1"),
            Map.entry("natural-context", "Bool:false"), Map.entry("kelp-length", "Int:16,2,64,1"),
            Map.entry("vine-length", "Int:16,2,64,1"), Map.entry("cave-vine-length", "Int:20,2,64,1"),
            Map.entry("bamboo-length", "Int:14,2,32,1"), Map.entry("mature-clusters", "Int:12,2,128,1"),
            Map.entry("mature-percent", "Int:75,10,100,5"), Map.entry("object-range", "Int:128,16,256,16"));
        var actual = new LinkedHashMap<String, String>();
        var matcher = Pattern.compile("new (Int|Bool|Color)Setting\\(\\s*\"([^\"]+)\",\\s*\"[^\"]*\",\\s*([^)]*)\\)").matcher(source());
        while (matcher.find()) actual.put(matcher.group(2), matcher.group(1) + ":" + matcher.group(3).replaceAll("\\s+", ""));
        assertEquals(expected, actual);
        String source = source();
        assertTrue(source.contains("new EnumSetting<>(\"mode\", \"Detection mode\", Mode.CLASSIC, Mode.values(), Mode::toString)"));
        assertTrue(source.contains("\"clue-\" + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')"));
        assertTrue(source.contains("new BoolSetting(\"objects-\" + category.id(), category.label + \" outlines\", true)"));
        assertTrue(source.contains("new ColorSetting(\"color-\" + category.id(), category.label + \" colour\", category.color)"));
    }

    @Test void modeTokensLabelsAndLegacyMigrationSurvive() {
        var modes = SusChunkFinderModule.Mode.values();
        assertArrayEquals(new String[] {"CLASSIC", "ANBS_PLUS", "ACTIVITY_CLUES"},
            java.util.Arrays.stream(modes).map(Enum::name).toArray(String[]::new));
        assertArrayEquals(new String[] {"Classic", "Dog+", "Activity clues"},
            java.util.Arrays.stream(modes).map(Object::toString).toArray(String[]::new));
        assertEquals("ANBS_PLUS", SusChunkFinderModule.Mode.migrate("ANBS+ Scan", modes));
        for (String token : new String[] {"CLASSIC", "ANBS_PLUS", "ACTIVITY_CLUES", "Dog+", "Classic", "Activity clues", "unknown"})
            assertEquals(token, SusChunkFinderModule.Mode.migrate(token, modes));
        assertEquals("ANBS+ Scan", SusChunkFinderModule.Mode.migrate("ANBS+ Scan", new Enum<?>[0]));
    }

    @Test void heatIsHiddenOnlyForActivityWhileOutlineFiltersRemainCrossMode() throws IOException {
        String source = source();
        for (String id : new String[] {"geode-heat", "geode-spread"})
            assertTrue(setting(source, id).contains("visibleWhen(() -> mode.get() != Mode.ACTIVITY_CLUES)"));
        for (String id : new String[] {"kelp-length", "vine-length", "cave-vine-length", "bamboo-length"})
            assertTrue(setting(source, id).contains("visibleWhen(() -> usesPlantOutline("));
        assertEquals("return objectKinds.get(Category.GROWTH).get() && clueKinds.get(kind).get();",
            body(source, "private boolean usesPlantOutline(ActivityClues.Kind kind)"));
        for (String id : new String[] {"mature-clusters", "mature-percent"})
            assertTrue(setting(source, id).contains("visibleWhen(this::usesMatureOutline)"));
        assertEquals("return !naturalContext.get() && objectKinds.get(Category.GEODES).get() && clueKinds.get(ActivityClues.Kind.AMETHYST).get();",
            body(source, "private boolean usesMatureOutline()"));
        assertEquals("return !naturalContext.get() && clueKinds.get(ActivityClues.Kind.BEES).get() && (mode.get() == Mode.ACTIVITY_CLUES || objectKinds.get(Category.GROWTH).get());",
            body(source, "private boolean usesBeeThreshold()"));
        assertTrue(source.contains("category != Category.ANOMALIES || mode.get() == Mode.ANBS_PLUS"));
        assertFalse(setting(source, "natural-context").contains("visibleWhen"));
    }

    @Test void helpDistinguishesFlagsOutlinesAndOptionalPanelsWithoutEnablingAnything() throws IOException {
        String source = source();
        assertTrue(source.contains("Outline lengths do not set chunk flags"));
        assertTrue(source.contains("Peaks without terrain in that band use 1 instead"));
        assertTrue(source.contains("it is not a probability or proof of a base"));
        assertTrue(source.contains("Dogs / Observations is a separate opt-in panel"));
        assertFalse(Pattern.compile("set(?:ConfiguredEnabled|Enabled|EnabledSilently)\\s*\\(\\s*true").matcher(source).find());
        assertFalse(source.contains("ModuleLookup"));
        assertFalse(source.contains("HudElementRegistry"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of(System.getProperty("finder.sourceRoot", "src"))
            .resolve(Path.of("main", "java", "com", "autism", "seedcracker", "modules", "SusChunkFinderModule.java")))
            .replace("\r\n", "\n");
    }

    private static String setting(String source, String id) {
        int start = source.indexOf("Setting(\"" + id + "\"");
        assertTrue(start >= 0, id);
        return source.substring(start, source.indexOf(";\n", start));
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        start = source.indexOf('{', start) + 1;
        return source.substring(start, source.indexOf("\n    }", start)).trim().replaceAll("\\s+", " ");
    }
}