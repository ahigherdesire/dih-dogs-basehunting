package com.autism.seedcracker.baselog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BaseLogTest {
    private Path directory;

    private Path log(String content) throws IOException {
        directory = Path.of("build", "baselog-review-test-" + UUID.randomUUID());
        Files.createDirectories(directory);
        Path path = directory.resolve("bases.txt");
        Files.writeString(path, content);
        return path;
    }

    @AfterEach void cleanup() throws IOException {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test void rtpSqlTimestampIsNotMistakenForABlock() {
        BaseLog.Entry entry = BaseLog.parse("-123 64 987  minecraft:overworld  2026-10-30 12:34:56.123");
        assertEquals("", entry.blockId);
        assertEquals("2026-10-30 12:34:56.123", entry.timestamp);
        assertEquals("-123 64 987", entry.coord());
    }

    @Test void legacyNamespacedBlockIsNotMistakenForTimestamp() {
        BaseLog.Entry entry = BaseLog.parse("1 2 3 minecraft:the_nether minecraft:chest");
        assertEquals("minecraft:chest", entry.blockId);
        assertEquals("", entry.timestamp);
        entry = BaseLog.parse("1 2 3 minecraft:the_nether minecraft:chest 2026-10-30 12:00:00");
        assertEquals("minecraft:chest", entry.blockId);
        assertEquals("2026-10-30 12:00:00", entry.timestamp);
    }

    @Test void acceptsIsoTimestampAndUnknownCoordinateOnlyContext() {
        assertEquals("2026-10-30T12:00:00Z", BaseLog.parse("1 2 3 unknown 2026-10-30T12:00:00Z").timestamp);
        BaseLog.Entry entry = BaseLog.parse(" -30000000 -64 30000000 ");
        assertEquals("unknown", entry.dimension);
        assertEquals("", entry.timestamp);
        assertTrue(entry.details().contains("Server/world: not recorded"));
        assertTrue(entry.details().contains("unverified"));
    }

    @Test void rejectsNonIntegralOverflowAndOversizedRows() {
        assertNull(BaseLog.parse("1.5 2 3"));
        assertNull(BaseLog.parse("2147483648 2 3"));
        assertNull(BaseLog.parse("1 2"));
        assertNull(BaseLog.parse("1 2 3 " + "x".repeat(4096)));
        assertNull(BaseLog.parse(null));
    }

    @Test void searchUsesAllWordsAndLocaleIndependentCase() throws IOException {
        BaseLog.Snapshot snapshot = BaseLog.parseText("1 2 3 minecraft:overworld minecraft:IRON_BLOCK 2026-10-30\n4 5 6 unknown\n");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of(snapshot.entries().getFirst()), BaseLog.filter(snapshot.entries(), "iron overworld 2026", false, null));
        } finally { Locale.setDefault(previous); }
        assertTrue(BaseLog.filter(snapshot.entries(), "overworld missing", false, null).isEmpty());
    }

    @Test void sortingRetainsTheSameDuplicateOccurrenceNotAnIndex() throws IOException {
        BaseLog.Snapshot snapshot = BaseLog.parseText("1 2 3\n90 2 90\n1 2 3\n");
        BaseLog.Entry first = snapshot.entries().getFirst();
        List<BaseLog.Entry> newest = BaseLog.filter(snapshot.entries(), "", false, null);
        assertSame(snapshot.entries().getLast(), newest.getFirst());
        assertSame(first, BaseLog.retainSelection(first, newest));
        List<BaseLog.Entry> near = BaseLog.filter(snapshot.entries(), "", true, new double[] {0, 0});
        assertSame(snapshot.entries().getLast(), near.getFirst());
        assertSame(first, BaseLog.retainSelection(first, near));
        assertNull(BaseLog.retainSelection(first, BaseLog.filter(snapshot.entries(), "90", false, null)));
        assertEquals(newest, BaseLog.filter(snapshot.entries(), "", true, null));
    }

    @Test void deletingOnlyOneOccurrencePreservesUnparsedLinesAndLineEndings() throws IOException {
        String text = "# note\r\n1 2 3\r\n\r\n1 2 3\nnot coords\r9 8 7";
        BaseLog.Snapshot snapshot = BaseLog.parseText(text);
        assertEquals(2, snapshot.skipped());
        assertEquals("# note\r\n1 2 3\r\n\r\nnot coords\r9 8 7", BaseLog.without(snapshot, snapshot.entries().get(1)));
        assertEquals("# note\r\n1 2 3\r\n\r\n1 2 3\nnot coords\r", BaseLog.without(snapshot, snapshot.entries().getLast()));
        assertThrows(IOException.class, () -> BaseLog.without(snapshot, BaseLog.parse("1 2 3")));
    }

    @Test void realReplacementKeepsAnOriginalBackup() throws IOException {
        String text = "bad line\r\n1 2 3\r\n1 2 3\n";
        Path path = log(text);
        BaseLog.Snapshot snapshot = BaseLog.read(path);
        Path backup = BaseLog.remove(path, snapshot, snapshot.entries().getLast());
        assertEquals("bad line\r\n1 2 3\r\n", Files.readString(path));
        assertEquals(text, Files.readString(backup));
        assertFalse(Files.isSameFile(path, backup));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void aWriterOpenedBeforeDeletionCannotLoseItsLateAppend() throws IOException {
        Path path = log("1 2 3\n4 5 6\n");
        BaseLog.Snapshot snapshot = BaseLog.read(path);
        try (var writer = Files.newOutputStream(path, StandardOpenOption.APPEND)) {
            Path preserved;
            try {
                preserved = BaseLog.remove(path, snapshot, snapshot.entries().getFirst());
                assertEquals("4 5 6\n", Files.readString(path));
            } catch (java.nio.file.FileSystemException sharingViolation) {
                // Windows can refuse atomic replacement while a writer holds the original open.
                preserved = path;
                assertEquals(snapshot.text(), Files.readString(path));
            }
            writer.write("7 8 9\n".getBytes(StandardCharsets.UTF_8));
            writer.flush();
            assertEquals("1 2 3\n4 5 6\n7 8 9\n", Files.readString(preserved));
        }
    }

    @Test void changedFileOrRepeatedDeletionIsRejectedWithoutTouchingIt() throws IOException {
        Path path = log("1 2 3\n1 2 3\n");
        BaseLog.Snapshot snapshot = BaseLog.read(path);
        Files.writeString(path, "7 8 9\n", StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> BaseLog.remove(path, snapshot, snapshot.entries().getFirst()));
        assertEquals("1 2 3\n1 2 3\n7 8 9\n", Files.readString(path));
        BaseLog.Snapshot fresh = BaseLog.read(path);
        BaseLog.remove(path, fresh, fresh.entries().getFirst());
        assertThrows(IOException.class, () -> BaseLog.remove(path, fresh, fresh.entries().getFirst()));
        assertEquals("1 2 3\n7 8 9\n", Files.readString(path));
    }

    @Test void invalidUtf8AndOversizeLogsFailClosed() throws IOException {
        Path path = log("");
        Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> BaseLog.read(path));
        assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0x28}, Files.readAllBytes(path));
        Files.write(path, new byte[BaseLog.MAX_BYTES + 1]);
        assertThrows(IOException.class, () -> BaseLog.read(path));
        assertEquals(BaseLog.MAX_BYTES + 1, Files.size(path));
        assertThrows(IOException.class, () -> BaseLog.parseText("\n".repeat(BaseLog.MAX_LINES + 1)));
    }

    @Test void missingFileIsEmptyAndDirectoriesAreNotRead() throws IOException {
        Path path = log("");
        Files.delete(path);
        assertTrue(BaseLog.read(path).entries().isEmpty());
        assertFalse(Files.exists(path));
        assertThrows(IOException.class, () -> BaseLog.read(directory));
    }
}
