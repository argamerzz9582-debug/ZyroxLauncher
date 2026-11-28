package git.artdeell.mojo.ai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.BotConnectionState;
import git.artdeell.mojo.ai.AiFriendModels.BotStatusSnapshot;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.ai.AiFriendModels.ResultCallback;
import git.artdeell.mojo.ai.AiFriendModels.StateListener;
import git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode;
import git.artdeell.mojo.ai.AiFriendTargetResolver.ResolvedTarget;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;

/**
 * Process-safe coordinator singleton for Zyrox AI Friend.
 *
 * <p>Unifies configuration persistence ({@link AiFriendConfigStore}), cloud &amp; local LLM
 * inference ({@link OnlineFreeApiLlmProvider}, {@link LocalGgufLlmProvider}), GGUF model management
 * ({@link GgufModelManager}), target world resolution ({@link AiFriendTargetResolver}), and
 * in-world companion bot orchestration ({@link CompanionBotBridgeClient}).</p>
 */
public final class AiFriendManager implements CompanionBotBridgeClient.BridgeEventListener {

    private static final String TAG = "AiFriendManager";
    private static final int MAX_HISTORY_ENTRIES = 80;
    private static final long WORLD_MENTION_COOLDOWN_MS = 2500L;

    private static volatile AiFriendManager sInstance;

    private final AiFriendConfigStore configStore;
    private final LlamaCppRuntimeBridge runtimeBridge;
    private final GgufModelManager modelManager;
    private final FreeAnonymousRouterLlmProvider freeAnonymousProvider;
    private final OnlineFreeApiLlmProvider onlineProvider;
    private final LocalGgufLlmProvider localProvider;
    private final CompanionBotBridgeClient bridgeClient;
    private final ExecutorService executor;

    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final Object historyLock = new Object();
    private final List<ChatHistoryEntry> chatHistory = new ArrayList<>();
    private volatile long lastWorldMentionHandledMs = 0L;
    private volatile int lastDetectedLiveLanPort = -1;

    @NonNull
    public static AiFriendManager getInstance(@Nullable Context context) {
        if (sInstance == null) {
            synchronized (AiFriendManager.class) {
                if (sInstance == null) {
                    Context appCtx = context != null ? context.getApplicationContext() : null;
                    File baseDir = AiFriendConfigStore.resolveBaseDataDir(appCtx);
                    sInstance = new AiFriendManager(baseDir, resolveSharedExecutor());
                }
            }
        }
        return sInstance;
    }

    /** Package-visible constructor for deterministic JUnit testing with isolated temp directories. */
    AiFriendManager(@NonNull File baseDataDir, @NonNull ExecutorService executor) {
        this.executor = executor;
        this.configStore = new AiFriendConfigStore(baseDataDir);
        this.runtimeBridge = new LlamaCppRuntimeBridge(baseDataDir);
        StateListener internalNotifier = new StateListener() {
            @Override
            public void onAiFriendStateChanged() {
                dispatchStateChangedOnMain();
            }
        };
        this.modelManager = new GgufModelManager(
                baseDataDir, configStore, runtimeBridge, executor, internalNotifier);
        this.freeAnonymousProvider = new FreeAnonymousRouterLlmProvider(configStore);
        this.onlineProvider = new OnlineFreeApiLlmProvider(configStore);
        this.localProvider = new LocalGgufLlmProvider(modelManager);
        this.bridgeClient = new CompanionBotBridgeClient(executor, this);
    }

