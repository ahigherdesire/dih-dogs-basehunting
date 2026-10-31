package com.autism.seedcracker.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The builder's block-type-only parsers, isolated so a failed replacement cannot mutate its preview. */
public final class SchematicLoader {
    public static final int MAX_BLOCKS = 65_536;
    public static final int MAX_VOLUME = 1_048_576;
    private static final int MAX_SPAN = 4096;
    private static final int MAX_PALETTE = 4096;
    private static final long NBT_BYTES = 64L * 1024 * 1024;

    public record Loaded(Path file, Map<BlockPos, BlockState> blocks,
                         java.util.List<Map.Entry<BlockPos, BlockState>> entries,
                         Map<net.minecraft.world.item.Item, Integer> materials,
                         int width, int height, int length, int skipped) {}
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private int visited;
    private int skipped;

    private SchematicLoader() {}

    public static Loaded load(Path base, String input) throws IOException {
        Path file = SchematicFiles.validate(base, input);
        CompoundTag nbt;
        try (InputStream raw = new LimitedInput(Files.newInputStream(file));
             BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(2);
            boolean gzip = in.read() == 0x1f && in.read() == 0x8b;
            in.reset();
            nbt = gzip ? NbtIo.readCompressed(in, NbtAccounter.create(NBT_BYTES))
                : NbtIo.read(new DataInputStream(in), NbtAccounter.create(NBT_BYTES));
        }
        return decode(file, nbt);
    }

    static Loaded decode(Path file, CompoundTag nbt) throws IOException {
        if (nbt == null) throw new IOException("File has no NBT compound.");
        if (nbt.getCompound("Schematic").isPresent()) nbt = nbt.getCompoundOrEmpty("Schematic");
        SchematicLoader parser = new SchematicLoader();
        if (nbt.getCompound("Regions").isPresent()) parser.loadLitematica(nbt);
        else if (nbt.contains("palette") && nbt.contains("blocks")) parser.loadStructure(nbt);
        else if (nbt.getCompound("Palette").isPresent() || nbt.getCompound("Blocks").isPresent()) parser.loadSponge(nbt);
        else if (nbt.getByteArray("Blocks").isPresent()) parser.loadLegacy(nbt);
        else throw new IOException("Unknown NBT format. Export Litematica, Sponge .schem or legacy MCEdit.");
        return parser.normalize(file);
    }

    private Block block(String name) {
        int properties = name.indexOf('[');
        Identifier id = Identifier.tryParse(properties < 0 ? name : name.substring(0, properties));
        Block result = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (result == null) { skipped++; return Blocks.AIR; }
        return result;
    }

    // ponytail: block types only, matching the placer. Exact facing / half needs orientation-aware placement, not just NBT properties.
    private BlockState[] palette(ListTag palette) throws IOException {
        if (palette.isEmpty() || palette.size() > MAX_PALETTE) throw new IOException("Invalid or oversized block palette (limit 4096).");
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < states.length; i++) {
            states[i] = block(palette.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air")).defaultBlockState();
        }
        return states;
    }

    private void loadStructure(CompoundTag nbt) throws IOException {
        BlockState[] states = palette(nbt.getListOrEmpty("palette"));
        ListTag entries = nbt.getListOrEmpty("blocks");
        budget(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            SchematicFiles.checkInterrupted();
            CompoundTag entry = entries.getCompoundOrEmpty(i);
            int[] position = vector(entry, "pos", true);
            int state = entry.getIntOr("state", -1);
            if (position.length != 3 || state < 0 || state >= states.length) throw new IOException("Invalid structure block position or palette index.");
            put(new BlockPos(position[0], position[1], position[2]), states[state]);
        }
    }

