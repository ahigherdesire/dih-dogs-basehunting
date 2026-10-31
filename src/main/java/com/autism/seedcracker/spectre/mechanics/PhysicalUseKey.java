package com.autism.seedcracker.spectre.mechanics;

import com.autism.seedcracker.util.FlagLog;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import dihclient.util.DihKeys;

/** Reads the configured physical use binding, not a key state forced by another automation. */
public final class PhysicalUseKey {
    private String binding;
    private InputConstants.Key key;
    private int keyboardCode = -1;

    public boolean held(Minecraft mc) {
        if (!mc.isSameThread() || !mc.isWindowActive() || mc.gui.screen() != null) return false;
        String current = mc.options.keyUse.saveString();
        if (!current.equals(binding)) resolve(current);
        if (key == null || key.getValue() < 0) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return DihKeys.isMouseDown(key.getValue());
        }
        return keyboardCode >= 0 && DihKeys.isKeyDown(keyboardCode);
    }

    private void resolve(String current) {
        binding = current;
        key = null;
        keyboardCode = -1;
        try {
            key = InputConstants.getKey(current);
        } catch (IllegalArgumentException error) {
            FlagLog.warn("INPUT", "Riptide", "Cannot read configured use binding: " + current + " (" + error + ")");
            return;
        }
        //? if >=26.3 {
        /*if (key.getType() == InputConstants.Type.KEYBOARD) keyboardCode = key.getValue();
        *///?} else {
        if (key.getType() == InputConstants.Type.KEYSYM) {
            keyboardCode = key.getValue();
        } else if (key.getType() == InputConstants.Type.SCANCODE && key.getValue() >= 0) {
            // Resolve a scan-code binding once, not a full keyboard scan on every tick.
            for (int candidate = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE; candidate <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST; candidate++) {
                if (pollableGlfwKey(candidate) && org.lwjgl.glfw.GLFW.glfwGetKeyScancode(candidate) == key.getValue()) {
                    keyboardCode = candidate;
                    break;
                }
            }
            if (keyboardCode < 0) FlagLog.warn("INPUT", "Riptide", "No GLFW key for use binding: " + current);
        }
        //?}
    }

    //? if >=26.3 {
    /*
    *///?} else {
    // GLFW key tokens are sparse. Querying the gaps produces GLFW_INVALID_ENUM errors.
    static boolean pollableGlfwKey(int code) {
        return code == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE || code == org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_9
            || code == org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON || code == org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_A && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET
            || code == org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1 && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_2
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_END
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_CAPS_LOCK && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_PAUSE
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_F1 && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_F25
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0 && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_KP_EQUAL
            || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_MENU;
    }

    //?}

    public void reset() {
        binding = null;
        key = null;
        keyboardCode = -1;
    }
}
