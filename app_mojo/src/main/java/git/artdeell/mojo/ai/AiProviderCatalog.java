package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.utils.ZyroxHttpClient;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Curated directory of verified Free Anonymous Internet LLM Routers and Free-Tier / Gateway
 * OpenAI-compatible API Presets, plus safe HTTPS + Local/LAN raw-socket HTTP transport.
 */
public final class AiProviderCatalog {

    private static final MediaType MEDIA_JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_SOCKET_RESPONSE_BYTES = 512 * 1024;

    public static final String POLICY_AUTO_CASCADE = "AUTO_CASCADE";
    public static final String CUSTOM_ROUTER_ID = "custom_router";

    private AiProviderCatalog() {
    }

    /** Descriptor for a zero-signup / keyless internet OpenAI-compatible provider. */
    public static final class AnonymousProviderSpec {
        public final String id;
        public final String title;
        public final String baseUrl;
        /** Optional documented anonymous token (e.g. {@code "0000000000"} for AI Horde), or {@code null} when keyless. */
        @Nullable
        public final String anonymousBearerToken;
        public final String quotaSummary;
        public final String privacyNote;
        public final List<String> curatedFreeModels;
        public final boolean rotateModelsRoundRobin;
        public final boolean strictSingleSystemUserOrder;
        public final boolean preferLiveModelsLookup;
        public final boolean filterFreeModelsOnly;
        public final int readTimeoutSeconds;

        public AnonymousProviderSpec(@NonNull String id,
                                     @NonNull String title,
                                     @NonNull String baseUrl,
                                     @Nullable String anonymousBearerToken,
                                     @NonNull String quotaSummary,
                                     @NonNull String privacyNote,
                                     @NonNull List<String> curatedFreeModels,
                                     boolean rotateModelsRoundRobin,
                                     boolean strictSingleSystemUserOrder,
                                     boolean preferLiveModelsLookup,
                                     boolean filterFreeModelsOnly,
                                     int readTimeoutSeconds) {
            this.id = id;
            this.title = title;
            this.baseUrl = baseUrl;
            this.anonymousBearerToken = anonymousBearerToken;
            this.quotaSummary = quotaSummary;
            this.privacyNote = privacyNote;
            this.curatedFreeModels = Collections.unmodifiableList(new ArrayList<>(curatedFreeModels));
            this.rotateModelsRoundRobin = rotateModelsRoundRobin;
            this.strictSingleSystemUserOrder = strictSingleSystemUserOrder;
            this.preferLiveModelsLookup = preferLiveModelsLookup;
            this.filterFreeModelsOnly = filterFreeModelsOnly;
            this.readTimeoutSeconds = readTimeoutSeconds;
        }
    }

    /** Descriptor for a 1-tap BYOK Cloud or Self-Hosted Gateway provider preset. */
    public static final class ApiKeyProviderPreset {
        public final String id;
        public final String title;
        public final String baseUrl;
        public final String defaultModel;
        public final List<String> freeModels;
        public final String freeQuotaBadge;
        public final String keyHint;
        public final boolean keyOptionalForLocalGateway;

        public ApiKeyProviderPreset(@NonNull String id,
                                    @NonNull String title,
                                    @NonNull String baseUrl,
                                    @NonNull String defaultModel,
                                    @NonNull List<String> freeModels,
                                    @NonNull String freeQuotaBadge,
                                    @NonNull String keyHint,
                                    boolean keyOptionalForLocalGateway) {
            this.id = id;
            this.title = title;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.freeModels = Collections.unmodifiableList(new ArrayList<>(freeModels));
            this.freeQuotaBadge = freeQuotaBadge;
            this.keyHint = keyHint;
            this.keyOptionalForLocalGateway = keyOptionalForLocalGateway;
        }
    }

    /** HTTP status + body result shared across OkHttp HTTPS and loopback/LAN socket transport. */
    public static final class HttpResult {
        public final int statusCode;
        public final String body;
        public final long retryAfterMs;

