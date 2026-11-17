package git.artdeell.mojo.servers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * User-triggered Minecraft Java Edition Server List Ping (SLP 1.7+) client with bounded connect/read
 * timeouts, strict packet/JSON size limits, and safe MOTD text extraction.
 *
 * <p>Never polls servers silently in the background; executes only when explicitly requested by the user.
 */
public final class ServerPinger {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_STATUS_PACKET_BYTES = 256 * 1024;
    // Generic SLP status ping protocol version (-1 reports server's real version without client bias)
    private static final int SLP_PROTOCOL_QUERY = -1;

    public static final class PingResult {
        public final boolean online;
        public final long latencyMs;
        @NonNull public final String motd;
        public final int onlinePlayers;
        public final int maxPlayers;
        @NonNull public final String versionName;
        @Nullable public final String faviconBase64;
        @Nullable public final String errorMessage;

        public PingResult(boolean online,
                          long latencyMs,
                          @NonNull String motd,
                          int onlinePlayers,
                          int maxPlayers,
                          @NonNull String versionName,
                          @Nullable String faviconBase64,
                          @Nullable String errorMessage) {
            this.online = online;
            this.latencyMs = latencyMs;
            this.motd = motd;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.versionName = versionName;
            this.faviconBase64 = faviconBase64;
            this.errorMessage = errorMessage;
        }
    }

    private ServerPinger() {
    }

    /**
     * Performs a single bounded status + latency ping against {@code rawAddress}.
     */
    @NonNull
    public static PingResult ping(@NonNull String rawAddress) {
        ServerListManager.ParsedHostPort target;
        try {
            target = ServerListManager.parseAndValidateAddress(rawAddress);
        } catch (IllegalArgumentException e) {
            return offline(e.getMessage());
        }

        long connectStart = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
            long tcpRttMs = Math.max(1L, (System.nanoTime() - connectStart) / 1_000_000L);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1. Handshake packet (ID 0x00, next state = 1 for Status)
            ByteArrayOutputStream hsBody = new ByteArrayOutputStream(64);
            DataOutputStream hsOut = new DataOutputStream(hsBody);
            writeVarInt(hsOut, 0x00);
            writeVarInt(hsOut, SLP_PROTOCOL_QUERY);
            byte[] hostBytes = target.host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hsOut, hostBytes.length);
            hsOut.write(hostBytes);
            hsOut.writeShort(target.port);
            writeVarInt(hsOut, 1); // status state
            byte[] hsBytes = hsBody.toByteArray();
            writeVarInt(out, hsBytes.length);
            out.write(hsBytes);

            // 2. Status request packet (length=1, ID=0x00)
            writeVarInt(out, 1);
            writeVarInt(out, 0x00);
            out.flush();

            // 3. Read status response packet (bounded)
            int packetLen = readVarInt(in);
            if (packetLen <= 0 || packetLen > MAX_STATUS_PACKET_BYTES) {
                return offline("Unexpected status response length");
            }
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return offline("Unexpected status packet ID: " + packetId);
            }
            int jsonLen = readVarInt(in);
            if (jsonLen <= 0 || jsonLen > MAX_STATUS_PACKET_BYTES) {
                return offline("Status JSON exceeds safe size limit");
            }
            byte[] jsonBytes = new byte[jsonLen];
            in.readFully(jsonBytes);
            String jsonText = new String(jsonBytes, StandardCharsets.UTF_8);

            // 4. Optional ping/pong packet (ID 0x01) for exact round-trip latency
            long measuredMs = tcpRttMs;
            try {
                long pingSentNs = System.nanoTime();
                long payload = System.currentTimeMillis();
                ByteArrayOutputStream pingBuf = new ByteArrayOutputStream(16);
                DataOutputStream pingOut = new DataOutputStream(pingBuf);
                writeVarInt(pingOut, 0x01);
                pingOut.writeLong(payload);
                byte[] pBytes = pingBuf.toByteArray();
                writeVarInt(out, pBytes.length);
                out.write(pBytes);
                out.flush();

                int pongLen = readVarInt(in);
                if (pongLen == 9) {
                    int pongId = readVarInt(in);
                    long echoed = in.readLong();
                    if (pongId == 0x01 && echoed == payload) {
                        measuredMs = Math.max(1L, (System.nanoTime() - pingSentNs) / 1_000_000L);
                    }
                }
            } catch (IOException ignored) {
                // Keep measured TCP RTT if server closes socket immediately after status response
            }

            return parseStatusJson(jsonText, measuredMs);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return offline(msg);
        }
    }

    /**
     * Safely parses a Minecraft Server List Ping JSON response without formatting injection.
     */
    @NonNull
    public static PingResult parseStatusJson(@NonNull String jsonText, long latencyMs) {
        try {
            JsonObject root = new JsonParser().parse(jsonText).getAsJsonObject();
            String versionName = "Unknown";
            if (root.has("version") && root.get("version").isJsonObject()) {
                JsonObject v = root.getAsJsonObject("version");
                if (v.has("name") && v.get("name").isJsonPrimitive()) {
                    versionName = stripSectionCodes(v.get("name").getAsString());
                }
            }
            int online = -1;
            int max = -1;
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject p = root.getAsJsonObject("players");
                if (p.has("online") && p.get("online").isJsonPrimitive()) online = p.get("online").getAsInt();
                if (p.has("max") && p.get("max").isJsonPrimitive()) max = p.get("max").getAsInt();
            }
            String motd = "";
            if (root.has("description")) {
                StringBuilder sb = new StringBuilder();
                flattenChatComponent(root.get("description"), sb, 0);
                motd = stripSectionCodes(sb.toString()).replaceAll("\\s+", " ").trim();
            }
            String favicon = null;
            if (root.has("favicon") && root.get("favicon").isJsonPrimitive()) {
                String rawIcon = root.get("favicon").getAsString();
                String prefix = "data:image/png;base64,";
                if (rawIcon.startsWith(prefix) && rawIcon.length() <= 192 * 1024) {
                    favicon = rawIcon.substring(prefix.length()).replace("\n", "").trim();
                }
            }
            return new PingResult(true, latencyMs, motd, online, max, versionName, favicon, null);
        } catch (Exception e) {
            return offline("Malformed server status response");
        }
    }

    private static void flattenChatComponent(@Nullable JsonElement el,
                                             @NonNull StringBuilder out,
                                             int depth) {
        if (el == null || el.isJsonNull() || depth > 12 || out.length() > 512) return;
        if (el.isJsonPrimitive()) {
            out.append(el.getAsString());
            return;
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement child : arr) {
                flattenChatComponent(child, out, depth + 1);
            }
            return;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
                out.append(obj.get("text").getAsString());
            }
            if (obj.has("extra") && obj.get("extra").isJsonArray()) {
                flattenChatComponent(obj.get("extra"), out, depth + 1);
            }
        }
    }

    @NonNull
    private static String stripSectionCodes(@NonNull String input) {
        return input.replaceAll("§[0-9A-FK-ORa-fk-or]", "").trim();
    }

    @NonNull
    private static PingResult offline(@NonNull String reason) {
        return new PingResult(false, -1L, "", -1, -1, "", null, reason);
    }

    static void writeVarInt(@NonNull DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(@NonNull DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt exceeds 5 bytes");
            }
        } while ((read & 0x80) != 0);
        return result;
    }
}
