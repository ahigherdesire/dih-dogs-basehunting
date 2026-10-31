package com.autism.seedcracker.finder;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Shared evidence score, not a probability. Generated structures can contain storage, lamps and machines. */
public final class BaseConfidence {

    private BaseConfidence() {}

    public static final int NATURAL_COMPATIBLE_LIMIT = 35;

    public enum Evidence { UNKNOWN, NATURAL_COMPATIBLE, BUILDING, UNSPECIFIED }

    public record Result(int score, String topReason, Evidence evidence) {
        /** Retains the numeric-result API without inventing a block-evidence classification. */
        public Result(int score, String topReason) { this(score, topReason, Evidence.UNSPECIFIED); }
    }

    /** Returns a heuristic evidence score, not a calibrated probability of a player base. */
    public static Result score(LevelChunk chunk) {
        if (chunk == null) return new Result(0, "", Evidence.UNKNOWN);
        return scoreSections(chunk.getSections());
    }

    public static boolean hasBuildEvidence(LevelChunk chunk) {
        return chunk != null && buildEvidenceSections(chunk.getSections());
    }

    static boolean buildEvidenceSections(LevelChunkSection[] sections) { return buildEvidence(counts(sections)); }

    private static java.util.Map<Block, Integer> counts(LevelChunkSection[] sections) {
        if (sections == null || sections.length == 0) return null;
        var counts = new java.util.HashMap<Block, Integer>();
        for (LevelChunkSection section : sections) {
            if (section == null) return null;
            if (section.hasOnlyAir()) continue;
            section.getStates().count((state, count) -> counts.merge(state.getBlock(), count, Integer::sum));
        }
        return counts;
    }

    private static boolean buildEvidence(java.util.Map<Block, Integer> counts) {
        if (counts == null) return false;
        int storage = 0, automation = 0;
        for (var entry : counts.entrySet()) {
            Block block = entry.getKey();
            if (block instanceof net.minecraft.world.level.block.ShulkerBoxBlock || block == Blocks.ENDER_CHEST
                || isPlacedDevice(block) || block == Blocks.ENCHANTING_TABLE) return true;
            if (block == Blocks.HOPPER || block == Blocks.OBSERVER) automation += entry.getValue();
            if (block == Blocks.CHEST || block == Blocks.BARREL || block == Blocks.TRAPPED_CHEST) storage += entry.getValue();
        }
        return automation >= 2 && storage >= 1;
    }

    static Result scoreSections(LevelChunkSection[] sections) {
        int storage = 0, lights = 0, workstations = 0, devices = 0, mechanisms = 0, artificial = 0, natural = 0;
        var counts = counts(sections);
        if (counts == null) return new Result(0, "", Evidence.UNKNOWN);
        boolean building = buildEvidence(counts);
        for (var entry : counts.entrySet()) {
            Block block = entry.getKey();
            int count = entry.getValue();
            if (isStorage(block)) storage += count;
            else if (isPlacedDevice(block)) devices += count;
            else if (isWorkstation(block)) workstations += count;
            else if (isLight(block)) lights += count;
            else if (isMechanism(block)) mechanisms += count;
            else if (isArtificial(block)) artificial += count;
            else if (isNatural(block)) natural += count;
        }

        int score = 0;
        String reason = "";
        score += Math.min(50, storage * 8);
        if (storage > 0) reason = storage + " storage";
        int ws = Math.min(20, workstations * 5);
        score += ws;
        if (workstations > 0 && reason.isEmpty()) reason = workstations + " workstations";
        score += Math.min(20, devices * 5);
        if (devices > 0 && reason.isEmpty()) reason = devices + (devices == 1 ? " placed device" : " placed devices");
        int li = Math.min(15, lights * 3);
        score += li;
        int mech = Math.min(10, mechanisms * 2);
        score += mech;
        int art = Math.min(15, artificial / 8);
        score += art;
        if (artificial >= 24 && reason.isEmpty()) reason = "built structure";
        // Natural surroundings must not erase a separately observed placed device.
        if (!building && natural > 3000 && storage == 0 && lights == 0) score -= 15;

        if (reason.isEmpty() && lights > 0) reason = lights + " light sources";
        if (reason.isEmpty() && mechanisms > 0) reason = mechanisms + " mechanisms";
        if (reason.isEmpty() && art > 0) reason = "built structure";
        if (!building && score > NATURAL_COMPATIBLE_LIMIT) {
            score = NATURAL_COMPATIBLE_LIMIT;
            reason = "Natural-compatible: " + (reason.isEmpty() ? "mixed blocks" : reason);
        }
        return new Result(Math.clamp(score, 0, 100), reason, building ? Evidence.BUILDING : Evidence.NATURAL_COMPATIBLE);
    }

    private static boolean isPlacedDevice(Block block) {
        return block == Blocks.BEACON || block == Blocks.CONDUIT || block == Blocks.CRAFTER || block == Blocks.RESPAWN_ANCHOR;
    }

    private static boolean isStorage(Block b) {
        return b == Blocks.CHEST || b == Blocks.BARREL || b == Blocks.TRAPPED_CHEST
            || b == Blocks.ENDER_CHEST || b == Blocks.HOPPER || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
            || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.DISPENSER || b == Blocks.DROPPER;
    }

    private static boolean isWorkstation(Block b) {
        return b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE || b instanceof net.minecraft.world.level.block.AnvilBlock
            || b == Blocks.BREWING_STAND || b == Blocks.GRINDSTONE || b == Blocks.SMITHING_TABLE
            || b == Blocks.LOOM || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.STONECUTTER
            || b == Blocks.FLETCHING_TABLE;
    }

    private static boolean isLight(Block b) {
        return b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.SOUL_TORCH
            || b == Blocks.SOUL_WALL_TORCH || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.REDSTONE_LAMP || b == Blocks.JACK_O_LANTERN || b == Blocks.CAMPFIRE
            || b == Blocks.SOUL_CAMPFIRE;
    }

    private static boolean isMechanism(Block b) {
        return b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.OBSERVER
            || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.REDSTONE_WIRE
            || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_BLOCK || b == Blocks.RAIL
            || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    private static boolean isArtificial(Block b) {
        return b == Blocks.COBBLESTONE || b == Blocks.STONE_BRICKS || b == Blocks.BRICKS
            || b == Blocks.SMOOTH_STONE || b == Blocks.POLISHED_DEEPSLATE || b == Blocks.DEEPSLATE_BRICKS
            || b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS || b == Blocks.BIRCH_PLANKS
            || b == Blocks.GLASS;
    }

    private static boolean isNatural(Block b) {
        return b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.DIRT || b == Blocks.GRASS_BLOCK
            || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.NETHERRACK;
    }
}
