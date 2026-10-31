// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.activity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnubisSimulationSignalsTest {
    private static final UUID ENTITY = new UUID(0, 1);
    private static final long SECOND = 1_000_000_000L;
    private final AnubisSimulationSignals signals = new AnubisSimulationSignals();

    private boolean sample(double x, boolean own, boolean packet, long seconds) {
        return signals.observe(7, ENTITY, 10, x, 70, 0, own, packet, seconds * SECOND);
    }

    @Test void onlyAppliedPositionsProduceEvidence() {
        assertFalse(sample(0, false, true, 0));
        assertFalse(sample(1, false, false, 3));
        assertFalse(sample(0, false, true, 4));
        assertTrue(sample(1, false, true, 5));
        assertFalse(sample(1, false, true, 6));
    }

    @Test void renderedBaselineIsNotPacketProof() {
        assertFalse(sample(0, false, false, 0));
        assertFalse(sample(1, false, true, 3));
        assertTrue(sample(2, false, true, 4));
    }

    @Test void freshEntityNeedsTwoSeconds() {
        assertFalse(sample(0, false, true, 0));
        assertFalse(sample(1, false, true, 1));
        assertTrue(sample(2, false, true, 2));
    }

    @Test void entityLeavingOwnRangeIsQuarantinedForSixtySeconds() {
        assertFalse(sample(0, true, true, 0));
        assertFalse(sample(1, false, true, 3));
        assertFalse(sample(2, false, true, 59));
        assertTrue(sample(3, false, true, 60));
    }

    @Test void renderedOwnRangeRefreshesOriginButNotPosition() {
        sample(0, false, true, 0);
        sample(50, true, false, 5);
        assertFalse(sample(1, false, true, 64));
        assertTrue(sample(2, false, true, 65));
    }

    @Test void hugeMovementAndInvalidPositionAreNotActivity() {
        sample(0, false, true, 0);
        assertFalse(sample(16, false, true, 3));
        assertFalse(sample(Double.NaN, false, true, 4));
        assertEquals(0, signals.size());
        assertFalse(sample(17, false, true, 5));
    }

    @Test void reusedEntityIdAndClockRollbackRequireNewBaseline() {
        sample(0, false, true, 0);
        assertFalse(signals.observe(7, new UUID(0, 2), 10, 1, 70, 0, false, true, 3 * SECOND));
        assertFalse(signals.observe(7, new UUID(0, 2), 10, 2, 70, 0, false, true, 2 * SECOND));
    }

    @Test void forgettingChunkAndSessionDropsAllEvidence() {
        sample(0, false, true, 0);
        assertEquals(10L, signals.chunk(7));
        signals.forgetChunk(10);
        assertFalse(sample(1, false, true, 3));
        signals.clear();
        assertEquals(0, signals.size());
        assertNull(signals.chunk(7));
    }

    @Test void memoryIsBoundedAndRemovalAllowsNewBaseline() {
        for (int id = 0; id < AnubisSimulationSignals.MAX_ENTITIES + 10; id++) {
            signals.observe(id, ENTITY, 10, 0, 70, 0, false, true, 0);
        }
        assertEquals(AnubisSimulationSignals.MAX_ENTITIES, signals.size());
        signals.retain(id -> id == 0);
        assertEquals(1, signals.size());
        signals.forget(0);
        assertEquals(0, signals.size());
    }

    @Test void renderingCannotCreateActivityAndPacketCoordinatesAreAuthoritative() throws Exception {
        String source = moduleSource();
        String rendered = source.substring(source.indexOf("private void sampleEntities("), source.indexOf("private void sampleBlock("));
        assertFalse(rendered.contains("signal("));
        assertTrue(rendered.contains("own, false, now"));
        assertTrue(source.contains("entity.getPositionCodec().getBase()"));
        assertTrue(source.contains("com.autism.seedcracker.compat.McCompat.syncPosition(sync)"));
        assertTrue(source.contains("if (entity == null || position == null) return;"));
        assertTrue(source.contains("if (!mc.isSameThread()) return false"));
    }

    @Test void missingChunkBaselineAndBoundarySpillCannotBecomeEvidence() throws Exception {
        String source = moduleSource();
        assertTrue(source.contains("Long loaded = loadedAt.get(pos)"));
        assertFalse(source.contains("loadedAt.putIfAbsent"));
        assertTrue(source.contains("loaded == null || now - loaded < 3_000_000_000L"));
        assertTrue(source.contains("!candidate(mc, pos, now, 1)"));
        assertTrue(source.contains("block.getY() < 0"));
        assertTrue(source.contains("entitySignals.forgetChunk("));
    }

    @Test void moduleDefaultsAndQuietRawLightPolicyAreUnchanged() throws Exception {
        String source = moduleSource();
        assertTrue(source.contains("public enum Mode { LIGHT_DATA, SIMULATION_ACTIVITY }"));
        assertTrue(source.contains("\"Detection mode\", Mode.LIGHT_DATA"));
        assertTrue(source.contains("\"Memory seconds\", 45, 5, 180, 5"));
        assertTrue(source.contains("\"Independent activity sources\", 4, 2, 16, 1"));
        assertTrue(source.contains("\"Require building evidence\", true"));
        assertTrue(source.contains("mode.get() == Mode.LIGHT_DATA && !requireBuild.get() ? Set.of() : flagged"));
        assertTrue(source.contains("\"Auto disconnect\", false"));
    }

    @Test void extraSimulationPacketsCannotChurnDefaultLightTracking() throws Exception {
        String source = moduleSource();
        int entry = source.indexOf("public void onPacketApplied(");
        String early = source.substring(entry, source.indexOf("if (!ready()) return;", entry));
        assertTrue(early.contains("mode.get() == Mode.LIGHT_DATA && !(packet instanceof"));
        for (String type : new String[] {"ClientboundLevelChunkWithLightPacket", "ClientboundLightUpdatePacket",
                "ClientboundForgetLevelChunkPacket", "ClientboundBlockUpdatePacket", "ClientboundSectionBlocksUpdatePacket"})
            assertTrue(early.contains(type), type);
        assertTrue(early.contains(")) return;"));
    }

    @Test void simulationHooksAreAppliedOnClientThreadAndIsolatedFromOtherFinders() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("finder.sourceRoot", "src"),
            "main", "java", "kaptainwutax", "seedcrackerX", "mixin", "ClientPacketListenerMixin.java"));
        int start = source.indexOf("@Inject(method = {\"handleMoveEntity\"");
        assertTrue(start >= 0);
        String route = source.substring(start, source.indexOf("private void spectre$packetApplied(", start));
        for (String handler : new String[] {"handleMoveEntity", "handleEntityPositionSync", "handleAddEntity",
                "handleRemoveEntities", "handleMovePlayer", "handleRespawn"}) assertTrue(route.contains('"' + handler + '"'), handler);
        assertTrue(route.contains("at = @At(\"TAIL\")"));
        assertTrue(route.contains("!mc.isSameThread() || this.level == null || this.level != mc.level"));
        assertTrue(route.contains("finder.onPacketApplied(packet)"));
        assertFalse(route.contains("spectre$packetApplied(packet)"));
        assertEquals(2, source.split(java.util.regex.Pattern.quote("spectre$playerPacketApplied(packet);"), -1).length - 1);
    }

    private static String moduleSource() throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("finder.sourceRoot", "src"),
            "main", "java", "com", "autism", "seedcracker", "modules", "PlayerChunksModule.java"));
    }

    @Test void anubisNaturalGrowthAndHiveExclusionsAreRetained() {
        for (String name : new String[] {"pointed_dripstone", "cave_vines", "cave_vines_plant", "sweet_berry_bush",
                "beehive", "bee_nest", "vine", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant"}) {
            assertTrue(AnubisSimulationSignals.ignoredBlock("minecraft:" + name), name);
        }
        assertFalse(AnubisSimulationSignals.ignoredBlock("minecraft:stone"));
        assertFalse(AnubisSimulationSignals.ignoredBlock("minecraft:piston"));
        assertFalse(AnubisSimulationSignals.ignoredBlock("mod:vine"));
        assertFalse(AnubisSimulationSignals.ignoredBlock(null));
    }
}
