package git.artdeell.mojo.ai;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.BotAuthMode;
import git.artdeell.mojo.ai.AiFriendModels.BotConnectionState;
import git.artdeell.mojo.ai.AiFriendModels.BotStatusSnapshot;
import git.artdeell.mojo.ai.AiFriendTargetResolver.ResolvedTarget;

/**
 * Persistent NDJSON-over-TCP client speaking to the Zyrox Companion Bot Bridge worker
 * ({@code tools/ai-friend-bridge/zyrox-bot-bridge.js}).
 *
 * <p>Uses raw framed TCP sockets ({@link Socket} with {@code TCP_NODELAY} and bounded 16 KB
 * UTF-8 lines) rather than cleartext HTTP/WebSocket so {@code res/xml/netconfig.xml}
 * ({@code cleartextTrafficPermitted="false"}) remains 100% enforced across the entire APK.</p>
 */
public final class CompanionBotBridgeClient {

    private static final String TAG = "CompanionBotBridge";
    public static final int PROTOCOL_VERSION = 1;
    private static final int CONNECT_TIMEOUT_MS = 4500;
    private static final int MAX_FRAME_BYTES = 16 * 1024;

    /** Listener notified on background bridge events (marshal to main thread in UI layer). */
    public interface BridgeEventListener {
        void onBotStatusChanged(@NonNull BotStatusSnapshot snapshot);
        void onWorldChatReceived(@NonNull String username, @NonNull String message, boolean isWhisper);
        void onActionUpdate(@NonNull BotAction action, @NonNull String status, @NonNull String detail);
        void onBridgeNotice(@NonNull String message);
    }

    private final ExecutorService executor;
    @Nullable private final BridgeEventListener listener;

