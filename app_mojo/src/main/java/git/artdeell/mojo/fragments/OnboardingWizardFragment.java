package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.SkinHeadRenderer;
import git.artdeell.mojo.content.ModrinthContentInstaller;
import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.performance.GameOptimizer;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.GameOptionsUtils;
import git.artdeell.mojo.utils.GpuUtils;

/**
 * 3-Step First-Time Onboarding Wizard for Zyrox Launcher:
 * <ol>
 *   <li><b>Account Setup:</b> Clean Liquid Glass cards for Microsoft, Ely.by, and Offline/Local
 *       with a real-time 3D isometric head avatar rendered via {@link SkinHeadRenderer}.</li>
 *   <li><b>Smart Device Calibration:</b> Plain-English GPU ({@link GpuUtils} +
 *       {@link GraphicsAdvisor}) and JVM heap RAM auto-configuration summary.</li>
 *   <li><b>Play Preset:</b> 1-tap provisioning for <i>Vanilla Smooth</i>,
 *       <i>FPS Boosted (Sodium)</i>, or <i>PvP Ready</i>.</li>
 * </ol>
 */
public class OnboardingWizardFragment extends Fragment {
    public static final String TAG = "ONBOARDING_WIZARD_FRAGMENT";

    public static final int PRESET_VANILLA_SMOOTH = 0;
    public static final int PRESET_FPS_BOOSTED = 1;
    public static final int PRESET_PVP_READY = 2;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private static final int[] SKIN_TONES = {
            0xFFD8A47F, 0xFFC6865A, 0xFFF1C29B, 0xFF8D5534, 0xFFE0AC82, 0xFFA56B46
    };
    private static final int[] HAIR_PALETTE = {
            0xFF3B2618, 0xFF1E1B18, 0xFF6B3E26, 0xFFB8863B, 0xFF2B3A67, 0xFF5C2A2A
    };
    private static final int[] EYE_PALETTE = {
            0xFF3B82F6, 0xFF10B981, 0xFF8B5CF6, 0xFFF59E0B, 0xFF06B6D4, 0xFF6366F1
    };

    private int mCurrentStep = 1;
    private int mSelectedPreset = PRESET_FPS_BOOSTED;
    private boolean mHasSavedAccount = false;
    private int mRecommendedHeapMb = 2048;
    @Nullable
    private GraphicsAdvisor.RendererPreset mRecommendedGpuPreset;
    @Nullable
    private Bitmap mLastPreviewBitmap;
    private final SkinHeadRenderer mSkinHeadRenderer = new SkinHeadRenderer();

    private View mStep1View, mStep2View, mStep3View;
    private TextView mPill1, mPill2, mPill3;
    private ImageView mSkinPreview;
    private TextView mPreviewName, mPreviewBadge, mStep1Status;
    private EditText mOfflineEdit;
    private TextView mCalHeadline, mCalGpu, mCalRam, mCalJava, mCalDetail;
    private View mCardVanilla, mCardFps, mCardPvp;
    private TextView mStep3Status;
    public static final String ARG_INITIAL_STEP = "initial_step";
    private static volatile boolean sAwaitingOAuthReturn = false;
    private static volatile boolean sWizardCurrentlyActive = false;

    private Button mBackBtn, mNextBtn;
    private ProgressBar mBusyBar;

    public OnboardingWizardFragment() {
        super(R.layout.fragment_onboarding_wizard);
    }

    /** Consumes and clears the OAuth-from-onboarding return flag so OAuth resumes on Step 2. */
    public static boolean consumePendingOAuthReturn() {
        boolean pending = sAwaitingOAuthReturn;
        sAwaitingOAuthReturn = false;
        return pending;
    }

    /** Clears pending OAuth-from-onboarding state if the user cancels the browser sign-in. */
    public static void cancelPendingOAuthReturn() {
        sAwaitingOAuthReturn = false;
    }