    private void loadLitematica(CompoundTag nbt) throws IOException {
        CompoundTag regions = nbt.getCompoundOrEmpty("Regions");
        for (String name : regions.keySet()) {
            SchematicFiles.checkInterrupted();
            CompoundTag region = regions.getCompoundOrEmpty(name);
            int[] size = vector(region, "Size", false);
            int[] position = vector(region, "Position", false);
            if (size.length != 3 || position.length != 3) throw new IOException("Region needs Size and Position XYZ values.");
            int w = dimension(size[0]), h = dimension(size[1]), l = dimension(size[2]);
            int volume = volume(w, h, l);
            budget(volume);
            BlockState[] states = palette(region.getListOrEmpty("BlockStatePalette"));
            long[] data = region.getLongArray("BlockStates").orElseThrow(() -> new IOException("Missing Litematica BlockStates."));
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(states.length - 1));
            long needed = ((long) volume * bits + 63) / 64;
            if (data.length < needed) throw new IOException("Truncated Litematica block data.");
            long mask = (1L << bits) - 1;
            int ox = coordinate(position[0]) + Math.min(0, size[0] + 1);
            int oy = coordinate(position[1]) + Math.min(0, size[1] + 1);
            int oz = coordinate(position[2]) + Math.min(0, size[2] + 1);
            for (int i = 0; i < volume; i++) {
                if ((i & 1023) == 0) SchematicFiles.checkInterrupted();
                long bit = (long) i * bits;
                int index = (int) (bit >>> 6), offset = (int) (bit & 63);
                long value = data[index] >>> offset;
                if (offset + bits > 64) value |= data[index + 1] << (64 - offset);
                int state = (int) (value & mask);
                if (state >= states.length) throw new IOException("Invalid Litematica palette index.");
                put(new BlockPos(ox + i % w, oy + i / (w * l), oz + i / w % l), states[state]);
            }
        }
    }

    private void loadSponge(CompoundTag nbt) throws IOException {
        int w = nbt.getShortOr("Width", (short) 0) & 0xffff;
        int h = nbt.getShortOr("Height", (short) 0) & 0xffff;
        int l = nbt.getShortOr("Length", (short) 0) & 0xffff;
        int volume = volume(w, h, l);
        budget(volume);
        CompoundTag container = nbt.getCompound("Blocks").orElse(nbt);
        CompoundTag palette = container.getCompoundOrEmpty("Palette");
        if (palette.isEmpty() || palette.size() > MAX_PALETTE) throw new IOException("Invalid Sponge palette (limit 4096).");
        Map<Integer, BlockState> states = new HashMap<>();
        for (String name : palette.keySet()) {
            int id = palette.getIntOr(name, -1);
            if (id < 0 || id >= MAX_PALETTE || states.put(id, block(name).defaultBlockState()) != null) throw new IOException("Invalid Sponge palette index.");
        }
        byte[] data = container.getByteArray(container == nbt ? "BlockData" : "Data")
            .orElseThrow(() -> new IOException("Missing Sponge block data."));
        int offset = 0;
        for (int i = 0; i < volume; i++) {
            if ((i & 1023) == 0) SchematicFiles.checkInterrupted();
            int value = 0, shift = 0, part;
            do {
                if (offset >= data.length || shift > 21) throw new IOException("Invalid Sponge block varint.");
                part = data[offset++] & 0xff;
                value |= (part & 0x7f) << shift;
                shift += 7;
            } while ((part & 0x80) != 0);
            BlockState state = states.get(value);
            if (state == null) throw new IOException("Unknown Sponge block palette index.");
            put(new BlockPos(i % w, i / (w * l), i / w % l), state);
        }
    }

    private void loadLegacy(CompoundTag nbt) throws IOException {
        int w = nbt.getShortOr("Width", (short) 0) & 0xffff;
        int h = nbt.getShortOr("Height", (short) 0) & 0xffff;
        int l = nbt.getShortOr("Length", (short) 0) & 0xffff;
        int volume = volume(w, h, l);
        budget(volume);
        byte[] data = nbt.getByteArray("Blocks").orElseThrow();
        byte[] metadata = nbt.getByteArray("Data").orElse(new byte[0]);
        byte[] extra = nbt.getByteArray("AddBlocks").orElse(new byte[0]);
        if (data.length < volume) throw new IOException("Truncated legacy Blocks array.");
        for (int i = 0; i < volume; i++) {
            if ((i & 1023) == 0) SchematicFiles.checkInterrupted();
            int id = data[i] & 0xff;
            if (i / 2 < extra.length) id |= ((extra[i / 2] >>> ((i & 1) * 4)) & 15) << 8;
            int meta = i < metadata.length ? metadata[i] & 15 : 0;
            Block block = legacyBlock(id, meta);
            if (block != null) put(new BlockPos(i % w, i / (w * l), i / w % l), block.defaultBlockState());
            else if (id != 0) skipped++;
        }
    }

    private static Block legacyBlock(int id, int meta) {
        return switch (id) {
            case 1 -> Blocks.STONE; case 2 -> Blocks.GRASS_BLOCK; case 3 -> Blocks.DIRT;
            case 4 -> Blocks.COBBLESTONE; case 5 -> Blocks.OAK_PLANKS; case 7 -> Blocks.BEDROCK;
            case 12 -> Blocks.SAND; case 13 -> Blocks.GRAVEL; case 14 -> Blocks.GOLD_ORE;
            case 15 -> Blocks.IRON_ORE; case 16 -> Blocks.COAL_ORE; case 20 -> Blocks.GLASS;
            case 35 -> Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.byId(meta & 15));
            case 41 -> Blocks.GOLD_BLOCK; case 42 -> Blocks.IRON_BLOCK;
            case 45 -> Blocks.BRICKS; case 46 -> Blocks.TNT; case 47 -> Blocks.BOOKSHELF;
            case 48 -> Blocks.MOSSY_COBBLESTONE; case 49 -> Blocks.OBSIDIAN; case 54 -> Blocks.CHEST;
            case 56 -> Blocks.DIAMOND_ORE; case 57 -> Blocks.DIAMOND_BLOCK; case 80 -> Blocks.SNOW_BLOCK;
            case 86 -> Blocks.PUMPKIN; case 87 -> Blocks.NETHERRACK; case 89 -> Blocks.GLOWSTONE;
            case 98 -> Blocks.STONE_BRICKS; case 112 -> Blocks.NETHER_BRICKS; case 121 -> Blocks.END_STONE;
            case 133 -> Blocks.EMERALD_BLOCK; case 155 -> Blocks.QUARTZ_BLOCK;
            default -> null;
        };
    }

    private static int[] vector(CompoundTag tag, String key, boolean listAllowed) {
        if (tag.getIntArray(key).isPresent()) return tag.getIntArray(key).get();
        if (tag.getCompound(key).isPresent()) {
            CompoundTag value = tag.getCompoundOrEmpty(key);
            if (value.getInt("x").isEmpty() || value.getInt("y").isEmpty() || value.getInt("z").isEmpty()) return new int[0];
            return new int[]{value.getIntOr("x", 0), value.getIntOr("y", 0), value.getIntOr("z", 0)};
        }
        ListTag list = tag.getListOrEmpty(key);
        if (!listAllowed || list.size() != 3) return new int[0];
        int[] result = new int[3];
        for (int i = 0; i < result.length; i++) {
            if (list.getInt(i).isEmpty()) return new int[0];
            result[i] = list.getIntOr(i, 0);
        }
        return result;
    }

    private static int coordinate(int value) throws IOException {
        if (value < -1_000_000 || value > 1_000_000) throw new IOException("Schematic coordinate exceeds safety limit.");
        return value;
    }

    private static int dimension(int value) throws IOException {
        if (value == 0 || value < -MAX_SPAN || value > MAX_SPAN) throw new IOException("Schematic dimensions must be 1..4096 blocks.");
        return Math.abs(value);
    }

    static int volume(int w, int h, int l) throws IOException {
        if (w < 1 || h < 1 || l < 1 || w > MAX_SPAN || h > MAX_SPAN || l > MAX_SPAN
                || (long) w * h * l > MAX_VOLUME) throw new IOException("Selection exceeds 1,048,576 cells or 4096 blocks per axis. Export a smaller region.");
        return w * h * l;
    }

    private void budget(int count) throws IOException {
        if (count < 0 || (long) visited + count > MAX_VOLUME) throw new IOException("Selection exceeds the 1,048,576-cell safety limit.");
        visited += count;
    }

    private void put(BlockPos position, BlockState state) throws IOException {
        if (state.isAir()) return;
        coordinate(position.getX()); coordinate(position.getY()); coordinate(position.getZ());
        if (blocks.size() >= MAX_BLOCKS && !blocks.containsKey(position)) throw new IOException("More than 65,536 non-air blocks. Export a smaller selection.");
        blocks.put(position, state);
    }

    private Loaded normalize(Path file) throws IOException {
        if (blocks.isEmpty()) throw new IOException("No supported non-air blocks found; the previous preview was kept.");
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (BlockPos p : blocks.keySet()) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        int width = maxX - minX + 1, height = maxY - minY + 1, length = maxZ - minZ + 1;
        if (width > MAX_SPAN || height > MAX_SPAN || length > MAX_SPAN) throw new IOException("Combined region bounds exceed 4096 blocks per axis.");
        Map<BlockPos, BlockState> normalized = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            SchematicFiles.checkInterrupted();
            normalized.put(entry.getKey().offset(-minX, -minY, -minZ), entry.getValue());
        }
        Map<BlockPos, BlockState> immutable = Map.copyOf(normalized);
        var entries = new java.util.ArrayList<>(immutable.entrySet());
        entries.sort(java.util.Comparator.<Map.Entry<BlockPos, BlockState>>comparingInt(e -> e.getKey().getY())
            .thenComparingInt(e -> e.getKey().getZ()).thenComparingInt(e -> e.getKey().getX()));
        Map<net.minecraft.world.item.Item, Integer> materials = new HashMap<>();
        for (var entry : entries) materials.merge(entry.getValue().getBlock().asItem(), 1, Integer::sum);
        return new Loaded(file, immutable, java.util.List.copyOf(entries), Map.copyOf(materials), width, height, length, skipped);
    }

    public static BlockPos rotate(BlockPos p, int steps) {
        return switch (Math.floorMod(steps, 4)) {
            case 1 -> new BlockPos(-p.getZ(), p.getY(), p.getX());
            case 2 -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
            case 3 -> new BlockPos(p.getZ(), p.getY(), -p.getX());
            default -> p;
        };
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining = SchematicFiles.MAX_FILE_BYTES;
        LimitedInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            SchematicFiles.checkInterrupted();
            int value = in.read();
            if (value >= 0 && --remaining < 0) throw new IOException("File grew beyond the 16 MiB safety limit.");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            SchematicFiles.checkInterrupted();
            int count = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("File grew beyond the 16 MiB safety limit.");
            return count;
        }
    }
}
