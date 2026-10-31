package com.autism.seedcracker.schematic;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchematicFilesTest {
    private Path root;

    @BeforeEach void directory() throws IOException {
        root = Files.createDirectories(Path.of("build", "schematic-tests", UUID.randomUUID().toString())).toAbsolutePath();
    }

    @AfterEach void clean() throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test void acceptsQuotedRelativeAbsoluteAndUnicodePaths() throws IOException {
        Path file = Files.write(root.resolve("house with spaces - 雪.SCHEM"), new byte[]{1});
        assertEquals(file.toRealPath(), SchematicFiles.validate(root, "  \"" + file.getFileName() + "\"  "));
        assertEquals(file.toRealPath(), SchematicFiles.validate(root, file.toString()));
        assertEquals(file, SchematicFiles.resolve(root, Path.of(".", file.getFileName().toString()).toString()));
    }

    @Test void rejectsMissingUnsupportedDirectoryEmptyAndOversizedFiles() throws IOException {
        assertThrows(IOException.class, () -> SchematicFiles.validate(root, "missing.schem"));
        Path text = Files.write(root.resolve("file.txt"), new byte[]{1});
        assertThrows(IOException.class, () -> SchematicFiles.validate(root, text.toString()));
        Files.createDirectory(root.resolve("folder.schem"));
        assertThrows(IOException.class, () -> SchematicFiles.validate(root, "folder.schem"));
        Files.createFile(root.resolve("empty.schematic"));
        assertThrows(IOException.class, () -> SchematicFiles.validate(root, "empty.schematic"));
        try (var file = new java.io.RandomAccessFile(root.resolve("large.litematic").toFile(), "rw")) {
            file.setLength(SchematicFiles.MAX_FILE_BYTES + 1);
        }
        assertThrows(IOException.class, () -> SchematicFiles.validate(root, "large.litematic"));
        assertThrows(IOException.class, () -> SchematicFiles.resolve(root, ""));
        assertThrows(IOException.class, () -> SchematicFiles.resolve(root, null));
        assertThrows(IOException.class, () -> SchematicFiles.resolve(root, "a\nb.schem"));
        assertThrows(IOException.class, () -> SchematicFiles.resolve(root, "x".repeat(SchematicFiles.MAX_PATH_LENGTH + 1)));
    }

    @Test void recentsAreBoundedDeduplicatedAndNewestFirst() {
        String saved = "";
        for (int i = 0; i < 15; i++) saved = SchematicFiles.remember(saved, root.resolve("house" + i + ".schem"));
        saved = SchematicFiles.remember(saved, root.resolve("house9.schem"));
        List<Path> history = SchematicFiles.recent(saved);
        assertEquals(10, history.size());
        assertEquals(root.resolve("house9.schem"), history.getFirst());
        assertEquals(1, history.stream().filter(path -> path.endsWith("house9.schem")).count());
        assertEquals(history, SchematicFiles.recent(String.join("\n", history.stream().map(Path::toString).toList())));
        assertTrue(SchematicFiles.recent(null).isEmpty());
        assertTrue(SchematicFiles.recent("relative.schem\n\u0000\nhttps://example.invalid/file.txt").isEmpty());
        assertTrue(SchematicFiles.recent("x".repeat(50_000)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SchematicFiles.remember("", root.resolve("not.txt")));
    }

    @Test void recursiveSearchAndBrowserExcludeUnrelatedFiles() throws IOException {
        Path folder = Files.createDirectories(root.resolve("subfolder"));
        Path first = Files.write(root.resolve("one.schem"), new byte[]{1});
        Path nested = Files.write(folder.resolve("two.LITEMATIC"), new byte[]{1});
        Files.write(root.resolve("ignore.txt"), new byte[]{1});
        var recursive = SchematicFiles.scan(List.of(root), true).entries();
        assertEquals(2, recursive.size());
        assertTrue(recursive.stream().anyMatch(entry -> entry.path().equals(first)));
        assertTrue(recursive.stream().anyMatch(entry -> entry.path().equals(nested)));
        var browser = SchematicFiles.scan(List.of(root), false).entries();
        assertEquals(2, browser.size());
        assertTrue(browser.getFirst().directory());
        assertEquals(folder, browser.getFirst().path());
    }

    @Test void fileScanStopsAtItsResultBudgetAndHonorsCancellation() throws IOException {
        for (int i = 0; i < 530; i++) Files.write(root.resolve("part" + i + ".schematic"), new byte[]{1});
        var listing = SchematicFiles.scan(List.of(root), true);
        assertEquals(512, listing.entries().size());
        assertTrue(listing.notice().startsWith("Partial list"));
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> SchematicFiles.scan(List.of(root), true)); }
        finally { Thread.interrupted(); }
    }

    @Test void workerHasOnlyOneActiveAndOneQueuedRequest() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> first = SchematicFiles.submit(() -> { running.countDown(); release.await(5, TimeUnit.SECONDS); return null; });
        Future<?> queued = null;
        try {
            assertTrue(running.await(5, TimeUnit.SECONDS));
            queued = SchematicFiles.submit(() -> null);
            assertThrows(RejectedExecutionException.class, () -> SchematicFiles.submit(() -> null));
            queued.cancel(true);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals("ready", SchematicFiles.submit(() -> "ready").get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.cancel(true);
            if (queued != null) queued.cancel(true);
        }
    }
}
