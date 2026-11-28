package git.artdeell.mojo.ai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.BotAuthMode;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode;
import git.artdeell.mojo.authenticator.accounts.AccountTokenCipher;
import git.artdeell.mojo.utils.JSONUtils;

/**
 * Atomic cross-process configuration store for Zyrox AI Friend.
 *
 * <p>Backed by {@code <DIR_DATA>/ai_friend/ai_friend_config.json} written via
 * {@link JSONUtils#writeToFileAtomic(File, Object)} so both {@code :launcher} and {@code :game}
 * processes observe consistent settings without partial reads, and API keys / bridge auth tokens
 * are encrypted at rest via {@link AccountTokenCipher}.</p>
 */
public final class AiFriendConfigStore {

    private static final String TAG = "AiFriendConfigStore";
    public static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_ONLINE_MODEL = "meta-llama/llama-3.3-70b-instruct:free";
    public static final String DEFAULT_BOT_USERNAME = "ZyroxBuddy";
    public static final String DEFAULT_BRIDGE_HOST = "127.0.0.1";
    public static final int DEFAULT_BRIDGE_PORT = 25580;
    public static final int DEFAULT_LOCAL_SERVER_PORT = 8080;
    public static final int DEFAULT_GGUF_CONTEXT_SIZE = 2048;

    /** Curated OpenRouter {@code :free} models tried in priority order on HTTP 429 / 5xx fallback. */
    public static final List<String> CURATED_FREE_MODELS = Collections.unmodifiableList(Arrays.asList(
            "meta-llama/llama-3.3-70b-instruct:free",
            "qwen/qwen-2.5-72b-instruct:free",
            "google/gemma-3-27b-it:free",
            "mistralai/mistral-small-3.1-24b-instruct:free",
            "deepseek/deepseek-r1:free",
            "openrouter/free"
    ));

    /** Serializable JSON DTO persisted on disk (secrets stored only in encrypted fields). */
    public static final class ConfigData {
        public int schemaVersion = 2;
        public String providerMode = ProviderMode.FREE_ANONYMOUS.name();

        // Mode 1: Free Anonymous Internet Router settings
        public String anonymousRoutingPolicy = AiProviderCatalog.POLICY_AUTO_CASCADE;
        public List<String> enabledAnonymousProviders =
                AiProviderCatalog.getDefaultEnabledAnonymousProviderIds();
        public boolean anonymousAllowCrossProviderFallback = true;
        public String customFreeRouterUrl = "";
        public String customFreeRouterModel = "auto";

        // Mode 2: API Key (Cloud & Self-Hosted Gateways) settings
        public String onlinePresetId = "openrouter";
        public String baseApiUrl = DEFAULT_OPENROUTER_BASE_URL;
        public String encryptedApiKey = AccountTokenCipher.NO_TOKEN;
        public String selectedOnlineModel = DEFAULT_ONLINE_MODEL;
        public List<String> fallbackOnlineModels = new ArrayList<>(CURATED_FREE_MODELS);
        public boolean autoFallbackFreeModels = true;

        // Mode 3: Local GGUF settings
        public String selectedGgufFileName = "";
        public boolean autoUnloadOnGameLaunch = true;
        public int ggufContextSize = DEFAULT_GGUF_CONTEXT_SIZE;
        public int ggufThreads = 4;
        public int localLoopbackServerPort = DEFAULT_LOCAL_SERVER_PORT;

        public String role = AiRole.COMPANION.name();
        public String botUsername = DEFAULT_BOT_USERNAME;
        public String targetMode = TargetConnectionMode.LAN_WORLD.name();
        public String lanAddress = "127.0.0.1:25565";
        public String zyroxRoomCode = "";
        public String savedServerAddress = "";
        public String preferredMcVersion = "auto";
        public String botAuthMode = BotAuthMode.OFFLINE.name();

