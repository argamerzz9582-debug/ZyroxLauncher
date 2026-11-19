package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Tools.openPath;
import static git.artdeell.mojo.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import java.io.File;

import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceFragment;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Zyrox 2.0 Home Hub Command Center.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;

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
        Button mDownloadsButton = view.findViewById(R.id.downloads_button);
        Button mHomeSettingsButton = view.findViewById(R.id.home_settings_button);

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

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

        // Throttled GitHub Releases in-app auto-updater check (runs once onboarding is complete)
        if (LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE) {
            git.artdeell.mojo.updater.AppUpdateChecker.checkSilentlyIfDue(
                    requireContext(), (info, err) -> {
                        if (isAdded() && isResumed() && getActivity() != null
                                && info != null && info.updateAvailable) {
                            git.artdeell.mojo.updater.AppUpdateChecker.showUpdateDialog(
                                    requireActivity(), info);
                        }
                    });
        }
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
    }

    private void runInstallerWithConfirmation() {
        if (!ProgressLayout.ensureNoOngoingTasks(requireContext())) return;
        mModInstallerLauncher.launch(null);
    }
}
