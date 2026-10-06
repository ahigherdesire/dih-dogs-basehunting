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

    /** The synced position, or null when the 26.3 packet carries none. */
    public static Vec3 syncPosition(ClientboundEntityPositionSyncPacket p) {
        //? if >=26.3 {
        /*return p.hasPosition() ? p.position().endPosition() : null;
        *///?} else {
        return p.values().position();
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
