package com.autism.seedcracker.gui.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

import com.autism.seedcracker.modules.SchematicBuilderModule;
import com.autism.seedcracker.schematic.SchematicFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** Vanilla widgets only. Closing any step leaves the builder paused, never implicitly resumes it. */
public final class SchematicSetupScreen extends Screen {
    private enum Source { SCHEMATICS, BROWSE, RECENT }
    private record BrowseResult(SchematicFiles.Listing listing, Path folder) {}

    private final Screen parent;
    private final SchematicBuilderModule module;
    private int step;
    private Source source = Source.SCHEMATICS;
    private String pathText;
    private String search = "";
    private final String[] coordinates = {"0", "0", "0"};
    private String localNotice = "";
    private String listingNotice = "";
    private Path browseFolder;
    private List<SchematicFiles.Entry> files = List.of();
    private Future<BrowseResult> listing;
    private EditBox pathField;
    private ChoiceList choices;
    private Button loadButton, startButton, upButton;
    private StringWidget statusLine, actionLine;
    private int refreshTicks;
    private boolean scanRequested;
    private long seenRevision;

    public SchematicSetupScreen(Screen parent, SchematicBuilderModule module, boolean review) {
        super(Component.literal("Schematic Builder - preview first"));
        this.parent = parent;
        this.module = module;
        step = review ? 2 : 0;
        pathText = module.selectedPath();
        seenRevision = module.previewRevision();
        readOrigin();
        module.pauseBuild("Setup open. Only Start closes setup and begins placement / movement.");
    }

    @Override protected void init() {
        pathField = null;
        choices = null;
        loadButton = null;
        upButton = null;
        int width = contentWidth();
        int tabWidth = (width - 8) / 3;
        String[] names = {"1. File", "2. Origin", "3. Materials"};
        for (int i = 0; i < names.length; i++) {
            int selected = i;
            Button tab = button(names[i], "Choose step " + (i + 1) + ". This never starts building.", b -> changeStep(selected), tabWidth);
            tab.setPosition(12 + i * (tabWidth + 4), 23);
            tab.active = step != i;
            addRenderableWidget(tab);
        }
        if (step == 0) buildFilePage();
        else if (step == 1) buildOriginPage();
        else buildReviewPage();

        statusLine = new StringWidget(12, height - 67, width, 10, Component.empty(), font);
        statusLine.setMaxWidth(width);
        addRenderableWidget(statusLine);
        actionLine = new StringWidget(12, height - 53, width, 10, Component.empty(), font);
        actionLine.setMaxWidth(width);
        addRenderableWidget(actionLine);
        int buttonWidth = (width - 12) / 4;
        startButton = button(step == 2 ? "Start" : "Review & Start",
            "Start closes setup and permits walking / placement with the displayed options. Never starts on load, enable or close.",
            b -> start(), buttonWidth);
        startButton.setPosition(12, height - 29);
        addRenderableWidget(startButton);
        Button pause = button("Pause", "Release movement, aim, mining and pending interactions. Explicit Start is required again.",
            b -> { module.pauseBuild("Paused by you. Review and press Start to continue."); localNotice = "Paused. No automatic resume."; }, buttonWidth);
        pause.setPosition(16 + buttonWidth, height - 29);
        addRenderableWidget(pause);
        Button stop = button("Stop", "Reset the build pass and release controls; keep the schematic and origin preview.",
            b -> { module.stopBuild(); localNotice = "Stopped. File and preview kept."; }, buttonWidth);
        stop.setPosition(20 + buttonWidth * 2, height - 29);
        addRenderableWidget(stop);
        Button close = button("Close", "Return to the previous menu. The builder stays paused. Escape does the same.", b -> onClose(), buttonWidth);
        close.setPosition(24 + buttonWidth * 3, height - 29);
        addRenderableWidget(close);
        updateStatus();
        if (pathField != null) setInitialFocus(pathField);
    }

    private int contentWidth() { return Math.max(160, width - 24); }
    private Button button(String label, String tip, Button.OnPress action, int width) {
        return Button.builder(Component.literal(label), action).size(width, 20)
            .tooltip(Tooltip.create(Component.literal(tip))).build();
    }

