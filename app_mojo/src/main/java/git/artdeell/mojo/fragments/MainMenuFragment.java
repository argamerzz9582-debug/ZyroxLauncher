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
