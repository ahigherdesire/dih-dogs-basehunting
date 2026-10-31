package com.autism.seedcracker.baselog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dihclient.DihClientAddon;
import net.minecraft.client.Minecraft;

/** Local review of unverified coordinates in the existing bases.txt format. */
public final class BaseLog {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_LINES = 50_000;
    private static final Pattern LINES = Pattern.compile("([^\\r\\n]*)(?:\\r\\n|\\r|\\n|$)");

    private BaseLog() {}

    public static final class Entry {
        public final int x, y, z;
        public final String dimension, blockId, timestamp, raw;
        final int start, end;

        Entry(int x, int y, int z, String dimension, String blockId, String timestamp,
              String raw, int start, int end) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.blockId = blockId;
            this.timestamp = timestamp;
            this.raw = raw;
            this.start = start;
            this.end = end;
        }

        public double distanceTo(double px, double pz) { return Math.hypot(x - px, z - pz); }
        public String coord() { return x + " " + y + " " + z; }
        public String details() {
            return "Candidate (unverified): " + coord() + "\nDimension: " + dimension
                + "\nServer/world: not recorded\nBlock: " + (blockId.isEmpty() ? "not recorded" : blockId)
                + "\nLogged: " + (timestamp.isEmpty() ? "not recorded" : timestamp);
        }
    }

    public record Snapshot(String text, List<Entry> entries, int skipped) {}

    public static Path file() { return DihClientAddon.FOLDER.toPath().resolve("bases.txt"); }

    public static Snapshot read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Snapshot("", List.of(), 0);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular log file.");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Log exceeds 4 MiB; review a smaller copy. File unchanged.");
        // Reject invalid UTF-8 rather than silently replacing bytes during a later delete.
        return parseText(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }

    static Snapshot parseText(String text) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Matcher lines = LINES.matcher(text);
        int count = 0, skipped = 0;
        while (lines.find() && lines.end() > lines.start()) {
            if (++count > MAX_LINES) throw new IOException("Log exceeds 50,000 lines; review a smaller copy. File unchanged.");
            Entry entry = parse(lines.group(1), lines.start(), lines.end());
            if (entry != null) entries.add(entry);
            else if (!lines.group(1).isBlank()) skipped++;
        }
        return new Snapshot(text, List.copyOf(entries), skipped);
    }

    static Entry parse(String raw) { return parse(raw, -1, -1); }

    private static Entry parse(String raw, int start, int end) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) return null;
        String[] tokens = raw.strip().split("\\s+");
        if (tokens.length < 3) return null;
        int x, y, z;
        try {
            x = Integer.parseInt(tokens[0]);
            y = Integer.parseInt(tokens[1]);
            z = Integer.parseInt(tokens[2]);
        } catch (NumberFormatException ignored) { return null; }
        String dimension = tokens.length > 3 ? tokens[3] : "unknown";
        String block = "", timestamp = "";
        if (tokens.length > 4) {
            int timeStart = 4;
            if (!looksLikeTimestamp(tokens[4])) { block = tokens[4]; timeStart = 5; }
            timestamp = String.join(" ", java.util.Arrays.copyOfRange(tokens, timeStart, tokens.length));
        }
        return new Entry(x, y, z, dimension, block, timestamp, raw, start, end);
    }

    private static boolean looksLikeTimestamp(String value) {
        return value.matches("\\d{4}-\\d{2}-\\d{2}.*") || value.matches("\\d{1,2}:\\d{2}.*");
    }

    static List<Entry> filter(List<Entry> entries, String query, boolean nearest, double[] position) {
        String[] words = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            String haystack = (entry.raw + " " + entry.dimension).toLowerCase(Locale.ROOT);
            boolean match = true;
            for (String word : words) if (!haystack.contains(word)) { match = false; break; }
            if (match) result.add(entry);
        }
        Collections.reverse(result);
        if (nearest && position != null) result.sort(Comparator.comparingDouble(e -> e.distanceTo(position[0], position[1])));
        return result;
    }

    static Entry retainSelection(Entry selected, List<Entry> visible) {
        return visible.contains(selected) ? selected : null;
    }

    /** Removes exactly this occurrence, preserving every other byte including malformed lines. */
    static String without(Snapshot snapshot, Entry entry) throws IOException {
        if (entry == null || !snapshot.entries().contains(entry) || entry.start < 0 || entry.end > snapshot.text().length()) {
            throw new IOException("Selection is stale. Refresh and select the candidate again.");
        }
        return snapshot.text().substring(0, entry.start) + snapshot.text().substring(entry.end);
    }

    /** Atomic replacement only; a backup retains the original file, including duplicate records. */
    public static Path remove(Path path, Snapshot snapshot, Entry entry) throws IOException {
        String remaining = without(snapshot, entry);
        if (!read(path).text().equals(snapshot.text())) throw new IOException("Log changed. Refresh before deleting; nothing deleted.");
        String suffix = System.currentTimeMillis() + "-" + UUID.randomUUID();
        Path staged = path.resolveSibling(path.getFileName() + ".review-" + suffix);
        Path backup = path.resolveSibling(path.getFileName() + ".backup-" + suffix);
        try {
            Files.writeString(staged, remaining, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            // A hard link keeps the original inode, including an already-open writer's late append.
            // If unavailable, fail closed rather than falling back to a destructive in-place rewrite.
            try {
                Files.createLink(backup, path);
            } catch (UnsupportedOperationException exception) {
                throw new IOException("Safety backups unsupported on this file system; nothing deleted.", exception);
            }
            if (!read(path).text().equals(snapshot.text())) throw new IOException("Log changed. Refresh before deleting; nothing deleted.");
            Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static double[] playerPos() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.player == null ? null : new double[] {mc.player.getX(), mc.player.getZ()};
    }

    public static String shortDim(String dimension) {
        if (dimension == null || dimension.isBlank()) return "unknown";
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }

    public static String shortBlock(String block) {
        if (block == null) return "";
        int colon = block.indexOf(':');
        return colon < 0 ? block : block.substring(colon + 1);
    }

    public static String formatDistance(double distance) {
        return distance >= 1000 ? String.format(Locale.ROOT, "%.2f km", distance / 1000)
            : String.format(Locale.ROOT, "%.0f m", distance);
    }
}
