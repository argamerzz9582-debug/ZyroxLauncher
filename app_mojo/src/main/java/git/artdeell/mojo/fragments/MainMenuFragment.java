package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Tools.openPath;
import static git.artdeell.mojo.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
import java.util.Locale;

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

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    private TextView mEngineStatusText;

    private final ActivityResultLauncher<Object> mModInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data) -> {
                if (data != null) Tools.launchModInstaller(requireContext(), data);
            });

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Preserved original MojoLauncher View IDs
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.social_media_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);
        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // New Zyrox Left-Dock Command Center items
        Button mModpacksButton = view.findViewById(R.id.modpacks_button);
        Button mInstancesButton = view.findViewById(R.id.instances_button);
        Button mHomeSettingsButton = view.findViewById(R.id.home_settings_button);
        mEngineStatusText = view.findViewById(R.id.tv_zyrox_engine_status);

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        if (mModpacksButton != null) {
            mModpacksButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        }
        if (mInstancesButton != null) {
            mInstancesButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        }
        if (mHomeSettingsButton != null) {
            mHomeSettingsButton.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), LauncherPreferenceFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null));
        }

        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));

        // Tap opens Zyrox Log Inspector; long-press quick-shares latestlog.txt
        mShareLogsButton.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), LogInspectorFragment.class, LogInspectorFragment.TAG, null));
        mShareLogsButton.setOnLongClickListener(v -> {
            shareLog(requireContext());
            return true;
        });

        mOpenDirectoryButton.setOnClickListener(v -> openGameDirectory(v.getContext()));
        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));

        mNewsButton.setOnLongClickListener(v -> {
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });

        updateEngineStatus();
        setupFullScreenGlassPolish(view);
    }

    private void setupFullScreenGlassPolish(@NonNull View root) {
        View rightDock = root.findViewById(R.id.zyrox_left_dock);
        View heroBrand = root.findViewById(R.id.zyrox_hero_brand);
        View updatesCard = root.findViewById(R.id.zyrox_updates_card);

        // Smooth luxury entrance glide on Home Hub reveal
        if (heroBrand != null) {
            heroBrand.setAlpha(0f);
            heroBrand.setTranslationY(-8f);
            heroBrand.animate().alpha(1f).translationY(0f).setDuration(260L).start();
        }
        if (updatesCard != null) {
            updatesCard.setAlpha(0f);
            updatesCard.setTranslationY(10f);
            updatesCard.animate().alpha(1f).translationY(0f).setStartDelay(45L).setDuration(260L).start();
        }
        if (rightDock != null) {
            rightDock.setAlpha(0f);
            rightDock.setTranslationX(12f);
            rightDock.animate().alpha(1f).translationX(0f).setDuration(240L).start();
        }

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // Post layout param changes outside the layout pass to stay 100% jank-free
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
        int safeLeft = cutout != null ? cutout.getSafeInsetLeft() : 0;
        int safeRight = cutout != null ? cutout.getSafeInsetRight() : 0;
        float density = getResources().getDisplayMetrics().density;
        int baseDockW = Math.round(286f * density);
        int pad14 = Math.round(14f * density);
        int pad18 = Math.round(18f * density);
        int pad6 = Math.round(6f * density);

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

        View updatesCard = root.findViewById(R.id.zyrox_updates_card);
        if (updatesCard != null && updatesCard.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) updatesCard.getLayoutParams();
            int targetLeftMargin = Math.max(pad18, safeLeft + pad6);
            if (lp.leftMargin != targetLeftMargin || lp.getMarginStart() != targetLeftMargin) {
                lp.leftMargin = targetLeftMargin;
                lp.setMarginStart(targetLeftMargin);
                updatesCard.setLayoutParams(lp);
            }
        }
    }

    private void updateEngineStatus() {
        if (mEngineStatusText == null) return;
        String renderer = LauncherPreferences.PREF_RENDERER;
        String rendererShort = "GL4ES";
        if (renderer != null) {
            String r = renderer.toLowerCase(Locale.ROOT);
            if (r.contains("zink")) rendererShort = "ZINK VULKAN";
            else if (r.contains("ltw")) rendererShort = "LTW GLES3";
            else if (r.contains("freedreno") || r.contains("kgsl")) rendererShort = "TURNIP KGSL";
            else if (r.contains("mesa")) rendererShort = "MESA DRM";
            else rendererShort = "HOLY GL4ES";
        }
        int ram = LauncherPreferences.PREF_RAM_ALLOCATION > 0 ? LauncherPreferences.PREF_RAM_ALLOCATION : 2048;
        mEngineStatusText.setText(getString(R.string.zyrox_updates_status_fmt, ram, rendererShort));
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
        updateEngineStatus();
    }

    private void runInstallerWithConfirmation() {
        if (!ProgressLayout.ensureNoOngoingTasks(requireContext())) return;
        mModInstallerLauncher.launch(null);
    }
}