    private void buildFilePage() {
        int width = contentWidth();
        addRenderableWidget(new StringWidget(12, 47, width, 10,
            Component.literal("File path (Ctrl+V); folder paths work with Browse"), font));
        pathField = new EditBox(font, 12, 59, width, 20, Component.literal("Schematic file or folder path"));
        pathField.setMaxLength(SchematicFiles.MAX_PATH_LENGTH);
        pathField.setValue(pathText == null ? "" : pathText);
        pathField.setResponder(value -> pathText = value);
        pathField.setTooltip(Tooltip.create(Component.literal("Absolute path, quoted path, or path relative to the autism folder. Loading only creates a preview.")));
        addRenderableWidget(pathField);
        int small = (width - 12) / 4;
        loadButton = button("Load preview", "Load the selected file safely; invalid replacements keep the last preview. Enter in the path field also loads.",
            b -> { if (module.isLoading()) module.cancelLoad(); else loadSelected(); }, small);
        loadButton.setPosition(12, 83);
        addRenderableWidget(loadButton);
        Button folder = button("Schematics", "Search game/schematics and autism/schematics, including four folder levels.",
            b -> { source = Source.SCHEMATICS; refreshFiles(null); }, small);
        folder.setPosition(16 + small, 83);
        addRenderableWidget(folder);
        Button browse = button("Browse", "Open the folder in the path field, or its parent. Double-click a folder or use Enter to open it.",
            b -> browseFromPath(), small);
        browse.setPosition(20 + small * 2, 83);
        addRenderableWidget(browse);
        Button recent = button("Recent", "The ten most recently loaded valid files, saved in the module configuration. Missing files report errors when loaded.",
            b -> { source = Source.RECENT; refreshFiles(null); }, small);
        recent.setPosition(24 + small * 3, 83);
        addRenderableWidget(recent);
        EditBox filter = new EditBox(font, 12, 107, width - 124, 20, Component.literal("Search filenames and paths"));
        filter.setMaxLength(128);
        filter.setHint(Component.literal("Search filenames / paths"));
        filter.setValue(search);
        filter.setResponder(value -> { search = value; refreshRows(); });
        addRenderableWidget(filter);
        Button refresh = button("Refresh", "Refresh the bounded file list (F5). No internet access or file modifications.", b -> refreshFiles(browseFolder), 62);
        refresh.setPosition(width - 108, 107);
        addRenderableWidget(refresh);
        upButton = button("Up", "Browse the parent folder. Paste another drive or folder path to browse elsewhere.", b -> {
            if (browseFolder != null && browseFolder.getParent() != null) {
                source = Source.BROWSE;
                pathField.setValue(browseFolder.getParent().toString());
                refreshFiles(browseFolder.getParent());
            }
        }, 54);
        upButton.setPosition(width - 42, 107);
        addRenderableWidget(upButton);
        choices = new ChoiceList(width, Math.max(20, height - 207), 131);
        addRenderableWidget(choices);
        refreshRows();
        if (!scanRequested && listing == null) refreshFiles(browseFolder);
    }

    private void buildOriginPage() {
        int width = contentWidth() - 12;
        LinearLayout form = LinearLayout.vertical().spacing(6);
        form.addChild(text(module.previewName() + "\n" + module.sizeText(), width));
        form.addChild(text("Anchor = original minimum corner, before rotation. Crosshair uses the block ABOVE the block you looked at. Feet uses your standing block.", width));
        LinearLayout fields = LinearLayout.horizontal().spacing(6);
        String[] axes = {"X", "Y", "Z"};
        int fieldWidth = (width - 12) / 3;
        for (int i = 0; i < axes.length; i++) {
            int axis = i;
            LinearLayout column = LinearLayout.vertical().spacing(2);
            column.addChild(new StringWidget(Component.literal(axes[i]), font));
            EditBox field = new EditBox(font, fieldWidth, 20, Component.literal("Origin " + axes[i]));
            field.setMaxLength(11);
            field.setValue(coordinates[i]);
            field.setResponder(value -> coordinates[axis] = value);
            field.setTooltip(Tooltip.create(Component.literal("Whole block coordinate; press Apply XYZ to update the preview.")));
            column.addChild(field);
            fields.addChild(column);
        }
        form.addChild(fields);
        boolean world = Minecraft.getInstance().level != null && Minecraft.getInstance().player != null;
        LinearLayout anchors = LinearLayout.horizontal().spacing(4);
        Button apply = button("Apply XYZ", "Apply these coordinates, invalidate old targets and pause any build.", b -> applyCoordinates(), (width - 8) / 3);
        Button crosshair = button("Crosshair", "Set above the last block you looked at; falls back to your feet if there was no block hit.", b -> {
            module.useCrosshairOrigin(); readOrigin(); rebuildWidgets();
        }, (width - 8) / 3);
        Button feet = button("Feet", "Set the anchor to your standing block.", b -> { module.useFeetOrigin(); readOrigin(); rebuildWidgets(); }, (width - 8) / 3);
        apply.active = crosshair.active = feet.active = world;
        anchors.addChild(apply); anchors.addChild(crosshair); anchors.addChild(feet);
        form.addChild(anchors);
        Button rotate = button("Rotation: " + (module.rotation() * 90) + " degrees", "Rotate positions around the anchor. Changing this clears stale targets and pauses.", b -> {
            module.setRotation(module.rotation() + 1); rebuildWidgets();
        }, width);
        form.addChild(rotate);
        form.addChild(text(module.boundsText(), width));
        form.addChild(text("Positions and block types only: facing, stair half, log axis, block entities and air clearing are NOT reproduced. Check directional blocks manually.", width));
        Button preview = button("Preview in world (no building)", "Close setup and show the ghost. Reopen via Automation > Schematic Builder > Open setup, or its bound setup key.", b -> {
            module.enablePreview(); Minecraft.getInstance().gui.setScreen(null);
        }, width);
        preview.active = world && module.hasPreview() && module.buildOrigin() != null;
        form.addChild(preview);
        form.addChild(text("Yellow = origin; white = full bounds; cyan = current layer; green = matching; red = missed; gray = future. Large ghosts show up to 4096 nearby boxes. Scroll or Tab to reach every control.", width));
        ScrollableLayout scroller = new ScrollableLayout(Minecraft.getInstance(), form, Math.max(30, height - 125));
        scroller.setMinWidth(width);
        scroller.setX(12);
        scroller.setY(48);
        scroller.arrangeElements();
        scroller.visitWidgets(this::addRenderableWidget);
    }