    @NonNull
    private static ExecutorService resolveSharedExecutor() {
        if (MojoApplication.sExecutorService != null) {
            return MojoApplication.sExecutorService;
        }
        final AtomicInteger counter = new AtomicInteger(1);
        return Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread t = new Thread(r, "ZyroxAiWorker-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    @NonNull
    public AiFriendConfigStore getConfigStore() {
        return configStore;
    }

    @NonNull
    public GgufModelManager getModelManager() {
        return modelManager;
    }

    @NonNull
    public CompanionBotBridgeClient getBridgeClient() {
        return bridgeClient;
    }

    @NonNull
    public FreeAnonymousRouterLlmProvider getFreeAnonymousProvider() {
        return freeAnonymousProvider;
    }

    @NonNull
    public OnlineFreeApiLlmProvider getOnlineProvider() {
        return onlineProvider;
    }

    @NonNull
    public LocalGgufLlmProvider getLocalProvider() {
        return localProvider;
    }

    @NonNull
    public LlmProvider getActiveProvider() {
        ProviderMode mode = ProviderMode.fromWire(configStore.load().providerMode);
        if (mode == ProviderMode.LOCAL_GGUF) {
            return localProvider;
        }
        if (mode == ProviderMode.ONLINE_API) {
            return onlineProvider;
        }
        return freeAnonymousProvider;
    }

    /**
     * Asynchronously queries {@code GET /models} either for a specific built-in anonymous internet
     * provider (when {@code anonymousProviderId != null}) or for the configured Mode 2 API-key endpoint.
     */
    public void fetchLiveModelsAsync(@Nullable final String anonymousProviderId,
                                     @NonNull final ResultCallback<List<String>> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String> models = (anonymousProviderId != null)
                            ? freeAnonymousProvider.fetchProviderLiveModelsBlocking(anonymousProviderId)
                            : onlineProvider.fetchLiveModelsBlocking();
                    postOnMain(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(models);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    postOnMain(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(msg);
                        }
                    });
                }
            }
        });
    }

    public void addStateListener(@NonNull StateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public void removeStateListener(@NonNull StateListener listener) {
        stateListeners.remove(listener);
    }

    @NonNull
    public List<ChatHistoryEntry> getChatHistorySnapshot() {
        synchronized (historyLock) {
            return new ArrayList<>(chatHistory);
        }
    }

    public void clearChatHistory() {
        synchronized (historyLock) {
            chatHistory.clear();
        }
        dispatchStateChangedOnMain();
    }

    public int getLastDetectedLiveLanPort() {
        return lastDetectedLiveLanPort;
    }

    /**
     * Sends a natural-language prompt to the active LLM provider on a background thread, parses
     * the structured {@code {"say":"...","action":"..."}} output, records history, and dispatches
     * execution to the Companion Bot Bridge if connected.
     */
    public void sendPromptAsync(@NonNull final String rawPrompt,
                                @Nullable final String explicitOwnerUsername,
                                @Nullable final ResultCallback<AiTurnResponse> callback) {
        final String trimmed = rawPrompt.trim();
        if (trimmed.isEmpty()) {
            if (callback != null) {
                postOnMain(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError("Enter a prompt or pick a quick action first.");
                    }
                });
            }
            return;
        }

        appendHistory(new ChatHistoryEntry(
                System.currentTimeMillis(),
                ChatHistoryEntry.SENDER_USER,
                trimmed,
                null,
                null
        ));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ConfigData cfg = configStore.load();
                    AiRole role = AiRole.fromWire(cfg.role);
                    String owner = resolveOwnerUsername(explicitOwnerUsername, cfg.botUsername);
                    LlmProvider provider = getActiveProvider();
                    String worldCtx = buildLiveWorldContextSummary();

                    final AiTurnResponse turn = provider.generateTurn(
                            trimmed,
                            role,
                            cfg.botUsername,
                            owner,
                            worldCtx,
                            getChatHistorySnapshot()
                    );

                    boolean sentToBot = bridgeClient.sendExecuteTurn(turn.say, turn.action, owner);
                    String badge = turn.action.name()
                            + (sentToBot ? " • SENT TO BOT" : " • CO-PILOT")
                            + " • " + turn.latencyMs + "ms";

                    appendHistory(new ChatHistoryEntry(
                            System.currentTimeMillis(),
                            ChatHistoryEntry.SENDER_AI,
                            turn.say,
                            turn.action,
                            badge
                    ));

                    if (callback != null) {
                        postOnMain(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(turn);
                            }
                        });
                    }
                } catch (final Exception e) {
                    final String msg = e.getMessage() != null ? e.getMessage() : "AI generation failed";
                    appendHistory(new ChatHistoryEntry(
                            System.currentTimeMillis(),
                            ChatHistoryEntry.SENDER_SYSTEM,
                            "Error: " + msg,
                            null,
                            "ERROR"
                    ));
                    if (callback != null) {
                        postOnMain(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(msg);
                            }
                        });
                    }
                }
            }
        });
    }

    /**
     * Dispatches a 1-tap tactical {@link BotAction} immediately without requiring keyboard focus.
     * If the Companion Bot Bridge is connected, executes directly in-world; also logs a crisp turn.
     */
    public void dispatchQuickActionAsync(@NonNull final BotAction action,
                                         @Nullable final String explicitOwnerUsername,
                                         @Nullable final ResultCallback<String> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                ConfigData cfg = configStore.load();
                AiRole role = AiRole.fromWire(cfg.role);
                String owner = resolveOwnerUsername(explicitOwnerUsername, cfg.botUsername);
                boolean sent = bridgeClient.sendDirectAction(action, owner);
                String reply = AiResponseParser.fallbackSayForAction(action, role);

                appendHistory(new ChatHistoryEntry(
                        System.currentTimeMillis(),
                        ChatHistoryEntry.SENDER_AI,
                        reply,
                        action,
                        action.name() + (sent ? " • BOT EXECUTING" : " • BRIDGE OFFLINE")
                ));

                final String summary = sent
                        ? cfg.botUsername + " -> " + action.displayName
                        : action.displayName + " queued (connect Bot Bridge to execute in-world)";
                if (callback != null) {
                    postOnMain(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(summary);
                        }
                    });
                }
            }
        });
    }

    /** Resolves the configured target world/server and summons the Mineflayer companion bot. */
    public void summonBotAsync(@Nullable final ResultCallback<ResolvedTarget> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ConfigData cfg = configStore.load();
                    final ResolvedTarget target = AiFriendTargetResolver.resolve(cfg);
                    AiRole role = AiRole.fromWire(cfg.role);
                    String owner = resolveOwnerUsername(null, cfg.botUsername);
                    String mcVer = resolvePreferredMcVersion(cfg);
                    String bridgeToken = configStore.getDecryptedBridgeToken();

                    bridgeClient.connectAndSummonBlocking(
                            cfg.bridgeHost,
                            cfg.bridgePort,
                            bridgeToken,
                            target,
                            cfg.botUsername,
                            role,
                            owner,
                            mcVer
                    );

                    appendHistory(new ChatHistoryEntry(
                            System.currentTimeMillis(),
                            ChatHistoryEntry.SENDER_SYSTEM,
                            "Summoning " + cfg.botUsername + " (" + role.displayName + ") into " + target.displaySummary,
                            role.defaultAction,
                            "SUMMON"
                    ));

                    if (callback != null) {
                        postOnMain(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(target);
                            }
                        });
                    }
                } catch (final Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : "Failed summoning bot";
                    appendHistory(new ChatHistoryEntry(
                            System.currentTimeMillis(),
                            ChatHistoryEntry.SENDER_SYSTEM,
                            err,
                            null,
                            "BRIDGE ERROR"
                    ));
                    if (callback != null) {
                        postOnMain(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(err);
                            }
                        });
                    }
                }
            }
        });
    }

    public void dismissBotAsync(@Nullable final ResultCallback<Boolean> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                bridgeClient.dismissAndClose();
                appendHistory(new ChatHistoryEntry(
                        System.currentTimeMillis(),
                        ChatHistoryEntry.SENDER_SYSTEM,
                        "Dismissed companion bot from world.",
                        BotAction.IDLE,
                        "DISMISSED"
                ));
                if (callback != null) {
                    postOnMain(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(Boolean.TRUE);
                        }
                    });
                }
            }
        });
    }

    public void pingBridgeAsync(@NonNull final ResultCallback<String> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ConfigData cfg = configStore.load();
                    final String status = CompanionBotBridgeClient.pingBridgeBlocking(
                            cfg.bridgeHost, cfg.bridgePort, configStore.getDecryptedBridgeToken(), 3000);
                    postOnMain(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(status);
                        }
                    });
                } catch (final Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : "Bridge unreachable";
                    postOnMain(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(err);
                        }
                    });
                }
            }
        });
    }

    /**
     * Hook invoked from {@code ContextAwareDoneListener} and {@code LauncherActivity} right before
     * launching {@code GameActivity}: enforces <b>Auto-Unload on Game Launch</b> so local GGUF
     * memory is returned to Android before the Minecraft JVM starts.
     */
    public void onBeforeGameLaunch() {
        try {
            modelManager.autoUnloadForGameLaunchIfEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "Non-fatal warning during pre-launch GGUF auto-unload", t);
        }
    }

    /**
     * Live log hook called from {@code GameActivity.onLiveGameLogLine}: detects when the player
     * opens a singleplayer world to LAN ({@code "Local game hosted on port <port>"}) and updates
     * the LAN target port automatically!
     */
    public void onLiveGameLogLine(@Nullable String line) {
        int lanPort = AiFriendTargetResolver.parseLanPortFromGameLogLine(line);
        if (lanPort > 0 && lanPort != lastDetectedLiveLanPort) {
            lastDetectedLiveLanPort = lanPort;
            ConfigData cfg = configStore.load();
            cfg.lanAddress = "127.0.0.1:" + lanPort;
            cfg.targetMode = TargetConnectionMode.LAN_WORLD.name();
            configStore.save(cfg);
            appendHistory(new ChatHistoryEntry(
                    System.currentTimeMillis(),
                    ChatHistoryEntry.SENDER_SYSTEM,
                    "Detected Open-to-LAN world on port " + lanPort + " — tap Summon to bring " + cfg.botUsername + " in!",
                    null,
                    "LAN DETECTED"
            ));
        }
    }

    /** Returns a concise status badge label for Launcher / HUD pills. */
    @NonNull
    public String getCompactHudBadgeLabel() {
        BotStatusSnapshot bot = bridgeClient.getLatestStatus();
        if (bot.state == BotConnectionState.CONNECTED) {
            return "AI · " + bot.activeAction.shortChipLabel.toUpperCase(Locale.ROOT);
        }
        if (bot.state == BotConnectionState.CONNECTING) {
            return "AI · JOINING";
        }
        ProviderMode mode = ProviderMode.fromWire(configStore.load().providerMode);
        if (mode == ProviderMode.LOCAL_GGUF) {
            return modelManager.getRuntimeBridge().isModelLoaded() ? "AI · GGUF" : "AI · UNLOADED";
        }
        if (mode == ProviderMode.ONLINE_API) {
            return onlineProvider.isReady() ? "AI · API KEY" : "AI · KEY?";
        }
        return "AI · FREE NET";
    }

    // =========================================================================
    // BridgeEventListener callbacks
    // =========================================================================

    @Override
    public void onBotStatusChanged(@NonNull BotStatusSnapshot snapshot) {
        dispatchStateChangedOnMain();
    }

    @Override
    public void onWorldChatReceived(@NonNull final String username,
                                    @NonNull final String message,
                                    boolean isWhisper) {
        ConfigData cfg = configStore.load();
        if (username.equalsIgnoreCase(cfg.botUsername)) {
            return; // Never reply to the bot's own chat messages
        }
        if (!cfg.respondToWorldChatMentions) {
            return;
        }

        String lowerMsg = message.toLowerCase(Locale.ROOT).trim();
        String lowerBot = cfg.botUsername.toLowerCase(Locale.ROOT);
        boolean addressed = isWhisper
                || lowerMsg.startsWith("@" + lowerBot)
                || lowerMsg.startsWith(lowerBot + ",")
                || lowerMsg.startsWith(lowerBot + ":")
                || lowerMsg.startsWith("!ai ");
        if (!addressed) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastWorldMentionHandledMs < WORLD_MENTION_COOLDOWN_MS) {
            return;
        }
        lastWorldMentionHandledMs = now;

        String cleanedPrompt = message
                .replaceFirst("(?i)^@?" + java.util.regex.Pattern.quote(cfg.botUsername) + "[,:\\s]*", "")
                .replaceFirst("(?i)^!ai\\s+", "")
                .trim();
        if (cleanedPrompt.isEmpty()) {
            cleanedPrompt = "status";
        }

        appendHistory(new ChatHistoryEntry(
                now,
                ChatHistoryEntry.SENDER_WORLD,
                "<" + username + "> " + message,
                null,
                "IN-WORLD"
        ));

        sendPromptAsync(cleanedPrompt, username, null);
    }

    @Override
    public void onActionUpdate(@NonNull BotAction action, @NonNull String status, @NonNull String detail) {
        if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            appendHistory(new ChatHistoryEntry(
                    System.currentTimeMillis(),
                    ChatHistoryEntry.SENDER_SYSTEM,
                    action.displayName + ": " + (detail.isEmpty() ? status : detail),
                    action,
                    status.toUpperCase(Locale.ROOT)
            ));
        } else {
            dispatchStateChangedOnMain();
        }
    }

    @Override
    public void onBridgeNotice(@NonNull String message) {
        appendHistory(new ChatHistoryEntry(
                System.currentTimeMillis(),
                ChatHistoryEntry.SENDER_SYSTEM,
                message,
                null,
                "BRIDGE"
        ));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void appendHistory(@NonNull ChatHistoryEntry entry) {
        synchronized (historyLock) {
            chatHistory.add(entry);
            while (chatHistory.size() > MAX_HISTORY_ENTRIES) {
                chatHistory.remove(0);
            }
        }
        dispatchStateChangedOnMain();
    }

    private void dispatchStateChangedOnMain() {
        postOnMain(new Runnable() {
            @Override
            public void run() {
                for (StateListener l : stateListeners) {
                    try {
                        l.onAiFriendStateChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @NonNull
    private String buildLiveWorldContextSummary() {
        BotStatusSnapshot snap = bridgeClient.getLatestStatus();
        if (snap.state != BotConnectionState.CONNECTED) {
            return "Companion bot is currently offline (answering in co-pilot chat mode).";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("Connected to ").append(snap.endpoint)
                .append(", HP=").append(String.format(Locale.US, "%.1f", snap.health))
                .append("/20, Food=").append(snap.food).append("/20")
                .append(", CurrentAction=").append(snap.activeAction.name());
        if (snap.hasPosition) {
            sb.append(", Pos=(").append(snap.blockX).append(", ")
                    .append(snap.blockY).append(", ").append(snap.blockZ).append(")");
        }
        return sb.toString();
    }

    @NonNull
    private static String resolveOwnerUsername(@Nullable String explicitOwner, @NonNull String botUsername) {
        if (explicitOwner != null && !explicitOwner.trim().isEmpty()) {
            return explicitOwner.trim();
        }
        try {
            if (Tools.DIR_ACCOUNT_NEW != null) {
                Account acc = Accounts.getCurrent();
                if (acc != null && acc.username != null && !acc.username.trim().isEmpty()
                        && !"0".equals(acc.username.trim())
                        && !acc.username.equalsIgnoreCase(botUsername)) {
                    return acc.username.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "Player";
    }

    @NonNull
    private static String resolvePreferredMcVersion(@NonNull ConfigData cfg) {
        if (cfg.preferredMcVersion != null
                && !cfg.preferredMcVersion.trim().isEmpty()
                && !"auto".equalsIgnoreCase(cfg.preferredMcVersion.trim())) {
            return cfg.preferredMcVersion.trim();
        }
        try {
            Instance inst = Instances.loadSelectedInstance();
            if (inst != null && inst.versionId != null) {
                String clean = Instance.extractCleanMinecraftVersion(inst.versionId);
                if (clean != null && !clean.isEmpty()) {
                    return clean;
                }
            }
        } catch (Throwable ignored) {
        }
        return "auto";
    }

    private static void postOnMain(@NonNull Runnable r) {
        if (Tools.MAIN_HANDLER != null) {
            Tools.MAIN_HANDLER.post(r);
        } else {
            r.run();
        }
    }
}