        public HttpResult(int statusCode, @NonNull String body, long retryAfterMs) {
            this.statusCode = statusCode;
            this.body = body;
            this.retryAfterMs = retryAfterMs;
        }
    }

    // =========================================================================
    // 1. Built-in Free Anonymous Internet Providers (Zero Signup / No Account)
    // =========================================================================

    public static final List<AnonymousProviderSpec> ANONYMOUS_PROVIDERS =
            Collections.unmodifiableList(Arrays.asList(
                    new AnonymousProviderSpec(
                            "kilo",
                            "Kilo AI Gateway",
                            "https://ai-gateway.kilo.ai/api/v1",
                            null,
                            "200 req/hr per IP • Keyless Auto-Router (:free)",
                            "Routes free models automatically without signup.",
                            Arrays.asList(
                                    "kilo-auto/free",
                                    "nvidia/nemotron-3.5-super-vl:free",
                                    "nvidia/nemotron-3.5-lightning:free",
                                    "nvidia/nemotron-3-ultra:free"
                            ),
                            false,
                            false,
                            false,
                            true,
                            28
                    ),
                    new AnonymousProviderSpec(
                            "ovhcloud",
                            "OVHcloud AI Endpoints",
                            "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1",
                            null,
                            "2 RPM / model / IP (18+ RPM rotated across 9 EU models)",
                            "EU-hosted • OVHcloud states prompts are not stored.",
                            Arrays.asList(
                                    "Meta-Llama-3_3-70B-Instruct",
                                    "Mistral-Small-3.2-24B-Instruct-2506",
                                    "Qwen3.8-27B",
                                    "Qwen3.6-27B",
                                    "gpt-oss-120b",
                                    "gpt-oss-20b",
                                    "Qwen3.5-9B",
                                    "Mistral-Nemo-Instruct-2407",
                                    "Olala-v1.0-mini-7A1B"
                            ),
                            true,
                            false,
                            false,
                            false,
                            28
                    ),
                    new AnonymousProviderSpec(
                            "pollinations",
                            "Pollinations.AI Open Text",
                            "https://text.pollinations.ai/openai",
                            null,
                            "Keyless Open GenAI (~1–4 RPM per IP)",
                            "Zero-signup community OpenAI text lane.",
                            Arrays.asList(
                                    "gpt-oss-20b",
                                    "openai",
                                    "mistral"
                            ),
                            false,
                            false,
                            false,
                            false,
                            30
                    ),
                    new AnonymousProviderSpec(
                            "vlmrun",
                            "VLM Run OpenAI Gateway",
                            "https://gateway.vlm.run/v1/openai",
                            null,
                            "Anonymous Public Beta • Open-Weight Qwen & Gemma",
                            "Strict single-system + user message order handled automatically.",
                            Arrays.asList(
                                    "qwen/qwen3.8-27b",
                                    "google/gemma4-26b-a4b-it"
                            ),
                            true,
                            true,
                            false,
                            false,
                            28
                    ),
                    new AnonymousProviderSpec(
                            "aihorde",
                            "AI Horde Volunteer Cluster",
                            "https://oai.aihorde.net/v1",
                            "0000000000",
                            "Unlimited Community GPUs • Key 0000000000 (Queue-Based)",
                            "Volunteer workers process prompts; used as resilient last-resort fallback.",
                            Arrays.asList(
                                    "koboldcpp/Qwen2.5-7B-Instruct",
                                    "aphrodite/Meta-Llama-3.1-8B-Instruct"
                            ),
                            false,
                            false,
                            true,
                            false,
                            65
                    )
            ));

    @NonNull
    public static List<String> getDefaultEnabledAnonymousProviderIds() {
        List<String> ids = new ArrayList<>(ANONYMOUS_PROVIDERS.size());
        for (AnonymousProviderSpec spec : ANONYMOUS_PROVIDERS) {
            ids.add(spec.id);
        }
        return ids;
    }