        public String bridgeHost = DEFAULT_BRIDGE_HOST;
        public int bridgePort = DEFAULT_BRIDGE_PORT;
        public String encryptedBridgeToken = AccountTokenCipher.NO_TOKEN;

        public boolean inGameOverlayEnabled = true;
        public boolean respondToWorldChatMentions = true;
        public long lastUpdatedEpochMs = 0L;

        @NonNull
        public ConfigData copy() {
            ConfigData c = new ConfigData();
            c.schemaVersion = schemaVersion;
            c.providerMode = providerMode;
            c.anonymousRoutingPolicy = anonymousRoutingPolicy;
            c.enabledAnonymousProviders = enabledAnonymousProviders != null
                    ? new ArrayList<>(enabledAnonymousProviders)
                    : AiProviderCatalog.getDefaultEnabledAnonymousProviderIds();
            c.anonymousAllowCrossProviderFallback = anonymousAllowCrossProviderFallback;
            c.customFreeRouterUrl = customFreeRouterUrl;
            c.customFreeRouterModel = customFreeRouterModel;
            c.onlinePresetId = onlinePresetId;
            c.baseApiUrl = baseApiUrl;
            c.encryptedApiKey = encryptedApiKey;
            c.selectedOnlineModel = selectedOnlineModel;
            c.fallbackOnlineModels = fallbackOnlineModels != null
                    ? new ArrayList<>(fallbackOnlineModels)
                    : new ArrayList<>(CURATED_FREE_MODELS);
            c.autoFallbackFreeModels = autoFallbackFreeModels;
            c.selectedGgufFileName = selectedGgufFileName;
            c.autoUnloadOnGameLaunch = autoUnloadOnGameLaunch;
            c.ggufContextSize = ggufContextSize;
            c.ggufThreads = ggufThreads;
            c.localLoopbackServerPort = localLoopbackServerPort;
            c.role = role;
            c.botUsername = botUsername;
            c.targetMode = targetMode;
            c.lanAddress = lanAddress;
            c.zyroxRoomCode = zyroxRoomCode;
            c.savedServerAddress = savedServerAddress;
            c.preferredMcVersion = preferredMcVersion;
            c.botAuthMode = botAuthMode;
            c.bridgeHost = bridgeHost;
            c.bridgePort = bridgePort;
            c.encryptedBridgeToken = encryptedBridgeToken;
            c.inGameOverlayEnabled = inGameOverlayEnabled;
            c.respondToWorldChatMentions = respondToWorldChatMentions;
            c.lastUpdatedEpochMs = lastUpdatedEpochMs;
            return c;
        }
    }

    private final File configFile;
    private final Object lock = new Object();
    private ConfigData cached;
    private long lastLoadedMtime = -1L;

