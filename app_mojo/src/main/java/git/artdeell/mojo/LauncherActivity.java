package git.artdeell.mojo;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.extra.ExtraListener;
import git.artdeell.mojo.fragments.ElyByLoginFragment;
import git.artdeell.mojo.fragments.FabriclikeInstallFragment;
import git.artdeell.mojo.fragments.FileSelectorFragment;
import git.artdeell.mojo.fragments.GamepadMapperFragment;
import git.artdeell.mojo.fragments.InstanceEditorFragment;
import git.artdeell.mojo.fragments.LocalLoginFragment;
import git.artdeell.mojo.fragments.LogInspectorFragment;
import git.artdeell.mojo.fragments.MainMenuFragment;
import git.artdeell.mojo.fragments.MicrosoftLoginFragment;
import git.artdeell.mojo.fragments.ModVersionListFragment;
import git.artdeell.mojo.fragments.ProfileTypeSelectFragment;
import git.artdeell.mojo.fragments.SearchModFragment;
import git.artdeell.mojo.fragments.SelectAuthFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.imagecache.IconCacheJanitor;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceFragment;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.progresskeeper.TaskCountListener;
import git.artdeell.mojo.services.ProgressServiceKeeper;
import git.artdeell.mojo.tasks.AsyncVersionList;
import git.artdeell.mojo.tasks.mojson.MoJsonDownloader;
import git.artdeell.mojo.tasks.mojson.MoJsonExtras;
import git.artdeell.mojo.ui.ZyroxShaderBackgroundView;
import git.artdeell.mojo.utils.NotificationUtils;
import git.artdeell.mojo.utils.lifecycle.ContextAwareDoneListener;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;

