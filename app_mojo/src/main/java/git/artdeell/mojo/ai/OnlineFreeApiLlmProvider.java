package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cloud LLM provider supporting OpenRouter {@code :free} models and any HTTPS OpenAI-compatible
 * {@code /chat/completions} endpoint.
 *
 * <ul>
 *   <li>Uses {@link ZyroxHttpClient#apiClient()} shared connection pool &amp; dispatcher.</li>
 *   <li>Enforces structured JSON output ({@code {"say":"...","action":"..."}}) with automatic
 *   downgrade if an upstream {@code :free} provider does not support {@code response_format}.</li>
 *   <li>Automatically cascades across configured {@code :free} fallback models when encountering
 *   HTTP {@code 429} rate limits, {@code 404} model unavailability, timeouts, or {@code 5xx} errors.</li>
 * </ul>
 */
public final class OnlineFreeApiLlmProvider implements LlmProvider {

    private static final MediaType MEDIA_JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RECENT_TURNS = 6;
    private static final int MAX_ERROR_PREVIEW = 180;

    private final AiFriendConfigStore configStore;
    private final OkHttpClient httpClient;
    private final boolean allowLoopbackHttpForTest;

    public OnlineFreeApiLlmProvider(@NonNull AiFriendConfigStore configStore) {
        this(configStore, ZyroxHttpClient.apiClient().newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build(), false);
    }

    /** Package-visible constructor used by JUnit tests with a local loopback HTTP server. */
    OnlineFreeApiLlmProvider(@NonNull AiFriendConfigStore configStore,
                             @NonNull OkHttpClient customClient,
                             boolean allowLoopbackHttpForTest) {
        this.configStore = configStore;
        this.httpClient = customClient;
        this.allowLoopbackHttpForTest = allowLoopbackHttpForTest;
    }

    @NonNull
    @Override
    public ProviderMode getProviderMode() {
        return ProviderMode.ONLINE_API;
    }

    @Override
    public boolean isReady() {
        if (!configStore.getDecryptedApiKey().isEmpty()) {
            return true;
        }
        ConfigData cfg = configStore.load();
        if (AiProviderCatalog.isLocalOrPrivateLanHttpUrl(cfg.baseApiUrl)) {
            return true;
        }
        AiProviderCatalog.ApiKeyProviderPreset preset =
                AiProviderCatalog.findPresetByIdOrUrl(cfg.onlinePresetId, cfg.baseApiUrl);
        return preset != null && preset.keyOptionalForLocalGateway;
    }

    @NonNull
    @Override
    public String getStatusSummary() {
        ConfigData cfg = configStore.load();
        if (!isReady()) {
            return "API Key · Key needed";
        }
        return "API Key · " + shortenModelId(cfg.selectedOnlineModel);
    }

    @NonNull
    public List<String> fetchLiveModelsBlocking() throws IOException {
        ConfigData cfg = configStore.load();
        String apiKey = resolveEffectiveBearerKey(cfg);
        return AiProviderCatalog.fetchRemoteModelIds(
                httpClient,
                cfg.baseApiUrl,
                apiKey.isEmpty() ? null : apiKey,
                cfg.baseApiUrl != null && cfg.baseApiUrl.toLowerCase(Locale.ROOT).contains("openrouter.ai"));
    }

    @NonNull
    private String resolveEffectiveBearerKey(@NonNull ConfigData cfg) {
        String key = configStore.getDecryptedApiKey();
        if (!key.isEmpty()) return key;
        if (cfg.baseApiUrl != null && cfg.baseApiUrl.toLowerCase(Locale.ROOT).contains("aihorde.net")) {
            return "0000000000";
        }
        return "";
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
        String apiKey = resolveEffectiveBearerKey(cfg);
        if (apiKey.isEmpty() && !isReady()) {
            throw new IOException("API key is not configured. Paste a key in API Key mode or switch to Free Router mode.");
        }

        String endpointUrl = resolveChatCompletionsEndpoint(cfg.baseApiUrl, true);
        List<String> candidateModels = buildModelCascade(cfg);
        String systemPrompt = AiResponseParser.buildSystemPrompt(
                role, botUsername, ownerUsername, worldContextSummary);
        List<ChatHistoryEntry> contextTurns = AiResponseParser.selectRecentDialogue(
                recentHistory, MAX_RECENT_TURNS);

        long overallStartMs = System.currentTimeMillis();
        List<String> attemptErrors = new ArrayList<>();

        for (int i = 0; i < candidateModels.size(); i++) {
            String modelId = candidateModels.get(i);
            long attemptStartMs = System.currentTimeMillis();
            try {
                AttemptResult result = executeSingleModelAttempt(
                        endpointUrl, apiKey, modelId, systemPrompt, contextTurns, userPrompt, true);
                if (result.retryWithoutJsonFormat) {
                    result = executeSingleModelAttempt(
                            endpointUrl, apiKey, modelId, systemPrompt, contextTurns, userPrompt, false);
                }

                if (result.content != null && !result.content.trim().isEmpty()) {
                    long latency = System.currentTimeMillis() - attemptStartMs;
                    return AiResponseParser.parseModelOutput(
                            result.content,
                            userPrompt,
                            role,
                            modelId,
                            ProviderMode.ONLINE_API,
                            latency
                    );
                }

                attemptErrors.add(shortenModelId(modelId) + ": " + result.failureDetail);
                if (!result.retryableOnNextModel) {
                    // Fatal auth errors (401/403) should surface immediately without spamming fallbacks
                    break;
                }
                if (i + 1 < candidateModels.size()) {
                    sleepQuietly(Math.min(result.suggestedBackoffMs, 1500L));
                }
            } catch (IOException netEx) {
                String safeErr = LogRedactor.redact(netEx.getMessage());
                attemptErrors.add(shortenModelId(modelId) + ": " + (safeErr.isEmpty() ? netEx.getClass().getSimpleName() : safeErr));
                if (i + 1 < candidateModels.size()) {
                    sleepQuietly(350L * (i + 1));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - overallStartMs;
        String joined = joinErrors(attemptErrors);
        throw new IOException("Online LLM request failed after " + elapsed + " ms (" + joined + ")");
    }

    @NonNull
    static List<String> buildModelCascade(@NonNull ConfigData cfg) {
        Set<String> ordered = new LinkedHashSet<>();
        if (cfg.selectedOnlineModel != null && !cfg.selectedOnlineModel.trim().isEmpty()) {
            ordered.add(cfg.selectedOnlineModel.trim());
        }
        if (cfg.autoFallbackFreeModels) {
            if (cfg.fallbackOnlineModels != null) {
                for (String m : cfg.fallbackOnlineModels) {
                    if (m != null && !m.trim().isEmpty()) {
                        ordered.add(m.trim());
                    }
                }
            }
            if (cfg.baseApiUrl != null && cfg.baseApiUrl.toLowerCase(Locale.ROOT).contains("openrouter.ai")) {
                for (String curated : AiFriendConfigStore.CURATED_FREE_MODELS) {
                    ordered.add(curated);
                }
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(AiFriendConfigStore.DEFAULT_ONLINE_MODEL);
        }
        return new ArrayList<>(ordered);
    }

    @NonNull
    static String resolveChatCompletionsEndpoint(@Nullable String baseUrl, boolean allowLoopbackOrLanHttp) throws IOException {
        return AiProviderCatalog.resolveEndpoint(
                baseUrl,
                AiFriendConfigStore.DEFAULT_OPENROUTER_BASE_URL,
                "/chat/completions",
                allowLoopbackOrLanHttp);
    }

    @NonNull
    public static String shortenModelId(@Nullable String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return "unknown";
        String m = modelId.trim();
        int slash = m.indexOf('/');
        if (slash >= 0 && slash + 1 < m.length()) {
            return m.substring(slash + 1);
        }
        return m;
    }

    @NonNull
    private AttemptResult executeSingleModelAttempt(@NonNull String endpointUrl,
                                                    @NonNull String apiKey,
                                                    @NonNull String modelId,
                                                    @NonNull String systemPrompt,
                                                    @NonNull List<ChatHistoryEntry> contextTurns,
                                                    @NonNull String userPrompt,
                                                    boolean includeJsonResponseFormat) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelId);
        payload.addProperty("temperature", 0.35);
        payload.addProperty("max_tokens", 320);

        if (includeJsonResponseFormat) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            payload.add("response_format", responseFormat);
        }

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        for (ChatHistoryEntry turn : contextTurns) {
            JsonObject msg = new JsonObject();
            if (ChatHistoryEntry.SENDER_AI.equals(turn.sender)) {
                msg.addProperty("role", "assistant");
                JsonObject assistantStructured = new JsonObject();
                assistantStructured.addProperty("say", turn.message);
                assistantStructured.addProperty("action",
                        turn.action != null ? turn.action.name() : "FOLLOW");
                msg.addProperty("content", assistantStructured.toString());
            } else {
                msg.addProperty("role", "user");
                msg.addProperty("content", turn.message);
            }
            messages.add(msg);
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        payload.add("messages", messages);

        // Use raw socket HTTP/1.1 automatically for local/LAN gateways (OmniRoute :20128 / FreeLM :4000)
        // when not in unit-test OkHttp loopback mode, keeping netconfig.xml cleartextTrafficPermitted="false".
        if (!allowLoopbackHttpForTest && AiProviderCatalog.isLocalOrPrivateLanHttpUrl(endpointUrl)) {
            AiProviderCatalog.HttpResult sockRes = AiProviderCatalog.executeHttpRequest(
                    httpClient, "POST", endpointUrl, apiKey.isEmpty() ? null : apiKey,
                    payload.toString(), 35, false);
            int code = sockRes.statusCode;
            String bodyText = sockRes.body;
            if (code >= 200 && code < 300) {
                String extracted = extractAssistantContentFromOpenAiJson(bodyText);
                if (extracted != null && !extracted.trim().isEmpty()) {
                    return AttemptResult.success(extracted);
                }
                return AttemptResult.failure("empty completion payload", true, 300L);
            }
            String safeErrPreview = extractErrorSummary(code, bodyText);
            if (code == 400 && includeJsonResponseFormat) {
                return AttemptResult.retryWithoutJsonFormat();
            }
            if (code == 401 || code == 403) {
                return AttemptResult.failure("HTTP " + code + " unauthorized (" + safeErrPreview + ")", false, 0L);
            }
            return AttemptResult.failure("HTTP " + code + " (" + safeErrPreview + ")", true, sockRes.retryAfterMs);
        }

        Request.Builder rb = new Request.Builder()
                .url(endpointUrl)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", ZyroxHttpClient.USER_AGENT)
                .header("HTTP-Referer", "https://github.com/argamerzz9582-debug/ZyroxLauncher")
                .header("X-Title", "ZyroxLauncher AI Friend")
                .post(RequestBody.create(payload.toString(), MEDIA_JSON));
        if (!apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        Request request = rb.build();

        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            ResponseBody body = response.body();
            String bodyText = body != null ? body.string() : "";

            if (code >= 200 && code < 300) {
                String extracted = extractAssistantContentFromOpenAiJson(bodyText);
                if (extracted != null && !extracted.trim().isEmpty()) {
                    return AttemptResult.success(extracted);
                }
                return AttemptResult.failure("empty completion payload", true, 300L);
            }

            String safeErrPreview = extractErrorSummary(code, bodyText);
            if (code == 400 && includeJsonResponseFormat
                    && (bodyText.contains("response_format")
                    || bodyText.contains("json_object")
                    || bodyText.toLowerCase(Locale.ROOT).contains("json mode"))) {
                return AttemptResult.retryWithoutJsonFormat();
            }

            if (code == 401 || code == 403) {
                return AttemptResult.failure("HTTP " + code + " unauthorized — check your API key (" + safeErrPreview + ")", false, 0L);
            }

            if (code == 429 || code == 404 || code == 408 || code >= 500) {
                long parsedRetry = ZyroxHttpClient.parseRetryAfterMs(response.header("Retry-After"));
                long retryMs = parsedRetry > 0L ? parsedRetry : (code == 429 ? 900L : 450L);
                return AttemptResult.failure("HTTP " + code + " (" + safeErrPreview + ")", true, retryMs);
            }

            return AttemptResult.failure("HTTP " + code + " (" + safeErrPreview + ")", true, 300L);
        }
    }

    @Nullable
    static String extractAssistantContentFromOpenAiJson(@Nullable String responseJson) {
        if (responseJson == null || responseJson.trim().isEmpty()) return null;
        try {
            JsonElement rootEl = JsonParser.parseString(responseJson);
            if (!rootEl.isJsonObject()) return null;
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()) return null;
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices.size() == 0) return null;
            JsonElement firstEl = choices.get(0);
            if (!firstEl.isJsonObject()) return null;
            JsonObject first = firstEl.getAsJsonObject();
            if (first.has("message") && first.get("message").isJsonObject()) {
                JsonObject msg = first.getAsJsonObject("message");
                if (msg.has("content") && !msg.get("content").isJsonNull()) {
                    JsonElement contentEl = msg.get("content");
                    if (contentEl.isJsonPrimitive()) {
                        return contentEl.getAsString();
                    }
                }
            }
            if (first.has("text") && first.get("text").isJsonPrimitive()) {
                return first.get("text").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @NonNull
    static String summarizeHttpError(int statusCode, @Nullable String bodyText) {
        return "HTTP " + statusCode + " (" + extractErrorSummary(statusCode, bodyText) + ")";
    }

    @NonNull
    private static String extractErrorSummary(int statusCode, @Nullable String bodyText) {
        if (bodyText == null || bodyText.trim().isEmpty()) {
            return "status " + statusCode;
        }
        String summary = bodyText;
        try {
            JsonElement el = JsonParser.parseString(bodyText);
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("error")) {
                    JsonElement err = obj.get("error");
                    if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                        summary = err.getAsJsonObject().get("message").getAsString();
                    } else if (err.isJsonPrimitive()) {
                        summary = err.getAsString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String redacted = LogRedactor.redact(summary).replaceAll("\\s+", " ").trim();
        if (redacted.length() > MAX_ERROR_PREVIEW) {
            return redacted.substring(0, MAX_ERROR_PREVIEW - 3) + "...";
        }
        return redacted;
    }

    @NonNull
    private static String joinErrors(@NonNull List<String> errors) {
        if (errors.isEmpty()) return "no candidate models succeeded";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(errors.size(), 3);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append("; ");
            sb.append(errors.get(i));
        }
        if (errors.size() > limit) {
            sb.append(" +").append(errors.size() - limit).append(" more");
        }
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0L) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class AttemptResult {
        @Nullable final String content;
        final boolean retryWithoutJsonFormat;
        final boolean retryableOnNextModel;
        final long suggestedBackoffMs;
        @NonNull final String failureDetail;

        private AttemptResult(@Nullable String content,
                              boolean retryWithoutJsonFormat,
                              boolean retryableOnNextModel,
                              long suggestedBackoffMs,
                              @NonNull String failureDetail) {
            this.content = content;
            this.retryWithoutJsonFormat = retryWithoutJsonFormat;
            this.retryableOnNextModel = retryableOnNextModel;
            this.suggestedBackoffMs = suggestedBackoffMs;
            this.failureDetail = failureDetail;
        }

        static AttemptResult success(@NonNull String content) {
            return new AttemptResult(content, false, false, 0L, "");
        }

        static AttemptResult retryWithoutJsonFormat() {
            return new AttemptResult(null, true, true, 0L, "retrying without response_format");
        }

        static AttemptResult failure(@NonNull String detail, boolean retryable, long backoffMs) {
            return new AttemptResult(null, false, retryable, backoffMs, detail);
        }
    }
}
