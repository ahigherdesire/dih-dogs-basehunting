// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.modules;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.bedrock.SeedSeedProvider;
import com.autism.seedcracker.spectre.chunks.seedscan.GeodePredictor;
import com.autism.seedcracker.spectre.palette.GeodeSeedSelection;
import com.autism.seedcracker.spectre.palette.SeedGeodePreview;
import com.autism.seedcracker.corz.CorzGeodeContextCache;
import com.autism.seedcracker.corz.CorzGeodeMorphology;
import com.autism.seedcracker.corz.CorzGeodeSampler;
import com.autism.seedcracker.finder.ScanCursor;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.palette.AmethystCache;
import com.autism.seedcracker.spectre.palette.AmethystGeometry;
import com.autism.seedcracker.spectre.palette.AmethystScan;
import com.autism.seedcracker.spectre.palette.AmethystScan.Method;
import com.autism.seedcracker.spectre.palette.PaletteScan;
import com.autism.seedcracker.util.RelogPersistence;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.EnumSetting;
import dihclient.api.module.IntSetting;
import dihclient.api.module.StringSetting;
import dihclient.modules.Module;
import dihclient.util.DihClientMessaging;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Spectre AmethystBypass (d5a7dd3de0be), preserving the existing amethyst-esp registry id. */
public final class AmethystEspModule extends Module {
    private static final String ID = SeedcrackerAddon.ID + ":amethyst-esp";
    private static final String HINT_ID = ID + ":hints";
    private static final String BLOCK_ID = ID + ":blocks";
    private static final String SEED_BOX_ID = ID + ":seed-boxes";
    private static final String SEED_BLOCK_ID = ID + ":seed-blocks";
    public enum SeedStyle { ATTEMPTS, BOUNDS, BLOCKS, BOTH }
    private final BoolSetting showHints = add(new BoolSetting("show-uncertain-hints", "Show uncertain hints (not exact geodes)", AmethystGeometry.DEFAULT_SHOW_HINTS)
        .description("Off shows only bounds of received amethyst blocks. On adds optional amber section hints, not precise hidden geodes or base evidence.").group("Detection"));
    private final EnumSetting<Method> method = add(new EnumSetting<>("method", "Method", Method.BYPASS, Method.values(),
        value -> switch (value) {
            case BYPASS -> "Observed; optional palette hints";
            case LIGHT -> "Observed; optional light hints";
            case ANBS_SCAN -> "Observed; optional glow hints";
        }).group("Detection").visibleWhen(showHints::get));
    private final IntSetting simDistance = add(new IntSetting("sim-distance", "Scan radius (chunks)", 8, 1, 32, 1).group("Detection"));
    private final BoolSetting uncorroborated = add(new BoolSetting("uncorroborated-palette", "Uncorroborated palette hints (no proof)", false)
        .group("Detection").visibleWhen(() -> showHints.get() && method.get() == Method.BYPASS));
    private final IntSetting minCells = add(new IntSetting("min-cells", "Minimum glow cells per section", 12, 1, 100, 1)
        .group("Detection").visibleWhen(() -> showHints.get() && method.get() == Method.ANBS_SCAN));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Sections per tick (work limit)", 4, 1, 16, 1).group("Performance"));
    private final BoolSetting chatAlert = add(new BoolSetting("chat-alert", "Chat alert", false).group("General"));
    private final BoolSetting blockEsp = add(new BoolSetting("block-esp", "Render markers", true).group("Render"));
    private final BoolSetting individual = add(new BoolSetting("individual-block-outlines", "Individual block outlines (max 4096)", AmethystGeometry.DEFAULT_BLOCK_OUTLINES).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer / anchor on observed blocks only", false).group("Render"));
    private final BoolSetting fill = add(new BoolSetting("fill", "Fill observed formation bounds", true).group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Observed formation color", -3636481).group("Render"));
    private final ColorSetting hintColor = add(new ColorSetting("hint-color", "Unconfirmed 16x16x16 outline color", 0xFFEAB75A).group("Render"));
    private final BoolSetting corzContext = add(new BoolSetting("corz-geode-context", "Geode morphology context (quiet)", false)
        .description("Optional numeric chunk context in module info: received amethyst, shell material and air edges at Y -64..48. Unknown data stays unknown. No new boxes, alerts, mining claims or base confidence.").group("Context"));
    private final BoolSetting seedEnabled = add(new BoolSetting("seed-preview", "Seed-based preview (predictions)", false)
        .description("Optional local vanilla forecast for this Minecraft build. Cyan outlines are not observed blocks or base proof; structures, older chunks and player edits can differ.").group("Seed prediction"));
    private final StringSetting manualSeed = add(new StringSetting("seed", "World seed (blank = automatic)", "")
        .description("Signed 64-bit number. Blank uses a matching cracked seed, then the last verified seed. Kept private; never sent to Chunkbase or the server.")
        .group("Seed prediction").visibleWhen(seedEnabled::get));
    private final StringSetting rememberedSeed = add(new StringSetting("seed-last-verified", "Last verified seed", "")
        .group("Seed prediction").visibleWhen(() -> false));
    private final BoolSetting unverifiedSeed = add(new BoolSetting("seed-unverified-manual", "Allow unverified manual seed", false)
        .description("Explicit manual-only fallback for unavailable/masked seed hashes. Remains unverified and is never saved as a world-matched seed.")
        .group("Seed prediction").visibleWhen(() -> seedEnabled.get() && manualSeed.get() != null && !manualSeed.get().isBlank()));
    private final EnumSetting<SeedStyle> seedStyle = add(new EnumSetting<>("seed-style", "Prediction display", SeedStyle.BOUNDS, SeedStyle.values(), value -> switch (value) {
        case ATTEMPTS -> "Placement attempts (approximate envelopes)";
        case BOUNDS -> "Reconstructed amethyst bounds";
        case BLOCKS -> "Reconstructed block outlines";
        case BOTH -> "Bounds + block outlines";
    }).description("Detailed modes run isolated terrain/geode simulation and may take several seconds. No live-world or hidden-base guarantee.")
        .group("Seed prediction").visibleWhen(seedEnabled::get));
    private final IntSetting seedRadius = add(new IntSetting("seed-radius", "Prediction radius (chunks)", 4, 1, SeedGeodePreview.MAX_RADIUS, 1)
        .description("Background work only; no forced client loads. Retains 65,536 detailed cells; renders at most 128 bounds / 4,096 block outlines, omitting whole geodes at limits.")
        .group("Seed prediction").visibleWhen(seedEnabled::get));
    private final ColorSetting seedColor = add(new ColorSetting("seed-color", "Predicted outline color", 0xFF3ED7C8)
        .group("Seed prediction").visibleWhen(seedEnabled::get));
    private final java.util.concurrent.atomic.AtomicLong seedTagVersion = new java.util.concurrent.atomic.AtomicLong();
    private SeedGeodePreview seedPreview, stoppingSeedPreview;
    private ClientPacketListener seedConnection;
    private long activeSeedTags = -1;
    private String seedText;
    private int seedEditTicks;
    private volatile String seedInfo = "";
    private final ScanCursor scan = new ScanCursor();
    private final AmethystScan detector = new AmethystScan();
    private final AmethystCache cache = new AmethystCache();
    private final CorzGeodeContextCache morphology = new CorzGeodeContextCache();
    private CorzGeodeMorphology.Builder pendingMorphology;
    private boolean lastCorzContext, evaluateMorphologyNext;
    private volatile String morphologyInfo = "";
    private final Set<BlockPos> pendingBlocks = new LinkedHashSet<>();
    private final Set<Integer> pendingHints = new LinkedHashSet<>();
    private boolean pendingHintsValid = true;
    private boolean pendingOversized;
    private boolean preferDirty = true;
    private LevelChunk pending;
    private int nextSection;
    private ClientLevel level;
    private Method lastMethod;
    private int lastRadius;
    private int lastMinimum;
    private boolean lastUncorroborated;
    private boolean lastShowHints;
    private boolean unloadHook;

    public AmethystEspModule() {
        super(ID, "Amethyst ESP", "Observed amethyst formation bounds, optional amber hints and separate cyan seed forecasts. Seed shapes are local simulations, not confirmed server blocks. Natural amethyst is not player/base proof.");
    }

    @Override public void onEnable() {
        reset();
        BlockEspRenderer.init();
        if (!unloadHook) {
            unloadHook = true;
            ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (world != level) return;
                ChunkPos pos = chunk.getPos();
                boolean changed = cache.unload(pos);
                cancelAffected(pos);
                scan.reset();
                if (changed) render();
            });
            CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
                if (!client) return;
                seedTagVersion.incrementAndGet();
                Minecraft mc = Minecraft.getInstance();
                if (mc.isSameThread()) stopSeedPreview(); else mc.execute(this::stopSeedPreview);
            });
        }
    }
    @Override public void onDisable() { reset(); }
    @Override protected void onOptionValueChanged(String settingId) {
        if ("seed-preview".equals(settingId) && !seedEnabled.get()) {
            stopSeedPreview(); seedInfo = ""; seedText = null;
        }
    }
    @Override public void onGameLeft() {
        reset();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    private void reset() {
        stopSeedPreview();
        seedInfo = ""; seedText = null; seedEditTicks = 0; seedConnection = null; activeSeedTags = -1;
        cache.clear();
        clearScan();
        level = null;
        lastMethod = null;
    }
    private void cancelPending() {
        pending = null;
        pendingMorphology = null;
        nextSection = 0;
        pendingBlocks.clear();
        pendingHints.clear();
        pendingHintsValid = true;
        pendingOversized = false;
    }
    private void invalidatePendingHints(ChunkPos pos) {
        if (pending != null && Math.abs((long) pending.getPos().x() - pos.x()) <= 1
            && Math.abs((long) pending.getPos().z() - pos.z()) <= 1) {
            pendingHints.clear();
            pendingHintsValid = false;
        }
    }
    private void cancelAffected(ChunkPos pos) {
        invalidatePendingHints(pos);
        invalidateMorphology(pos);
        if (pending != null && pending.getPos().equals(pos)) cancelPending();
    }
    private void invalidateMorphology(ChunkPos pos) {
        if (!corzContext.get()) return;
        var key = new CorzGeodeMorphology.Key(pos.x(), pos.z());
        morphology.invalidateAround(key);
        if (pendingMorphology != null && pendingMorphology.key().touches(key)) pendingMorphology = null;
        morphologyInfo = "";
    }
    private void clearMorphology() {
        morphology.clear();
        pendingMorphology = null;
        morphologyInfo = "";
        evaluateMorphologyNext = false;
    }
    private void pruneMorphology(Predicate<ChunkPos> keep) {
        morphology.prune(key -> {
            ChunkPos pos = new ChunkPos(key.x(), key.z());
            return keep.test(pos) && cache.results().containsKey(pos);
        });
    }
    private void clearRender() {
        BlockEspRenderer.clear(ID); BlockEspRenderer.clear(HINT_ID); BlockEspRenderer.clear(BLOCK_ID);
        clearSeedRender();
    }
    private void clearSeedRender() {
        BlockEspRenderer.clear(SEED_BOX_ID); BlockEspRenderer.clear(SEED_BLOCK_ID);
    }
    private void stopSeedPreview() {
        if (seedPreview != null) {
            seedPreview.close();
            stoppingSeedPreview = seedPreview;
            seedPreview = null;
        }
        clearSeedRender();
    }
    private void clearScan() {
        cache.clearResults();
        clearMorphology();
        cancelPending();
        preferDirty = true;
        scan.reset();
        clearRender();
    }
    private void sync(Minecraft mc) {
        if (level != mc.level) { reset(); level = mc.level; }
        if (lastMethod != method.get() || lastRadius != simDistance.get() || lastMinimum != minCells.get()
            || lastUncorroborated != uncorroborated.get() || lastShowHints != showHints.get()) {
            clearScan();
            lastMethod = method.get();
            lastRadius = simDistance.get();
            lastMinimum = minCells.get();
            lastUncorroborated = uncorroborated.get();
            lastShowHints = showHints.get();
        }
        if (lastCorzContext != corzContext.get()) {
            // Toggling context must not erase the existing observed formation boxes.
            clearMorphology();
            scan.reset();
            lastCorzContext = corzContext.get();
        }
    }

    /** Takes ownership of a fresh packet read-buffer wrapper; releases it on every path. */
    public void onChunkData(FriendlyByteBuf buffer, int chunkX, int chunkZ, int sectionCount, int minSectionY) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) throw new IllegalStateException("Palette packets must be applied on the client thread");
            if (!isEnabled()) return;
            sync(mc);
            if (level == null || !PaletteScan.validChunk(chunkX, chunkZ) || !level.getChunkSource().hasChunk(chunkX, chunkZ)) return;
            var result = PaletteScan.read(buffer.nioBuffer(buffer.readerIndex(), buffer.readableBytes()), sectionCount, minSectionY,
                id -> AmethystScan.bud(Block.stateById(id)));
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            boolean changed = cache.packet(pos, result);
            cancelAffected(pos);
            if (changed) render();
        } finally {
            buffer.release();
        }
    }

    public void onBlockUpdate(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Block updates must be applied on the client thread");
        if (!isEnabled()) return;
        sync(mc);
        if (level == null || pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()
            || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        boolean amethyst = AmethystScan.amethyst(level.getBlockState(pos));
        boolean changed = cache.block(pos, amethyst);
        ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        invalidatePendingHints(chunk);
        invalidateMorphology(chunk);
        if (pending != null && pending.getPos().equals(chunk) && !pendingOversized
            && (pos.getY() >> 4) < pending.getMinSectionY() + nextSection) {
            if (amethyst) pendingBlocks.add(pos.immutable()); else pendingBlocks.remove(pos);
            if (pendingBlocks.size() > AmethystScan.MAX_BLOCKS_PER_CHUNK) {
                pendingBlocks.clear(); pendingOversized = true;
            }
        }
        if (changed) render();
    }

    /** Call after queued block-light data has actually been applied, not just after receiving its packet. */
    public void onLightApplied(ClientLevel world, int chunkX, int chunkZ) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Light updates must be applied on the client thread");
        if (!isEnabled()) return;
        sync(mc);
        if (world == null || world != level || !PaletteScan.validChunk(chunkX, chunkZ)) return;
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        boolean changed = cache.invalidateAround(pos);
        invalidatePendingHints(pos);
        if (changed) render();
    }

    @Override public void tick() {
        if (!isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        sync(mc);
        if (level == null || mc.player == null) { clearRender(); cancelPending(); clearMorphology(); stopSeedPreview(); seedInfo = ""; return; }
        ChunkPos center = mc.player.chunkPosition();
        Predicate<ChunkPos> keep = pos -> level.getChunkSource().hasChunk(pos.x(), pos.z())
            && Math.abs((long) pos.x() - center.x()) <= simDistance.get()
            && Math.abs((long) pos.z() - center.z()) <= simDistance.get();
        cache.prune(keep);
        if (pending != null && (!keep.test(pending.getPos())
            || level.getChunkSource().getChunkNow(pending.getPos().x(), pending.getPos().z()) != pending)) cancelPending();
        long deadline = System.nanoTime() + 2_000_000;
        int remaining = chunksPerTick.get();
        if (corzContext.get()) {
            if (!mc.isSameThread()) throw new IllegalStateException("Morphology work must run on the client thread");
            pruneMorphology(keep);
            if (morphology.hasPending()) {
                evaluateMorphologyNext = !evaluateMorphologyNext;
                // With a one-section budget, alternate so context cannot starve observed scans.
                if ((remaining > 1 || evaluateMorphologyNext)
                    && morphology.advance(CorzGeodeContextCache.EVALUATION_CELLS_PER_TICK, deadline) > 0) remaining--;
            }
        }
        int inspections = remaining * 32;
        // ponytail: a single 4096-cell section is the budget floor; split cells if profiling requires a tighter limit.
        while (remaining > 0 && inspections-- > 0 && System.nanoTime() < deadline) {
            if (pending == null) {
                var batch = preferDirty ? List.<LevelChunk>of() : scan.nextBatch(mc, simDistance.get(), 1000, 1);
                ChunkPos dirty = batch.isEmpty() ? cache.pollDirty() : null;
                if (dirty != null) {
                    preferDirty = false;
                    if (!keep.test(dirty)) continue;
                    pending = level.getChunkSource().getChunkNow(dirty.x(), dirty.z());
                } else {
                    if (batch.isEmpty()) batch = scan.nextBatch(mc, simDistance.get(), 1000, 1);
                    if (batch.isEmpty()) break;
                    preferDirty = true;
                    LevelChunk candidate = batch.getFirst();
                    if (keep.test(candidate.getPos())
                        && level.getChunkSource().getChunkNow(candidate.getPos().x(), candidate.getPos().z()) == candidate) pending = candidate;
                }
                if (pending == null) continue;
            }
            if (corzContext.get() && nextSection == 0 && pendingMorphology == null)
                pendingMorphology = new CorzGeodeMorphology.Builder(pending.getPos().x(), pending.getPos().z());
            if (nextSection < pending.getSections().length) {
                int index = nextSection++;
                int sectionY = pending.getMinSectionY() + index;
                var section = pending.getSections()[index];
                boolean worked = pendingMorphology != null && !pendingOversized && CorzGeodeMorphology.intersectsSection(sectionY);
                if (worked && !CorzGeodeSampler.captureSection(mc, level, pending, index, pendingMorphology)) pendingMorphology = null;
                boolean unused = cache.palette(pending.getPos()).contains(sectionY);
                if (!pendingOversized && ((section != null && section.getStates().maybeHas(AmethystScan::amethyst))
                    || (showHints.get() && method.get() == Method.BYPASS && uncorroborated.get() && unused))) {
                    var result = detector.section(level, pending, index, method.get(), unused, uncorroborated.get(), minCells.get(),
                        showHints.get() && pendingHintsValid);
                    worked = true;
                    if (pendingBlocks.size() + result.blocks().size() > AmethystScan.MAX_BLOCKS_PER_CHUNK) {
                        pendingBlocks.clear(); pendingHints.clear(); pendingOversized = true;
                    } else {
                        pendingBlocks.addAll(result.blocks());
                        pendingHints.addAll(result.hints());
                    }
                }
                if (worked) remaining--;
            }
            if (nextSection >= pending.getSections().length) {
                ChunkPos pos = pending.getPos();
                if (pendingOversized) cache.rejectOversized(pos);
                else cache.store(pos, new AmethystScan.Result(pendingBlocks, pendingHints));
                if (pendingMorphology != null && !pendingOversized && !pendingBlocks.isEmpty() && cache.results().containsKey(pos))
                    morphology.offer(pendingMorphology.freeze());
                cancelPending();
            }
        }
        if (corzContext.get()) {
            pruneMorphology(keep);
            morphologyInfo = morphology.summary(new CorzGeodeMorphology.Key(center.x(), center.z()));
        }
        int fresh = cache.takeNotifications();
        if (fresh > 0 && chatAlert.get())
            DihClientMessaging.sendPrefixed("§d[AmethystESP] §f" + fresh + " new amethyst formation observation(s)"
                + " §7(received extent only; natural amethyst is not player/base proof)");
        render();
        updateSeedPreview(mc);
    }

    private void updateSeedPreview(Minecraft mc) {
        if (stoppingSeedPreview != null && stoppingSeedPreview.stopped()) stoppingSeedPreview = null;
        if (!seedEnabled.get() || !blockEsp.get() || mc.getConnection() == null) {
            stopSeedPreview();
            seedInfo = seedEnabled.get() ? " / seed preview paused" : "";
            return;
        }
        if ((!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
                && !level.dimensionTypeRegistration().is(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD))
            || level.getMinY() != -64 || level.getHeight() != 384 || level.isDebug() || level.getLevelData().isFlat) {
            stopSeedPreview(); seedInfo = " / seed: default-noise Overworld only"; return;
        }
        String text = manualSeed.get() == null ? "" : manualSeed.get();
        if (!text.equals(seedText)) { stopSeedPreview(); seedText = text; seedEditTicks = 0; }
        if (seedEditTicks < 10) { seedEditTicks++; seedInfo = " / seed: waiting for edits"; return; }
        long tags = seedTagVersion.get();
        if (seedConnection != mc.getConnection() || activeSeedTags != tags) {
            stopSeedPreview(); seedConnection = mc.getConnection(); activeSeedTags = tags;
        }
        var choice = GeodeSeedSelection.choose(text, text.isBlank() ? SeedSeedProvider.crackedSeedPublic() : null,
            rememberedSeed.get(), SeedSeedProvider.hashedSeed(level), unverifiedSeed.get());
        if (!choice.usable()) {
            stopSeedPreview();
            seedInfo = " / seed: " + switch (choice.status()) {
                case INVALID_MANUAL -> "enter a signed 64-bit number";
                case MISMATCH -> "no seed matching this world";
                case WAITING_FOR_WORLD -> "waiting for world fingerprint";
                default -> "enter or crack a seed";
            };
            return;
        }
        if (choice.verified() && !choice.remembered().equals(rememberedSeed.get())) rememberedSeed.set(choice.remembered());
        if (seedPreview != null && seedPreview.seed() != choice.seed()) stopSeedPreview();
        if (seedPreview == null && stoppingSeedPreview != null) { seedInfo = " / seed: stopping previous simulation"; return; }
        ChunkPos center = mc.player.chunkPosition();
        boolean detail = seedStyle.get() != SeedStyle.ATTEMPTS;
        if (seedPreview == null) seedPreview = new SeedGeodePreview(choice.seed(), center, seedRadius.get(), detail);
        else seedPreview.request(center, seedRadius.get(), detail);
        var frame = seedPreview.frame();
        int generated = 0, rejected = 0, limited = 0;
        List<net.minecraft.world.phys.AABB> boxes = new java.util.ArrayList<>();
        Set<BlockPos> blocks = new LinkedHashSet<>();
        for (var view : frame.views()) {
            if (Math.abs((long) view.chunk().x() - center.x()) > seedRadius.get()
                || Math.abs((long) view.chunk().z() - center.z()) > seedRadius.get()) continue;
            if (view.status() == GeodePredictor.Status.TERRAIN_REJECTED) rejected++;
            if (view.limited()) limited++;
            if (seedStyle.get() == SeedStyle.ATTEMPTS) {
                if (boxes.size() < 128) boxes.add(seedBox(view.envelope())); else limited++;
            } else if (view.status() == GeodePredictor.Status.GENERATED_IN_SIMULATION) {
                generated++;
                if (seedStyle.get() != SeedStyle.BLOCKS && view.amethystBounds() != null) {
                    if (boxes.size() < 128) boxes.add(seedBox(view.amethystBounds())); else limited++;
                }
                if (seedStyle.get() != SeedStyle.BOUNDS) {
                    if (blocks.size() + view.blocks().size() <= 4096) blocks.addAll(view.blocks()); else limited++;
                }
            }
        }
        BlockEspRenderer.feedBoxes(SEED_BOX_ID, boxes, seedColor.get(), 0);
        BlockEspRenderer.feed(SEED_BLOCK_ID, blocks, seedColor.get(), false, false);
        String source = choice.verified() ? choice.source().name().toLowerCase(java.util.Locale.ROOT) + ", hash matches" : "manual, unverified";
        seedInfo = " / seed (" + source + "): " + frame.scanned() + "/" + frame.total() + " chunks, "
            + (detail ? generated + " simulated shapes, " + rejected + " rejected" : frame.views().size() + " placement attempts")
            + ", " + frame.pending() + " pending / " + frame.status() + (limited > 0 ? " / detail/render limited" : "");
    }

    private static net.minecraft.world.phys.AABB seedBox(GeodePredictor.Bounds bounds) {
        return new net.minecraft.world.phys.AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
            bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0);
    }

    private void render() {
        if (!isEnabled() || level == null || !blockEsp.get()) { clearRender(); return; }
        var geometry = cache.geometry();
        BlockEspRenderer.feedBoxes(ID, geometry.boxes(), color.get(), fill.get() ? 0x2E : 0);
        if (individual.get() || tracer.get())
            BlockEspRenderer.feed(BLOCK_ID, individual.get() ? geometry.outlines() : geometry.anchors(), color.get(), tracer.get(), false);
        else BlockEspRenderer.clear(BLOCK_ID);
        if (showHints.get()) BlockEspRenderer.feedBoxes(HINT_ID, cache.hints(true), hintColor.get(), 0);
        else BlockEspRenderer.clear(HINT_ID);
    }

    @Override public String info() {
        var geometry = cache.geometry();
        String info = geometry.boxes().size() + "/" + geometry.formations() + " observed formations / "
            + geometry.observed().size() + " blocks (received extent)";
        if (individual.get()) info += " / " + geometry.outlines().size() + " block outlines (max 4096)";
        if (showHints.get()) info += " / " + cache.hints(true).size() + " uncertain sections";
        if (cache.limited()) info += " / coverage limited: whole chunks omitted";
        if (corzContext.get()) info += morphologyInfo;
        if (seedEnabled.get()) info += seedInfo;
        return info;
    }

    @Override public boolean settingsShareable() { return false; }
}