    @Nullable
    public static AnonymousProviderSpec findAnonymousProvider(@Nullable String id) {
        if (id == null) return null;
        String clean = id.trim().toLowerCase(Locale.ROOT);
        for (AnonymousProviderSpec spec : ANONYMOUS_PROVIDERS) {
            if (spec.id.equals(clean)) return spec;
        }
        return null;
    }

    @Nullable
    public static AnonymousProviderSpec buildCustomAnonymousRouterSpec(@Nullable String customUrl,
                                                                       @Nullable String customModel) {
        if (customUrl == null || customUrl.trim().isEmpty()) {
            return null;
        }
        String model = (customModel == null || customModel.trim().isEmpty())
                ? "auto"
                : customModel.trim();
        return new AnonymousProviderSpec(
                CUSTOM_ROUTER_ID,
                "Custom Free Router (OmniRoute / FreeLM)",
                customUrl.trim(),
                null,
                "Self-Hosted / LAN Gateway (" + customUrl.trim() + ")",
                "Direct connection to your own OmniRoute (:20128) or FreeLM (:4000) router.",
                Arrays.asList(model, "free-combo", "kilo-auto/free"),
                false,
                false,
                false,
                false,
                35
        );
    }

    // =========================================================================
    // 2. 21 Curated Free-Tier Cloud & Self-Hosted Gateway API-Key Presets
    // =========================================================================

