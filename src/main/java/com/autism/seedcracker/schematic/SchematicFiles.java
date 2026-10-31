package com.autism.seedcracker.schematic;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded local-only file discovery. Paths remain compatible with the module's autism-folder base. */
public final class SchematicFiles {
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    public static final int MAX_PATH_LENGTH = 4096;
    public static final int MAX_RECENT = 10;
    private static final int MAX_VISITS = 4096;
    private static final int MAX_RESULTS = 512;
    // One worker/one queued request, even across screen reopenings. No permanent worker thread.
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> Thread.ofPlatform().daemon().name("Schematic-files").unstarted(task));

    private SchematicFiles() {}

    public record Entry(Path path, boolean directory) {}
    public record Listing(List<Entry> entries, String notice) {}

    public static <T> Future<T> submit(Callable<T> task) {
        IO.purge();
        return IO.submit(task);
    }

    public static boolean supported(Path path) {
        if (path == null || path.getFileName() == null) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".litematic") || name.endsWith(".schem") || name.endsWith(".schematic");
    }

    public static Path resolve(Path base, String input) throws IOException {
        String text = input == null ? "" : input.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty()) throw new IOException("Choose a file or paste its path first.");
        if (text.length() > MAX_PATH_LENGTH || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IOException("The path is too long or contains a line break.");
        }
        try {
            Path path = Path.of(text);
            return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Invalid path. Paste a local file path, not a URL.", e);
        }
    }

    public static Path validate(Path base, String input) throws IOException {
        Path path = resolve(base, input);
        if (!supported(path)) throw new IOException("Choose a .litematic, .schem or .schematic file.");
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(path, BasicFileAttributes.class); }
        catch (java.nio.file.NoSuchFileException e) { throw new IOException("File not found. Browse or paste an existing file: " + path, e); }
        catch (java.nio.file.AccessDeniedException e) { throw new IOException("Permission denied. Select a readable local file: " + path, e); }
        if (!attributes.isRegularFile() || !Files.isReadable(path)) throw new IOException("Not a readable file: " + path);
        if (attributes.size() == 0) throw new IOException("The selected file is empty.");
        if (attributes.size() > MAX_FILE_BYTES) throw new IOException("File exceeds the 16 MiB safety limit. Export a smaller selection.");
        Path real = path.toRealPath();
        if (!supported(real)) throw new IOException("Link target must also be a supported schematic file.");
        return real;
    }

    public static List<Path> recent(String value) {
        if (value == null || value.length() > (MAX_PATH_LENGTH + 1) * MAX_RECENT) return List.of();
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (String text : value.split("\n", MAX_RECENT + 1)) {
            try {
                Path path = Path.of(text.trim()).normalize();
                if (text.length() <= MAX_PATH_LENGTH && text.indexOf('\r') < 0 && path.isAbsolute() && supported(path)) paths.add(path);
            } catch (InvalidPathException ignored) {
                // A damaged/stale history entry must not prevent selecting a different file.
            }
            if (paths.size() == MAX_RECENT) break;
        }
        return List.copyOf(paths);
    }

    public static String remember(String value, Path selected) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        Path path = selected.toAbsolutePath().normalize();
        if (!supported(path) || path.toString().length() > MAX_PATH_LENGTH || path.toString().contains("\n") || path.toString().contains("\r")) {
            throw new IllegalArgumentException("Invalid recent schematic path");
        }
        paths.add(path);
        paths.addAll(recent(value));
        return paths.stream().limit(MAX_RECENT).map(Path::toString).collect(java.util.stream.Collectors.joining("\n"));
    }

    public static Listing scan(List<Path> roots, boolean recursive) throws IOException {
        List<Entry> result = new ArrayList<>();
        int[] visits = {0};
        boolean[] limited = {false};
        List<String> errors = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Path root : new LinkedHashSet<>(roots)) {
            checkInterrupted();
            if (limited[0]) break;
            try {
                Files.walkFileTree(root, java.util.Set.of(), recursive ? 4 : 1, new SimpleFileVisitor<>() {
                    private boolean limit() throws IOException {
                        checkInterrupted();
                        if (++visits[0] > MAX_VISITS || result.size() >= MAX_RESULTS || System.nanoTime() > deadline) {
                            limited[0] = true;
                            return true;
                        }
                        return false;
                    }
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return limit() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (limit()) return FileVisitResult.TERMINATE;
                        if (!recursive && attrs.isDirectory()) result.add(new Entry(file, true));
                        else if (attrs.isRegularFile() && supported(file)) result.add(new Entry(file, false));
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                        if (limit()) return FileVisitResult.TERMINATE;
                        if (errors.size() < 3) errors.add("Cannot read " + file.getFileName());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | SecurityException e) {
                errors.add("Cannot list " + root);
            }
        }
        result.sort(Comparator.comparing(Entry::directory).reversed()
            .thenComparing(entry -> entry.path().toString(), String.CASE_INSENSITIVE_ORDER));
        String notice = limited[0] ? "Partial list (512 results / 4096 entries / 2s). Browse a narrower folder." : result.size() + " entries. Select a file, then Load preview.";
        if (recursive) notice += " Search includes 4 folder levels.";
        if (!errors.isEmpty()) notice += " " + String.join("; ", errors);
        return new Listing(List.copyOf(result), notice);
    }

    public static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("File operation cancelled.");
    }
}
