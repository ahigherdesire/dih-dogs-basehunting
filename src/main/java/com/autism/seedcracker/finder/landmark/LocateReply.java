package com.autism.seedcracker.finder.landmark;

import java.util.regex.Pattern;

/** Correlates locate results with their requested world/type; does not infer player activity. */
public final class LocateReply {
    private static final Pattern COORDS = Pattern.compile("\\[?(-?\\d+)[,\\s]+(~|-?\\d+)[,\\s]+(-?\\d+)\\]?");
    private static final Pattern ENGLISH = Pattern.compile("^The nearest (.+?) is at (.+?)(?: \\([^\\n]*blocks away\\))?[.!]?$");
    private LocateReply() {}

    public record Request(Object world, String structure, long deadline) {
        public boolean valid(Object currentWorld, String currentStructure, long now) {
            return world != null && world == currentWorld && structure.equals(currentStructure) && now - deadline <= 0;
        }
    }

    public record Location(int x, int y, int z, boolean knownY) {}

    public static Location parse(String requested, String label, String coordinates) {
        if (requested == null || label == null || coordinates == null) return null;
        String name = label.startsWith("#") ? label.substring(1) : label;
        String exact = "minecraft:" + requested;
        boolean match = name.equals(exact) || name.startsWith(exact + " (");
        if (requested.equals("village") || requested.equals("ruined_portal")) match |= name.startsWith(exact + "_");
        if (!match) return null;
        String coords = coordinates.trim();
        if (coords.startsWith("[") != coords.endsWith("]")) return null;
        var matcher = COORDS.matcher(coords);
        if (!matcher.matches()) return null;
        try {
            int x = Integer.parseInt(matcher.group(1));
            boolean knownY = !matcher.group(2).equals("~");
            // ponytail: missing Y uses a display plane, not structure height; refine only with loaded terrain if needed.
            int y = knownY ? Integer.parseInt(matcher.group(2)) : 64;
            int z = Integer.parseInt(matcher.group(3));
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000) return null;
            return new Location(x, y, z, knownY);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public static Location parseEnglish(String requested, String message) {
        if (message == null) return null;
        var matcher = ENGLISH.matcher(message);
        return matcher.matches() ? parse(requested, matcher.group(1), matcher.group(2)) : null;
    }
}
