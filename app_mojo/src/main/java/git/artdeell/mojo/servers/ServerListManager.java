package git.artdeell.mojo.servers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

/**
 * Zyrox 2.0 Minecraft Server List Manager with native {@code servers.dat} uncompressed NBT support,
 * non-destructive favorites/groups sidecar metadata, host/port validation, portable JSON export/import,
 * and quick-connect launch argument formatting.
 */
public final class ServerListManager {
    public static final int DEFAULT_MC_PORT = 25565;
    private static final String SERVERS_DAT = "servers.dat";
    private static final String META_SIDECAR = "zyrox_servers_meta.json";

    // Standard Minecraft binary NBT tag IDs
    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    private static volatile String sPendingQuickConnectAddress = null;

    public static final class ServerEntry {
        @NonNull public String name;
        @NonNull public String address;
        @Nullable public String iconBase64;
        public boolean acceptTextures;
        public boolean hidden;
        public boolean favorite;
        @NonNull public String group;
        // Transient live-ping result fields (populated only when user checks status)
        public transient long lastPingMs = -1L;
        @Nullable public transient String lastMotd;
        @Nullable public transient String lastPlayers;
        @Nullable public transient String lastVersion;
        public transient boolean lastOnline;

        public ServerEntry(@NonNull String name, @NonNull String address) {
            this.name = name.trim().isEmpty() ? "Minecraft Server" : name.trim();
            this.address = address.trim();
            this.group = "";
        }
    }

    public static final class ParsedHostPort {
        @NonNull public final String host;
        public final int port;

