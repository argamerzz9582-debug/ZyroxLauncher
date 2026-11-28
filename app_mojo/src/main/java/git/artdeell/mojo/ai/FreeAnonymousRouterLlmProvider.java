package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.ai.AiProviderCatalog.AnonymousProviderSpec;
import git.artdeell.mojo.ai.AiProviderCatalog.HttpResult;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import okhttp3.OkHttpClient;

/**
 * Zero-signup, zero-API-key multi-provider internet LLM router ({@link ProviderMode#FREE_ANONYMOUS}).
 *
 * <p>Automatically routes across verified anonymous OpenAI-compatible endpoints (Kilo AI Gateway,
 * OVHcloud AI Endpoints, Pollinations.AI, VLM Run Gateway, AI Horde Community Cluster, plus any
 * optional self-hosted keyless router such as OmniRoute or FreeLM) with:</p>
 * <ul>
 *   <li>Per-model round-robin rotation (unlocking 18+ RPM across OVHcloud's 2-RPM-per-model pool).</li>
 *   <li>Per-route HTTP {@code 429} / {@code 5xx} cooldown circuit breaking so rate-limited routes are skipped immediately.</li>
 *   <li>Live {@code GET /models} catalog refresh for dynamic worker pools (AI Horde) and rotated model IDs.</li>
 *   <li>Automatic {@code response_format} / sampling downgrade and strict message-order shaping.</li>
 * </ul>
 */
public final class FreeAnonymousRouterLlmProvider implements LlmProvider {

    private static final int MAX_RECENT_TURNS = 6;
    private static final int MAX_MODELS_PER_PROVIDER_ATTEMPT = 2;
    private static final long MODEL_CATALOG_TTL_MS = 10L * 60L * 1000L;
    private static final long RATE_LIMIT_COOLDOWN_MS = 62_000L;
    private static final long TRANSIENT_ERROR_COOLDOWN_MS = 18_000L;

    /** Snapshot of recent live routing telemetry for UI badges in {@code AiFriendFragment}. */
    public static final class ProviderHealthSnapshot {
        public final String providerId;
        public final long lastLatencyMs;
        public final String lastModelUsed;
        public final String lastError;
        public final boolean healthy;
        public final int successCount;

        public ProviderHealthSnapshot(@NonNull String providerId,
                                      long lastLatencyMs,
                                      @NonNull String lastModelUsed,
                                      @Nullable String lastError,
                                      boolean healthy,
                                      int successCount) {
            this.providerId = providerId;
            this.lastLatencyMs = lastLatencyMs;
            this.lastModelUsed = lastModelUsed;
            this.lastError = lastError;
            this.healthy = healthy;
            this.successCount = successCount;
        }
    }

    private static final class CachedModelCatalog {
        final List<String> models;
        final long fetchedAtMs;

