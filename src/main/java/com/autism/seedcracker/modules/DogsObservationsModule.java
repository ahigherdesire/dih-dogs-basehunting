package com.autism.seedcracker.modules;

import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.spectre.chunks.SuspectObjects;
import com.autism.seedcracker.spectre.chunks.SuspectPanel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Opt-in view of Sus Chunk Finder's existing observations; performs no world scans. */
public final class DogsObservationsModule extends Module {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "dogs-observations");
    private final IntSetting range = add(new IntSetting("inspect-range", "Inspect range", 128, 16, 256, 16)
        .description("Look-to-inspect distance in blocks, limited to objects collected within Sus Chunk Finder's object range.")
        .group("Panel"));
    private boolean hudRegistered;

    public DogsObservationsModule() {
        super(SeedcrackerAddon.ID + ":dogs-observations", "Dogs / Observations",
            "Optional look-to-inspect panel. Enable Sus Chunk Finder separately to collect observations; clues are not proof of a base.");
    }

    private static SusChunkFinderModule finder() {
        return ModuleLookup.get(SeedcrackerAddon.ID + ":z-sus-chunk-finder") instanceof SusChunkFinderModule module ? module : null;
    }

    @Override public void onEnable() {
        registerHud();
        var finder = finder();
        if (finder == null || !finder.isEnabled()) ClientNotify.warning("Dogs / Observations: enable Sus Chunk Finder to collect observations.");
    }

    private void registerHud() {
        if (hudRegistered) return;
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, HUD_ID, (g, delta) -> renderHud(g));
        hudRegistered = true;
    }

    @Override public void onDisable() {
        if (hudRegistered) HudElementRegistry.removeElement(HUD_ID);
        hudRegistered = false;
    }

    @Override public void onGameLeft() {
        onDisable();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override public void tick() {
        if (isEnabled() && Minecraft.getInstance().level != null) registerHud();
    }

    private void renderHud(GuiGraphicsExtractor g) {
        var mc = Minecraft.getInstance();
        if (!isEnabled() || mc.level == null || mc.player == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) return;
        var finder = finder();
        if (finder == null) return;
        var observations = finder.observations();
        if (observations == null || observations.markers().isEmpty()) return;
        var markers = observations.markers().stream().filter(marker -> loaded(mc.level, marker)).toList();
        if (markers.isEmpty()) return;
        var camera = mc.gameRenderer.mainCamera();
        var direction = camera.forwardVector();
        var focus = SuspectObjects.focus(markers, camera.position(),
            new Vec3(direction.x(), direction.y(), direction.z()), range.get());
        if (focus != null) SuspectPanel.draw(g, focus, category -> observations.colors().get(category), observations.limited());
    }

    // Sus refreshes objects periodically; reject unloaded bounds before picking or counting icons.
    private static boolean loaded(net.minecraft.client.multiplayer.ClientLevel level, SuspectObjects.Marker marker) {
        var bounds = marker.bounds();
        int minX = (int) Math.floor(bounds.minX) >> 4, maxX = (int) Math.floor(Math.nextDown(bounds.maxX)) >> 4;
        int minZ = (int) Math.floor(bounds.minZ) >> 4, maxZ = (int) Math.floor(Math.nextDown(bounds.maxZ)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }

    @Override public String info() {
        var finder = finder();
        return finder == null || !finder.isEnabled() ? "Enable Sus Chunk Finder" : "Look at an observed object";
    }
}
