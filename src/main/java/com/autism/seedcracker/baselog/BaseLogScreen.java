package com.autism.seedcracker.baselog;

import java.io.IOException;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Local, keyboard-accessible review of candidate coordinates; no travel or detection actions. */
public final class BaseLogScreen extends com.autism.seedcracker.gui.AddonScreen {
    private final Screen parent;
    private BaseLog.Snapshot snapshot;
    private List<BaseLog.Entry> view = List.of();
    private BaseLog.Entry selected;
    private EditBox search, statusField;
    private double[] sortOrigin;
    private CandidateList candidates;
    private Button copyCoords, copyInfo, delete;
    private String query = "", status = "";
    private boolean nearest, readFailed;

    public BaseLogScreen(Screen parent) {
        super(Component.literal("Base Log Browser"));
        this.parent = parent;
    }

    @Override protected void init() {
        super.init();
        if (snapshot == null) load();
        int contentWidth = screenWidth() - 24;
        int searchWidth = contentWidth - 188;
        search = new EditBox(font, 12, 40, searchWidth, 20, Component.literal("Search candidate logs"));
        search.setMaxLength(128);
        search.setHint(Component.literal("coords / dim / block / date"));
        search.setValue(query);
        search.setTooltip(Tooltip.create(Component.literal("All words must match; case-insensitive. Searches recorded fields only.")));
        addRenderableWidget(search);
        addRenderableWidget(button(Component.literal(sortLabel()), button -> {
            nearest = !nearest;
            button.setMessage(Component.literal(sortLabel()));
            refilter();
        }).bounds(16 + searchWidth, 40, 120, 20).tooltip(Tooltip.create(Component.literal(
            "Newest = last in file. X/Z near = numerical distance from your current X/Z only.\n"
                + "World identity was not recorded. This is not a verified same-world distance.\n"
                + "Without a player, X/Z near falls back to newest."))).build());
        addRenderableWidget(button(Component.literal("Refresh"), button -> { load(); refilter(); })
            .bounds(screenWidth() - 72, 40, 60, 20).build());

        candidates = new CandidateList(contentWidth, Math.max(32, screenHeight() - 178), 70);
        addRenderableWidget(candidates);
        statusField = new EditBox(font, 12, screenHeight() - 50, contentWidth, 20, Component.literal("Local log status"));
        statusField.setMaxLength(4096);
        statusField.setEditable(false);
        addRenderableWidget(statusField);
        int actionWidth = (contentWidth - 12) / 4;
        int y = screenHeight() - 24;
        copyCoords = addRenderableWidget(button(Component.literal("Copy XYZ"), button -> copy(false))
            .bounds(12, y, actionWidth, 20).build());
        copyInfo = addRenderableWidget(button(Component.literal("Copy Info"), button -> copy(true))
            .bounds(16 + actionWidth, y, actionWidth, 20).build());
        delete = addRenderableWidget(button(Component.literal("Delete..."), button -> confirmDelete())
            .bounds(20 + actionWidth * 2, y, actionWidth, 20).build());
        addRenderableWidget(button(Component.literal("Close"), button -> onClose())
            .bounds(24 + actionWidth * 3, y, actionWidth, 20).build());
        search.setResponder(value -> { query = value; refilter(); });
        refilter();
    }

    private String sortLabel() { return nearest ? "Sort: X/Z near" : "Sort: Newest"; }

    private void load() {
        try {
            BaseLog.Snapshot fresh = BaseLog.read(BaseLog.file());
            // A refresh must never silently select a different duplicate or shifted record.
            if (snapshot == null || !fresh.text().equals(snapshot.text())) {
                snapshot = fresh;
                selected = null;
            }
            readFailed = false;
            status = "Read " + fresh.entries().size() + " candidate(s)."
                + (fresh.skipped() == 0 ? "" : " " + fresh.skipped() + " unreadable line(s) kept in file.");
        } catch (IOException | SecurityException exception) {
            readFailed = true;
            status = "Read failed: " + exception.getMessage();
            if (snapshot == null) snapshot = new BaseLog.Snapshot("", List.of(), 0);
        }
    }

    private void refilter() {
        if (candidates == null) return;
        sortOrigin = nearest ? BaseLog.playerPos() : null;
        view = BaseLog.filter(snapshot.entries(), query, nearest, sortOrigin);
        selected = BaseLog.retainSelection(selected, view);
        candidates.replaceRows();
        updateActions();
    }

    private void updateActions() {
        if (copyCoords == null) return;
        copyCoords.active = copyInfo.active = selected != null;
        delete.active = selected != null && !readFailed;
        copyInfo.setTooltip(selected == null ? null : Tooltip.create(Component.literal(selected.details())));
        delete.setTooltip(Tooltip.create(Component.literal("Confirm deletion of one occurrence only. Keeps an original-file backup.")));
    }

