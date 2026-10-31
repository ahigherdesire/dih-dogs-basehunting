// SPDX-License-Identifier: GPL-3.0-only
// Spectre PrimeChunkFinderModule, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da; resumable native port.
package com.autism.seedcracker.spectre.chunks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.util.pure.PrimeLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

public final class PrimeTracker {
    private static final Direction[] SIDES = Direction.values();
    private record Update(long chunk, java.util.BitSet present, java.util.BitSet empty, List<byte[]> bytes,
                          boolean baseline, long event, long time) {}
    private record Source(BlockPos pos, int emission, boolean amethyst) {}
    private record GoneLamp(int emission, long expires) {}
    private final LightHistory history = new LightHistory(5000);
    private final ArrayDeque<Update> updates = new ArrayDeque<>();
    private final ArrayDeque<Pending> differences = new ArrayDeque<>();
    private final ArrayDeque<ChunkPos> initial = new ArrayDeque<>();
    private final LinkedHashSet<Long> passed = new LinkedHashSet<>();
    private final PrimeConfirmation confirmation = new PrimeConfirmation();
    private final Map<Long, Long> baselineAt = new HashMap<>();
    private final LinkedHashMap<BlockPos, GoneLamp> goneLamps = new LinkedHashMap<>();
    private ClientLevel level;
    private boolean initialQueued, resync;
    private long nextEvent;
    private int activeRadius = 32;
    private static final long SETTLE_NANOS = 2_000_000_000L;

