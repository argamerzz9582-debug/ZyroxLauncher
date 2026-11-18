package git.artdeell.mojo.worlds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Lossless, crash-safe Minecraft Java {@code level.dat} GZIP Named Binary Tag (NBT) reader and
 * quick editor (supports Minecraft 1.0 through 1.21+).
 *
 * <p>Implements all 13 standard Java Edition NBT tag types ({@code TAG_End}..{@code TAG_Long_Array})
 * preserving exact compound key ordering and every untouched vanilla/modded tag, writes atomically
 * via a synced temporary file, and rotates the prior {@code level.dat} to {@code level.dat_old}.
 */
public final class LevelDatNbtEditor {
    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_SHORT = 2;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_FLOAT = 5;
    public static final byte TAG_DOUBLE = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;
    public static final byte TAG_INT_ARRAY = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    public static final int GAMEMODE_SURVIVAL = 0;
    public static final int GAMEMODE_CREATIVE = 1;
    public static final int GAMEMODE_ADVENTURE = 2;
    public static final int GAMEMODE_SPECTATOR = 3;

    private static final int MAX_NBT_DEPTH = 512;
    private static final int MAX_COLLECTION_LEN = 4_194_304;

    public static final class LevelDatSummary {
        @NonNull public final String levelName;
        @NonNull public final String versionName;
        public final int gameType;
        public final boolean allowCommands;
        public final boolean hardcore;
        public final int difficulty;
        public final long seed;
        public final boolean hasSeed;
        public final long dayTime;

        public LevelDatSummary(@NonNull String levelName,
                               @NonNull String versionName,
                               int gameType,
                               boolean allowCommands,
                               boolean hardcore,
                               int difficulty,
                               long seed,
                               boolean hasSeed,
                               long dayTime) {
            this.levelName = levelName;
            this.versionName = versionName;
            this.gameType = gameType;
            this.allowCommands = allowCommands;
            this.hardcore = hardcore;
            this.difficulty = difficulty;
            this.seed = seed;
            this.hasSeed = hasSeed;
            this.dayTime = dayTime;
        }

        @NonNull
        public String getGameModeLabel() {
            return gameModeLabel(gameType);
        }

        @NonNull
        public String getDifficultyLabel() {
            return difficultyLabel(difficulty);
        }
    }

    /** Mutable patch specification; {@code null} fields leave the existing {@code level.dat} tag untouched. */
    public static final class EditSpec {
        @Nullable public String levelName;
        @Nullable public Integer gameType;
        @Nullable public Boolean allowCommands;
        @Nullable public Boolean hardcore;
        @Nullable public Integer difficulty;
        @Nullable public Long seed;

        @NonNull
        public EditSpec setLevelName(@Nullable String name) {
            this.levelName = name;
            return this;
        }

        @NonNull
        public EditSpec setGameType(int mode) {
            this.gameType = Math.max(GAMEMODE_SURVIVAL, Math.min(GAMEMODE_SPECTATOR, mode));
            return this;
        }

        @NonNull
        public EditSpec setAllowCommands(boolean allow) {
            this.allowCommands = allow;
            return this;
        }

        @NonNull
        public EditSpec setHardcore(boolean hc) {
            this.hardcore = hc;
            return this;
        }

        @NonNull
        public EditSpec setDifficulty(int diff) {
            this.difficulty = Math.max(0, Math.min(3, diff));
            return this;
        }

        @NonNull
        public EditSpec setSeed(long worldSeed) {
            this.seed = worldSeed;
            return this;
        }
    }

    private LevelDatNbtEditor() {
    }

    @NonNull
    public static String gameModeLabel(int mode) {
        switch (mode) {
            case GAMEMODE_CREATIVE: return "Creative";
            case GAMEMODE_ADVENTURE: return "Adventure";
            case GAMEMODE_SPECTATOR: return "Spectator";
            case GAMEMODE_SURVIVAL:
            default: return "Survival";
        }
    }

    @NonNull
    public static String difficultyLabel(int diff) {
        switch (diff) {
            case 0: return "Peaceful";
            case 1: return "Easy";
            case 3: return "Hard";
            case 2:
            default: return "Normal";
        }
    }

