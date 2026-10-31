package com.autism.seedcracker.modules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.landmark.TunnelScan;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Nether Tunnel Finder.
 *
 * Detects player-dug nether highways: long, straight, walkable air corridors at nether-travel
 * height, often paved with obsidian / cobblestone / slabs. A corridor of mostly-air with a flat
 * floor needs enclosing walls/roof or constructed paving, not just a line in an open cavern.
 * These are travel-route candidates, not proof of a base; generated corridors can also match.
 *
 * Flagged chunks are rendered by the shared {@link ChunkFlagRenderer}. Best used in the Nether.
 */
public final class NetherTunnelFinderModule extends Module {

    private final dihclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new dihclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("HIGH = half min-length (more candidates). LOW = double min-length (fewer candidates, not guaranteed player builds).")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 6, 1, 12, 1)
        .description("Chunk bubble around you scanned for tunnels.").group("General"));
    private final IntSetting minRunLength = add(new IntSetting("min-run", "Min corridor length", 12, 4, 64, 1)
        .description("Consecutive walkable-air blocks in a straight line needed to count as a tunnel.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notifications", true)
        .description("Toast + chat ping when a tunnel is found.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0xFFFF8040)
        .description("Colour of the flagged chunk marker.").group("Render"));
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal Y range, scan rate and tracer.").group("General"));
    private final IntSetting minY = add(new IntSetting("min-y", "Min Y", 8, 0, 128, 1)
        .description("Lowest Y level to scan (nether tunnels are usually near the bedrock ceiling or mid-level).")
        .group("Advanced").visibleWhen(showAdvanced::get));
    private final IntSetting maxY = add(new IntSetting("max-y", "Max Y", 118, 8, 127, 1)
        .description("Highest Y level to scan.").group("Advanced").visibleWhen(showAdvanced::get));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false)
        .description("Draw a tracer line to each flagged chunk.").group("Render").visibleWhen(showAdvanced::get));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 1, 1, 32, 1)
        .description("Chunk/work budget per tick. Large scans resume next tick; work is capped to keep rendering responsive.").group("Performance").visibleWhen(showAdvanced::get));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    private final Map<ChunkPos, TunnelScan.Match> matches = new LinkedHashMap<>();
    private final Set<ChunkPos> flagged = matches.keySet();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Tunnel", 50);
    private Object world;
    private record ScanSettings(int radius, int length, int minY, int maxY) {}
    private ScanSettings settings;
    private LevelChunk pending;
    private TunnelScan search;

    public NetherTunnelFinderModule() {
        super(SeedcrackerAddon.ID + ":nether-tunnel-finder", "Nether Tunnel Finder",
            "Finds enclosed corridors or paved Nether route candidates, not guaranteed bases.");
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.init();
        clear();
    }

    @Override public void onDisable() { clear(); }

    private void clear() {
        flagged.clear();
        scanCursor.reset();
        reporter.clear();
        pending = null;
        search = null;
        world = null;
        settings = null;
        ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() {
        clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.level.dimension() != Level.NETHER) { clear(); return; }
        var current = new ScanSettings(scanRadius.get(), sensitivity.get().scale(minRunLength.get()),
            Math.max(mc.level.getMinY() + 1, Math.min(minY.get(), maxY.get())),
            Math.min(mc.level.getMaxY() - 2, Math.max(minY.get(), maxY.get())));
        if (world != mc.level || !current.equals(settings)) {
            clear();
            world = mc.level;
            settings = current;
        }
        ChunkPos center = mc.player.chunkPosition();
        flagged.removeIf(pos -> tooFar(pos, center, current.radius())
            || mc.level.getChunkSource().getChunk(pos.x(), pos.z(), false) == null);
        if (pending != null && (tooFar(pending.getPos(), center, current.radius())
            || mc.level.getChunkSource().getChunk(pending.getPos().x(), pending.getPos().z(), false) != pending)) {
            pending = null;
            search = null;
        }
        // Rotate one live match per tick so disappearance does not wait for a full volume sweep.
        if (!matches.isEmpty()) {
            var entry = matches.entrySet().iterator().next();
            ChunkPos pos = entry.getKey();
            var match = entry.getValue();
            matches.remove(pos);
            if (TunnelScan.valid(match, (x, y, z) -> tunnelCell(mc, x, y, z))) matches.put(pos, match);
        }
        int budget = Math.min(32768, 8192 * chunksPerTick.get());
        for (int completed = 0; completed < chunksPerTick.get() && budget > 0;) {
            if (pending == null) {
                var batch = scanCursor.nextBatch(mc, current.radius(), 1000, 1);
                if (batch.isEmpty()) break;
                pending = batch.getFirst();
                search = new TunnelScan(pending.getPos().getMinBlockX(), pending.getPos().getMinBlockZ(),
                    current.minY(), current.maxY(), current.length(), (x, y, z) -> tunnelCell(mc, x, y, z));
            }
            budget -= search.step(budget);
            if (!search.done()) break;
            ChunkPos pos = pending.getPos();
            if (search.found()) {
                if (matches.put(pos, search.match()) == null) onNewFlag(pos);
            } else flagged.remove(pos);
            pending = null;
            search = null;
            completed++;
        }
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private static int tunnelCell(Minecraft mc, int x, int y, int z) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk == null) return TunnelScan.UNKNOWN;
        int section = (y - chunk.getMinY()) >> 4;
        if (section < 0 || section >= chunk.getSections().length || chunk.getSections()[section] == null) return TunnelScan.UNKNOWN;
        var state = chunk.getSections()[section].getBlockState(x & 15, y & 15, z & 15);
        if (state.isAir()) return TunnelScan.AIR;
        if (!state.getFluidState().isEmpty()) return TunnelScan.UNKNOWN;
        boolean slab = state.getBlock() instanceof SlabBlock;
        if (!slab && !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return TunnelScan.UNKNOWN;
        boolean paving = slab || state.is(Blocks.OBSIDIAN) || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.COBBLED_DEEPSLATE);
        return TunnelScan.SOLID | (paving ? TunnelScan.PAVED : 0);
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        com.autism.seedcracker.finder.FinderNotify.flag("§6[NetherTunnel]",
            "Tunnel / paved route candidate at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), true);
    }

    @Override
    public String info() {
        return world == null ? "Nether only" : flagged.size() + " candidates";
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs((long) a.x() - b.x()) > radius || Math.abs((long) a.z() - b.z()) > radius;
    }
}
