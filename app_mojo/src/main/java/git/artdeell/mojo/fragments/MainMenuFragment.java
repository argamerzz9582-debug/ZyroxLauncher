package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Tools.openPath;
import static git.artdeell.mojo.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import java.io.File;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.extra.ExtraListener;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceFragment;
import git.artdeell.mojo.servers.ServerListManager;
import git.artdeell.mojo.updater.AppUpdateChecker;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Zyrox 2.0 Home Hub Command Center.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    @Nullable private TextView mInstanceCapsule;
    @Nullable private TextView mUpdatePill;
    @Nullable private TextView mQuickServerPill;
    @Nullable private TextView mWatermarkView;
    private int mTelemetryGeneration = 0;

    private final ExtraListener<String> mVersionRefreshListener = (key, value) -> {
        refreshHomeTelemetryAsync();
        return false;
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mSelectedInstanceObserver =
            (prefs, key) -> {
                if ("selectedInstance".equals(key) || "allocation".equals(key)
                        || LauncherPreferences.KEY_ZYROX_AUTO_UPDATE_CHECK.equals(key)) {
                    refreshHomeTelemetryAsync();
                }
            };

    private final ActivityResultLauncher<Object> mModInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data) -> {
                if (data != null) Tools.launchModInstaller(requireContext(), data);
            });

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Preserved core launcher View IDs
        Button mTrustButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.social_media_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);
        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // Zyrox 2.0 Liquid Glass Studio Command Center tiles
        Button mModpacksButton = view.findViewById(R.id.modpacks_button);
        Button mInstancesButton = view.findViewById(R.id.instances_button);
        Button mModsManagerButton = view.findViewById(R.id.mods_manager_button);
        Button mWorldsButton = view.findViewById(R.id.worlds_button);
        Button mServersButton = view.findViewById(R.id.servers_button);
        Button mSkinsButton = view.findViewById(R.id.skins_button);
        Button mAiFriendButton = view.findViewById(R.id.ai_friend_button);
        Button mDownloadsButton = view.findViewById(R.id.downloads_button);
        Button mHomeSettingsButton = view.findViewById(R.id.home_settings_button);
        mInstanceCapsule = view.findViewById(R.id.tv_home_instance_capsule);
        mUpdatePill = view.findViewById(R.id.tv_home_update_pill);
        mQuickServerPill = view.findViewById(R.id.tv_home_quick_server_pill);
        mWatermarkView = view.findViewById(R.id.tv_zyrox_watermark);

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        attachLiquidPressSpring(mPlayButton);

        if (mInstanceCapsule != null) {
            attachLiquidPressSpring(mInstanceCapsule);
            mInstanceCapsule.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), ContentManagerFragment.class,
                            ContentManagerFragment.TAG, null));
            mInstanceCapsule.setOnLongClickListener(v -> {
                mVersionSpinner.openProfileEditor(requireActivity());
                return true;
            });
        }
        if (mWatermarkView != null) {
            mWatermarkView.setOnClickListener(v -> {
                if (isAdded() && getActivity() != null) {
                    AppUpdateChecker.checkNowInteractive(requireActivity());
                }
            });
        }

        ExtraCore.addExtraListener(ExtraConstants.REFRESH_VERSION_SPINNER, mVersionRefreshListener);
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.registerOnSharedPreferenceChangeListener(
                    mSelectedInstanceObserver);
        }

        // Single unified first-time setup: wait until initial dependency extraction finishes,
        // then present OnboardingWizardFragment once (never overlapping with SelectAuthFragment).
        if (!git.artdeell.mojo.prefs.LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE) {
            git.artdeell.mojo.progresskeeper.ProgressKeeper.waitUntilDone(() -> Tools.runOnUiThread(() -> {
                if (!isAdded() || !isResumed()) return;
                androidx.fragment.app.FragmentActivity host = getActivity();
                if (host == null || host.isFinishing()
                        || host.getSupportFragmentManager().isStateSaved()) {
                    return;
                }
                Fragment current = host.getSupportFragmentManager()
                        .findFragmentById(R.id.container_fragment);
                if (current != MainMenuFragment.this) return;
                if (OnboardingWizardFragment.shouldLaunchOnboarding()) {
                    Tools.swapFragment(
                            host,
                            OnboardingWizardFragment.class,
                            OnboardingWizardFragment.TAG,
                            null);
                }
            }));
        }

        if (mModpacksButton != null) {
            mModpacksButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        }
        if (mInstancesButton != null) {
            mInstancesButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        }
        if (mModsManagerButton != null) {
            mModsManagerButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), ContentManagerFragment.class, ContentManagerFragment.TAG, null));
        }
        if (mWorldsButton != null) {
            mWorldsButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), WorldManagerFragment.class, WorldManagerFragment.TAG, null));
        }
        if (mServersButton != null) {
            mServersButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), ServerManagerFragment.class, ServerManagerFragment.TAG, null));
        }
        if (mSkinsButton != null) {
            mSkinsButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), SkinStudioFragment.class, SkinStudioFragment.TAG, null));
        }
        if (mAiFriendButton != null) {
            mAiFriendButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), AiFriendFragment.class, AiFriendFragment.TAG, null));
        }
        if (mDownloadsButton != null) {
            mDownloadsButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), DownloadCenterFragment.class, DownloadCenterFragment.TAG, null));
        }
        if (mHomeSettingsButton != null) {
            mHomeSettingsButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), LauncherPreferenceFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null));
        }

        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());
        // Tap opens Touch HUD Editor; long-press opens Control Profiles & Auditor
        mCustomControlButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mCustomControlButton.setOnLongClickListener(v -> {
            Bundle args = new Bundle(1);
            args.putInt("tab", 2);
            Tools.swapFragment(requireActivity(), AboutSecurityFragment.class, AboutSecurityFragment.TAG, args);
            return true;
        });

        // Tap opens Zyrox Guard Log Inspector; long-press quick-shares redacted log
        mShareLogsButton.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), LogInspectorFragment.class, LogInspectorFragment.TAG, null));
        mShareLogsButton.setOnLongClickListener(v -> {
            shareLog(requireContext());
            return true;
        });

        // Tap opens Files; long-press opens Screenshots Gallery Studio
        mOpenDirectoryButton.setOnClickListener(v -> openGameDirectory(v.getContext()));
        mOpenDirectoryButton.setOnLongClickListener(v -> {
            Tools.swapFragment(requireActivity(), ScreenshotGalleryFragment.class,
                    ScreenshotGalleryFragment.TAG, null);
            return true;
        });
        // Tap opens Security, Verification & Graphics Center; long-press opens official GitHub repository
        mTrustButton.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), AboutSecurityFragment.class, AboutSecurityFragment.TAG, null));
        mTrustButton.setOnLongClickListener(v -> {
            Tools.openURL(requireActivity(), Tools.URL_HOME);
            return true;
        });
        mDiscordButton.setOnClickListener(v ->
                Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));

        setupFullScreenGlassPolish(view);
        refreshHomeTelemetryAsync();
        bindAvailableUpdateUi(AppUpdateChecker.getAvailableUpdate(requireContext()), false);
    }

    /**
     * Formats the concise Home Hero instance status capsule text (e.g.
     * {@code "Fabric 1.21.4  ·  6 Mods  ·  4096 MB"}).
     */
    @NonNull
    public static String formatHomeInstanceCapsule(@Nullable String loader,
                                                   @Nullable String mcVersion,
                                                   int enabledModCount,
                                                   int ramMb) {
        String loaderName = Instance.formatLoaderDisplayName(loader);
        String ver = (mcVersion != null && !mcVersion.trim().isEmpty()) ? mcVersion.trim() : "";
        StringBuilder sb = new StringBuilder();
        sb.append(loaderName);
        if (!ver.isEmpty()) {
            sb.append(' ').append(ver);
        }
        if (enabledModCount > 0) {
            sb.append("  ·  ").append(enabledModCount)
                    .append(enabledModCount == 1 ? " Mod" : " Mods");
        }
        if (ramMb > 0) {
            sb.append("  ·  ").append(ramMb).append(" MB");
        }
        return sb.toString();
    }

    private void refreshHomeTelemetryAsync() {
        if (!isAdded()) return;
        final int gen = ++mTelemetryGeneration;
        MojoApplication.sExecutorService.execute(() -> {
            Instance inst = Instances.loadSelectedInstance();
            if (inst == null) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || gen != mTelemetryGeneration) return;
                    if (mInstanceCapsule != null) mInstanceCapsule.setVisibility(View.GONE);
                    if (mQuickServerPill != null) mQuickServerPill.setVisibility(View.GONE);
                });
                return;
            }
            String rawVersion = inst.versionId;
            String loader = Instance.detectModLoader(rawVersion);
            String mcVer = Instance.resolveCleanMinecraftVersion(rawVersion);
            if (mcVer.isEmpty() && Instance.isLatestFabricId(rawVersion)) {
                mcVer = "Latest";
            } else if (mcVer.isEmpty() && Instance.isLatestReleaseId(rawVersion)) {
                mcVer = "Latest";
            } else if (mcVer.isEmpty() && Instance.isLatestSnapshotId(rawVersion)) {
                mcVer = "Snapshot";
            }
            int activeMods = countEnabledMods(inst.getGameDirectory());
            int ramMb = inst.ramAllocationMb > 0 ? inst.ramAllocationMb : LauncherPreferences.PREF_RAM_ALLOCATION;
            final String capsuleText = formatHomeInstanceCapsule(loader, mcVer, activeMods, ramMb);

            // Resolve favorite or most-recent saved server for 1-tap Home Quick Join
            String quickName = null;
            String quickAddr = null;
            String quickProfile = null;
            try {
                File gameDir = inst.getGameDirectory();
                List<ServerListManager.ServerEntry> servers = ServerListManager.loadServers(gameDir);
                ServerListManager.ServerEntry chosen = null;
                for (ServerListManager.ServerEntry s : servers) {
                    if (s != null && !s.hidden && s.favorite && Tools.isValidString(s.address)) {
                        chosen = s;
                        break;
                    }
                }
                if (chosen == null) {
                    for (ServerListManager.ServerEntry s : servers) {
                        if (s != null && !s.hidden && Tools.isValidString(s.address)) {
                            chosen = s;
                            break;
                        }
                    }
                }
                if (chosen != null) {
                    quickName = Tools.isValidString(chosen.name) ? chosen.name.trim() : chosen.address.trim();
                    quickAddr = chosen.address.trim();
                    quickProfile = chosen.controlProfile;
                }
            } catch (Throwable ignored) {
            }

            final String fQuickName = quickName;
            final String fQuickAddr = quickAddr;
            final String fQuickProfile = quickProfile;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || gen != mTelemetryGeneration) return;
                if (mInstanceCapsule != null) {
                    mInstanceCapsule.setText(capsuleText);
                    mInstanceCapsule.setVisibility(View.VISIBLE);
                }
                if (mQuickServerPill != null) {
                    if (fQuickAddr != null && !fQuickAddr.isEmpty()) {
                        mQuickServerPill.setText("⚡ QUICK JOIN  ·  " + fQuickName);
                        mQuickServerPill.setVisibility(View.VISIBLE);
                        attachLiquidPressSpring(mQuickServerPill);
                        mQuickServerPill.setOnClickListener(v -> {
                            ServerListManager.setPendingQuickConnect(fQuickAddr, fQuickProfile);
                            Toast.makeText(requireContext(),
                                    "Joining " + fQuickName + "…", Toast.LENGTH_SHORT).show();
                            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                        });
                        mQuickServerPill.setOnLongClickListener(v -> {
                            Tools.swapFragment(requireActivity(), ServerManagerFragment.class,
                                    ServerManagerFragment.TAG, null);
                            return true;
                        });
                    } else {
                        mQuickServerPill.setVisibility(View.GONE);
                    }
                }
            });
        });
    }

    private static int countEnabledMods(@Nullable File gameDir) {
        if (gameDir == null) return 0;
        File modsDir = new File(gameDir, "mods");
        File[] files = modsDir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f != null && f.isFile()
                    && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                count++;
            }
        }
        return count;
    }

    /** Triggered by {@link LauncherActivity} when internet connectivity comes online mid-session. */
    public void onConnectivityRestored() {
        triggerAutomaticUpdateCheck();
    }

    private void triggerAutomaticUpdateCheck() {
        if (!isAdded() || getContext() == null) return;
        // Run automatic update popup once first-time onboarding isn't actively taking over screen
        if (OnboardingWizardFragment.shouldLaunchOnboarding()) return;

        AppUpdateChecker.checkSilentlyIfDue(requireContext(), (info, err) -> {
            if (!isAdded()) return;
            bindAvailableUpdateUi(info, true);
        });
    }

    private void bindAvailableUpdateUi(@Nullable AppUpdateChecker.ReleaseInfo info,
                                       boolean allowAutoModal) {
        if (!isAdded() || getContext() == null) return;
        boolean hasUpdate = (info != null && info.updateAvailable);

        if (mUpdatePill != null) {
            if (hasUpdate) {
                String tag = info.tagName.startsWith("v") || info.tagName.startsWith("V")
                        ? info.tagName : ("v" + info.versionClean);
                mUpdatePill.setText("⬆ UPDATE " + tag.toUpperCase(Locale.ROOT)
                        + " READY  ·  TAP TO INSTALL");
                mUpdatePill.setVisibility(View.VISIBLE);
                attachLiquidPressSpring(mUpdatePill);
                mUpdatePill.setOnClickListener(v -> {
                    if (isAdded() && getActivity() != null) {
                        AppUpdateChecker.markAutoPromptShown(info.tagName);
                        AppUpdateChecker.showUpdateDialog(requireActivity(), info);
                    }
                });
            } else {
                mUpdatePill.setVisibility(View.GONE);
            }
        }

        if (mWatermarkView != null) {
            if (hasUpdate) {
                String tag = info.tagName.startsWith("v") || info.tagName.startsWith("V")
                        ? info.tagName : ("v" + info.versionClean);
                mWatermarkView.setText("ZYROX v" + BuildConfig.VERSION_NAME
                        + "  •  ⬆ " + tag.toUpperCase(Locale.ROOT));
            } else {
                mWatermarkView.setText(R.string.zyrox_engine_watermark);
            }
        }

        if (allowAutoModal && hasUpdate && isResumed() && getActivity() != null) {
            Fragment current = getActivity().getSupportFragmentManager()
                    .findFragmentById(R.id.container_fragment);
            if (current == this && AppUpdateChecker.shouldShowAutoModalNow(requireContext(), info)) {
                if (AppUpdateChecker.showUpdateDialog(requireActivity(), info)) {
                    AppUpdateChecker.markAutoPromptShown(info.tagName);
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachLiquidPressSpring(@NonNull View target) {
        target.setOnTouchListener((v, ev) -> {
            if (getContext() == null || LauncherPreferences.isReducedMotionEnabled(getContext())) {
                return false;
            }
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(85L).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start();
            }
            return false;
        });
    }

    private void setupFullScreenGlassPolish(@NonNull View root) {
        View rightDock = root.findViewById(R.id.zyrox_left_dock);
        View heroBrand = root.findViewById(R.id.zyrox_hero_brand);

        if (!LauncherPreferences.isReducedMotionEnabled(requireContext())) {
            if (heroBrand != null) {
                heroBrand.setAlpha(0f);
                heroBrand.setTranslationY(-8f);
                heroBrand.animate().alpha(1f).translationY(0f).setDuration(240L).start();
            }
            if (rightDock != null) {
                rightDock.setAlpha(0f);
                rightDock.setTranslationX(12f);
                rightDock.animate().alpha(1f).translationX(0f).setDuration(220L).start();
            }
        }

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || r != or || t != ot || b != ob) {
                v.post(this::refreshCutoutSafeZones);
            }
        });
        root.post(this::refreshCutoutSafeZones);
    }

    public void refreshCutoutSafeZones() {
        View root = getView();
        if (root == null || !isAdded() || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return;
        android.view.WindowInsets rootInsets = requireActivity().getWindow().getDecorView().getRootWindowInsets();
        if (rootInsets == null) return;
        android.view.DisplayCutout cutout = rootInsets.getDisplayCutout();
        int safeRight = cutout != null ? cutout.getSafeInsetRight() : 0;
        float density = getResources().getDisplayMetrics().density;
        int baseDockW = Math.round(286f * density);
        int pad14 = Math.round(14f * density);

        View rightDock = root.findViewById(R.id.zyrox_left_dock);
        if (rightDock != null) {
            android.view.ViewGroup.LayoutParams lp = rightDock.getLayoutParams();
            int targetW = baseDockW + safeRight;
            int targetPadRight = pad14 + safeRight;
            if (lp.width != targetW) {
                lp.width = targetW;
                rightDock.setLayoutParams(lp);
            }
            if (rightDock.getPaddingRight() != targetPadRight) {
                rightDock.setPadding(pad14, rightDock.getPaddingTop(), targetPadRight, rightDock.getPaddingBottom());
            }
        }
    }

    private void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if (FileUtils.ensureDirectorySilently(gameDirectory)) {
            openPath(context, gameDirectory, false);
        } else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
        refreshHomeTelemetryAsync();
        // Present any pending update popup that arrived while paused/in-dialog & check GitHub live!
        bindAvailableUpdateUi(AppUpdateChecker.getAvailableUpdate(requireContext()), true);
        triggerAutomaticUpdateCheck();
    }

    @Override
    public void onDestroyView() {
        ExtraCore.removeExtraListenerFromValue(
                ExtraConstants.REFRESH_VERSION_SPINNER, mVersionRefreshListener);
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.unregisterOnSharedPreferenceChangeListener(
                    mSelectedInstanceObserver);
        }
        mInstanceCapsule = null;
        mUpdatePill = null;
        mQuickServerPill = null;
        mWatermarkView = null;
        super.onDestroyView();
    }

    private void runInstallerWithConfirmation() {
        if (!ProgressLayout.ensureNoOngoingTasks(requireContext())) return;
        mModInstallerLauncher.launch(null);
    }
}
