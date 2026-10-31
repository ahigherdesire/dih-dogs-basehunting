package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.ScanCursor;
import com.autism.seedcracker.finder.landmark.PortalGeometry;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Portal Finder.
 *
 * Flags chunks containing lit nether portals. Away from spawn, a portal is almost always player
 * infrastructure: a base entrance, a highway exit, or a farm's mob portal. In the Nether the
 * same scan finds the overworld-side bases' nether hubs. Scans incrementally (budgeted chunks
 * per tick) with section-level fast-skip so it costs nothing on empty terrain.
 */
public final class PortalFinderModule extends Module {

    private final IntSetting scanRadius = add(new IntSetting("radius", "Scan radius (chunks)", 8, 2, 16, 1)
        .description("Chunk radius scanned around you.").group("General"));
    private final IntSetting minDistance = add(new IntSetting("min-distance", "Min distance (blocks)", 500, 0, 10000, 100)
        .description("Ignore portals closer than this to 0,0 (spawn-area portals are noise).").group("General"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Toast + chat when a portal chunk is found.").group("General"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Tracer to flagged chunks.").group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFF9B30FF)
        .description("Flag colour.").group("Render"));

    private final Map<ChunkPos, BlockPos> portals = new LinkedHashMap<>();
    private final Set<ChunkPos> flagged = portals.keySet();
    private final ScanCursor scan = new ScanCursor();
    private final Set<BlockPos> examined = new HashSet<>();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Portal", 45);
    private Object world;
    private int radiusSetting, distanceSetting;
    private LevelChunk pending;
    private BlockPos portalFound;
    private int sectionIndex, blockIndex;

    public PortalFinderModule() {
        super(SeedcrackerAddon.ID + ":portal-finder", "Portal Finder",
            "Flags complete active portal frames (infrastructure, not proof of a base).");
    }

    /** Grouped into the Finders hub (it's a simple on/off tool); hidden from the main menu. */
    @Override
    public boolean showInModuleMenu() {
        return false;
    }

    @Override public void onEnable() { clear(); }
    @Override public void onDisable() { clear(); }

    private void clear() {
        ChunkFlagRenderer.clear(id());
        flagged.clear();
        scan.reset();
        reporter.clear();
        pending = null;
        portalFound = null;
        examined.clear();
        world = null;
    }

    @Override
    public void onGameLeft() {
        clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { clear(); return; }
        int radius = scanRadius.get();
        if (world != mc.level || radiusSetting != radius || distanceSetting != minDistance.get()) {
            clear();
            world = mc.level;
            radiusSetting = radius;
            distanceSetting = minDistance.get();
        }
        ChunkPos centre = mc.player.chunkPosition();
        flagged.removeIf(pos -> !inRange(pos, centre, radius)
            || mc.level.getChunkSource().getChunk(pos.x(), pos.z(), false) == null);
        if (pending != null && (!inRange(pending.getPos(), centre, radius)
            || mc.level.getChunkSource().getChunk(pending.getPos().x(), pending.getPos().z(), false) != pending)) {
            pending = null;
            portalFound = null;
            examined.clear();
        }

        // One live frame per tick, rotating fairly even while the main scan is still busy.
        if (!portals.isEmpty()) {
            var entry = portals.entrySet().iterator().next();
            ChunkPos pos = entry.getKey();
            BlockPos anchor = entry.getValue();
            portals.remove(pos);
            if (PortalGeometry.complete((x, y, z) -> portalCell(mc, x, y, z), anchor.getX(), anchor.getY(), anchor.getZ(),
                (x, y, z) -> {})) portals.put(pos, anchor);
        }
        int[] budget = {4096, 8}; // block reads and complete-frame probes, across all chunks this tick
        for (int completed = 0; completed < 2 && budget[0] > 0 && budget[1] > 0;) {
            if (pending == null) {
                var batch = scan.nextBatch(mc, radius, 1000, 1);
                if (batch.isEmpty()) break;
                pending = batch.getFirst();
                sectionIndex = blockIndex = 0;
                examined.clear();
            }
            Boolean found = scanPending(mc, budget);
            if (found == null) break;
            ChunkPos pos = pending.getPos();
            if (found) {
                if (portals.put(pos, portalFound) == null && notify.get()) FinderNotify.flag("§5[PortalFinder]",
                    "Active portal at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), true);
            } else flagged.remove(pos);
            pending = null;
            completed++;
        }
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private Boolean scanPending(Minecraft mc, int[] budget) {
        LevelChunkSection[] sections = pending.getSections();
        while (sectionIndex < sections.length) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.NETHER_PORTAL))) {
                sectionIndex++;
                blockIndex = 0;
                continue;
            }
            while (blockIndex < 4096) {
                if (budget[0] <= 0 || budget[1] <= 0) return null;
                int index = blockIndex++;
                budget[0]--;
                int x = index >> 4 & 15, y = index >> 8, z = index & 15;
                if (!section.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL)) continue;
                BlockPos pos = new BlockPos(pending.getPos().getMinBlockX() + x,
                    pending.getMinY() + sectionIndex * 16 + y, pending.getPos().getMinBlockZ() + z);
                if (!examined.add(pos)) continue;
                int scale = mc.level.dimension() == net.minecraft.world.level.Level.NETHER ? 8 : 1;
                if (Math.max(Math.abs((long) pos.getX() * scale), Math.abs((long) pos.getZ() * scale)) < minDistance.get()) continue;
                budget[1]--;
                if (PortalGeometry.complete((px, py, pz) -> portalCell(mc, px, py, pz), pos.getX(), pos.getY(), pos.getZ(),
                    (px, py, pz) -> examined.add(new BlockPos(px, py, pz)))) {
                    portalFound = pos;
                    return true;
                }
            }
            sectionIndex++;
            blockIndex = 0;
        }
        return false;
    }

    private static int portalCell(Minecraft mc, int x, int y, int z) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk == null) return PortalGeometry.OTHER;
        int section = (y - chunk.getMinY()) >> 4;
        if (section < 0 || section >= chunk.getSections().length || chunk.getSections()[section] == null) return PortalGeometry.OTHER;
        var state = chunk.getSections()[section].getBlockState(x & 15, y & 15, z & 15);
        if (state.is(Blocks.OBSIDIAN)) return PortalGeometry.OBSIDIAN;
        if (!state.is(Blocks.NETHER_PORTAL)) return PortalGeometry.OTHER;
        return state.getValue(NetherPortalBlock.AXIS) == Direction.Axis.X ? PortalGeometry.X : PortalGeometry.Z;
    }

    private static boolean inRange(ChunkPos pos, ChunkPos centre, int radius) {
        return Math.abs((long) pos.x() - centre.x()) <= radius && Math.abs((long) pos.z() - centre.z()) <= radius;
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "" : flagged.size() + " flagged";
    }
}