        CachedModelCatalog(@NonNull List<String> models, long fetchedAtMs) {
            this.models = models;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private final AiFriendConfigStore configStore;
    private final List<AnonymousProviderSpec> providerRegistry;
    private final OkHttpClient httpsClient;
    private final boolean testLoopbackMode;

    private final Map<String, Long> routeCooldownUntilMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    private final Map<String, ProviderHealthSnapshot> providerHealth = new ConcurrentHashMap<>();
    private final Map<String, CachedModelCatalog> liveModelCache = new ConcurrentHashMap<>();
    private volatile String lastWinningSummary = "Auto Cascade Ready";

    public FreeAnonymousRouterLlmProvider(@NonNull AiFriendConfigStore configStore) {
        this(configStore,
                AiProviderCatalog.ANONYMOUS_PROVIDERS,
                ZyroxHttpClient.apiClient().newBuilder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                        .build(),
                false);
    }

    /** Package-visible constructor for deterministic JUnit testing against loopback HTTP servers. */
    FreeAnonymousRouterLlmProvider(@NonNull AiFriendConfigStore configStore,
                                   @NonNull List<AnonymousProviderSpec> customRegistry,
                                   @NonNull OkHttpClient customClient,
                                   boolean testLoopbackMode) {
        this.configStore = configStore;
        this.providerRegistry = Collections.unmodifiableList(new ArrayList<>(customRegistry));
        this.httpsClient = customClient;
        this.testLoopbackMode = testLoopbackMode;
    }

    @NonNull
    @Override
    public ProviderMode getProviderMode() {
        return ProviderMode.FREE_ANONYMOUS;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @NonNull
    @Override
    public String getStatusSummary() {
        return "Free Router · " + lastWinningSummary;
    }

    @NonNull
    public String getLastWinningSummary() {
        return lastWinningSummary;
    }

    @Nullable
    public ProviderHealthSnapshot getHealthSnapshot(@NonNull String providerId) {
        return providerHealth.get(providerId);
    }

    @NonNull
    public Map<String, ProviderHealthSnapshot> getAllHealthSnapshots() {
        return new HashMap<>(providerHealth);
    }

    @NonNull
    public List<AnonymousProviderSpec> getBuiltInProviders() {
        return providerRegistry;
    }

    /** Fetches live {@code /models} from a specific anonymous provider and caches the result. */
    @NonNull
    public List<String> fetchProviderLiveModelsBlocking(@NonNull String providerId) throws IOException {
        ConfigData cfg = configStore.load();
        AnonymousProviderSpec spec = resolveSpecById(providerId, cfg);
        if (spec == null) {
            throw new IOException("Unknown anonymous provider: " + providerId);
        }
        String modelsUrl = AiProviderCatalog.resolveEndpoint(
                spec.baseUrl, spec.baseUrl, "/models", true);
        HttpResult res = AiProviderCatalog.executeHttpRequest(
                httpsClient, "GET", modelsUrl, spec.anonymousBearerToken, null, 15, testLoopbackMode);
        if (res.statusCode < 200 || res.statusCode >= 300) {
            throw new IOException(spec.title + " /models returned HTTP " + res.statusCode);
        }
        List<String> parsed = AiProviderCatalog.parseModelIdsFromCatalogJson(
                res.body, spec.filterFreeModelsOnly);
        if (!parsed.isEmpty()) {
            liveModelCache.put(spec.id, new CachedModelCatalog(parsed, System.currentTimeMillis()));
        }
        return parsed;
    }

    @NonNull
    @Override
    public AiTurnResponse generateTurn(@NonNull String userPrompt,
                                       @NonNull AiRole role,
                                       @NonNull String botUsername,
                                       @Nullable String ownerUsername,
                                       @Nullable String worldContextSummary,
                                       @Nullable List<ChatHistoryEntry> recentHistory) throws IOException {
        ConfigData cfg = configStore.load();
        List<AnonymousProviderSpec> orderedProviders = buildOrderedProviderPlan(cfg);
        String systemPrompt = AiResponseParser.buildSystemPrompt(
                role, botUsername, ownerUsername, worldContextSummary);
        List<ChatHistoryEntry> contextTurns = AiResponseParser.selectRecentDialogue(
                recentHistory, MAX_RECENT_TURNS);

        long overallStartMs = System.currentTimeMillis();
        List<String> failureNotes = new ArrayList<>();

        for (int pIdx = 0; pIdx < orderedProviders.size(); pIdx++) {
            AnonymousProviderSpec provider = orderedProviders.get(pIdx);
            List<String> candidateModels = selectCandidateModelsForProvider(provider);
            int triedOnThisProvider = 0;
            boolean refreshedCatalogOn404 = false;

            for (int mIdx = 0; mIdx < candidateModels.size()
                    && triedOnThisProvider < MAX_MODELS_PER_PROVIDER_ATTEMPT; mIdx++) {
                String modelId = candidateModels.get(mIdx);
                String routeKey = provider.id + ":" + modelId;
                if (isRouteCoolingDown(routeKey) && orderedProviders.size() > 1) {
                    continue;
                }

                triedOnThisProvider++;
                long attemptStart = System.currentTimeMillis();
                try {
                    String chatUrl = AiProviderCatalog.resolveEndpoint(
                            provider.baseUrl, provider.baseUrl, "/chat/completions", true);

                    SingleCallOutcome outcome = executeChatAttempt(
                            provider, chatUrl, modelId, systemPrompt, contextTurns, userPrompt,
                            !provider.strictSingleSystemUserOrder, true);

                    if (outcome.retryCompatMode) {
                        outcome = executeChatAttempt(
                                provider, chatUrl, modelId, systemPrompt, contextTurns, userPrompt,
                                false, false);
                    }

                    if (outcome.assistantContent != null && !outcome.assistantContent.trim().isEmpty()) {
                        long latency = System.currentTimeMillis() - attemptStart;
                        String modelBadge = provider.id + "/" + OnlineFreeApiLlmProvider.shortenModelId(modelId);
                        recordSuccess(provider.id, modelId, latency);
                        lastWinningSummary = provider.title + " (" + latency + "ms)";

                        return AiResponseParser.parseModelOutput(
                                outcome.assistantContent,
                                userPrompt,
                                role,
                                modelBadge,
                                ProviderMode.FREE_ANONYMOUS,
                                latency
                        );
                    }

                    // Handle rate limits, model rotations, and transient upstream failures
                    if (outcome.statusCode == 429) {
                        long cd = Math.max(RATE_LIMIT_COOLDOWN_MS, outcome.retryAfterMs);
                        markRouteCooldown(routeKey, cd);
                    } else if (outcome.statusCode == 404 && !refreshedCatalogOn404) {
                        refreshedCatalogOn404 = true;
                        markRouteCooldown(routeKey, RATE_LIMIT_COOLDOWN_MS);
                        List<String> fresh = tryRefreshLiveCatalogQuietly(provider);
                        for (String fm : fresh) {
                            if (!candidateModels.contains(fm)) {
                                candidateModels.add(fm);
                            }
                        }
                    } else if (outcome.statusCode >= 500 || outcome.statusCode == 408) {
                        markRouteCooldown(routeKey, TRANSIENT_ERROR_COOLDOWN_MS);
                    }

                    recordFailure(provider.id, modelId, outcome.errorSummary);
                    failureNotes.add(provider.id + "[" + OnlineFreeApiLlmProvider.shortenModelId(modelId)
                            + "]: " + outcome.errorSummary);
                } catch (IOException ioEx) {
                    markRouteCooldown(routeKey, TRANSIENT_ERROR_COOLDOWN_MS);
                    String safeMsg = LogRedactor.redact(ioEx.getMessage());
                    if (safeMsg.isEmpty()) safeMsg = ioEx.getClass().getSimpleName();
                    recordFailure(provider.id, modelId, safeMsg);
                    failureNotes.add(provider.id + ": " + safeMsg);
                    // Network-level failure on this host -> jump straight to next provider!
                    break;
                }
            }
        }

        long totalMs = System.currentTimeMillis() - overallStartMs;
        throw new IOException("All free anonymous internet routes were busy or unreachable after "
                + totalMs + " ms (" + joinBrief(failureNotes) + "). Try again or switch provider.");
    }

    // =========================================================================
    // Routing Plan & Model Rotation
    // =========================================================================

    @NonNull
    List<AnonymousProviderSpec> buildOrderedProviderPlan(@NonNull ConfigData cfg) {
        Set<String> enabledIds = new LinkedHashSet<>();
        if (cfg.enabledAnonymousProviders != null) {
            for (String id : cfg.enabledAnonymousProviders) {
                if (id != null && !id.trim().isEmpty()) {
                    enabledIds.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (enabledIds.isEmpty()) {
            for (AnonymousProviderSpec s : providerRegistry) {
                enabledIds.add(s.id);
            }
        }

        AnonymousProviderSpec customSpec = AiProviderCatalog.buildCustomAnonymousRouterSpec(
                cfg.customFreeRouterUrl, cfg.customFreeRouterModel);

        List<AnonymousProviderSpec> plan = new ArrayList<>();
        String policy = cfg.anonymousRoutingPolicy == null
                ? AiProviderCatalog.POLICY_AUTO_CASCADE
                : cfg.anonymousRoutingPolicy.trim();

        if (!AiProviderCatalog.POLICY_AUTO_CASCADE.equalsIgnoreCase(policy)) {
            AnonymousProviderSpec pinned = resolveSpecById(policy, cfg);
            if (pinned != null) {
                plan.add(pinned);
                if (!cfg.anonymousAllowCrossProviderFallback) {
                    return plan;
                }
            }
        }

        if (customSpec != null && !containsSpec(plan, customSpec.id)) {
            plan.add(customSpec);
        }

        for (AnonymousProviderSpec spec : providerRegistry) {
            if (enabledIds.contains(spec.id) && !containsSpec(plan, spec.id)) {
                plan.add(spec);
            }
        }

        if (plan.isEmpty() && !providerRegistry.isEmpty()) {
            plan.add(providerRegistry.get(0));
        }
        return plan;
    }

    @Nullable
    private AnonymousProviderSpec resolveSpecById(@NonNull String id, @NonNull ConfigData cfg) {
        if (AiProviderCatalog.CUSTOM_ROUTER_ID.equalsIgnoreCase(id)) {
            return AiProviderCatalog.buildCustomAnonymousRouterSpec(
                    cfg.customFreeRouterUrl, cfg.customFreeRouterModel);
        }
        for (AnonymousProviderSpec spec : providerRegistry) {
            if (spec.id.equalsIgnoreCase(id)) return spec;
        }
        return null;
    }

    private static boolean containsSpec(@NonNull List<AnonymousProviderSpec> list, @NonNull String id) {
        for (AnonymousProviderSpec s : list) {
            if (s.id.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    @NonNull
    private List<String> selectCandidateModelsForProvider(@NonNull AnonymousProviderSpec provider) {
        List<String> base = new ArrayList<>();
        if (provider.preferLiveModelsLookup) {
            List<String> live = getFreshOrFetchLiveCatalog(provider);
            base.addAll(live);
        } else {
            CachedModelCatalog cached = liveModelCache.get(provider.id);
            if (cached != null && (System.currentTimeMillis() - cached.fetchedAtMs) < MODEL_CATALOG_TTL_MS) {
                for (String m : cached.models) {
                    if (!base.contains(m)) base.add(m);
                }
            }
        }

        for (String curated : provider.curatedFreeModels) {
            if (!base.contains(curated)) {
                base.add(curated);
            }
        }

        if (base.isEmpty()) {
            base.add("auto");
        }

        if (provider.rotateModelsRoundRobin && base.size() > 1) {
            AtomicInteger counter = roundRobinCounters.get(provider.id);
            if (counter == null) {
                counter = new AtomicInteger(0);
                roundRobinCounters.put(provider.id, counter);
            }
            int offset = (counter.getAndIncrement() & 0x7FFFFFFF) % base.size();
            if (offset > 0) {
                List<String> rotated = new ArrayList<>(base.size());
                for (int i = 0; i < base.size(); i++) {
                    rotated.add(base.get((offset + i) % base.size()));
                }
                return rotated;
            }
        }
        return base;
    }

    @NonNull
    private List<String> getFreshOrFetchLiveCatalog(@NonNull AnonymousProviderSpec provider) {
        CachedModelCatalog cached = liveModelCache.get(provider.id);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.fetchedAtMs) < MODEL_CATALOG_TTL_MS) {
            return cached.models;
        }
        return tryRefreshLiveCatalogQuietly(provider);
    }

    @NonNull
    private List<String> tryRefreshLiveCatalogQuietly(@NonNull AnonymousProviderSpec provider) {
        try {
            String modelsUrl = AiProviderCatalog.resolveEndpoint(
                    provider.baseUrl, provider.baseUrl, "/models", true);
            HttpResult res = AiProviderCatalog.executeHttpRequest(
                    httpsClient, "GET", modelsUrl, provider.anonymousBearerToken, null, 10, testLoopbackMode);
            if (res.statusCode >= 200 && res.statusCode < 300) {
                List<String> parsed = AiProviderCatalog.parseModelIdsFromCatalogJson(
                        res.body, provider.filterFreeModelsOnly);
                if (!parsed.isEmpty()) {
                    liveModelCache.put(provider.id, new CachedModelCatalog(parsed, System.currentTimeMillis()));
                    return parsed;
                }
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // Single HTTP Chat Completion Execution
    // =========================================================================

    private static final class SingleCallOutcome {
        final int statusCode;
        @Nullable
        final String assistantContent;
        final String errorSummary;
        final long retryAfterMs;
        final boolean retryCompatMode;

        SingleCallOutcome(int statusCode,
                          @Nullable String assistantContent,
                          @NonNull String errorSummary,
                          long retryAfterMs,
                          boolean retryCompatMode) {
            this.statusCode = statusCode;
            this.assistantContent = assistantContent;
            this.errorSummary = errorSummary;
            this.retryAfterMs = retryAfterMs;
            this.retryCompatMode = retryCompatMode;
        }
    }

    @NonNull
    private SingleCallOutcome executeChatAttempt(@NonNull AnonymousProviderSpec provider,
                                                 @NonNull String chatUrl,
                                                 @NonNull String modelId,
                                                 @NonNull String systemPrompt,
                                                 @NonNull List<ChatHistoryEntry> contextTurns,
                                                 @NonNull String userPrompt,
                                                 boolean includeJsonFormat,
                                                 boolean includeTemperature) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelId);
        // Set >= 300 tokens so reasoning models do not exhaust their token cap inside <think>
        payload.addProperty("max_tokens", 320);
        if (includeTemperature) {
            payload.addProperty("temperature", 0.35);
        }
        if (includeJsonFormat) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_object");
            payload.add("response_format", rf);
        }

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        if (provider.strictSingleSystemUserOrder) {
            // Gateways such as VLM Run require strictly [system, user] without interleaved history
            StringBuilder mergedUser = new StringBuilder(256);
            if (!contextTurns.isEmpty()) {
                mergedUser.append("Recent conversation:\n");
                for (ChatHistoryEntry t : contextTurns) {
                    mergedUser.append(ChatHistoryEntry.SENDER_AI.equals(t.sender) ? "Assistant: " : "Player: ")
                            .append(t.message).append('\n');
                }
                mergedUser.append("\nCurrent request: ");
            }
            mergedUser.append(userPrompt);
            JsonObject u = new JsonObject();
            u.addProperty("role", "user");
            u.addProperty("content", mergedUser.toString());
            messages.add(u);
        } else {
            for (ChatHistoryEntry turn : contextTurns) {
                JsonObject msg = new JsonObject();
                if (ChatHistoryEntry.SENDER_AI.equals(turn.sender)) {
                    msg.addProperty("role", "assistant");
                    JsonObject structured = new JsonObject();
                    structured.addProperty("say", turn.message);
                    structured.addProperty("action",
                            turn.action != null ? turn.action.name() : "FOLLOW");
                    msg.addProperty("content", structured.toString());
                } else {
                    msg.addProperty("role", "user");
                    msg.addProperty("content", turn.message);
                }
                messages.add(msg);
            }
            JsonObject u = new JsonObject();
            u.addProperty("role", "user");
            u.addProperty("content", userPrompt);
            messages.add(u);
        }
        payload.add("messages", messages);

        HttpResult res = AiProviderCatalog.executeHttpRequest(
                httpsClient,
                "POST",
                chatUrl,
                provider.anonymousBearerToken,
                payload.toString(),
                provider.readTimeoutSeconds,
                testLoopbackMode
        );

        if (res.statusCode >= 200 && res.statusCode < 300) {
            String content = OnlineFreeApiLlmProvider.extractAssistantContentFromOpenAiJson(res.body);
            if (content != null && !content.trim().isEmpty()) {
                return new SingleCallOutcome(res.statusCode, content, "", 0L, false);
            }
            return new SingleCallOutcome(res.statusCode, null, "empty completion content", 400L, false);
        }

        String lowerBody = res.body.toLowerCase(Locale.ROOT);
        if (res.statusCode == 400 && (includeJsonFormat || includeTemperature)
                && (lowerBody.contains("response_format")
                || lowerBody.contains("json_object")
                || lowerBody.contains("json mode")
                || lowerBody.contains("temperature")
                || lowerBody.contains("unsupported"))) {
            return new SingleCallOutcome(400, null, "retrying compat payload", 0L, true);
        }

        String shortErr = OnlineFreeApiLlmProvider.summarizeHttpError(res.statusCode, res.body);
        return new SingleCallOutcome(res.statusCode, null, shortErr, res.retryAfterMs, false);
    }

    // =========================================================================
    // Cooldown & Telemetry Helpers
    // =========================================================================

    private boolean isRouteCoolingDown(@NonNull String routeKey) {
        Long until = routeCooldownUntilMs.get(routeKey);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            routeCooldownUntilMs.remove(routeKey);
            return false;
        }
        return true;
    }

    private void markRouteCooldown(@NonNull String routeKey, long durationMs) {
        routeCooldownUntilMs.put(routeKey, System.currentTimeMillis() + Math.max(2000L, durationMs));
    }

    private void recordSuccess(@NonNull String providerId, @NonNull String modelId, long latencyMs) {
        ProviderHealthSnapshot prev = providerHealth.get(providerId);
        int count = (prev != null ? prev.successCount : 0) + 1;
        providerHealth.put(providerId, new ProviderHealthSnapshot(
                providerId, latencyMs, modelId, null, true, count));
    }

    private void recordFailure(@NonNull String providerId, @NonNull String modelId, @NonNull String error) {
        ProviderHealthSnapshot prev = providerHealth.get(providerId);
        int count = prev != null ? prev.successCount : 0;
        long prevLat = prev != null ? prev.lastLatencyMs : 0L;
        providerHealth.put(providerId, new ProviderHealthSnapshot(
                providerId, prevLat, modelId, error, false, count));
    }

    @NonNull
    private static String joinBrief(@NonNull List<String> items) {
        if (items.isEmpty()) return "no active routes";
        StringBuilder sb = new StringBuilder();
        int cap = Math.min(items.size(), 4);
        for (int i = 0; i < cap; i++) {
            if (i > 0) sb.append("; ");
            sb.append(items.get(i));
        }
        if (items.size() > cap) {
            sb.append(" +").append(items.size() - cap).append(" more");
        }
        return sb.toString();
    }
}
