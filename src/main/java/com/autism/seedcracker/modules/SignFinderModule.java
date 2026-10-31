package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.Objects;

import com.autism.seedcracker.finder.ScanCursor;
import com.autism.seedcracker.finder.landmark.SignEvidence;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.StringSetting;
import dihclient.modules.Module;
import dihclient.util.DihClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sign Finder.
 *
 * Highlights signs and reads both sides. Signs can also occur in generated structures;
 * text is a useful landmark, not proof of a player base.
 */
public final class SignFinderModule extends Module {

    private final BoolSetting readText = add(new BoolSetting("read-text", "Read text in chat", true)
        .description("Print each new sign's text to chat.").group("General"));
    private final StringSetting keywords = add(new StringSetting("keywords", "Keyword filter", "")
        .description("Comma-separated keywords; blank = report every sign, else only matching ones.")
        .group("General"));
    private final BoolSetting tracers = add(new BoolSetting("tracer", "Tracer", false)
        .description("Tracer to each found sign.").group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xC04DE1FF)
        .description("Highlight colour.").group("Render"));

    private final SignEvidence evidence = new SignEvidence();
    private final ScanCursor scan = new ScanCursor();
    private Object world;
    private String keywordSetting;
    private boolean readSetting;
    private LevelChunk pending;
    private java.util.List<BlockPos> positions = java.util.List.of();
    private int positionIndex;
    private final java.util.Map<BlockPos, String> nextSigns = new HashMap<>();

    public SignFinderModule() {
        super(SeedcrackerAddon.ID + ":sign-finder", "Sign Finder",
            "Highlights signs and reads both sides; signs are landmarks, not guaranteed base evidence.");
    }

    /** Grouped into the Finders hub (it's a simple on/off tool); hidden from the main menu. */
    @Override
    public boolean showInModuleMenu() {
        return false;
    }

    @Override public void onEnable() { clear(); }
    @Override public void onDisable() { clear(); }

    private void clear() {
        BlockEspRenderer.clear(id());
        evidence.clear();
        scan.reset();
        world = null;
        pending = null;
        positions = java.util.List.of();
        positionIndex = 0;
        nextSigns.clear();
        kwCacheKey = null;
        kwCache = java.util.List.of();
    }

    @Override
    public void onGameLeft() {
        clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { clear(); return; }
        if (world != mc.level || !Objects.equals(keywordSetting, keywords.get()) || readSetting != readText.get()) {
            clear();
            world = mc.level;
            keywordSetting = keywords.get();
            readSetting = readText.get();
        }
        var centre = mc.player.chunkPosition();
        evidence.prune(pos -> Math.abs((pos.getX() >> 4) - centre.x()) <= 6
            && Math.abs((pos.getZ() >> 4) - centre.z()) <= 6
            && liveSign(mc, pos));
        if (pending != null && (Math.abs((long) pending.getPos().x() - centre.x()) > 6
            || Math.abs((long) pending.getPos().z() - centre.z()) > 6
            || mc.level.getChunkSource().getChunk(pending.getPos().x(), pending.getPos().z(), false) != pending)) {
            pending = null;
            positions = java.util.List.of();
            nextSigns.clear();
        }
        int budget = 256;
        for (int completed = 0; completed < 4 && budget > 0;) {
            if (pending == null) {
                var batch = scan.nextBatch(mc, 6, 500, 1);
                if (batch.isEmpty()) break;
                pending = batch.getFirst();
                // ponytail: one O(block-entities) key snapshot avoids mutable iterators; use a chunk-change index if this copy dominates.
                positions = new java.util.ArrayList<>(pending.getBlockEntities().keySet());
                positionIndex = 0;
                nextSigns.clear();
            }
            while (positionIndex < positions.size() && budget > 0) {
                BlockPos pos = positions.get(positionIndex++);
                budget--;
                BlockEntity be = pending.getBlockEntities().get(pos);
                if (!(be instanceof SignBlockEntity sign) || be.isRemoved()) continue;
                String text = SignEvidence.text(com.autism.seedcracker.compat.McCompat.signText(sign, true), com.autism.seedcracker.compat.McCompat.signText(sign, false));
                if (matchesKeywords(text)) nextSigns.put(pos.immutable(), text);
            }
            if (positionIndex < positions.size()) break;
            nextSigns.keySet().removeIf(pos -> !liveSign(mc, pos));
            evidence.replaceChunk(pending.getPos(), nextSigns, readText.get()).forEach((pos, text) ->
                DihClientMessaging.sendPrefixed("§b[Sign] §f\"" + text + "\" at "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            pending = null;
            positions = java.util.List.of();
            nextSigns.clear();
            completed++;
        }
        BlockEspRenderer.feed(id(), evidence.positions(), color.get(), tracers.get(), true);
    }

    private static boolean liveSign(Minecraft mc, BlockPos pos) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk == null) return false;
        BlockEntity be = chunk.getBlockEntities().get(pos);
        return be instanceof SignBlockEntity && !be.isRemoved();
    }

    private String kwCacheKey;
    private java.util.List<String> kwCache = java.util.List.of();

    private boolean matchesKeywords(String text) {
        String raw = keywords.get();
        if (raw == null || raw.isBlank()) return true;
        if (!raw.equals(kwCacheKey)) {
            kwCacheKey = raw;
            java.util.List<String> next = new java.util.ArrayList<>();
            for (String kw : raw.split(",")) {
                String k = kw.trim().toLowerCase(java.util.Locale.ROOT);
                if (!k.isEmpty()) next.add(k);
            }
            kwCache = next;
        }
        if (kwCache.isEmpty()) return true;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String k : kwCache) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    @Override
    public String info() {
        return evidence.positions().isEmpty() ? "" : evidence.positions().size() + " signs";
    }
}
