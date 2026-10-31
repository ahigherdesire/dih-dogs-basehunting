package com.autism.seedcracker.finder;

import com.google.gson.GsonBuilder;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Labelled synthetic policy checks, not measured live-server precision or recall. */
class FinderEvidenceEvaluationTest {
    private record Fixture(String id, String label, LevelChunkSection[] sections, boolean building,
                           String category, int score, int reported, String abstention) {}
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();

    @BeforeAll static void bootstrap() {
        RESULTS.clear();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static LevelChunkSection section(Block fill, Block... blocks) {
        var section = new LevelChunkSection(new PalettedContainer<>(fill.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        for (int index = 0; index < blocks.length; index++)
            section.setBlockState(index & 15, index >> 8, (index >> 4) & 15, blocks[index].defaultBlockState());
        return section;
    }

    private static Fixture fixture(String id, String label, boolean building, int score, Block... blocks) {
        return new Fixture(id, label, new LevelChunkSection[] {section(Blocks.AIR, blocks)}, building,
            building ? "BUILDING" : "NATURAL_COMPATIBLE", score, Math.max(score, 35),
            building ? "Placement evidence is not proof of an occupied base" : "Abstain from a building claim; raw clue remains low-score");
    }

    @TestFactory Stream<DynamicTest> labelledBlockMixtures() {
        var cases = new ArrayList<Fixture>();
        for (Block block : new Block[] {Blocks.STONE, Blocks.LAVA, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT,
                Blocks.SEA_LANTERN, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER}) {
            cases.add(new Fixture("terrain-" + block, "Observed terrain/glow/geode, not building evidence",
                new LevelChunkSection[] {section(block)}, false, "NATURAL_COMPATIBLE", 0, 35,
                "No building evidence; hypothetical Chunk flag is only a raw clue"));
        }
        cases.add(fixture("mineshaft", "Natural-compatible mineshaft", false, 13,
            Blocks.CHEST, Blocks.TORCH, Blocks.RAIL, Blocks.OAK_PLANKS));
        var village = new ArrayList<Block>();
        for (int i = 0; i < 12; i++) village.add(Blocks.CHEST);
        for (int i = 0; i < 8; i++) { village.add(Blocks.FURNACE); village.add(Blocks.BREWING_STAND); }
        for (int i = 0; i < 30; i++) village.add(Blocks.TORCH);
        cases.add(fixture("village", "Natural-compatible village storage/workstations/lights", false, 35,
            village.toArray(Block[]::new)));
        var temple = new ArrayList<Block>();
        for (int i = 0; i < 8; i++) {
            temple.add(Blocks.DISPENSER); temple.add(Blocks.STICKY_PISTON); temple.add(Blocks.REDSTONE_WIRE);
        }
        cases.add(fixture("temple", "Natural-compatible temple mechanisms/storage", false, 35,
            temple.toArray(Block[]::new)));
        cases.add(fixture("storage-only", "Ambiguous bulk storage without independent placement clues", false, 35,
            Blocks.CHEST, Blocks.CHEST, Blocks.CHEST, Blocks.BARREL, Blocks.BARREL));
        cases.add(fixture("single-chest", "Ambiguous sparse storage", false, 8, Blocks.CHEST));
        cases.add(fixture("one-hopper", "Automation below the existing corroboration threshold", false, 16,
            Blocks.HOPPER, Blocks.CHEST));
        cases.add(fixture("storage-automation", "Constructed storage plus two automation blocks", true, 18,
            Blocks.HOPPER, Blocks.OBSERVER, Blocks.CHEST));
        cases.add(fixture("storage-room", "Constructed storage/workstation/light mixture", true, 83,
            Blocks.CHEST, Blocks.CHEST, Blocks.BARREL, Blocks.DYED_SHULKER_BOX.red(), Blocks.DYED_SHULKER_BOX.black(),
            Blocks.HOPPER, Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.BREWING_STAND,
            Blocks.TORCH, Blocks.TORCH, Blocks.LANTERN, Blocks.LANTERN, Blocks.REDSTONE_LAMP));
        for (Block block : new Block[] {Blocks.ENDER_CHEST, Blocks.SHULKER_BOX, Blocks.DYED_SHULKER_BOX.blue(),
                Blocks.ENCHANTING_TABLE, Blocks.CRAFTER, Blocks.BEACON, Blocks.CONDUIT, Blocks.RESPAWN_ANCHOR}) {
            int score = block == Blocks.ENDER_CHEST || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock ? 8 : 5;
            cases.add(fixture("device-" + block, "A lone distinct placed device warrants investigation, not high certainty", true, score, block));
            cases.add(new Fixture("buried-device-" + block, "The same device embedded in natural stone",
                new LevelChunkSection[] {section(Blocks.STONE, block)}, true, "BUILDING", score, 35,
                "A lone placed device is still only weak evidence"));
        }
        cases.add(new Fixture("missing-sections", "Unknown/unavailable section data", null, false, "UNKNOWN", 0, 0,
            "Abstain: unavailable observations must not acquire a source floor"));
        cases.add(new Fixture("hidden-section", "Partial section data cannot establish complete context",
            new LevelChunkSection[] {section(Blocks.AIR, Blocks.BEACON), null}, false, "UNKNOWN", 0, 0,
            "Abstain: a missing section is unknown, not air"));
        cases.add(new Fixture("observed-air", "Observed air is not an assertion about server-hidden blocks",
            new LevelChunkSection[] {section(Blocks.AIR)}, false, "NATURAL_COMPATIBLE", 0, 35,
            "Abstain from hidden-world inference; cannot identify anti-xray data from an ordinary palette"));
        return cases.stream().map(f -> DynamicTest.dynamicTest(f.id(), () -> check(f.id(), f.label(), f.abstention(),
            Map.of("building", f.building(), "category", f.category(), "score", f.score(), "reported", f.reported(),
                "nonemptyReasonWhenScored", true), () -> {
                var result = BaseConfidence.scoreSections(f.sections());
                boolean building = BaseConfidence.buildEvidenceSections(f.sections());
                var actual = new LinkedHashMap<String, Object>();
                actual.put("building", building);
                actual.put("category", category(result, building, f.sections() == null));
                actual.put("categoryOrigin", hasMethod(BaseConfidence.Result.class, "evidence") ? "typed-result" : "legacy-hasBuildEvidence-derived");
                actual.put("score", result.score());
                actual.put("reason", result.topReason());
                actual.put("nonemptyReasonWhenScored", result.score() == 0 || !result.topReason().isBlank());
                var report = new FinderReport("Evaluation", 55);
                var world = new Object(); var chunk = new Object(); var pos = new ChunkPos(0, 0);
                var observed = new AtomicInteger();
                for (int tick = 0; tick < 20; tick++) tick(report, world, Set.of(pos), p -> chunk, p -> result,
                    (p, value) -> observed.set(value));
                actual.put("reported", observed.get());
                report.clear();
                return actual;
            })));
    }

    @TestFactory Stream<DynamicTest> labelledLifecycleAndCorrelation() {
        return Stream.of(
            DynamicTest.dynamicTest("filter-100-stable", () -> check("filter-100-stable", "100 loaded, supported candidates",
                "None within the admitted coverage budget", Map.of("minimumRetainedAfterWarmup", 100, "maxScansPerTick", 1),
                () -> filterLifecycle(100, 350, false))),
            DynamicTest.dynamicTest("filter-churn", () -> check("filter-churn", "40 stable candidates plus a constantly replaced newcomer",
                "New observations wait their bounded turn", Map.of("minimumRetainedAfterWarmup", 40, "maxScansPerTick", 1),
                () -> filterLifecycle(40, 250, true))),
            DynamicTest.dynamicTest("report-150-stable", () -> check("report-150-stable", "150 supported report candidates",
                "None within the admitted coverage budget", Map.of("minimumRetainedAfterWarmup", 150, "maxScansPerTick", 1),
                FinderEvidenceEvaluationTest::reportLifecycle)),
            DynamicTest.dynamicTest("filter-report-pipeline", () -> check("filter-report-pipeline", "200 stable candidates through corroboration and reporting together",
                "Each stage has its own one-scan/tick budget; allow both stages to warm up",
                Map.of("minimumRetainedAfterWarmup", 200, "maxFilterScansPerTick", 1, "maxReportScansPerTick", 1),
                FinderEvidenceEvaluationTest::pipelineLifecycle)),
            DynamicTest.dynamicTest("filter-coverage-ceiling", () -> check("filter-coverage-ceiling", "201 loaded candidates, 200-scan freshness ceiling",
                "One deferred candidate is explicitly unassessed, not classified as natural", Map.of("retained", 200, "deferred", 1), () -> {
                    var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
                    var candidates = positions(201); Set<ChunkPos> shown = Set.of();
                    for (int tick = 0; tick < 420; tick++) shown = filter.filter(world, candidates, p -> chunk, p -> true);
                    return Map.of("retained", shown.size(), "deferred", deferred(filter));
                })),
            DynamicTest.dynamicTest("filter-invalidated-in-flight", () -> check("filter-invalidated-in-flight", "A scan is invalidated while evaluating",
                "Abstain until a new valid observation", Map.of("retained", 0), () -> {
                    var filter = new BuildEvidenceFilter(); var pos = new ChunkPos(0, 0); var chunk = new Object();
                    return Map.of("retained", filter.filter(new Object(), Set.of(pos), p -> chunk,
                        p -> { filter.invalidate(p); return true; }).size());
                })),
            DynamicTest.dynamicTest("report-replacement", () -> check("report-replacement", "A loaded chunk object is replaced between refreshes",
                "Withdraw the old observation before the next report cadence", Map.of("retainedImmediately", 0), () -> {
                    var report = new FinderReport("Evaluation", 0); var world = new Object(); var pos = new ChunkPos(0, 0);
                    var chunk = new Object[] {new Object()};
                    BiConsumer<ChunkPos, Integer> publish = (p, value) -> BaseTracker.storeReport(8, 8, value, "Evaluation", 1000);
                    for (int tick = 0; tick < 20; tick++) tick(report, world, Set.of(pos), p -> chunk[0],
                        p -> new BaseConfidence.Result(80, "verified"), publish);
                    chunk[0] = new Object();
                    tick(report, world, Set.of(pos), p -> chunk[0], p -> BaseConfidence.score(null), publish);
                    return Map.of("retainedImmediately", BaseTracker.retainedEntries());
                })),
            DynamicTest.dynamicTest("correlated-natural-reports", () -> check("correlated-natural-reports", "Repeated Chunk/Sus/Quantum clues are correlated",
                "No extra building certainty from repetition or finder names", Map.of("score", 35, "x", 8, "z", 8), () -> correlated(false))),
            DynamicTest.dynamicTest("direct-verified-report", () -> check("direct-verified-report", "A stronger direct verified report retains its score and actual coordinates",
                "None; fusion remains strongest-observation, not a sum", Map.of("score", 90, "x", 40, "z", 8), () -> correlated(true)))
        );
    }

    private static Map<String, Object> filterLifecycle(int stableCount, int ticks, boolean churn) {
        var filter = new BuildEvidenceFilter(); var world = new Object(); var chunk = new Object();
        var stable = positions(stableCount); var calls = new AtomicInteger(); int minimum = Integer.MAX_VALUE, max = 0;
        for (int t = 0; t < ticks; t++) {
            var candidates = new LinkedHashSet<>(stable);
            if (churn) candidates.add(new ChunkPos(1000 + t, 0));
            int before = calls.get();
            var shown = filter.filter(world, candidates, p -> chunk, p -> { calls.incrementAndGet(); return true; });
            max = Math.max(max, calls.get() - before);
            if (t >= stableCount * 2) minimum = Math.min(minimum, (int) shown.stream().filter(stable::contains).count());
        }
        return Map.of("minimumRetainedAfterWarmup", minimum, "maxScansPerTick", max, "totalScans", calls.get());
    }

    private static Map<String, Object> reportLifecycle() {
        var report = new FinderReport("Evaluation", 0); var world = new Object(); var chunk = new Object();
        var candidates = positions(150); var calls = new AtomicInteger(); int minimum = Integer.MAX_VALUE, max = 0;
        for (int t = 0; t < 420; t++) {
            int before = calls.get();
            report.tick(world, candidates, p -> true, p -> { calls.incrementAndGet(); return 80; },
                (p, score) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, score, "Evaluation", 1000));
            max = Math.max(max, calls.get() - before);
            if (t >= 200 && (t + 1) % 20 == 0) minimum = Math.min(minimum, BaseTracker.retainedEntries());
        }
        return Map.of("minimumRetainedAfterWarmup", minimum, "maxScansPerTick", max, "totalScans", calls.get());
    }

    private static Map<String, Object> pipelineLifecycle() {
        var filter = new BuildEvidenceFilter(); var report = new FinderReport("Evaluation", 0);
        var world = new Object(); var chunk = new Object(); var candidates = positions(200);
        var filtering = new AtomicInteger(); var scoring = new AtomicInteger();
        int minimum = Integer.MAX_VALUE, maxFilter = 0, maxReport = 0;
        for (int t = 1; t <= 800; t++) {
            int beforeFilter = filtering.get(), beforeReport = scoring.get(); long now = t * 50L;
            var shown = filter.filter(world, candidates, p -> chunk, p -> { filtering.incrementAndGet(); return true; });
            report.tick(world, shown, p -> true, p -> { scoring.incrementAndGet(); return 80; },
                (p, value) -> BaseTracker.storeReport(p.getMinBlockX() + 8, 8, value, "Evaluation", now));
            maxFilter = Math.max(maxFilter, filtering.get() - beforeFilter);
            maxReport = Math.max(maxReport, scoring.get() - beforeReport);
            if (t >= 420) minimum = Math.min(minimum, BaseTracker.nearestAt(0, 0, 201, now).size());
        }
        return Map.of("minimumRetainedAfterWarmup", minimum, "maxFilterScansPerTick", maxFilter, "maxReportScansPerTick", maxReport);
    }

    private static Map<String, Object> correlated(boolean direct) throws Exception {
        var world = new Object(); var chunk = new Object(); var pos = new ChunkPos(0, 0);
        var fixture = BaseConfidence.scoreSections(new LevelChunkSection[] {section(Blocks.AIR,
            Blocks.CHEST, Blocks.CHEST, Blocks.CHEST, Blocks.CHEST, Blocks.CHEST)});
        int[] floors = {55, 45, 40}; String[] sources = {"Chunk", "Sus", "Quantum"};
        for (int i = 0; i < floors.length; i++) {
            String source = sources[i]; var report = new FinderReport(source, floors[i]);
            for (int t = 0; t < 40; t++) tick(report, world, Set.of(pos), p -> chunk, p -> fixture,
                (p, value) -> BaseTracker.storeReport(8, 8, value, source, 1000));
        }
        if (direct) BaseTracker.storeReport(40, 8, 90, "Direct", 1000);
        var fused = BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst();
        return Map.of("score", fused.confidence(), "x", fused.blockX(), "z", fused.blockZ(), "sources", fused.sources());
    }

    private static LinkedHashSet<ChunkPos> positions(int count) {
        var result = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < count; i++) result.add(new ChunkPos(i, 0));
        return result;
    }

    // The same labelled test can measure the pre-patch API; production never uses reflection or reason parsing.
    private static void tick(FinderReport report, Object world, Set<ChunkPos> candidates,
                             Function<ChunkPos, Object> chunks, Function<ChunkPos, BaseConfidence.Result> scores,
                             BiConsumer<ChunkPos, Integer> publish) throws Exception {
        try {
            Method method = FinderReport.class.getDeclaredMethod("tickEvidence", Object.class, Set.class,
                Function.class, Function.class, BiConsumer.class);
            method.setAccessible(true);
            method.invoke(report, world, candidates, chunks, scores, publish);
        } catch (NoSuchMethodException legacy) {
            report.tick(world, candidates, p -> chunks.apply(p) != null, p -> scores.apply(p).score(), publish);
        }
    }

    private static String category(BaseConfidence.Result result, boolean building, boolean missing) throws Exception {
        try { return result.getClass().getMethod("evidence").invoke(result).toString(); }
        catch (NoSuchMethodException legacy) { return missing ? "UNKNOWN" : building ? "BUILDING" : "NATURAL_COMPATIBLE"; }
    }

    private static int deferred(Object filter) {
        try { return (int) filter.getClass().getMethod("deferredCandidates").invoke(filter); }
        catch (ReflectiveOperationException legacy) { return -1; }
    }

    private static boolean hasMethod(Class<?> type, String method) {
        try { type.getMethod(method); return true; }
        catch (NoSuchMethodException absent) { return false; }
    }

    @FunctionalInterface private interface Observation { Map<String, Object> get() throws Exception; }

    private static void check(String id, String label, String abstention, Map<String, Object> expected, Observation observe) throws Exception {
        BaseTracker.clear();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("label", label); row.put("abstention", abstention); row.put("expected", expected);
        try {
            var actual = observe.get(); row.put("actual", actual);
            boolean passed = expected.entrySet().stream().allMatch(e -> e.getValue().equals(actual.get(e.getKey())));
            row.put("passed", passed); RESULTS.add(row);
            assertAll(id, expected.entrySet().stream().map(e -> () -> assertEquals(e.getValue(), actual.get(e.getKey()), e.getKey())));
        } catch (Exception error) {
            row.put("error", error.toString()); row.put("passed", false); RESULTS.add(row); throw error;
        } finally { BaseTracker.clear(); }
    }

    @AfterAll static void writeEvaluation() throws Exception {
        String output = System.getProperty("finder.evaluation.output");
        if (output == null) return;
        var result = Map.of("kind", "labelled synthetic policy evaluation", "livePrecisionRecall", "not measured",
            "judgeCalibration", "not applicable: deterministic assertions only", "cases", RESULTS);
        Files.writeString(Path.of(output), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
