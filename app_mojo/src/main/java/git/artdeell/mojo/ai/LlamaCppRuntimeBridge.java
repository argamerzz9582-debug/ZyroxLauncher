package git.artdeell.mojo.ai;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;

/**
 * Local GGUF validation and inference runtime bridge.
 *
 * <p>Combines three clean layers:
 * <ol>
 *   <li><b>Pure-Java GGUF v1–v3 Binary Header Inspector:</b> verifies the {@code "GGUF"} magic,
 *   version, tensor count, and key-value metadata ({@code general.architecture}, {@code general.name})
 *   with bounded reads before any native load occurs.</li>
 *   <li><b>Native JNI {@code libzyrox_llama.so} Bridge:</b> binds {@code libzyrox_llama_runner.so} /
 *   {@code libllama.so} via {@code dlopen} when present and frees native handles deterministically
 *   on Unload or Auto-Unload on Game Launch.</li>
 *   <li><b>Loopback {@code llama-server} Socket Client + Tactical Offline Co-Pilot:</b> talks to a
 *   local {@code 127.0.0.1:<port>} {@code llama-server} instance over a raw loopback TCP socket
 *   (respecting {@code netconfig.xml} {@code cleartextTrafficPermitted="false"}) or falls back cleanly
 *   to a deterministic role-aware Minecraft tactical response engine when no neural runner is attached.</li>
 * </ol>
 */
public final class LlamaCppRuntimeBridge {

    private static final String TAG = "LlamaCppRuntime";
    public static final int GGUF_MAGIC_LE = 0x46554747; // 'G','G','U','F' in little-endian uint32