    /**
     * Notified whenever an account finishes signing in. If the login occurred outside the
     * Onboarding Wizard (e.g. via {@link SelectAuthFragment}), immediately marks onboarding
     * complete and initializes the default Latest Fabric instance so a second login screen never appears.
     */
    public static void onAccountAuthenticated() {
        if (!sWizardCurrentlyActive && !sAwaitingOAuthReturn
                && !LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE) {
            markOnboardingCompleted();
            Instances.ensureDefaultLatestFabricAsync();
        }
    }

    /**
     * Checks whether first-time onboarding should be shown. Preserves strict backward
     * compatibility: existing users who already have at least one saved account (or an active
     * in-flight sign-in) keep their setup undisturbed and never see duplicate login screens.
     */
    /** Where an "add account / sign in" request should navigate. */
    public enum AuthRoute { NONE, ONBOARDING, SELECT_AUTH }

    /**
     * Pure routing rule for sign-in requests. Requests are ignored when an auth screen is already
     * showing or the user is not on Home. On first run they coalesce into the onboarding wizard,
     * so SelectAuth and Onboarding are never stacked.
     */
    public static AuthRoute resolveAuthRoute(boolean onAuthScreen, boolean onHome, boolean needsOnboarding) {
        if (onAuthScreen || !onHome) return AuthRoute.NONE;
        return needsOnboarding ? AuthRoute.ONBOARDING : AuthRoute.SELECT_AUTH;
    }

