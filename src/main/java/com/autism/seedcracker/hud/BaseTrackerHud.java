package com.autism.seedcracker.hud;

import java.util.List;

import dihclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.BaseTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Base Tracker HUD.
 *
 * An on-screen list of the nearest flagged bases reported by the finder modules (via the shared
 * {@link BaseTracker} registry), sorted nearest-first. Each row shows the finder's name, the
 * base's coordinates, the distance to it, and a confidence bar - so you can see at a glance which
 * flagged chunk is worth checking first.
 */
public final class BaseTrackerHud implements HudElementProvider {
    private static final int PAD = 4;
    private static final int LINE = 10;
    private static final int MAX_ROWS = 6;
    private static final int BG = 0x90101018;
    private static final int TITLE_COLOR = 0xFF55FFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int DIM_COLOR = 0xFFAAAAAA;

    @Override public String id() { return SeedcrackerAddon.ID + ":base-tracker"; }
    @Override public String label() { return "Base Tracker"; }
    @Override public String description() { return "Recent nearby finder candidates with source, coordinates, distance and heuristic score—not confirmed bases."; }

    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 60; }

    @Override
    public int width() {
        return 230;
    }

    @Override
    public int height() {
        return PAD * 2 + LINE * (MAX_ROWS * 2 + 1);
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (font == null || mc.player == null || mc.level == null || alpha <= 0) return;

        List<BaseTracker.Fused> bases = BaseTracker.fusedNearest(mc.player.getX(), mc.player.getZ(), MAX_ROWS);
        int w = width();
        int h = height();
        ctx.fill(x, y, x + w, y + h, opacity(BG, alpha));
        ctx.text(font, "Finder candidates", x + PAD, y + 2, opacity(TITLE_COLOR, alpha));
        ctx.text(font, "Score /100", x + w - PAD - font.width("Score /100"), y + 2, opacity(DIM_COLOR, alpha));

        if (bases.isEmpty()) {
            ctx.text(font, "No recent candidates", x + PAD, y + 2 + LINE, opacity(DIM_COLOR, alpha));
            return;
        }

        int row = 1;
        for (BaseTracker.Fused e : bases) {
            double dist = Math.sqrt(e.distSq());
            int ly = y + 2 + LINE * row;
            int barW = 30, bx = x + w - PAD - barW;
            String source = font.plainSubstrByWidth(e.sources(), w - PAD * 2 - 60);
            ctx.text(font, source, x + PAD, ly, opacity(TEXT_COLOR, alpha));
            String score = Integer.toString(e.confidence());
            ctx.text(font, score, bx - 4 - font.width(score), ly, opacity(DIM_COLOR, alpha));
            String location = String.format(java.util.Locale.ROOT, "%d, %d   %.0f m", e.blockX(), e.blockZ(), dist);
            ctx.text(font, font.plainSubstrByWidth(location, w - PAD * 2), x + PAD, ly + LINE, opacity(DIM_COLOR, alpha));
            int filled = Math.round((Math.max(0, Math.min(100, e.confidence())) / 100f) * barW);
            ctx.fill(bx, ly + 1, bx + barW, ly + 5, opacity(0xFF333340, alpha));
            ctx.fill(bx, ly + 1, bx + filled, ly + 5, opacity(confidenceColor(e.confidence()), alpha));
            row += 2;
        }
    }

    private static int opacity(int color, float alpha) {
        return Math.round((color >>> 24) * Math.clamp(alpha, 0f, 1f)) << 24 | color & 0xffffff;
    }

    private static int confidenceColor(int c) {
        if (c >= 70) return 0xFF55FF55; // high = green
        if (c >= 40) return 0xFFFFFF55; // mid = yellow
        return 0xFFFF5555;              // low = red
    }
}
