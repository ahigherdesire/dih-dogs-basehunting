package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.activity.ActivityFlags;
import com.autism.seedcracker.finder.storage.StashScoring;
import net.minecraft.core.BlockPos;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import dihclient.util.DihClientMessaging;
import com.autism.seedcracker.compat.ClientNotify;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Stash Finder.
 *
 * Scans the chunks around the player and counts storage-type blocks (chests, barrels, shulker
 * boxes, hoppers, ender chests, trapped chests). A chunk whose storage-block count reaches the
 * configured threshold is flagged as a storage lead and announced with a toast + chat ping.
 * Flagged chunks are rendered as a box by the shared {@link ChunkFlagRenderer}.
 *
 * This is a clean-room port of the obfuscated Zelith "StashFinder" module: same detection idea
 * (per-chunk storage-block density), rewritten against the AUTISM module API.
 */
public final class StashFinderModule extends Module {

    // ---- settings ----
    public enum Mode { THRESHOLD, SCORING, DONUT }
    private final dihclient.api.module.EnumSetting<Mode> mode = add(new dihclient.api.module.EnumSetting<>(
            "mode", "Mode", Mode.DONUT, Mode.values())
        .description("THRESHOLD = storage count. SCORING = contextual clusters. DONUT = deep storage/spawner leads; lone natural spawners are excluded unless explicitly requested.")
        .group("General"));
    private final dihclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new dihclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.HIGH, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("THRESHOLD: HIGH = half the storage count flags, LOW = double. SCORING: shifts the real/ambiguous score gates.")
        .group("General"));
    private final BoolSetting naturalSpawner = add(new BoolSetting("natural-spawner-hints", "Include lone natural spawners", false)
        .description("DONUT/HIGH only: show dungeon spawners without a storage cluster. Off avoids treating a natural dungeon as a stash.")
        .group("General").visibleWhen(() -> mode.get() == Mode.DONUT
            && sensitivity.get() == com.autism.seedcracker.finder.FinderSensitivity.HIGH));
    private final IntSetting threshold = add(new IntSetting(
            "threshold", "Threshold", 10, 1, 100, 1)
        .description("Storage blocks in a chunk needed to flag it as a stash.")
        .group("General")
        .visibleWhen(() -> mode.get() == Mode.THRESHOLD));
    private final IntSetting realThreshold = add(new IntSetting(
            "real-threshold", "Real threshold", 40, -100, 200, 1)
        .description("SCORING: cluster score needed for the strong-candidate band, not proof of a player base.")
        .group("Scoring")
        .visibleWhen(() -> mode.get() == Mode.SCORING));
    private final IntSetting minConfidence = add(new IntSetting(
            "min-confidence", "Min base confidence", 20, 0, 100, 5)
        .description("THRESHOLD: base-confidence score (0-100) needed to flag a low-count chunk (filters lone chests in natural structures).")
        .group("General")
        .visibleWhen(() -> mode.get() == Mode.THRESHOLD));
    private final IntSetting ambiguousThreshold = add(new IntSetting(
            "ambiguous-threshold", "Ambiguous threshold", -10, -100, 100, 1)
        .description("SCORING: cluster score >= this (but < real) is AMBIGUOUS.")
        .group("Scoring")
        .visibleWhen(() -> mode.get() == Mode.SCORING));
    private final BoolSetting realOnly = add(new BoolSetting(
            "real-only", "Flag real only", false)
        .description("SCORING: only flag fully sampled strong candidates (hide ambiguous or partly unloaded surroundings).")
        .group("Scoring")
        .visibleWhen(() -> mode.get() == Mode.SCORING));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 6, 1, 12, 1)
        .description("Chunk bubble around the player scanned for stashes.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0xFFFFB020)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a stash chunk is found.")
        .group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 2, 1, 32, 1)
        .description("All modes: chunks sampled per tick. SCORING then spends up to 4096 analysis steps per budgeted chunk each tick.")
        .group("Performance"));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    private final ActivityFlags flags = new ActivityFlags();
    private final Map<ChunkPos, LevelChunk> sampled = new HashMap<>();
    private final Map<ChunkPos, List<BlockPos>> storage = new LinkedHashMap<>();
    private final Map<ChunkPos, LevelChunk> analysisChunks = new HashMap<>();
    private final Map<ChunkPos, Map<ChunkPos, LevelChunk>> evidence = new HashMap<>();
    private final com.autism.seedcracker.finder.FinderReport reporter = new com.autism.seedcracker.finder.FinderReport("Stash", 0);
    private Object world;
    private StashScoring scoring;
    private final StashScoring.CollectionSchedule scoringSchedule = new StashScoring.CollectionSchedule();

    public StashFinderModule() {
        super(SeedcrackerAddon.ID + ":z-stash-finder", "Stash Finder",
            "Finds storage/spawner leads and contextual stash candidates; natural structures can also match.");
    }

    private void clearState() {
        flags.clear();
        sampled.clear();
        storage.clear();
        analysisChunks.clear();
        evidence.clear();
        scoring = null;
        scoringSchedule.reset();
        world = null;
        scanCursor.reset();
        reporter.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-stash-finder");
    }

    @Override public void onEnable() { clearState(); }
    @Override public void onDisable() { clearState(); }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (Set.of("mode", "sensitivity", "scan-radius", "threshold", "min-confidence",
                "real-threshold", "ambiguous-threshold", "real-only", "natural-spawner-hints").contains(settingId)) clearState();
    }

    @Override
    public void onGameLeft() {
        clearState();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level || mc.level == null || mc.player == null) {
            clearState();
            world = mc.level;
        }
        if (mc.level == null || mc.player == null) return;
        ChunkPos centre = mc.player.chunkPosition();
        sampled.entrySet().removeIf(entry -> {
            ChunkPos pos = entry.getKey();
            if (!tooFar(pos, centre, scanRadius.get())
                    && mc.level.getChunkSource().getChunk(pos.x(), pos.z(), false) == entry.getValue()) return false;
            storage.remove(pos);
            flags.record(pos, false);
            return true;
        });
        evidence.entrySet().removeIf(entry -> {
            if (!tooFar(entry.getKey(), centre, scanRadius.get()) && currentChunks(mc, entry.getValue())) return false;
            flags.record(entry.getKey(), false);
            return true;
        });
        if (scoring != null && !currentChunks(mc, analysisChunks)) {
            scoring = null;
            analysisChunks.clear();
        }
        flags.retain(pos -> !tooFar(pos, centre, scanRadius.get())
            && mc.level.getChunkSource().getChunk(pos.x(), pos.z(), false) != null);
        scan(mc);
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-stash-finder", flags.current(), color.get(), tracer.get());
        reporter.tick(mc, flags.current());
    }

    private void scan(Minecraft mc) {
        if (mode.get() == Mode.SCORING && scoring != null) {
            scoring.advance(chunksPerTick.get() * 4096);
            if (scoring.complete()) {
                publishScores(mc, scoring.results());
                scoring = null;
                analysisChunks.clear();
            }
            return;
        }
        List<LevelChunk> batch = scanCursor.nextBatch(mc, scanRadius.get(), 400, chunksPerTick.get());
        for (LevelChunk chunk : batch) {
            ChunkPos pos = chunk.getPos();
            sampled.put(pos, chunk);
            if (mode.get() == Mode.SCORING) {
                List<BlockPos> positions = new ArrayList<>();
                ChunkScanHelper.forEachBlockInChunk(chunk, StashScoring::isStorage, p -> positions.add(p.immutable()));
                storage.put(pos, positions);
            } else if (mode.get() == Mode.DONUT) {
                scanDonut(chunk);
            } else {
                int count = ChunkScanHelper.countBlocksInChunk(chunk, StashScoring::isStorage);
                int need = sensitivity.get().scale(threshold.get());
                boolean matches = count >= need;
                if (matches && count < need * 2) {
                    matches = com.autism.seedcracker.finder.BaseConfidence.score(chunk).score() >= minConfidence.get();
                }
                flags.record(pos, matches);
                if (flags.announce(pos, 1)) onNewFlag(pos, count + " storage");
            }
        }
        if (mode.get() == Mode.SCORING && scoringSchedule.afterBatch(!batch.isEmpty(), scanCursor.progress())) {
            List<BlockPos> positions = new ArrayList<>();
            storage.forEach((pos, blocks) -> {
                positions.addAll(blocks);
                if (!blocks.isEmpty()) analysisChunks.put(pos, sampled.get(pos));
            });
            scoring = new StashScoring(positions, p -> {
                ChunkPos pos = new ChunkPos(p.getX() >> 4, p.getZ() >> 4);
                LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.x(), pos.z(), false);
                if (chunk == null) return null;
                analysisChunks.putIfAbsent(pos, chunk);
                return chunk.getBlockState(p);
            }, mc.level.getMinY(), mc.level.getMaxY());
        }
    }

    private void scanDonut(LevelChunk chunk) {
        int storageDeep = 0, spawners = 0;
        for (var be : chunk.getBlockEntities().values()) {
            if (be == null) continue;
            if (be instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity) {
                spawners++;
                continue;
            }
            if (be.getBlockPos().getY() > 0) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).getPath();
            if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")
                    || id.equals("shulker_box") || id.equals("hopper")) storageDeep++;
        }
        int bulk = sensitivity.get().scale(20);
        boolean matches = StashScoring.donutCandidate(storageDeep, spawners, bulk,
            naturalSpawner.get() && sensitivity.get() == com.autism.seedcracker.finder.FinderSensitivity.HIGH);
        ChunkPos pos = chunk.getPos();
        flags.record(pos, matches);
        if (flags.announce(pos, 1)) onNewFlag(pos, storageDeep + " storage at Y<=0, " + spawners + " spawners");
    }

    private void publishScores(Minecraft mc, List<StashScoring.Result> results) {
        Set<ChunkPos> next = new HashSet<>();
        Map<ChunkPos, StashScoring.Result> leads = new LinkedHashMap<>();
        int shift = switch (sensitivity.get()) { case HIGH -> -15; case MEDIUM -> 0; case LOW -> 15; };
        for (StashScoring.Result result : results) {
            ChunkPos pos = new ChunkPos(result.centre().getX() >> 4, result.centre().getZ() >> 4);
            if (tooFar(pos, mc.player.chunkPosition(), scanRadius.get())
                    || mc.level.getChunkSource().getChunk(pos.x(), pos.z(), false) == null) continue;
            boolean strong = result.completeEnvironment() && result.score() >= realThreshold.get() + shift;
            if (strong || (!realOnly.get() && result.score() >= ambiguousThreshold.get() + shift)) {
                next.add(pos);
                leads.merge(pos, result, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        flags.replace(next);
        evidence.clear();
        leads.forEach((pos, result) -> {
            Map<ChunkPos, LevelChunk> inputs = new HashMap<>();
            for (ChunkPos input : result.chunks()) {
                LevelChunk chunk = analysisChunks.get(input);
                if (chunk != null) inputs.put(input, chunk);
            }
            evidence.put(pos, inputs);
            if (flags.announce(pos, 1)) onNewScoreFlag(result,
                result.completeEnvironment() && result.score() >= realThreshold.get() + shift);
        });
    }

    private void onNewScoreFlag(StashScoring.Result result, boolean strong) {
        BlockPos centre = result.centre();
        String band = strong ? "Strong stash candidate" : "Ambiguous storage cluster";
        String detail = result.completeEnvironment() ? "" : ", surroundings partly unloaded";
        announce(centre.getX(), centre.getZ(), band + " (score " + result.score() + ", " + result.storage()
            + " storage" + detail + ") at X:" + centre.getX() + " Y:" + centre.getY() + " Z:" + centre.getZ());
    }

    private static boolean currentChunks(Minecraft mc, Map<ChunkPos, LevelChunk> chunks) {
        return chunks.entrySet().stream().allMatch(entry ->
            mc.level.getChunkSource().getChunk(entry.getKey().x(), entry.getKey().z(), false) == entry.getValue());
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs((long) a.x() - b.x()) > radius || Math.abs((long) a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, String evidence) {
        announce(pos.getMiddleBlockX(), pos.getMiddleBlockZ(), "Storage lead (" + evidence
            + ") at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ());
    }

    private void announce(int x, int z, String message) {
        recordHeat(x, z);
        if (!notify.get()) return;
        ClientNotify.warning(message);
        DihClientMessaging.sendPrefixed("§6[StashFinder] §f" + message);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    /** Record a find into the heatmap (auto) or queue it for manual confirm, per the setting. */
    private void recordHeat(int blockX, int blockZ) {
        int cb = com.autism.seedcracker.modules.RegionMapModule.cellBlocks();
        if (com.autism.seedcracker.modules.RegionMapModule.isAutoHeatmap()) {
            com.autism.seedcracker.finder.BaseHeatTracker.recordFind(blockX, blockZ, cb);
        } else {
            com.autism.seedcracker.finder.BaseHeatTracker.queueFind(blockX, blockZ, cb);
            if (notify.get()) {
                DihClientMessaging.sendPrefixed("§7[Heatmap] Queued ("
                    + com.autism.seedcracker.finder.BaseHeatTracker.pendingCount() + " pending). /heatconfirm to add.");
            }
        }
    }

    @Override
    public String info() {
        return flags.current().size() + " leads / " + (scoring == null
            ? (int) (100 * scanCursor.progress()) + "% scanned" : "analysing clusters");
    }
}
