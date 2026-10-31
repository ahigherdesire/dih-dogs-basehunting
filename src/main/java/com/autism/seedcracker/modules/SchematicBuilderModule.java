package com.autism.seedcracker.modules;

import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.util.tunnel.LegitMovement;
import com.autism.seedcracker.schematic.SchematicBuildState;
import com.autism.seedcracker.schematic.SchematicFiles;
import com.autism.seedcracker.schematic.SchematicLoader;
import com.autism.seedcracker.gui.schematic.SchematicSetupScreen;

import dihclient.api.module.ActionSetting;
import dihclient.api.module.KeybindSetting;
import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.EnumSetting;
import dihclient.api.module.IntSetting;
import dihclient.api.module.StringSetting;
import dihclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Schematic Builder.
 *
 * Loads a schematic file (.litematic / .schem / legacy .schematic), renders a layer-by-layer
 * ghost preview in the world, and builds only after explicit Start. The auto-placer is deliberately "legit":
 * it walks to within vanilla reach, eases its view onto the placement face with the
 * {@link LegitMovement} human-mouse engine (no instant head-snap), and places through the real
 * {@code useItemOn} interaction so the packets match a real player right-clicking blocks.
 *
 * Ghost colours: green = placed, cyan = current layer, red = missed in a past layer,
 * grey (distance-faded) = future layers.
 *
 * Port of the Water Client "SchematicBuilder" file-loading + render logic to the AUTISM API
 * (Mojang 26.2), with the placement rewritten to use legit rotation + vanilla interactions.
 */
public final class SchematicBuilderModule extends Module {

    private static final double MAX_REACH = 4.5;
    private static final int GHOST_LIMIT = 4096;

    public SchematicBuilderModule() {
        super(SeedcrackerAddon.ID + ":schematic-builder", "Schematic Builder",
            "Preview first. Open setup to choose a file, set an origin and review materials. Only Start builds.");
    }

