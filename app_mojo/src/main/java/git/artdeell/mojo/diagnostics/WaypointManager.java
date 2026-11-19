package git.artdeell.mojo.diagnostics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Client-mod-free, zero-F3 coordinate and death waypoint persistence &amp; log/clipboard parser.
 * Stores up to {@value #MAX_WAYPOINTS} waypoints per instance in
 * {@code <gameDir>/zyrox_waypoints.json} using atomic file replacement.
 */
public final class WaypointManager {

    public static final String WAYPOINTS_FILE_NAME = "zyrox_waypoints.json";
    public static final int MAX_WAYPOINTS = 100;

    public static final String DIM_OVERWORLD = "minecraft:overworld";
    public static final String DIM_NETHER = "minecraft:the_nether";
    public static final String DIM_END = "minecraft:the_end";

    /**
     * Matches vanilla Minecraft 1.13+ {@code F3+C} clipboard output, e.g.:
     * {@code /execute in minecraft:overworld run tp @s -248.42 69.00 1042.85 180.00 0.00}
     */
    private static final Pattern F3C_CLIPBOARD_PATTERN = Pattern.compile(
            "(?i)/?execute\\s+in\\s+([a-z0-9_.:/-]+)\\s+run\\s+tp\\s+@s\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)");

    /**
     * Matches teleport / spawnpoint / login coordinates printed in vanilla Minecraft logs, e.g.:
     * {@code Teleported Steve to -120.50, 64.00, 340.25} or {@code spawn point to [-12, 70, 88]}.
     */
    private static final Pattern LOG_COORD_PATTERN = Pattern.compile(
            "(?i)(?:teleported\\s+\\S+\\s+to|spawn\\s*point\\s+to|at\\s*\\((?:\\[[^]]+])?)\\s*\\[?"
                    + "(-?\\d+(?:\\.\\d+)?)[,\\s]+(-?\\d+(?:\\.\\d+)?)[,\\s]+(-?\\d+(?:\\.\\d+)?)");

    /** Matches vanilla Java Edition death chat announcements logged by {@code [CHAT]}. */
    private static final Pattern LOG_DEATH_PATTERN = Pattern.compile(
            "(?i)\\[CHAT]\\s+(?:You died!|\\S+\\s+(?:was slain by|was shot by|fell from a high place"
                    + "|hit the ground too hard|drowned|burned to death|tried to swim in lava"
                    + "|blew up|was blown up by|suffocated in a wall|starved to death"
                    + "|fell out of the world|withered away|was killed by|was impaled by"
                    + "|was obliterated by|froze to death|experienced kinetic energy))");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WaypointManager() {
    }

    /** Immutable parsed coordinate sample. */
    public static final class CoordinateSample {
        public final int x;
        public final int y;
        public final int z;
        @NonNull public final String dimension;

        public CoordinateSample(int x, int y, int z, @NonNull String dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = normalizeDimension(dimension);
        }

        @NonNull
        public String formatCompact() {
            return x + ", " + y + ", " + z;
        }
    }

    /** Saved named coordinate or death location entry. */
    public static final class Waypoint {
        @NonNull public final String id;
        @NonNull public final String name;
        public final int x;
        public final int y;
        public final int z;
        @NonNull public final String dimension;
        public final boolean deathMarker;
        public final boolean pinned;
        public final long createdAtMs;

        public Waypoint(@NonNull String id,
                        @NonNull String name,
                        int x,
                        int y,
                        int z,
                        @NonNull String dimension,
                        boolean deathMarker,
                        boolean pinned,
                        long createdAtMs) {
            this.id = id;
            this.name = sanitizeName(name, deathMarker ? "Death" : "Waypoint");
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = normalizeDimension(dimension);
            this.deathMarker = deathMarker;
            this.pinned = pinned;
            this.createdAtMs = createdAtMs;
        }

        @NonNull
        public Waypoint withPinned(boolean newPinned) {
            return new Waypoint(id, name, x, y, z, dimension, deathMarker, newPinned, createdAtMs);
        }

        /** Compact HUD badge text (e.g. {@code "DEATH 120, 64, -88 (OW)"} or {@code "Base -248, 69, 1043"}). */
        @NonNull
        public String formatHudBadge() {
            String prefix = deathMarker ? "☠ " : "📍 ";
            String shortName = name.length() > 14 ? name.substring(0, 13) + "…" : name;
            return prefix + shortName + " " + x + " " + y + " " + z
                    + " [" + shortDimensionTag(dimension) + "]";
        }

        /** Friendly list item label for the in-game Waypoint Manager dialog. */
        @NonNull
        public String formatListLabel() {
            String icon = deathMarker ? "☠ " : (pinned ? "★ " : "📍 ");
            return icon + name + "  •  " + x + ", " + y + ", " + z
                    + "  (" + friendlyDimensionName(dimension) + ")";
        }

        /** Clean chat string ready to share with friends in multiplayer. */
        @NonNull
        public String formatChatMessage() {
            return "[" + name + "] X: " + x + ", Y: " + y + ", Z: " + z
                    + " (" + friendlyDimensionName(dimension) + ")";
        }

        /** Dimension-safe vanilla {@code /execute ... tp} command for singleplayer/OP worlds. */
        @NonNull
        public String formatTeleportCommand() {
            return "/execute in " + dimension + " run tp @s " + x + " " + y + " " + z;
        }
    }

    /** Loads all persisted waypoints for {@code gameDir} (newest first). */
    @NonNull
    public static synchronized List<Waypoint> load(@Nullable File gameDir) {
        if (gameDir == null) return new ArrayList<>();
        File file = new File(gameDir, WAYPOINTS_FILE_NAME);
        if (!file.isFile() || file.length() <= 0L || file.length() > 1024L * 1024L) {
            return new ArrayList<>();
        }
        try {
            String raw = Tools.read(file.getAbsolutePath());
            JsonElement rootEl = JsonParser.parseString(raw);
            if (!rootEl.isJsonObject()) return new ArrayList<>();
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("waypoints") || !root.get("waypoints").isJsonArray()) {
                return new ArrayList<>();
            }
            JsonArray arr = root.getAsJsonArray("waypoints");
            List<Waypoint> out = new ArrayList<>(Math.min(MAX_WAYPOINTS, arr.size()));
            for (JsonElement el : arr) {
                if (!el.isJsonObject() || out.size() >= MAX_WAYPOINTS) continue;
                JsonObject o = el.getAsJsonObject();
                boolean isDeath = o.has("deathMarker") && o.get("deathMarker").getAsBoolean();
                boolean isPinned = o.has("pinned") && o.get("pinned").getAsBoolean();
                String id = o.has("id") && !o.get("id").isJsonNull()
                        ? o.get("id").getAsString() : ("wp-" + out.size());
                String name = o.has("name") && !o.get("name").isJsonNull()
                        ? o.get("name").getAsString() : (isDeath ? "Death" : "Waypoint");
                int x = o.has("x") ? o.get("x").getAsInt() : 0;
                int y = o.has("y") ? o.get("y").getAsInt() : 64;
                int z = o.has("z") ? o.get("z").getAsInt() : 0;
                String dim = o.has("dimension") && !o.get("dimension").isJsonNull()
                        ? o.get("dimension").getAsString() : DIM_OVERWORLD;
                long ts = o.has("createdAtMs") ? o.get("createdAtMs").getAsLong() : 0L;
                out.add(new Waypoint(id, name, x, y, z, dim, isDeath, isPinned, ts));
            }
            return out;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** Atomically persists {@code waypoints} into {@code <gameDir>/zyrox_waypoints.json}. */
    public static synchronized void save(@NonNull File gameDir,
                                         @NonNull List<Waypoint> waypoints) throws IOException {
        FileUtils.ensureDirectory(gameDir);
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray arr = new JsonArray();
        int cap = Math.min(MAX_WAYPOINTS, waypoints.size());
        for (int i = 0; i < cap; i++) {
            Waypoint w = waypoints.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", w.id);
            o.addProperty("name", w.name);
            o.addProperty("x", w.x);
            o.addProperty("y", w.y);
            o.addProperty("z", w.z);
            o.addProperty("dimension", w.dimension);
            o.addProperty("deathMarker", w.deathMarker);
            o.addProperty("pinned", w.pinned);
            o.addProperty("createdAtMs", w.createdAtMs);
            arr.add(o);
        }
        root.add("waypoints", arr);

        File target = new File(gameDir, WAYPOINTS_FILE_NAME);
        File tmp = new File(gameDir, WAYPOINTS_FILE_NAME + ".tmp");
        byte[] payload = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(payload);
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not update " + WAYPOINTS_FILE_NAME);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Could not finalize " + WAYPOINTS_FILE_NAME);
        }
    }

    /** Creates, pins, prepends, and persists a new named coordinate or death waypoint. */
    @NonNull
    public static synchronized Waypoint addAndPin(@NonNull File gameDir,
                                                  @NonNull String name,
                                                  int x,
                                                  int y,
                                                  int z,
                                                  @NonNull String dimension,
                                                  boolean deathMarker) throws IOException {
        List<Waypoint> current = load(gameDir);
        List<Waypoint> updated = new ArrayList<>(Math.min(MAX_WAYPOINTS, current.size() + 1));
        long now = System.currentTimeMillis();
        Waypoint created = new Waypoint(
                "wp-" + Long.toHexString(now) + "-" + updated.size(),
                name, x, y, z, dimension, deathMarker, true, now);
        updated.add(created);
        for (Waypoint existing : current) {
            if (updated.size() >= MAX_WAYPOINTS) break;
            updated.add(existing.pinned ? existing.withPinned(false) : existing);
        }
        save(gameDir, updated);
        return created;
    }

    /** Pins {@code waypointId} as the active HUD pill target (or unpins all if {@code null}). */
    @Nullable
    public static synchronized Waypoint setPinned(@NonNull File gameDir,
                                                  @Nullable String waypointId) throws IOException {
        List<Waypoint> current = load(gameDir);
        List<Waypoint> updated = new ArrayList<>(current.size());
        Waypoint active = null;
        for (Waypoint w : current) {
            boolean pin = waypointId != null && waypointId.equals(w.id);
            Waypoint nw = (w.pinned == pin) ? w : w.withPinned(pin);
            if (pin) active = nw;
            updated.add(nw);
        }
        save(gameDir, updated);
        return active;
    }

    /** Deletes {@code waypointId} from {@code gameDir} and returns the remaining list. */
    @NonNull
    public static synchronized List<Waypoint> delete(@NonNull File gameDir,
                                                     @NonNull String waypointId) throws IOException {
        List<Waypoint> current = load(gameDir);
        List<Waypoint> remaining = new ArrayList<>(current.size());
        for (Waypoint w : current) {
            if (!waypointId.equals(w.id)) {
                remaining.add(w);
            }
        }
        save(gameDir, remaining);
        return Collections.unmodifiableList(remaining);
    }

    /** Returns the pinned waypoint, or the newest death/saved waypoint if none is explicitly pinned. */
    @Nullable
    public static Waypoint findActiveWaypoint(@NonNull List<Waypoint> list) {
        if (list.isEmpty()) return null;
        for (Waypoint w : list) {
            if (w.pinned) return w;
        }
        return list.get(0);
    }

    /** Parses vanilla {@code F3+C} clipboard text into exact integer block coordinates &amp; dimension. */
    @Nullable
    public static CoordinateSample parseF3ClipboardCommand(@Nullable String clipText) {
        if (clipText == null || clipText.isEmpty()) return null;
        Matcher m = F3C_CLIPBOARD_PATTERN.matcher(clipText.trim());
        if (!m.find()) return null;
        try {
            String dim = m.group(1);
            int x = (int) Math.floor(Double.parseDouble(m.group(2)));
            int y = (int) Math.floor(Double.parseDouble(m.group(3)));
            int z = (int) Math.floor(Double.parseDouble(m.group(4)));
            return new CoordinateSample(x, y, z, dim);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Extracts player coordinates from vanilla teleport, spawnpoint, or login log messages. */
    @Nullable
    public static CoordinateSample parseCoordinatesFromLogLine(@Nullable String logLine) {
        if (logLine == null || logLine.length() < 10 || logLine.length() > 512) return null;
        CoordinateSample f3 = parseF3ClipboardCommand(logLine);
        if (f3 != null) return f3;
        Matcher m = LOG_COORD_PATTERN.matcher(logLine);
        if (!m.find()) return null;
        try {
            int x = (int) Math.floor(Double.parseDouble(m.group(1)));
            int y = (int) Math.floor(Double.parseDouble(m.group(2)));
            int z = (int) Math.floor(Double.parseDouble(m.group(3)));
            String low = logLine.toLowerCase(Locale.ROOT);
            String dim = low.contains("the_nether") ? DIM_NETHER
                    : (low.contains("the_end") ? DIM_END : DIM_OVERWORLD);
            return new CoordinateSample(x, y, z, dim);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Returns {@code true} when a client log line reports a vanilla player death announcement. */
    public static boolean isDeathLogLine(@Nullable String logLine,
                                         @Nullable String activePlayerName) {
        if (logLine == null || !logLine.contains("[CHAT]")) return false;
        if (!LOG_DEATH_PATTERN.matcher(logLine).find()) return false;
        if (activePlayerName == null || activePlayerName.trim().isEmpty()) {
            return logLine.contains("You died!");
        }
        String chatPart = logLine.substring(logLine.indexOf("[CHAT]") + 6).trim();
        return chatPart.startsWith("You died!")
                || chatPart.regionMatches(true, 0, activePlayerName.trim() + " ",
                0, activePlayerName.trim().length() + 1);
    }

    @NonNull
    public static String normalizeDimension(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return DIM_OVERWORLD;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if ("overworld".equals(clean) || DIM_OVERWORLD.equals(clean)) return DIM_OVERWORLD;
        if ("nether".equals(clean) || "the_nether".equals(clean) || DIM_NETHER.equals(clean)) {
            return DIM_NETHER;
        }
        if ("end".equals(clean) || "the_end".equals(clean) || DIM_END.equals(clean)) {
            return DIM_END;
        }
        return clean.replaceAll("[^a-z0-9_.:/-]", "");
    }

    @NonNull
    public static String shortDimensionTag(@NonNull String dimension) {
        if (DIM_NETHER.equals(dimension)) return "Nether";
        if (DIM_END.equals(dimension)) return "End";
        if (DIM_OVERWORLD.equals(dimension)) return "OW";
        int colon = dimension.indexOf(':');
        return colon >= 0 && colon + 1 < dimension.length()
                ? dimension.substring(colon + 1) : dimension;
    }

    @NonNull
    public static String friendlyDimensionName(@NonNull String dimension) {
        if (DIM_NETHER.equals(dimension)) return "Nether";
        if (DIM_END.equals(dimension)) return "The End";
        if (DIM_OVERWORLD.equals(dimension)) return "Overworld";
        return shortDimensionTag(dimension);
    }

    @NonNull
    public static String sanitizeName(@Nullable String raw, @NonNull String fallback) {
        if (raw == null) return fallback;
        String clean = raw.replaceAll("[\\r\\n\\t]", " ").trim();
        if (clean.isEmpty()) return fallback;
        return clean.length() > 32 ? clean.substring(0, 32) : clean;
    }
}
