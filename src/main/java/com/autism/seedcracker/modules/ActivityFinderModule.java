package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.activity.ActivityFlags;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Activity Finder.
 *
 * A y-level-gated activity detector. The Zelith original listened to network packets, pulled block
 * positions out of them via reflection and flagged chunks below a configurable Y level. This
 * clean-room port keeps the essence - "flag chunks showing block-entity activity at or below a Y
 * cut-off" - but reads it from loaded chunk data instead of packet reflection: any chunk with one
 * or more matching block entities at or below the configured Y level is flagged. This measures
 * presence, not recent activity; natural structures can contain the same block entities.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 */
public final class ActivityFinderModule extends Module {

    // ---- settings ----
    /** Which block entities count as activity. */
    public enum Mode {
        /** Any block entity (original). */
        ALL,
        /** Storage only: chests/barrels/shulkers/hoppers (loot rooms). */
        STORAGE,
        /** Work stations: furnaces/brewing/enchanting/anvil area blocks (active base). */
        UTILITY,
        /** Redstone machinery: hoppers/dispensers/droppers/comparators (farms & vaults). */
        REDSTONE
    }

    private final dihclient.api.module.EnumSetting<Mode> mode = add(
        new dihclient.api.module.EnumSetting<>("mode", "Mode", Mode.STORAGE, Mode.values())
        .description("ALL = any block entity. STORAGE = chests/shulkers (loot). UTILITY = furnaces/brewing (lived-in). REDSTONE = hoppers/dispensers (farms/vaults).")
        .group("General"));
    private final dihclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new dihclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("HIGH/MEDIUM = one block entity below Y flags. LOW = need 2+ (a single lone chest can be a dungeon).")
        .group("General"));
    private final IntSetting yLevel = add(new IntSetting(
            "y-level", "Y level", 32, -64, 320, 1)
        .description("Only flag chunks whose block-entity activity is at or below this Y level.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for activity.")
        .group("General"));
    private final BoolSetting requireBuild = add(new BoolSetting("require-build-evidence", "Require building evidence", true)
        .description("Ignore lone dungeon/village block entities unless independent storage or automation evidence is visible. Turn off for broad presence scanning, not proof of activity.").group("General"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", false)
        .description("Toast + chat ping when activity is found.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0xB4FFDC00)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 2, 1, 32, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.")
        .group("Performance"));

    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    private final ActivityFlags flags = new ActivityFlags();
    private final Map<ChunkPos, LevelChunk> sampled = new HashMap<>();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Activity", 0);
    private Object world;

    public ActivityFinderModule() {
        super(SeedcrackerAddon.ID + ":z-activity-finder", "Activity Finder",
            "Flags matching block entities below a Y level; presence is a lead, not live activity or proof of a base.");
    }

    private void clearState() {
        flags.clear();
        sampled.clear();
        world = null;
        scanCursor.reset();
        reporter.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-activity-finder");
    }

    @Override public void onEnable() { clearState(); }
    @Override public void onDisable() { clearState(); }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (Set.of("mode", "sensitivity", "y-level", "scan-radius", "require-build-evidence").contains(settingId)) clearState();
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
        sampled.entrySet().removeIf(entry -> tooFar(entry.getKey(), centre, scanRadius.get())
            || mc.level.getChunkSource().getChunk(entry.getKey().x(), entry.getKey().z(), false) != entry.getValue());
        flags.retain(sampled::containsKey);
        scan(mc);
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-activity-finder", flags.current(), color.get(), tracer.get());
        reporter.tick(mc, flags.current());
    }

    private void scan(Minecraft mc) {
        int radius = scanRadius.get();
        int yGate = yLevel.get();

        int need = sensitivity.get().scale(1); // HIGH/MEDIUM 1, LOW 2
        Mode m = mode.get();
        for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 400, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            sampled.put(pos, chunk);
            int count = countActivityAtOrBelow(chunk, yGate, Integer.MAX_VALUE, m);
            flags.record(pos, count >= need && (!requireBuild.get()
                || com.autism.seedcracker.finder.BaseConfidence.hasBuildEvidence(chunk)));
            if (flags.announce(pos, 1)) onNewFlag(pos, count);
        }
    }

    /** Count mode-matching block entities at or below {@code yGate}, stopping early at {@code enough}. */
    private static int countActivityAtOrBelow(LevelChunk chunk, int yGate, int enough, Mode mode) {
        int n = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be == null) continue;
            BlockPos p = be.getBlockPos();
            if (p == null || p.getY() > yGate) continue;
            if (!matchesMode(be, mode)) continue;
            if (++n >= enough) return n;
        }
        return n;
    }

    private static boolean matchesMode(BlockEntity be, Mode mode) {
        if (mode == Mode.ALL) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
            .getKey(be.getType()).getPath();
        return switch (mode) {
            case STORAGE -> id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")
                || id.equals("shulker_box") || id.equals("ender_chest") || id.equals("hopper");
            case UTILITY -> id.equals("furnace") || id.equals("blast_furnace") || id.equals("smoker")
                || id.equals("brewing_stand") || id.equals("enchanting_table") || id.equals("beacon")
                || id.equals("campfire") || id.equals("lectern") || id.equals("crafter");
            case REDSTONE -> id.equals("hopper") || id.equals("dispenser") || id.equals("dropper")
                || id.equals("comparator") || id.equals("piston") || id.equals("daylight_detector")
                || id.equals("sculk_sensor") || id.equals("calibrated_sculk_sensor");
            default -> true;
        };
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, int count) {
        if (!notify.get()) return;
        com.autism.seedcracker.finder.FinderNotify.flag("§e[ActivityFinder]",
            count + " " + mode.get() + " block entities in chunk " + pos.x() + ", " + pos.z()
                + " (Y<=" + yLevel.get() + "; presence, not live activity)", true);
    }

    @Override
    public String info() {
        return flags.current().size() + " leads / " + (int) (100 * scanCursor.progress()) + "% scanned";
    }
}
