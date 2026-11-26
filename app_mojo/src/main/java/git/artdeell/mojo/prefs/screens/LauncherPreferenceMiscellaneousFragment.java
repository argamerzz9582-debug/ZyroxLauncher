package git.artdeell.mojo.prefs.screens;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.kdt.mcgui.ProgressLayout;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.tasks.AsyncVersionList;
import git.artdeell.mojo.tasks.DataMigrator;
import git.artdeell.mojo.ui.ZyroxShaderBackgroundView;

public class LauncherPreferenceMiscellaneousFragment extends LauncherPreferenceBaseFragment {

    private final ActivityResultLauncher<Uri> mMigrateLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), (uri) -> {
                if (uri != null) {
                    new AlertDialog.Builder(getLauncherActivity())
                            .setTitle(R.string.migration_progress_warning_title)
                            .setMessage(R.string.migration_progress_warning_summary)
                            .setPositiveButton(android.R.string.ok, (d, w) -> new DataMigrator(getLauncherActivity(), uri).migrateData())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            }
    );

    private final ActivityResultLauncher<String[]> mSelectBgVideoLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), (uri) -> {
                if (uri == null || getContext() == null) return;
                Context appCtx = getContext().getApplicationContext();
                MojoApplication.sExecutorService.execute(() -> {
                    String mime = appCtx.getContentResolver().getType(uri);
                    boolean isGif = (mime != null && mime.toLowerCase(java.util.Locale.ROOT).contains("gif"))
                            || uri.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
                    File gifDest = new File(Tools.DIR_GAME_HOME, ZyroxShaderBackgroundView.CUSTOM_GIF_FILENAME);
                    File videoDest = new File(Tools.DIR_GAME_HOME, ZyroxShaderBackgroundView.CUSTOM_VIDEO_FILENAME);
                    File dest = isGif ? gifDest : videoDest;
                    File stale = isGif ? videoDest : gifDest;
                    try (InputStream in = appCtx.getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(dest)) {
                        if (in == null) return;
                        IOUtils.copy(in, out);
                        out.flush();
                        if (stale.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            stale.delete();
                        }
                        Tools.runOnUiThread(() -> {
                            ExtraCore.setValue(ExtraConstants.REFRESH_BG_VIDEO, true);
                            if (isAdded()) updateVisibility();
                        });
                    } catch (IOException e) {
                        Tools.showErrorRemote(e);
                    }
                });
            }
    );

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        mVisibilityUpdater = this::updateVisibility;
        addPreferencesFromResource(R.xml.pref_misc);

        Preference dimPref = findPreference(git.artdeell.mojo.ui.ZyroxBackdrops.PREF_DIM);
        if (dimPref instanceof git.artdeell.mojo.prefs.CustomSeekBarPreference) {
            ((git.artdeell.mojo.prefs.CustomSeekBarPreference) dimPref).setSuffix(" %");
        }

        Preference checkUpdatesPref = findPreference("zyroxCheckUpdatesNow");
        if (checkUpdatesPref != null) {
            git.artdeell.mojo.updater.AppUpdateChecker.ReleaseInfo avail =
                    getContext() != null
                            ? git.artdeell.mojo.updater.AppUpdateChecker.getAvailableUpdate(getContext())
                            : null;
            if (avail != null && avail.updateAvailable) {
                checkUpdatesPref.setSummary("Update " + avail.tagName
                        + " available! (Installed: v" + git.artdeell.mojo.BuildConfig.VERSION_NAME
                        + ") — tap to install");
            } else {
                checkUpdatesPref.setSummary("Installed: v" + git.artdeell.mojo.BuildConfig.VERSION_NAME
                        + " — tap to check GitHub Releases now");
            }
            checkUpdatesPref.setOnPreferenceClickListener(p -> {
                if (getActivity() != null) {
                    git.artdeell.mojo.updater.AppUpdateChecker.checkNowInteractive(getActivity());
                }
                return true;
            });
        }

        Preference gifFilterPref = findPreference(git.artdeell.mojo.prefs.LauncherPreferences.KEY_ZYROX_GIF_FILTER);
        if (gifFilterPref != null) {
            gifFilterPref.setOnPreferenceChangeListener((pref, newVal) -> {
                if (newVal instanceof String) {
                    git.artdeell.mojo.prefs.LauncherPreferences.persistGifFilter((String) newVal);
                    ExtraCore.setValue(ExtraConstants.REFRESH_BG_VIDEO, true);
                }
                return true;
            });
        }

        Preference selectVideoPref = requirePreference("selectBgVideo");
        selectVideoPref.setOnPreferenceClickListener(p -> {
            mSelectBgVideoLauncher.launch(new String[]{"video/*", "image/gif"});
            return true;
        });

        Preference resetVideoPref = requirePreference("resetBgVideo");
        resetVideoPref.setOnPreferenceClickListener(p -> {
            File videoDest = new File(Tools.DIR_GAME_HOME, ZyroxShaderBackgroundView.CUSTOM_VIDEO_FILENAME);
            File gifDest = new File(Tools.DIR_GAME_HOME, ZyroxShaderBackgroundView.CUSTOM_GIF_FILENAME);
            if (videoDest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                videoDest.delete();
            }
            if (gifDest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                gifDest.delete();
            }
            ExtraCore.setValue(ExtraConstants.REFRESH_BG_VIDEO, true);
            updateVisibility();
            return true;
        });

        Preference importPreference = requirePreference("runDataMigration");
        importPreference.setOnPreferenceClickListener(preference -> {
            if (!ProgressLayout.ensureNoOngoingTasks(getContext())) return true;
            mMigrateLauncher.launch(null);
            return true;
        });

        setupNotificationRequestPreference();
        setupCacheClearPreference();
        setupMicrophoneRequestPreference();
        updateVisibility();
    }

    private void updateVisibility() {
        LauncherActivity activity = getLauncherActivity();
        if (activity != null) {
            requirePreference("microphoneAccessRequest").setVisible(!activity.checkForPermissionRationale(33, Manifest.permission.RECORD_AUDIO));
            requirePreference("notification_permission_request").setVisible(!activity.checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS));
        }
        requirePreference("clearMetadataCache").setVisible(new File(Tools.DIR_CACHE, "string_cache").exists());
        File customVideo = new File(Tools.DIR_GAME_HOME, ZyroxShaderBackgroundView.CUSTOM_VIDEO_FILENAME);
        File customGif = new File(Tools.DIR_GAME_HOME, ZyroxShaderBackgroundView.CUSTOM_GIF_FILENAME);
        requirePreference("resetBgVideo").setVisible(customVideo.isFile() || customGif.isFile());
    }

    private void setupNotificationRequestPreference() {
        Preference pref = requirePreference("notification_permission_request");
        Activity activity = getActivity();
        if (activity instanceof LauncherActivity) {
            pref.setOnPreferenceClickListener(p -> {
                ((LauncherActivity) activity).askForPermission(33, Manifest.permission.POST_NOTIFICATIONS);
                return true;
            });
        } else {
            pref.setVisible(false);
        }
    }

    private void setupMicrophoneRequestPreference() {
        Preference mRequestMicrophonePermissionPreference = requirePreference("microphoneAccessRequest");
        Activity activity = getActivity();
        if (activity instanceof LauncherActivity) {
            mRequestMicrophonePermissionPreference.setOnPreferenceClickListener(preference -> {
                ((LauncherActivity) activity).askForPermission(23, Manifest.permission.RECORD_AUDIO);
                return true;
            });
        } else {
            mRequestMicrophonePermissionPreference.setVisible(false);
        }
    }

    private void setupCacheClearPreference() {
        Preference clearPreference = requirePreference("clearMetadataCache");
        clearPreference.setOnPreferenceClickListener(preference -> {
            if (!ProgressLayout.ensureNoOngoingTasks(getContext())) return true;
            MojoApplication.sExecutorService.submit(() -> {
                try {
                    VersionMetadataRepository.getInstance().clearAllCaches();
                    FileUtils.deleteDirectory(new File(Tools.DIR_CACHE, "string_cache"));
                } catch (IOException e) {
                    Tools.showErrorRemote(getLauncherActivity(), R.string.preference_metadata_clear_fail, e);
                    return;
                }
                new AsyncVersionList().getVersionList(
                        versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions));
                Tools.runOnUiThread(() -> {
                    if (getLauncherActivity() != null) {
                        Toast.makeText(getLauncherActivity(), R.string.preference_metadata_clear_complete, Toast.LENGTH_LONG).show();
                    }
                    if (isAdded()) updateVisibility();
                });
            });
            return true;
        });
    }
}
