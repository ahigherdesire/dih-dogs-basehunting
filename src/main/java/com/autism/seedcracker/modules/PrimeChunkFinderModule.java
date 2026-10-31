// SPDX-License-Identifier: GPL-3.0-only
// Native port of Spectre PrimeChunkFinderModule, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.BuildEvidenceFilter;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import com.autism.seedcracker.spectre.chunks.PrimeTracker;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;

public final class PrimeChunkFinderModule extends Module {
    private final IntSetting range = add(new IntSetting("range", "Detection radius (chunks)", 32, 3, 32, 1).group("Detect"));
    private final BoolSetting requireBuild = add(new BoolSetting("require-build-evidence", "Require building evidence", true)
        .description("Require independent non-natural storage/automation clues. Off shows raw changes quietly without base reports or alerts; hidden bases may be missed.").group("Detect"));
    private final BoolSetting notifications = add(new BoolSetting("notification", "Notification", true).group("Alerts").visibleWhen(requireBuild::get));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true).group("Alerts").visibleWhen(notifications::get));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFB030).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true).group("Render"));
    private final FinderReport reporter = new FinderReport("Prime", 0);
    private final PrimeTracker tracker = new PrimeTracker();
    private final BuildEvidenceFilter context = new BuildEvidenceFilter();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int visible;
    private ClientLevel world;

    public PrimeChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":prime-chunk-finder", "Prime Chunk Finder",
            "Repeated unexplained light/redstone events below Y 0, after settled baselines. Excludes visible emitters and your two-chunk area; clues expire, not base proof.");
    }
    private void clear(ClientLevel level) {
        tracker.reset(level); context.clear(); notified.clear(); visible = 0;
        reporter.clear(); ChunkFlagRenderer.clear(id()); world = level;
    }
    @Override public void onEnable() {
        var mc = Minecraft.getInstance(); clear(mc.level); ChunkFlagRenderer.init();
        if (mc.player != null && mc.player.getY() >= 0) {
            FinderNotify.flag("[Prime Finder]", "Go below Y 0 before enabling Prime detection.", false);
            clear(null); setEnabledSilently(false);
        }
    }
    @Override public void onDisable() { clear(null); }
    @Override protected void onSettingsReset() { clear(isEnabled() ? Minecraft.getInstance().level : null); }
    @Override protected void onOptionValueChanged(String key) {
        if ("range".equals(key) || "require-build-evidence".equals(key)) clear(isEnabled() ? Minecraft.getInstance().level : null);
    }
    @Override public void onGameLeft() {
        clear(null);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override public void tick() {
        var mc = Minecraft.getInstance();
        if (world != mc.level) clear(mc.level);
        Set<ChunkPos> raw = tracker.tick(mc, range.get());
        if (mc.level == null || mc.player == null) { context.clear(); notified.clear(); visible = 0; reporter.clear(); return; }
        Set<ChunkPos> shown = context.filter(mc, raw, requireBuild.get());
        visible = shown.size();
        notified.retainAll(raw);
        for (ChunkPos pos : shown) if (requireBuild.get() && notifications.get() && notified.add(pos)) {
            FinderNotify.flag("[Prime Finder]", "Repeated changes with building evidence at X:" + pos.getMinBlockX()
                + " Z:" + pos.getMinBlockZ() + " (candidate, not base proof)", sound.get());
        }
        ChunkFlagRenderer.feed(id(), shown, color.get(), tracer.get());
        reporter.tick(mc, requireBuild.get() ? shown : Set.of());
    }

    /** ClientPacketListener TAIL: chunk-with-light, light-update and forget-chunk packets. */
    public void onPacketApplied(ClientLevel level, Packet<?> packet) {
        if (!isEnabled() || !Minecraft.getInstance().isSameThread() || level != Minecraft.getInstance().level) return;
        if (world != level) clear(level);
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk) context.invalidate(new ChunkPos(com.autism.seedcracker.compat.McCompat.chunkX(chunk), com.autism.seedcracker.compat.McCompat.chunkZ(chunk)));
        else if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forgotten) context.invalidate(forgotten.pos());
        tracker.applied(level, packet);
    }

    /** Existing ClientLevelMixin HEAD hook preserves the previous state before vanilla replaces it. */
    public void onBlockChange(ClientLevel level, BlockPos pos, BlockState previous, BlockState incoming) {
        var mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread() || level == null || level != mc.level) return;
        if (world != level) clear(level);
        context.invalidate(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        tracker.blockChange(level, pos, previous, incoming);
    }
    @Override public String info() {
        return (requireBuild.get() ? visible + " supported / " + tracker.size() + " raw changes" : tracker.size() + " raw changes (quiet)")
            + context.coverageSuffix() + reporter.coverageSuffix();
    }
}