    // ---- settings ----
    private final ActionSetting openSetup = add(new ActionSetting("open-setup", "Guided setup", this::openSetup)
        .buttonLabel("Open setup").description("File -> origin / rotation -> materials -> explicit Start. Opening pauses building.")
        .group("1. Setup").availableOffline());
    private final KeybindSetting setupKey = add(new KeybindSetting("setup-key", "Open setup key", -1)
        .description("Optional shortcut, including while disabled. Release then press in gameplay; never starts building.")
        .group("1. Setup").availableOffline());
    private final StringSetting schematicPath = add(new StringSetting("schematic-path", "File path (or use Open setup)", "")
        .description("Paste a .litematic / .schem / .schematic path. Relative paths start in the DIH config folder.")
        .group("1. Setup").availableOffline());
    private final BoolSetting load = add(new BoolSetting("load", "Load preview (one shot)", false)
        .description("Loads without walking, placing or mining. Invalid files keep the previous preview.").group("1. Setup").availableOffline());
    private final StringSetting recentFiles = add(new StringSetting("recent-files", "Recent schematics", "")
        .visibleWhen(() -> false).keepOnReset().availableOffline());
    private final BoolSetting setOrigin = add(new BoolSetting("set-origin", "Use crosshair / feet as origin", false)
        .description("One shot. Anchor above the looked-at block, or at your feet. Pauses and resets progress.").group("2. Origin"));
    private final ActionSetting startAction = add(new ActionSetting("start", "Start / resume", this::openReview)
        .buttonLabel("Review & Start").description("Review materials and confirm Start in setup. Never starts from saved settings.")
        .group("3. Controls").availableOffline());
    private final ActionSetting pauseAction = add(new ActionSetting("pause", "Pause", () -> pauseBuild("Paused by you. Press Start to continue."))
        .buttonLabel("Pause").group("3. Controls").availableOffline());
    private final ActionSetting stopAction = add(new ActionSetting("stop", "Stop", this::stopBuild)
        .buttonLabel("Stop").description("Release controls and reset the pass. Keep the file and preview.")
        .group("3. Controls").availableOffline());
    private final BoolSetting build = add(new BoolSetting("build", "Running (use Review & Start)", false)
        .readonlySummary().description("Runtime status only. A saved true value cannot start automation; false pauses it.")
        .group("3. Controls"));
    private final ActionSetting safePreset = add(new ActionSetting("safe-preset", "Safe placement preset", this::applySafePreset)
        .buttonLabel("Apply safe preset").description("Pause; visible aim and human walking; no mining, bridging or chest access.")
        .group("3. Controls").availableOffline());
    public enum BuildOrder { ADAPTIVE, LAYERED }
    private final EnumSetting<BuildOrder> buildOrder = add(new EnumSetting<>("build-order", "Build order", BuildOrder.LAYERED, BuildOrder.values())
        .description("LAYERED waits for every block and pauses after 8 failed passes. ADAPTIVE advances at 95%; inspect missed blocks afterwards.")
        .group("Advanced: Placement").visibleWhen(() -> bool("advanced")));
    private final BoolSetting renderGhost = add(new BoolSetting("render-ghost", "Ghost preview", true)
        .description("Show a yellow origin, full bounds and up to 4096 ghost boxes; enable the module for world preview.").group("2. Origin"));
    private final BoolSetting restock = add(new BoolSetting("restock", "Allow nearby chest restock", false)
        .description("Opt-in: take missing blocks from reachable containers. Pauses if items must be moved from inventory to hotbar.")
        .group("Advanced: Restock").visibleWhen(() -> bool("advanced")));
    private final IntSetting chestRange = add(new IntSetting("chest-range", "Chest range", 12, 4, 32, 1)
        .description("Search radius; actual openings are always capped to vanilla reach (4.5 blocks).")
        .group("Advanced: Restock").visibleWhen(() -> bool("advanced") && restock.get()));
    private final IntSetting chestDelay = add(new IntSetting("chest-delay", "Chest action delay", 4, 0, 20, 1)
        .description("Ticks between opening / taking from a chest.")
        .group("Advanced: Restock").visibleWhen(() -> bool("advanced") && restock.get()));

    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced controls", false)
        .description("Reveal placement timing, aim / walk styles and destructive mining / scaffold / chest opt-ins.")
        .group("4. Advanced").availableOffline());
    private final IntSetting placeDelay = add(new IntSetting("place-delay", "Place delay (ticks)", 2, 0, 20, 1)
        .description("Ticks to wait between block placements.").group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final IntSetting rotationSteps = add(new IntSetting("rotation", "Rotation (quarter turns)", 0, 0, 3, 1)
        .description("0/90/180/270 degrees around the origin. Positions only; block facing/axis/half are NOT reproduced. Changing pauses and resets progress.")
        .formatter(value -> (Integer.parseInt(value) * 90) + " degrees").group("2. Origin"));
    private final BoolSetting silentPlace = add(new BoolSetting("silent-place", "Silent rotation", false)
        .description("Face placement via movement packets (camera stays). OFF eases the real camera (recommended - the crosshair must be on the block to place).")
        .group("Advanced: Placement").visibleWhen(showAdvanced::get));
    public enum AimStyle { LEGIT, WIND_GRAVITY }
    private final EnumSetting<AimStyle> aimStyle = add(new EnumSetting<>("aim-style", "Aim style", AimStyle.WIND_GRAVITY, AimStyle.values())
        .description("Placement aim easing. LEGIT = time-eased human mouse. WIND_GRAVITY = wocky AdvancedRotationModel (velocity + gust + gravity, most human).")
        .group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final ColorSetting placedColor = add(new ColorSetting("placed-color", "Placed colour", 0x6400FF00).group("Advanced: Preview").visibleWhen(showAdvanced::get));
    private final ColorSetting currentColor = add(new ColorSetting("current-color", "Current layer colour", 0x9600FFFF).group("Advanced: Preview").visibleWhen(showAdvanced::get));
    private final ColorSetting missedColor = add(new ColorSetting("missed-color", "Missed colour", 0x78FF0000).group("Advanced: Preview").visibleWhen(showAdvanced::get));
    private final ColorSetting futureColor = add(new ColorSetting("future-color", "Future colour", 0x40808080).group("Advanced: Preview").visibleWhen(showAdvanced::get));
    private final BoolSetting pathfind = add(new BoolSetting("pathfind", "A* pathfinding", true)
        .description("Walk around obstacles to reach blocks (straight-line walk when off).").group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final BoolSetting legitWalk = add(new BoolSetting("legit-walk", "Legit walk (key-press)", true)
        .description("Walk by pressing real movement keys (nyx HumanMotionSim) with ledge/fall-hazard stop - most legit, no velocity/packet edits.")
        .group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final BoolSetting scaffold = add(new BoolSetting("scaffold", "Allow temporary scaffold blocks", false)
        .description("Place temporary blocks to bridge gaps / reach high spots while building.").group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final BoolSetting clearBlocks = add(new BoolSetting("clear-blocks", "Allow mining wrong blocks", false)
        .description("Destructive opt-in: mine wrong blocks occupying non-air targets and obstructing walking. Air in the file is never cleared.")
        .group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final BoolSetting autoTool = add(new BoolSetting("auto-tool", "Auto tool swap", true)
        .description("Swap to the best hotbar tool when mining out a block (faster clearing).")
        .group("Advanced: Placement").visibleWhen(showAdvanced::get));
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug tracing", false)
        .description("Trace layer/task/restock/verify transitions to chat + /flaglog (diagnose stalls).")
        .group("Advanced: Placement").visibleWhen(showAdvanced::get));

    // ---- state ----
    private Map<BlockPos, BlockState> schematic = Map.of();
    private SchematicLoader.Loaded preview;
    private long previewRevision;
    private List<Map.Entry<BlockPos, BlockState>> entries = List.of();
    private final Map<Item, Integer> requiredMaterials = new HashMap<>();
    private final Map<Item, Integer> auditMatches = new HashMap<>();
    private Map<Item, Integer> worldMatches = Map.of();
    private int auditIndex, auditMatched, matchedBlocks, auditUnknown, unknownBlocks;
    private boolean audited;
    private Future<SchematicLoader.Loaded> loading;
    private String fileNotice = "";
    private final SchematicBuildState intent = new SchematicBuildState();
    private java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> previewWorld = new java.lang.ref.WeakReference<>(null);
    private int observedRotation;
    private int previewTicks;
    private record GhostLayer(List<AABB> boxes, int color, int fill) {}
    private final Map<String, GhostLayer> ghostLayers = new HashMap<>();
    private boolean changingSettings, setupKeyReleased;
    private boolean ownsWalk, ownsAttack;
    private float[] ownedHumanLook;
    private int ownedContainer = -1;
    private int restockTimeout;
    private boolean restockRequested;
    private BlockPos verifyTarget;
    private BlockState verifyState;
    private int verifyTaskIndex;
    private BlockPos origin = null;
    private int minY, maxY;
    private boolean loaded = false;
    private int currentLayer = 0;
    private int layerRetries = 0;
    private final List<BlockPlaceTask> layerTasks = new ArrayList<>();
    private int layerIndex = 0;
    private int placeCooldown = 0;
    private int totalBlocks = 0;
    private final LegitMovement look = new LegitMovement();
    private boolean pendingPlace = false;
    private int pendingSlot = -1;
    private BlockHitResult pendingHit = null;
    private BlockPos pendingTarget;
    private boolean pendingSneak = false;
    /** Placement awaiting world-state confirmation: expected block + bounded retry window. */
    private BlockPos verifyPos = null;
    private int verifyTicks = 0;
    private int verifyRetries = 0;

    // pathfinding + scaffold
    private List<BlockPos> currentPath = new ArrayList<>();
    private int pathIndex = 0;
    // restock
    private enum RestockState { NONE, SEARCHING, OPENING, TAKING }
    private RestockState restockState = RestockState.NONE;
    private BlockPos restockChest = null;
    private Item neededItem = null;
    private int restockDelay = 0;
    private final Set<BlockPos> searchedChests = new HashSet<>();

    private record BlockPlaceTask(BlockPos worldPos, BlockState state) {}

    private final com.autism.seedcracker.util.StuckDetector stuck =
        new com.autism.seedcracker.util.StuckDetector("SchematicBuilderModule",
            com.autism.seedcracker.util.Tuning.STUCK_TICKS_BUILDER, com.autism.seedcracker.util.Tuning.STUCK_EPSILON);

    @Override
    public void onEnable() {
        observeWorld();
        previewOnly("Preview only. Open setup, check the origin and materials, then press Start.");
        setTransient("load", "false");
        setTransient("set-origin", "false");
        setupKeyReleased = false;
    }

    @Override
    public void onDisable() {
        cancelLoad();
        stopBuild();
        clearGhost();
        setupKeyReleased = false;
    }

    @Override
    protected void onSettingsReset() {
        cancelLoad();
        previewOnly("Settings reset. Review the preview and press Start explicitly.");
        invalidateGeometry();
    }

    @Override
    protected void onOptionValueChanged(String key) {
        if (changingSettings || intent == null) return;
        if ("load".equals(key) && load.get()) {
            setTransient("load", "false");
            loadPreview(schematicPath.get());
        } else if ("set-origin".equals(key) && setOrigin.get()) {
            setTransient("set-origin", "false");
            setOriginHere(Minecraft.getInstance());
        } else if ("rotation".equals(key)) {
            invalidateGeometry();
        } else if ("build".equals(key)) {
            pauseBuild("Use Review & Start to begin; saved build values never arm placement.");
        } else if (!"recent-files".equals(key) && !"load".equals(key) && !"set-origin".equals(key)
                && !"setup-key".equals(key) && !"advanced".equals(key) && intent.running()) {
            pauseBuild("Settings changed. Review them and press Start again.");
        }
    }

    @Override
    public void onGameLeft() {
        cancelLoad();
        stopBuild();
        origin = null;
        previewWorld.clear();
        layerTasks.clear();
        clearAudit();
        clearGhost();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override public boolean ticksWhenDisabled() { return true; }
    @Override public boolean hasDisabledTickWork() {
        return loading != null || setupKey.get() >= 0 || load.get() || setOrigin.get() || build.get();
    }

    @Override public void onMouseRotation(double dx, double dy) {
        if (intent.running() && !intent.waitingForRelease() && Math.abs(dx) + Math.abs(dy) > 0.01) {
            pauseBuild("Mouse takeover. Press Start when ready to continue.");
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        pollSetupKey(mc);
        if (load.get()) { setTransient("load", "false"); loadPreview(schematicPath.get()); }
        if (setOrigin.get()) { setTransient("set-origin", "false"); setOriginHere(mc); }
        updateSetup();
        if (!isEnabled() || mc.player == null || mc.level == null) return;
        if (renderGhost.get() && loaded && origin != null) {
            if (ghostLayers.isEmpty() || ++previewTicks % 5 == 0) renderGhost(mc);
            else ghostLayers.forEach((key, layer) -> BlockEspRenderer.feedBoxes(key, layer.boxes(), layer.color(), layer.fill()));
        } else clearGhost();
        if (!intent.running()) {
            if (build.get()) setTransient("build", "false");
            return;
        }
        boolean ownedRestockScreen = restock.get() && restockState != RestockState.NONE
            && !(mc.gui.screen() instanceof SchematicSetupScreen)
            && mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
            && (ownedContainer == mc.player.containerMenu.containerId
                || restockState == RestockState.OPENING && restockRequested && restockTimeout > 0);
        if (!intent.allowTick(mc.level, mc.gui.screen() != null && !ownedRestockScreen)) {
            releaseWork();
            setTransient("build", "false");
            return;
        }
        if (!mc.isWindowActive()) {
            pauseBuild("Window unfocused. Press Start when ready to continue.");
            return;
        }
        if (!intent.allowInputTick(manualInput(mc))) {
            if (!intent.running()) { releaseWork(); setTransient("build", "false"); }
            return;
        }
        if (!build.get() || !loaded || origin == null) {
            pauseBuild("Preview not ready. Choose a file and origin, then Start.");
            return;
        }
        com.autism.seedcracker.util.DebugProbe.setEnabled(id(), debug.get());
        com.autism.seedcracker.util.DebugProbe.traceChange(id(), "state",
            "layer=" + currentLayer + " task=" + layerIndex + "/" + layerTasks.size() + " restock=" + restockState);
        if (restock.get() && restockState != RestockState.NONE) {
            tickRestock(mc);
            return;
        }
        if (com.autism.seedcracker.util.ContainerMutex.containerBusy(mc)) {
            pauseBuild("Container grace period. Close the container, then press Start.");
            return;
        }
        if (com.autism.seedcracker.motion.Motion.isBusy()) {
            pauseBuild("Another movement controller is active. Stop it, then press Start.");
            return;
        }
        tickBuild(mc);
        if (!currentPath.isEmpty() && pathIndex < currentPath.size()) {
            stuck.setAction("layer=" + currentLayer + " task=" + layerIndex + "/" + layerTasks.size());
            stuck.tick(mc);
        } else stuck.reset();
    }

    // ---- origin and explicit user controls ----
    private void setOriginHere(Minecraft mc) {
        if (mc.player == null || mc.level == null) { fileNotice = "Join a world to set an origin."; return; }
        if (mc.hitResult instanceof BlockHitResult hit
            && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) setBuildOrigin(hit.getBlockPos().above());
        else setBuildOrigin(mc.player.blockPosition());
    }

    public void useCrosshairOrigin() { setOriginHere(Minecraft.getInstance()); }
    public void useFeetOrigin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) setBuildOrigin(mc.player.blockPosition());
    }

    public boolean setBuildOrigin(BlockPos position) {
        Minecraft mc = Minecraft.getInstance();
        observeWorld();
        if (mc.level == null || mc.player == null) { fileNotice = "Join a world to set an origin."; return false; }
        if (Math.abs((long) position.getX()) > 29_995_000 || Math.abs((long) position.getZ()) > 29_995_000
                || position.getY() < mc.level.getMinY() || position.getY() > mc.level.getMaxY()) {
            fileNotice = "Origin must be inside the world's height and coordinate limits.";
            return false;
        }
        origin = position.immutable();
        invalidateGeometry();
        fileNotice = "Origin set. Preview it in the world before Start.";
        return true;
    }

    public void setRotation(int steps) { rotationSteps.set(Math.floorMod(steps, 4)); }

    private void invalidateGeometry() {
        previewOnly("Origin / rotation changed. Preview the new bounds, then press Start.");
        observedRotation = rotationSteps.get();
        currentLayer = minY;
        layerRetries = 0;
        clearAudit();
        rebuildLayerTasks();
        clearGhost();
    }

    private void observeWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (previewWorld.get() == mc.level && (mc.level != null || origin == null)) return;
        previewWorld = new java.lang.ref.WeakReference<>(mc.level);
        origin = null;
        previewOnly("World changed. Set an origin in this world, then press Start.");
        clearAudit();
        layerTasks.clear();
        clearGhost();
    }

    public void openSetup() { openSetup(false); }
    private void openReview() { openSetup(true); }
    private void openSetup(boolean review) {
        Minecraft mc = Minecraft.getInstance();
        pauseBuild("Setup open. Preview is safe; only Start closes setup and builds.");
        mc.gui.setScreen(new SchematicSetupScreen(mc.gui.screen(), this, review));
    }

    public void enablePreview() {
        if (!isEnabled()) setEnabled(true);
        pauseBuild(isEnabled() ? "Preview only. Reopen setup to review materials and Start."
            : "The client did not enable the preview. Check client activation and module access.");
    }

    public boolean startBuild() {
        Minecraft mc = Minecraft.getInstance();
        observeWorld();
        String problem = startProblem();
        if (!problem.isEmpty()) { pauseBuild(problem); fileNotice = problem; return false; }
        if (mc.gui.screen() != null) {
            pauseBuild("Use setup's Start button to close menus and begin.");
            fileNotice = intent.reason();
            return false;
        }
        if (!isEnabled()) setEnabled(true);
        if (!isEnabled()) {
            pauseBuild("The client did not enable Schematic Builder. Check client activation and module access, then try again.");
            fileNotice = intent.reason();
            return false;
        }
        releaseWork();
        if (currentLayer > maxY) currentLayer = minY;
        rebuildLayerTasks();
        if (!intent.start(mc.level, loaded && origin != null && loading == null, false)) return false;
        fileNotice = "";
        setTransient("build", "true");
        return true;
    }

    public String startProblem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "Join a world, then set the origin.";
        if (loading != null) return "Wait for the file load to finish.";
        if (!loaded) return "Load a schematic preview first.";
        if (origin == null || previewWorld.get() != mc.level) return "Set an origin in this world.";
        if (origin.getY() + maxY > mc.level.getMaxY()) return "Selection exceeds world height. Lower the origin.";
        if (mc.gameMode == null || !mc.player.isAlive()) return "Wait for the player to be ready in this world.";
        if (mc.player.isSpectator()) return "Leave spectator mode before building.";
        return "";
    }

    public void pauseBuild(String reason) {
        intent.pause(reason);
        releaseWork();
        setTransient("build", "false");
    }

    public void stopBuild() {
        intent.stop("Stopped. Preview kept; Start begins a fresh pass (existing blocks are skipped).");
        releaseWork();
        setTransient("build", "false");
        currentLayer = minY;
        layerRetries = 0;
        rebuildLayerTasks();
    }

    private void previewOnly(String reason) {
        intent.preview(reason);
        releaseWork();
        setTransient("build", "false");
    }

    public void applySafePreset() {
        pauseBuild("Safe preset applied. No mining, scaffolding or chest access. Review, then Start.");
        for (String key : List.of("clear-blocks", "scaffold", "restock", "silent-place")) setTransient(key, "false");
        for (String key : List.of("pathfind", "legit-walk", "render-ghost")) setTransient(key, "true");
        setTransient("build-order", "LAYERED");
        setTransient("place-delay", "2");
        try { persistConfiguredState(); }
        catch (RuntimeException e) { fileNotice = "Safe preset active, but it could not be saved: " + e.getMessage(); }
    }

    private void setTransient(String key, String value) {
        changingSettings = true;
        try { setValueTransient(key, value); }
        finally { changingSettings = false; }
    }

    private void releaseWork() {
        Minecraft mc = Minecraft.getInstance();
        stopWalk(mc);
        releaseAttack(mc);
        com.autism.seedcracker.motion.RotationEngine.release(id());
        resetRestock();
        look.reset();
        stuck.reset();
        pendingPlace = false;
        pendingSlot = -1;
        pendingHit = null;
        pendingSneak = false;
        pendingTarget = null;
        verifyPos = null;
        verifyTarget = null;
        verifyState = null;
        verifyTicks = verifyRetries = 0;
        placeCooldown = 0;
        currentPath.clear();
        pathIndex = 0;
        searchedChests.clear();
    }

    private void releaseAttack(Minecraft mc) {
        if (ownsAttack && mc.options != null) {
            boolean manual = physicalKey(mc, mc.options.keyAttack);
            mc.options.keyAttack.setDown(manual);
            if (!manual && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        }
        ownsAttack = false;
    }

    private static boolean physicalKey(Minecraft mc, net.minecraft.client.KeyMapping binding) {
        return physicalKey(mc, binding, false);
    }

    private static boolean physicalKey(Minecraft mc, net.minecraft.client.KeyMapping binding, boolean unknownHeld) {
        if (mc.getWindow() == null || !mc.isWindowActive()) return false;
        try {
            var key = com.mojang.blaze3d.platform.InputConstants.getKey(binding.saveString());
            if (key.getValue() < 0) return false;
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
                return com.autism.seedcracker.compat.McCompat.mouseDown(mc, key.getValue());
            }
            if (com.autism.seedcracker.compat.McCompat.isKeyboardKey(key)) {
                return com.autism.seedcracker.compat.McCompat.keyDown(mc, key.getValue());
            }
            return unknownHeld; // Pause on unknown bindings, but never synthesize a held key when releasing.
        } catch (IllegalArgumentException e) { return unknownHeld; }
    }

    private static boolean manualInput(Minecraft mc) {
        if (mc.options == null) return false;
        if (com.autism.seedcracker.compat.McCompat.mouseDown(mc, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)
                || com.autism.seedcracker.compat.McCompat.mouseDown(mc, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT)) return true;
        return physicalKey(mc, mc.options.keyUp, true) || physicalKey(mc, mc.options.keyDown, true)
            || physicalKey(mc, mc.options.keyLeft, true) || physicalKey(mc, mc.options.keyRight, true)
            || physicalKey(mc, mc.options.keyJump, true) || physicalKey(mc, mc.options.keyShift, true)
            || physicalKey(mc, mc.options.keyAttack, true) || physicalKey(mc, mc.options.keyUse, true);
    }

    private void pollSetupKey(Minecraft mc) {
        if (mc.gui.screen() != null || mc.player == null || !mc.isWindowActive() || setupKey.get() < 0) {
            setupKeyReleased = false;
            return;
        }
        boolean down = com.autism.seedcracker.compat.McCompat.keyDown(mc, setupKey.get());
        if (!down) setupKeyReleased = true;
        else if (setupKeyReleased) { setupKeyReleased = false; openSetup(); }
    }

    // ---- build state machine ----
    private void tickBuild(Minecraft mc) {
        if (placeCooldown > 0) { placeCooldown--; return; }
        // Verify before testing the end of a layer, including its LAST placement.
        if (verifyPos != null) {
            if (!mc.level.hasChunkAt(verifyPos)) { pauseBuild("Target chunk unloaded. Move nearer and Start again."); return; }
            if (matches(mc.level.getBlockState(verifyPos), verifyState)) {
                verifyPos = null;
                verifyRetries = 0;
            } else if (--verifyTicks <= 0) {
                if (++verifyRetries >= 3) {
                    com.autism.seedcracker.modules.FlagDetectorModule.report("place-fail", "SchematicBuilder", "placement not confirmed at " + verifyPos);
                    pauseBuild("Placement not confirmed at " + verifyPos.toShortString() + ". Check reach / permissions, then Start.");
                } else {
                    layerIndex = verifyTaskIndex;
                    verifyPos = null;
                }
            }
            return;
        }
        if (currentLayer > maxY) {
            intent.finish("Pass finished. Review world counts and missed blocks; Start retries.");
            releaseWork();
            setTransient("build", "false");
            msg(mc, "Pass finished. Review missing blocks in setup; Start retries them.");
            return;
        }
        if (layerIndex >= layerTasks.size()) {
            // verify + advance
            int placedInLayer = 0;
            for (BlockPlaceTask t : layerTasks) {
                if (matches(mc.level.getBlockState(t.worldPos), t.state)) placedInLayer++;
            }
            boolean advance;
            if (buildOrder.get() == BuildOrder.LAYERED) {
                // Strict printer: the layer must be COMPLETE before the next starts. Retry passes
                // are capped so an unplaceable spot (bedrock hole, mob cage) can't loop forever.
                advance = placedInLayer == layerTasks.size();
            } else {
                advance = placedInLayer >= (int) Math.ceil(layerTasks.size() * 0.95);
            }
            if (!advance && ++layerRetries > 8) {
                pauseBuild("Layer has unreachable / blocked targets after 8 passes. Fix supports, then Start.");
                layerRetries = 0;
                return;
            }
            if (advance) {
                currentLayer++;
                layerRetries = 0;
                rebuildLayerTasks();
            } else {
                rebuildLayerTasks(); // retry the missed ones
            }
            return;
        }

        BlockPlaceTask task = layerTasks.get(layerIndex);
        if (!mc.level.hasChunkAt(task.worldPos)) {
            pauseBuild("Target chunk unloaded at " + task.worldPos.toShortString() + ". Move nearer and Start.");
            return;
        }
        BlockState cur = mc.level.getBlockState(task.worldPos);
        if (matches(cur, task.state)) {
            releaseAttack(mc);
            layerIndex++;
            return;
        }
        // The spot is occupied by a wrong / non-replaceable block. Mine it out first (legit dig),
        // then come back and place. Bedrock is never dug.
        if (!cur.isAir() && !cur.canBeReplaced()) {
            if (clearBlocks.get() && cur.getBlock() != Blocks.BEDROCK) {
                if (digOut(mc, task.worldPos, cur)) return; // still digging
                // digOut returns false when it can't reach / finished this tick: fall through to re-check next tick
                return;
            }
            pauseBuild("Blocked at " + task.worldPos.toShortString() + ". Clear it manually, or opt into mining in advanced settings.");
            return;
        }
        releaseAttack(mc);
        if (entityBlocking(mc, task.worldPos)) {
            pauseBuild("Entity in target " + task.worldPos.toShortString() + ". Move it away, then Start.");
            return;
        }

        // Find the item for this block in the hotbar.
        Item item = task.state.getBlock().asItem();
        int slot = findHotbarItem(mc, item);
        if (slot < 0) {
            if (restock.get() && !inventoryHas(mc, item)) {
                neededItem = item;
                restockState = RestockState.SEARCHING;
                restockTimeout = 160;
                searchedChests.clear();
                return;
            }
            pauseBuild("Put " + new ItemStack(item).getHoverName().getString() + " in the hotbar, then Start. See materials in setup.");
            return;
        }

        // Placement solve. The base client's AutismFaceScan was renamed in later (obfuscated)
        // client builds, so we use our own face-centre placement scan directly (findPlacementHit).
        // That fallback was already the safety net when FaceScan had no candidate, so behaviour is
        // unchanged on clients without the renamed class.
        BlockHitResult hit = findPlacementHit(mc, task.worldPos);
        boolean scanSneak = false;
        if (hit == null) { layerIndex++; return; }
        Vec3 eye = mc.player.getEyePosition();
        double dist = eye.distanceTo(hit.getLocation());
        double reach = reachFor(task.worldPos);
        if (dist > reach) {
            if (pathfind.get()) walkPath(mc, task.worldPos);
            else walkToward(mc, task.worldPos);
            return;
        }
        stopWalk(mc);
        currentPath.clear(); pathIndex = 0;
        if (!mc.player.onGround()) return; // never place airborne (Grim)

        // Swap first, then place on the NEXT tick (Water #2) so the server registers the held item
        // before the interact - reads as a real click instead of an instant swap-place.
        if (pendingSlot != slot || !pendingPlace || !task.worldPos.equals(pendingTarget)) {
            com.autism.seedcracker.util.InvSync.select(mc, slot);
            pendingSlot = slot;
            pendingPlace = true;
            pendingHit = hit;
            pendingTarget = task.worldPos;
            pendingSneak = scanSneak;
            return;
        }
        pendingHit = hit;

        // Face the placement point with the legit human-mouse engine, then place.
        Vec3 target = pendingHit.getLocation();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        boolean aimed;
        if (silentPlace.get()) {
            aimed = com.autism.seedcracker.motion.RotationEngine.request(id(),
                com.autism.seedcracker.motion.RotationEngine.PRIORITY_INTERACT, yaw, pitch, null, true);
        } else {
            float[] rot = aimStyle.get() == AimStyle.WIND_GRAVITY
                ? look.updateWindGravity(yaw, pitch) : look.update(yaw, pitch);
            if (!com.autism.seedcracker.motion.RotationEngine.write(id(), com.autism.seedcracker.motion.RotationEngine.PRIORITY_INTERACT, rot[0], rot[1])) return;
            aimed = look.isRotationsDone();
        }
        if (!aimed) return;
        if (mc.gameMode == null || !validPlacementHit(mc, pendingHit, task.worldPos)) {
            pauseBuild("Placement face occluded / out of reach. Move nearer or add a support, then Start.");
            return;
        }
        boolean sneakForContainer = pendingSneak || isContainer(mc, pendingHit.getBlockPos());
        boolean wasSneaking = mc.options.keyShift.isDown();
        try {
            if (sneakForContainer) mc.options.keyShift.setDown(true);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, pendingHit);
            com.autism.seedcracker.compat.McCompat.swing(mc.player, InteractionHand.MAIN_HAND);
        } finally {
            if (sneakForContainer) mc.options.keyShift.setDown(wasSneaking);
        }
        if (!task.worldPos.equals(verifyTarget)) verifyRetries = 0;
        verifyTarget = task.worldPos;
        verifyPos = task.worldPos;
        verifyState = task.state;
        verifyTaskIndex = layerIndex;
        verifyTicks = 10;
        layerIndex++;
        pendingPlace = false;
        pendingSlot = -1;
        pendingHit = null;
        pendingSneak = false;
        pendingTarget = null;
        // Jittered + TPS-scaled: a constant N-tick place cadence is a printer signature.
        placeCooldown = com.autism.seedcracker.util.Humanizer.delay(Math.max(1, placeDelay.get()));
    }

    /**
     * Same block type counts as placed. Palettes load as default states and the click decides facing/axis/half,
     * so an exact state compare never matched a stair, log or slab: the layer check kept failing and, with
     * "Mine out wrong blocks" on, the builder dug out its own correct block and placed it again forever.
     */
    private static boolean matches(BlockState cur, BlockState want) {
        return cur.is(want.getBlock());
    }

    private double reachFor(BlockPos pos) { return MAX_REACH; }

    private void rebuildLayerTasks() {
        layerTasks.clear();
        layerIndex = 0;
        pendingPlace = false;
        pendingSlot = -1;
        pendingHit = null;
        pendingSneak = false;
        pendingTarget = null;
        if (!loaded || origin == null) return;
        int rot = rotationSteps.get();
        for (Map.Entry<BlockPos, BlockState> e : schematic.entrySet()) {
            BlockPos rel = e.getKey();
            if (rel.getY() != currentLayer) continue;
            BlockPos r = rotatePos(rel, rot);
            layerTasks.add(new BlockPlaceTask(origin.offset(r), e.getValue()));
        }
        BlockPos pp = Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.blockPosition() : BlockPos.ZERO;
        layerTasks.sort((a, b) -> Double.compare(pp.distSqr(a.worldPos), pp.distSqr(b.worldPos)));
    }

    private BlockHitResult findPlacementHit(Minecraft mc, BlockPos pos) {
        // For blocks above the player, prefer the DOWN face (place on top of the block beneath) -
        // more natural. Otherwise prefer placing on top of the block below.
        BlockPos below = pos.below();
        if (isSolid(mc, below)) return faceCenterHit(below, Direction.UP);

        // Prioritize horizontal sides for eye-level blocks, DOWN for above-head blocks.
        boolean aboveHead = mc.player != null && pos.getY() > mc.player.getBlockY();
        Direction[] dirs = aboveHead
            ? new Direction[] { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP }
            : new Direction[] { Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN };
        for (Direction d : dirs) {
            BlockPos n = pos.relative(d);
            if (isSolid(mc, n)) return faceCenterHit(n, d.getOpposite());
        }

        return null;
    }

    /**
     * Build a BlockHitResult on the support block's face. Tries a raycast-validated VISIBLE point
     * first (THack Interact.Legit - survives Grim's PositionPlace ray check even when the face
     * centre is occluded); falls back to the face centre (nyx class243Of) if none is visible.
     */
    private static BlockHitResult faceCenterHit(BlockPos support, Direction face) {
        Vec3 legit = com.autism.seedcracker.util.tunnel.LegitHitPoint.find(
            Minecraft.getInstance(), support, face, 4.5);
        if (legit != null) return new BlockHitResult(legit, face, support, false);
        Vec3 point = new Vec3(
            support.getX() + 0.5 + face.getStepX() * 0.5,
            support.getY() + 0.5 + face.getStepY() * 0.5,
            support.getZ() + 0.5 + face.getStepZ() * 0.5);
        return new BlockHitResult(point, face, support, false);
    }

    private static boolean validPlacementHit(Minecraft mc, BlockHitResult hit, BlockPos target) {
        if (mc.gui.screen() != null || mc.player == null || mc.level == null
                || !hit.getBlockPos().relative(hit.getDirection()).equals(target)
                || mc.player.getEyePosition().distanceTo(hit.getLocation()) > MAX_REACH
                || !isSolid(mc, hit.getBlockPos())) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = hit.getLocation().add(hit.getLocation().subtract(eye).normalize().scale(0.001));
        BlockHitResult ray = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
            net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        return ray.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && ray.getBlockPos().equals(hit.getBlockPos())
            && ray.getDirection() == hit.getDirection();
    }

    private static boolean isSolid(Minecraft mc, BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        return !s.isAir() && !s.canBeReplaced();
    }

    /** True if the block has a container GUI (placing against it needs sneak to not open it). */
    private static boolean isContainer(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
    }

    /** True if a living/non-item entity intersects the target cell (server rejects the place). */
    private static boolean entityBlocking(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return false;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos);
        for (net.minecraft.world.entity.Entity e : mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, box,
                x -> !(x instanceof net.minecraft.world.entity.item.ItemEntity) && x.isPickable())) {
            return true;
        }
        return false;
    }

    private void walkToward(Minecraft mc, BlockPos target) {
        ownsWalk = true;
        if (legitWalk.get()) {
            // nyx HumanMotionSim: drive the REAL movement keys toward the target with a turn-gate,
            // ledge/fall-hazard stop and mine-through detection - zero velocity/packet manipulation.
            Vec3 t = Vec3.atCenterOf(target);
            var result = com.autism.seedcracker.util.tunnel.HumanMotionSim.step(t, MAX_REACH, 30.0, 4, 1);
            ownedHumanLook = com.autism.seedcracker.util.tunnel.LookRotation.getRequested();
            if (result == com.autism.seedcracker.util.tunnel.HumanMotionSim.StepResult.MINE_THROUGH) {
                BlockPos mine = com.autism.seedcracker.util.tunnel.HumanMotionSim.getMineTarget();
                if (clearBlocks.get() && mine != null) digOut(mc, mine, mc.level.getBlockState(mine));
                else pauseBuild("Walking blocked. Clear the path manually, then Start (mining is off).");
            } else if (result == com.autism.seedcracker.util.tunnel.HumanMotionSim.StepResult.EDGE_STOP
                    || result == com.autism.seedcracker.util.tunnel.HumanMotionSim.StepResult.FALL_HAZARD
                    || result == com.autism.seedcracker.util.tunnel.HumanMotionSim.StepResult.BLOCKED) {
                pauseBuild("Unsafe / blocked walking route. Move to a safe position, then Start.");
            }
            return;
        }
        double dx = (target.getX() + 0.5) - mc.player.getX();
        double dz = (target.getZ() + 0.5) - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float[] rot = look.update(yaw, 0f);
        com.autism.seedcracker.motion.RotationEngine.write(id(), com.autism.seedcracker.motion.RotationEngine.PRIORITY_MOVE, rot[0], mc.player.getXRot());
        mc.options.keyUp.setDown(true);
        if (mc.player.horizontalCollision && mc.player.onGround()) mc.options.keyJump.setDown(true);
        else mc.options.keyJump.setDown(false);
    }

    private void stopWalk(Minecraft mc) {
        if (!ownsWalk) return;
        ownsWalk = false;
        com.autism.seedcracker.util.tunnel.HumanMotionSim.releaseAll();
        if (ownedHumanLook != null && ownedHumanLook == com.autism.seedcracker.util.tunnel.LookRotation.getRequested()) {
            com.autism.seedcracker.util.tunnel.LookRotation.clear();
        }
        ownedHumanLook = null;
        if (mc.options != null) {
            mc.options.keyUp.setDown(physicalKey(mc, mc.options.keyUp));
            mc.options.keyJump.setDown(physicalKey(mc, mc.options.keyJump));
            mc.options.keySprint.setDown(physicalKey(mc, mc.options.keySprint));
        }
    }

    /**
     * Legit-mine the block occupying a schematic spot. Walks to within vanilla reach, eases the
     * view onto the block with the legit engine, swaps to the best tool, then holds attack on the
     * crosshair block (real reported face). Returns true while a dig is actively in progress.
     */
    private boolean digOut(Minecraft mc, BlockPos pos, BlockState state) {
        if (!clearBlocks.get() || state.is(Blocks.BEDROCK)) {
            pauseBuild("Mining not permitted at " + pos.toShortString() + ". Clear a safe path manually.");
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        double dist = eye.distanceTo(Vec3.atCenterOf(pos));
        if (dist > MAX_REACH) {
            releaseAttack(mc);
            if (pathfind.get()) walkPath(mc, pos);
            else walkToward(mc, pos);
            return true;
        }
        stopWalk(mc);
        if (!mc.player.onGround()) { releaseAttack(mc); return true; }
        if (autoTool.get()) selectBestTool(mc, state);
        // Ease onto the block with the human-mouse engine so the crosshair lands on it. Cooldown-
        // scaled (Wocky SpookyTime): the aim slows as attack-strength charges, like a real player
        // timing their swings, instead of a constant-speed head-track.
        double dx = pos.getX() + 0.5 - eye.x, dy = pos.getY() + 0.5 - eye.y, dz = pos.getZ() + 0.5 - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float cd = mc.player.getAttackStrengthScale(0.5f); // 0..1 crit charge
        float scale = 0.4f + 0.6f * cd; // aim faster when charged, slower while charging
        float[] rot = look.update(
            mc.player.getYRot() + (yaw - mc.player.getYRot()) * scale,
            mc.player.getXRot() + (pitch - mc.player.getXRot()) * scale);
        if (!com.autism.seedcracker.motion.RotationEngine.write(id(), com.autism.seedcracker.motion.RotationEngine.PRIORITY_INTERACT, rot[0], rot[1])) {
            releaseAttack(mc);
            return true;
        }
        if (!look.isRotationsDone()) { releaseAttack(mc); return true; }
        // Only the requested block may be mined, never a different block occluding it.
        if (mc.hitResult instanceof BlockHitResult bhr
            && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos hitPos = bhr.getBlockPos();
            if (hitPos.equals(pos) && !mc.level.getBlockState(hitPos).isAir() && mc.gameMode != null
                    && eye.distanceTo(bhr.getLocation()) <= MAX_REACH) {
                // Key only: vanilla's continueAttack already drives continueDestroyBlock + swing
                // while the key is down; calling it here too doubled break progress per tick
                // (fast-break flag risk, A15).
                mc.options.keyAttack.setDown(true);
                ownsAttack = true;
                return true;
            }
        }
        releaseAttack(mc);
        return true;
    }

    /** Swap to the best hotbar tool for a block (used when clearing). */
    private static void selectBestTool(Minecraft mc, BlockState state) {
        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = slot; }
        }
        if (bestSlot == -1) return;
        float current = mc.player.getMainHandItem().getDestroySpeed(state);
        if (bestSpeed > current) com.autism.seedcracker.util.InvSync.select(mc, bestSlot);
    }

    // ---- A* pathfinding + scaffold bridging ----

    /** Follow an A* path toward the target, bridging gaps with scaffold blocks when needed. */
    private void walkPath(Minecraft mc, BlockPos target) {
        BlockPos start = mc.player.blockPosition();
        if (currentPath.isEmpty() || pathIndex >= currentPath.size()
            || !currentPath.get(currentPath.size() - 1).equals(target)) {
            List<BlockPos> path = findPath(mc, start, target);
            if (path != null && !path.isEmpty()) { currentPath = path; pathIndex = 0; }
            else { walkToward(mc, target); return; } // no path: fall back to straight walk
        }
        // Scaffold-bridge: if the next step has no floor, place a temporary block to stand on.
        if (scaffold.get() && tryScaffoldStep(mc)) return;

        Vec3 pp = mc.player.position();
        while (pathIndex < currentPath.size()) {
            BlockPos next = currentPath.get(pathIndex);
            double d = pp.distanceTo(Vec3.atCenterOf(next));
            if (d < 0.6) { pathIndex++; continue; }
            walkToward(mc, next);
            return;
        }
        walkToward(mc, target);
    }

    /** Place a temporary scaffold block under the next step if there's a gap. Aims with the
     * eased engine at the REAL hit point and only places once converged (no snap-place). */
    private boolean tryScaffoldStep(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos ahead = feet.relative(mc.player.getDirection());
        BlockPos floor = ahead.below();
        if (isSolid(mc, floor)) return false; // already a floor
        if (schematic.containsKey(toRel(floor))) return false; // don't overwrite schematic spots
        if (!mc.player.onGround()) return true; // airborne: wait, don't scaffold mid-jump (Grim)
        int slot = findScaffoldSlot(mc);
        if (slot < 0) return false;
        // place on the side of an adjacent solid block
        BlockHitResult hit = findPlacementHit(mc, floor);
        if (hit == null) return false;
        stopWalk(mc);
        if (pendingSlot != slot || !floor.equals(pendingTarget)) {
            com.autism.seedcracker.util.InvSync.select(mc, slot);
            pendingSlot = slot;
            pendingTarget = floor;
            return true;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 tgt = hit.getLocation();
        double dx = tgt.x - eye.x, dy = tgt.y - eye.y, dz = tgt.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float[] rot = look.update(yaw, pitch);
        if (!com.autism.seedcracker.motion.RotationEngine.write(id(), com.autism.seedcracker.motion.RotationEngine.PRIORITY_INTERACT, rot[0], rot[1])) return true;
        // Keep easing across ticks until converged; return true = "busy scaffolding, don't walk".
        if (!look.isRotationsDone()) return true;
        if (mc.gameMode == null || !validPlacementHit(mc, hit, floor)) {
            pauseBuild("Scaffold face is not safely reachable. Add support manually, then Start.");
            return true;
        }
        if (!(mc.player.getInventory().getItem(slot).getItem() instanceof net.minecraft.world.item.BlockItem scaffoldItem)) return false;
        BlockState scaffoldState = scaffoldItem.getBlock().defaultBlockState();
        boolean sneak = isContainer(mc, hit.getBlockPos());
        boolean wasSneaking = mc.options.keyShift.isDown();
        try {
            if (sneak) mc.options.keyShift.setDown(true);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            com.autism.seedcracker.compat.McCompat.swing(mc.player, InteractionHand.MAIN_HAND);
        } finally {
            if (sneak) mc.options.keyShift.setDown(wasSneaking);
        }
        if (!floor.equals(verifyTarget)) verifyRetries = 0;
        verifyTarget = verifyPos = floor;
        verifyState = scaffoldState;
        verifyTaskIndex = layerIndex;
        verifyTicks = 10;
        placeCooldown = com.autism.seedcracker.util.Humanizer.delay(Math.max(1, placeDelay.get()));
        pendingSlot = -1;
        pendingTarget = null;
        return true;
    }

    private BlockPos toRel(BlockPos worldPos) {
        return origin == null ? worldPos : rotatePos(worldPos.subtract(origin), -rotationSteps.get());
    }

    private static int findScaffoldSlot(Minecraft mc) {
        Item[] pref = { Blocks.DIRT.asItem(), Blocks.COBBLESTONE.asItem(), Blocks.STONE.asItem(), Blocks.OAK_PLANKS.asItem() };
        for (Item it : pref) { int s = findHotbarItem(mc, it); if (s >= 0) return s; }
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (!st.isEmpty() && st.getItem() instanceof net.minecraft.world.item.BlockItem) return i;
        }
        return -1;
    }

    /** Simple A* over walkable positions (3-D, with jump/build costs). */
    private List<BlockPos> findPath(Minecraft mc, BlockPos start, BlockPos goal) {
        record Node(BlockPos pos, double f) {}
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(Node::f));
        Map<BlockPos, BlockPos> came = new HashMap<>();
        Map<BlockPos, Double> g = new HashMap<>();
        g.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goal)));
        int iter = 0;
        while (!open.isEmpty() && iter++ < 1500) {
            BlockPos cur = open.poll().pos();
            if (cur.equals(goal) || cur.distSqr(goal) <= 4) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos n = cur; n != null; n = came.get(n)) path.add(0, n);
                return path;
            }
            if (cur.distSqr(start) > 60.0 * 60.0) break;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dz == 0 && dy == 0) continue;
                BlockPos nb = cur.offset(dx, dy, dz);
                if (!isWalkable(mc, nb)) continue;
                double cost = 1.0;
                if (dx != 0 && dz != 0) cost = 1.414;
                if (dy > 0) cost += 0.8;
                if (!isSolid(mc, nb.below())) cost += 2.0; // needs a bridge
                double tg = g.getOrDefault(cur, Double.MAX_VALUE) + cost;
                if (tg < g.getOrDefault(nb, Double.MAX_VALUE)) {
                    came.put(nb, cur);
                    g.put(nb, tg);
                    open.add(new Node(nb, tg + heuristic(nb, goal)));
                }
            }
        }
        return null;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean isWalkable(Minecraft mc, BlockPos pos) {
        BlockState feet = mc.level.getBlockState(pos);
        BlockState head = mc.level.getBlockState(pos.above());
        boolean passable = (feet.isAir() || feet.canBeReplaced()) && (head.isAir() || head.canBeReplaced());
        if (!passable) return false;
        if (isSolid(mc, pos.below())) return true;
        // can bridge a gap if scaffolding is on and we have a scaffold block
        return scaffold.get() && findScaffoldSlot(mc) >= 0;
    }

    // ---- chest restock (optional) ----

    private void tickRestock(Minecraft mc) {
        stopWalk(mc);
        if (--restockTimeout <= 0) { pauseBuild("Restock timed out. Put materials in the hotbar and Start again."); return; }
        if (neededItem == null || mc.gameMode == null) { pauseBuild("Restock unavailable. Put materials in the hotbar."); return; }
        if (restockDelay > 0) { restockDelay--; return; }
        if (findHotbarItem(mc, neededItem) < 0 && inventoryHas(mc, neededItem)) {
            pauseBuild("Materials are in your inventory. Move them into the hotbar, then Start.");
            return;
        }
        switch (restockState) {
            case SEARCHING -> {
                if (findHotbarItem(mc, neededItem) >= 0) { resetRestock(); return; }
                restockChest = findNearestChest(mc, neededItem);
                if (restockChest == null) {
                    pauseBuild("No reachable restock chest. Put " + new ItemStack(neededItem).getHoverName().getString() + " in the hotbar, then Start.");
                    return;
                }
                restockState = RestockState.OPENING;
                restockRequested = false;
                restockDelay = chestDelay.get();
            }
            case OPENING -> {
                if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                    if (mc.player.containerMenu == mc.player.inventoryMenu) {
                        pauseBuild("Inventory opened manually. Close it, then Start.");
                        return;
                    }
                    ownedContainer = mc.player.containerMenu.containerId;
                    restockState = RestockState.TAKING;
                    restockDelay = com.autism.seedcracker.util.Humanizer.delay(chestDelay.get());
                    return;
                }
                if (restockRequested) return;
                // Face the chest with the eased engine at a raycast-visible point; only use once
                // converged (fabricated centre-hit + instant aim was a rotation-checker flag).
                Vec3 visible = com.autism.seedcracker.util.tunnel.LegitHitPoint.find(
                    mc, restockChest, Direction.UP, MAX_REACH);
                if (visible == null || mc.player.getEyePosition().distanceTo(visible) > MAX_REACH) {
                    searchedChests.add(restockChest);
                    restockState = RestockState.SEARCHING;
                    return;
                }
                BlockHitResult chestHit = new BlockHitResult(visible, Direction.UP, restockChest, false);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 tgt = chestHit.getLocation();
                double dx = tgt.x - eye.x, dy = tgt.y - eye.y, dz = tgt.z - eye.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                float[] rot = look.update(yaw, pitch);
                if (!com.autism.seedcracker.motion.RotationEngine.write(id(), com.autism.seedcracker.motion.RotationEngine.PRIORITY_INTERACT, rot[0], rot[1])) return;
                if (!look.isRotationsDone()) return; // keep easing next tick
                if (mc.gameMode != null) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, chestHit);
                    com.autism.seedcracker.compat.McCompat.swing(mc.player, InteractionHand.MAIN_HAND);
                    restockRequested = true;
                }
                restockDelay = com.autism.seedcracker.util.Humanizer.delay(chestDelay.get());
            }
            case TAKING -> {
                if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
                    resetRestock();
                    return;
                }
                var handler = mc.player.containerMenu;
                if (handler.containerId != ownedContainer || handler == mc.player.inventoryMenu) {
                    pauseBuild("Container changed. Put materials in the hotbar, then Start.");
                    return;
                }
                int containerSlots = handler.slots.size() - 36;
                boolean took = false;
                for (int i = 0; i < containerSlots; i++) {
                    var slot = handler.slots.get(i);
                    if (slot.hasItem() && slot.getItem().is(neededItem)) {
                        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(handler.containerId, i, 0,
                            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                        took = true;
                        break;
                    }
                }
                restockDelay = chestDelay.get();
                if (findHotbarItem(mc, neededItem) >= 0) {
                    closeScreen(mc);
                    resetRestock();
                } else if (!took) {
                    // this chest is empty of the item: mark + look for another
                    searchedChests.add(restockChest);
                    closeScreen(mc);
                    restockState = RestockState.SEARCHING;
                    restockDelay = chestDelay.get();
                }
            }
            default -> {}
        }
    }

    private void resetRestock() {
        closeScreen(Minecraft.getInstance());
        restockState = RestockState.NONE;
        restockChest = null;
        neededItem = null;
        restockDelay = restockTimeout = 0;
        restockRequested = false;
    }

    private void closeScreen(Minecraft mc) {
        if (ownedContainer >= 0 && mc.player != null && mc.player.containerMenu.containerId == ownedContainer) {
            mc.player.closeContainer();
            com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
        }
        ownedContainer = -1;
    }

    private BlockPos findNearestChest(Minecraft mc, Item item) {
        BlockPos pp = mc.player.blockPosition();
        int r = Math.min(chestRange.get(), (int) Math.ceil(MAX_REACH));
        BlockPos nearest = null;
        double nd = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            BlockPos p = pp.offset(x, y, z);
            if (searchedChests.contains(p) || !mc.level.hasChunkAt(p)
                    || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > MAX_REACH) continue;
            if (!(mc.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity)) continue;
            double d = pp.distSqr(p);
            if (d < nd) { nd = d; nearest = p; }
        }
        return nearest;
    }

    private static boolean inventoryHas(Minecraft mc, Item item) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return true;
        }
        return false;
    }

    private static int findHotbarItem(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    private static BlockPos rotatePos(BlockPos p, int rot) { return SchematicLoader.rotate(p, rot); }

    // ---- ghost render ----
    private void renderGhost(Minecraft mc) {
        List<AABB> placedBoxes = new ArrayList<>();
        List<AABB> currentBoxes = new ArrayList<>();
        List<AABB> missedBoxes = new ArrayList<>();
        List<AABB> futureBoxes = new ArrayList<>();
        int rot = rotationSteps.get();
        int count = 0;
        for (Map.Entry<BlockPos, BlockState> e : entries) {
            BlockPos worldPos = origin.offset(rotatePos(e.getKey(), rot));
            if (mc.player.distanceToSqr(Vec3.atCenterOf(worldPos)) > 200.0 * 200.0 || !mc.level.hasChunkAt(worldPos)) continue;
            if (++count > GHOST_LIMIT) break;
            AABB box = new AABB(worldPos);
            int y = e.getKey().getY();
            boolean isPlaced = matches(mc.level.getBlockState(worldPos), e.getValue());
            if (isPlaced) placedBoxes.add(box);
            else if (y == currentLayer) currentBoxes.add(box);
            else if (y < currentLayer) missedBoxes.add(box);
            else futureBoxes.add(box);
        }
        feed(id() + ":p", placedBoxes, placedColor.get());
        feed(id() + ":c", currentBoxes, currentColor.get());
        feed(id() + ":m", missedBoxes, missedColor.get());
        feed(id() + ":f", futureBoxes, futureColor.get());
        if (preview != null) {
            BlockPos other = origin.offset(rotatePos(new BlockPos(preview.width() - 1, preview.height() - 1, preview.length() - 1), rot));
            AABB bounds = new AABB(Math.min(origin.getX(), other.getX()), origin.getY(), Math.min(origin.getZ(), other.getZ()),
                Math.max(origin.getX(), other.getX()) + 1, other.getY() + 1, Math.max(origin.getZ(), other.getZ()) + 1);
            feed(id() + ":bounds", List.of(bounds), 0xFFFFFFFF, 0);
            feed(id() + ":origin", List.of(new AABB(origin)), 0xFFFFCC33);
        }
    }

    private void clearGhost() {
        ghostLayers.clear();
        for (String suffix : List.of(":p", ":c", ":m", ":f", ":bounds", ":origin")) BlockEspRenderer.clearBox(id() + suffix);
    }

    private void feed(String key, List<AABB> boxes, int argb) { feed(key, boxes, argb, 0x2e); }
    private void feed(String key, List<AABB> boxes, int argb, int fill) {
        if (boxes.isEmpty()) { ghostLayers.remove(key); BlockEspRenderer.clearBox(key); }
        else {
            ghostLayers.put(key, new GhostLayer(boxes, argb, fill));
            BlockEspRenderer.feedBoxes(key, boxes, argb, fill);
        }
    }

    // ---- transactional background loading and setup summaries ----
    public Path pathBase() { return dihclient.DihClientAddon.FOLDER.toPath().toAbsolutePath().normalize(); }
    public List<Path> schematicFolders() {
        return List.of(Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("schematics"), pathBase().resolve("schematics"));
    }
    public String selectedPath() { return schematicPath.get(); }
    public List<Path> recentSelections() { return SchematicFiles.recent(recentFiles.get()); }
    public boolean isLoading() { return loading != null; }
    public boolean hasPreview() { return loaded; }
    public long previewRevision() { return previewRevision; }
    public BlockPos buildOrigin() { return origin; }
    public int rotation() { return rotationSteps.get(); }
    public boolean isBuilding() { return intent.running(); }
    public String fileNotice() { return fileNotice; }

    public void loadPreview(String path) {
        cancelLoad();
        previewOnly("Loading preview only. Placement and movement are stopped.");
        setTransient("schematic-path", path == null ? "" : path);
        Path base = pathBase();
        try {
            loading = SchematicFiles.submit(() -> SchematicLoader.load(base, path));
            fileNotice = "Reading local file... (16 MiB file / 64 MiB NBT / 65,536 blocks maximum)";
        } catch (java.util.concurrent.RejectedExecutionException e) {
            intent.preview("File queue busy. Retry Load preview after the previous request stops.");
            fileNotice = "Previous file operation is still stopping. Wait briefly and retry Load preview.";
        }
    }

    public void cancelLoad() {
        if (loading == null) return;
        loading.cancel(true);
        loading = null;
        intent.preview("Load cancelled. Previous preview kept; choose another file or Start explicitly.");
        fileNotice = "File load cancelled; previous preview kept.";
    }

    public void updateSetup() {
        observeWorld();
        if (observedRotation != rotationSteps.get()) invalidateGeometry();
        if (loading != null && loading.isDone()) {
            Future<SchematicLoader.Loaded> completed = loading;
            loading = null;
            try {
                SchematicLoader.Loaded next = completed.get();
                preview = next;
                schematic = next.blocks();
                entries = next.entries();
                requiredMaterials.clear();
                requiredMaterials.putAll(next.materials());
                loaded = true;
                previewRevision++;
                totalBlocks = schematic.size();
                minY = 0;
                maxY = next.height() - 1;
                invalidateGeometry();
                intent.preview("Preview loaded. Check origin, orientation and materials, then press Start.");
                setTransient("schematic-path", next.file().toString());
                setTransient("recent-files", SchematicFiles.remember(recentFiles.get(), next.file()));
                fileNotice = "Preview loaded: " + next.file().getFileName() + ". No actions until Start."
                    + (next.skipped() > 0 ? " WARNING: " + next.skipped() + " unsupported entries omitted." : "");
                try { persistConfiguredState(); }
                catch (RuntimeException e) { fileNotice += " Could not save recent files: " + e.getMessage(); }
            } catch (java.util.concurrent.CancellationException e) {
                intent.preview("Load cancelled. Choose a file or Start the previous preview explicitly.");
                fileNotice = "Load cancelled; previous preview kept.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                intent.preview("Load interrupted. Retry Load preview.");
                fileNotice = "Load interrupted; previous preview kept.";
            } catch (java.util.concurrent.ExecutionException e) {
                intent.preview("Replacement failed. Previous preview kept. Fix the path or choose another file.");
                String detail = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                fileNotice = "Load failed; previous preview kept. " + (detail == null ? "Invalid or oversized NBT file." : detail);
            }
        }
        auditWorld();
    }

    private long lastAuditTick = Long.MIN_VALUE;
    private void clearAudit() {
        audited = false;
        auditIndex = auditMatched = matchedBlocks = auditUnknown = unknownBlocks = 0;
        lastAuditTick = Long.MIN_VALUE;
        auditMatches.clear();
        worldMatches = Map.of();
    }

    private void auditWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (!loaded || origin == null || mc.level == null || entries.isEmpty()) return;
        long now = mc.level.getGameTime();
        if (lastAuditTick == now) return;
        lastAuditTick = now;
        int end = Math.min(entries.size(), auditIndex + 1024);
        for (; auditIndex < end; auditIndex++) {
            var entry = entries.get(auditIndex);
            BlockPos pos = origin.offset(rotatePos(entry.getKey(), rotationSteps.get()));
            if (!mc.level.hasChunkAt(pos)) { auditUnknown++; continue; }
            if (matches(mc.level.getBlockState(pos), entry.getValue())) {
                auditMatches.merge(entry.getValue().getBlock().asItem(), 1, Integer::sum);
                auditMatched++;
            }
        }
        if (auditIndex == entries.size()) {
            worldMatches = Map.copyOf(auditMatches);
            matchedBlocks = auditMatched;
            unknownBlocks = auditUnknown;
            audited = true;
            auditMatches.clear();
            auditIndex = auditMatched = auditUnknown = 0;
        }
    }

    public record Material(String name, int total, int remaining, int inventory, int hotbar, int missing) {}
    public List<Material> materialSummary() {
        Map<Item, Integer> inventory = new HashMap<>(), hotbar = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            inventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
            if (i < 9) hotbar.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<Material> result = new ArrayList<>();
        for (var entry : requiredMaterials.entrySet()) {
            int remaining = Math.max(0, entry.getValue() - worldMatches.getOrDefault(entry.getKey(), 0));
            int have = inventory.getOrDefault(entry.getKey(), 0);
            result.add(new Material(new ItemStack(entry.getKey()).getHoverName().getString(), entry.getValue(), remaining,
                have, hotbar.getOrDefault(entry.getKey(), 0), Math.max(0, remaining - have)));
        }
        result.sort(java.util.Comparator.comparing(Material::name));
        return result;
    }

    public String previewName() { return preview == null ? "No schematic loaded" : preview.file().getFileName().toString(); }
    public String sizeText() {
        if (preview == null) return "Choose a file first";
        boolean swap = (rotationSteps.get() & 1) != 0;
        return (swap ? preview.length() : preview.width()) + " x " + preview.height() + " x "
            + (swap ? preview.width() : preview.length()) + " blocks (X x Y x Z); " + totalBlocks + " non-air";
    }
    public String boundsText() {
        if (origin == null || preview == null) return "Origin not set in this world";
        BlockPos other = origin.offset(rotatePos(new BlockPos(preview.width() - 1, preview.height() - 1, preview.length() - 1), rotationSteps.get()));
        return "Bounds: " + Math.min(origin.getX(), other.getX()) + ", " + origin.getY() + ", " + Math.min(origin.getZ(), other.getZ())
            + " to " + Math.max(origin.getX(), other.getX()) + ", " + other.getY() + ", " + Math.max(origin.getZ(), other.getZ());
    }
    public String progressText() {
        if (origin == null) return "Set an origin to count blocks already in the world.";
        if (!audited) return "Checking world: " + auditIndex + "/" + totalBlocks + "; material needs are provisional.";
        return "In world: " + matchedBlocks + "/" + totalBlocks + " matching block types; " + unknownBlocks + " unloaded. Layer "
            + Math.min(maxY - minY + 1, currentLayer - minY + 1) + "/" + (maxY - minY + 1);
    }
    public String controlsText() {
        return "Walking: on | Mining: " + (clearBlocks.get() ? "ON" : "off") + " | Scaffold: "
            + (scaffold.get() ? "ON" : "off") + " | Restock: " + (restock.get() ? "ON" : "off");
    }
    public String statusText() { return intent.phase() + ": " + intent.reason(); }
    public String nextAction() {
        if (loading != null) return "Wait for Load preview, or cancel it. The previous preview is safe.";
        if (!loaded) return "Choose a file, browse, paste a path or select Recent; then Load preview.";
        if (origin == null) return "Join a world and set the origin on step 2.";
        return intent.running() ? "Open setup / Pause to take control. Manual input also pauses." : "Review materials on step 3, then Start. Closing setup never resumes.";
    }

    private static void msg(Minecraft mc, String s) {
        dihclient.util.DihClientMessaging.sendPrefixed("§d[Schematic] §f" + s);
    }

    @Override public String info() {
        if (loading != null) return "loading preview";
        if (!loaded) return "preview only - Open setup";
        return intent.phase().name().toLowerCase(java.util.Locale.ROOT) + " " + matchedBlocks + "/" + totalBlocks
            + (origin == null ? " - set origin" : " - " + (rotationSteps.get() * 90) + " deg");
    }
}