    private void copy(boolean details) {
        if (selected == null) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(details ? selected.details() : selected.coord());
        String message = details ? "Copied recorded candidate details (unverified)." : "Copied exact XYZ: " + selected.coord();
        if (readFailed) toast(message, 0xffc3d6eb);
        else status = message;
    }

    private void confirmDelete() {
        if (selected == null || readFailed) return;
        BaseLog.Entry target = selected;
        BaseLog.Snapshot original = snapshot;
        ConfirmScreen confirmation = new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    var backup = BaseLog.remove(BaseLog.file(), original, target);
                    load();
                    if (!readFailed) status = "Deleted one candidate. Backup: " + backup.getFileName();
                } catch (IOException | SecurityException exception) {
                    status = "Delete failed: " + exception.getMessage();
                }
            }
            Minecraft.getInstance().gui.setScreen(this);
        }, Component.literal("Delete one candidate?"), Component.literal(target.details()
            + "\nOnly this log occurrence is removed. An original-file backup is kept."),
            Component.literal("Delete one"), Component.literal("Cancel"));
        Minecraft.getInstance().gui.setScreen(confirmation);
        confirmation.setDelay(20);
    }

    @Override protected void renderContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.centeredText(font, getTitle(), screenWidth() / 2, 8, 0xffffffff);
        line(graphics, "Candidates only - not confirmed bases. World/server not recorded.", 22, 0xffffd17a);
        int y = screenHeight() - 104;
        if (selected == null) {
            line(graphics, view.size() + " / " + snapshot.entries().size() + " candidates. Select a row to review.", y, 0xffeeeeee);
            line(graphics, view.isEmpty() ? (query.isBlank() ? "No readable candidates." : "No matches. Clear the search or refresh.")
                : "Tab / arrows select; row clicks never copy or delete.", y + 12, 0xffbfc7d5);
        } else {
            line(graphics, "Selected: " + selected.coord() + " | " + selected.dimension, y, 0xffffffff);
            line(graphics, "Block: " + (selected.blockId.isEmpty() ? "not recorded" : selected.blockId)
                + " | Logged: " + (selected.timestamp.isEmpty() ? "not recorded" : selected.timestamp), y + 12, 0xffc3d6eb);
        }
        line(graphics, nearest ? "X/Z only; not verified same-world distance. No player = newest."
            : "Newest means file order. Copy Info includes all recorded details.", y + 24, 0xffbfc7d5);
        if (!statusField.getValue().equals(status)) {
            statusField.setValue(status);
            statusField.setTooltip(Tooltip.create(Component.literal(status)));
        }
    }

    private void line(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.text(font, font.plainSubstrByWidth(text, Math.max(1, screenWidth() - 24)), 12, y, color, false);
    }

    @Override public Component getNarrationMessage() {
        return Component.literal("Base Log Browser. Unverified candidates. " + status
            + (selected == null ? "" : ". " + selected.details()));
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }

    private final class CandidateList extends ObjectSelectionList<CandidateRow> {
        CandidateList(int width, int height, int top) {
            super(Minecraft.getInstance(), width, height, top, 32);
            setX(12);
        }
        @Override public int getRowWidth() { return getWidth() - 16; }
        void replaceRows() {
            BaseLog.Entry keep = selected;
            setFocused(null);
            clearEntries();
            for (BaseLog.Entry entry : view) {
                CandidateRow row = new CandidateRow(entry);
                addEntry(row);
                if (entry == keep) setSelected(row);
            }
            if (keep == null) setSelected(null);
            setScrollAmount(keep == null ? 0 : Math.max(0, view.indexOf(keep) * 32 - getHeight() / 2 + 16));
        }
        @Override public void setSelected(CandidateRow row) {
            super.setSelected(row);
            selected = row == null ? null : row.entry;
            updateActions();
        }
    }

    private final class CandidateRow extends ObjectSelectionList.Entry<CandidateRow> {
        final BaseLog.Entry entry;
        CandidateRow(BaseLog.Entry entry) { this.entry = entry; }
        @Override public Component getNarration() { return Component.literal(entry.details()); }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) return false;
            candidates.setSelected(this);
            return true;
        }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int width = Math.max(1, getContentWidth() - 4);
            String distance = sortOrigin == null ? "" : " | X/Z only: "
                + BaseLog.formatDistance(entry.distanceTo(sortOrigin[0], sortOrigin[1]));
            graphics.text(font, font.plainSubstrByWidth(entry.coord() + " | " + BaseLog.shortDim(entry.dimension) + distance, width),
                getContentX() + 2, getContentY() + 2, 0xffffffff, false);
            graphics.text(font, font.plainSubstrByWidth((entry.blockId.isEmpty() ? "Block not recorded" : BaseLog.shortBlock(entry.blockId))
                + " | " + entry.timestamp, width), getContentX() + 2, getContentY() + 14, 0xffc3d6eb, false);
        }
    }
}