    public static final List<ApiKeyProviderPreset> API_KEY_PRESETS =
            Collections.unmodifiableList(Arrays.asList(
                    new ApiKeyProviderPreset(
                            "openrouter",
                            "OpenRouter (:free Models)",
                            "https://openrouter.ai/api/v1",
                            "meta-llama/llama-3.3-70b-instruct:free",
                            Arrays.asList(
                                    "meta-llama/llama-3.3-70b-instruct:free",
                                    "qwen/qwen-2.5-72b-instruct:free",
                                    "google/gemma-3-27b-it:free",
                                    "mistralai/mistral-small-3.1-24b-instruct:free",
                                    "deepseek/deepseek-r1:free",
                                    "openrouter/free"
                            ),
                            "20 RPM • 200 RPD • No Card",
                            "Get free key at openrouter.ai/keys (sk-or-v1-...)",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "groq",
                            "Groq Cloud (Ultra-Fast LPU)",
                            "https://api.groq.com/openai/v1",
                            "llama-3.3-70b-versatile",
                            Arrays.asList(
                                    "llama-3.3-70b-versatile",
                                    "openai/gpt-oss-120b",
                                    "openai/gpt-oss-20b",
                                    "qwen/qwen3.8-27b",
                                    "llama-3.1-8b-instant"
                            ),
                            "30 RPM • Up to 14.4K RPD • No Card",
                            "Get free key at console.groq.com/keys (gsk_...)",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "gemini",
                            "Google Gemini (OpenAI Endpoint)",
                            "https://generativelanguage.googleapis.com/v1beta/openai",
                            "gemini-2.5-flash",
                            Arrays.asList(
                                    "gemini-2.5-flash",
                                    "gemini-2.5-pro",
                                    "gemini-2.0-flash"
                            ),
                            "15 RPM • 1,500 RPD • No Card",
                            "Get free key at aistudio.google.com/apikey (AIza...)",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "mistral",
                            "Mistral AI (La Plateforme Free)",
                            "https://api.mistral.ai/v1",
                            "mistral-small-latest",
                            Arrays.asList(
                                    "mistral-small-latest",
                                    "open-mistral-nemo",
                                    "mistral-large-latest",
                                    "codestral-latest"
                            ),
                            "~60 RPM Experiment Plan • No Card",
                            "Get free Experiment key at console.mistral.ai",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "cerebras",
                            "Cerebras Wafer-Scale Inference",
                            "https://api.cerebras.ai/v1",
                            "llama-3.3-70b",
                            Arrays.asList(
                                    "llama-3.3-70b",
                                    "qwen-3-235b-a22b-instruct-2507",
                                    "gpt-oss-120b"
                            ),
                            "30 RPM • 14.4K RPD • Ultra-Fast",
                            "Get key at cloud.cerebras.ai",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "github_models",
                            "GitHub Models (GitHub Account)",
                            "https://models.github.ai/inference",
                            "openai/gpt-4o-mini",
                            Arrays.asList(
                                    "openai/gpt-4o-mini",
                                    "openai/gpt-4o",
                                    "meta/Llama-3.3-70B-Instruct",
                                    "deepseek/DeepSeek-R1"
                            ),
                            "10–15 RPM • 150 RPD • No Card",
                            "Use a GitHub Personal Access Token (github_pat_...)",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "nvidia_nim",
                            "NVIDIA NIM API Catalog",
                            "https://integrate.api.nvidia.com/v1",
                            "meta/llama-3.3-70b-instruct",
                            Arrays.asList(
                                    "meta/llama-3.3-70b-instruct",
                                    "qwen/qwen3-235b-a22b",
                                    "nvidia/llama-3.1-nemotron-70b-instruct"
                            ),
                            "40 RPM Free Prototyping • No Card",
                            "Get free key at build.nvidia.com (nvapi-...)",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "sambanova",
                            "SambaNova Cloud",
                            "https://api.sambanova.ai/v1",
                            "Meta-Llama-3.3-70B-Instruct",
                            Arrays.asList(
                                    "Meta-Llama-3.3-70B-Instruct",
                                    "Qwen3-32B",
                                    "DeepSeek-R1"
                            ),
                            "Free Developer Tier • No Card",
                            "Get free key at cloud.sambanova.ai",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "unorouter",
                            "UnoRouter (190+ :free Models)",
                            "https://api.unorouter.com/v1",
                            "deepseek/deepseek-chat:free",
                            Arrays.asList(
                                    "deepseek/deepseek-chat:free",
                                    "qwen/qwen3.5-397b:free",
                                    "mistralai/mistral-small:free"
                            ),
                            "190+ :free Models • GitHub/Discord Sign-in",
                            "Get free key at unorouter.com",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "requesty",
                            "Requesty Free Router",
                            "https://router.requesty.ai/v1",
                            "nvidia/nemotron-3-ultra:free",
                            Arrays.asList(
                                    "nvidia/nemotron-3-ultra:free",
                                    "meta-llama/llama-3.3-70b-instruct:free"
                            ),
                            "200 RPD Permanent Free • No Card",
                            "Get free key at requesty.ai",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "kilo_key",
                            "Kilo AI Gateway (Account Key)",
                            "https://ai-gateway.kilo.ai/api/v1",
                            "kilo-auto/free",
                            Arrays.asList(
                                    "kilo-auto/free",
                                    "google/gemini-2.5-flash",
                                    "deepseek/deepseek-v3.2"
                            ),
                            "380+ Models + Free Auto Router",
                            "Optional Kilo key (or leave empty on :free models)",
                            true
                    ),
                    new ApiKeyProviderPreset(
                            "ovhcloud_key",
                            "OVHcloud AI Endpoints (400 RPM Key)",
                            "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1",
                            "Meta-Llama-3_3-70B-Instruct",
                            Arrays.asList(
                                    "Meta-Llama-3_3-70B-Instruct",
                                    "Mistral-Small-3.2-24B-Instruct-2506",
                                    "Qwen3.8-27B",
                                    "gpt-oss-120b"
                            ),
                            "400 RPM w/ Key (2 RPM Keyless) • EU Privacy",
                            "OVHcloud AI key (or leave empty for 2 RPM anonymous)",
                            true
                    ),
                    new ApiKeyProviderPreset(
                            "cohere",
                            "Cohere Trial Compatibility API",
                            "https://api.cohere.ai/compatibility/v1",
                            "command-a-03-2025",
                            Arrays.asList(
                                    "command-a-03-2025",
                                    "command-r-plus-08-2024",
                                    "c4ai-aya-expanse-32b"
                            ),
                            "20 RPM • 1,000 Calls/Month • No Card",
                            "Get free Trial key at dashboard.cohere.com",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "zai_zhipu",
                            "Z.ai / Zhipu GLM Flash",
                            "https://open.bigmodel.cn/api/paas/v4",
                            "glm-4.7-flash",
                            Arrays.asList(
                                    "glm-4.7-flash",
                                    "glm-4-flash"
                            ),
                            "Permanent Free Flash Models • No Card",
                            "Get free key at open.bigmodel.cn / z.ai",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "axforge",
                            "AxForge (EU Open Models)",
                            "https://api.axforge.ai/v1",
                            "llama-3.3-70b",
                            Arrays.asList(
                                    "llama-3.3-70b",
                                    "qwen3-32b"
                            ),
                            "3M Tokens / 30 Days • EU Hosted • No Card",
                            "Get free key at axforge.ai",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "airforce",
                            "Api.Airforce Unified Gateway",
                            "https://api.airforce/v1",
                            "gpt-oss-120b",
                            Arrays.asList(
                                    "gpt-oss-120b",
                                    "gpt-oss-20b",
                                    "glm-4.7-flash",
                                    "gpt-4o-mini"
                            ),
                            "13 Permanent Free Models • No Card",
                            "Get free key at api.airforce",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "pollinations_cloud",
                            "Pollinations Cloud (gen.pollinations.ai)",
                            "https://gen.pollinations.ai/v1",
                            "openai/gpt-oss-20b",
                            Arrays.asList(
                                    "openai/gpt-oss-20b",
                                    "deepseek/deepseek-v4-flash",
                                    "moonshotai/kimi-k3"
                            ),
                            "Free Quest Pollen Credits • No Card",
                            "Get key at enter.pollinations.ai/keys",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "huggingface",
                            "Hugging Face Inference Router",
                            "https://router.huggingface.co/v1",
                            "Qwen/Qwen2.5-72B-Instruct",
                            Arrays.asList(
                                    "Qwen/Qwen2.5-72B-Instruct",
                                    "meta-llama/Llama-3.3-70B-Instruct"
                            ),
                            "Monthly Free HF Credits • No Card",
                            "Get User Access Token (hf_...) at huggingface.co/settings/tokens",
                            false
                    ),
                    new ApiKeyProviderPreset(
                            "aihorde_key",
                            "AI Horde (Kudos / Anonymous Key)",
                            "https://oai.aihorde.net/v1",
                            "koboldcpp/Qwen2.5-7B-Instruct",
                            Arrays.asList(
                                    "koboldcpp/Qwen2.5-7B-Instruct",
                                    "aphrodite/Meta-Llama-3.1-8B-Instruct"
                            ),
                            "Priority w/ Free Account Key (or 0000000000)",
                            "Register free at aihorde.net/register or enter 0000000000",
                            true
                    ),
                    new ApiKeyProviderPreset(
                            "omniroute",
                            "OmniRoute Gateway (:20128 Self-Hosted)",
                            "http://127.0.0.1:20128/v1",
                            "free-combo",
                            Arrays.asList(
                                    "free-combo",
                                    "auto",
                                    "gemini-2.5-flash",
                                    "llama-3.3-70b-versatile"
                            ),
                            "394 Providers • 140+ Free • Local/LAN/HTTPS",
                            "OmniRoute sk-... key (or leave empty if REQUIRE_API_KEY=false)",
                            true
                    ),
                    new ApiKeyProviderPreset(
                            "freelm_local",
                            "FreeLM / Free-LLM Local Router (:4000)",
                            "http://127.0.0.1:4000/v1",
                            "auto",
                            Arrays.asList(
                                    "auto",
                                    "kilo-auto/free",
                                    "llama-3.3-70b-versatile"
                            ),
                            "Zero-Key Failover Router on Termux / PC",
                            "Optional FREELM_SERVER_KEY (or leave empty)",
                            true
                    )
            ));

