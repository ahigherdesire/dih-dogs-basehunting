package com.autism.seedcracker.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.net.URI;

/**
 * Minecraft calls that changed between 26.2 and 26.3, in one place. The {@code //? if >=26.3} blocks follow DIH's
 * Stonecutter convention: the 26.2 code compiles as written, and {@code gradlew build -Pmc=26.3} swaps in the
 * commented 26.3 code (see {@code preprocessSources} in build.gradle.kts).
 */
public final class McCompat {
    private McCompat() {
    }

    /** Swings {@code entity}'s arm (animation only). */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        //? if >=26.3 {
        /*entity.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        *///?} else {
        entity.swing(hand);
        //?}
    }

    /** Drops the selected item (the whole stack when {@code fullStack}), as the drop key does. */
    public static void dropSelected(Minecraft mc, boolean fullStack) {
        if (mc.player == null || mc.gameMode == null) return;
        //? if >=26.3 {
        /*mc.gameMode.dropItem(mc.player, fullStack);
        *///?} else {
        mc.player.drop(fullStack);
        //?}
    }

    public static int chunkX(ClientboundLevelChunkWithLightPacket p) {
        //? if >=26.3 {
        /*return p.x();
        *///?} else {
        return p.getX();
        //?}
    }

    public static int chunkZ(ClientboundLevelChunkWithLightPacket p) {
        //? if >=26.3 {
        /*return p.z();
        *///?} else {
        return p.getZ();
        //?}
    }

    public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket p) {
        //? if >=26.3 {
        /*return p.chunkData();
        *///?} else {
        return p.getChunkData();
        //?}
    }

    public static net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData chunkLightData(ClientboundLevelChunkWithLightPacket p) {
        //? if >=26.3 {
        /*return p.lightData();
        *///?} else {
        return p.getLightData();
        //?}
    }

    /** The synced position, or null when the 26.3 packet carries none. */
    public static Vec3 syncPosition(ClientboundEntityPositionSyncPacket p) {
        //? if >=26.3 {
        /*return p.hasPosition() ? p.position().endPosition() : null;
        *///?} else {
        return p.values().position();
        //?}
    }

    public static int chunkX(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p) {
        //? if >=26.3 {
        /*return p.x();
        *///?} else {
        return p.getX();
        //?}
    }

    public static int chunkZ(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p) {
        //? if >=26.3 {
        /*return p.z();
        *///?} else {
        return p.getZ();
        //?}
    }

    public static net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData chunkLightData(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p) {
        //? if >=26.3 {
        /*return p.lightData();
        *///?} else {
        return p.getLightData();
        //?}
    }

    public static java.util.BitSet blockYMask(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        //? if >=26.3 {
        /*return data.blockYMask();
        *///?} else {
        return data.getBlockYMask();
        //?}
    }

    public static java.util.BitSet skyYMask(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        //? if >=26.3 {
        /*return data.skyYMask();
        *///?} else {
        return data.getSkyYMask();
        //?}
    }

    public static java.util.BitSet emptyBlockYMask(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        //? if >=26.3 {
        /*return data.emptyBlockYMask();
        *///?} else {
        return data.getEmptyBlockYMask();
        //?}
    }

    public static java.util.BitSet emptySkyYMask(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        //? if >=26.3 {
        /*return data.emptySkyYMask();
        *///?} else {
        return data.getEmptySkyYMask();
        //?}
    }

    public static java.util.List<byte[]> blockUpdates(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        //? if >=26.3 {
        /*return data.blockUpdates();
        *///?} else {
        return data.getBlockUpdates();
        //?}
    }

    public static java.util.List<byte[]> skyUpdates(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        //? if >=26.3 {
        /*return data.skyUpdates();
        *///?} else {
        return data.getSkyUpdates();
        //?}
    }

    public static void forEachBlockEntityTag(ClientboundLevelChunkPacketData data, int x, int z,
            ClientboundLevelChunkPacketData.BlockEntityTagOutput output) {
        //? if >=26.3 {
        /*data.forEachBlockEntityTag(x, z, output);
        *///?} else {
        data.getBlockEntitiesTagsConsumer(x, z).accept(output);
        //?}
    }

    public static net.minecraft.world.level.block.entity.SignText signText(net.minecraft.world.level.block.entity.SignBlockEntity sign, boolean front) {
        //? if >=26.3 {
        /*return sign.getText(front ? net.minecraft.world.level.block.entity.SignTextSlot.FRONT : net.minecraft.world.level.block.entity.SignTextSlot.BACK);
        *///?} else {
        return front ? sign.getFrontText() : sign.getBackText();
        //?}
    }

    public static net.minecraft.network.chat.Component signLine(net.minecraft.world.level.block.entity.SignText text, int line) {
        //? if >=26.3 {
        /*return text.getMessages(false).get(line);
        *///?} else {
        return text.getMessage(line, false);
        //?}
    }

    public static it.unimi.dsi.fastutil.ints.IntList entityIds(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet) {
        //? if >=26.3 {
        /*return packet.entityIds();
        *///?} else {
        return packet.getEntityIds();
        //?}
    }

    public static boolean isAttackPacket(net.minecraft.network.protocol.Packet<?> packet) {
        //? if >=26.3 {
        /*return packet instanceof net.minecraft.network.protocol.game.ServerboundAttackPacket;
        *///?} else {
        return packet instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket;
        //?}
    }

    public static net.minecraft.world.phys.BlockHitResult hitResult(net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
        //? if >=26.3 {
        /*return packet.hitResult();
        *///?} else {
        return packet.getHitResult();
        //?}
    }

    public static InteractionHand hand(net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
        //? if >=26.3 {
        /*return packet.hand();
        *///?} else {
        return packet.getHand();
        //?}
    }

    public static boolean isKeyboardKey(com.mojang.blaze3d.platform.InputConstants.Key key) {
        //? if >=26.3 {
        /*return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD;
        *///?} else {
        return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM;
        //?}
    }

    public static boolean keyDown(Minecraft mc, int code) {
        //? if >=26.3 {
        /*return com.mojang.blaze3d.platform.InputConstants.isKeyDown(code);
        *///?} else {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), code);
        //?}
    }

    public static boolean mouseDown(Minecraft mc, int button) {
        if (mc.getWindow() == null || !mc.isWindowActive()) return false;
        //? if >=26.3 {
        /*return button >= 1 && button <= 32 && (org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(
            (java.nio.FloatBuffer) null, (java.nio.FloatBuffer) null) & (1 << (button - 1))) != 0;
        *///?} else {
        return button >= 0 && button <= org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST
            && org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        //?}
    }

    /** Opens {@code uri} with the system handler (Util's platform on 26.2, Blaze3D on 26.3). */
    public static void openUri(URI uri) {
        //? if >=26.3 {
        /*com.mojang.blaze3d.Blaze3D.openUri(uri);
        *///?} else {
        net.minecraft.util.Util.getPlatform().openUri(uri);
        //?}
    }
}
