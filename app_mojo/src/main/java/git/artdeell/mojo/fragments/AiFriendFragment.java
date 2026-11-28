package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ai.AiFriendConfigStore;
import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendManager;
import git.artdeell.mojo.ai.AiProviderCatalog;
import git.artdeell.mojo.ai.AiProviderCatalog.AnonymousProviderSpec;
import git.artdeell.mojo.ai.AiProviderCatalog.ApiKeyProviderPreset;
import git.artdeell.mojo.ai.FreeAnonymousRouterLlmProvider.ProviderHealthSnapshot;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.BotAuthMode;
import git.artdeell.mojo.ai.AiFriendModels.BotConnectionState;
import git.artdeell.mojo.ai.AiFriendModels.BotStatusSnapshot;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ModelLoadState;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.ai.AiFriendModels.ResultCallback;
import git.artdeell.mojo.ai.AiFriendModels.StateListener;
import git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode;
import git.artdeell.mojo.ai.AiFriendModels.TransferProgressListener;
import git.artdeell.mojo.ai.AiFriendTargetResolver;
import git.artdeell.mojo.ai.AiFriendTargetResolver.ResolvedTarget;
import git.artdeell.mojo.ai.GgufModelManager;
import git.artdeell.mojo.ai.GgufModelManager.CatalogModel;
import git.artdeell.mojo.ai.GgufModelManager.DownloadStateSnapshot;
import git.artdeell.mojo.ai.GgufModelManager.LocalModelEntry;
import git.artdeell.mojo.ai.GgufRamAdvisor;
import git.artdeell.mojo.ai.GgufRamAdvisor.FitSafety;
import git.artdeell.mojo.ai.GgufRamAdvisor.ModelFitAssessment;
import git.artdeell.mojo.ai.GgufRamAdvisor.RamReport;
import git.artdeell.mojo.ai.LlamaCppRuntimeBridge.ActiveModelSession;
import git.artdeell.mojo.ai.OnlineFreeApiLlmProvider;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.servers.ServerListManager;
import git.artdeell.mojo.servers.ServerPinger;
import git.artdeell.mojo.servers.ZyroxRoomBridge;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Full-featured Frosted Liquid Glass / Dark OLED <b>AI Friend Studio</b> fragment:
 * <ul>
 *   <li><b>Tab 1 — AI Brain &amp; Models:</b> switch between Online Free API (OpenRouter {@code :free}
 *   &amp; OpenAI-compatible endpoints) and Local GGUF models; inspect real-time RAM diagnostics &amp;
 *   recommended quantization tier; import {@code .gguf} files via SAF {@code OpenDocument}; download
 *   curated models with resume/cancel progress; explicitly Load/Unload models; and toggle Auto-Unload
 *   on Game Launch.</li>
 *   <li><b>Tab 2 — World &amp; Bot Bridge:</b> choose AI role ({@code Companion}, {@code Bodyguard},
 *   {@code Miner}, {@code Builder}, {@code Scout}), target connection mode ({@code LAN World},
 *   {@code Zyrox Self-Hosted ZYX-XXXX}, {@code Saved Server}), offline vs. Microsoft device-code auth,
 *   and summon/dismiss the Companion Bot Bridge worker.</li>
 *   <li><b>Tab 3 — Command &amp; Chat Console:</b> live conversation log, 1-tap tactical action chips,
 *   and natural-language prompt bar.</li>
 * </ul>
 */
public class AiFriendFragment extends Fragment implements StateListener {

    public static final String TAG = "AI_FRIEND_FRAGMENT";
    public static final String ARG_PRESET_TARGET_MODE = "preset_target_mode";
    public static final String ARG_PRESET_ADDRESS = "preset_address";
    public static final String ARG_PRESET_ROOM_CODE = "preset_room_code";
    public static final String ARG_INITIAL_TAB = "initial_tab";

    public static final int TAB_BRAIN_MODELS = 0;
    public static final int TAB_WORLD_BRIDGE = 1;
    public static final int TAB_COMMAND_CONSOLE = 2;