    private MultiLineTextWidget text(String value, int width) {
        MultiLineTextWidget widget = new MultiLineTextWidget(Component.literal(value), font).setMaxWidth(width);
        return widget;
    }

    private void buildReviewPage() {
        int width = contentWidth();
        Button safe = button("Apply safe preset", "Pause; human walking, visible aim, strict layers. No mining, scaffold or chest access. Does not reset your file or origin.",
            b -> { module.applySafePreset(); localNotice = "Safe preset applied. Inspect the preview, then Start."; refreshReview(); }, width);
        safe.setPosition(12, 48);
        addRenderableWidget(safe);
        choices = new ChoiceList(width, Math.max(20, height - 150), 74);
        addRenderableWidget(choices);
        refreshReview();
    }

    private void refreshReview() {
        if (step != 2 || choices == null) return;
        double scroll = choices.scrollAmount();
        int selectedIndex = choices.children().indexOf(choices.getSelected());
        boolean focused = choices.isFocused();
        List<Choice> rows = new ArrayList<>();
        addWrapped(rows, module.previewName(), module.sizeText());
        addWrapped(rows, "Origin / rotation", (module.buildOrigin() == null ? "Not set" : module.buildOrigin().toShortString()) + "; " + (module.rotation() * 90) + " degrees. " + module.boundsText());
        addWrapped(rows, "Progress", module.progressText());
        addWrapped(rows, "Start permits", module.controlsText());
        addWrapped(rows, "Advanced options", "Change optional mining, scaffolding, restock and timing in the module's Show advanced controls. Scaffold blocks are extra, not included in material counts.");
        addWrapped(rows, "Orientation limitation", "Positions / block types only; facing, half and axis need manual checks. Air and block-entity data are not built.");
        addWrapped(rows, "Materials", "Need = not already present. Have = inventory. Bar = hotbar. Missing = max(0, Need - Have). Move needed items to the hotbar before Start.");
        for (var material : module.materialSummary()) {
            addWrapped(rows, material.name() + (material.missing() > 0 ? " - MISSING " + material.missing() : ""),
                "Total " + material.total() + " | Need " + material.remaining() + " | Have " + material.inventory()
                    + " | Bar " + material.hotbar() + " | Missing " + material.missing());
        }
        if (!module.fileNotice().isEmpty()) addWrapped(rows, "File status", module.fileNotice());
        addWrapped(rows, "Next action", module.startProblem().isEmpty() ? "Start closes setup and begins. Pause/Stop or manual movement/mouse input cancels pending actions. Closing this screen alone NEVER starts." : module.startProblem());
        choices.replaceEntries(rows);
        if (selectedIndex >= 0 && selectedIndex < rows.size()) {
            choices.setSelected(rows.get(selectedIndex));
            if (focused) choices.setFocused(rows.get(selectedIndex));
        }
        choices.setScrollAmount(scroll);
    }