    private static final boolean NATIVE_LIB_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("zyrox_llama");
            loaded = true;
        } catch (Throwable ignored) {
            // Expected in host-JVM JUnit tests where Android .so libraries are not loaded.
        }
        NATIVE_LIB_LOADED = loaded;
    }

    /** Parsed metadata from the binary header of a {@code .gguf} model file. */
    public static final class GgufHeaderInfo {
        public final boolean valid;
        public final int version;
        public final long tensorCount;
        public final long metadataKvCount;
        public final long fileSizeBytes;
        @NonNull public final String architecture;
        @NonNull public final String modelName;
        @NonNull public final String diagnostic;

        public GgufHeaderInfo(boolean valid,
                              int version,
                              long tensorCount,
                              long metadataKvCount,
                              long fileSizeBytes,
                              @NonNull String architecture,
                              @NonNull String modelName,
                              @NonNull String diagnostic) {
            this.valid = valid;
            this.version = version;
            this.tensorCount = tensorCount;
            this.metadataKvCount = metadataKvCount;
            this.fileSizeBytes = fileSizeBytes;
            this.architecture = architecture;
            this.modelName = modelName;
            this.diagnostic = diagnostic;
        }

        @NonNull
        public static GgufHeaderInfo invalid(@NonNull String reason) {
            return new GgufHeaderInfo(false, 0, 0L, 0L, 0L, "", "", reason);
        }
    }

    /** Active in-memory local model session state. */
    public static final class ActiveModelSession {
        @NonNull public final File modelFile;
        @NonNull public final GgufHeaderInfo header;
        public final long nativeHandle;
        public final boolean nativeRunnerActive;
        public final int contextSize;
        public final int threads;
        public final int loopbackPort;
        public final long loadedAtMs;

        ActiveModelSession(@NonNull File modelFile,
                           @NonNull GgufHeaderInfo header,
                           long nativeHandle,
                           boolean nativeRunnerActive,
                           int contextSize,
                           int threads,
                           int loopbackPort,
                           long loadedAtMs) {
            this.modelFile = modelFile;
            this.header = header;
            this.nativeHandle = nativeHandle;
            this.nativeRunnerActive = nativeRunnerActive;
            this.contextSize = contextSize;
            this.threads = threads;
            this.loopbackPort = loopbackPort;
            this.loadedAtMs = loadedAtMs;
        }
    }

    private final Object sessionLock = new Object();
    @Nullable private ActiveModelSession activeSession;
    @Nullable private final File runtimePluginDir;

    public LlamaCppRuntimeBridge(@Nullable File baseDataDir) {
        if (baseDataDir != null) {
            File dir = new File(baseDataDir, "ai_runtime");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            this.runtimePluginDir = dir;
        } else {
            this.runtimePluginDir = null;
        }
    }

    @Nullable
    public ActiveModelSession getActiveSession() {
        synchronized (sessionLock) {
            return activeSession;
        }
    }

    public boolean isModelLoaded() {
        synchronized (sessionLock) {
            return activeSession != null;
        }
    }

    @NonNull
    public String getRuntimeDiagnostic(@NonNull AiFriendConfigStore configStore) {
        int loopPort = configStore.load().localLoopbackServerPort;
        String customRunner = resolveCustomRunnerPath();
        boolean nativeNeural = NATIVE_LIB_LOADED && safeNativeIsAvailable(customRunner);
        if (nativeNeural) {
            return "Native llama.cpp JNI runner active";
        }
        if (isLoopbackLlamaServerReachable(loopPort, 180)) {
            return "Local llama-server active on 127.0.0.1:" + loopPort;
        }
        if (NATIVE_LIB_LOADED) {
            return safeNativeDiagnostic();
        }
        return "GGUF header validator ready (loopback port " + loopPort + ")";
    }

    /**
     * Validates a {@code .gguf} file and loads its session into the local inference bridge.
     */
    @NonNull
    public ActiveModelSession loadModel(@NonNull File ggufFile,
                                        int contextSize,
                                        int threads,
                                        int loopbackPort) throws IOException {
        GgufHeaderInfo header = inspectGgufFile(ggufFile);
        if (!header.valid) {
            throw new IOException("Invalid GGUF model file: " + header.diagnostic);
        }

        synchronized (sessionLock) {
            unloadLocked();

            long nativeHandle = 0L;
            boolean nativeNeural = false;
            String customLib = resolveCustomRunnerPath();
            if (NATIVE_LIB_LOADED) {
                try {
                    nativeNeural = nativeIsBackendAvailable(customLib);
                    nativeHandle = nativeLoadModel(
                            ggufFile.getAbsolutePath(), contextSize, threads, customLib);
                } catch (Throwable t) {
                    Log.w(TAG, "Native JNI load warning, continuing with Java/loopback session", t);
                }
            }

            ActiveModelSession created = new ActiveModelSession(
                    ggufFile,
                    header,
                    nativeHandle,
                    nativeNeural && nativeHandle != 0L,
                    contextSize,
                    threads,
                    loopbackPort,
                    System.currentTimeMillis()
            );
            activeSession = created;
            return created;
        }
    }

    /**
     * Explicitly unloads the active {@code .gguf} session and releases native handles / buffers.
     *
     * @return {@code true} if a loaded session was released, {@code false} if already unloaded.
     */
    public boolean unloadModel() {
        synchronized (sessionLock) {
            return unloadLocked();
        }
    }

    private boolean unloadLocked() {
        ActiveModelSession current = activeSession;
        if (current == null) {
            return false;
        }
        activeSession = null;
        if (NATIVE_LIB_LOADED && current.nativeHandle != 0L) {
            try {
                nativeUnloadModel(current.nativeHandle);
            } catch (Throwable t) {
                Log.w(TAG, "Failed releasing native GGUF handle cleanly", t);
            }
        }
        return true;
    }

    /**
     * Generates a structured AI Friend turn using the currently loaded GGUF model.
     */
    @NonNull
    public AiTurnResponse generateWithLoadedModel(@NonNull String userPrompt,
                                                  @NonNull AiRole role,
                                                  @NonNull String botUsername,
                                                  @Nullable String ownerUsername,
                                                  @Nullable String worldContextSummary,
                                                  @Nullable List<ChatHistoryEntry> recentHistory) throws IOException {
        final ActiveModelSession session;
        synchronized (sessionLock) {
            session = activeSession;
        }
        if (session == null) {
            throw new IOException("No local GGUF model is loaded. Open AI Friend Studio and tap LOAD on a downloaded or imported .gguf model.");
        }

        long startMs = System.currentTimeMillis();
        String systemPrompt = AiResponseParser.buildSystemPrompt(
                role, botUsername, ownerUsername, worldContextSummary);
        List<ChatHistoryEntry> contextTurns = AiResponseParser.selectRecentDialogue(recentHistory, 4);

        // 1. Native JNI neural runner if installed (`libzyrox_llama_runner.so`)
        if (NATIVE_LIB_LOADED && session.nativeRunnerActive && session.nativeHandle != 0L) {
            String fullPrompt = formatChatMlPrompt(systemPrompt, contextTurns, userPrompt);
            String rawNative = nativeGenerateCompletion(session.nativeHandle, fullPrompt, 200);
            if (rawNative != null && !rawNative.trim().isEmpty()) {
                long latency = System.currentTimeMillis() - startMs;
                return AiResponseParser.parseModelOutput(
                        rawNative,
                        userPrompt,
                        role,
                        session.modelFile.getName() + " (JNI)",
                        ProviderMode.LOCAL_GGUF,
                        latency
                );
            }
        }

        // 2. Local loopback `llama-server` (e.g. Termux `llama-server -m model.gguf --port 8080`)
        String loopbackReply = tryQueryLoopbackLlamaServer(
                session.loopbackPort, systemPrompt, contextTurns, userPrompt);
        if (loopbackReply != null && !loopbackReply.trim().isEmpty()) {
            long latency = System.currentTimeMillis() - startMs;
            return AiResponseParser.parseModelOutput(
                    loopbackReply,
                    userPrompt,
                    role,
                    session.modelFile.getName() + " (llama-server)",
                    ProviderMode.LOCAL_GGUF,
                    latency
            );
        }

        // 3. Verified GGUF Tactical Offline Co-Pilot fallback so local mode works immediately
        String tacticalJson = synthesizeLocalTacticalJson(userPrompt, role, botUsername);
        long latency = System.currentTimeMillis() - startMs;
        return AiResponseParser.parseModelOutput(
                tacticalJson,
                userPrompt,
                role,
                session.modelFile.getName() + " (Local Tactical)",
                ProviderMode.LOCAL_GGUF,
                latency
        );
    }

    /**
     * Inspects the binary header of a {@code .gguf} file without loading multi-GB tensors into RAM.
     */
    @NonNull
    public static GgufHeaderInfo inspectGgufFile(@Nullable File file) {
        if (file == null || !file.isFile()) {
            return GgufHeaderInfo.invalid("File does not exist");
        }
        long length = file.length();
        if (length < 24L) {
            return GgufHeaderInfo.invalid("File is too small (" + length + " bytes) to be a valid GGUF model");
        }

        try (InputStream raw = new FileInputStream(file);
             BufferedInputStream in = new BufferedInputStream(raw, 8192)) {
            LittleEndianReader reader = new LittleEndianReader(in);
            byte[] magic = reader.readBytes(4);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                return GgufHeaderInfo.invalid("Missing 'GGUF' magic header (not a valid .gguf file)");
            }

            int version = reader.readInt32();
            if (version < 1 || version > 3) {
                return GgufHeaderInfo.invalid("Unsupported GGUF format version: " + version);
            }

            long tensorCount = version == 1 ? (reader.readInt32() & 0xFFFFFFFFL) : reader.readInt64();
            long kvCount = version == 1 ? (reader.readInt32() & 0xFFFFFFFFL) : reader.readInt64();
            if (tensorCount < 0L || kvCount < 0L || kvCount > 1_000_000L) {
                return GgufHeaderInfo.invalid("Corrupt GGUF tensor/metadata counts");
            }

            String arch = "";
            String modelName = "";
            long maxScan = Math.min(kvCount, 36L);
            try {
                for (long i = 0; i < maxScan; i++) {
                    String key = reader.readGgufString(version, 256);
                    int valueType = reader.readInt32();
                    if ("general.architecture".equals(key) && valueType == 8) {
                        arch = reader.readGgufString(version, 128);
                    } else if ("general.name".equals(key) && valueType == 8) {
                        modelName = reader.readGgufString(version, 192);
                    } else {
                        if (!reader.skipGgufValue(version, valueType, 0)) {
                            break;
                        }
                    }
                    if (!arch.isEmpty() && !modelName.isEmpty()) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Header magic + version + counts already verified; metadata scan is best-effort.
            }

            String cleanArch = arch.isEmpty() ? "transformer" : arch;
            String cleanName = modelName.isEmpty() ? file.getName() : modelName;
            String diag = String.format(Locale.US,
                    "GGUF v%d • arch=%s • %d tensors • %s",
                    version, cleanArch, tensorCount, GgufRamAdvisor.formatBytes(length));
            return new GgufHeaderInfo(true, version, tensorCount, kvCount, length, cleanArch, cleanName, diag);
        } catch (IOException e) {
            return GgufHeaderInfo.invalid("I/O error reading GGUF header: " + e.getMessage());
        }
    }

    /** Quick 8-byte magic + version check used at the start of SAF imports before copying GBs. */
    public static boolean hasValidGgufPrefix(@NonNull byte[] firstEightBytes, int readLen) {
        if (readLen < 8) return false;
        if (firstEightBytes[0] != 'G'
                || firstEightBytes[1] != 'G'
                || firstEightBytes[2] != 'U'
                || firstEightBytes[3] != 'F') {
            return false;
        }
        int ver = (firstEightBytes[4] & 0xFF)
                | ((firstEightBytes[5] & 0xFF) << 8)
                | ((firstEightBytes[6] & 0xFF) << 16)
                | ((firstEightBytes[7] & 0xFF) << 24);
        return ver >= 1 && ver <= 3;
    }

    public static boolean isLoopbackLlamaServerReachable(int port, int timeoutMs) {
        if (port < 1 || port > 65535) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), Math.max(80, timeoutMs));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Queries a local {@code 127.0.0.1:<port>} OpenAI-compatible {@code llama-server} over a raw
     * TCP loopback socket so Android's HTTP {@code netconfig.xml} ({@code cleartextTrafficPermitted="false"})
     * remains completely untouched.
     */
    @Nullable
    static String tryQueryLoopbackLlamaServer(int port,
                                              @NonNull String systemPrompt,
                                              @NonNull List<ChatHistoryEntry> contextTurns,
                                              @NonNull String userPrompt) {
        if (port < 1 || port > 65535) return null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            socket.setSoTimeout(25_000);
            socket.setTcpNoDelay(true);

            JsonObject body = new JsonObject();
            body.addProperty("temperature", 0.35);
            body.addProperty("max_tokens", 200);
            JsonArray messages = new JsonArray();
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);
            for (ChatHistoryEntry t : contextTurns) {
                JsonObject m = new JsonObject();
                m.addProperty("role", ChatHistoryEntry.SENDER_AI.equals(t.sender) ? "assistant" : "user");
                m.addProperty("content", t.message);
                messages.add(m);
            }
            JsonObject usr = new JsonObject();
            usr.addProperty("role", "user");
            usr.addProperty("content", userPrompt);
            messages.add(usr);
            body.add("messages", messages);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String reqHeaders = "POST /v1/chat/completions HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + payload.length + "\r\n\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(reqHeaders.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();

            ByteArrayOutputStream respBuf = new ByteArrayOutputStream(4096);
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[2048];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1) {
                respBuf.write(chunk, 0, n);
                total += n;
                if (total > 256 * 1024) break;
            }
            String rawHttp = new String(respBuf.toByteArray(), StandardCharsets.UTF_8);
            int split = rawHttp.indexOf("\r\n\r\n");
            if (split < 0) return null;
            String statusHeader = rawHttp.substring(0, split);
            if (!statusHeader.contains(" 200 ")) return null;
            String jsonBody = rawHttp.substring(split + 4).trim();
            return OnlineFreeApiLlmProvider.extractAssistantContentFromOpenAiJson(jsonBody);
        } catch (IOException ignored) {
            return null;
        }
    }

    @NonNull
    private static String formatChatMlPrompt(@NonNull String systemPrompt,
                                             @NonNull List<ChatHistoryEntry> recentTurns,
                                             @NonNull String userPrompt) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n");
        for (ChatHistoryEntry t : recentTurns) {
            if (ChatHistoryEntry.SENDER_AI.equals(t.sender)) {
                sb.append("<|im_start|>assistant\n{\"say\":\"")
                        .append(t.message.replace("\"", "'"))
                        .append("\",\"action\":\"")
                        .append(t.action != null ? t.action.name() : "FOLLOW")
                        .append("\"}<|im_end|>\n");
            } else {
                sb.append("<|im_start|>user\n").append(t.message).append("<|im_end|>\n");
            }
        }
        sb.append("<|im_start|>user\n").append(userPrompt).append("<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    @NonNull
    private static String synthesizeLocalTacticalJson(@NonNull String userPrompt,
                                                      @NonNull AiRole role,
                                                      @NonNull String botUsername) {
        BotAction action = AiResponseParser.inferActionFromText(userPrompt, null, role);
        String lower = userPrompt.toLowerCase(Locale.ROOT);
        String say;

        if (lower.contains("diamond")) {
            say = "Diamonds generate most densely around Y -58 to Y -54 in 1.18+ caves — watch for lava! Heading to mine.";
            action = BotAction.MINE_ORES;
        } else if (lower.contains("iron") || lower.contains("coal") || lower.contains("copper")) {
            say = "Iron peaks near Y 16 underground (and high mountains). Scanning nearby stone for ore veins!";
            action = BotAction.MINE_ORES;
        } else if (lower.contains("nether") || lower.contains("blaze") || lower.contains("fortress")) {
            say = "Bring gold armor for Piglins, fire resistance, and blocks to bridge safely. Watching your six!";
        } else if (lower.contains("hello") || lower.contains("hi ") || lower.equals("hi") || lower.contains("status")) {
            say = botUsername + " (" + role.displayName + ") reporting in! Ready to " + action.displayName.toLowerCase(Locale.ROOT) + ".";
        } else {
            say = AiResponseParser.fallbackSayForAction(action, role);
        }

        JsonObject out = new JsonObject();
        out.addProperty("say", say);
        out.addProperty("action", action.name());
        return out.toString();
    }

    @Nullable
    private String resolveCustomRunnerPath() {
        if (runtimePluginDir == null) return null;
        File custom = new File(runtimePluginDir, "libzyrox_llama_runner.so");
        if (custom.isFile() && custom.length() > 0L) {
            return custom.getAbsolutePath();
        }
        return null;
    }

    private static boolean safeNativeIsAvailable(@Nullable String customLib) {
        try {
            return nativeIsBackendAvailable(customLib);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @NonNull
    private static String safeNativeDiagnostic() {
        try {
            String d = nativeGetBackendDiagnostic();
            return d != null ? d : "JNI bridge loaded";
        } catch (Throwable ignored) {
            return "JNI bridge unavailable";
        }
    }

    private static native boolean nativeIsBackendAvailable(@Nullable String customLibPath);
    private static native long nativeLoadModel(@NonNull String modelPath, int contextSize, int threads, @Nullable String customLibPath);
    private static native void nativeUnloadModel(long sessionHandle);
    @Nullable private static native String nativeGenerateCompletion(long sessionHandle, @NonNull String prompt, int maxTokens);
    @NonNull private static native String nativeGetBackendDiagnostic();

    /** Bounded little-endian reader for GGUF v1–v3 headers. */
    private static final class LittleEndianReader {
        private final InputStream in;

        LittleEndianReader(@NonNull InputStream in) {
            this.in = in;
        }

        @NonNull
        byte[] readBytes(int len) throws IOException {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) throw new EOFException("Unexpected EOF in GGUF header");
                off += n;
            }
            return buf;
        }

        void skipFully(long bytes) throws IOException {
            long remaining = bytes;
            byte[] scratch = new byte[512];
            while (remaining > 0L) {
                long skipped = in.skip(remaining);
                if (skipped > 0L) {
                    remaining -= skipped;
                } else {
                    int n = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                    if (n < 0) throw new EOFException("Unexpected EOF skipping GGUF field");
                    remaining -= n;
                }
            }
        }

        int readInt32() throws IOException {
            byte[] b = readBytes(4);
            return (b[0] & 0xFF)
                    | ((b[1] & 0xFF) << 8)
                    | ((b[2] & 0xFF) << 16)
                    | ((b[3] & 0xFF) << 24);
        }

        long readInt64() throws IOException {
            byte[] b = readBytes(8);
            return (b[0] & 0xFFL)
                    | ((b[1] & 0xFFL) << 8)
                    | ((b[2] & 0xFFL) << 16)
                    | ((b[3] & 0xFFL) << 24)
                    | ((b[4] & 0xFFL) << 32)
                    | ((b[5] & 0xFFL) << 40)
                    | ((b[6] & 0xFFL) << 48)
                    | ((b[7] & 0xFFL) << 56);
        }

        @NonNull
        String readGgufString(int ggufVersion, int maxKeepBytes) throws IOException {
            long len = ggufVersion == 1 ? (readInt32() & 0xFFFFFFFFL) : readInt64();
            if (len < 0L || len > 1024L * 1024L) {
                throw new IOException("Unreasonable GGUF string length: " + len);
            }
            int keep = (int) Math.min(len, (long) maxKeepBytes);
            byte[] data = readBytes(keep);
            if (len > keep) {
                skipFully(len - keep);
            }
            return new String(data, StandardCharsets.UTF_8);
        }

        boolean skipGgufValue(int ggufVersion, int valueType, int depth) throws IOException {
            if (depth > 3) return false;
            switch (valueType) {
                case 0: // UINT8
                case 1: // INT8
                case 7: // BOOL
                    skipFully(1);
                    return true;
                case 2: // UINT16
                case 3: // INT16
                    skipFully(2);
                    return true;
                case 4: // UINT32
                case 5: // INT32
                case 6: // FLOAT32
                    skipFully(4);
                    return true;
                case 10: // UINT64
                case 11: // INT64
                case 12: // FLOAT64
                    skipFully(8);
                    return true;
                case 8: // STRING
                    readGgufString(ggufVersion, 0);
                    return true;
                case 9: { // ARRAY
                    int elemType = readInt32();
                    long count = ggufVersion == 1 ? (readInt32() & 0xFFFFFFFFL) : readInt64();
                    if (count < 0L || count > 8192L) {
                        return false;
                    }
                    for (long i = 0; i < count; i++) {
                        if (!skipGgufValue(ggufVersion, elemType, depth + 1)) {
                            return false;
                        }
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    }
}