    @Nullable
    public static ApiKeyProviderPreset findPresetByIdOrUrl(@Nullable String presetId,
                                                           @Nullable String baseUrl) {
        if (presetId != null && !presetId.trim().isEmpty()) {
            String clean = presetId.trim().toLowerCase(Locale.ROOT);
            for (ApiKeyProviderPreset p : API_KEY_PRESETS) {
                if (p.id.equals(clean)) return p;
            }
        }
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            String norm = trimTrailingSlashes(baseUrl.trim()).toLowerCase(Locale.ROOT);
            for (ApiKeyProviderPreset p : API_KEY_PRESETS) {
                if (trimTrailingSlashes(p.baseUrl).toLowerCase(Locale.ROOT).equals(norm)) {
                    return p;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // 3. Safe HTTPS + Local/LAN Raw-Socket HTTP Transport & Live /models Probe
    // =========================================================================

    /**
     * Returns {@code true} if {@code url} is an {@code http://} URL targeting loopback
     * ({@code 127.0.0.1}, {@code localhost}) or a private RFC-1918 LAN IP ({@code 10.x},
     * {@code 192.168.x}, {@code 172.16-31.x}) such as a self-hosted OmniRoute or FreeLM server.
     *
     * <p>Public internet hosts MUST always use {@code https://}.</p>
     */
    public static boolean isLocalOrPrivateLanHttpUrl(@Nullable String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("http://")) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h)) {
                return true;
            }
            String[] parts = h.split("\\.");
            if (parts.length == 4) {
                int o1 = Integer.parseInt(parts[0]);
                int o2 = Integer.parseInt(parts[1]);
                int o3 = Integer.parseInt(parts[2]);
                int o4 = Integer.parseInt(parts[3]);
                if (o1 < 0 || o1 > 255 || o2 < 0 || o2 > 255 || o3 < 0 || o3 > 255 || o4 < 0 || o4 > 255) {
                    return false;
                }
                if (o1 == 10 || o1 == 127) return true;
                if (o1 == 192 && o2 == 168) return true;
                if (o1 == 172 && o2 >= 16 && o2 <= 31) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @NonNull
    public static String resolveEndpoint(@Nullable String baseUrl,
                                         @NonNull String defaultBaseUrl,
                                         @NonNull String suffixPath,
                                         boolean allowLoopbackOrLanHttp) throws IOException {
        String base = (baseUrl == null || baseUrl.trim().isEmpty())
                ? defaultBaseUrl
                : baseUrl.trim();
        base = trimTrailingSlashes(base);
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            if (!(allowLoopbackOrLanHttp && isLocalOrPrivateLanHttpUrl(base))) {
                throw new IOException("Remote internet API endpoints must use HTTPS (or a local 127.0.0.1 / 192.168.x.x gateway).");
            }
        }
        if (lower.endsWith(suffixPath.toLowerCase(Locale.ROOT))) {
            return base;
        }
        return base + suffixPath;
    }

    /**
     * Executes an HTTP GET or POST JSON request:
     * uses {@link OkHttpClient} for {@code https://} URLs (and unit-test loopback when injected),
     * and raw {@link Socket} HTTP/1.1 for {@code http://} loopback/LAN self-hosted gateways so
     * {@code res/xml/netconfig.xml} retains {@code cleartextTrafficPermitted="false"}.
     */
    @NonNull
    public static HttpResult executeHttpRequest(@NonNull OkHttpClient httpsClient,
                                                @NonNull String method,
                                                @NonNull String targetUrl,
                                                @Nullable String bearerToken,
                                                @Nullable String jsonBody,
                                                int readTimeoutSec,
                                                boolean preferOkHttpOnLoopbackTest) throws IOException {
        if (!preferOkHttpOnLoopbackTest && isLocalOrPrivateLanHttpUrl(targetUrl)) {
            return executeRawLocalHttpSocket(method, targetUrl, bearerToken, jsonBody, readTimeoutSec);
        }

        Request.Builder rb = new Request.Builder()
                .url(targetUrl)
                .header("Accept", "application/json")
                .header("User-Agent", ZyroxHttpClient.USER_AGENT);
        if (bearerToken != null && !bearerToken.trim().isEmpty()) {
            rb.header("Authorization", "Bearer " + bearerToken.trim());
        }
        if ("POST".equalsIgnoreCase(method)) {
            String payload = jsonBody != null ? jsonBody : "{}";
            rb.header("Content-Type", "application/json");
            rb.post(RequestBody.create(payload, MEDIA_JSON));
        } else {
            rb.get();
        }

        OkHttpClient timedClient = httpsClient.newBuilder()
                .readTimeout(Math.max(8, readTimeoutSec), java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(Math.max(12, readTimeoutSec + 10), java.util.concurrent.TimeUnit.SECONDS)
                .build();

        try (Response response = timedClient.newCall(rb.build()).execute()) {
            int code = response.code();
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            long retryMs = ZyroxHttpClient.parseRetryAfterMs(response, code == 429 ? 1200L : 450L);
            return new HttpResult(code, text, retryMs);
        }
    }

    /**
     * Queries {@code GET <baseUrl>/models} and extracts chat-capable model IDs.
     */
    @NonNull
    public static List<String> fetchRemoteModelIds(@NonNull OkHttpClient httpsClient,
                                                   @NonNull String baseUrl,
                                                   @Nullable String bearerToken,
                                                   boolean filterFreeOnly) throws IOException {
        String modelsUrl = resolveEndpoint(baseUrl, baseUrl, "/models", true);
        HttpResult res = executeHttpRequest(httpsClient, "GET", modelsUrl, bearerToken, null, 15, false);
        if (res.statusCode < 200 || res.statusCode >= 300) {
            throw new IOException("GET /models returned HTTP " + res.statusCode);
        }
        return parseModelIdsFromCatalogJson(res.body, filterFreeOnly);
    }

    @NonNull
    public static List<String> parseModelIdsFromCatalogJson(@Nullable String jsonText,
                                                            boolean filterFreeOnly) {
        List<String> out = new ArrayList<>();
        if (jsonText == null || jsonText.trim().isEmpty()) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(jsonText);
            JsonArray arr = null;
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("data") && obj.get("data").isJsonArray()) {
                    arr = obj.getAsJsonArray("data");
                } else if (obj.has("models") && obj.get("models").isJsonArray()) {
                    arr = obj.getAsJsonArray("models");
                }
            } else if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            }
            if (arr == null) return out;

            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);
                if (!item.isJsonObject()) continue;
                JsonObject m = item.getAsJsonObject();
                if (!m.has("id") || !m.get("id").isJsonPrimitive()) continue;
                String id = m.get("id").getAsString().trim();
                if (id.isEmpty() || isNonChatUtilityModel(id)) continue;

                if (filterFreeOnly) {
                    boolean looksFree = id.toLowerCase(Locale.ROOT).contains(":free")
                            || id.toLowerCase(Locale.ROOT).contains("/free")
                            || isPricingZero(m);
                    if (!looksFree) continue;
                }
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean isPricingZero(@NonNull JsonObject modelObj) {
        if (!modelObj.has("pricing") || !modelObj.get("pricing").isJsonObject()) {
            return false;
        }
        try {
            JsonObject p = modelObj.getAsJsonObject("pricing");
            String promptCost = p.has("prompt") && p.get("prompt").isJsonPrimitive()
                    ? p.get("prompt").getAsString().trim() : "";
            String compCost = p.has("completion") && p.get("completion").isJsonPrimitive()
                    ? p.get("completion").getAsString().trim() : "";
            return ("0".equals(promptCost) || "0.0".equals(promptCost))
                    && ("0".equals(compCost) || "0.0".equals(compCost));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isNonChatUtilityModel(@NonNull String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("embedding")
                || lower.contains("bge-")
                || lower.contains("whisper")
                || lower.contains("nvr-tts")
                || lower.contains("tts-")
                || lower.contains("stable-diffusion")
                || lower.contains("qwen3guard")
                || lower.contains("rerank")
                || lower.contains("moderation");
    }

    @NonNull
    private static HttpResult executeRawLocalHttpSocket(@NonNull String method,
                                                        @NonNull String targetUrl,
                                                        @Nullable String bearerToken,
                                                        @Nullable String jsonBody,
                                                        int readTimeoutSec) throws IOException {
        URI uri = URI.create(targetUrl);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }

        byte[] bodyBytes = ("POST".equalsIgnoreCase(method) && jsonBody != null)
                ? jsonBody.getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        StringBuilder hdr = new StringBuilder(320);
        hdr.append(method.toUpperCase(Locale.ROOT)).append(' ').append(path).append(" HTTP/1.1\r\n");
        hdr.append("Host: ").append(host).append(':').append(port).append("\r\n");
        hdr.append("Accept: application/json\r\n");
        hdr.append("User-Agent: ").append(ZyroxHttpClient.USER_AGENT).append("\r\n");
        if (bearerToken != null && !bearerToken.trim().isEmpty()) {
            hdr.append("Authorization: Bearer ").append(bearerToken.trim()).append("\r\n");
        }
        if ("POST".equalsIgnoreCase(method)) {
            hdr.append("Content-Type: application/json; charset=utf-8\r\n");
            hdr.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        }
        hdr.append("Connection: close\r\n\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(Math.max(8, readTimeoutSec) * 1000);

            OutputStream out = socket.getOutputStream();
            out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
            if (bodyBytes.length > 0) {
                out.write(bodyBytes);
            }
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (buf.size() + n > MAX_SOCKET_RESPONSE_BYTES) {
                    throw new IOException("Local gateway HTTP response exceeded 512 KB cap");
                }
                buf.write(chunk, 0, n);
            }

            String raw = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) {
                throw new IOException("Malformed HTTP response from local gateway " + host + ":" + port);
            }
            String headerBlock = raw.substring(0, split);
            String rawBody = raw.substring(split + 4);
            int status = parseHttpStatus(headerBlock);
            String decodedBody = headerBlock.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")
                    ? decodeChunkedBody(rawBody)
                    : rawBody;
            return new HttpResult(status, decodedBody, 500L);
        }
    }

    private static int parseHttpStatus(@NonNull String headerBlock) {
        int nl = headerBlock.indexOf("\r\n");
        String statusLine = nl >= 0 ? headerBlock.substring(0, nl) : headerBlock;
        String[] parts = statusLine.split("\\s+");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return 500;
    }

    @NonNull
    private static String decodeChunkedBody(@NonNull String rawBody) {
        StringBuilder out = new StringBuilder(rawBody.length());
        int idx = 0;
        while (idx < rawBody.length()) {
            int crlf = rawBody.indexOf("\r\n", idx);
            if (crlf < 0) {
                out.append(rawBody.substring(idx));
                break;
            }
            String hexLen = rawBody.substring(idx, crlf).trim();
            int semi = hexLen.indexOf(';');
            if (semi >= 0) hexLen = hexLen.substring(0, semi).trim();
            int chunkLen;
            try {
                chunkLen = Integer.parseInt(hexLen, 16);
            } catch (NumberFormatException e) {
                out.append(rawBody.substring(idx));
                break;
            }
            if (chunkLen <= 0) break;
            int start = crlf + 2;
            int end = Math.min(rawBody.length(), start + chunkLen);
            out.append(rawBody, start, end);
            idx = end + 2;
        }
        return out.toString();
    }

    @NonNull
    public static String trimTrailingSlashes(@NonNull String s) {
        String res = s;
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
