// SPDX-License-Identifier: GPL-3.0-only
// Native port of Spectre SusChunkFinderModule, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.StoneStackScanner;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.chunks.ActivityClues;
import com.autism.seedcracker.spectre.chunks.ChunkSignals;
import com.autism.seedcracker.spectre.chunks.SusChunkScan;
import com.autism.seedcracker.spectre.chunks.SuspectObjects;
import com.autism.seedcracker.spectre.chunks.SuspectObjects.Category;
import com.autism.seedcracker.util.pure.SusScore;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.EnumSetting;
import dihclient.api.module.IntSetting;
import dihclient.api.module.StringSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SusChunkFinderModule extends Module {
    public enum Mode {
        CLASSIC, ANBS_PLUS, ACTIVITY_CLUES;
        @Override public String toString() { return switch (this) { case CLASSIC -> "Classic"; case ANBS_PLUS -> "Dog+"; case ACTIVITY_CLUES -> "Activity clues"; }; }
        public static String migrate(String token, Enum<?>[] choices) {
            return "ANBS+ Scan".equals(token) && choices.length > 0 && choices[0].getDeclaringClass() == Mode.class ? "ANBS_PLUS" : token;
        }
    }
    private static final int MAX_DETAILS = 8192, MAX_MARKERS = 2048;
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Detection mode", Mode.CLASSIC, Mode.values(), Mode::toString)
        .description("Chooses chunk flags, not the object outline categories. Classic and Dog+ use chunk heat; Activity clues uses bee points or optional natural context.").group("Detection"));
    private final IntSetting threshold = add(new IntSetting("geode-heat", "Minimum chunk heat", 6, 1, 64, 1)
        .description("Classic / Dog+ only: minimum spread heat at eligible peaks with terrain in Y -16..30. Peaks without terrain in that band use 1 instead. Does not filter object outlines.")
        .group("Chunk heat").visibleWhen(() -> mode.get() != Mode.ACTIVITY_CLUES));
    private final IntSetting spread = add(new IntSetting("geode-spread", "Heat spread (chunks)", 5, 0, 16, 1)
        .description("Classic / Dog+ only: radius used to spread heat and vetoes and compare nearby peaks. An inference setting, not a change to server simulation distance.")
        .group("Chunk heat").visibleWhen(() -> mode.get() != Mode.ACTIVITY_CLUES));
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 12, 2, 32, 1)
        .description("Search already-loaded chunks around you. Does not load chunks; object outlines also have a separate block range.").group("Performance"));
    private final IntSetting budget = add(new IntSetting("chunks-per-tick", "Completed chunks per tick", 2, 1, 128, 1)
        .description("Maximum completed scans per tick, still bounded by the existing time budget. Larger values do not guarantee faster results.").group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify on chunk hypotheses", true)
        .description("Alerts for newly flagged chunks, not each outlined object. A flag or reported confidence is not proof of a base.").group("Chunk markers / alerts"));
    private final BoolSetting detectMined = add(new BoolSetting("detect-mined-clusters", "Bare formation hypotheses", false)
        .description("Optional outlines in every mode: large budding formations without visible crystals. Requires the Amethyst observation type; natural generation or hidden blocks can look the same. Does not flag chunks.").group("Growth / amethyst filters"));
    private final ColorSetting minedColor = add(new ColorSetting("mined-color", "Bare formation colour", 0x60A64AFF).group("Object colours").visibleWhen(detectMined::get));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk flag colour", 0x96FF4444)
        .description("Chunk markers only. Object outline colours are separate.").group("Chunk markers / alerts"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Trace to flagged chunks", false)
        .description("Tracer lines to chunk flags, not individual object outlines.").group("Chunk markers / alerts"));
    private final IntSetting clueThreshold = add(new IntSetting("clue-threshold", "Minimum bee clue points", 2, 1, 8, 1)
        .description("Activity clues chunk flags AND bee outlines in every mode. Honey nests give 2 points each (up to 4); any crafted hive gives 4. Natural context bypasses this filter.")
        .group("Growth / amethyst filters").visibleWhen(this::usesBeeThreshold));
    private final BoolSetting naturalContext = add(new BoolSetting("natural-context", "Include natural growth context", false)
        .description("Every mode: include short plants, immature amethyst and unscored growth / bees in outlines. Activity clues ALSO flags any selected growth, bypassing bee points. Can be noisy; does not prove player activity.").group("Growth / amethyst filters"));
    private final IntSetting kelpLength = add(new IntSetting("kelp-length", "Kelp outline length (blocks)", 16, 2, 64, 1)
        .description("Object outlines in every mode, not chunk flags. Natural context includes shorter plants too.")
        .group("Growth / amethyst filters").visibleWhen(() -> usesPlantOutline(ActivityClues.Kind.KELP)));
    private final IntSetting vineLength = add(new IntSetting("vine-length", "Vine outline length (blocks)", 16, 2, 64, 1)
        .description("Object outlines in every mode, not chunk flags. Natural context includes shorter plants too.")
        .group("Growth / amethyst filters").visibleWhen(() -> usesPlantOutline(ActivityClues.Kind.VINES)));
    private final IntSetting caveVineLength = add(new IntSetting("cave-vine-length", "Cave vine outline length (blocks)", 20, 2, 64, 1)
        .description("Object outlines in every mode, not chunk flags. Natural context includes shorter plants too.")
        .group("Growth / amethyst filters").visibleWhen(() -> usesPlantOutline(ActivityClues.Kind.CAVE_VINES)));
    private final IntSetting bambooLength = add(new IntSetting("bamboo-length", "Bamboo outline height (blocks)", 14, 2, 32, 1)
        .description("Object outlines in every mode, not chunk flags. Natural context includes shorter plants too.")
        .group("Growth / amethyst filters").visibleWhen(() -> usesPlantOutline(ActivityClues.Kind.BAMBOO)));
    private final IntSetting matureClusters = add(new IntSetting("mature-clusters", "Amethyst outline: mature crystals", 12, 2, 128, 1)
        .description("Minimum mature crystals in a connected formation containing budding amethyst for an outline, in every mode. Not Classic / Dog+ chunk heat or the bare-formation check.")
        .group("Growth / amethyst filters").visibleWhen(this::usesMatureOutline));
    private final IntSetting maturePercent = add(new IntSetting("mature-percent", "Amethyst outline: mature %", 75, 10, 100, 5)
        .description("Minimum observed mature crystals as a percentage of crystals plus buds for an outline. Not time spent by players or base confidence.")
        .group("Growth / amethyst filters").visibleWhen(this::usesMatureOutline));
    private final IntSetting objectRange = add(new IntSetting("object-range", "Object outline range (blocks)", 128, 16, 256, 16)
        .description("Limits collected object details and outlines around your eyes, not chunk heat. The optional Dogs / Observations panel reads these same objects; enable it separately.").group("Object outlines"));
    private final EnumMap<ActivityClues.Kind, BoolSetting> clueKinds = new EnumMap<>(ActivityClues.Kind.class);
    private final EnumMap<Category, BoolSetting> objectKinds = new EnumMap<>(Category.class);
    private final EnumMap<Category, ColorSetting> objectColors = new EnumMap<>(Category.class);
    private final Map<ChunkPos, SusChunkScan.Sample> samples = new LinkedHashMap<>();
    private final Map<ChunkPos, Integer> scannedAt = new HashMap<>();
    private final LinkedHashSet<ChunkPos> queue = new LinkedHashSet<>();
    private final Set<ChunkPos> flagged = new HashSet<>(), alerted = new HashSet<>();
    private final FinderReport reporter = new FinderReport("Sus", 45);
    private ClientLevel world;
    private SusChunkScan active;
    private ChunkPos activePos;
    private int ticks, storedDetails;
    private boolean detailLimited;
    private List<SuspectObjects.Marker> markers = List.of();
    private final EnumMap<Category, List<AABB>> boxes = new EnumMap<>(Category.class);

    public SusChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder",
            "Chunk hypotheses plus separate object outlines. Start with Detection mode; outline filters work in every mode. Dogs / Observations is a separate opt-in panel. Confidence is not proof of a base.");
        add(new StringSetting("mode-help", "What this mode flags", "").readonlySummary()
            .formatter(ignored -> modeHelp()).group("Detection"));
        add(new StringSetting("scan-status", "Current observations", "").readonlySummary()
            .formatter(ignored -> info()).description("Chunk flags and object outlines are separate outputs. Reported confidence ranks clues; it is not a probability or proof of a base. Capped details can omit outlines.").group("Detection"));
        for (var kind : ActivityClues.Kind.values()) clueKinds.put(kind, add(new BoolSetting("clue-" + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), kind.label, true)
            .description("Include this type in Activity clues context and object outlines in EVERY mode. Does not switch Classic / Dog+ chunk heat on or off. Only honey nests and crafted hives supply bee points.").group("Observed growth types")));
        for (var category : Category.values()) {
            if (category == Category.BARE_GEODES) { objectKinds.put(category, detectMined); objectColors.put(category, minedColor); continue; }
            objectKinds.put(category, add(new BoolSetting("objects-" + category.id(), category.label + " outlines", true)
                .description(category == Category.ANOMALIES ? "Dog+ only: observed light edges inside flagged chunks. Not proof of a player or base."
                    : "Show observed objects, independently of chunk flags. Growth / bees and amethyst also use the observation types and outline filters.")
                .group("Object outlines").visibleWhen(() -> category != Category.ANOMALIES || mode.get() == Mode.ANBS_PLUS)));
            objectColors.put(category, add(new ColorSetting("color-" + category.id(), category.label + " colour", category.color)
                .group("Object colours").visibleWhen(() -> objectKinds.get(category).get() && (category != Category.ANOMALIES || mode.get() == Mode.ANBS_PLUS))));
        }
    }

    private String modeHelp() {
        return switch (mode.get()) {
            case CLASSIC -> "Amethyst heat with young-bud vetoes (palette presence on DonutFolia), in skylit dimensions. Outline filters are separate.";
            case ANBS_PLUS -> "Observed light-edge heat peaks in skylit dimensions. Light anomaly outlines show edges in flagged chunks.";
            case ACTIVITY_CLUES -> "Bee points flag chunks. Natural context also flags any selected growth. Outline lengths do not set chunk flags.";
        };
    }

    private boolean usesBeeThreshold() {
        return !naturalContext.get() && clueKinds.get(ActivityClues.Kind.BEES).get()
            && (mode.get() == Mode.ACTIVITY_CLUES || objectKinds.get(Category.GROWTH).get());
    }

    private boolean usesPlantOutline(ActivityClues.Kind kind) {
        return objectKinds.get(Category.GROWTH).get() && clueKinds.get(kind).get();
    }

    private boolean usesMatureOutline() {
        return !naturalContext.get() && objectKinds.get(Category.GEODES).get() && clueKinds.get(ActivityClues.Kind.AMETHYST).get();
    }

    private void clear(ClientLevel level) {
        samples.clear(); scannedAt.clear(); queue.clear(); flagged.clear(); alerted.clear(); reporter.clear();
        markers = List.of(); boxes.clear(); active = null; activePos = null; storedDetails = 0; ticks = 0; detailLimited = false; world = level;
        ChunkFlagRenderer.clear(id()); ChunkFlagRenderer.clear(id() + ":mined");
        for (var category : Category.values()) BlockEspRenderer.clear(id() + ":" + category.id());
    }
    @Override public void onEnable() {
        clear(Minecraft.getInstance().level); BlockEspRenderer.init(); ChunkFlagRenderer.init();
    }
    @Override public void onDisable() { clear(null); }
    @Override public void onGameLeft() {
        clear(null);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) { onDisable(); setEnabledSilently(false); }
    }
    @Override protected void onSettingsReset() { clear(isEnabled() ? Minecraft.getInstance().level : null); }
    @Override protected void onOptionValueChanged(String id) {
        if ("mode".equals(id) || "detect-mined-clusters".equals(id) || "object-range".equals(id)
            || id.startsWith("clue-") && !"clue-threshold".equals(id) || id.startsWith("objects-")) clear(isEnabled() ? Minecraft.getInstance().level : null);
    }

    /** Call from ClientPacketListener TAIL hooks on the client thread, never Netty. */
    public void onPacketApplied(ClientLevel level, Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread() || level == null || level != mc.level) return;
        if (world != level) clear(level);
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(com.autism.seedcracker.compat.McCompat.chunkX(chunk) + dx, com.autism.seedcracker.compat.McCompat.chunkZ(chunk) + dz));
        } else if (packet instanceof ClientboundLightUpdatePacket light && mode.get() == Mode.ANBS_PLUS) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(com.autism.seedcracker.compat.McCompat.chunkX(light) + dx, com.autism.seedcracker.compat.McCompat.chunkZ(light) + dz));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) forget(forget.pos());
        else if (packet instanceof ClientboundBlockUpdatePacket block) blockChanged(block.getPos());
        else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) section.runUpdates((pos, state) -> blockChanged(pos));
    }
    private void blockChanged(BlockPos pos) {
        for (int cx = (pos.getX() - 1) >> 4; cx <= (pos.getX() + 1) >> 4; cx++)
            for (int cz = (pos.getZ() - 1) >> 4; cz <= (pos.getZ() + 1) >> 4; cz++) enqueue(new ChunkPos(cx, cz));
    }
    private void enqueue(ChunkPos pos) { if (nearby(pos) && queue.size() < 4225) queue.add(pos); }
    private boolean nearby(ChunkPos pos) {
        var mc = Minecraft.getInstance();
        return world != null && mc.player != null && distance(mc.player.chunkPosition(), pos) <= radius.get()
            && world.getChunkSource().getChunkNow(pos.x(), pos.z()) != null;
    }
    private static int distance(ChunkPos a, ChunkPos b) { return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())); }
    private void forget(ChunkPos pos) {
        var old = samples.remove(pos); if (old != null) storedDetails -= details(old);
        scannedAt.remove(pos); queue.remove(pos); flagged.remove(pos); alerted.remove(pos);
        if (pos.equals(activePos)) { active = null; activePos = null; }
    }
    private static int details(SusChunkScan.Sample sample) { return sample.objects().size() + sample.lightEdges().size(); }
    private static SusChunkScan.Sample limit(SusChunkScan.Sample s, int capacity) {
        int objects = Math.min(capacity, s.objects().size()), edges = Math.min(capacity - objects, s.lightEdges().size());
        return new SusChunkScan.Sample(s.heat(), s.veto(), s.terrain(), s.complete(), s.activity(),
            List.copyOf(s.objects().subList(0, objects)), List.copyOf(s.lightEdges().subList(0, edges)), s.limited() || objects + edges < details(s));
    }
    private void store(ChunkPos pos, SusChunkScan.Sample sample) {
        var old = samples.remove(pos); if (old != null) storedDetails -= details(old);
        if (storedDetails + details(sample) > MAX_DETAILS) {
            ChunkPos center = Minecraft.getInstance().player.chunkPosition();
            var distant = new ArrayList<>(samples.keySet());
            distant.sort(Comparator.comparingInt((ChunkPos p) -> distance(center, p)).reversed());
            for (ChunkPos other : distant) {
                if (storedDetails + details(sample) <= MAX_DETAILS || distance(center, other) <= distance(center, pos)) break;
                var prior = samples.get(other); storedDetails -= details(prior); samples.put(other, limit(prior, 0));
            }
        }
        sample = limit(sample, Math.max(0, MAX_DETAILS - storedDetails));
        samples.put(pos, sample); storedDetails += details(sample); scannedAt.put(pos, ticks);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) clear(mc.level);
        if (world == null || mc.player == null) return;
        ticks++;
        if (ticks % 20 == 1) for (ChunkPos pos : List.copyOf(samples.keySet())) if (!nearby(pos)) forget(pos);
        if (ticks % 100 == 1) {
            queue.removeIf(pos -> !nearby(pos));
            var loaded = ChunkScanHelper.loadedChunksAround(mc, radius.get());
            loaded.sort(Comparator.comparingInt(chunk -> distance(mc.player.chunkPosition(), chunk.getPos())));
            for (var chunk : loaded) {
                var sample = samples.get(chunk.getPos());
                if (sample == null || !sample.complete() || ticks - scannedAt.getOrDefault(chunk.getPos(), 0) >= 200) enqueue(chunk.getPos());
            }
        }
        long deadline = System.nanoTime() + 3_000_000L;
        int completed = 0;
        while (completed < budget.get() && System.nanoTime() < deadline) {
            if (active == null) {
                if (queue.isEmpty()) break;
                activePos = queue.removeFirst(); if (!nearby(activePos)) continue;
                var clues = EnumSet.noneOf(ActivityClues.Kind.class); clueKinds.forEach((kind, setting) -> { if (setting.get()) clues.add(kind); });
                var categories = EnumSet.noneOf(Category.class); objectKinds.forEach((kind, setting) -> { if (setting.get()) categories.add(kind); });
                if (detectMined.get()) categories.add(Category.GEODES);
                String brand = mc.getConnection() == null ? null : mc.getConnection().serverBrand();
                active = new SusChunkScan(world, world.getChunkSource().getChunkNow(activePos.x(), activePos.z()),
                    brand != null && brand.contains("DonutFolia"), mode.get() == Mode.ANBS_PLUS, clues, categories, mc.player.getEyePosition(), objectRange.get());
            }
            if (!nearby(activePos)) { active = null; activePos = null; continue; }
            if (!active.step(deadline)) break;
            store(activePos, active.result()); active = null; activePos = null; completed++;
        }
        if (ticks % 20 == 1) rebuild(mc);
        if (ticks % 5 == 1) refreshObjects(mc);
        flagged.removeIf(pos -> !nearby(pos));
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get()); reporter.tick(mc, flagged);
        for (var category : Category.values()) BlockEspRenderer.feedBoxes(id() + ":" + category.id(), boxes.getOrDefault(category, List.of()), objectColors.get(category).get() | 0xFF000000, 0);
    }

    private void rebuild(Minecraft mc) {
        flagged.clear(); ChunkPos center = mc.player.chunkPosition();
        if (mode.get() == Mode.ACTIVITY_CLUES) {
            samples.forEach((pos, sample) -> { if (nearby(pos) && (sample.activity().score() >= clueThreshold.get() || naturalContext.get() && !sample.activity().empty())) flagged.add(pos); });
        } else if (world.dimensionType().hasSkyLight()) {
            var scores = new HashMap<ChunkPos, Integer>(); var vetoes = new HashMap<ChunkPos, Integer>();
            samples.forEach((pos, sample) -> { if (sample.complete() && nearby(pos)) { scores.put(pos, sample.heat()); if (sample.veto()) vetoes.put(pos, 1); } });
            var heat = StoneStackScanner.spreadHeat(scores, center, radius.get(), spread.get());
            var veto = StoneStackScanner.spreadHeat(vetoes, center, radius.get(), spread.get());
            heat.keySet().removeIf(pos -> veto.containsKey(pos) || !nearby(pos) || scannedNeighbours(pos) < 3);
            int side = radius.get() * 2 + 1, minX = center.x() - radius.get(), minZ = center.z() - radius.get();
            int[] field = new int[side * side];
            heat.forEach((pos, value) -> field[(pos.x() - minX) * side + pos.z() - minZ] = value);
            int[] maximum = ChunkSignals.neighborhoodMax(field, side, spread.get());
            heat.forEach((pos, value) -> {
                var sample = samples.get(pos); boolean terrain = sample == null || sample.terrain();
                if (SusScore.geodePeakEligible(value, terrain, threshold.get(), maximum[(pos.x() - minX) * side + pos.z() - minZ])) flagged.add(pos);
            });
        }
        alerted.removeIf(pos -> !nearby(pos));
        for (ChunkPos pos : flagged) if (alerted.add(pos) && notify.get())
            FinderNotify.flag("[Sus Finder]", mode.get() + " hypothesis at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), false);
    }
    private int scannedNeighbours(ChunkPos pos) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            var other = new ChunkPos(pos.x() + dx, pos.z() + dz); var sample = samples.get(other);
            if (sample != null && sample.complete() && nearby(other)) count++;
        }
        return count;
    }

    private void refreshObjects(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition(); double rangeSq = (double) objectRange.get() * objectRange.get();
        var candidates = new ArrayList<SuspectObjects.Found>(); var geodes = new HashMap<BlockPos, SuspectObjects.Found>(); var plants = new HashMap<BlockPos, SuspectObjects.Found>();
        detailLimited = false;
        for (var entry : samples.entrySet()) {
            if (!nearby(entry.getKey())) continue;
            var sample = entry.getValue(); detailLimited |= sample.limited();
            for (var found : sample.objects()) {
                if (Vec3.atCenterOf(found.pos()).distanceToSqr(eye) > rangeSq
                    || !objectKinds.get(found.category()).get() && !(found.category() == Category.GEODES && detectMined.get())) continue;
                if (found.category() == Category.GEODES) { geodes.put(found.pos(), found); continue; }
                if (found.category() == Category.GROWTH) {
                    var kind = ActivityClues.kind(found.state());
                    if (kind == ActivityClues.Kind.KELP || kind == ActivityClues.Kind.VINES || kind == ActivityClues.Kind.CAVE_VINES || kind == ActivityClues.Kind.BAMBOO) { plants.put(found.pos(), found); continue; }
                    var clue = new ActivityClues.Summary(); clue.add(found.state());
                    if (!naturalContext.get() && (clue.score() == 0 || sample.activity().score() < clueThreshold.get())) continue;
                }
                candidates.add(found);
            }
        }
        candidates.sort(Comparator.comparingDouble(found -> Vec3.atCenterOf(found.pos()).distanceToSqr(eye)));
        var next = new ArrayList<SuspectObjects.Marker>();
        for (int i = 0; i < Math.min(MAX_MARKERS, candidates.size()); i++) next.add(SuspectObjects.block(candidates.get(i), world));
        detailLimited |= candidates.size() > MAX_MARKERS;
        for (var formation : SuspectObjects.geodes(geodes, matureClusters.get(), maturePercent.get(), naturalContext.get(), detectMined.get() && !detailLimited))
            if (objectKinds.get(formation.category()).get()) next.add(formation);
        next.addAll(SuspectObjects.plants(plants, kind -> switch (kind) { case KELP -> kelpLength.get(); case VINES -> vineLength.get(); case CAVE_VINES -> caveVineLength.get(); case BAMBOO -> bambooLength.get(); default -> Integer.MAX_VALUE; }, naturalContext.get()));
        if (objectKinds.get(Category.DISPLAYS).get()) {
            int examined = 0;
            for (var entity : world.entitiesForRendering()) {
                if (++examined > 512) { detailLimited = true; break; }
                var item = entity instanceof net.minecraft.world.entity.decoration.ArmorStand ? Items.ARMOR_STAND : entity instanceof net.minecraft.world.entity.decoration.ItemFrame ? Items.ITEM_FRAME : null;
                if (item != null && entity.isAlive() && entity.distanceToSqr(eye) <= rangeSq) {
                    var box = entity.getBoundingBox(); var icon = SuspectObjects.Icon.item(item);
                    next.add(new SuspectObjects.Marker(box, List.of(box), Category.DISPLAYS, Map.of(icon, 1), icon.label()));
                }
            }
        }
        if (mode.get() == Mode.ANBS_PLUS && objectKinds.get(Category.ANOMALIES).get()) for (var entry : samples.entrySet()) {
            if (!flagged.contains(entry.getKey())) continue;
            for (BlockPos pos : entry.getValue().lightEdges()) if (Vec3.atCenterOf(pos).distanceToSqr(eye) <= rangeSq) {
                var box = new AABB(pos); next.add(new SuspectObjects.Marker(box, List.of(box), Category.ANOMALIES, Map.of(SuspectObjects.Icon.item(Items.AMETHYST_SHARD), 1), "Observed light edge"));
            }
        }
        next.sort(Comparator.comparingDouble(marker -> marker.bounds().distanceToSqr(eye)));
        if (next.size() > MAX_MARKERS) { detailLimited = true; next.subList(MAX_MARKERS, next.size()).clear(); }
        markers = List.copyOf(next); boxes.clear();
        for (var marker : markers) boxes.computeIfAbsent(marker.category(), ignored -> new ArrayList<>()).addAll(marker.shapes());
    }

    public record Observations(List<SuspectObjects.Marker> markers, Map<Category, Integer> colors, boolean limited) {}

    /** Immutable current-world view; only the opt-in panel requests it, and it never starts a scan. */
    public Observations observations() {
        var mc = Minecraft.getInstance();
        if (!isEnabled() || world == null || world != mc.level || mc.player == null) return null;
        var colors = new EnumMap<Category, Integer>(Category.class);
        objectColors.forEach((category, setting) -> colors.put(category, setting.get()));
        return new Observations(markers, Map.copyOf(colors), detailLimited);
    }
    @Override public String info() { return mode.get() + " / " + flagged.size() + " chunk hypotheses / " + markers.size() + " outlines" + (detailLimited ? " (details capped)" : "") + reporter.coverageSuffix(); }
}