public class LauncherActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";

    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton;
    private ProgressLayout mProgressLayout;
    private ZyroxShaderBackgroundView mShaderBackground;
    private View mGlobalHeader;
    private View mAccountDockCard;
    private TextView mBackButton;
    private TextView mPageTitleView;
    private TextView mHeaderTasksView;
    private ProgressBar mHeaderProgressView;
    private Button mTopActionButton;
    private ImageButton mOpenAccountVaultButton;

    private ProgressServiceKeeper mProgressServiceKeeper;
    private NotificationManager mNotificationManager;
    private int mActiveTaskCount = 0;
    private static ActivityResultLauncher<String> mRequestPermissionLauncher;

    /* Updates Zyrox Global Shell Header Bar & Home Left-Dock Account Card per active screen */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            if (fm != getSupportFragmentManager()) return;
            if (f.getId() != R.id.container_fragment) return;
            updateGlobalShellForFragment(f);
        }
    };

    private void updateGlobalShellForFragment(@NonNull Fragment f) {
        boolean isHome = f instanceof MainMenuFragment;
        mSettingsButton.setImageDrawable(ContextCompat.getDrawable(getBaseContext(),
                isHome ? R.drawable.ic_px_sliders : R.drawable.ic_px_home));

        if (mAccountDockCard != null) {
            mAccountDockCard.setVisibility(isHome ? View.VISIBLE : View.GONE);
        }
        if (mGlobalHeader != null) {
            mGlobalHeader.setVisibility(isHome ? View.GONE : View.VISIBLE);
        }
        updateHeaderTaskIndicators();

        if (isHome || mPageTitleView == null || mTopActionButton == null) return;

        // Default: hide contextual top action unless screen provides one
        mTopActionButton.setVisibility(View.GONE);
        mTopActionButton.setOnClickListener(null);

        if (f instanceof InstanceEditorFragment) {
            mPageTitleView.setText(R.string.zyrox_title_instance_studio);
            mTopActionButton.setText(R.string.zyrox_new_instance);
            mTopActionButton.setVisibility(View.VISIBLE);
            mTopActionButton.setOnClickListener(v ->
                    Tools.swapFragment(this, ProfileTypeSelectFragment.class, ProfileTypeSelectFragment.TAG, null));
        } else if (f instanceof ProfileTypeSelectFragment) {
            mPageTitleView.setText(R.string.zyrox_title_create_instance);
        } else if (f instanceof SearchModFragment) {
            mPageTitleView.setText(R.string.zyrox_title_mod_store);
            mTopActionButton.setText(R.string.zyrox_import_mrpack);
            mTopActionButton.setVisibility(View.VISIBLE);
            mTopActionButton.setOnClickListener(v -> ((SearchModFragment) f).triggerLocalImport());
        } else if (f instanceof LauncherPreferenceFragment) {
            mPageTitleView.setText(R.string.zyrox_title_control_center);
        } else if (f instanceof SelectAuthFragment || f instanceof LocalLoginFragment) {
            mPageTitleView.setText(R.string.zyrox_title_account_vault);
        } else if (f instanceof MicrosoftLoginFragment) {
            mPageTitleView.setText(R.string.zyrox_title_microsoft_login);
        } else if (f instanceof ElyByLoginFragment) {
            mPageTitleView.setText(R.string.zyrox_title_elyby_login);
        } else if (f instanceof FabriclikeInstallFragment || f instanceof ModVersionListFragment) {
            mPageTitleView.setText(R.string.zyrox_title_modloader_install);
        } else if (f instanceof FileSelectorFragment) {
            mPageTitleView.setText(R.string.zyrox_title_file_browser);
        } else if (f instanceof GamepadMapperFragment) {
            mPageTitleView.setText(R.string.zyrox_title_gamepad);
        } else if (f instanceof LogInspectorFragment) {
            mPageTitleView.setText(R.string.zyrox_title_log_inspector);
        } else {
            mPageTitleView.setText(R.string.app_short_name);
        }
    }

    private void updateHeaderTaskIndicators() {
        boolean headerVisible = mGlobalHeader != null && mGlobalHeader.getVisibility() == View.VISIBLE;
        boolean showTasks = headerVisible && mActiveTaskCount > 0;
        if (mHeaderTasksView != null) {
            mHeaderTasksView.setVisibility(showTasks ? View.VISIBLE : View.GONE);
            if (showTasks) {
                mHeaderTasksView.setText(getString(R.string.progresslayout_tasks_in_progress, mActiveTaskCount));
            }
        }
        if (mHeaderProgressView != null) {
            mHeaderProgressView.setVisibility(showTasks ? View.VISIBLE : View.GONE);
        }
    }

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if (value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for refreshing shader / looping video background */
    private final ExtraListener<Boolean> mRefreshBgListener = (key, value) -> {
        if (mShaderBackground != null) {
            Tools.runOnUiThread(() -> mShaderBackground.refreshBackgroundState());
        }
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        FragmentManager manager = getSupportFragmentManager();
        if (!value || manager.isStateSaved()) return false;
        Fragment fragment = manager.findFragmentById(mFragmentView.getId());
        if (fragment instanceof SelectAuthFragment) return false;
        if (!(fragment instanceof MainMenuFragment)) return false;

        Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        return false;
    };

    /* Listener for the settings/home header button */
    private final View.OnClickListener mSettingButtonListener = v -> {
        FragmentManager manager = getSupportFragmentManager();
        if (manager.isStateSaved()) return;
        Fragment fragment = manager.findFragmentById(mFragmentView.getId());
        if (fragment instanceof MainMenuFragment) {
            Tools.swapFragment(this, LauncherPreferenceFragment.class, SETTING_FRAGMENT_TAG, null);
        } else {
            Tools.backToMainMenu(this);
        }
    };

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if (!ProgressLayout.ensureNoOngoingTasks(this)) return false;

        Instance selectedInstance = Instances.loadSelectedInstance();

        if (selectedInstance == null) {
            Toast.makeText(this, R.string.no_instance, Toast.LENGTH_LONG).show();
            return false;
        }

        if (selectedInstance.installer != null) {
            selectedInstance.installer.start();
            return false;
        }

        if (!Tools.isValidString(selectedInstance.versionId)) {
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        if (Accounts.getCurrent() == null) {
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }
        String normalizedVersionId = MoJsonExtras.normalizeVersionId(selectedInstance.versionId);
        JVersionList.Version mcVersion = MoJsonExtras.getListedVersion(normalizedVersionId);
        new MoJsonDownloader().start(
                this.getAssets(),
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId)
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        mActiveTaskCount = taskCount;
        Tools.runOnUiThread(() -> {
            if (taskCount > 0 && mNotificationManager != null) {
                mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START);
            }
            updateHeaderTaskIndicators();
        });
        return false;
    };

    @Override
    protected boolean shouldIgnoreNotch() {
        return getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean setFullscreen() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        String fragmentName = pref.getFragment();
        if (fragmentName == null) return false;
        try {
            Class<? extends Fragment> clazz = (Class<? extends Fragment>) Class.forName(fragmentName);
            Tools.swapFragment(this, clazz, clazz.getSimpleName(), pref.getExtras());
            return true;
        } catch (ClassNotFoundException e) {
            Tools.showError(this, e);
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mojo_ui);
        MoJsonDownloader.prepareSubstitutionMap(getAssets());

        try {
            Os.setenv("TMPDIR", Tools.DIR_CACHE.getAbsolutePath(), true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        IconCacheJanitor.runJanitor();

        getWindow().setBackgroundDrawable(null);
        bindViews();
        mRequestPermissionLauncher = this.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if (!isAllowed) Tools.runOnUiThread(() -> Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show());
                }
        );
        checkNotificationPermission();
        if (LauncherPreferences.PREF_MIGRATION_NOTICE)
            MojoApplication.sExecutorService.submit(this::checkPreviousInstalls);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);
        if (mBackButton != null) {
            mBackButton.setOnClickListener(v -> onBackPressed());
        }
        if (mOpenAccountVaultButton != null) {
            mOpenAccountVaultButton.setOnClickListener(v -> {
                FragmentManager manager = getSupportFragmentManager();
                if (!manager.isStateSaved()) {
                    Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
                }
            });
        }
        if (mHeaderTasksView != null) {
            mHeaderTasksView.setOnClickListener(v -> {
                if (mProgressLayout != null) mProgressLayout.performClick();
            });
        }

        ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.addExtraListener(ExtraConstants.REFRESH_BG_VIDEO, mRefreshBgListener);
        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions));

        mProgressLayout.observe(ProgressLayout.DOWNLOAD_GAME);
        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
        mProgressLayout.observe(ProgressLayout.AUTHENTICATE);
        mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        mProgressLayout.observe(ProgressLayout.INSTANCE_INSTALL);
        mProgressLayout.observe(ProgressLayout.DATA_MIGRATION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        InstanceInstaller.postInstallCheck(this);
        if (mShaderBackground != null) {
            mShaderBackground.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (mShaderBackground != null) {
            mShaderBackground.onPause();
        }
        super.onPause();
        ContextExecutor.clearActivity();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, false);
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container_fragment);
        if (current != null) {
            updateGlobalShellForFragment(current);
        }
    }

    @Override
    protected void onStop() {
        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.REFRESH_BG_VIDEO, mRefreshBgListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);
    }

    @Override
    public void onBackPressed() {
        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if (fragment != null && fragment.canGoBack()) {
            fragment.goBack();
            return;
        }
        SearchModFragment searchModFragment = (SearchModFragment) getVisibleFragment(SearchModFragment.TAG);
        if (searchModFragment != null && searchModFragment.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    private Fragment getVisibleFragment(String tag) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    public void askForPermission(int minApi, final String permission) {
        if (Build.VERSION.SDK_INT < minApi) return;
        mRequestPermissionLauncher.launch(permission);
    }

    public boolean checkForPermission(int minApi, final String permission) {
        return Build.VERSION.SDK_INT < minApi ||
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_DENIED;
    }

    public boolean checkForPermissionRationale(int minApi, final String permission) {
        return checkForPermission(minApi, permission) || ActivityCompat.shouldShowRequestPermissionRationale(this, permission);
    }

    private void checkNotificationPermission() {
        if (LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
                this.checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS)) {
            return;
        }
        showNotificationPermissionReasoning();
    }

    private void checkPreviousInstalls() {
        final String[] packages = {"git.artdeell.mojo", "git.artdeell.mojo.debug", "git.artdeell.mojo.pub"};
        for (String s : packages) {
            Intent i = getPackageManager().getLaunchIntentForPackage(s);
            if (i == null) continue;
            Tools.runOnUiThread(() ->
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.migration_progress_warning_title)
                            .setMessage(R.string.migration_notice)
                            .setPositiveButton(android.R.string.ok, (d, button) -> LauncherPreferences.DEFAULT_PREF.edit().putBoolean("migrationNotice", false).apply())
                            .setOnDismissListener(d -> LauncherPreferences.PREF_MIGRATION_NOTICE = false)
                            .show());
            break;
        }
    }

    private void showNotificationPermissionReasoning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        askForPermission(33, Manifest.permission.POST_NOTIFICATIONS))
                .setNegativeButton(android.R.string.cancel, (d, w) -> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
    }

    private void bindViews() {
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mProgressLayout = findViewById(R.id.progress_layout);
        mShaderBackground = findViewById(R.id.zyrox_shader_background);
        mGlobalHeader = findViewById(R.id.zyrox_global_header);
        mAccountDockCard = findViewById(R.id.zyrox_account_dock_card);
        mBackButton = findViewById(R.id.btn_zyrox_back);
        mPageTitleView = findViewById(R.id.tv_zyrox_page_title);
        mHeaderTasksView = findViewById(R.id.tv_zyrox_header_tasks);
        mHeaderProgressView = findViewById(R.id.zyrox_header_progress);
        mTopActionButton = findViewById(R.id.btn_zyrox_top_action);
        mOpenAccountVaultButton = findViewById(R.id.btn_open_account_vault);
    }
}