    public static boolean shouldLaunchOnboarding() {
        if (LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE
                || sWizardCurrentlyActive
                || sAwaitingOAuthReturn
                || com.kdt.mcgui.AccountSpinner.isLoginInFlight()) {
            return false;
        }
        try {
            Accounts accounts = Accounts.load();
            boolean hasAccounts = accounts.accounts != null && !accounts.accounts.isEmpty();
            if (hasAccounts) {
                markOnboardingCompleted();
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Persists onboarding completion so the wizard never re-appears unexpectedly. */
    public static void markOnboardingCompleted() {
        LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE = true;
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(LauncherPreferences.KEY_ZYROX_ONBOARDING_DONE, true)
                    .apply();
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStep1View = view.findViewById(R.id.step_1_account);
        mStep2View = view.findViewById(R.id.step_2_calibration);
        mStep3View = view.findViewById(R.id.step_3_preset);
        mPill1 = view.findViewById(R.id.pill_step_1);
        mPill2 = view.findViewById(R.id.pill_step_2);
        mPill3 = view.findViewById(R.id.pill_step_3);

        mSkinPreview = view.findViewById(R.id.iv_onboard_skin_preview);
        mPreviewName = view.findViewById(R.id.tv_onboard_preview_name);
        mPreviewBadge = view.findViewById(R.id.tv_onboard_preview_badge);
        mStep1Status = view.findViewById(R.id.tv_step1_account_status);
        mOfflineEdit = view.findViewById(R.id.et_onboard_offline_name);

        mCalHeadline = view.findViewById(R.id.tv_calibration_headline);
        mCalGpu = view.findViewById(R.id.tv_cal_metric_gpu);
        mCalRam = view.findViewById(R.id.tv_cal_metric_ram);
        mCalJava = view.findViewById(R.id.tv_cal_metric_java);
        mCalDetail = view.findViewById(R.id.tv_calibration_detail);

        mCardVanilla = view.findViewById(R.id.card_preset_vanilla);
        mCardFps = view.findViewById(R.id.card_preset_fps);
        mCardPvp = view.findViewById(R.id.card_preset_pvp);
        mStep3Status = view.findViewById(R.id.tv_step3_status);

        mBackBtn = view.findViewById(R.id.btn_onboarding_back);
        mNextBtn = view.findViewById(R.id.btn_onboarding_next);
        mBusyBar = view.findViewById(R.id.pb_onboarding_busy);

        sWizardCurrentlyActive = true;
        if (savedInstanceState != null) {
            mCurrentStep = savedInstanceState.getInt("step", 1);
            mSelectedPreset = savedInstanceState.getInt("preset", PRESET_FPS_BOOSTED);
        } else if (getArguments() != null && getArguments().containsKey(ARG_INITIAL_STEP)) {
            mCurrentStep = Math.max(1, Math.min(3, getArguments().getInt(ARG_INITIAL_STEP, 1)));
            if (mCurrentStep >= 2) {
                mHasSavedAccount = true;
            }
        }

        // Step 1 listeners (single login flow — OAuth returns directly to Step 2)
        view.findViewById(R.id.btn_onboard_microsoft).setOnClickListener(v -> {
            sAwaitingOAuthReturn = true;
            Tools.swapFragment(requireActivity(),
                    MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null);
        });
        view.findViewById(R.id.btn_onboard_elyby).setOnClickListener(v -> {
            sAwaitingOAuthReturn = true;
            Tools.swapFragment(requireActivity(),
                    ElyByLoginFragment.class, ElyByLoginFragment.TAG, null);
        });

        mOfflineEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateLiveOfflineSkinPreview(s != null ? s.toString().trim() : "");
            }
        });
        mOfflineEdit.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                advanceFromStep1();
                return true;
            }
            return false;
        });

        // Step 3 preset cards
        mCardVanilla.setOnClickListener(v -> selectPreset(PRESET_VANILLA_SMOOTH));
        mCardFps.setOnClickListener(v -> selectPreset(PRESET_FPS_BOOSTED));
        mCardPvp.setOnClickListener(v -> selectPreset(PRESET_PVP_READY));

        // Navigation controls
        view.findViewById(R.id.btn_onboarding_skip).setOnClickListener(v -> {
            sAwaitingOAuthReturn = false;
            markOnboardingCompleted();
            Instances.ensureDefaultLatestFabricAsync();
            Tools.backToMainMenu(requireActivity());
        });
        mBackBtn.setOnClickListener(v -> {
            if (mCurrentStep > 1) {
                showStep(mCurrentStep - 1);
            }
        });
        mNextBtn.setOnClickListener(v -> onPrimaryButtonClicked());

        updateLiveOfflineSkinPreview("Steve");
        selectPreset(mSelectedPreset);
        populateDeviceCalibration(requireContext());
        showStep(mCurrentStep);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSavedAccountState();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("step", mCurrentStep);
        outState.putInt("preset", mSelectedPreset);
    }

    @Override
    public void onDestroyView() {
        sWizardCurrentlyActive = false;
        if (mSkinPreview != null) {
            mSkinPreview.setImageResource(R.drawable.ic_default_steve_head);
        }
        if (mLastPreviewBitmap != null && !mLastPreviewBitmap.isRecycled()) {
            mLastPreviewBitmap.recycle();
            mLastPreviewBitmap = null;
        }
        super.onDestroyView();
    }

    private void refreshSavedAccountState() {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Accounts loaded = Accounts.load();
                if (loaded.accounts == null || loaded.accounts.isEmpty()) {
                    mHasSavedAccount = false;
                    return;
                }
                int idx = Math.max(0, Math.min(loaded.accounts.size() - 1, loaded.selectionIndex));
                Account active = loaded.accounts.get(idx);
                Bitmap face = active.getSkinFace();
                mHasSavedAccount = true;
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (face != null && mSkinPreview != null) {
                        mSkinPreview.setImageBitmap(face);
                    } else {
                        updateLiveOfflineSkinPreview(active.username);
                    }
                    if (mPreviewName != null) mPreviewName.setText(active.username);
                    if (mPreviewBadge != null) {
                        String kind = active.authType == AuthType.MICROSOFT
                                ? "Signed in via Microsoft"
                                : (active.authType == AuthType.ELY_BY
                                ? "Signed in via Ely.by" : "Offline Account Ready");
                        mPreviewBadge.setText(kind);
                    }
                    if (mStep1Status != null) {
                        mStep1Status.setText("Signed in as " + active.username
                                + " — tap Continue to calibrate your device!");
                    }
                });
            } catch (IOException ignored) {
            }
        });
    }

    /**
     * Generates a crisp procedural 64×64 Minecraft character skin from {@code username} and
     * renders its 3D isometric head + outer hat layer via {@link SkinHeadRenderer#render}.
     */
    private void updateLiveOfflineSkinPreview(@NonNull String username) {
        if (mSkinPreview == null) return;
        String seedName = username.isEmpty() ? "Player" : username;
        if (mPreviewName != null) {
            mPreviewName.setText(username.isEmpty() ? "Offline / Local" : username);
        }
        if (mPreviewBadge != null && !username.isEmpty()) {
            mPreviewBadge.setText(USERNAME_PATTERN.matcher(username).matches()
                    ? "Valid username • Live preview"
                    : "Use 3–16 letters, numbers or _");
        }
        int hash = Math.abs(seedName.toLowerCase(Locale.ROOT).hashCode());
        Bitmap skin64 = buildProceduralMinecraftSkin(hash);
        try {
            int sizePx = Math.max(64,
                    Math.round(40f * getResources().getDisplayMetrics().density));
            Bitmap isoHead = mSkinHeadRenderer.render(sizePx, skin64);
            if (isoHead != null) {
                Bitmap old = mLastPreviewBitmap;
                mLastPreviewBitmap = isoHead;
                mSkinPreview.setImageBitmap(isoHead);
                if (old != null && old != isoHead && !old.isRecycled()) {
                    old.recycle();
                }
            }
        } finally {
            skin64.recycle();
        }
    }

    @NonNull
    private static Bitmap buildProceduralMinecraftSkin(int hash) {
        Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(false);

        int skin = SKIN_TONES[hash % SKIN_TONES.length];
        int hair = HAIR_PALETTE[(hash / 7) % HAIR_PALETTE.length];
        int eyes = EYE_PALETTE[(hash / 31) % EYE_PALETTE.length];
        int mouth = 0xFF8C4F3E;

        // Top face (8,0)-(16,8), left face (0,8)-(8,16), front/right face (8,8)-(16,16)
        p.setColor(hair);
        c.drawRect(8, 0, 16, 8, p);
        p.setColor(skin);
        c.drawRect(0, 8, 16, 16, p);

        // Hairline & sideburns on head faces
        p.setColor(hair);
        c.drawRect(0, 8, 8, 11, p);
        c.drawRect(0, 11, 3, 14, p);
        c.drawRect(8, 8, 16, 10, p);
        c.drawRect(8, 10, 9, 12, p);
        c.drawRect(15, 10, 16, 12, p);

        // Minecraft pixel eyes + whites + smile on front face (8..16, 8..16)
        p.setColor(0xFFFFFFFF);
        c.drawRect(9, 12, 10, 13, p);
        c.drawRect(14, 12, 15, 13, p);
        p.setColor(eyes);
        c.drawRect(10, 12, 11, 13, p);
        c.drawRect(13, 12, 14, 13, p);
        p.setColor(mouth);
        c.drawRect(11, 14, 13, 15, p);

        // Subtle 3D outer hat bangs layer (40,0)-(48,8) and (40,8)-(48,11)
        p.setColor(hair);
        c.drawRect(40, 0, 48, 4, p);
        c.drawRect(40, 8, 44, 10, p);
        return bmp;
    }

    private void populateDeviceCalibration(@NonNull Context context) {
        final Context appCtx = context.getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
            int totalRamMb = Math.max(1024, Tools.getTotalDeviceMemory(appCtx));
            int targetRequestMb = Math.min(4096, Math.max(1024, (int) Math.round(totalRamMb * 0.42)));
            int safeHeapMb = GameOptimizer.recommendSafeHeapMb(
                    targetRequestMb, totalRamMb, 0, false,
                    git.artdeell.mojo.Architecture.is64BitsDevice());
            boolean hasVulkan = GpuUtils.checkVulkanSupport(appCtx.getPackageManager());
            boolean hasMesa = android.os.Build.VERSION.SDK_INT >= 29
                    && Tools.NATIVE_LIB_DIR != null
                    && new File(Tools.NATIVE_LIB_DIR, "libEGL_mesa.so").exists();
            boolean hasTurnip = !git.artdeell.mojo.vulkan.DriverManager.getDriverPaths().isEmpty();
            GraphicsAdvisor.RendererPreset preset = GraphicsAdvisor.computeOptimalPreset(
                    gl.architecture,
                    gl.renderer,
                    gl.glesMajorVersion,
                    hasVulkan,
                    hasMesa,
                    hasTurnip,
                    false,
                    true,
                    totalRamMb);
            String plainSummary = GraphicsAdvisor.buildPlainEnglishCalibrationSummary(
                    preset.architecture,
                    preset.rendererId,
                    totalRamMb,
                    safeHeapMb,
                    preset.recommendedScalePercent,
                    hasTurnip && gl.supportsTurnip());
            final String rawGpuName = gl.renderer;

            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mRecommendedHeapMb = safeHeapMb;
                mRecommendedGpuPreset = preset;
                if (mCalHeadline != null) mCalHeadline.setText(plainSummary);
                if (mCalGpu != null) {
                    mCalGpu.setText("GPU Engine\n" + preset.presetName);
                }
                if (mCalRam != null) {
                    mCalRam.setText(String.format(Locale.US,
                            "Safe JVM Heap\n%,d MB / %.1f GB RAM",
                            safeHeapMb, totalRamMb / 1024f));
                }
                if (mCalJava != null) {
                    mCalJava.setText("Java & Guard\nAuto (Java 8 / 17 / 21 / 25)");
                }
                if (mCalDetail != null) {
                    mCalDetail.setText("Hardware: " + rawGpuName
                            + " • " + preset.summary
                            + " Advanced overrides stay tucked away in Pro Mode.");
                }
            });
        });
    }

    private void selectPreset(int preset) {
        mSelectedPreset = preset;
        if (mCardVanilla != null) {
            mCardVanilla.setBackgroundResource(preset == PRESET_VANILLA_SMOOTH
                    ? R.drawable.bg_zyrox_glass_surface : R.drawable.bg_zyrox_glass_card);
        }
        if (mCardFps != null) {
            mCardFps.setBackgroundResource(preset == PRESET_FPS_BOOSTED
                    ? R.drawable.bg_zyrox_glass_surface : R.drawable.bg_zyrox_glass_card);
        }
        if (mCardPvp != null) {
            mCardPvp.setBackgroundResource(preset == PRESET_PVP_READY
                    ? R.drawable.bg_zyrox_glass_surface : R.drawable.bg_zyrox_glass_card);
        }
    }

    private void showStep(int step) {
        mCurrentStep = Math.max(1, Math.min(3, step));
        mStep1View.setVisibility(mCurrentStep == 1 ? View.VISIBLE : View.GONE);
        mStep2View.setVisibility(mCurrentStep == 2 ? View.VISIBLE : View.GONE);
        mStep3View.setVisibility(mCurrentStep == 3 ? View.VISIBLE : View.GONE);

        mPill1.setSelected(mCurrentStep == 1);
        mPill2.setSelected(mCurrentStep == 2);
        mPill3.setSelected(mCurrentStep == 3);
        mPill1.setAlpha(mCurrentStep == 1 ? 1.0f : 0.62f);
        mPill2.setAlpha(mCurrentStep == 2 ? 1.0f : 0.62f);
        mPill3.setAlpha(mCurrentStep == 3 ? 1.0f : 0.62f);

        mBackBtn.setVisibility(mCurrentStep > 1 ? View.VISIBLE : View.INVISIBLE);
        if (mCurrentStep == 1) {
            mNextBtn.setText("Continue →");
        } else if (mCurrentStep == 2) {
            mNextBtn.setText("Apply & Choose Preset →");
        } else {
            mNextBtn.setText("Create Instance & Start Playing");
        }
    }

    private void onPrimaryButtonClicked() {
        if (mCurrentStep == 1) {
            advanceFromStep1();
        } else if (mCurrentStep == 2) {
            applyDeviceCalibrationBaseline();
            showStep(3);
        } else {
            finishOnboardingAndCreateInstance();
        }
    }

    private void advanceFromStep1() {
        String typed = mOfflineEdit != null ? mOfflineEdit.getText().toString().trim() : "";
        if (!typed.isEmpty()) {
            if (!USERNAME_PATTERN.matcher(typed).matches()) {
                mOfflineEdit.setError("Use 3–16 letters, numbers, or _");
                mOfflineEdit.requestFocus();
                return;
            }
            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{typed, ""});
            mHasSavedAccount = true;
            showStep(2);
            return;
        }
        if (mHasSavedAccount) {
            showStep(2);
            return;
        }
        Toast.makeText(requireContext(),
                "Sign in with Microsoft / Ely.by, or enter a 3–16 character username.",
                Toast.LENGTH_SHORT).show();
        if (mOfflineEdit != null) mOfflineEdit.requestFocus();
    }

    private void applyDeviceCalibrationBaseline() {
        Context ctx = getContext();
        if (ctx == null) return;
        GraphicsAdvisor.applyOptimalPreset(ctx, null, null);
        if (mRecommendedHeapMb >= 512 && LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.PREF_RAM_ALLOCATION = mRecommendedHeapMb;
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putInt("allocation", mRecommendedHeapMb)
                    .apply();
        }
    }

    private void finishOnboardingAndCreateInstance() {
        final Context appCtx = requireContext().getApplicationContext();
        final int chosenPreset = mSelectedPreset;
        final int heapMb = mRecommendedHeapMb;
        final GraphicsAdvisor.RendererPreset gpuPreset = mRecommendedGpuPreset;

        mNextBtn.setEnabled(false);
        mBackBtn.setEnabled(false);
        if (mBusyBar != null) mBusyBar.setVisibility(View.VISIBLE);
        if (mStep3Status != null) {
            mStep3Status.setText(chosenPreset == PRESET_VANILLA_SMOOTH
                    ? "Creating your calibrated Vanilla Smooth profile…"
                    : "Setting up Fabric + Zero-Lag Modrinth optimization pack…");
        }

        MojoApplication.sExecutorService.execute(() -> {
            String resolvedVersionId;
            String resolvedMcLabel = null;
            boolean fabricInstalled = false;

            if (chosenPreset == PRESET_FPS_BOOSTED || chosenPreset == PRESET_PVP_READY) {
                try {
                    FabriclikeUtils.ResolvedFabricRelease latestFabric =
                            FabriclikeUtils.FABRIC_UTILS.resolveAndInstallLatestStable();
                    resolvedVersionId = latestFabric.versionId;
                    resolvedMcLabel = latestFabric.minecraftVersion;
                    fabricInstalled = true;
                } catch (Exception ignored) {
                    // Stay on latest Fabric symbolically so first Play click installs Fabric automatically
                    resolvedVersionId = Instance.VERSION_LATEST_FABRIC;
                }
            } else {
                resolvedVersionId = Instance.VERSION_LATEST_RELEASE;
            }

            final String finalVersionId = resolvedVersionId;
            final boolean finalFabric = fabricInstalled;
            final boolean isFabricPreset = (chosenPreset != PRESET_VANILLA_SMOOTH);
            final String profileTitle;
            final String perfPreset;
            if (chosenPreset == PRESET_VANILLA_SMOOTH) {
                profileTitle = "Vanilla Smooth";
                perfPreset = Instance.PRESET_BALANCED;
            } else if (chosenPreset == PRESET_PVP_READY) {
                profileTitle = resolvedMcLabel != null
                        ? "Fabric " + resolvedMcLabel + " (PvP)" : "Fabric (PvP Ready)";
                perfPreset = Instance.PRESET_MAX_FPS;
            } else {
                profileTitle = resolvedMcLabel != null
                        ? "Fabric " + resolvedMcLabel : "Fabric (Latest)";
                perfPreset = Instance.PRESET_MAX_FPS;
            }

            String toastSummary;
            try {
                Instance created = Instances.createInstance(inst -> {
                    inst.name = profileTitle;
                    inst.versionId = finalVersionId;
                    inst.icon = isFabricPreset ? "fabric" : "default";
                    inst.performancePreset = perfPreset;
                    inst.resolutionScalePercent = 100;
                    inst.ramAllocationMb = heapMb;
                    inst.runtimeLocked = false;
                    inst.rendererLocked = false;
                    inst.sharedData = false;
                    if (gpuPreset != null) {
                        inst.renderer = gpuPreset.rendererId;
                        inst.resolutionScalePercent =
                                Math.max(50, Math.min(100, gpuPreset.recommendedScalePercent));
                    }
                }, profileTitle.replace(" ", ""));
                GraphicsAdvisor.ensureOptimalInstanceRenderer(appCtx, created, finalVersionId);

                File gameDir = created.getGameDirectory();
                //noinspection ResultOfMethodCallIgnored
                gameDir.mkdirs();

                if (chosenPreset == PRESET_FPS_BOOSTED || chosenPreset == PRESET_PVP_READY) {
                    GameOptionsUtils.applyZeroLagFpsOptions(
                            gameDir, chosenPreset == PRESET_PVP_READY);
                    if (finalFabric) {
                        try {
                            ModrinthContentInstaller.InstallSummary modRes =
                                    ModrinthContentInstaller.installZeroLagFpsPack(
                                            created, false, null);
                            toastSummary = "Ready! Selected \"" + created.name + "\" ("
                                    + modRes.installedCount + " FPS mods installed).";
                        } catch (Exception netErr) {
                            created.pendingAutoFpsPack = true;
                            created.maybeWrite();
                            toastSummary = "Selected \"" + created.name
                                    + "\" — FPS mods will finish downloading on launch.";
                        }
                    } else {
                        created.pendingAutoFpsPack = true;
                        created.maybeWrite();
                        toastSummary = "Selected \"" + created.name
                                + "\" — latest Fabric will sync automatically on launch!";
                    }
                } else {
                    GameOptionsUtils.applyVanillaSmoothOptions(gameDir);
                    toastSummary = "Ready! Selected calibrated \"" + created.name + "\" instance.";
                }

                Instances.setSelectedInstance(created);
                // Remove any untouched auto-bootstrap placeholder so the spinner stays clutter-free
                try {
                    for (Instance existing : Instances.loadAllInstances()) {
                        if (existing != null && existing != created
                                && existing.sharedData
                                && existing.launchCount == 0
                                && !created.getGameDirectory().equals(existing.getGameDirectory())
                                && (Instances.DEFAULT_FABRIC_PLACEHOLDER_NAME.equals(existing.name)
                                    || Instance.isLatestFabricId(existing.versionId))) {
                            Instances.removeInstance(existing);
                        }
                    }
                } catch (Throwable ignored) {
                }
                LauncherPreferences.PREF_ZYROX_DEFAULT_FABRIC_READY = true;
                if (LauncherPreferences.DEFAULT_PREF != null) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putBoolean(LauncherPreferences.KEY_ZYROX_DEFAULT_FABRIC_READY, true)
                            .apply();
                }
                markOnboardingCompleted();
            } catch (Exception e) {
                markOnboardingCompleted();
                toastSummary = "Setup complete! (" + e.getMessage() + ")";
            }

            final String finalToast = toastSummary;
            Tools.runOnUiThread(() -> {
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                Toast.makeText(appCtx, finalToast, Toast.LENGTH_LONG).show();
                if (!isAdded()) return;
                if (mBusyBar != null) mBusyBar.setVisibility(View.GONE);
                mNextBtn.setEnabled(true);
                mBackBtn.setEnabled(true);
                Tools.backToMainMenu(requireActivity());
            });
        });
    }
}
