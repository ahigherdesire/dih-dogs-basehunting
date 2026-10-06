package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.example.donutflipscanner.ClientProductionRuntime;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.StringSetting;
import dihclient.modules.Module;
import dihclient.util.DihClientMessaging;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * AH Flipper.
 *
 * Boots the bundled DonutSMP auction-house flipper engine. In packet-interception mode it
 * scans the auction house as you open /ah (no API key required) and finds underpriced flips.
 * If you add a DonutSMP API key (paste it into the setting, saved to the engine's api-key.txt),
 * it switches to live API mode for continuous market scanning and automated buy/relist.
 *
 * The engine (donutflipscanner) provides the market analysis, trade state machine, and profit
 * tracking; this module just starts/stops it from the AUTISM module menu.
 */
public final class AHFlipperModule extends Module {

    private final StringSetting apiKey = add(new StringSetting("api-key", "DonutSMP API key", "")
        .description("Optional DonutSMP API key for live market scanning (leave blank for packet mode).")
        .group("General"));
    private final BoolSetting liveMode = add(new BoolSetting("live-mode", "Live API mode", false)
        .description("Use the DonutSMP API (requires an API key). Off = packet mode (open /ah to scan).")
        .group("General"));

    private ClientProductionRuntime runtime;
    private CompletableFuture<Optional<ClientProductionRuntime>> starting;

    public AHFlipperModule() {
        super(SeedcrackerAddon.ID + ":ah-flipper", "AH Flipper",
            "Scans the DonutSMP auction house for underpriced flips (packet mode, or live API with a key).");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        Path configDir = dihclient.DihClientAddon.FOLDER.toPath().resolve("donut-ah");
        try {
            java.nio.file.Files.createDirectories(configDir);
        } catch (Exception e) {
            DihClientMessaging.sendPrefixed("§cAH Flipper: can't create config dir: " + e.getMessage());
            setEnabledSilently(false);
            return;
        }

        String key = apiKey.get().trim();
        if (!key.isEmpty()) {
            try {
                java.nio.file.Files.writeString(configDir.resolve("api-key.txt"), key);
            } catch (Exception e) {
                DihClientMessaging.sendPrefixed("§eAH Flipper: couldn't save API key: " + e.getMessage());
            }
        }

        String username = mc.getUser() != null ? mc.getUser().getName() : "";
        String uuid = mc.getUser() != null && mc.getUser().getProfileId() != null
            ? mc.getUser().getProfileId().toString() : "";

        boolean live = liveMode.get() && !key.isEmpty();
        DihClientMessaging.sendPrefixed(live
            ? "§aAH Flipper: starting live API mode..."
            : "§aAH Flipper: starting packet mode (open /ah to scan)...");

        starting = live
            ? ClientProductionRuntime.startAsync(configDir, username, uuid)
            : ClientProductionRuntime.startPacketModeAsync(configDir, username, uuid);

        starting.whenComplete((opt, error) -> {
            if (error != null) {
                DihClientMessaging.sendPrefixed("§cAH Flipper failed to start: " + error.getMessage());
                setEnabledSilently(false);
                return;
            }
            if (opt != null && opt.isPresent()) {
                runtime = opt.get();
                DihClientMessaging.sendPrefixed("§aAH Flipper running.");
            } else {
                DihClientMessaging.sendPrefixed(live
                    ? "§eAH Flipper: no valid API key found; falling back. Add a key for live mode."
                    : "§eAH Flipper: engine didn't start (mock mode).");
                if (live) setEnabledSilently(false);
            }
        });
    }

    @Override
    public void onDisable() {
        if (starting != null) {
            starting.cancel(true);
            starting = null;
        }
        if (runtime != null) {
            try {
                runtime.close();
            } catch (Exception ignored) {}
            runtime = null;
        }
        DihClientMessaging.sendPrefixed("§7AH Flipper stopped.");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public String info() {
        return runtime != null ? "running" : "idle";
    }
}
