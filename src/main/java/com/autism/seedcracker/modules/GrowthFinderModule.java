package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.growth.GrowthScan;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Weighted growth clues with resumable scans; naturally generated growth is not proof of a base. */
public final class GrowthFinderModule extends Module {

    private static final long SCAN_NANOS = 3_000_000L;
    private static final int MAX_CELLS_PER_TICK = 65_536;

    // ---- settings ----
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 32, 1)
        .description("Chunk bubble around the player scanned for growth.")
        .group("General"));
    private final dihclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new dihclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("HIGH = half the weighted threshold (more clues and noise). LOW = double (fewer clues, not proof).")
        .group("General"));
    private final IntSetting threshold = add(new IntSetting(
            "threshold", "Threshold", 8, 1, 1000, 1)
        .description("Weighted score before sensitivity: vines at least 6 long = length x 0.5; dripstone = 1.875 each; berries = 1 each. Natural growth is not proof of a base.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "alpha", "Marker colour", 0x5040FF40)
        .description("Colour (with alpha) of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a growth chunk is found.")
        .group("General"));
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal per-type toggles, scan rate and source estimation.").group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 1, 1, 128, 1)
        .description("Maximum completed chunks per tick, within a 3 ms / 65,536-block scan budget. Dense chunks resume next tick; higher values speed up sparse sweeps.")
        .group("Performance").visibleWhen(showAdvanced::get));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    // ---- per-type toggles ----
    private final BoolSetting vines = add(new BoolSetting("render-vines", "Vines", true)
        .description("Score regular, cave, weeping and twisting vine runs at least 6 long: length x 0.5.").group("Types").visibleWhen(showAdvanced::get));
    private final BoolSetting berries = add(new BoolSetting("render-berries", "Berries", true)
        .description("Add 1 weighted point per sweet-berry bush.").group("Types").visibleWhen(showAdvanced::get));
    private final BoolSetting dripstone = add(new BoolSetting("render-dripstone", "Dripstone", true)
        .description("Add 1.875 weighted points per pointed dripstone or dripstone block.").group("Types").visibleWhen(showAdvanced::get));
    private final BoolSetting sourceTracking = add(new BoolSetting("source-tracking", "Source estimation", true)
        .description("Show the weighted growth centroid, not a confirmed farm or player position.")
        .group("General").visibleWhen(showAdvanced::get));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Growth", 45);
    private final Set<ChunkPos> notified = new HashSet<>();
    private ClientLevel world;
    private LevelChunk activeChunk;
    private GrowthScan active;

    public GrowthFinderModule() {
        super(SeedcrackerAddon.ID + ":z-growth-finder", "Growth Finder",
            "Weighted vine, berry and dripstone growth clues. Natural growth is not proof of player activity or a base.");
    }

    private void clear(ClientLevel level) {
        flagged.clear();
        notified.clear();
        reporter.clear();
        scanCursor.reset();
        active = null;
        activeChunk = null;
        world = level;
        ChunkFlagRenderer.clear(id());
    }

    @Override public void onEnable() { clear(Minecraft.getInstance().level); }
    @Override public void onDisable() { clear(null); }
    @Override public void onGameLeft() {
        clear(null);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onSettingsReset() { clear(isEnabled() ? Minecraft.getInstance().level : null); }
    @Override protected void onOptionValueChanged(String id) {
        if ("scan-radius".equals(id) || "threshold".equals(id) || "sensitivity".equals(id)
            || "render-vines".equals(id) || "render-berries".equals(id) || "render-dripstone".equals(id))
            clear(isEnabled() ? Minecraft.getInstance().level : null);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) clear(mc.level);
        if (world == null || mc.player == null) return;

        scan(mc);
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private void scan(Minecraft mc) {
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();
        long deadline = System.nanoTime() + SCAN_NANOS;
        int completed = 0, cells = 0;
        while (completed < chunksPerTick.get() && cells < MAX_CELLS_PER_TICK && System.nanoTime() < deadline) {
            if (active == null) {
                var next = scanCursor.nextBatch(mc, radius, 400, 1);
                if (next.isEmpty()) break;
                activeChunk = next.getFirst();
                active = new GrowthScan(activeChunk.getSectionsCount());
            }
            ChunkPos pos = activeChunk.getPos();
            if (tooFar(pos, playerChunk, radius) || world.getChunkSource().getChunkNow(pos.x(), pos.z()) != activeChunk) {
                active = null;
                activeChunk = null;
                continue;
            }
            if (!active.complete()) {
                var section = activeChunk.getSection(active.section());
                if (active.atSectionStart() && (section == null || section.hasOnlyAir())) {
                    cells += active.skipEmptySection();
                } else {
                    cells += active.step(cell -> section == null ? GrowthScan.NONE : growthType(
                        section.getBlockState((cell >>> 4) & 15, cell >>> 8, cell & 15)),
                        MAX_CELLS_PER_TICK - cells, deadline);
                }
            }
            if (!active.complete()) continue;
            GrowthScan.Result result = active.result();
            if (result.score() >= sensitivity.get().scale(threshold.get())) {
                flagged.add(pos);
                if (notified.add(pos)) onNewFlag(mc, pos, result);
            } else flagged.remove(pos);
            active = null;
            activeChunk = null;
            completed++;
        }
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r) || world.getChunkSource().getChunkNow(p.x(), p.z()) == null);
        notified.removeIf(p -> tooFar(p, playerChunk, r) || world.getChunkSource().getChunkNow(p.x(), p.z()) == null);
    }

    private int growthType(BlockState state) {
        if (vines.get() && isVine(state)) return GrowthScan.VINE;
        if (dripstone.get() && (state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.DRIPSTONE_BLOCK))) return GrowthScan.DRIPSTONE;
        if (berries.get() && state.is(Blocks.SWEET_BERRY_BUSH)) return GrowthScan.BERRY;
        return GrowthScan.NONE;
    }

    private boolean isVine(BlockState state) {
        return state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)
            || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT);
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(Minecraft mc, ChunkPos pos, GrowthScan.Result result) {
        if (!notify.get()) return;
        BlockPos source = sourceTracking.get()
            ? new BlockPos((int) Math.round(pos.getMinBlockX() + result.sourceX()), (int) mc.player.getY(),
                (int) Math.round(pos.getMinBlockZ() + result.sourceZ()))
            : new BlockPos(pos.getMinBlockX() + 8, (int) mc.player.getY(), pos.getMinBlockZ() + 8);
        com.autism.seedcracker.finder.FinderNotify.flag("§2[GrowthFinder]",
            "Growth clue (weighted score " + result.score() + (result.maxVine() > 0 ? ", max vine " + result.maxVine() : "")
                + ") near X:" + source.getX() + " Z:" + source.getZ(), true);
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "" : flagged.size() + " flagged";
    }
}