    private final ActivityResultLauncher<String[]> mGgufImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    new androidx.activity.result.ActivityResultCallback<Uri>() {
                        @Override
                        public void onActivityResult(Uri uri) {
                            onGgufDocumentPicked(uri);
                        }
                    });

    private AiFriendManager mManager;
    private int mActiveTab = TAB_BRAIN_MODELS;

    // Top live status badges & tab buttons
    private TextView mBadgeProvider;
    private TextView mBadgeModelState;
    private TextView mBadgeBotState;
    private Button mTabBtnBrain;
    private Button mTabBtnWorld;
    private Button mTabBtnConsole;

    // Tab containers
    private ScrollView mScrollBrain;
    private ScrollView mScrollWorld;
    private LinearLayout mPaneConsole;

    // Dynamic rebuild containers inside Brain & World tabs
    private LinearLayout mBrainContentHost;
    private LinearLayout mWorldContentHost;
    private LinearLayout mConsoleHistoryContainer;
    private ScrollView mConsoleScroll;
    private EditText mConsolePromptInput;
    private TextView mImportProgressBanner;

    @NonNull
    public static Bundle createPresetArgs(@NonNull TargetConnectionMode mode,
                                          @Nullable String address,
                                          @Nullable String roomCode,
                                          int initialTab) {
        Bundle b = new Bundle();
        b.putString(ARG_PRESET_TARGET_MODE, mode.name());
        if (address != null) b.putString(ARG_PRESET_ADDRESS, address);
        if (roomCode != null) b.putString(ARG_PRESET_ROOM_CODE, roomCode);
        b.putInt(ARG_INITIAL_TAB, initialTab);
        return b;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        mManager = AiFriendManager.getInstance(ctx);
        applyPresetArgumentsIfPresent();

        LinearLayout outer = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout root = outer.findViewById(android.R.id.content);

        // 1. Live Status Header Strip
        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = ZyroxStudioUi.dp(ctx, 6);
        statusRow.setLayoutParams(statusLp);

        mBadgeProvider = ZyroxStudioUi.createBadge(ctx, "ONLINE API", false);
        mBadgeModelState = ZyroxStudioUi.createBadge(ctx, "UNLOADED", false);
        mBadgeBotState = ZyroxStudioUi.createBadge(ctx, "BRIDGE OFFLINE", false);

        LinearLayout.LayoutParams badgeGap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeGap.rightMargin = ZyroxStudioUi.dp(ctx, 6);
        mBadgeProvider.setLayoutParams(badgeGap);

        LinearLayout.LayoutParams badgeGap2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeGap2.rightMargin = ZyroxStudioUi.dp(ctx, 6);
        mBadgeModelState.setLayoutParams(badgeGap2);

        statusRow.addView(mBadgeProvider);
        statusRow.addView(mBadgeModelState);
        statusRow.addView(mBadgeBotState);
        root.addView(statusRow);

        // 2. Segmented Tab Bar
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabRowLp.bottomMargin = ZyroxStudioUi.dp(ctx, 8);
        tabRow.setLayoutParams(tabRowLp);

        mTabBtnBrain = ZyroxStudioUi.createTabButton(ctx, "AI BRAIN & GGUF");
        mTabBtnWorld = ZyroxStudioUi.createTabButton(ctx, "WORLD & BOT");
        mTabBtnConsole = ZyroxStudioUi.createTabButton(ctx, "LIVE CONSOLE");

        addWeightedView(tabRow, mTabBtnBrain, 1f, 4);
        addWeightedView(tabRow, mTabBtnWorld, 1f, 4);
        addWeightedView(tabRow, mTabBtnConsole, 1f, 0);

        mTabBtnBrain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_BRAIN_MODELS);
            }
        });
        mTabBtnWorld.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_WORLD_BRIDGE);
            }
        });
        mTabBtnConsole.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_COMMAND_CONSOLE);
            }
        });
        root.addView(tabRow);

        // Optional SAF import progress banner
        mImportProgressBanner = ZyroxStudioUi.createSubtitle(ctx, "");
        mImportProgressBanner.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_amber));
        mImportProgressBanner.setVisibility(View.GONE);
        root.addView(mImportProgressBanner);

        // 3. Tab Panes
        mScrollBrain = new ScrollView(ctx);
        mScrollBrain.setFillViewport(true);
        mBrainContentHost = new LinearLayout(ctx);
        mBrainContentHost.setOrientation(LinearLayout.VERTICAL);
        mScrollBrain.addView(mBrainContentHost);

        mScrollWorld = new ScrollView(ctx);
        mScrollWorld.setFillViewport(true);
        mWorldContentHost = new LinearLayout(ctx);
        mWorldContentHost.setOrientation(LinearLayout.VERTICAL);
        mScrollWorld.addView(mWorldContentHost);

        mPaneConsole = buildConsolePane(ctx);

        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(mScrollBrain, bodyLp);
        root.addView(mScrollWorld, bodyLp);
        root.addView(mPaneConsole, bodyLp);

        switchTab(mActiveTab);
        refreshAllUi();
        return outer;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mManager != null) {
            mManager.addStateListener(this);
            refreshAllUi();
        }
    }

    @Override
    public void onStop() {
        if (mManager != null) {
            mManager.removeStateListener(this);
        }
        super.onStop();
    }

    /** Invoked by {@code LauncherActivity}'s contextual top-right header action button. */
    public void onGlobalActionTriggered() {
        if (mManager == null || !isAdded()) return;
        if (mManager.getBridgeClient().isBridgeSocketOpen()) {
            mManager.dismissBotAsync(new ResultCallback<Boolean>() {
                @Override
                public void onSuccess(@NonNull Boolean result) {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "AI Friend bot dismissed", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(@NonNull String message) {
                }
            });
        } else {
            triggerSummonBot();
        }
    }

    @Override
    public void onAiFriendStateChanged() {
        if (!isAdded()) return;
        refreshAllUi();
    }

    private void applyPresetArgumentsIfPresent() {
        Bundle args = getArguments();
        if (args == null) return;
        if (args.containsKey(ARG_INITIAL_TAB)) {
            mActiveTab = args.getInt(ARG_INITIAL_TAB, TAB_BRAIN_MODELS);
            args.remove(ARG_INITIAL_TAB);
        }
        String modeStr = args.getString(ARG_PRESET_TARGET_MODE, null);
        if (modeStr != null) {
            ConfigData cfg = mManager.getConfigStore().load();
            TargetConnectionMode mode = TargetConnectionMode.fromWire(modeStr);
            cfg.targetMode = mode.name();
            String addr = args.getString(ARG_PRESET_ADDRESS, null);
            String code = args.getString(ARG_PRESET_ROOM_CODE, null);
            if (mode == TargetConnectionMode.LAN_WORLD && addr != null && !addr.isEmpty()) {
                cfg.lanAddress = addr;
            } else if (mode == TargetConnectionMode.SAVED_SERVER && addr != null && !addr.isEmpty()) {
                cfg.savedServerAddress = addr;
            } else if (mode == TargetConnectionMode.ZYROX_SELF_HOSTED && code != null && !code.isEmpty()) {
                cfg.zyroxRoomCode = code;
            }
            mManager.getConfigStore().save(cfg);
            args.remove(ARG_PRESET_TARGET_MODE);
        }
    }

    private void switchTab(int tab) {
        mActiveTab = tab;
        mScrollBrain.setVisibility(tab == TAB_BRAIN_MODELS ? View.VISIBLE : View.GONE);
        mScrollWorld.setVisibility(tab == TAB_WORLD_BRIDGE ? View.VISIBLE : View.GONE);
        mPaneConsole.setVisibility(tab == TAB_COMMAND_CONSOLE ? View.VISIBLE : View.GONE);

        mTabBtnBrain.setAlpha(tab == TAB_BRAIN_MODELS ? 1.0f : 0.68f);
        mTabBtnWorld.setAlpha(tab == TAB_WORLD_BRIDGE ? 1.0f : 0.68f);
        mTabBtnConsole.setAlpha(tab == TAB_COMMAND_CONSOLE ? 1.0f : 0.68f);
    }

    private void refreshAllUi() {
        if (!isAdded() || mManager == null) return;
        Context ctx = requireContext();
        ConfigData cfg = mManager.getConfigStore().load();
        ProviderMode providerMode = ProviderMode.fromWire(cfg.providerMode);
        GgufModelManager modelMgr = mManager.getModelManager();
        BotStatusSnapshot botSnap = mManager.getBridgeClient().getLatestStatus();

        // Update top status badges across all 3 AI Brain modes
        if (providerMode == ProviderMode.FREE_ANONYMOUS) {
            styleBadge(ctx, mBadgeProvider, "FREE ROUTER", 1);
        } else if (providerMode == ProviderMode.ONLINE_API) {
            styleBadge(ctx, mBadgeProvider, "API KEY", mManager.getOnlineProvider().isReady() ? 1 : 2);
        } else {
            styleBadge(ctx, mBadgeProvider, "LOCAL MODEL",
                    modelMgr.getRuntimeBridge().isModelLoaded() ? 1 : 0);
        }

        ModelLoadState loadState = modelMgr.getLoadState();
        int modelTone = loadState == ModelLoadState.LOADED ? 1
                : (loadState == ModelLoadState.ERROR ? 3 : 0);
        String loadedFile = modelMgr.getLoadedModelFileName();
        String modelBadgeTxt = loadState == ModelLoadState.LOADED && loadedFile != null
                ? "LOADED • " + truncateMiddle(loadedFile, 18)
                : loadState.badgeText;
        styleBadge(ctx, mBadgeModelState, modelBadgeTxt, modelTone);

        int botTone = botSnap.state == BotConnectionState.CONNECTED ? 1
                : (botSnap.state == BotConnectionState.CONNECTING || botSnap.state == BotConnectionState.AUTH_REQUIRED ? 2
                : (botSnap.state == BotConnectionState.ERROR ? 3 : 0));
        styleBadge(ctx, mBadgeBotState, botSnap.state.badgeText, botTone);

        rebuildBrainPane(ctx, cfg, providerMode, modelMgr);
        rebuildWorldPane(ctx, cfg, botSnap);
        rebuildConsoleHistory(ctx);

        if (getActivity() instanceof git.artdeell.mojo.LauncherActivity) {
            ((git.artdeell.mojo.LauncherActivity) getActivity()).refreshGlobalHeaderActionLabel();
        }
    }

    // =========================================================================
    // TAB 1: AI BRAIN & MODELS (3 Modes: Free Internet Router | API Key | Local Model)
    // =========================================================================

    private void rebuildBrainPane(@NonNull final Context ctx,
                                  @NonNull final ConfigData cfg,
                                  @NonNull final ProviderMode providerMode,
                                  @NonNull final GgufModelManager modelMgr) {
        mBrainContentHost.removeAllViews();

        // 1. 3-Mode AI Brain Switcher Card
        LinearLayout switcherCard = ZyroxStudioUi.createItemCard(ctx);
        switcherCard.addView(ZyroxStudioUi.createSectionLabel(ctx, "AI BRAIN MODE (3 ENGINES)"));
        switcherCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Choose Free Anonymous Internet Routing (zero signup/key), API Key Cloud & Gateways (21 free-tier presets), or Offline Local GGUF Models."));

        LinearLayout modeRow = new LinearLayout(ctx);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, 0);

        Button btnFreeAnon = providerMode == ProviderMode.FREE_ANONYMOUS
                ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ FREE ROUTER")
                : ZyroxStudioUi.createSecondaryButton(ctx, "FREE ROUTER", true);
        Button btnOnline = providerMode == ProviderMode.ONLINE_API
                ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ API KEY")
                : ZyroxStudioUi.createSecondaryButton(ctx, "API KEY", true);
        Button btnLocal = providerMode == ProviderMode.LOCAL_GGUF
                ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ LOCAL MODEL")
                : ZyroxStudioUi.createSecondaryButton(ctx, "LOCAL MODEL", true);

        addWeightedView(modeRow, btnFreeAnon, 1.1f, 5);
        addWeightedView(modeRow, btnOnline, 0.95f, 5);
        addWeightedView(modeRow, btnLocal, 1.05f, 0);

        btnFreeAnon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigData next = mManager.getConfigStore().load();
                next.providerMode = ProviderMode.FREE_ANONYMOUS.name();
                mManager.getConfigStore().save(next);
                refreshAllUi();
            }
        });
        btnOnline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigData next = mManager.getConfigStore().load();
                next.providerMode = ProviderMode.ONLINE_API.name();
                mManager.getConfigStore().save(next);
                refreshAllUi();
            }
        });
        btnLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigData next = mManager.getConfigStore().load();
                next.providerMode = ProviderMode.LOCAL_GGUF.name();
                mManager.getConfigStore().save(next);
                refreshAllUi();
            }
        });
        switcherCard.addView(modeRow);
        mBrainContentHost.addView(switcherCard);

        // Render dedicated view for the selected mode
        if (providerMode == ProviderMode.FREE_ANONYMOUS) {
            appendFreeAnonymousRouterSection(ctx, cfg);
        } else if (providerMode == ProviderMode.ONLINE_API) {
            appendOnlineApiKeySection(ctx, cfg);
        } else {
            appendLocalGgufSection(ctx, cfg, modelMgr);
        }
    }

    private void appendFreeAnonymousRouterSection(@NonNull final Context ctx,
                                                  @NonNull final ConfigData cfg) {
        LinearLayout routerCard = ZyroxStudioUi.createItemCard(ctx);
        routerCard.addView(createHorizontalHeader(ctx,
                "FREE ANONYMOUS INTERNET MODELS ROUTER",
                "ZERO SIGNUP • NO KEY",
                false));

        String policyDesc;
        if (AiProviderCatalog.POLICY_AUTO_CASCADE.equalsIgnoreCase(cfg.anonymousRoutingPolicy)) {
            policyDesc = "Auto Smart Cascade (Kilo -> OVHcloud 9-Model Rotation -> Pollinations -> VLM Run -> AI Horde)";
        } else if (AiProviderCatalog.CUSTOM_ROUTER_ID.equalsIgnoreCase(cfg.anonymousRoutingPolicy)) {
            policyDesc = "Pinned: Custom Free Router (" + cfg.customFreeRouterUrl + ")";
        } else {
            AnonymousProviderSpec pinned = AiProviderCatalog.findAnonymousProvider(cfg.anonymousRoutingPolicy);
            policyDesc = "Pinned: " + (pinned != null ? pinned.title : cfg.anonymousRoutingPolicy);
        }

        routerCard.addView(ZyroxStudioUi.createSubtitle(ctx, "Routing Policy: " + policyDesc));
        routerCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Live Status: " + mManager.getFreeAnonymousProvider().getLastWinningSummary()));
        if (!cfg.customFreeRouterUrl.isEmpty()) {
            routerCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                    "Self-Hosted Keyless Router: " + cfg.customFreeRouterUrl
                            + " (" + cfg.customFreeRouterModel + ")"));
        }

        SwitchCompat swCrossFallback = createStyledSwitch(ctx,
                "Auto-Failover to Next Free Provider on HTTP 429 / Queue / Timeout",
                cfg.anonymousAllowCrossProviderFallback);
        swCrossFallback.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ConfigData next = mManager.getConfigStore().load();
                next.anonymousAllowCrossProviderFallback = isChecked;
                mManager.getConfigStore().save(next);
            }
        });
        routerCard.addView(swCrossFallback);

        LinearLayout actionsRow = new LinearLayout(ctx);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, 0);

        Button btnStrategy = ZyroxStudioUi.createSecondaryButton(ctx, "ROUTING MODE", true);
        Button btnCustomRouter = ZyroxStudioUi.createSecondaryButton(ctx, "OMNIROUTE / LOCAL", true);
        Button btnTestAnon = ZyroxStudioUi.createPrimaryButton(ctx, "TEST ROUTER");

        addWeightedView(actionsRow, btnStrategy, 1f, 5);
        addWeightedView(actionsRow, btnCustomRouter, 1.05f, 5);
        addWeightedView(actionsRow, btnTestAnon, 0.95f, 0);

        btnStrategy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPickAnonymousRoutingStrategyDialog();
            }
        });
        btnCustomRouter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigureCustomFreeRouterDialog();
            }
        });
        btnTestAnon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTestInference(ProviderMode.FREE_ANONYMOUS);
            }
        });
        routerCard.addView(actionsRow);
        mBrainContentHost.addView(routerCard);

        // Pool of all built-in anonymous internet providers
        LinearLayout poolCard = ZyroxStudioUi.createItemCard(ctx);
        poolCard.addView(ZyroxStudioUi.createSectionLabel(ctx,
                "ANONYMOUS INTERNET PROVIDERS POOL (" + AiProviderCatalog.ANONYMOUS_PROVIDERS.size() + " NETWORKS)"));
        poolCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Toggle which free anonymous networks participate in internet auto-routing or inspect their live /models catalog."));

        for (final AnonymousProviderSpec spec : AiProviderCatalog.ANONYMOUS_PROVIDERS) {
            LinearLayout pRow = new LinearLayout(ctx);
            pRow.setOrientation(LinearLayout.VERTICAL);
            pRow.setPadding(0, ZyroxStudioUi.dp(ctx, 8), 0, ZyroxStudioUi.dp(ctx, 4));

            ProviderHealthSnapshot health = mManager.getFreeAnonymousProvider().getHealthSnapshot(spec.id);
            boolean enabled = cfg.enabledAnonymousProviders == null
                    || cfg.enabledAnonymousProviders.isEmpty()
                    || cfg.enabledAnonymousProviders.contains(spec.id);

            String badgeText;
            boolean warnBadge = false;
            if (!enabled) {
                badgeText = "DISABLED";
            } else if (health != null && health.healthy) {
                badgeText = "OK • " + health.lastLatencyMs + "ms";
            } else if (health != null && health.lastError != null) {
                badgeText = "STANDBY / 429";
                warnBadge = true;
            } else {
                badgeText = spec.anonymousBearerToken != null ? "KEY: " + spec.anonymousBearerToken : "KEYLESS";
            }

            pRow.addView(createHorizontalHeader(ctx, spec.title, badgeText, warnBadge));
            pRow.addView(ZyroxStudioUi.createSubtitle(ctx,
                    spec.baseUrl + "  •  " + spec.quotaSummary));
            pRow.addView(ZyroxStudioUi.createSubtitle(ctx,
                    "Default Free Models: " + TextUtils.join(", ", spec.curatedFreeModels)
                            + "  •  " + spec.privacyNote));

            LinearLayout ctrlRow = new LinearLayout(ctx);
            ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
            ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
            ctrlRow.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, 0);

            SwitchCompat swEnable = createStyledSwitch(ctx, "Include in Auto-Router", enabled);
            swEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    ConfigData next = mManager.getConfigStore().load();
                    List<String> list = new ArrayList<>(next.enabledAnonymousProviders != null
                            ? next.enabledAnonymousProviders
                            : AiProviderCatalog.getDefaultEnabledAnonymousProviderIds());
                    if (isChecked) {
                        if (!list.contains(spec.id)) list.add(spec.id);
                    } else {
                        list.remove(spec.id);
                    }
                    next.enabledAnonymousProviders = list;
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                }
            });

            Button btnPin = ZyroxStudioUi.createSecondaryButton(ctx, "PIN FIRST", true);
            Button btnLiveModels = ZyroxStudioUi.createSecondaryButton(ctx, "LIVE /models", true);

            addWeightedView(ctrlRow, swEnable, 1.35f, 5);
            addWeightedView(ctrlRow, btnPin, 0.8f, 5);
            addWeightedView(ctrlRow, btnLiveModels, 0.85f, 0);

            btnPin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.anonymousRoutingPolicy = spec.id;
                    if (!next.enabledAnonymousProviders.contains(spec.id)) {
                        next.enabledAnonymousProviders.add(spec.id);
                    }
                    mManager.getConfigStore().save(next);
                    Toast.makeText(ctx, "Pinned " + spec.title + " as primary free route", Toast.LENGTH_SHORT).show();
                    refreshAllUi();
                }
            });
            btnLiveModels.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    probeAndShowLiveModelsDialog(spec.id, spec.title);
                }
            });

            pRow.addView(ctrlRow);
            poolCard.addView(pRow);
        }
        mBrainContentHost.addView(poolCard);
    }

    private void appendOnlineApiKeySection(@NonNull final Context ctx,
                                           @NonNull final ConfigData cfg) {
        LinearLayout onlineCard = ZyroxStudioUi.createItemCard(ctx);
        boolean ready = mManager.getOnlineProvider().isReady();
        ApiKeyProviderPreset activePreset =
                AiProviderCatalog.findPresetByIdOrUrl(cfg.onlinePresetId, cfg.baseApiUrl);

        String headerTitle = activePreset != null
                ? "API KEY MODE • " + activePreset.title.toUpperCase(Locale.ROOT)
                : "API KEY MODE • CUSTOM OPENAI ENDPOINT";
        onlineCard.addView(createHorizontalHeader(ctx,
                headerTitle,
                ready ? "READY" : "KEY NEEDED",
                !ready));

        if (activePreset != null) {
            onlineCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                    activePreset.freeQuotaBadge + "  •  " + activePreset.keyHint));
        }
        onlineCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Endpoint: " + cfg.baseApiUrl + "  •  Key: " + mManager.getConfigStore().getMaskedApiKeyPreview()));
        onlineCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Primary Model: " + cfg.selectedOnlineModel
                        + (cfg.autoFallbackFreeModels ? "  •  Auto 429/5xx Fallback ON" : "")));

        // Quick scrollable 1-tap preset bar for top free-tier API-key providers & self-hosted gateways
        onlineCard.addView(ZyroxStudioUi.createSubtitle(ctx, "1-Tap Free-Tier Provider & Gateway Presets:"));
        HorizontalScrollView presetScroll = new HorizontalScrollView(ctx);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, ZyroxStudioUi.dp(ctx, 4));

        String[] featuredIds = {
                "openrouter", "groq", "gemini", "mistral", "cerebras",
                "github_models", "nvidia_nim", "unorouter", "omniroute", "freelm_local"
        };
        for (int i = 0; i < featuredIds.length; i++) {
            final ApiKeyProviderPreset preset = AiProviderCatalog.findPresetByIdOrUrl(featuredIds[i], null);
            if (preset == null) continue;
            boolean isCurrent = activePreset != null && preset.id.equals(activePreset.id);
            Button chip = isCurrent
                    ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ " + shortPresetChipName(preset))
                    : ZyroxStudioUi.createSecondaryButton(ctx, shortPresetChipName(preset), true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34));
            lp.rightMargin = ZyroxStudioUi.dp(ctx, 6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyApiKeyPreset(preset);
                }
            });
            chipRow.addView(chip);
        }
        presetScroll.addView(chipRow);
        onlineCard.addView(presetScroll);

        LinearLayout onlineActions = new LinearLayout(ctx);
        onlineActions.setOrientation(LinearLayout.HORIZONTAL);
        onlineActions.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, 0);

        Button btnAllPresets = ZyroxStudioUi.createSecondaryButton(ctx,
                "ALL " + AiProviderCatalog.API_KEY_PRESETS.size() + " PRESETS", true);
        Button btnConfigApi = ZyroxStudioUi.createSecondaryButton(ctx, "KEY & URL", true);
        Button btnPickFreeModel = ZyroxStudioUi.createSecondaryButton(ctx, "MODELS", true);
        Button btnTestOnline = ZyroxStudioUi.createPrimaryButton(ctx, "TEST API");

        addWeightedView(onlineActions, btnAllPresets, 1.05f, 5);
        addWeightedView(onlineActions, btnConfigApi, 0.95f, 5);
        addWeightedView(onlineActions, btnPickFreeModel, 0.85f, 5);
        addWeightedView(onlineActions, btnTestOnline, 0.85f, 0);

        btnAllPresets.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPickApiKeyPresetDialog();
            }
        });
        btnConfigApi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigureOnlineApiDialog();
            }
        });
        btnPickFreeModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPickFreeModelDialog();
            }
        });
        btnTestOnline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTestInference(ProviderMode.ONLINE_API);
            }
        });
        onlineCard.addView(onlineActions);
        mBrainContentHost.addView(onlineCard);
    }

    private void appendLocalGgufSection(@NonNull final Context ctx,
                                        @NonNull final ConfigData cfg,
                                        @NonNull final GgufModelManager modelMgr) {
        // 3. RAM-Based Model Advisor Card
        final RamReport ram = GgufRamAdvisor.inspectDevice(ctx);
        LinearLayout ramCard = ZyroxStudioUi.createItemCard(ctx);
        ramCard.addView(createHorizontalHeader(ctx,
                "DEVICE RAM DIAGNOSTICS & GGUF ADVISOR",
                ram.tier.recommendedRange,
                ram.systemLowMemory));
        ramCard.addView(ZyroxStudioUi.createTitle(ctx, ram.tier.title));
        ramCard.addView(ZyroxStudioUi.createSubtitle(ctx, ram.summaryLine));

        TextView warnView = ZyroxStudioUi.createSubtitle(ctx, ram.warningBanner);
        warnView.setTextColor(ContextCompat.getColor(ctx,
                ram.systemLowMemory ? R.color.zyrox_danger : R.color.zyrox_amber));
        warnView.setPadding(0, ZyroxStudioUi.dp(ctx, 3), 0, 0);
        ramCard.addView(warnView);
        mBrainContentHost.addView(ramCard);

        // 4. Explicit Load / Unload Lifecycle + SAF Import + Auto-Unload on Game Launch Card
        LinearLayout lifecycleCard = ZyroxStudioUi.createItemCard(ctx);
        boolean isLoaded = modelMgr.getRuntimeBridge().isModelLoaded();
        lifecycleCard.addView(createHorizontalHeader(ctx,
                "LOCAL GGUF RUNTIME & MEMORY LIFECYCLE",
                isLoaded ? "LOADED IN RAM" : "UNLOADED",
                false));

        String selectedLabel = cfg.selectedGgufFileName.isEmpty()
                ? "None selected yet (import or download below)"
                : cfg.selectedGgufFileName;
        lifecycleCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Selected Model: " + selectedLabel));
        lifecycleCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Status: " + modelMgr.getStatusMessage()));
        lifecycleCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Engine: " + modelMgr.getRuntimeBridge().getRuntimeDiagnostic(mManager.getConfigStore())));

        SwitchCompat autoUnloadSwitch = createStyledSwitch(ctx,
                "Auto-Unload Local GGUF on Game Launch (Frees RAM for Minecraft)",
                cfg.autoUnloadOnGameLaunch);
        autoUnloadSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ConfigData next = mManager.getConfigStore().load();
                next.autoUnloadOnGameLaunch = isChecked;
                mManager.getConfigStore().save(next);
            }
        });
        lifecycleCard.addView(autoUnloadSwitch);

        LinearLayout loadRow = new LinearLayout(ctx);
        loadRow.setOrientation(LinearLayout.HORIZONTAL);
        loadRow.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, 0);

        Button btnImportGguf = ZyroxStudioUi.createSecondaryButton(ctx, "IMPORT .GGUF", true);
        Button btnLoadModel = ZyroxStudioUi.createPrimaryButton(ctx, isLoaded ? "RELOAD MODEL" : "LOAD MODEL");
        Button btnUnloadModel = ZyroxStudioUi.createDangerButton(ctx, "UNLOAD RAM", true);
        btnUnloadModel.setEnabled(isLoaded);
        btnUnloadModel.setAlpha(isLoaded ? 1.0f : 0.45f);

        addWeightedView(loadRow, btnImportGguf, 1f, 5);
        addWeightedView(loadRow, btnLoadModel, 1f, 5);
        addWeightedView(loadRow, btnUnloadModel, 0.9f, 0);

        btnImportGguf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    mGgufImportLauncher.launch(new String[]{"*/*"});
                } catch (Exception e) {
                    Toast.makeText(ctx, "Unable to open document picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        btnLoadModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerLoadModelWithFitCheck(null, ram);
            }
        });
        btnUnloadModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                modelMgr.unloadModelAsync(new ResultCallback<Boolean>() {
                    @Override
                    public void onSuccess(@NonNull Boolean released) {
                        if (isAdded()) {
                            Toast.makeText(ctx, "Unloaded local GGUF model and freed RAM", Toast.LENGTH_SHORT).show();
                            refreshAllUi();
                        }
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        if (isAdded()) {
                            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
        lifecycleCard.addView(loadRow);
        mBrainContentHost.addView(lifecycleCard);

        // 5. Installed Local .GGUF Files Card (if any imported/downloaded)
        List<LocalModelEntry> installed = modelMgr.listInstalledModels();
        if (!installed.isEmpty()) {
            LinearLayout installedSection = ZyroxStudioUi.createItemCard(ctx);
            installedSection.addView(ZyroxStudioUi.createSectionLabel(ctx,
                    "INSTALLED LOCAL MODELS (" + installed.size() + " IN APP STORAGE)"));

            for (final LocalModelEntry entry : installed) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, ZyroxStudioUi.dp(ctx, 4));

                String badge = entry.loaded ? "LOADED" : (entry.selected ? "SELECTED" : "DOWNLOADED");
                row.addView(createHorizontalHeader(ctx, entry.fileName, badge, false));

                ModelFitAssessment fit = GgufRamAdvisor.assessModelFit(entry.sizeBytes, ram);
                TextView fitTv = ZyroxStudioUi.createSubtitle(ctx,
                        GgufRamAdvisor.formatBytes(entry.sizeBytes) + "  •  " + fit.message);
                if (fit.safety == FitSafety.CRITICAL_OOM_RISK) {
                    fitTv.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_danger));
                } else if (fit.safety == FitSafety.CAUTION) {
                    fitTv.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_amber));
                }
                row.addView(fitTv);

                LinearLayout actions = new LinearLayout(ctx);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, 0);

                Button btnUseAndLoad = ZyroxStudioUi.createSecondaryButton(ctx,
                        entry.loaded ? "LOADED ✓" : "SELECT & LOAD", true);
                Button btnDelete = ZyroxStudioUi.createDangerButton(ctx, "DELETE", true);
                addWeightedView(actions, btnUseAndLoad, 1f, 6);
                addWeightedView(actions, btnDelete, 0.55f, 0);

                btnUseAndLoad.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        triggerLoadModelWithFitCheck(entry.fileName, ram);
                    }
                });
                btnDelete.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmDeleteModel(entry.fileName);
                    }
                });
                row.addView(actions);
                installedSection.addView(row);
            }
            mBrainContentHost.addView(installedSection);
        }

        // 6. Built-In Curated GGUF Model Downloader Catalog
        LinearLayout catalogHdrCard = ZyroxStudioUi.createItemCard(ctx);
        catalogHdrCard.addView(ZyroxStudioUi.createSectionLabel(ctx, "BUILT-IN QUANTIZED GGUF MODEL DOWNLOADER"));
        catalogHdrCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Verified Q4_K_M models streamed over HTTPS with .part crash-safe resume and automatic GGUF header validation."));
        mBrainContentHost.addView(catalogHdrCard);

        for (final CatalogModel cat : GgufModelManager.BUILTIN_CATALOG) {
            mBrainContentHost.addView(buildCatalogModelCard(ctx, cat, modelMgr, ram));
        }
    }

    @NonNull
    private View buildCatalogModelCard(@NonNull final Context ctx,
                                       @NonNull final CatalogModel cat,
                                       @NonNull final GgufModelManager modelMgr,
                                       @NonNull final RamReport ram) {
        LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
        boolean isInstalled = modelMgr.isCatalogModelInstalled(cat);
        boolean isLoaded = cat.fileName.equals(modelMgr.getLoadedModelFileName())
                && modelMgr.getRuntimeBridge().isModelLoaded();
        boolean isRecommended = cat.id.equals(ram.tier.recommendedCatalogId);
        DownloadStateSnapshot dlSnap = modelMgr.getDownloadSnapshot(cat.id);
        boolean downloading = modelMgr.isDownloading(cat.id);
        boolean hasPartial = !isInstalled && modelMgr.hasResumablePartialDownload(cat);

        String badgeLabel = isLoaded ? "LOADED"
                : (isInstalled ? "DOWNLOADED"
                : (isRecommended ? "RECOMMENDED FOR YOUR RAM" : cat.parameterBadge + " • " + cat.quantization));
        card.addView(createHorizontalHeader(ctx, cat.title, badgeLabel, false));

        ModelFitAssessment fit = GgufRamAdvisor.assessModelFit(cat.approximateBytes, ram);
        card.addView(ZyroxStudioUi.createSubtitle(ctx,
                cat.parameterBadge + " (" + cat.quantization + ")  •  ~"
                        + GgufRamAdvisor.formatBytes(cat.approximateBytes) + "  •  " + cat.description));

        TextView fitLine = ZyroxStudioUi.createSubtitle(ctx, fit.message);
        if (fit.safety == FitSafety.CRITICAL_OOM_RISK) {
            fitLine.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_danger));
        } else if (fit.safety == FitSafety.CAUTION) {
            fitLine.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_warning));
        } else {
            fitLine.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_success));
        }
        card.addView(fitLine);

        if (downloading || (dlSnap != null && dlSnap.lastError != null)) {
            ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            int pct = dlSnap != null ? Math.max(0, dlSnap.percent) : 0;
            bar.setProgress(pct);
            card.addView(bar);

            String detailText;
            if (downloading && dlSnap != null) {
                detailText = String.format(Locale.US, "%d%%  •  %s / %s  •  %s/s",
                        pct,
                        GgufRamAdvisor.formatBytes(dlSnap.downloadedBytes),
                        GgufRamAdvisor.formatBytes(dlSnap.totalBytes),
                        GgufRamAdvisor.formatBytes(dlSnap.bytesPerSecond));
            } else {
                detailText = "Stopped: " + (dlSnap != null ? dlSnap.lastError : "");
            }
            TextView dlTv = ZyroxStudioUi.createSubtitle(ctx, detailText);
            dlTv.setTextColor(ContextCompat.getColor(ctx, downloading ? R.color.zyrox_info : R.color.zyrox_warning));
            card.addView(dlTv);
        } else if (hasPartial) {
            long partBytes = modelMgr.getPartialDownloadBytes(cat);
            card.addView(ZyroxStudioUi.createSubtitle(ctx,
                    "Partial download saved (" + GgufRamAdvisor.formatBytes(partBytes) + ") — Resume ready."));
        }

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, ZyroxStudioUi.dp(ctx, 5), 0, 0);

        if (downloading) {
            Button btnCancel = ZyroxStudioUi.createDangerButton(ctx, "CANCEL DOWNLOAD", true);
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    modelMgr.cancelDownload(cat.id);
                }
            });
            addWeightedView(btnRow, btnCancel, 1f, 0);
        } else if (isInstalled) {
            Button btnLoad = isLoaded
                    ? ZyroxStudioUi.createSecondaryButton(ctx, "LOADED IN MEMORY ✓", true)
                    : ZyroxStudioUi.createPrimaryButton(ctx, "LOAD INTO AI BRAIN");
            Button btnRemove = ZyroxStudioUi.createDangerButton(ctx, "DELETE", true);

            btnLoad.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.providerMode = ProviderMode.LOCAL_GGUF.name();
                    mManager.getConfigStore().save(next);
                    triggerLoadModelWithFitCheck(cat.fileName, ram);
                }
            });
            btnRemove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDeleteModel(cat.fileName);
                }
            });
            addWeightedView(btnRow, btnLoad, 1f, 6);
            addWeightedView(btnRow, btnRemove, 0.45f, 0);
        } else {
            String dlLabel = hasPartial ? "RESUME DOWNLOAD" : "DOWNLOAD GGUF";
            Button btnDownload = ZyroxStudioUi.createSecondaryButton(ctx, dlLabel, true);
            btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    modelMgr.downloadCatalogModelAsync(cat, null, new ResultCallback<LocalModelEntry>() {
                        @Override
                        public void onSuccess(@NonNull LocalModelEntry result) {
                            if (isAdded()) {
                                Toast.makeText(ctx, "Downloaded " + result.fileName + " — tap Load to activate!", Toast.LENGTH_SHORT).show();
                                refreshAllUi();
                            }
                        }

                        @Override
                        public void onError(@NonNull String message) {
                            if (isAdded()) {
                                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                                refreshAllUi();
                            }
                        }
                    });
                }
            });
            addWeightedView(btnRow, btnDownload, 1f, 0);
        }

        card.addView(btnRow);
        return card;
    }

    // =========================================================================
    // TAB 2: WORLD JOINING & COMPANION BOT BRIDGE
    // =========================================================================

    private void rebuildWorldPane(@NonNull final Context ctx,
                                  @NonNull final ConfigData cfg,
                                  @NonNull final BotStatusSnapshot botSnap) {
        mWorldContentHost.removeAllViews();

        // 1. Persona & Identity Card
        LinearLayout roleCard = ZyroxStudioUi.createItemCard(ctx);
        AiRole activeRole = AiRole.fromWire(cfg.role);
        roleCard.addView(createHorizontalHeader(ctx,
                "AI FRIEND ROLE & PERSONALITY",
                activeRole.badgeLabel,
                false));
        roleCard.addView(ZyroxStudioUi.createSubtitle(ctx, activeRole.description));

        HorizontalScrollView roleChipsScroll = new HorizontalScrollView(ctx);
        roleChipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout roleChips = new LinearLayout(ctx);
        roleChips.setOrientation(LinearLayout.HORIZONTAL);
        roleChips.setPadding(0, ZyroxStudioUi.dp(ctx, 5), 0, ZyroxStudioUi.dp(ctx, 5));

        for (final AiRole candidate : AiRole.values()) {
            boolean selected = candidate == activeRole;
            Button chip = selected
                    ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ " + candidate.displayName)
                    : ZyroxStudioUi.createSecondaryButton(ctx, candidate.displayName, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ZyroxStudioUi.dp(ctx, 5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.role = candidate.name();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                }
            });
            roleChips.addView(chip);
        }
        roleChipsScroll.addView(roleChips);
        roleCard.addView(roleChipsScroll);

        LinearLayout nameRow = new LinearLayout(ctx);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etBotName = ZyroxStudioUi.createGlassInput(ctx, "Bot Username (e.g. ZyroxBuddy)");
        etBotName.setText(cfg.botUsername);
        Button btnSaveName = ZyroxStudioUi.createSecondaryButton(ctx, "SAVE NAME", true);
        addWeightedView(nameRow, etBotName, 1f, 6);
        addWeightedView(nameRow, btnSaveName, 0.45f, 0);
        btnSaveName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigData next = mManager.getConfigStore().load();
                next.botUsername = etBotName.getText().toString();
                mManager.getConfigStore().save(next);
                Toast.makeText(ctx, "Saved bot username: " + mManager.getConfigStore().load().botUsername, Toast.LENGTH_SHORT).show();
                refreshAllUi();
            }
        });
        roleCard.addView(nameRow);
        mWorldContentHost.addView(roleCard);

        // 2. Target World / Multiplayer Connection Mode Card
        LinearLayout targetCard = ZyroxStudioUi.createItemCard(ctx);
        final TargetConnectionMode targetMode = TargetConnectionMode.fromWire(cfg.targetMode);
        targetCard.addView(createHorizontalHeader(ctx,
                "WORLD / SERVER CONNECTION TARGET",
                targetMode.displayName,
                false));

        LinearLayout targetTabs = new LinearLayout(ctx);
        targetTabs.setOrientation(LinearLayout.HORIZONTAL);
        targetTabs.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, ZyroxStudioUi.dp(ctx, 6));

        for (final TargetConnectionMode m : TargetConnectionMode.values()) {
            Button b = (m == targetMode)
                    ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ " + m.displayName)
                    : ZyroxStudioUi.createSecondaryButton(ctx, m.displayName, true);
            addWeightedView(targetTabs, b, 1f, m == TargetConnectionMode.SAVED_SERVER ? 0 : 4);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.targetMode = m.name();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                }
            });
        }
        targetCard.addView(targetTabs);

        if (targetMode == TargetConnectionMode.LAN_WORLD) {
            targetCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                    "Open a singleplayer world to LAN (Pause -> Open to LAN). Zyrox auto-captures the LAN port from live game logs, or scan Wi-Fi below:"));
            LinearLayout lanRow = new LinearLayout(ctx);
            lanRow.setOrientation(LinearLayout.HORIZONTAL);
            lanRow.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, 0);
            final EditText etLan = ZyroxStudioUi.createGlassInput(ctx, "LAN IP:Port (e.g. 127.0.0.1:54321)");
            etLan.setText(cfg.lanAddress);
            Button btnSaveLan = ZyroxStudioUi.createSecondaryButton(ctx, "SAVE", true);
            Button btnScanLan = ZyroxStudioUi.createSecondaryButton(ctx, "SCAN LAN", true);
            addWeightedView(lanRow, etLan, 1.2f, 5);
            addWeightedView(lanRow, btnSaveLan, 0.45f, 5);
            addWeightedView(lanRow, btnScanLan, 0.55f, 0);

            btnSaveLan.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.lanAddress = etLan.getText().toString().trim();
                    mManager.getConfigStore().save(next);
                    Toast.makeText(ctx, "LAN address saved", Toast.LENGTH_SHORT).show();
                }
            });
            btnScanLan.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    scanAndPickLanWorld(ctx);
                }
            });
            targetCard.addView(lanRow);
        } else if (targetMode == TargetConnectionMode.ZYROX_SELF_HOSTED) {
            ZyroxRoomBridge.HostSession activeHost = ZyroxRoomBridge.getActiveHost();
            ZyroxRoomBridge.ClientTunnel activeClient = ZyroxRoomBridge.getActiveClient();
            String liveInfo = activeHost != null
                    ? "Active Hosted Room Detected: " + activeHost.shortCode + " (MC port " + activeHost.localMcPort + ")"
                    : (activeClient != null
                    ? "Active Joined Room Detected: " + activeClient.shortCode + " (127.0.0.1:" + activeClient.localPort + ")"
                    : "No active room in this process — host one in Server Manager or paste a ZYX-XXXX share code:");
            targetCard.addView(ZyroxStudioUi.createSubtitle(ctx, liveInfo));

            LinearLayout roomRow = new LinearLayout(ctx);
            roomRow.setOrientation(LinearLayout.HORIZONTAL);
            roomRow.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, 0);
            final EditText etRoom = ZyroxStudioUi.createGlassInput(ctx, "ZYX-XXXX or full ZYX-XXXX-... code");
            etRoom.setText(cfg.zyroxRoomCode);
            Button btnSaveRoom = ZyroxStudioUi.createSecondaryButton(ctx, "SAVE CODE", true);
            addWeightedView(roomRow, etRoom, 1f, 6);
            addWeightedView(roomRow, btnSaveRoom, 0.5f, 0);
            btnSaveRoom.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.zyroxRoomCode = etRoom.getText().toString().trim();
                    mManager.getConfigStore().save(next);
                    Toast.makeText(ctx, "Zyrox Room code saved", Toast.LENGTH_SHORT).show();
                }
            });
            targetCard.addView(roomRow);
        } else {
            targetCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                    "Connects to a saved entry from servers.dat or custom host:port:"));
            LinearLayout srvRow = new LinearLayout(ctx);
            srvRow.setOrientation(LinearLayout.HORIZONTAL);
            srvRow.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, 0);
            final EditText etSrv = ZyroxStudioUi.createGlassInput(ctx, "mc.example.com:25565");
            etSrv.setText(cfg.savedServerAddress);
            Button btnSaveSrv = ZyroxStudioUi.createSecondaryButton(ctx, "SAVE", true);
            Button btnPickSrv = ZyroxStudioUi.createSecondaryButton(ctx, "PICK SAVED", true);
            addWeightedView(srvRow, etSrv, 1.2f, 5);
            addWeightedView(srvRow, btnSaveSrv, 0.45f, 5);
            addWeightedView(srvRow, btnPickSrv, 0.6f, 0);

            btnSaveSrv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigData next = mManager.getConfigStore().load();
                    next.savedServerAddress = etSrv.getText().toString().trim();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                }
            });
            btnPickSrv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPickSavedServerDialog(ctx);
                }
            });
            targetCard.addView(srvRow);

            if (!cfg.savedServerAddress.isEmpty()) {
                String advisory = AiFriendTargetResolver.inspectServerSafetyAndAuth(
                        cfg.savedServerAddress, BotAuthMode.fromWire(cfg.botAuthMode));
                if (advisory != null) {
                    TextView advTv = ZyroxStudioUi.createSubtitle(ctx, advisory);
                    advTv.setTextColor(ContextCompat.getColor(ctx,
                            advisory.startsWith("Warning:") ? R.color.zyrox_danger : R.color.zyrox_amber));
                    advTv.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, 0);
                    targetCard.addView(advTv);
                }
            }
        }

        // Bot Authentication Mode Toggle
        final BotAuthMode authMode = BotAuthMode.fromWire(cfg.botAuthMode);
        LinearLayout authRow = new LinearLayout(ctx);
        authRow.setOrientation(LinearLayout.HORIZONTAL);
        authRow.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, 0);
        Button btnOfflineAuth = authMode == BotAuthMode.OFFLINE
                ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ OFFLINE / LAN AUTH")
                : ZyroxStudioUi.createSecondaryButton(ctx, "OFFLINE / LAN AUTH", true);
        Button btnMsaAuth = authMode == BotAuthMode.MICROSOFT_DEVICE_CODE
                ? ZyroxStudioUi.createPrimaryButton(ctx, "✓ MICROSOFT AUTH (ONLINE SMP)")
                : ZyroxStudioUi.createSecondaryButton(ctx, "MICROSOFT AUTH (ONLINE SMP)", true);
        addWeightedView(authRow, btnOfflineAuth, 1f, 5);
        addWeightedView(authRow, btnMsaAuth, 1f, 0);

        btnOfflineAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigData next = mManager.getConfigStore().load();
                next.botAuthMode = BotAuthMode.OFFLINE.name();
                mManager.getConfigStore().save(next);
                refreshAllUi();
            }
        });
        btnMsaAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigData next = mManager.getConfigStore().load();
                next.botAuthMode = BotAuthMode.MICROSOFT_DEVICE_CODE.name();
                mManager.getConfigStore().save(next);
                refreshAllUi();
            }
        });
        targetCard.addView(authRow);
        mWorldContentHost.addView(targetCard);

        // 3. Companion Bot Bridge Worker Connection & Summon Controls
        LinearLayout bridgeCard = ZyroxStudioUi.createItemCard(ctx);
        bridgeCard.addView(createHorizontalHeader(ctx,
                "COMPANION BOT BRIDGE WORKER (MINEFLAYER TCP)",
                botSnap.state.badgeText,
                botSnap.state == BotConnectionState.ERROR));

        bridgeCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Bridge Endpoint: " + cfg.bridgeHost + ":" + cfg.bridgePort
                        + "  •  Bot Status: " + botSnap.detail));

        if (botSnap.state == BotConnectionState.AUTH_REQUIRED && botSnap.msaUserCode != null) {
            TextView msaPrompt = ZyroxStudioUi.createTitle(ctx,
                    "Microsoft Sign-In Code: " + botSnap.msaUserCode
                            + "  (" + botSnap.msaVerificationUri + ")");
            msaPrompt.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_amber));
            bridgeCard.addView(msaPrompt);
        }

        LinearLayout bridgeCtrlRow = new LinearLayout(ctx);
        bridgeCtrlRow.setOrientation(LinearLayout.HORIZONTAL);
        bridgeCtrlRow.setPadding(0, ZyroxStudioUi.dp(ctx, 6), 0, 0);

        Button btnBridgeEndpoint = ZyroxStudioUi.createSecondaryButton(ctx, "BRIDGE IP/TOKEN", true);
        Button btnPingBridge = ZyroxStudioUi.createSecondaryButton(ctx, "PING BRIDGE", true);
        Button btnSummonOrDismiss = mManager.getBridgeClient().isBridgeSocketOpen()
                ? ZyroxStudioUi.createDangerButton(ctx, "DISMISS BOT", false)
                : ZyroxStudioUi.createPrimaryButton(ctx, "SUMMON AI FRIEND");

        addWeightedView(bridgeCtrlRow, btnBridgeEndpoint, 0.9f, 5);
        addWeightedView(bridgeCtrlRow, btnPingBridge, 0.85f, 5);
        addWeightedView(bridgeCtrlRow, btnSummonOrDismiss, 1.15f, 0);

        btnBridgeEndpoint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigureBridgeDialog();
            }
        });
        btnPingBridge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mManager.pingBridgeAsync(new ResultCallback<String>() {
                    @Override
                    public void onSuccess(@NonNull String result) {
                        if (isAdded()) {
                            Toast.makeText(ctx, result, Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        if (isAdded()) {
                            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
        btnSummonOrDismiss.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onGlobalActionTriggered();
            }
        });
        bridgeCard.addView(bridgeCtrlRow);

        // In-game overlay + world-chat auto-response switches
        SwitchCompat swOverlay = createStyledSwitch(ctx,
                "Show Floating AI Friend HUD Pill In-Game",
                cfg.inGameOverlayEnabled);
        swOverlay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ConfigData next = mManager.getConfigStore().load();
                next.inGameOverlayEnabled = isChecked;
                mManager.getConfigStore().save(next);
            }
        });
        bridgeCard.addView(swOverlay);

        SwitchCompat swMentions = createStyledSwitch(ctx,
                "Reply Automatically When Players Mention @" + cfg.botUsername + " in World Chat",
                cfg.respondToWorldChatMentions);
        swMentions.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ConfigData next = mManager.getConfigStore().load();
                next.respondToWorldChatMentions = isChecked;
                mManager.getConfigStore().save(next);
            }
        });
        bridgeCard.addView(swMentions);
        mWorldContentHost.addView(bridgeCard);
    }

    // =========================================================================
    // TAB 3: LIVE COMMAND & CHAT CONSOLE
    // =========================================================================

    @NonNull
    private LinearLayout buildConsolePane(@NonNull final Context ctx) {
        LinearLayout pane = new LinearLayout(ctx);
        pane.setOrientation(LinearLayout.VERTICAL);

        // 1-Tap Structured Action Bar
        HorizontalScrollView chipsScroll = new HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipsRow = new LinearLayout(ctx);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, 0, 0, ZyroxStudioUi.dp(ctx, 6));

        for (final BotAction act : BotAction.values()) {
            Button chip = ZyroxStudioUi.createSecondaryButton(ctx, act.displayName, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ZyroxStudioUi.dp(ctx, 5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mManager.dispatchQuickActionAsync(act, null, new ResultCallback<String>() {
                        @Override
                        public void onSuccess(@NonNull String summary) {
                            if (isAdded()) {
                                Toast.makeText(ctx, summary, Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(@NonNull String message) {
                        }
                    });
                }
            });
            chipsRow.addView(chip);
        }
        chipsScroll.addView(chipsRow);
        pane.addView(chipsScroll);

        // Dark OLED Terminal History Scroll
        mConsoleScroll = new ScrollView(ctx);
        mConsoleScroll.setBackgroundResource(R.drawable.bg_zyrox_terminal);
        int pad = ZyroxStudioUi.dp(ctx, 10);
        mConsoleScroll.setPadding(pad, pad, pad, pad);
        mConsoleHistoryContainer = new LinearLayout(ctx);
        mConsoleHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        mConsoleScroll.addView(mConsoleHistoryContainer);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.bottomMargin = ZyroxStudioUi.dp(ctx, 6);
        pane.addView(mConsoleScroll, scrollLp);

        // Prompt Input Bar
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        mConsolePromptInput = ZyroxStudioUi.createGlassInput(ctx,
                "Ask AI Friend or give a command (e.g. 'mine some iron and place torches')...");
        Button btnSend = ZyroxStudioUi.createPrimaryButton(ctx, "SEND");
        Button btnClear = ZyroxStudioUi.createSecondaryButton(ctx, "CLEAR", true);

        addWeightedView(inputRow, mConsolePromptInput, 1f, 5);
        addWeightedView(inputRow, btnSend, 0.35f, 5);
        addWeightedView(inputRow, btnClear, 0.3f, 0);

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String prompt = mConsolePromptInput.getText().toString().trim();
                if (prompt.isEmpty()) return;
                mConsolePromptInput.setText("");
                mManager.sendPromptAsync(prompt, null, null);
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mManager.clearChatHistory();
            }
        });
        pane.addView(inputRow);
        return pane;
    }

    private void rebuildConsoleHistory(@NonNull Context ctx) {
        if (mConsoleHistoryContainer == null) return;
        mConsoleHistoryContainer.removeAllViews();
        List<ChatHistoryEntry> history = mManager.getChatHistorySnapshot();

        if (history.isEmpty()) {
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "No messages yet. Tap any quick action chip above or type a prompt below to command your AI Friend!");
            mConsoleHistoryContainer.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        for (ChatHistoryEntry entry : history) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, ZyroxStudioUi.dp(ctx, 3), 0, ZyroxStudioUi.dp(ctx, 5));

            String header = "[" + fmt.format(new Date(entry.timestampMs)) + "] " + entry.sender
                    + (entry.badgeDetail != null ? "  •  " + entry.badgeDetail : "");
            TextView tvHdr = new TextView(ctx);
            tvHdr.setText(header);
            tvHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            tvHdr.setTypeface(ZyroxStudioUi.getDisplayFont(ctx), Typeface.BOLD);
            int colorRes = ChatHistoryEntry.SENDER_AI.equals(entry.sender) ? R.color.zyrox_success
                    : (ChatHistoryEntry.SENDER_SYSTEM.equals(entry.sender) ? R.color.zyrox_warning : R.color.zyrox_info);
            tvHdr.setTextColor(ContextCompat.getColor(ctx, colorRes));
            item.addView(tvHdr);

            TextView tvMsg = new TextView(ctx);
            tvMsg.setText(entry.message);
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvMsg.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_text_primary));
            item.addView(tvMsg);

            mConsoleHistoryContainer.addView(item);
        }

        mConsoleScroll.post(new Runnable() {
            @Override
            public void run() {
                if (mConsoleScroll != null) {
                    mConsoleScroll.fullScroll(View.FOCUS_DOWN);
                }
            }
        });
    }

    // =========================================================================
    // DIALOGS & ACTIONS
    // =========================================================================

    private void onGgufDocumentPicked(@Nullable Uri uri) {
        if (uri == null || !isAdded() || mManager == null) return;
        final Context ctx = requireContext();
        mImportProgressBanner.setVisibility(View.VISIBLE);
        mImportProgressBanner.setText("Importing .gguf model into internal storage...");

        mManager.getModelManager().importModelFromUriAsync(
                ctx.getApplicationContext(),
                uri,
                new TransferProgressListener() {
                    @Override
                    public void onProgress(@NonNull String modelId,
                                           int percent,
                                           long transferredBytes,
                                           long totalBytes,
                                           long bytesPerSecond) {
                        if (!isAdded() || mImportProgressBanner == null) return;
                        String pctStr = percent >= 0 ? (percent + "% • ") : "";
                        mImportProgressBanner.setText("Importing " + modelId + ": " + pctStr
                                + GgufRamAdvisor.formatBytes(transferredBytes)
                                + (totalBytes > 0 ? " / " + GgufRamAdvisor.formatBytes(totalBytes) : ""));
                    }
                },
                new ResultCallback<LocalModelEntry>() {
                    @Override
                    public void onSuccess(@NonNull LocalModelEntry result) {
                        if (!isAdded()) return;
                        mImportProgressBanner.setVisibility(View.GONE);
                        Toast.makeText(ctx, "Imported verified GGUF: " + result.fileName, Toast.LENGTH_LONG).show();
                        refreshAllUi();
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        if (!isAdded()) return;
                        mImportProgressBanner.setVisibility(View.GONE);
                        Toast.makeText(ctx, "Import failed: " + message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void triggerLoadModelWithFitCheck(@Nullable final String fileName,
                                              @NonNull RamReport ram) {
        if (!isAdded() || mManager == null) return;
        final Context ctx = requireContext();
        String candidate = fileName != null ? fileName : mManager.getConfigStore().load().selectedGgufFileName;
        long bytes = 0L;
        for (LocalModelEntry e : mManager.getModelManager().listInstalledModels()) {
            if (e.fileName.equals(candidate)) {
                bytes = e.sizeBytes;
                break;
            }
        }
        if (bytes > 0L) {
            ModelFitAssessment fit = GgufRamAdvisor.assessModelFit(bytes, ram);
            if (fit.safety == FitSafety.CRITICAL_OOM_RISK) {
                new AlertDialog.Builder(ctx)
                        .setTitle("High OOM Memory Warning")
                        .setMessage(fit.message + "\n\nAttempt to load anyway?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Load Anyway", (d, w) -> executeLoadModel(ctx, fileName))
                        .show();
                return;
            }
        }
        executeLoadModel(ctx, fileName);
    }

    private void executeLoadModel(@NonNull final Context ctx, @Nullable String fileName) {
        mManager.getModelManager().loadModelAsync(fileName, new ResultCallback<ActiveModelSession>() {
            @Override
            public void onSuccess(@NonNull ActiveModelSession session) {
                if (isAdded()) {
                    Toast.makeText(ctx, "Loaded " + session.modelFile.getName(), Toast.LENGTH_SHORT).show();
                    refreshAllUi();
                }
            }

            @Override
            public void onError(@NonNull String message) {
                if (isAdded()) {
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                    refreshAllUi();
                }
            }
        });
    }

    private void confirmDeleteModel(@NonNull final String fileName) {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        new AlertDialog.Builder(ctx)
                .setTitle("Delete Local GGUF Model?")
                .setMessage("Remove " + fileName + " from app storage?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    mManager.getModelManager().deleteModel(fileName);
                    refreshAllUi();
                })
                .show();
    }

    @NonNull
    private static String shortPresetChipName(@NonNull ApiKeyProviderPreset preset) {
        int paren = preset.title.indexOf('(');
        String base = paren > 0 ? preset.title.substring(0, paren).trim() : preset.title;
        return base.toUpperCase(Locale.ROOT);
    }

    private void applyApiKeyPreset(@NonNull ApiKeyProviderPreset preset) {
        if (!isAdded() || mManager == null) return;
        ConfigData next = mManager.getConfigStore().load();
        next.providerMode = ProviderMode.ONLINE_API.name();
        next.onlinePresetId = preset.id;
        next.baseApiUrl = preset.baseUrl;
        next.selectedOnlineModel = preset.defaultModel;
        next.fallbackOnlineModels = new ArrayList<>(preset.freeModels);
        mManager.getConfigStore().save(next);
        Toast.makeText(requireContext(),
                "Applied " + preset.title + " (" + preset.defaultModel + ")",
                Toast.LENGTH_SHORT).show();
        refreshAllUi();
    }

    private void showPickApiKeyPresetDialog() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final List<ApiKeyProviderPreset> presets = AiProviderCatalog.API_KEY_PRESETS;
        final String[] labels = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            ApiKeyProviderPreset p = presets.get(i);
            labels[i] = p.title + "\n" + p.freeQuotaBadge + " • " + p.defaultModel;
        }
        new AlertDialog.Builder(ctx)
                .setTitle("Select Free-Tier API Provider or Gateway")
                .setItems(labels, (d, which) -> applyApiKeyPreset(presets.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPickAnonymousRoutingStrategyDialog() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final List<AnonymousProviderSpec> pool = AiProviderCatalog.ANONYMOUS_PROVIDERS;
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        ids.add(AiProviderCatalog.POLICY_AUTO_CASCADE);
        labels.add("Auto Smart Cascade (Recommended — All Enabled Free Networks)");

        for (AnonymousProviderSpec spec : pool) {
            ids.add(spec.id);
            labels.add("Pin First: " + spec.title + " (" + spec.quotaSummary + ")");
        }

        ConfigData cfg = mManager.getConfigStore().load();
        if (!cfg.customFreeRouterUrl.isEmpty()) {
            ids.add(AiProviderCatalog.CUSTOM_ROUTER_ID);
            labels.add("Pin First: Custom Free Router (" + cfg.customFreeRouterUrl + ")");
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Select Free Anonymous Routing Strategy")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.anonymousRoutingPolicy = ids.get(which);
                    next.providerMode = ProviderMode.FREE_ANONYMOUS.name();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConfigureCustomFreeRouterDialog() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final ConfigData cfg = mManager.getConfigStore().load();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        box.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 4));

        box.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Optional keyless self-hosted router (e.g. OmniRoute on http://127.0.0.1:20128/v1 or FreeLM on http://127.0.0.1:4000/v1):"));
        final EditText etUrl = ZyroxStudioUi.createGlassInput(ctx, "http://127.0.0.1:20128/v1");
        etUrl.setText(cfg.customFreeRouterUrl);
        box.addView(etUrl);

        box.addView(ZyroxStudioUi.createSubtitle(ctx, "Preferred Combo / Model ID:"));
        final EditText etModel = ZyroxStudioUi.createGlassInput(ctx, "free-combo or auto");
        etModel.setText(cfg.customFreeRouterModel);
        box.addView(etModel);

        new AlertDialog.Builder(ctx)
                .setTitle("Optional Self-Hosted Free Router")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (d, w) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.customFreeRouterUrl = "";
                    if (AiProviderCatalog.CUSTOM_ROUTER_ID.equalsIgnoreCase(next.anonymousRoutingPolicy)) {
                        next.anonymousRoutingPolicy = AiProviderCatalog.POLICY_AUTO_CASCADE;
                    }
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                })
                .setPositiveButton("Save", (d, w) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.customFreeRouterUrl = etUrl.getText().toString().trim();
                    next.customFreeRouterModel = etModel.getText().toString().trim();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                })
                .show();
    }

    private void probeAndShowLiveModelsDialog(@Nullable final String anonymousProviderId,
                                              @NonNull final String title) {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        Toast.makeText(ctx, "Fetching live /models from " + title + "...", Toast.LENGTH_SHORT).show();
        mManager.fetchLiveModelsAsync(anonymousProviderId, new ResultCallback<List<String>>() {
            @Override
            public void onSuccess(@NonNull final List<String> models) {
                if (!isAdded()) return;
                if (models.isEmpty()) {
                    Toast.makeText(ctx, "No chat models returned by " + title, Toast.LENGTH_LONG).show();
                    return;
                }
                final String[] arr = models.toArray(new String[0]);
                new AlertDialog.Builder(ctx)
                        .setTitle(title + " (" + models.size() + " Live Models)")
                        .setItems(arr, (d, which) -> {
                            if (anonymousProviderId == null) {
                                ConfigData next = mManager.getConfigStore().load();
                                next.selectedOnlineModel = arr[which];
                                mManager.getConfigStore().save(next);
                                refreshAllUi();
                            } else {
                                Toast.makeText(ctx,
                                        title + " cached " + models.size() + " live models (picked: " + arr[which] + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
            }

            @Override
            public void onError(@NonNull String message) {
                if (!isAdded()) return;
                Toast.makeText(ctx, "Live /models failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showConfigureOnlineApiDialog() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final ConfigData cfg = mManager.getConfigStore().load();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        box.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 4));

        final EditText etUrl = ZyroxStudioUi.createGlassInput(ctx,
                "https://openrouter.ai/api/v1 or http://127.0.0.1:20128/v1");
        etUrl.setText(cfg.baseApiUrl);
        box.addView(ZyroxStudioUi.createSubtitle(ctx, "OpenAI-Compatible Base URL (HTTPS or Local/LAN Gateway):"));
        box.addView(etUrl);

        final EditText etKey = ZyroxStudioUi.createGlassInput(ctx, "Paste API Key (sk-..., gsk_..., AIza..., github_pat_...)");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(ZyroxStudioUi.createSubtitle(ctx,
                "API Key (Android Keystore AES-GCM Vault — current: "
                        + mManager.getConfigStore().getMaskedApiKeyPreview() + "):"));
        box.addView(etKey);

        final EditText etCustomModel = ZyroxStudioUi.createGlassInput(ctx, "Model ID (e.g. llama-3.3-70b-versatile)");
        etCustomModel.setText(cfg.selectedOnlineModel);
        box.addView(ZyroxStudioUi.createSubtitle(ctx, "Primary Model ID:"));
        box.addView(etCustomModel);

        final SwitchCompat swFallback = new SwitchCompat(ctx);
        swFallback.setText("Automatic fallback across preset's free models on HTTP 429 / 5xx");
        swFallback.setChecked(cfg.autoFallbackFreeModels);
        box.addView(swFallback);

        new AlertDialog.Builder(ctx)
                .setTitle("Configure API Key & Endpoint")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear Key", (d, w) -> {
                    mManager.getConfigStore().setApiKey("");
                    refreshAllUi();
                })
                .setPositiveButton("Save", (d, w) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.baseApiUrl = etUrl.getText().toString().trim();
                    next.selectedOnlineModel = etCustomModel.getText().toString().trim();
                    next.autoFallbackFreeModels = swFallback.isChecked();
                    ApiKeyProviderPreset matched = AiProviderCatalog.findPresetByIdOrUrl(null, next.baseApiUrl);
                    if (matched != null) {
                        next.onlinePresetId = matched.id;
                    }
                    mManager.getConfigStore().save(next);

                    String enteredKey = etKey.getText().toString().trim();
                    if (!enteredKey.isEmpty()) {
                        mManager.getConfigStore().setApiKey(enteredKey);
                    }
                    refreshAllUi();
                })
                .show();
    }

    private void showPickFreeModelDialog() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final ConfigData cfg = mManager.getConfigStore().load();
        final ApiKeyProviderPreset preset =
                AiProviderCatalog.findPresetByIdOrUrl(cfg.onlinePresetId, cfg.baseApiUrl);
        final List<String> models = preset != null
                ? preset.freeModels
                : (cfg.fallbackOnlineModels != null && !cfg.fallbackOnlineModels.isEmpty()
                        ? cfg.fallbackOnlineModels
                        : AiFriendConfigStore.CURATED_FREE_MODELS);
        final String[] items = models.toArray(new String[0]);
        final String title = preset != null
                ? "Select Model • " + preset.title
                : "Select Curated Free Model";

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.selectedOnlineModel = items[which];
                    next.providerMode = ProviderMode.ONLINE_API.name();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                })
                .setNeutralButton("Fetch Live /models", (d, w) ->
                        probeAndShowLiveModelsDialog(null, preset != null ? preset.title : cfg.baseApiUrl))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConfigureBridgeDialog() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final ConfigData cfg = mManager.getConfigStore().load();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        box.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 4));

        final EditText etHost = ZyroxStudioUi.createGlassInput(ctx, "127.0.0.1 (Termux) or 192.168.x.x (LAN PC)");
        etHost.setText(cfg.bridgeHost);
        box.addView(ZyroxStudioUi.createSubtitle(ctx, "Companion Bot Bridge Host:"));
        box.addView(etHost);

        final EditText etPort = ZyroxStudioUi.createGlassInput(ctx, "25580");
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(cfg.bridgePort));
        box.addView(ZyroxStudioUi.createSubtitle(ctx, "Bridge TCP Port:"));
        box.addView(etPort);

        final EditText etToken = ZyroxStudioUi.createGlassInput(ctx, "Optional ZYROX_BRIDGE_TOKEN");
        etToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(ZyroxStudioUi.createSubtitle(ctx, "Optional Shared Bridge Token:"));
        box.addView(etToken);

        new AlertDialog.Builder(ctx)
                .setTitle("Companion Bot Bridge Settings")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.bridgeHost = etHost.getText().toString().trim();
                    try {
                        next.bridgePort = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    mManager.getConfigStore().save(next);
                    String tok = etToken.getText().toString().trim();
                    if (!tok.isEmpty()) {
                        mManager.getConfigStore().setBridgeToken(tok);
                    }
                    refreshAllUi();
                })
                .show();
    }

    private void scanAndPickLanWorld(@NonNull final Context ctx) {
        Toast.makeText(ctx, "Scanning Wi-Fi for Open-to-LAN worlds...", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                final List<ServerPinger.LanWorldEntry> found =
                        AiFriendTargetResolver.scanLocalLanWorldsBlocking(2200);
                Tools.MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        if (found.isEmpty()) {
                            Toast.makeText(ctx, "No Open-to-LAN broadcasts detected yet.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        final String[] labels = new String[found.size()];
                        for (int i = 0; i < found.size(); i++) {
                            ServerPinger.LanWorldEntry w = found.get(i);
                            labels[i] = w.motd + " (" + w.address + ")";
                        }
                        new AlertDialog.Builder(ctx)
                                .setTitle("Select Discovered LAN World")
                                .setItems(labels, (d, which) -> {
                                    ConfigData next = mManager.getConfigStore().load();
                                    next.lanAddress = found.get(which).address;
                                    next.targetMode = TargetConnectionMode.LAN_WORLD.name();
                                    mManager.getConfigStore().save(next);
                                    refreshAllUi();
                                })
                                .show();
                    }
                });
            }
        });
    }

    private void showPickSavedServerDialog(@NonNull final Context ctx) {
        final List<ServerListManager.ServerEntry> servers = AiFriendTargetResolver.loadSavedServersSafe();
        if (servers.isEmpty()) {
            Toast.makeText(ctx, "No saved entries found in servers.dat — add a server in Server Manager first.", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            ServerListManager.ServerEntry s = servers.get(i);
            labels[i] = s.name + " (" + s.address + ")";
        }
        new AlertDialog.Builder(ctx)
                .setTitle("Select Saved Server")
                .setItems(labels, (d, which) -> {
                    ConfigData next = mManager.getConfigStore().load();
                    next.savedServerAddress = servers.get(which).address;
                    next.targetMode = TargetConnectionMode.SAVED_SERVER.name();
                    mManager.getConfigStore().save(next);
                    refreshAllUi();
                })
                .show();
    }

    private void runTestInference(@NonNull ProviderMode forcedMode) {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        ConfigData cfg = mManager.getConfigStore().load();
        cfg.providerMode = forcedMode.name();
        mManager.getConfigStore().save(cfg);

        Toast.makeText(ctx, "Testing " + forcedMode.displayName + "...", Toast.LENGTH_SHORT).show();
        mManager.sendPromptAsync("Hello! Give a quick status check and follow me.", null,
                new ResultCallback<AiTurnResponse>() {
                    @Override
                    public void onSuccess(@NonNull AiTurnResponse turn) {
                        if (!isAdded()) return;
                        String msg = "Model: " + OnlineFreeApiLlmProvider.shortenModelId(turn.modelUsed)
                                + " (" + turn.latencyMs + " ms)\n"
                                + "Action: " + turn.action.name() + "\n"
                                + "Say: \"" + turn.say + "\"";
                        new AlertDialog.Builder(ctx)
                                .setTitle("AI Brain Test Succeeded")
                                .setMessage(msg)
                                .setPositiveButton("Open Console", (d, w) -> switchTab(TAB_COMMAND_CONSOLE))
                                .setNegativeButton("Close", null)
                                .show();
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        if (!isAdded()) return;
                        new AlertDialog.Builder(ctx)
                                .setTitle("AI Brain Test Failed")
                                .setMessage(LogRedactor.redact(message))
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
    }

    private void triggerSummonBot() {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        mManager.summonBotAsync(new ResultCallback<ResolvedTarget>() {
            @Override
            public void onSuccess(@NonNull ResolvedTarget target) {
                if (isAdded()) {
                    Toast.makeText(ctx, "Summoning bot -> " + target.displaySummary, Toast.LENGTH_SHORT).show();
                    refreshAllUi();
                }
            }

            @Override
            public void onError(@NonNull String message) {
                if (isAdded()) {
                    new AlertDialog.Builder(ctx)
                            .setTitle("Companion Bot Bridge")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                    refreshAllUi();
                }
            }
        });
    }

    // =========================================================================
    // UI STYLING HELPERS
    // =========================================================================

    @NonNull
    private static LinearLayout createHorizontalHeader(@NonNull Context ctx,
                                                       @NonNull String sectionTitle,
                                                       @NonNull String badgeText,
                                                       boolean danger) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = ZyroxStudioUi.createSectionLabel(ctx, sectionTitle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = ZyroxStudioUi.dp(ctx, 6);
        label.setLayoutParams(lp);

        TextView badge = ZyroxStudioUi.createBadge(ctx, badgeText, danger);
        row.addView(label);
        row.addView(badge);
        return row;
    }

    @NonNull
    private static SwitchCompat createStyledSwitch(@NonNull Context ctx,
                                                   @NonNull String title,
                                                   boolean checked) {
        SwitchCompat sw = new SwitchCompat(ctx);
        sw.setText(title);
        sw.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_text_primary));
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        sw.setChecked(checked);
        sw.setPadding(0, ZyroxStudioUi.dp(ctx, 5), 0, ZyroxStudioUi.dp(ctx, 2));
        return sw;
    }

    /** Tone: 0 = neutral glass, 1 = emerald active, 2 = amber caution, 3 = danger rose. */
    private static void styleBadge(@NonNull Context ctx,
                                   @NonNull TextView badge,
                                   @NonNull String text,
                                   int tone) {
        badge.setText(text);
        badge.setBackgroundResource(tone == 3 ? R.drawable.bg_zyrox_badge_danger : R.drawable.bg_zyrox_badge);
        int color = tone == 1 ? ContextCompat.getColor(ctx, R.color.zyrox_success)
                : (tone == 2 ? ContextCompat.getColor(ctx, R.color.zyrox_warning)
                : (tone == 3 ? ContextCompat.getColor(ctx, R.color.zyrox_danger)
                : Color.WHITE));
        badge.setTextColor(color);
    }

    private static void addWeightedView(@NonNull LinearLayout parent,
                                        @NonNull View child,
                                        float weight,
                                        int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (rightMarginDp > 0) {
            lp.rightMargin = ZyroxStudioUi.dp(parent.getContext(), rightMarginDp);
        }
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    @NonNull
    private static String truncateMiddle(@NonNull String s, int maxChars) {
        if (s.length() <= maxChars) return s;
        return TextUtils.substring(s, 0, maxChars - 3) + "...";
    }
}
