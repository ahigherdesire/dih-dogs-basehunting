package com.autism.seedcracker.schematic;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchematicLoaderTest {
    private Path root;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach void directory() throws IOException {
        root = Files.createDirectories(Path.of("build", "schematic-loader-tests", UUID.randomUUID().toString())).toAbsolutePath();
    }
    @AfterEach void clean() throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test void rotationsAndTheirInverseKeepTheAnchorAndVerticalAxis() {
        BlockPos point = new BlockPos(2, 3, 5), anchor = new BlockPos(100, -20, 200);
        BlockPos[] expected = {point, new BlockPos(-5, 3, 2), new BlockPos(-2, 3, -5), new BlockPos(5, 3, -2)};
        for (int i = 0; i < 4; i++) {
            BlockPos rotated = SchematicLoader.rotate(point, i);
            assertEquals(expected[i], rotated);
            assertEquals(point, SchematicLoader.rotate(anchor.offset(rotated).subtract(anchor), -i));
            assertEquals(BlockPos.ZERO, SchematicLoader.rotate(BlockPos.ZERO, i));
        }
        assertEquals(point, SchematicLoader.rotate(point, 4));
    }

    @Test void gzipAndUncompressedLoadsUseTheSameTransactionalParser() throws IOException {
        CompoundTag nbt = legacy(1, 1, 1, new byte[]{1});
        Path compressed = root.resolve("one.schematic"), plain = root.resolve("two.schematic");
        NbtIo.writeCompressed(nbt, compressed);
        NbtIo.write(nbt, plain);
        var first = SchematicLoader.load(root, compressed.toString());
        var second = SchematicLoader.load(root, plain.toString());
        assertEquals(first.blocks(), second.blocks());
        assertTrue(first.blocks().get(BlockPos.ZERO).is(Blocks.STONE));
        assertThrows(UnsupportedOperationException.class, () -> first.blocks().clear());
        Files.write(root.resolve("bad.schem"), new byte[]{1, 2, 3});
        assertThrows(Exception.class, () -> SchematicLoader.load(root, "bad.schem"));
        assertEquals(1, first.blocks().size());
    }

    @Test void structureListAndIntArrayPositionsStillNormalize() throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.put("palette", palette("minecraft:stone", "minecraft:dirt"));
        ListTag blocks = new ListTag();
        CompoundTag first = new CompoundTag(); first.putIntArray("pos", new int[]{-8, -2, 4}); first.putInt("state", 0); blocks.add(first);
        CompoundTag second = new CompoundTag();
        ListTag position = new ListTag(); position.add(IntTag.valueOf(-6)); position.add(IntTag.valueOf(-1)); position.add(IntTag.valueOf(7));
        second.put("pos", position); second.putInt("state", 1); blocks.add(second);
        tag.put("blocks", blocks);
        var loaded = SchematicLoader.decode(root.resolve("structure.schem"), tag);
        assertEquals(3, loaded.width()); assertEquals(2, loaded.height()); assertEquals(4, loaded.length());
        assertTrue(loaded.blocks().get(new BlockPos(2, 1, 3)).is(Blocks.DIRT));
        ListTag malformed = new ListTag();
        malformed.add(net.minecraft.nbt.StringTag.valueOf("x")); malformed.add(net.minecraft.nbt.StringTag.valueOf("y")); malformed.add(net.minecraft.nbt.StringTag.valueOf("z"));
        second.put("pos", malformed);
        assertThrows(IOException.class, () -> SchematicLoader.decode(root.resolve("bad-vector.schem"), tag));
        assertEquals(2, loaded.blocks().size());
    }

    @Test void spongeV2AndNestedV3PalettesDecodeVarints() throws IOException {
        CompoundTag base = new CompoundTag();
        base.putShort("Width", (short) 2); base.putShort("Height", (short) 1); base.putShort("Length", (short) 1);
        CompoundTag palette = new CompoundTag(); palette.putInt("minecraft:stone", 0); palette.putInt("minecraft:oak_log[axis=x]", 130);
        base.put("Palette", palette); base.putByteArray("BlockData", new byte[]{0, (byte) 0x82, 1});
        var v2 = SchematicLoader.decode(root.resolve("v2.schem"), base);
        assertEquals(2, v2.blocks().size());
        assertTrue(v2.blocks().get(new BlockPos(1, 0, 0)).is(Blocks.OAK_LOG));
        CompoundTag nested = new CompoundTag(); nested.put("Palette", palette); nested.putByteArray("Data", new byte[]{0, (byte) 0x82, 1});
        base.remove("Palette"); base.remove("BlockData"); base.put("Blocks", nested);
        CompoundTag wrapper = new CompoundTag(); wrapper.put("Schematic", base);
        assertEquals(v2.blocks(), SchematicLoader.decode(root.resolve("v3.schem"), wrapper).blocks());
    }

    @Test void litematicaCompoundVectorsNegativeSizesAndCrossLongValuesDecode() throws IOException {
        CompoundTag region = new CompoundTag();
        region.put("Size", xyz(-23, 1, 1)); region.put("Position", xyz(40, 7, -9));
        region.put("BlockStatePalette", palette("minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:glass", "minecraft:oak_planks", "minecraft:cobblestone"));
        int[] values = new int[23]; java.util.Arrays.fill(values, 1); values[21] = 5; values[22] = 2;
        region.putLongArray("BlockStates", pack(values, 3));
        CompoundTag regions = new CompoundTag(); regions.put("main", region);
        CompoundTag nbt = new CompoundTag(); nbt.put("Regions", regions);
        var loaded = SchematicLoader.decode(root.resolve("house.litematic"), nbt);
        assertEquals(23, loaded.blocks().size());
        assertEquals(23, loaded.width());
        assertTrue(loaded.blocks().get(new BlockPos(21, 0, 0)).is(Blocks.COBBLESTONE));
        assertTrue(loaded.blocks().get(new BlockPos(22, 0, 0)).is(Blocks.DIRT));
        region.putIntArray("Size", new int[]{23, 1, 1}); region.putIntArray("Position", new int[]{0, 0, 0});
        assertEquals(loaded.blocks(), SchematicLoader.decode(root.resolve("array-vectors.litematic"), nbt).blocks());
    }

    @Test void malformedAndOversizedDataFailBeforeBuildingAnything() throws IOException {
        assertThrows(IOException.class, () -> SchematicLoader.volume(Integer.MAX_VALUE, 2, 2));
        assertThrows(IOException.class, () -> SchematicLoader.volume(4096, 4096, 4096));
        assertThrows(IOException.class, () -> SchematicLoader.volume(0, 1, 1));
        assertThrows(IOException.class, () -> SchematicLoader.decode(root.resolve("bad.schematic"), legacy(3, 1, 1, new byte[]{1})));
        assertThrows(IOException.class, () -> SchematicLoader.decode(root.resolve("empty.schematic"), legacy(1, 1, 1, new byte[]{0})));
        byte[] blocks = new byte[257 * 257]; java.util.Arrays.fill(blocks, (byte) 1);
        IOException large = assertThrows(IOException.class, () -> SchematicLoader.decode(root.resolve("large.schematic"), legacy(257, 257, 1, blocks)));
        assertTrue(large.getMessage().contains("65,536"));
        Path bomb = root.resolve("allocation.schem");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(bomb))) {
            out.writeByte(10); out.writeUTF(""); out.writeByte(7); out.writeUTF("huge");
            out.writeInt(64 * 1024 * 1024 + 1); out.writeByte(0);
        }
        assertThrows(Exception.class, () -> SchematicLoader.load(root, bomb.toString()));
    }

    @Test void legacyUnknownBlocksAreReportedRatherThanInvented() throws IOException {
        var loaded = SchematicLoader.decode(root.resolve("legacy.schematic"), legacy(2, 1, 1, new byte[]{1, (byte) 250}));
        assertEquals(1, loaded.blocks().size());
        assertEquals(1, loaded.skipped());
    }

    private static CompoundTag legacy(int width, int height, int length, byte[] blocks) {
        CompoundTag tag = new CompoundTag(); tag.putShort("Width", (short) width); tag.putShort("Height", (short) height);
        tag.putShort("Length", (short) length); tag.putByteArray("Blocks", blocks); return tag;
    }
    private static ListTag palette(String... names) {
        ListTag result = new ListTag();
        for (String name : names) { CompoundTag block = new CompoundTag(); block.putString("Name", name); result.add(block); }
        return result;
    }
    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag result = new CompoundTag(); result.putInt("x", x); result.putInt("y", y); result.putInt("z", z); return result;
    }
    private static long[] pack(int[] values, int bits) {
        long[] data = new long[(values.length * bits + 63) / 64];
        for (int i = 0; i < values.length; i++) {
            int bit = i * bits, index = bit / 64, offset = bit % 64;
            data[index] |= (long) values[i] << offset;
            if (offset + bits > 64) data[index + 1] |= (long) values[i] >>> (64 - offset);
        }
        return data;
    }
}
