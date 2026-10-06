package com.autism.seedcracker.commands;

import com.autism.seedcracker.finder.BaseHeatTracker;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dihclient.commands.DihCommandSource;
import dihclient.commands.Command;
import dihclient.util.DihClientMessaging;

/**
 * Confirms queued base-find heatmap updates. Usage: .heatconfirm (alias .hc, .heat).
 * When the Region Map's "Auto-update heatmap" is off, finds queue up instead of updating the
 * heatmap automatically; this command adds all queued finds at once.
 */
public final class HeatConfirmCommand extends Command {
    public HeatConfirmCommand() {
        super("heatconfirm", "Add all queued base finds to the region heatmap.", "hc", "heat");
    }

    @Override
    public void build(LiteralArgumentBuilder<DihCommandSource> root) {
        root.executes(ctx -> {
            int pending = BaseHeatTracker.pendingCount();
            if (pending == 0) {
                DihClientMessaging.sendPrefixed("§7[Heatmap] No queued finds to add.");
            } else {
                int added = BaseHeatTracker.confirmPending();
                DihClientMessaging.sendPrefixed("§a[Heatmap] Added " + added + " find(s) to the heatmap.");
            }
            return SUCCESS;
        });
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("clear")
            .executes(ctx -> {
                BaseHeatTracker.discardPending();
                DihClientMessaging.sendPrefixed("§7[Heatmap] Discarded queued finds.");
                return SUCCESS;
            }));
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("count")
            .executes(ctx -> {
                DihClientMessaging.sendPrefixed("§7[Heatmap] " + BaseHeatTracker.pendingCount() + " queued find(s).");
                return SUCCESS;
            }));
    }
}