    public void reset(ClientLevel world) {
        updates.clear(); differences.clear(); initial.clear(); history.clear(); passed.clear(); confirmation.clear();
        baselineAt.clear(); goneLamps.clear(); level = world; initialQueued = false; resync = false; nextEvent = 0;
    }
    public void applied(ClientLevel world, Packet<?> packet) {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread() || world == null || world != mc.level) return;
        if (level != world) reset(world);
        Update update = null;
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) update = capture(ChunkPos.pack(com.autism.seedcracker.compat.McCompat.chunkX(chunk), com.autism.seedcracker.compat.McCompat.chunkZ(chunk)), com.autism.seedcracker.compat.McCompat.chunkLightData(chunk), true);
        else if (packet instanceof ClientboundLightUpdatePacket light) update = capture(ChunkPos.pack(com.autism.seedcracker.compat.McCompat.chunkX(light), com.autism.seedcracker.compat.McCompat.chunkZ(light)), com.autism.seedcracker.compat.McCompat.chunkLightData(light), false);
        else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            long key = forget.pos().pack(); history.forget(key); confirmation.forget(key); baselineAt.remove(key);
            updates.removeIf(queued -> queued.chunk() == key); differences.removeIf(pending -> pending.delta.chunk() == key);
            initial.remove(forget.pos());
        }
        if (update != null) {
            if (updates.size() >= 256) { updates.clear(); resync = true; }
            updates.addLast(update);
        }
    }

    private Update capture(long key, ClientboundLightUpdatePacketData data, boolean baseline) {
        var mask = com.autism.seedcracker.compat.McCompat.blockYMask(data); var bytes = com.autism.seedcracker.compat.McCompat.blockUpdates(data);
        if (mask.intersects(com.autism.seedcracker.compat.McCompat.emptyBlockYMask(data)) || mask.cardinality() != bytes.size()
                || mask.length() > level.getSectionsCount() + 2 || bytes.size() > level.getSectionsCount() + 2
                || com.autism.seedcracker.compat.McCompat.emptyBlockYMask(data).length() > level.getSectionsCount() + 2) {
            updates.clear(); resync = true; return null;
        }
        var present = new java.util.BitSet(); var empty = new java.util.BitSet(); var copies = new ArrayList<byte[]>();
        int lightBottom = level.getChunkSource().getLightEngine().getMinLightSection(), next = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
            byte[] value = bytes.get(next++);
            if (value == null || value.length != 2048) { updates.clear(); resync = true; return null; }
            int y = lightBottom + bit;
            if (y >= bottom() && y < top()) { present.set(bit); copies.add(value.clone()); }
        }
        var cleared = com.autism.seedcracker.compat.McCompat.emptyBlockYMask(data);
        for (int bit = cleared.nextSetBit(0); bit >= 0; bit = cleared.nextSetBit(bit + 1))
            if (lightBottom + bit >= bottom() && lightBottom + bit < top()) empty.set(bit);
        return new Update(key, present, empty, List.copyOf(copies), baseline, ++nextEvent, System.nanoTime());
    }

    public Set<ChunkPos> tick(Minecraft mc, int radius) {
        if (level != mc.level) reset(mc.level);
        if (level == null || mc.player == null) return Set.of();
        activeRadius = Math.clamp(radius, 1, 32);
        if (resync) {
            history.clear(); differences.clear(); initial.clear(); confirmation.clear(); baselineAt.clear();
            initialQueued = false; resync = false;
        }
        if (!initialQueued) {
            var loaded = ChunkScanHelper.loadedChunksAround(mc, radius);
            loaded.sort(Comparator.comparingInt(chunk -> distance(chunk.getPos(), mc.player.chunkPosition())));
            for (var chunk : loaded) initial.addLast(chunk.getPos());
            initialQueued = true;
        }
        if (mc.player.getY() < 0) {
            ChunkPos player = mc.player.chunkPosition();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) passed.add(ChunkPos.pack(player.x() + dx, player.z() + dz));
            while (passed.size() > 5000) passed.removeFirst();
        }
        long now = System.nanoTime();
        goneLamps.values().removeIf(lamp -> lamp.expires() < now);
        baselineAt.keySet().removeIf(key -> !loaded(key) || !nearby(key, mc));
        confirmation.retain(key -> loaded(key) && nearby(key, mc) && !own(key, mc));
        long deadline = System.nanoTime() + 3_000_000L;
        for (int n = 0; n < 8 && !updates.isEmpty() && System.nanoTime() < deadline; n++) ingest(updates.removeFirst(), mc);
        for (int n = 0; n < 2 && !initial.isEmpty() && System.nanoTime() < deadline; n++) {
            var pos = initial.removeFirst();
            if (distance(pos, mc.player.chunkPosition()) <= radius && !history.contains(pos.pack())) snapshot(pos);
        }
        while (!differences.isEmpty() && System.nanoTime() < deadline) {
            Pending next = differences.peekFirst();
            if (mc.player.getY() >= 0 || own(next.delta.chunk(), mc) || !loaded(next.delta.chunk())
                || !nearby(next.delta.chunk(), mc) || now - next.time > PrimeConfirmation.WINDOW || next.step(deadline)) differences.removeFirst();
            else break;
        }
        var shown = new HashSet<ChunkPos>();
        if (mc.player.getY() < 0) for (long key : confirmation.flagged(now)) {
            if (loaded(key) && nearby(key, mc) && !own(key, mc)) shown.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        return Set.copyOf(shown);
    }
    private boolean loaded(long key) { return level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null; }
    private static int distance(ChunkPos a, ChunkPos b) { return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())); }
    private boolean own(long key, Minecraft mc) {
        return passed.contains(key) || distance(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)), mc.player.chunkPosition()) <= 2;
    }
    private boolean nearby(long key, Minecraft mc) {
        return distance(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)), mc.player.chunkPosition()) <= activeRadius;
    }
    private boolean settled(long key, long now) {
        Long baseline = baselineAt.get(key);
        return baseline != null && now - baseline >= SETTLE_NANOS;
    }
    // ponytail: at most 64 below-zero sections; widen the history budget for deeper custom worlds.
    private int bottom() { return Math.max(-64, level.getMinSectionY()); }
    private int top() { return Math.min(0, level.getMaxSectionY() + 1); }

    private void snapshot(ChunkPos pos) {
        if (!loaded(pos.pack())) return;
        var layers = new HashMap<Integer, byte[]>();
        var light = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int y = bottom(); y < top(); y++) {
            var layer = light.getDataLayerData(SectionPos.of(pos.x(), y, pos.z()));
            if (layer != null) layers.put(y, layer.getData());
        }
        history.snapshot(pos.pack(), layers);
        baselineAt.put(pos.pack(), System.nanoTime());
    }
    private void ingest(Update update, Minecraft mc) {
        if (!loaded(update.chunk()) || !nearby(update.chunk(), mc) || top() <= bottom()) return;
        if (!update.baseline() && !history.contains(update.chunk())) { snapshot(new ChunkPos(ChunkPos.getX(update.chunk()), ChunkPos.getZ(update.chunk()))); return; }
        if (update.baseline()) {
            differences.removeIf(pending -> pending.delta.chunk() == update.chunk());
            confirmation.forget(update.chunk());
            baselineAt.put(update.chunk(), update.time());
        }
        var deltas = history.apply(update.chunk(), bottom(), top(), level.getChunkSource().getLightEngine().getMinLightSection(),
            update.present(), update.empty(), update.bytes(), update.baseline());
        if (!history.contains(update.chunk())) differences.removeIf(pending -> pending.delta.chunk() == update.chunk());
        if (own(update.chunk(), mc) || mc.player.getY() >= 0 || !settled(update.chunk(), update.time())) return;
        if (differences.size() + deltas.size() > 128) {
            // Missing comparisons are safer than treating a partial packet stream as activity.
            differences.clear(); history.clear(); confirmation.clear(); baselineAt.clear(); initialQueued = false; initial.clear(); return;
        }
        for (var delta : deltas) differences.addLast(new Pending(delta, update.event(), update.time()));
    }

    public void blockChange(ClientLevel world, BlockPos pos, BlockState previous, BlockState incoming) {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread() || world != mc.level || mc.player == null || previous.equals(incoming) || pos.getY() >= 0) return;
        if (world != level) reset(world);
        if (PrimeEvidence.lamp(previous) && (!PrimeEvidence.lamp(incoming) || incoming.getLightEmission() < previous.getLightEmission())) {
            goneLamps.put(pos.immutable(), new GoneLamp(previous.getLightEmission(), System.nanoTime() + 10_000_000_000L));
            while (goneLamps.size() > 2048) goneLamps.pollFirstEntry();
        }
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        long now = System.nanoTime();
        if (mc.player.getY() < 0 && !own(key, mc) && nearby(key, mc) && settled(key, now)
            && (PrimeEvidence.redstone(previous) || PrimeEvidence.redstone(incoming))) flag(key, pos, ++nextEvent, now);
    }
    private void flag(long key, BlockPos pos, long event, long now) {
        confirmation.observe(key, event, now);
    }
    public int size() { return confirmation.flagged(System.nanoTime()).size(); }

    private final class Pending {
        final LightHistory.Delta delta;
        final List<Source> sources = new ArrayList<>();
        final long event, time;
        int section, sourceCell, cell, checkingSource, unexplained;
        boolean sourcesReady, incomplete, checking;
        BlockPos position;
        int value;
        Pending(LightHistory.Delta delta, long event, long time) { this.delta = delta; this.event = event; this.time = time; }

        boolean step(long deadline) {
            if (!sourcesReady && !scanSources(deadline)) return false;
            if (incomplete) return true;
            while (cell < 4096 && System.nanoTime() < deadline) {
                if (!checking) {
                    int old = PrimeLight.value(delta.previous(), cell), now = PrimeLight.value(delta.incoming(), cell);
                    if (Math.abs(old - now) < 2 || Math.max(old, now) < 5) { cell++; continue; }
                    position = new BlockPos((ChunkPos.getX(delta.chunk()) << 4) + (cell & 15), (delta.sectionY() << 4) + (cell >>> 8),
                        (ChunkPos.getZ(delta.chunk()) << 4) + ((cell >>> 4) & 15));
                    value = Math.max(old, now); checkingSource = 0; checking = true;
                }
                boolean explained = false;
                while (checkingSource < sources.size()) {
                    Source source = sources.get(checkingSource++);
                    int distance = Math.abs(source.pos().getX() - position.getX()) + Math.abs(source.pos().getY() - position.getY()) + Math.abs(source.pos().getZ() - position.getZ());
                    if (source.amethyst() ? PrimeLight.amethystExplains(value, distance) : PrimeLight.sourceExplains(source.emission(), value, distance)) { explained = true; break; }
                    if ((checkingSource & 63) == 0 && System.nanoTime() >= deadline) return false;
                }
                if (!explained && ++unexplained >= 8) { flag(delta.chunk(), position, event, time); return true; }
                checking = false; cell++;
            }
            return cell == 4096;
        }

        boolean scanSources(long deadline) {
            int cx = ChunkPos.getX(delta.chunk()), cz = ChunkPos.getZ(delta.chunk());
            while (section < 27 && System.nanoTime() < deadline) {
                int sx = cx + section / 9 - 1, sy = delta.sectionY() + (section / 3) % 3 - 1, sz = cz + section % 3 - 1;
                if (sy < level.getMinSectionY() || sy > level.getMaxSectionY()) { section++; sourceCell = 0; continue; }
                var chunk = level.getChunkSource().getChunkNow(sx, sz);
                if (chunk == null) { incomplete = true; return true; }
                var slice = chunk.getSection(chunk.getSectionIndex(sy << 4));
                if (sourceCell == 0 && (slice.hasOnlyAir() || !slice.maybeHas(PrimeEvidence::source))) { section++; continue; }
                int end = Math.min(4096, sourceCell + 64);
                for (; sourceCell < end; sourceCell++) {
                    BlockState state = slice.getBlockState(sourceCell & 15, sourceCell >>> 8, (sourceCell >>> 4) & 15);
                    if (!PrimeEvidence.source(state)) continue;
                    var pos = new BlockPos((sx << 4) + (sourceCell & 15), (sy << 4) + (sourceCell >>> 8), (sz << 4) + ((sourceCell >>> 4) & 15));
                    boolean amethyst = PrimeEvidence.amethyst(state), ore = PrimeEvidence.ore(state);
                    int emission = ore ? 9 : state.getLightEmission();
                    int minDistance = axisDistance(pos.getX(), cx << 4) + axisDistance(pos.getY(), delta.sectionY() << 4) + axisDistance(pos.getZ(), cz << 4);
                    if (minDistance > (amethyst ? 5 : emission)) continue;
                    if (ore) {
                        boolean exposed = false;
                        for (Direction side : SIDES) {
                            BlockPos neighbor = pos.relative(side);
                            var other = level.getChunkSource().getChunkNow(neighbor.getX() >> 4, neighbor.getZ() >> 4);
                            if (other == null) { incomplete = true; return true; }
                            if (!other.getBlockState(neighbor).canOcclude()) { exposed = true; break; }
                        }
                        if (!exposed) continue;
                    }
                    if (sources.size() >= 8192) { incomplete = true; return true; }
                    sources.add(new Source(pos, emission, amethyst));
                }
                if (sourceCell == 4096) { section++; sourceCell = 0; }
            }
            if (section < 27) return false;
            goneLamps.forEach((pos, lamp) -> sources.add(new Source(pos, lamp.emission(), false)));
            sourcesReady = true; return true;
        }
        private static int axisDistance(int point, int minimum) { return Math.max(0, Math.max(minimum - point, point - minimum - 15)); }
    }
}
