package com.autism.seedcracker.corz;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source contracts supplement the geometry/cache regressions without requiring session artifacts. */
class CorzAmethystIntegrationTest {
    private static final Path ROOT = Path.of(System.getProperty("corz.overlay", "."));
    private static final String MODULE = "src/main/java/com/autism/seedcracker/modules/AmethystEspModule.java";
    private static String read(String path) throws Exception { return Files.readString(ROOT.resolve(path)).replace("\r\n", "\n"); }
    private static String body(String source, String header) {
        int start = source.indexOf(header); assertTrue(start >= 0, header);
        int opening = source.indexOf('{', start), depth = 1, end = opening + 1;
        for (; end < source.length() && depth > 0; end++) {
            if (source.charAt(end) == '{') depth++; else if (source.charAt(end) == '}') depth--;
        }
        assertEquals(0, depth); return source.substring(start, end);
    }
    @Test void optionIsOffByDefaultAndOnlyAddsQuietContextInfo() throws Exception {
        String text = read(MODULE);
        assertTrue(text.contains("new BoolSetting(\"corz-geode-context\", \"Geode morphology context (quiet)\", false)"));
        assertTrue(body(text, "@Override public String info()").contains("if (corzContext.get()) info += morphologyInfo;"));
        assertFalse(text.contains("FinderReport")); assertFalse(text.contains("BaseAlerts"));
    }
    @Test void defaultObservedRendererAndFeedsRemainByteForByteUnchanged() throws Exception {
        byte[] renderer = body(read(MODULE), "private void render()").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("56dd668ce510e27de840ad432b059784e8e5289cbb9c242f6b73b96e5096ce33",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(renderer)));
    }
    @Test void togglingContextDoesNotClearOrReplaceObservedFormationGeometry() throws Exception {
        String toggle = body(read(MODULE), "if (lastCorzContext != corzContext.get())");
        assertTrue(toggle.contains("clearMorphology()")); assertTrue(toggle.contains("scan.reset()"));
        assertFalse(toggle.contains("clearScan()")); assertFalse(toggle.contains("cache.clear"));
        assertFalse(toggle.contains("cancelPending()"));
    }
    @Test void existingClientThreadLifecycleInvalidatesContextButLightDoesNot() throws Exception {
        String text = read(MODULE);
        assertTrue(body(text, "private void cancelAffected(ChunkPos pos)").contains("invalidateMorphology(pos)"));
        assertTrue(body(text, "public void onBlockUpdate(").contains("invalidateMorphology(chunk)"));
        assertFalse(body(text, "public void onLightApplied(").contains("invalidateMorphology"));
        assertTrue(body(text, "private void clearScan()").contains("clearMorphology()"));
        assertTrue(body(text, "private void cancelPending()").contains("pendingMorphology = null"));
    }
    @Test void capturesReuseExistingSectionWalkAndImmutableJobsShareItsBudget() throws Exception {
        String tick = body(read(MODULE), "@Override public void tick()");
        assertTrue(tick.contains("CorzGeodeSampler.captureSection(mc, level, pending, index, pendingMorphology)"));
        assertTrue(tick.contains("if (worked) remaining--"));
        assertTrue(tick.contains("morphology.advance(CorzGeodeContextCache.EVALUATION_CELLS_PER_TICK, deadline)"));
        assertTrue(tick.contains("remaining > 1 || evaluateMorphologyNext"));
        assertTrue(tick.contains("morphology.offer(pendingMorphology.freeze())"));
        assertFalse(tick.contains("Executor")); assertFalse(tick.contains("new Thread"));
    }
    @Test void samplerNeverLoadsChunksOrTreatsMissingHeightAndSectionsAsAir() throws Exception {
        String sampler = read("src/main/java/com/autism/seedcracker/corz/CorzGeodeSampler.java");
        assertTrue(sampler.contains("client.isSameThread()")); assertTrue(sampler.contains("getChunkNow("));
        assertFalse(sampler.contains(".getChunk(")); assertTrue(sampler.contains("byte kind = CorzGeodeMorphology.UNKNOWN"));
        assertTrue(sampler.contains("y >= level.getMinY() && y <= level.getMaxY()"));
        assertTrue(sampler.contains("if (neighbor != null)")); assertTrue(sampler.contains("if (section != null) kind = classify"));
        assertTrue(sampler.contains("Math.floorDiv(x, 16)"));
    }
    @Test void helpersHaveNoAlertsNativeDispatchOrBackgroundWorldAccess() throws Exception {
        for (String name : new String[]{"CorzGeodeMorphology", "CorzGeodeContextCache", "CorzGeodeSampler"}) {
            String source = read("src/main/java/com/autism/seedcracker/corz/" + name + ".java");
            for (String forbidden : new String[]{"Runtime.run(", "System.load(", "sendMessage(", "sendPrefixed(", "FinderReport", "BaseAlerts", "Executors.", "CompletableFuture."})
                assertFalse(source.contains(forbidden), name + ": " + forbidden);
        }
    }
}
