package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import dihclient.api.module.IntSetting;
import dihclient.modules.Module;

/**
 * Finder Overlay.
 *
 * Smart overlap condensation for all finder chunk markers: in flooded areas (many flags close
 * together, or several finder modules agreeing) only the MOST-overlapped chunks render - as
 * distinct hotspot beacons (orange pyramid + white-hot core) - instead of the whole flooded
 * field. Sparse, isolated flags render normally.
 *
 * Toggle the module off to always draw every flag (old behaviour).
 */
public final class FinderOverlayModule extends Module {

    private final IntSetting overlapModules = add(new IntSetting(
            "overlap-modules", "Finder sources in 5x5 chunks", 2, 2, 6, 1)
        .description("Condense when this many distinct finder sources flag the 5x5 area OR the flagged-chunk threshold is reached. Nearby signals need not be the same evidence.")
        .group("Condense chunk flags"));
    private final IntSetting floodChunks = add(new IntSetting(
            "flood-chunks", "Flagged chunks in 5x5 chunks", 9, 3, 50, 1)
        .description("Condense when this many unique chunks are flagged in the 5x5 area OR the source threshold is reached. Display only: scans, object outlines and reports are unchanged.")
        .group("Condense chunk flags"));

    public FinderOverlayModule() {
        super(SeedcrackerAddon.ID + ":finder-overlay", "Finder Overlay",
            "Display only: condenses dense or overlapping chunk flags into hotspots. Off shows individual flags; detection and reports continue. Hotspots are not proof of a base.");
    }

    /** Grouped into the Finders hub (it's a simple on/off tool); hidden from the main menu. */
    @Override
    public boolean showInModuleMenu() {
        return false;
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.configureSmartOverlap(true, overlapModules.get(), floodChunks.get());
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.configureSmartOverlap(false, overlapModules.get(), floodChunks.get());
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    protected void onSettingsReset() {
        ChunkFlagRenderer.configureSmartOverlap(isEnabled(), overlapModules.get(), floodChunks.get());
    }

    @Override public String info() { return isEnabled() ? "Condensed chunk flags" : "Individual chunk flags"; }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (isEnabled()) {
            ChunkFlagRenderer.configureSmartOverlap(true, overlapModules.get(), floodChunks.get());
        }
    }
}