    private void addWrapped(List<Choice> rows, String title, String detail) {
        int max = Math.max(60, contentWidth() - 26);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(detail), max);
        if (lines.isEmpty()) rows.add(new Choice(title, "", null));
        for (int i = 0; i < lines.size(); i++) {
            Choice row = new Choice(i == 0 ? title : "", "", null);
            row.formattedDetail = lines.get(i);
            row.narration = (i == 0 ? title + ". " : "") + detail;
            rows.add(row);
        }
    }

    private void browseFromPath() {
        source = Source.BROWSE;
        try {
            Path candidate = pathText == null || pathText.isBlank() ? module.schematicFolders().getFirst() : SchematicFiles.resolve(module.pathBase(), pathText);
            refreshFiles(candidate);
        } catch (IOException e) { localNotice = e.getMessage(); }
    }

    private void refreshFiles(Path candidate) {
        scanRequested = true;
        cancelListing();
        localNotice = "";
        if (source == Source.RECENT) {
            files = module.recentSelections().stream().map(path -> new SchematicFiles.Entry(path, false)).toList();
            listingNotice = files.isEmpty() ? "No recent selections yet. Load a file to remember it." : "Recent successful loads. Select one, then Load preview.";
            refreshRows();
            return;
        }
        List<Path> roots = module.schematicFolders();
        boolean recursive = source == Source.SCHEMATICS;
        Path requested = candidate == null ? roots.getFirst() : candidate;
        try {
            listing = SchematicFiles.submit(() -> {
                Path folder = recursive ? null : Files.isDirectory(requested) ? requested : requested.getParent();
                if (!recursive && folder == null) throw new IOException("Paste an existing folder path to browse.");
                return new BrowseResult(SchematicFiles.scan(recursive ? roots : List.of(folder), recursive), folder);
            });
            listingNotice = "Scanning local folders...";
        } catch (java.util.concurrent.RejectedExecutionException e) {
            listingNotice = "Another file operation is stopping. Wait briefly, then Refresh.";
        }
    }

    private void refreshRows() {
        if (step != 0 || choices == null) return;
        String filter = search.toLowerCase(Locale.ROOT).trim();
        List<Choice> rows = new ArrayList<>();
        for (SchematicFiles.Entry entry : files) {
            if (!entry.path().toString().toLowerCase(Locale.ROOT).contains(filter)) continue;
            String name = entry.path().getFileName() == null ? entry.path().toString() : entry.path().getFileName().toString();
            rows.add(new Choice((entry.directory() ? "[Folder] " : "") + name, entry.path().toString(), entry));
        }
        if (rows.isEmpty()) rows.add(new Choice("No matches", "Try Refresh, Browse, Recent, or paste a path above.", null));
        choices.replaceEntries(rows);
    }

    private void loadSelected() {
        localNotice = "";
        cancelListing();
        module.loadPreview(pathText);
    }

    private void readOrigin() {
        BlockPos p = module.buildOrigin();
        if (p == null && Minecraft.getInstance().player != null) p = Minecraft.getInstance().player.blockPosition();
        if (p == null) return;
        coordinates[0] = Integer.toString(p.getX());
        coordinates[1] = Integer.toString(p.getY());
        coordinates[2] = Integer.toString(p.getZ());
    }

    private void applyCoordinates() {
        try {
            BlockPos p = new BlockPos(Integer.parseInt(coordinates[0].trim()), Integer.parseInt(coordinates[1].trim()), Integer.parseInt(coordinates[2].trim()));
            localNotice = module.setBuildOrigin(p) ? "Origin applied. Preview in world before Start." : module.fileNotice();
            rebuildWidgets();
        } catch (NumberFormatException e) { localNotice = "X, Y and Z must be whole numbers within the world's bounds."; }
    }

    private void changeStep(int next) {
        if (next != 0) cancelListing();
        else if (files.isEmpty()) scanRequested = false;
        step = next;
        localNotice = "";
        rebuildWidgets();
    }

    private void start() {
        if (step != 2) { changeStep(2); return; }
        if (!module.startProblem().isEmpty()) { localNotice = module.startProblem(); return; }
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(null);
        if (!module.startBuild()) { mc.gui.setScreen(this); localNotice = module.fileNotice(); }
    }

    @Override public void tick() {
        module.updateSetup();
        if (listing != null && listing.isDone()) {
            Future<BrowseResult> completed = listing;
            listing = null;
            try {
                BrowseResult result = completed.get();
                files = result.listing().entries();
                browseFolder = result.folder();
                listingNotice = (browseFolder == null ? "" : "Folder: " + browseFolder + ". ") + result.listing().notice();
                refreshRows();
            } catch (java.util.concurrent.CancellationException e) {
                listingNotice = "Scan cancelled. Refresh to try again.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listingNotice = "Scan interrupted. Refresh to try again.";
            } catch (java.util.concurrent.ExecutionException e) {
                listingNotice = "Cannot browse: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            }
        }
        if (module.previewRevision() != seenRevision) {
            seenRevision = module.previewRevision();
            pathText = module.selectedPath();
            readOrigin();
            if (step == 0) changeStep(1);
            else rebuildWidgets();
        }
        if (++refreshTicks % 20 == 0) refreshReview();
        updateStatus();
    }

    private void updateStatus() {
        if (statusLine == null) return;
        statusLine.setMessage(Component.literal(module.statusText()));
        statusLine.setTooltip(Tooltip.create(Component.literal(module.statusText())));
        String notice = !localNotice.isEmpty() ? localNotice : module.isLoading() || !module.fileNotice().isEmpty()
            ? module.fileNotice() : step == 0 && !listingNotice.isEmpty() ? listingNotice : module.nextAction();
        actionLine.setMessage(Component.literal(notice).withStyle(net.minecraft.ChatFormatting.YELLOW));
        actionLine.setTooltip(Tooltip.create(Component.literal(notice + (step == 0 ? "\n" + listingNotice : ""))));
        if (loadButton != null) loadButton.setMessage(Component.literal(module.isLoading() ? "Cancel load" : "Load preview"));
        if (upButton != null) upButton.active = listing == null && source == Source.BROWSE && browseFolder != null && browseFolder.getParent() != null;
        startButton.active = step != 2 || module.startProblem().isEmpty();
        if (step == 2 && !module.startProblem().isEmpty()) startButton.setTooltip(Tooltip.create(Component.literal(module.startProblem())));
    }

    private void cancelListing() {
        if (listing != null) { listing.cancel(true); listing = null; }
    }

    @Override public void removed() { cancelListing(); }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public Component getNarrationMessage() {
        return Component.literal(getTitle().getString() + ". Step " + (step + 1) + ". " + module.statusText() + ". " + module.nextAction());
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (step == 0 && event.key() == InputConstants.KEY_F5) { refreshFiles(browseFolder); return true; }
        if (pathField != null && pathField.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
            loadSelected(); return true;
        }
        return super.keyPressed(event);
    }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xff141820);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.text(font, getTitle(), 12, 8, 0xffffffff, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private final class ChoiceList extends ObjectSelectionList<Choice> {
        ChoiceList(int width, int height, int top) { super(Minecraft.getInstance(), width, height, top, 32); setX(12); }
        @Override public int getRowWidth() { return getWidth() - 16; }
        @Override public void setSelected(Choice row) {
            super.setSelected(row);
            if (row != null && row.file != null && !row.file.directory() && pathField != null) pathField.setValue(row.file.path().toString());
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER || event.key() == InputConstants.KEY_SPACE)
                    && getSelected() != null) { getSelected().activate(); return true; }
            return super.keyPressed(event);
        }
    }

    private final class Choice extends ObjectSelectionList.Entry<Choice> {
        final String title, detail;
        final SchematicFiles.Entry file;
        String narration;
        net.minecraft.util.FormattedCharSequence formattedDetail;
        Choice(String title, String detail, SchematicFiles.Entry file) {
            this.title = title; this.detail = detail; this.file = file;
            narration = title + ". " + detail + (file == null ? "" : file.directory() ? ". Enter opens this folder." : ". Enter loads a safe preview.");
        }
        void activate() {
            if (file == null) return;
            if (file.directory()) {
                source = Source.BROWSE;
                if (pathField != null) pathField.setValue(file.path().toString());
                refreshFiles(file.path());
            }
            else { pathText = file.path().toString(); if (pathField != null) pathField.setValue(pathText); loadSelected(); }
        }
        @Override public Component getNarration() { return Component.literal(narration); }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) return false;
            choices.setSelected(this);
            if (doubleClick) activate();
            return true;
        }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int width = Math.max(20, getContentWidth() - 4);
            graphics.text(font, font.plainSubstrByWidth(title, width), getContentX() + 2, getContentY() + 2, 0xffffffff, false);
            if (formattedDetail == null) graphics.text(font, font.plainSubstrByWidth(detail, width), getContentX() + 2, getContentY() + 14, 0xffd7dce4, false);
            else graphics.text(font, formattedDetail, getContentX() + 2, getContentY() + 14, 0xffd7dce4, false);
        }
    }
}
