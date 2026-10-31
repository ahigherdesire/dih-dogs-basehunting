// SPDX-License-Identifier: GPL-3.0-only
// LIGHT_DATA adapted from Spectre PlayerBypassModule/tracker, d5a7dd3 (Cody / cody-raves).
// SIMULATION_ACTIVITY filters/origin tracking adapted from Anubis 0.9.9.3 SimulationSignals (4ldenz).
// Existing modes, settings IDs and defaults are retained.
package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.BuildEvidenceFilter;
import com.autism.seedcracker.spectre.activity.PlayerLightTracker;
import com.autism.seedcracker.spectre.activity.AnubisSimulationSignals;
import com.autism.seedcracker.spectre.activity.QuantumRules;
import com.autism.seedcracker.util.pure.PlayerFinderSignals;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.EnumSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class PlayerChunksModule extends Module {
    public enum Mode { LIGHT_DATA, SIMULATION_ACTIVITY }
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Detection mode", Mode.LIGHT_DATA,
        Mode.values()).group("Detection"));
    private final IntSetting memory = add(new IntSetting("memory", "Memory seconds", 45, 5, 180, 5)
        .group("Detection").visibleWhen(() -> mode.get() == Mode.SIMULATION_ACTIVITY));
    private final IntSetting sources = add(new IntSetting("min-sources", "Independent activity sources", 4, 2, 16, 1)
        .group("Detection").visibleWhen(() -> mode.get() == Mode.SIMULATION_ACTIVITY));
    private final BoolSetting requireBuild = add(new BoolSetting("require-build-evidence", "Require building evidence", true)
        .description("Light mode: require independent storage/automation evidence. Off shows raw clues quietly with no reporting or auto-disconnect. Fully hidden bases may be missed.")
        .group("Detection").visibleWhen(() -> mode.get() == Mode.LIGHT_DATA));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("Alerts"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true).group("Alerts").visibleWhen(() -> notify.get()));
    private final BoolSetting disconnect = add(new BoolSetting("disconnect-on-spotted", "Auto disconnect", false).group("Alerts"));
    private final BoolSetting showCoordinates = add(new BoolSetting("show-coordinates", "Show coordinates", true)
        .group("Alerts").visibleWhen(() -> notify.get() || disconnect.get()));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0xffff2828).group("Render"));
    private final IntSetting opacity = add(new IntSetting("opacity", "Opacity", 140, 10, 255, 1).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final PlayerLightTracker lightTracker = new PlayerLightTracker();
    private final BuildEvidenceFilter context = new BuildEvidenceFilter();
    private final Set<ChunkPos> rawLight = new HashSet<>();
    private final FinderReport reporter = new FinderReport("Player Finder", 35);
    private final Map<ChunkPos, Long> loadedAt = new LinkedHashMap<>();
    private final Map<ChunkPos, Map<String, Long>> activity = new LinkedHashMap<>();
    private final Map<ChunkPos, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>> blockStates = new LinkedHashMap<>();
    private final AnubisSimulationSignals entitySignals = new AnubisSimulationSignals();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> alerted = new HashSet<>();
    private ClientLevel world;
    private ClientPacketListener connection;
    private Vec3 lastPosition;
    private long quietUntil;
    private boolean disconnectTriggered;
    private int ticks;

    public PlayerChunksModule() {
        super(SeedcrackerAddon.ID + ":player-chunks", "Player Finder",
            "Light-data anomalies or simulation activity outside your own range. Signals, not player proof.");
    }

    private void clear() {
        lightTracker.clear();
        context.clear();
        rawLight.clear();
        loadedAt.clear();
        activity.clear();
        blockStates.clear();
        entitySignals.clear();
        flagged.clear();
        alerted.clear();
        reporter.clear();
        lastPosition = null;
        ticks = 0;
        disconnectTriggered = false;
        quietUntil = System.nanoTime() + 3_000_000_000L;
        ChunkFlagRenderer.clear(id());
    }
    @Override public void onEnable() { clear(); world = null; connection = null; }
    @Override public void onDisable() { clear(); world = null; connection = null; }
    @Override public void onGameLeft() {
        onDisable();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onOptionValueChanged(String settingId) {
        if ("mode".equals(settingId) || "memory".equals(settingId) || "min-sources".equals(settingId)
            || "require-build-evidence".equals(settingId)) clear();
    }

    private boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return false;
        if (world != mc.level || connection != mc.getConnection()) {
            clear();
            world = mc.level;
            connection = mc.getConnection();
        }
        return isEnabled() && world != null && mc.player != null && world.dimensionType().hasSkyLight();
    }

    /** ClientPacketListener TAIL hook: preserve packet order and never inspect the world on Netty. */
    public void onPacketApplied(Packet<?> packet) {
        if (mode.get() == Mode.LIGHT_DATA && !(packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundLightUpdatePacket || packet instanceof ClientboundForgetLevelChunkPacket
            || packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket)) return;
        if (!ready()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (mode.get() == Mode.SIMULATION_ACTIVITY) {
            if (packet instanceof ClientboundRespawnPacket) {
                clear();
                return;
            }
            if (packet instanceof ClientboundPlayerPositionPacket) {
                settleSimulation(now);
                return;
            }
            observePlayerPosition(mc, now);
            if (packet instanceof ClientboundAddEntityPacket add) {
                forgetEntity(add.getId());
                sampleEntity(mc, world.getEntity(add.getId()), new Vec3(add.getX(), add.getY(), add.getZ()), now);
                return;
            }
            if (packet instanceof ClientboundMoveEntityPacket move) {
                Entity entity = move.getEntity(world);
                if (entity != null && move.hasPosition()) sampleEntity(mc, entity, entity.getPositionCodec().getBase(), now);
                return;
            }
            if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
                sampleEntity(mc, world.getEntity(sync.id()), com.autism.seedcracker.compat.McCompat.syncPosition(sync), now);
                return;
            }
            if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
                if (com.autism.seedcracker.compat.McCompat.entityIds(remove).size() > AnubisSimulationSignals.MAX_ENTITIES) settleSimulation(now);
                else for (int id : com.autism.seedcracker.compat.McCompat.entityIds(remove)) forgetEntity(id);
                return;
            }
        }
        ChunkPos current = mc.player.chunkPosition();
        lightTracker.updateEntry(current.x(), current.z(), key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)));
        if (packet instanceof ClientboundBlockUpdatePacket block) context.invalidate(new ChunkPos(block.getPos().getX() >> 4, block.getPos().getZ() >> 4));
        else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) section.runUpdates((pos, state) -> context.invalidate(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4)));
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            onChunkUnload(forget.pos());
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            ChunkPos pos = new ChunkPos(com.autism.seedcracker.compat.McCompat.chunkX(chunk), com.autism.seedcracker.compat.McCompat.chunkZ(chunk));
            context.invalidate(pos);
            if (distance(pos, mc.player.chunkPosition()) > 64) return;
            if (mode.get() == Mode.LIGHT_DATA) ingest(pos, com.autism.seedcracker.compat.McCompat.chunkLightData(chunk), true);
            else {
                forget(pos);
                loadedAt.put(pos, now);
                while (loadedAt.size() > PlayerLightTracker.MAX_CHUNKS) forget(loadedAt.keySet().iterator().next());
            }
        } else if (mode.get() == Mode.LIGHT_DATA && packet instanceof ClientboundLightUpdatePacket light) {
            ingest(new ChunkPos(com.autism.seedcracker.compat.McCompat.chunkX(light), com.autism.seedcracker.compat.McCompat.chunkZ(light)), com.autism.seedcracker.compat.McCompat.chunkLightData(light), false);
        } else if (mode.get() == Mode.SIMULATION_ACTIVITY && packet instanceof ClientboundBlockUpdatePacket block) {
            sampleBlock(mc, block.getPos(), block.getBlockState(), now);
        } else if (mode.get() == Mode.SIMULATION_ACTIVITY && packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((pos, state) -> sampleBlock(mc, pos, state, now));
        }
    }

    @Override public void tick() {
        if (!ready()) { context.clear(); rawLight.clear(); flagged.clear(); reporter.clear(); ChunkFlagRenderer.clear(id()); return; }
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        observePlayerPosition(mc, now);
        if (mode.get() == Mode.LIGHT_DATA) {
            ChunkPos current = mc.player.chunkPosition();
            lightTracker.updateEntry(current.x(), current.z(), key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)));
        }
        if (++ticks % 10 == 0) {
            if (mode.get() == Mode.SIMULATION_ACTIVITY) sampleEntities(mc, now);
            refresh(mc, now);
        }
        if (mode.get() == Mode.LIGHT_DATA) {
            if (mc.player.getY() < 0) { rawLight.clear(); alerted.clear(); }
            flagged.clear();
            flagged.addAll(context.filter(mc, rawLight, requireBuild.get()));
            alerted.retainAll(rawLight);
            if (requireBuild.get()) for (ChunkPos pos : flagged) if (alerted.add(pos)) onSignal(pos);
        }
        int alpha = Math.round((color.get() >>> 24) * Math.max(20, opacity.get()) / 255.0f);
        ChunkFlagRenderer.feed(id(), flagged, (color.get() & 0x00ffffff) | (alpha << 24), tracer.get());
        reporter.tick(mc, mode.get() == Mode.LIGHT_DATA && !requireBuild.get() ? Set.of() : flagged);
    }

    private void observePlayerPosition(Minecraft mc, long now) {
        Vec3 position = mc.player.position();
        if (lastPosition != null && lastPosition.distanceToSqr(position) > 64
            || mode.get() == Mode.SIMULATION_ACTIVITY && position.y() < 0) settleSimulation(now);
        lastPosition = position;
    }

    private void settleSimulation(long now) {
        activity.clear();
        blockStates.clear();
        entitySignals.clear();
        if (mode.get() == Mode.SIMULATION_ACTIVITY) {
            flagged.clear();
            alerted.clear();
            reporter.clear();
            ChunkFlagRenderer.clear(id());
        }
        quietUntil = now + 3_000_000_000L;
    }

    private void ingest(ChunkPos pos, ClientboundLightUpdatePacketData data, boolean baseline) {
        Minecraft mc = Minecraft.getInstance();
        if (data == null || !world.getChunkSource().hasChunk(pos.x(), pos.z()) || distance(pos, mc.player.chunkPosition()) > 64) return;
        lightTracker.accept(pos.x(), pos.z(), world.getSectionsCount(), world.getMinSectionY(),
            com.autism.seedcracker.compat.McCompat.skyYMask(data), com.autism.seedcracker.compat.McCompat.emptySkyYMask(data), com.autism.seedcracker.compat.McCompat.skyUpdates(data), mc.player.getY() < 0, baseline, System.nanoTime());
    }

    private void onSignal(ChunkPos pos) {
        String location = showCoordinates.get() ? " at X:" + (pos.getMinBlockX() + 8) + " Z:" + (pos.getMinBlockZ() + 8) : "";
        String message = mode.get() + " signal" + location + " (not player/base proof)";
        if (notify.get()) FinderNotify.flag("[Player Finder]", message, sound.get());
        if (disconnect.get() && !disconnectTriggered && connection != null) {
            disconnectTriggered = true;
            connection.getConnection().disconnect(Component.literal("[Player Finder] Auto disconnect: " + message));
        }
    }

    public void onChunkUnload(ChunkPos pos) {
        if (!ready() || pos == null) return;
        forget(pos);
        reporter.clear();
        ChunkFlagRenderer.clear(id());
    }

    private void forget(ChunkPos pos) {
        lightTracker.forget(QuantumRules.key(pos.x(), pos.z()));
        context.invalidate(pos);
        rawLight.remove(pos);
        loadedAt.remove(pos);
        activity.remove(pos);
        blockStates.remove(pos);
        entitySignals.forgetChunk(QuantumRules.key(pos.x(), pos.z()));
        flagged.remove(pos);
        alerted.remove(pos);
    }

    private boolean candidate(Minecraft mc, ChunkPos pos, long now) { return candidate(mc, pos, now, 0); }

    private boolean candidate(Minecraft mc, ChunkPos pos, long now, int spill) {
        int sim = world.getServerSimulationDistance();
        if (now < quietUntil || mc.player.getY() < 0 || !world.getChunkSource().hasChunk(pos.x(), pos.z())
            || distance(pos, mc.player.chunkPosition()) > 64
            || !PlayerFinderSignals.outsideOwnSimulation(distance(pos, mc.player.chunkPosition()) - spill, sim)) return false;
        for (Player player : world.players()) {
            if (player != mc.player && distance(pos, player.chunkPosition()) <= sim + 1 + spill) return false;
        }
        return true;
    }

    private void signal(Minecraft mc, BlockPos block, String source, long now) {
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        if (block.getY() < 0 || !candidate(mc, pos, now)) return;
        Long loaded = loadedAt.get(pos);
        if (loaded == null || now - loaded < 3_000_000_000L) return;
        if (!activity.containsKey(pos) && activity.size() >= PlayerLightTracker.MAX_CHUNKS) return;
        Map<String, Long> chunk = activity.computeIfAbsent(pos, ignored -> new HashMap<>());
        if (chunk.containsKey(source) || chunk.size() < 64) chunk.put(source, now);
    }

    private void forgetEntity(int id) {
        Long previous = entitySignals.chunk(id);
        if (previous != null) {
            Map<String, Long> events = activity.get(new ChunkPos(QuantumRules.x(previous), QuantumRules.z(previous)));
            if (events != null) events.remove("entity:" + id);
        }
        entitySignals.forget(id);
    }

    private boolean ignoredEntity(Minecraft mc, Entity entity) {
        if (entity instanceof Player || entity.isPassenger() || entity.isVehicle() || entity.isLocalInstanceAuthoritative()) return true;
        if (entity instanceof FallingBlockEntity falling && falling.getBlockState().is(Blocks.POINTED_DRIPSTONE)) return true;
        if (entity instanceof OwnableEntity ownable) {
            var owner = ownable.getOwnerReference();
            if (owner != null && mc.player.getUUID().equals(owner.getUUID())) return true;
        }
        return false;
    }

    private void sampleEntity(Minecraft mc, Entity entity, Vec3 position, long now) {
        if (entity == null || position == null) return;
        if (ignoredEntity(mc, entity)) { forgetEntity(entity.getId()); return; }
        BlockPos block = BlockPos.containing(position);
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        long key = QuantumRules.key(pos.x(), pos.z());
        Long previous = entitySignals.chunk(entity.getId());
        if (previous != null && previous != key) {
            Map<String, Long> events = activity.get(new ChunkPos(QuantumRules.x(previous), QuantumRules.z(previous)));
            if (events != null) events.remove("entity:" + entity.getId());
        }
        int sim = world.getServerSimulationDistance();
        boolean own = !PlayerFinderSignals.outsideOwnSimulation(distance(pos, mc.player.chunkPosition()), sim);
        boolean moved = entitySignals.observe(entity.getId(), entity.getUUID(), key, position.x(), position.y(), position.z(), own, true, now);
        if (moved) signal(mc, block, "entity:" + entity.getId(), now);
    }

    private void sampleEntities(Minecraft mc, long now) {
        Set<Integer> live = new HashSet<>();
        int inspected = 0;
        for (var entity : world.entitiesForRendering()) {
            if (++inspected > AnubisSimulationSignals.MAX_ENTITIES) break;
            if (ignoredEntity(mc, entity)) { forgetEntity(entity.getId()); continue; }
            live.add(entity.getId());
            ChunkPos pos = entity.chunkPosition();
            boolean own = !PlayerFinderSignals.outsideOwnSimulation(distance(pos, mc.player.chunkPosition()), world.getServerSimulationDistance());
            entitySignals.observe(entity.getId(), entity.getUUID(), QuantumRules.key(pos.x(), pos.z()),
                entity.getX(), entity.getY(), entity.getZ(), own, false, now);
        }
        entitySignals.retain(live::contains);
    }

    private void sampleBlock(Minecraft mc, BlockPos block, net.minecraft.world.level.block.state.BlockState state, long now) {
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        if (block.getY() < 0 || !candidate(mc, pos, now, 1) || !loadedAt.containsKey(pos)) return;
        if (!blockStates.containsKey(pos) && blockStates.size() >= PlayerLightTracker.MAX_CHUNKS) return;
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> known = blockStates.computeIfAbsent(pos, ignored -> new HashMap<>());
        if (!known.containsKey(block) && known.size() >= 64) return;
        var previous = known.put(block.immutable(), state);
        if (PlayerFinderSignals.changed(previous, state) && previous.getFluidState().isEmpty() && state.getFluidState().isEmpty()
            && !AnubisSimulationSignals.ignoredBlock(BuiltInRegistries.BLOCK.getKey(previous.getBlock()).toString())
            && !AnubisSimulationSignals.ignoredBlock(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
            signal(mc, block, "block:" + block.asLong(), now);
        }
    }

    private void refresh(Minecraft mc, long now) {
        long lifetime = memory.get() * 1_000_000_000L;
        lightTracker.retain(key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key))
            && distance(new ChunkPos(QuantumRules.x(key), QuantumRules.z(key)), mc.player.chunkPosition()) <= 64);
        loadedAt.keySet().removeIf(pos -> !world.getChunkSource().hasChunk(pos.x(), pos.z()) || distance(pos, mc.player.chunkPosition()) > 64);
        blockStates.keySet().removeIf(pos -> !candidate(mc, pos, now, 1));
        activity.entrySet().removeIf(entry -> {
            boolean blocksAllowed = candidate(mc, entry.getKey(), now, 1);
            entry.getValue().entrySet().removeIf(source -> !PlayerFinderSignals.active(source.getValue(), now, lifetime)
                || !blocksAllowed && source.getKey().startsWith("block:"));
            return entry.getValue().isEmpty() || !candidate(mc, entry.getKey(), now);
        });
        flagged.clear();
        if (mode.get() == Mode.LIGHT_DATA) {
            rawLight.clear();
            if (mc.player.getY() >= 0) for (long key : lightTracker.flagged(now)) {
                ChunkPos pos = new ChunkPos(QuantumRules.x(key), QuantumRules.z(key));
                if (distance(pos, mc.player.chunkPosition()) <= 16) rawLight.add(pos);
            }
        } else {
            activity.forEach((pos, events) -> { if (events.size() >= sources.get()) flagged.add(pos); });
            alerted.retainAll(flagged);
            for (ChunkPos pos : flagged) if (alerted.add(pos)) onSignal(pos);
        }
    }

    private static int distance(ChunkPos first, ChunkPos second) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Math.abs((long) first.x() - second.x()), Math.abs((long) first.z() - second.z())));
    }
    @Override public String info() {
        String status = mode.get() == Mode.LIGHT_DATA ? requireBuild.get()
            ? flagged.size() + " supported / " + rawLight.size() + " raw light clues" : rawLight.size() + " raw light clues (quiet)"
            : mode.get() + " / " + flagged.size() + " signals";
        return status + (mode.get() == Mode.LIGHT_DATA ? context.coverageSuffix() : "") + reporter.coverageSuffix();
    }
}