        public ParsedHostPort(@NonNull String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private ServerListManager() {
    }

    /** Queues a one-shot quick-connect server target consumed on the very next game launch. */
    public static void setPendingQuickConnect(@Nullable String address) {
        sPendingQuickConnectAddress = (address != null && !address.trim().isEmpty())
                ? address.trim() : null;
    }

    /** Atomically consumes and clears any pending quick-connect server address. */
    @Nullable
    public static String consumePendingQuickConnect() {
        String addr = sPendingQuickConnectAddress;
        sPendingQuickConnectAddress = null;
        return addr;
    }

    /**
     * Validates and splits a Minecraft server address ({@code host}, {@code host:port}, or
     * {@code [ipv6]:port}) with strict port range bounds ({@code 1..65535}).
     */
    @NonNull
    public static ParsedHostPort parseAndValidateAddress(@Nullable String rawAddress)
            throws IllegalArgumentException {
        if (rawAddress == null) {
            throw new IllegalArgumentException("Server address cannot be empty");
        }
        String trimmed = rawAddress.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new IllegalArgumentException("Server address must be 1–255 characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c < 0x21 || c == '/' || c == '\\' || c == '?' || c == '#') {
                throw new IllegalArgumentException("Server address contains invalid character: '" + c + "'");
            }
        }

        String host;
        int port = DEFAULT_MC_PORT;
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close <= 1) {
                throw new IllegalArgumentException("Unclosed IPv6 bracket in server address");
            }
            host = trimmed.substring(1, close);
            if (close + 1 < trimmed.length()) {
                if (trimmed.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("Invalid characters after IPv6 address");
                }
                port = parseStrictPort(trimmed.substring(close + 2));
            }
        } else {
            int firstColon = trimmed.indexOf(':');
            int lastColon = trimmed.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                host = trimmed.substring(0, firstColon);
                port = parseStrictPort(trimmed.substring(firstColon + 1));
            } else if (firstColon == 0) {
                throw new IllegalArgumentException("Missing hostname before port");
            } else {
                host = trimmed;
            }
        }

        if (host.isEmpty() || host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
            throw new IllegalArgumentException("Invalid server hostname: " + host);
        }
        return new ParsedHostPort(host, port);
    }

    private static int parseStrictPort(@NonNull String portStr) {
        if (portStr.isEmpty() || portStr.length() > 5) {
            throw new IllegalArgumentException("Invalid port: " + portStr);
        }
        int p;
        try {
            p = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be numeric: " + portStr);
        }
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return p;
    }

    /**
     * Reads {@code <gameDir>/servers.dat} and merges favorites/group tags from the Zyrox sidecar.
     */
    @NonNull
    public static List<ServerEntry> loadServers(@NonNull File gameDir) throws IOException {
        File datFile = new File(gameDir, SERVERS_DAT);
        List<ServerEntry> entries = new ArrayList<>();
        if (datFile.isFile() && datFile.length() > 0L) {
            if (datFile.length() > 8L * 1024L * 1024L) {
                throw new IOException("servers.dat exceeds safe 8 MB size limit");
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(datFile)))) {
                byte rootType = in.readByte();
                if (rootType != TAG_COMPOUND) {
                    throw new IOException("Invalid servers.dat root NBT tag: " + rootType);
                }
                in.readUTF(); // root name (typically "")
                readRootCompound(in, entries);
            }
        }

        // Merge non-destructive favorites/group metadata
        Map<String, JsonObject> sidecar = readSidecar(new File(gameDir, META_SIDECAR));
        for (ServerEntry entry : entries) {
            JsonObject meta = sidecar.get(entry.address.toLowerCase(Locale.ROOT));
            if (meta != null) {
                if (meta.has("favorite")) entry.favorite = meta.get("favorite").getAsBoolean();
                if (meta.has("group")) entry.group = meta.get("group").getAsString();
            }
        }
        return entries;
    }

    /**
     * Atomically writes {@code servers.dat} (preserving a {@code .bak} backup) and updates the
     * favorites/group sidecar so vanilla Minecraft reads the exact server list seamlessly.
     */
    public static void saveServers(@NonNull File gameDir, @NonNull List<ServerEntry> servers)
            throws IOException {
        FileUtils.ensureDirectory(gameDir);
        File datFile = new File(gameDir, SERVERS_DAT);
        File bakFile = new File(gameDir, SERVERS_DAT + ".bak");
        File tmpFile = new File(gameDir, SERVERS_DAT + ".tmp");

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile, false)))) {
            // Root TAG_Compound ""
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");

            // TAG_List "servers" of TAG_Compound
            out.writeByte(TAG_LIST);
            out.writeUTF("servers");
            out.writeByte(servers.isEmpty() ? TAG_END : TAG_COMPOUND);
            out.writeInt(servers.size());

            for (ServerEntry s : servers) {
                writeStringTag(out, "name", s.name);
                writeStringTag(out, "ip", s.address);
                if (s.iconBase64 != null && !s.iconBase64.isEmpty()) {
                    writeStringTag(out, "icon", s.iconBase64);
                }
                if (s.acceptTextures) {
                    out.writeByte(TAG_BYTE);
                    out.writeUTF("acceptTextures");
                    out.writeByte(1);
                }
                out.writeByte(TAG_BYTE);
                out.writeUTF("hidden");
                out.writeByte(s.hidden ? 1 : 0);
                out.writeByte(TAG_END); // end server compound
            }

            out.writeByte(TAG_END); // end root compound
            out.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            throw e;
        }

        if (datFile.isFile() && datFile.length() > 0L) {
            try (FileInputStream in = new FileInputStream(datFile);
                 FileOutputStream out = new FileOutputStream(bakFile, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException ignored) {
            }
        }
        if (datFile.exists() && !datFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            throw new IOException("Failed to update servers.dat");
        }
        if (!tmpFile.renameTo(datFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            throw new IOException("Failed to finalize servers.dat");
        }

        // Persist favorites/groups sidecar
        JsonObject sidecarRoot = new JsonObject();
        JsonArray arr = new JsonArray();
        for (ServerEntry s : servers) {
            if (s.favorite || !s.group.isEmpty()) {
                JsonObject m = new JsonObject();
                m.addProperty("ip", s.address);
                m.addProperty("favorite", s.favorite);
                m.addProperty("group", s.group);
                arr.add(m);
            }
        }
        sidecarRoot.add("servers", arr);
        JSONUtils.writeToFileAtomic(new File(gameDir, META_SIDECAR), sidecarRoot, false);
    }

    /**
     * Exports saved servers as clean, human-readable JSON for backup or cross-instance sharing.
     */
    @NonNull
    public static String exportServersJson(@NonNull List<ServerEntry> servers) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "zyrox-servers-v2");
        JsonArray arr = new JsonArray();
        for (ServerEntry s : servers) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name);
            o.addProperty("address", s.address);
            o.addProperty("favorite", s.favorite);
            if (!s.group.isEmpty()) o.addProperty("group", s.group);
            if (s.acceptTextures) o.addProperty("acceptTextures", true);
            arr.add(o);
        }
        root.add("servers", arr);
        return Tools.GLOBAL_GSON.toJson(root);
    }

    /**
     * Validates and parses an exported server list JSON.
     */
    @NonNull
    public static List<ServerEntry> importServersJson(@NonNull String jsonText) throws IOException {
        try {
            JsonObject root = new JsonParser().parse(jsonText).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("servers");
            if (arr == null) return Collections.emptyList();
            List<ServerEntry> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String addr = o.has("address") ? o.get("address").getAsString()
                        : (o.has("ip") ? o.get("ip").getAsString() : "");
                parseAndValidateAddress(addr);
                String name = o.has("name") ? o.get("name").getAsString() : addr;
                ServerEntry entry = new ServerEntry(name, addr);
                if (o.has("favorite")) entry.favorite = o.get("favorite").getAsBoolean();
                if (o.has("group")) entry.group = o.get("group").getAsString();
                if (o.has("acceptTextures")) entry.acceptTextures = o.get("acceptTextures").getAsBoolean();
                out.add(entry);
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid server entry in import: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Invalid server list JSON", e);
        }
    }

    /**
     * Appends the appropriate quick-connect multiplayer client CLI flags for the target Minecraft version.
     */
    public static void appendQuickConnectArgs(@NonNull List<String> clientArgs,
                                              @NonNull String serverAddress,
                                              boolean supportsModernQuickPlay) {
        ParsedHostPort hp = parseAndValidateAddress(serverAddress);
        String formatted = hp.host.contains(":")
                ? ("[" + hp.host + "]:" + hp.port)
                : (hp.host + ":" + hp.port);
        if (supportsModernQuickPlay) {
            clientArgs.add("--quickPlayMultiplayer");
            clientArgs.add(formatted);
        } else {
            clientArgs.add("--server");
            clientArgs.add(hp.host);
            clientArgs.add("--port");
            clientArgs.add(Integer.toString(hp.port));
        }
    }

    private static void writeStringTag(@NonNull DataOutputStream out,
                                       @NonNull String name,
                                       @NonNull String value) throws IOException {
        out.writeByte(TAG_STRING);
        out.writeUTF(name);
        out.writeUTF(value);
    }

    private static void readRootCompound(@NonNull DataInputStream in,
                                         @NonNull List<ServerEntry> out) throws IOException {
        while (true) {
            byte tagType = in.readByte();
            if (tagType == TAG_END) return;
            String name = in.readUTF();
            if (tagType == TAG_LIST && "servers".equals(name)) {
                byte elemType = in.readByte();
                int count = in.readInt();
                if (count < 0 || count > 2048) {
                    throw new IOException("Unreasonable server list count in servers.dat: " + count);
                }
                for (int i = 0; i < count; i++) {
                    if (elemType == TAG_COMPOUND) {
                        ServerEntry s = readServerCompound(in);
                        if (s != null) out.add(s);
                    } else {
                        skipPayload(in, elemType, 1);
                    }
                }
            } else {
                skipPayload(in, tagType, 1);
            }
        }
    }

    @Nullable
    private static ServerEntry readServerCompound(@NonNull DataInputStream in) throws IOException {
        String name = "Minecraft Server";
        String ip = null;
        String icon = null;
        boolean acceptTextures = false;
        boolean hidden = false;

        while (true) {
            byte tagType = in.readByte();
            if (tagType == TAG_END) break;
            String key = in.readUTF();
            if (tagType == TAG_STRING) {
                String val = in.readUTF();
                if ("name".equals(key)) name = val;
                else if ("ip".equals(key)) ip = val;
                else if ("icon".equals(key) && val.length() <= 512 * 1024) icon = val;
            } else if (tagType == TAG_BYTE) {
                byte b = in.readByte();
                if ("acceptTextures".equals(key)) acceptTextures = (b != 0);
                else if ("hidden".equals(key)) hidden = (b != 0);
            } else {
                skipPayload(in, tagType, 2);
            }
        }
        if (ip == null || ip.trim().isEmpty()) return null;
        ServerEntry entry = new ServerEntry(name, ip);
        entry.iconBase64 = icon;
        entry.acceptTextures = acceptTextures;
        entry.hidden = hidden;
        return entry;
    }

    private static void skipPayload(@NonNull DataInputStream in, byte tagType, int depth)
            throws IOException {
        if (depth > 32) throw new IOException("NBT nesting depth limit exceeded");
        switch (tagType) {
            case TAG_END:
                return;
            case TAG_BYTE:
                in.readByte();
                return;
            case TAG_SHORT:
                in.readShort();
                return;
            case TAG_INT:
            case TAG_FLOAT:
                in.readInt();
                return;
            case TAG_LONG:
            case TAG_DOUBLE:
                in.readLong();
                return;
            case TAG_BYTE_ARRAY: {
                int len = in.readInt();
                skipExact(in, len, 4 * 1024 * 1024);
                return;
            }
            case TAG_STRING:
                in.readUTF();
                return;
            case TAG_LIST: {
                byte elem = in.readByte();
                int count = in.readInt();
                if (count < 0 || count > 65536) throw new IOException("Invalid NBT list size");
                for (int i = 0; i < count; i++) skipPayload(in, elem, depth + 1);
                return;
            }
            case TAG_COMPOUND: {
                while (true) {
                    byte child = in.readByte();
                    if (child == TAG_END) return;
                    in.readUTF();
                    skipPayload(in, child, depth + 1);
                }
            }
            case TAG_INT_ARRAY: {
                int len = in.readInt();
                if (len < 0 || len > 1024 * 1024) throw new IOException("Invalid NBT int array");
                skipExact(in, len * 4, 4 * 1024 * 1024);
                return;
            }
            case TAG_LONG_ARRAY: {
                int len = in.readInt();
                if (len < 0 || len > 512 * 1024) throw new IOException("Invalid NBT long array");
                skipExact(in, len * 8, 4 * 1024 * 1024);
                return;
            }
            default:
                throw new IOException("Unknown NBT tag type: " + tagType);
        }
    }

    private static void skipExact(@NonNull DataInputStream in, int bytes, int max) throws IOException {
        if (bytes < 0 || bytes > max) throw new IOException("NBT array exceeds safe limit");
        in.skipBytes(bytes);
    }

    @NonNull
    private static Map<String, JsonObject> readSidecar(@NonNull File sidecarFile) {
        if (!sidecarFile.isFile()) return Collections.emptyMap();
        try (FileInputStream in = new FileInputStream(sidecarFile)) {
            String text = new String(ArchiveSecurity.readBytesBounded(in, 256 * 1024), StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(text).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("servers");
            if (arr == null) return Collections.emptyMap();
            Map<String, JsonObject> map = new HashMap<>();
            for (JsonElement el : arr) {
                if (el.isJsonObject() && el.getAsJsonObject().has("ip")) {
                    map.put(el.getAsJsonObject().get("ip").getAsString().toLowerCase(Locale.ROOT),
                            el.getAsJsonObject());
                }
            }
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