    /**
     * Converts a Minecraft seed input string into its exact Java Edition {@code long} seed:
     * parses decimal 64-bit longs directly, or falls back to {@link String#hashCode()} just like
     * vanilla Minecraft's world-creation screen.
     */
    public static long parseMinecraftSeed(@NonNull String rawSeed) {
        String trimmed = rawSeed.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Seed cannot be empty");
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed.hashCode();
        }
    }

    /** Reads key gameplay metadata from a GZIP-compressed {@code level.dat} file. */
    @NonNull
    public static LevelDatSummary readSummary(@NonNull File levelDatFile) throws IOException {
        RootCompound root = readRootCompound(levelDatFile);
        NbtTag data = unwrapDataCompound(root.compound);
        return extractSummary(data);
    }

    /**
     * Atomically applies {@code edits} to {@code levelDatFile} (backing up the prior file to
     * {@code level.dat_old}), preserving every untouched NBT tag, and returns the updated summary.
     */
    @NonNull
    public static LevelDatSummary applyEdits(@NonNull File levelDatFile,
                                             @NonNull EditSpec edits) throws IOException {
        RootCompound root = readRootCompound(levelDatFile);
        NbtTag data = unwrapDataCompound(root.compound);

        if (edits.levelName != null && !edits.levelName.trim().isEmpty()) {
            putTag(data, "LevelName", NbtTag.ofString(edits.levelName.trim()));
        }
        if (edits.gameType != null) {
            int mode = Math.max(GAMEMODE_SURVIVAL, Math.min(GAMEMODE_SPECTATOR, edits.gameType));
            putTag(data, "GameType", NbtTag.ofInt(mode));
            // Also sync singleplayer Player.playerGameType so next load switches immediately
            NbtTag player = getCompound(data, "Player");
            if (player != null) {
                putTag(player, "playerGameType", NbtTag.ofInt(mode));
            }
        }
        if (edits.allowCommands != null) {
            putTag(data, "allowCommands", NbtTag.ofByte((byte) (edits.allowCommands ? 1 : 0)));
        }
        if (edits.hardcore != null) {
            putTag(data, "hardcore", NbtTag.ofByte((byte) (edits.hardcore ? 1 : 0)));
        }
        if (edits.difficulty != null) {
            int diff = Math.max(0, Math.min(3, edits.difficulty));
            putTag(data, "Difficulty", NbtTag.ofByte((byte) diff));
        }
        if (edits.seed != null) {
            long newSeed = edits.seed;
            boolean updatedModern = false;
            NbtTag wgs = getCompound(data, "WorldGenSettings");
            if (wgs != null) {
                putTag(wgs, "seed", NbtTag.ofLong(newSeed));
                updatedModern = true;
            }
            if (!updatedModern || data.compoundVal.containsKey("RandomSeed")) {
                putTag(data, "RandomSeed", NbtTag.ofLong(newSeed));
            }
        }

        writeRootCompoundAtomic(levelDatFile, root);
        return extractSummary(data);
    }

    @NonNull
    private static LevelDatSummary extractSummary(@NonNull NbtTag data) {
        String levelName = getString(data, "LevelName", "");
        String versionName = "";
        NbtTag ver = getCompound(data, "Version");
        if (ver != null) {
            versionName = getString(ver, "Name", "");
        }
        int gameType = getInt(data, "GameType", GAMEMODE_SURVIVAL);
        boolean allowCommands = getByteAsBool(data, "allowCommands", false);
        boolean hardcore = getByteAsBool(data, "hardcore", false);
        int difficulty = getInt(data, "Difficulty", 2);
        long dayTime = getLong(data, "DayTime", 0L);

        boolean hasSeed = false;
        long seed = 0L;
        NbtTag wgs = getCompound(data, "WorldGenSettings");
        if (wgs != null && wgs.compoundVal.containsKey("seed")) {
            seed = getLong(wgs, "seed", 0L);
            hasSeed = true;
        } else if (data.compoundVal.containsKey("RandomSeed")) {
            seed = getLong(data, "RandomSeed", 0L);
            hasSeed = true;
        }

        return new LevelDatSummary(
                levelName, versionName, gameType, allowCommands, hardcore,
                difficulty, seed, hasSeed, dayTime);
    }

    @NonNull
    private static NbtTag unwrapDataCompound(@NonNull NbtTag root) {
        NbtTag child = getCompound(root, "Data");
        return child != null ? child : root;
    }

    // =========================================================================
    // Low-Level GZIP Big-Endian NBT Serializer / Deserializer
    // =========================================================================

    static final class RootCompound {
        @NonNull final String rootName;
        @NonNull final NbtTag compound;

        RootCompound(@NonNull String rootName, @NonNull NbtTag compound) {
            this.rootName = rootName;
            this.compound = compound;
        }
    }

    static final class NbtTag {
        final byte type;
        long intVal;
        float floatVal;
        double doubleVal;
        String stringVal;
        byte[] byteArrayVal;
        int[] intArrayVal;
        long[] longArrayVal;
        byte listElemType;
        List<NbtTag> listVal;
        Map<String, NbtTag> compoundVal;

        private NbtTag(byte type) {
            this.type = type;
        }

        static NbtTag ofByte(byte v) { NbtTag t = new NbtTag(TAG_BYTE); t.intVal = v; return t; }
        static NbtTag ofShort(short v) { NbtTag t = new NbtTag(TAG_SHORT); t.intVal = v; return t; }
        static NbtTag ofInt(int v) { NbtTag t = new NbtTag(TAG_INT); t.intVal = v; return t; }
        static NbtTag ofLong(long v) { NbtTag t = new NbtTag(TAG_LONG); t.intVal = v; return t; }
        static NbtTag ofFloat(float v) { NbtTag t = new NbtTag(TAG_FLOAT); t.floatVal = v; return t; }
        static NbtTag ofDouble(double v) { NbtTag t = new NbtTag(TAG_DOUBLE); t.doubleVal = v; return t; }
        static NbtTag ofString(@NonNull String v) { NbtTag t = new NbtTag(TAG_STRING); t.stringVal = v; return t; }
        static NbtTag ofCompound() {
            NbtTag t = new NbtTag(TAG_COMPOUND);
            t.compoundVal = new LinkedHashMap<>();
            return t;
        }
    }

    @NonNull
    static RootCompound readRootCompound(@NonNull File file) throws IOException {
        if (!file.isFile() || file.length() < 10L) {
            throw new IOException("level.dat is missing or empty");
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            byte rootType = in.readByte();
            if (rootType != TAG_COMPOUND) {
                throw new IOException("Invalid level.dat root NBT tag type: " + rootType);
            }
            String rootName = in.readUTF();
            NbtTag compound = readPayload(in, TAG_COMPOUND, 0);
            return new RootCompound(rootName, compound);
        }
    }

    static void writeRootCompoundAtomic(@NonNull File target, @NonNull RootCompound root) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create world directory: " + parent);
        }
        File tmp = new File(parent, target.getName() + ".zyrox.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(fos));
            DataOutputStream out = new DataOutputStream(gz);
            out.writeByte(TAG_COMPOUND);
            out.writeUTF(root.rootName);
            writePayload(out, root.compound);
            out.flush();
            gz.finish();
            gz.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        // Preserve official Minecraft fallback copy before swapping
        if (target.isFile()) {
            File backupOld = new File(parent, "level.dat_old");
            copyFileSafe(target, backupOld);
        }

        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("Could not replace level.dat (file locked)");
            }
            if (!tmp.renameTo(target)) {
                copyFileSafe(tmp, target);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static void copyFileSafe(@NonNull File src, @NonNull File dst) throws IOException {
        byte[] buf = new byte[16 * 1024];
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }

    @NonNull
    private static NbtTag readPayload(@NonNull DataInputStream in, byte type, int depth) throws IOException {
        if (depth > MAX_NBT_DEPTH) {
            throw new IOException("NBT nesting exceeds safe depth limit (" + MAX_NBT_DEPTH + ")");
        }
        switch (type) {
            case TAG_BYTE: return NbtTag.ofByte(in.readByte());
            case TAG_SHORT: return NbtTag.ofShort(in.readShort());
            case TAG_INT: return NbtTag.ofInt(in.readInt());
            case TAG_LONG: return NbtTag.ofLong(in.readLong());
            case TAG_FLOAT: return NbtTag.ofFloat(in.readFloat());
            case TAG_DOUBLE: return NbtTag.ofDouble(in.readDouble());
            case TAG_BYTE_ARRAY: {
                int len = checkLen(in.readInt());
                byte[] arr = new byte[len];
                in.readFully(arr);
                NbtTag t = new NbtTag(TAG_BYTE_ARRAY);
                t.byteArrayVal = arr;
                return t;
            }
            case TAG_STRING: return NbtTag.ofString(in.readUTF());
            case TAG_LIST: {
                byte elemType = in.readByte();
                int len = checkLen(in.readInt());
                NbtTag t = new NbtTag(TAG_LIST);
                t.listElemType = elemType;
                t.listVal = new ArrayList<>(Math.min(len, 1024));
                for (int i = 0; i < len; i++) {
                    if (elemType == TAG_END) break;
                    t.listVal.add(readPayload(in, elemType, depth + 1));
                }
                return t;
            }
            case TAG_COMPOUND: {
                NbtTag t = NbtTag.ofCompound();
                while (true) {
                    byte childType = in.readByte();
                    if (childType == TAG_END) break;
                    String key = in.readUTF();
                    t.compoundVal.put(key, readPayload(in, childType, depth + 1));
                }
                return t;
            }
            case TAG_INT_ARRAY: {
                int len = checkLen(in.readInt());
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                NbtTag t = new NbtTag(TAG_INT_ARRAY);
                t.intArrayVal = arr;
                return t;
            }
            case TAG_LONG_ARRAY: {
                int len = checkLen(in.readInt());
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                NbtTag t = new NbtTag(TAG_LONG_ARRAY);
                t.longArrayVal = arr;
                return t;
            }
            default:
                throw new IOException("Unsupported NBT tag id: " + type);
        }
    }

    private static void writePayload(@NonNull DataOutputStream out, @NonNull NbtTag tag) throws IOException {
        switch (tag.type) {
            case TAG_BYTE: out.writeByte((int) tag.intVal); return;
            case TAG_SHORT: out.writeShort((int) tag.intVal); return;
            case TAG_INT: out.writeInt((int) tag.intVal); return;
            case TAG_LONG: out.writeLong(tag.intVal); return;
            case TAG_FLOAT: out.writeFloat(tag.floatVal); return;
            case TAG_DOUBLE: out.writeDouble(tag.doubleVal); return;
            case TAG_BYTE_ARRAY:
                out.writeInt(tag.byteArrayVal.length);
                out.write(tag.byteArrayVal);
                return;
            case TAG_STRING:
                out.writeUTF(tag.stringVal != null ? tag.stringVal : "");
                return;
            case TAG_LIST:
                if (tag.listVal == null || tag.listVal.isEmpty()) {
                    out.writeByte(tag.listElemType);
                    out.writeInt(0);
                    return;
                }
                out.writeByte(tag.listElemType);
                out.writeInt(tag.listVal.size());
                for (NbtTag elem : tag.listVal) {
                    writePayload(out, elem);
                }
                return;
            case TAG_COMPOUND:
                if (tag.compoundVal != null) {
                    for (Map.Entry<String, NbtTag> e : tag.compoundVal.entrySet()) {
                        NbtTag child = e.getValue();
                        if (child == null || child.type == TAG_END) continue;
                        out.writeByte(child.type);
                        out.writeUTF(e.getKey());
                        writePayload(out, child);
                    }
                }
                out.writeByte(TAG_END);
                return;
            case TAG_INT_ARRAY:
                out.writeInt(tag.intArrayVal.length);
                for (int v : tag.intArrayVal) out.writeInt(v);
                return;
            case TAG_LONG_ARRAY:
                out.writeInt(tag.longArrayVal.length);
                for (long v : tag.longArrayVal) out.writeLong(v);
                return;
            default:
                throw new IOException("Cannot serialize unknown NBT tag id: " + tag.type);
        }
    }

    private static int checkLen(int len) throws IOException {
        if (len < 0 || len > MAX_COLLECTION_LEN) {
            throw new IOException("Corrupt NBT array/list length: " + len);
        }
        return len;
    }

    private static void putTag(@NonNull NbtTag compound, @NonNull String key, @NonNull NbtTag val) {
        compound.compoundVal.put(key, val);
    }

    @Nullable
    private static NbtTag getCompound(@NonNull NbtTag compound, @NonNull String key) {
        if (compound.compoundVal == null) return null;
        NbtTag c = compound.compoundVal.get(key);
        return (c != null && c.type == TAG_COMPOUND) ? c : null;
    }

    @NonNull
    private static String getString(@NonNull NbtTag compound, @NonNull String key, @NonNull String def) {
        NbtTag c = compound.compoundVal.get(key);
        return (c != null && c.type == TAG_STRING && c.stringVal != null) ? c.stringVal : def;
    }

    private static int getInt(@NonNull NbtTag compound, @NonNull String key, int def) {
        NbtTag c = compound.compoundVal.get(key);
        if (c == null) return def;
        if (c.type == TAG_INT || c.type == TAG_BYTE || c.type == TAG_SHORT || c.type == TAG_LONG) {
            return (int) c.intVal;
        }
        return def;
    }

    private static long getLong(@NonNull NbtTag compound, @NonNull String key, long def) {
        NbtTag c = compound.compoundVal.get(key);
        if (c == null) return def;
        if (c.type == TAG_LONG || c.type == TAG_INT || c.type == TAG_SHORT || c.type == TAG_BYTE) {
            return c.intVal;
        }
        return def;
    }

    private static boolean getByteAsBool(@NonNull NbtTag compound, @NonNull String key, boolean def) {
        NbtTag c = compound.compoundVal.get(key);
        if (c == null) return def;
        if (c.type == TAG_BYTE || c.type == TAG_INT || c.type == TAG_SHORT) {
            return c.intVal != 0;
        }
        return def;
    }
}
