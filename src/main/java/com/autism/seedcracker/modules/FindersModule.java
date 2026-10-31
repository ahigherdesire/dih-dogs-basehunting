package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;

import dihclient.api.module.ActionSetting;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.StringSetting;
import dihclient.modules.Module;

/** Live native-state summaries and explicit, one-shot controls; never a second saved loadout. */
public final class FindersModule extends Module {
    private record Entry(String name, String shortId, String group) {
        String statusId() { return "on" + shortId.replace(":", "-"); }
    }

    private static final Entry[] FINDERS = {
        new Entry("Stash Finder", ":z-stash-finder", "Chunk finders"),
        new Entry("Chunk Finder", ":z-chunk-finder", "Chunk finders"),
        new Entry("Sus Chunk Finder", ":z-sus-chunk-finder", "Chunk finders"),
        new Entry("Spawner Finder", ":z-spawner-finder", "Chunk finders"),
        new Entry("Netherite Finder", ":netherite-finder", "Chunk finders"),
        new Entry("Prime Chunk Finder", ":prime-chunk-finder", "Activity finders"),
        new Entry("Activity Finder", ":z-activity-finder", "Activity finders"),
        new Entry("Growth Finder", ":z-growth-finder", "Activity finders"),
        new Entry("Player Finder", ":player-chunks", "Activity finders"),
        new Entry("Light Bypass", ":light-finder", "Activity finders"),
        new Entry("Quantum", ":quantum", "Activity finders"),
        new Entry("Nether Tunnel Finder", ":nether-tunnel-finder", "Landmark finders"),
        new Entry("Sign Finder", ":sign-finder", "Landmark finders"),
        new Entry("Portal Finder", ":portal-finder", "Landmark finders"),
        new Entry("Structure Detector", ":structure-detector", "Landmark finders"),
        new Entry("Finder Overlay", ":finder-overlay", "Display tools"),
        new Entry("Render Util", ":render-util", "Display tools"),
        new Entry("Base Webhook", ":base-webhook", "External delivery (explicit opt-in)"),
    };

    public FindersModule() {
        super(SeedcrackerAddon.ID + ":finders", "Finders",
            "Control and inspect chunk, activity and landmark finders. Each keeps its own settings. Dogs / Observations stays separate and opt-in. No presets or automatic enabling.");
        add(new StringSetting("controls-help", "How to use", "Read status, then Toggle one tool.").readonlySummary()
            .description("Status reads the tool's actual native state, including keybind / loadout changes. Toggle requests one change; host checks can refuse it. Saved hub values never activate tools. Configure each tool in its own settings; confidence is not proof of a base.")
            .group("Controls"));
        for (Entry entry : FINDERS) {
            add(new BoolSetting(entry.statusId(), entry.name(), false).readonlySummary().keepOnReset()
                .formatter(ignored -> status(entry)).description("Live status only; not a saved enable request. Configure " + entry.name() + " in its own settings.")
                .group(entry.group()));
            add(new ActionSetting("toggle" + entry.shortId().replace(":", "-"), "Toggle " + entry.name(), () -> toggle(entry))
                .buttonLabel("Toggle").description(":base-webhook".equals(entry.shortId())
                    ? "Explicit opt-in: enabling Base Webhook can send finds and coordinates to its configured external URL. Configure it separately first. Nothing else here enables it."
                    : "Toggle the current native state once. Manual changes win; a refused activation stays off without retries.")
                .group(entry.group()).visibleWhen(() -> target(entry) != null));
        }
    }

    private static Module target(Entry entry) {
        return ModuleLookup.get(SeedcrackerAddon.ID + entry.shortId());
    }

    private static Entry entry(String settingId) {
        for (Entry entry : FINDERS) if (entry.statusId().equals(settingId)) return entry;
        return null;
    }

    private static String status(Entry entry) {
        Module finder = target(entry);
        if (finder == null) return "Not registered";
        if (!finder.isEnabled()) return "Off";
        String detail = finder.info();
        return detail == null || detail.isBlank() ? "On" : "On - " + detail;
    }

    private static void toggle(Entry entry) {
        Module finder = target(entry);
        if (finder != null) finder.setEnabled(!finder.isEnabled());
    }

    @Override protected String externalSettingValue(String settingId) {
        Entry entry = entry(settingId);
        if (entry == null) return null;
        Module finder = target(entry);
        return Boolean.toString(finder != null && finder.isEnabled());
    }

    @Override protected boolean setExternalSettingValue(String settingId, String value) {
        // Retain the old BoolSetting keys, but never replay persisted hub intent into a module.
        return entry(settingId) != null;
    }

    @Override public String info() {
        int registered = 0, enabled = 0;
        for (Entry entry : FINDERS) {
            Module finder = target(entry);
            if (finder != null) { registered++; if (finder.isEnabled()) enabled++; }
        }
        return enabled + " / " + registered + " tools on";
    }

    @Override public boolean hasActivationToggle() { return false; }
    @Override public boolean showInArrayList() { return false; }
    @Override public boolean emitsToggleMessage() { return false; }
}