    public AiFriendConfigStore(@NonNull File baseDataDir) {
        File dir = new File(baseDataDir, "ai_friend");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create ai_friend config directory");
        }
        this.configFile = new File(dir, "ai_friend_config.json");
    }

    @NonNull
    public static File resolveBaseDataDir(@Nullable Context context) {
        if (Tools.DIR_DATA != null && !Tools.DIR_DATA.isEmpty()) {
            return new File(Tools.DIR_DATA);
        }
        if (context != null && context.getFilesDir() != null) {
            return context.getFilesDir();
        }
        return new File(System.getProperty("java.io.tmpdir", "."), "zyrox_ai_test");
    }

    @NonNull
    public ConfigData load() {
        synchronized (lock) {
            long mtime = configFile.isFile() ? configFile.lastModified() : -1L;
            if (cached != null && mtime == lastLoadedMtime && mtime > 0L) {
                return cached.copy();
            }
            if (configFile.isFile()) {
                try {
                    ConfigData loaded = JSONUtils.readFromFile(configFile, ConfigData.class);
                    if (loaded != null) {
                        sanitizeInPlace(loaded);
                        cached = loaded;
                        lastLoadedMtime = mtime;
                        return cached.copy();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed reading ai_friend_config.json, restoring defaults");
                }
            }
            if (cached == null) {
                cached = new ConfigData();
                sanitizeInPlace(cached);
            }
            return cached.copy();
        }
    }

    public void save(@NonNull ConfigData updated) {
        synchronized (lock) {
            ConfigData clean = updated.copy();
            sanitizeInPlace(clean);
            clean.lastUpdatedEpochMs = System.currentTimeMillis();
            try {
                JSONUtils.writeToFileAtomic(configFile, clean, true);
                cached = clean;
                lastLoadedMtime = configFile.lastModified();
            } catch (IOException e) {
                Log.e(TAG, "Failed persisting ai_friend_config.json atomically", e);
                cached = clean;
            }
        }
    }

    @NonNull
    public String getDecryptedApiKey() {
        ConfigData cfg = load();
        String dec = AccountTokenCipher.decrypt(cfg.encryptedApiKey);
        return AccountTokenCipher.NO_TOKEN.equals(dec) ? "" : dec.trim();
    }

    public void setApiKey(@Nullable String rawApiKey) {
        ConfigData cfg = load();
        String trimmed = rawApiKey == null ? "" : rawApiKey.trim();
        cfg.encryptedApiKey = trimmed.isEmpty()
                ? AccountTokenCipher.NO_TOKEN
                : AccountTokenCipher.encrypt(trimmed);
        save(cfg);
    }

    @NonNull
    public String getMaskedApiKeyPreview() {
        String key = getDecryptedApiKey();
        if (key.isEmpty()) {
            return "Not configured";
        }
        if (key.length() <= 10) {
            return "••••••••";
        }
        return key.substring(0, 6) + "••••••••" + key.substring(key.length() - 4);
    }

    @NonNull
    public String getDecryptedBridgeToken() {
        ConfigData cfg = load();
        String dec = AccountTokenCipher.decrypt(cfg.encryptedBridgeToken);
        return AccountTokenCipher.NO_TOKEN.equals(dec) ? "" : dec.trim();
    }

    public void setBridgeToken(@Nullable String rawToken) {
        ConfigData cfg = load();
        String trimmed = rawToken == null ? "" : rawToken.trim();
        cfg.encryptedBridgeToken = trimmed.isEmpty()
                ? AccountTokenCipher.NO_TOKEN
                : AccountTokenCipher.encrypt(trimmed);
        save(cfg);
    }

    @NonNull
    public File getConfigFile() {
        return configFile;
    }

    private static void sanitizeInPlace(@NonNull ConfigData cfg) {
        cfg.providerMode = ProviderMode.fromWire(cfg.providerMode).name();
        cfg.role = AiRole.fromWire(cfg.role).name();
        cfg.targetMode = TargetConnectionMode.fromWire(cfg.targetMode).name();
        cfg.botAuthMode = BotAuthMode.fromWire(cfg.botAuthMode).name();

        if (cfg.anonymousRoutingPolicy == null || cfg.anonymousRoutingPolicy.trim().isEmpty()) {
            cfg.anonymousRoutingPolicy = AiProviderCatalog.POLICY_AUTO_CASCADE;
        } else {
            cfg.anonymousRoutingPolicy = cfg.anonymousRoutingPolicy.trim();
        }
        if (cfg.enabledAnonymousProviders == null) {
            cfg.enabledAnonymousProviders = AiProviderCatalog.getDefaultEnabledAnonymousProviderIds();
        }
        if (cfg.customFreeRouterUrl == null) {
            cfg.customFreeRouterUrl = "";
        } else {
            cfg.customFreeRouterUrl = AiProviderCatalog.trimTrailingSlashes(cfg.customFreeRouterUrl.trim());
        }
        if (cfg.customFreeRouterModel == null || cfg.customFreeRouterModel.trim().isEmpty()) {
            cfg.customFreeRouterModel = "auto";
        } else {
            cfg.customFreeRouterModel = cfg.customFreeRouterModel.trim();
        }
        if (cfg.onlinePresetId == null || cfg.onlinePresetId.trim().isEmpty()) {
            cfg.onlinePresetId = "openrouter";
        } else {
            cfg.onlinePresetId = cfg.onlinePresetId.trim();
        }

        if (cfg.baseApiUrl == null || cfg.baseApiUrl.trim().isEmpty()) {
            cfg.baseApiUrl = DEFAULT_OPENROUTER_BASE_URL;
        } else {
            cfg.baseApiUrl = cfg.baseApiUrl.trim();
            while (cfg.baseApiUrl.endsWith("/")) {
                cfg.baseApiUrl = cfg.baseApiUrl.substring(0, cfg.baseApiUrl.length() - 1);
            }
        }

        if (cfg.selectedOnlineModel == null || cfg.selectedOnlineModel.trim().isEmpty()) {
            cfg.selectedOnlineModel = DEFAULT_ONLINE_MODEL;
        } else {
            cfg.selectedOnlineModel = cfg.selectedOnlineModel.trim();
        }

        if (cfg.fallbackOnlineModels == null || cfg.fallbackOnlineModels.isEmpty()) {
            cfg.fallbackOnlineModels = new ArrayList<>(CURATED_FREE_MODELS);
        }

        if (cfg.botUsername == null || cfg.botUsername.trim().isEmpty()) {
            cfg.botUsername = DEFAULT_BOT_USERNAME;
        } else {
            String cleanName = cfg.botUsername.replaceAll("[^A-Za-z0-9_]", "");
            if (cleanName.isEmpty()) cleanName = DEFAULT_BOT_USERNAME;
            if (cleanName.length() > 16) cleanName = cleanName.substring(0, 16);
            cfg.botUsername = cleanName;
        }

        if (cfg.bridgeHost == null || cfg.bridgeHost.trim().isEmpty()) {
            cfg.bridgeHost = DEFAULT_BRIDGE_HOST;
        } else {
            cfg.bridgeHost = cfg.bridgeHost.trim();
        }
        if (cfg.bridgePort < 1 || cfg.bridgePort > 65535) {
            cfg.bridgePort = DEFAULT_BRIDGE_PORT;
        }
        if (cfg.localLoopbackServerPort < 1 || cfg.localLoopbackServerPort > 65535) {
            cfg.localLoopbackServerPort = DEFAULT_LOCAL_SERVER_PORT;
        }
        if (cfg.ggufContextSize < 512 || cfg.ggufContextSize > 8192) {
            cfg.ggufContextSize = DEFAULT_GGUF_CONTEXT_SIZE;
        }
        if (cfg.ggufThreads < 1 || cfg.ggufThreads > 16) {
            cfg.ggufThreads = 4;
        }
        if (cfg.selectedGgufFileName == null) {
            cfg.selectedGgufFileName = "";
        }
        if (cfg.lanAddress == null || cfg.lanAddress.trim().isEmpty()) {
            cfg.lanAddress = "127.0.0.1:25565";
        }
        if (cfg.zyroxRoomCode == null) {
            cfg.zyroxRoomCode = "";
        }
        if (cfg.savedServerAddress == null) {
            cfg.savedServerAddress = "";
        }
        if (cfg.preferredMcVersion == null || cfg.preferredMcVersion.trim().isEmpty()) {
            cfg.preferredMcVersion = "auto";
        }
        if (cfg.encryptedApiKey == null || cfg.encryptedApiKey.isEmpty()) {
            cfg.encryptedApiKey = AccountTokenCipher.NO_TOKEN;
        }
        if (cfg.encryptedBridgeToken == null || cfg.encryptedBridgeToken.isEmpty()) {
            cfg.encryptedBridgeToken = AccountTokenCipher.NO_TOKEN;
        }
    }
}