    private final Object connLock = new Object();
    @Nullable private Socket activeSocket;
    @Nullable private OutputStream activeOut;
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);

    @NonNull private volatile BotStatusSnapshot latestStatus =
            BotStatusSnapshot.disconnected(AiFriendConfigStore.DEFAULT_BOT_USERNAME, "Bridge not connected");

    public CompanionBotBridgeClient(@NonNull ExecutorService executor,
                                    @Nullable BridgeEventListener listener) {
        this.executor = executor;
        this.listener = listener;
    }

    @NonNull
    public BotStatusSnapshot getLatestStatus() {
        return latestStatus;
    }

    public boolean isBridgeSocketOpen() {
        synchronized (connLock) {
            return activeSocket != null && activeSocket.isConnected() && !activeSocket.isClosed();
        }
    }

    public boolean isBotInWorld() {
        return isBridgeSocketOpen() && latestStatus.state == BotConnectionState.CONNECTED;
    }

    /**
     * Performs a lightweight TCP ping against {@code bridgeHost:bridgePort} to verify worker readiness.
     */
    @NonNull
    public static String pingBridgeBlocking(@NonNull String bridgeHost,
                                            int bridgePort,
                                            @Nullable String bridgeToken,
                                            int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(bridgeHost, bridgePort), Math.max(500, timeoutMs));
            socket.setSoTimeout(Math.max(1000, timeoutMs));
            socket.setTcpNoDelay(true);

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = socket.getInputStream();

            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("version", PROTOCOL_VERSION);
            hello.addProperty("client", "ZyroxLauncher");
            if (bridgeToken != null && !bridgeToken.isEmpty()) {
                hello.addProperty("token", bridgeToken);
            }
            writeNdjsonFrame(out, hello);

            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            ping.addProperty("ts", System.currentTimeMillis());
            writeNdjsonFrame(out, ping);

            String line1 = readBoundedLine(in, MAX_FRAME_BYTES);
            if (line1 == null) {
                throw new IOException("Bridge closed connection without responding");
            }
            JsonObject ack = JsonParser.parseString(line1).getAsJsonObject();
            String type = getStr(ack, "type", "");
            if ("error".equals(type)) {
                throw new IOException(getStr(ack, "message", "Bridge authentication failed"));
            }
            String bridgeName = getStr(ack, "bridge", "zyrox-bot-bridge");
            boolean botActive = ack.has("connected") && ack.get("connected").getAsBoolean();
            return "Bridge online (" + bridgeName + ", v" + getInt(ack, "version", 1)
                    + (botActive ? ", bot active" : ", ready to summon") + ")";
        } catch (IOException e) {
            throw new IOException("Cannot reach Companion Bot Bridge at " + bridgeHost + ":" + bridgePort
                    + " (" + e.getMessage() + "). Start `node tools/ai-friend-bridge/zyrox-bot-bridge.js` on Termux or your PC.");
        }
    }

    /**
     * Opens (or refreshes) the persistent TCP control channel to the bridge worker and summons
     * the Mineflayer companion bot into the resolved target world/server.
     */
    public void connectAndSummonBlocking(@NonNull String bridgeHost,
                                         int bridgePort,
                                         @Nullable String bridgeToken,
                                         @NonNull ResolvedTarget target,
                                         @NonNull String botUsername,
                                         @NonNull AiRole role,
                                         @Nullable String ownerUsername,
                                         @NonNull String mcVersion) throws IOException {
        updateStatus(new BotStatusSnapshot(
                BotConnectionState.CONNECTING,
                botUsername,
                target.displaySummary,
                role.defaultAction,
                20.0f, 20, 0, 0, 0, false,
                "Connecting to bridge " + bridgeHost + ":" + bridgePort + "...",
                null, null
        ));

        ensureBridgeSocketConnected(bridgeHost, bridgePort, bridgeToken, botUsername);

        JsonObject summon = new JsonObject();
        summon.addProperty("type", "summon");
        summon.addProperty("targetMode", target.mode.name());
        summon.addProperty("host", target.host);
        summon.addProperty("port", target.port);
        if (target.zyroxRoomCode != null && !target.zyroxRoomCode.isEmpty()) {
            summon.addProperty("zyroxRoomCode", target.zyroxRoomCode);
        }
        summon.addProperty("botUsername", botUsername);
        summon.addProperty("authMode", target.authMode == BotAuthMode.MICROSOFT_DEVICE_CODE ? "microsoft" : "offline");
        summon.addProperty("role", role.name());
        summon.addProperty("defaultAction", role.defaultAction.name());
        summon.addProperty("mcVersion", mcVersion);
        if (ownerUsername != null && !ownerUsername.trim().isEmpty()) {
            summon.addProperty("ownerUsername", ownerUsername.trim());
        }
        sendFrame(summon);
    }

    /** Sends a combined spoken chat line + structured action to the active in-world bot. */
    public boolean sendExecuteTurn(@NonNull String say,
                                   @NonNull BotAction action,
                                   @Nullable String ownerUsername) {
        if (!isBridgeSocketOpen()) return false;
        JsonObject cmd = new JsonObject();
        cmd.addProperty("type", "execute");
        cmd.addProperty("say", say);
        cmd.addProperty("action", action.name());
        if (ownerUsername != null && !ownerUsername.trim().isEmpty()) {
            cmd.addProperty("ownerUsername", ownerUsername.trim());
        }
        try {
            sendFrame(cmd);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed dispatching execute turn to bot bridge: " + e.getMessage());
            return false;
        }
    }

    /** Sends an immediate zero-latency tactical action command (for HUD quick chips). */
    public boolean sendDirectAction(@NonNull BotAction action, @Nullable String ownerUsername) {
        if (!isBridgeSocketOpen()) return false;
        JsonObject cmd = new JsonObject();
        cmd.addProperty("type", "action");
        cmd.addProperty("action", action.name());
        if (ownerUsername != null && !ownerUsername.trim().isEmpty()) {
            cmd.addProperty("ownerUsername", ownerUsername.trim());
        }
        try {
            sendFrame(cmd);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed dispatching direct action to bot bridge: " + e.getMessage());
            return false;
        }
    }

    /** Dismisses the bot from the Minecraft server and closes the control socket cleanly. */
    public void dismissAndClose() {
        synchronized (connLock) {
            if (activeOut != null) {
                try {
                    JsonObject bye = new JsonObject();
                    bye.addProperty("type", "dismiss");
                    bye.addProperty("reason", "user_requested");
                    writeNdjsonFrame(activeOut, bye);
                } catch (IOException ignored) {
                }
            }
            closeSocketLocked();
        }
        updateStatus(BotStatusSnapshot.disconnected(latestStatus.botUsername, "Bot dismissed"));
    }

    private void ensureBridgeSocketConnected(@NonNull String bridgeHost,
                                             int bridgePort,
                                             @Nullable String bridgeToken,
                                             @NonNull String botUsername) throws IOException {
        synchronized (connLock) {
            closeSocketLocked();

            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(bridgeHost, bridgePort), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(0); // Dedicated reader thread blocks on incoming frames
            } catch (IOException connectErr) {
                closeQuietly(socket);
                updateStatus(new BotStatusSnapshot(
                        BotConnectionState.ERROR,
                        botUsername,
                        bridgeHost + ":" + bridgePort,
                        BotAction.IDLE,
                        20.0f, 20, 0, 0, 0, false,
                        "Bridge unreachable at " + bridgeHost + ":" + bridgePort,
                        null, null
                ));
                throw new IOException("Could not connect to Companion Bot Bridge at "
                        + bridgeHost + ":" + bridgePort + " (" + connectErr.getMessage() + ").");
            }

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            final InputStream in = socket.getInputStream();

            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("version", PROTOCOL_VERSION);
            hello.addProperty("client", "ZyroxLauncher");
            if (bridgeToken != null && !bridgeToken.isEmpty()) {
                hello.addProperty("token", bridgeToken);
            }
            writeNdjsonFrame(out, hello);

            activeSocket = socket;
            activeOut = out;
            readerRunning.set(true);

            final Socket boundSocket = socket;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runReaderLoop(boundSocket, in);
                }
            });
        }
    }

    private void runReaderLoop(@NonNull Socket boundSocket, @NonNull InputStream in) {
        try {
            while (readerRunning.get() && !boundSocket.isClosed()) {
                String line = readBoundedLine(in, MAX_FRAME_BYTES);
                if (line == null) {
                    break;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                handleIncomingJsonLine(line);
            }
        } catch (IOException ignored) {
            // Expected on socket close or network drop
        } finally {
            boolean wasCurrent = false;
            synchronized (connLock) {
                if (activeSocket == boundSocket) {
                    closeSocketLocked();
                    wasCurrent = true;
                }
            }
            if (wasCurrent && latestStatus.state != BotConnectionState.DISCONNECTED) {
                updateStatus(BotStatusSnapshot.disconnected(
                        latestStatus.botUsername, "Bridge connection closed"));
            }
        }
    }

    private void handleIncomingJsonLine(@NonNull String line) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) return;
            JsonObject obj = parsed.getAsJsonObject();
            String type = getStr(obj, "type", "");

            if ("bot_state".equals(type)) {
                BotConnectionState state = BotConnectionState.fromWire(getStr(obj, "state", "DISCONNECTED"));
                String botName = getStr(obj, "botUsername", latestStatus.botUsername);
                String endpoint = getStr(obj, "endpoint", latestStatus.endpoint);
                BotAction activeAction = BotAction.fromWireOrAlias(getStr(obj, "activeAction", "IDLE"));
                if (activeAction == null) activeAction = BotAction.IDLE;
                float health = getFloat(obj, "health", 20.0f);
                int food = getInt(obj, "food", 20);
                int x = 0, y = 0, z = 0;
                boolean hasPos = false;
                if (obj.has("position") && obj.get("position").isJsonObject()) {
                    JsonObject pos = obj.getAsJsonObject("position");
                    x = getInt(pos, "x", 0);
                    y = getInt(pos, "y", 0);
                    z = getInt(pos, "z", 0);
                    hasPos = true;
                }
                String detail = getStr(obj, "detail", state.badgeText);
                updateStatus(new BotStatusSnapshot(
                        state, botName, endpoint, activeAction, health, food,
                        x, y, z, hasPos, detail,
                        latestStatus.msaVerificationUri, latestStatus.msaUserCode
                ));
            } else if ("microsoft_auth_prompt".equals(type)) {
                String uri = getStr(obj, "verificationUri", "https://www.microsoft.com/link");
                String code = getStr(obj, "userCode", "");
                String message = getStr(obj, "message",
                        "Sign in at " + uri + " with code " + code + " for the bot's Microsoft account");
                updateStatus(new BotStatusSnapshot(
                        BotConnectionState.AUTH_REQUIRED,
                        latestStatus.botUsername,
                        latestStatus.endpoint,
                        latestStatus.activeAction,
                        latestStatus.health,
                        latestStatus.food,
                        latestStatus.blockX, latestStatus.blockY, latestStatus.blockZ,
                        latestStatus.hasPosition,
                        message,
                        uri,
                        code
                ));
                if (listener != null) {
                    listener.onBridgeNotice(message);
                }
            } else if ("world_chat".equals(type)) {
                String sender = getStr(obj, "username", "");
                String msg = getStr(obj, "message", "");
                boolean whisper = obj.has("isWhisper") && obj.get("isWhisper").getAsBoolean();
                if (!sender.isEmpty() && !msg.isEmpty() && listener != null) {
                    listener.onWorldChatReceived(sender, msg, whisper);
                }
            } else if ("action_update".equals(type)) {
                BotAction action = BotAction.fromWireOrAlias(getStr(obj, "action", "IDLE"));
                if (action == null) action = BotAction.IDLE;
                String status = getStr(obj, "status", "UPDATED");
                String detail = getStr(obj, "detail", "");
                // Update snapshot's activeAction so HUD pills reflect live bot task immediately
                BotStatusSnapshot prev = latestStatus;
                updateStatus(new BotStatusSnapshot(
                        prev.state, prev.botUsername, prev.endpoint, action,
                        prev.health, prev.food, prev.blockX, prev.blockY, prev.blockZ,
                        prev.hasPosition, detail.isEmpty() ? prev.detail : detail,
                        prev.msaVerificationUri, prev.msaUserCode
                ));
                if (listener != null) {
                    listener.onActionUpdate(action, status, detail);
                }
            } else if ("error".equals(type)) {
                String errMsg = getStr(obj, "message", "Bridge error");
                updateStatus(new BotStatusSnapshot(
                        BotConnectionState.ERROR,
                        latestStatus.botUsername,
                        latestStatus.endpoint,
                        BotAction.IDLE,
                        latestStatus.health, latestStatus.food,
                        0, 0, 0, false,
                        errMsg, null, null
                ));
                if (listener != null) {
                    listener.onBridgeNotice("Bridge error: " + errMsg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Malformed NDJSON frame from bridge ignored");
        }
    }

    private void sendFrame(@NonNull JsonObject frame) throws IOException {
        synchronized (connLock) {
            if (activeOut == null || activeSocket == null || activeSocket.isClosed()) {
                throw new IOException("Bridge socket is not connected");
            }
            writeNdjsonFrame(activeOut, frame);
        }
    }

    private void closeSocketLocked() {
        readerRunning.set(false);
        closeQuietly(activeOut);
        closeQuietly(activeSocket);
        activeOut = null;
        activeSocket = null;
    }

    private void updateStatus(@NonNull BotStatusSnapshot next) {
        latestStatus = next;
        if (listener != null) {
            listener.onBotStatusChanged(next);
        }
    }

    private static void writeNdjsonFrame(@NonNull OutputStream out, @NonNull JsonObject obj) throws IOException {
        byte[] bytes = (obj.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
    }

    @Nullable
    static String readBoundedLine(@NonNull InputStream in, int maxBytes) throws IOException {
        byte[] buf = new byte[Math.min(maxBytes, 2048)];
        int len = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return len == 0 ? null : new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                if (len > 0 && buf[len - 1] == '\r') {
                    len--;
                }
                return new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            if (len < maxBytes) {
                if (len == buf.length) {
                    int nextCap = Math.min(maxBytes, buf.length * 2);
                    byte[] grown = new byte[nextCap];
                    System.arraycopy(buf, 0, grown, 0, len);
                    buf = grown;
                }
                buf[len++] = (byte) b;
            }
        }
    }

    @NonNull
    private static String getStr(@NonNull JsonObject o, @NonNull String key, @NonNull String def) {
        if (!o.has(key)) return def;
        JsonElement el = o.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : def;
    }

    private static int getInt(@NonNull JsonObject o, @NonNull String key, int def) {
        if (!o.has(key)) return def;
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    private static float getFloat(@NonNull JsonObject o, @NonNull String key, float def) {
        if (!o.has(key)) return def;
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsFloat();
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    private static void closeQuietly(@Nullable Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
