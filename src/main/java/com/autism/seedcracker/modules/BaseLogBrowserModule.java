package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.baselog.BaseLogScreen;

import dihclient.api.module.ActionSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;

/**
 * Base Log Browser.
 *
 * Opens a local review of unverified coordinates in bases.txt, including legacy logs.
 * Supports search, numerical X/Z sorting, exact coordinate copying and confirmed deletion.
 * The module itself does nothing while toggled - enabling it simply opens the browser.
 */
public final class BaseLogBrowserModule extends Module {

    public BaseLogBrowserModule() {
        super(SeedcrackerAddon.ID + ":base-log-browser", "Base Log Browser",
            "Review unverified candidates in bases.txt, not confirmed bases. Enable (or press Open) to browse.");

        add(new ActionSetting("open", "Open Base Log Browser", BaseLogBrowserModule::open)
            .buttonLabel("Open")
            .description("Review local candidates; no server connection or movement required.")
            .group("General").availableOffline());
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new BaseLogScreen(mc.gui.screen())));
    }

    @Override
    public void onEnable() {
        // Enabling the module just opens the GUI, then turns itself back off.
        open();
        setEnabledSilently(false);
    }
}
