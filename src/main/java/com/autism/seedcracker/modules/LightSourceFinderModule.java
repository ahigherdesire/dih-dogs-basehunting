// SPDX-License-Identifier: GPL-3.0-only
// Spectre PlayerBypassLightTracker d5a7dd3; packet evidence replaces the old raw glow-volume scanner.
package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.BuildEvidenceFilter;
import com.autism.seedcracker.spectre.activity.PlayerLightTracker;
import com.autism.seedcracker.spectre.activity.QuantumRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;

public final class LightSourceFinderModule extends Module {
    private final IntSetting radius = add(new IntSetting("scan-radius", "Packet range (chunks)", 8, 3, 32, 1).group("Detection"));
    private final BoolSetting requireBuild = add(new BoolSetting("require-build-evidence", "Require building evidence", true)
        .description("Require visible non-natural storage or automation clues in the same chunk. May miss hidden bases. Off shows raw clues quietly, without base reports or alerts.").group("Detection"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("Alerts").visibleWhen(requireBuild::get));
    private final ColorSetting color = add(new ColorSetting("color", "Candidate colour", 0x60ffcc00).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final PlayerLightTracker tracker = new PlayerLightTracker();
    private final FinderReport reporter = new FinderReport("Light Bypass", 0);
    private final BuildEvidenceFilter context = new BuildEvidenceFilter();
    private final Set<ChunkPos> raw = new HashSet<>();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private ClientLevel world;
    private ClientPacketListener connection;
    private int ticks;

    public LightSourceFinderModule() {
        super(SeedcrackerAddon.ID + ":light-finder", "Light Bypass",
            "Repeated Spectre skylight clues plus independent building evidence, not lava or natural brightness. Survey above Y 0; no proof of a base.");
    }

    private void clear() {
        tracker.clear(); context.clear(); raw.clear(); flagged.clear(); notified.clear(); reporter.clear(); ticks = 0;
        ChunkFlagRenderer.clear(id());
    }
    @Override public void onEnable() { clear(); world = null; connection = null; ChunkFlagRenderer.init(); }
    @Override public void onDisable() { clear(); world = null; connection = null; }
    @Override public void onGameLeft() {
        onDisable();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onOptionValueChanged(String key) { if ("scan-radius".equals(key) || "require-build-evidence".equals(key)) clear(); }

    private boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return false;
        if (world != mc.level || connection != mc.getConnection()) {
            clear(); world = mc.level; connection = mc.getConnection();
        }
        return isEnabled() && world != null && mc.player != null && world.dimensionType().hasSkyLight();
    }

    /** Applied-packet TAIL hook: no off-thread world reads or guessed missing light layers. */
    public void onPacketApplied(Packet<?> packet) {
        if (!ready()) return;
        Minecraft mc = Minecraft.getInstance();
        ChunkPos player = mc.player.chunkPosition();
        tracker.updateEntry(player.x(), player.z(), key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)));
        if (packet instanceof ClientboundForgetLevelChunkPacket forgotten) {
            onChunkUnload(forgotten.pos()); return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket block) {
            context.invalidate(new ChunkPos(block.getPos().getX() >> 4, block.getPos().getZ() >> 4)); return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((pos, state) -> context.invalidate(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4))); return;
        }
        int x, z;
        boolean baseline;
        ClientboundLightUpdatePacketData data;
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            x = com.autism.seedcracker.compat.McCompat.chunkX(chunk); z = com.autism.seedcracker.compat.McCompat.chunkZ(chunk); data = com.autism.seedcracker.compat.McCompat.chunkLightData(chunk); baseline = true;
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            x = com.autism.seedcracker.compat.McCompat.chunkX(light); z = com.autism.seedcracker.compat.McCompat.chunkZ(light); data = com.autism.seedcracker.compat.McCompat.chunkLightData(light); baseline = false;
        } else return;
        if (data == null || !world.getChunkSource().hasChunk(x, z) || !inRange(x, z, player)) return;
        if (baseline) context.invalidate(new ChunkPos(x, z));
        tracker.accept(x, z, world.getSectionsCount(), world.getMinSectionY(), com.autism.seedcracker.compat.McCompat.skyYMask(data),
            com.autism.seedcracker.compat.McCompat.emptySkyYMask(data), com.autism.seedcracker.compat.McCompat.skyUpdates(data), mc.player.getY() < 0, baseline, System.nanoTime());
    }

    public void onChunkUnload(ChunkPos pos) {
        if (!ready() || pos == null) return;
        tracker.forget(pos.pack()); context.invalidate(pos); raw.remove(pos); flagged.remove(pos); notified.remove(pos);
        reporter.clear(); ChunkFlagRenderer.clear(id());
    }

    @Override public void tick() {
        if (!ready()) { context.clear(); raw.clear(); flagged.clear(); reporter.clear(); ChunkFlagRenderer.clear(id()); return; }
        Minecraft mc = Minecraft.getInstance();
        ChunkPos player = mc.player.chunkPosition();
        tracker.updateEntry(player.x(), player.z(), key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)));
        if (++ticks % 10 == 0) {
            tracker.retain(key -> inRange(QuantumRules.x(key), QuantumRules.z(key), player)
                && world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)));
            raw.clear();
            if (mc.player.getY() >= 0) for (long key : tracker.flagged(System.nanoTime())) {
                raw.add(new ChunkPos(QuantumRules.x(key), QuantumRules.z(key)));
            }
        }
        if (mc.player.getY() < 0) { raw.clear(); notified.clear(); }
        flagged.clear();
        flagged.addAll(context.filter(mc, raw, requireBuild.get()));
        notified.retainAll(raw);
        for (ChunkPos pos : flagged) if (requireBuild.get() && notify.get() && notified.add(pos)) {
            FinderNotify.flag("[Light Bypass]", "Repeated light clue with building evidence at X:" + pos.getMinBlockX()
                + " Z:" + pos.getMinBlockZ() + " (candidate, not base proof)", false);
        }
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, requireBuild.get() ? flagged : Set.of());
    }

    private boolean inRange(int x, int z, ChunkPos center) {
        return Math.abs((long) x - center.x()) <= radius.get() && Math.abs((long) z - center.z()) <= radius.get();
    }
    @Override public String info() {
        var player = Minecraft.getInstance().player;
        if (player != null && player.getY() < 0) return "Survey above Y 0";
        return (requireBuild.get() ? flagged.size() + " supported / " + raw.size() + " raw clues" : raw.size() + " raw clues (quiet)")
            + context.coverageSuffix() + reporter.coverageSuffix();
    }
}
