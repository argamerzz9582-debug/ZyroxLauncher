package git.artdeell.mojo.game;

import static git.artdeell.mojo.Tools.dialogForceClose;
import static git.artdeell.mojo.game.platform.Platform.PLATFORM;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_SUSTAINED_PERFORMANCE;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_VIRTUAL_MOUSE_START;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

import git.artdeell.mojo.BaseActivity;
import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.Logger;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.game.platform.Platform;
import git.artdeell.mojo.game.platform.backend.PlatformBackend;
import git.artdeell.mojo.game.renderer.GameRenderer;
import git.artdeell.mojo.input.ControlButtonMenuListener;
import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.ControlDrawerData;
import git.artdeell.mojo.input.ControlJoystickData;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.input.ControlProfileManager;
import git.artdeell.mojo.input.CustomControls;
import git.artdeell.mojo.input.EditorExitable;
import git.artdeell.mojo.input.keyboard.TouchCharInput;
import git.artdeell.mojo.input.mouse.GyroControl;
import git.artdeell.mojo.input.mouse.HotbarView;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.InstancePlaytimeTracker;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.performance.GameOptimizer;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.prefs.QuickSettingSideDialog;
import git.artdeell.mojo.services.GameService;
import git.artdeell.mojo.tasks.AsyncAssetManager;
import git.artdeell.mojo.utils.JREUtils;
import git.artdeell.mojo.utils.KeycodeUtils;
import git.artdeell.mojo.utils.MCOptionUtils;
import git.artdeell.mojo.utils.jre.GameRunner;
import git.artdeell.mojo.utils.lifecycle.AppTaskGuard;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;

/**
 * In-game host activity coordinating the native render surface, custom control overlay, and input
 * dispatch. Boot overlay lifecycle is delegated to {@link GameLoadingOverlayController} and live
 * collision-free Performance HUD management is delegated to {@link GameHudCoordinator}.
 */
public class GameActivity extends BaseActivity
        implements ControlButtonMenuListener, EditorExitable, ServiceConnection {

    public static final String INTENT_LAUNCH_VERSION = "intent_version";
    public static final String INTENT_LAUNCH_CLASSPATH = "intent_classpath";

    public static TouchCharInput touchCharInput;
    public static int mForcedPanningHeight = 0;
    public static int mImeHeight = 0;

    private static WeakReference<GameCursorView> weakCursor;

    private GameView launcherGLView;
    private LoggerView loggerView;
    private DrawerLayout drawerLayout;
    private ListView navDrawer;
    private View mDrawerPullButton;
    private GyroControl mGyroControl = null;
    private ControlLayout mControlLayout;
    private HotbarView mHotbarView;
    private GameRenderer mGameRenderer;
    private GameService.LocalBinder mServiceBinder;
    private QuickSettingSideDialog mQuickSettingSideDialog;
    private GameOptimizer.ThermalGovernor mThermalGovernor;

    private GameLoadingOverlayController mLoadingController;
    private GameHudCoordinator mHudCoordinator;
    private long mSessionResumeRealtimeMs = 0L;
    boolean isInEditor;

    // v2.6.0 — Per-Server Control Profile Auto-Switch & 5s Server Auto-Reconnect state
    private static final int AUTO_RECONNECT_COUNTDOWN_SEC = 5;
    private static final int MAX_AUTO_RECONNECT_ATTEMPTS = 5;
    private volatile String mActiveServerAddress;
    private String mActiveControlLayoutPath;
    private boolean mAutoReconnectActive = false;
    private int mAutoReconnectSecondsLeft = 0;
    private int mAutoReconnectAttempts = 0;
    private final Logger.eventLogListener mSessionLogListener = this::onLiveGameLogLine;
    private final Runnable mAutoReconnectTickRunnable = this::tickAutoReconnectCountdown;

    Instance instance;
    Account account;

    private ArrayAdapter<String> gameActionArrayAdapter;
    private AdapterView.OnItemClickListener gameActionClickListener;
    public ArrayAdapter<String> ingameControlsEditorArrayAdapter;
    public AdapterView.OnItemClickListener ingameControlsEditorListener;

    private final ActivityResultLauncher<Object> mOpenControlJsonLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("json"), this::onControlJsonPicked);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppTaskGuard.pruneDuplicateTasks(this);
        instance = Instances.loadSelectedInstance();
        account = Accounts.getCurrent();
        if (instance == null) {
            Toast.makeText(this, R.string.instance_dir_missing, Toast.LENGTH_LONG).show();
            finishAndRemoveTask();
            return;
        }

        mLoadingController = new GameLoadingOverlayController(this);
        mHudCoordinator = new GameHudCoordinator(this);
        mGameRenderer = new GameRenderer(instance.getLaunchRenderer());

        if (!PREF_ZINK_PREFER_SYSTEM_DRIVER || instance.hasCustomVulkanDriver()) {
            mGameRenderer.overrideVulkanDriver(instance);
        }

        AsyncAssetManager.extractDefaultSettings(this, instance.getGameDirectory());
        MCOptionUtils.load(instance.getGameDirectory().getAbsolutePath());

        Intent gameServiceIntent = new Intent(this, GameService.class);
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout(R.layout.activity_basemain);

        Platform.initialize(this, launcherGLView);
        mGyroControl = new GyroControl(this);

        if (PREF_USE_ALTERNATE_SURFACE) {
            getWindow().setBackgroundDrawable(null);
        } else {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getWindow().setSustainedPerformanceMode(PREF_SUSTAINED_PERFORMANCE);
        }

        GameOptimizer.applyInGameSystemBoost(this, LauncherPreferences.PREF_ZYROX_OPTIMIZER_MODE);
        mThermalGovernor = new GameOptimizer.ThermalGovernor(
                this, (multiplier, cap60Hz, hudBadge) -> {
                    if (launcherGLView != null) {
                        launcherGLView.applyThermalGovernorScale(multiplier, cap60Hz);
                    }
                    if (mHotbarView != null) {
                        mHotbarView.onResolutionChanged();
                    }
                    if (mHudCoordinator != null) {
                        mHudCoordinator.setThermalGovernorBadge(hudBadge);
                    }
                });
        mThermalGovernor.start(LauncherPreferences.PREF_ZYROX_THERMAL_GOVERNOR);
        InstancePlaytimeTracker.recordSessionStart(this, instance, System.currentTimeMillis());

        setupWindowInsetsAndImePanning();
        setupInGameControlsEditorMenu();

        MCOptionUtils.addMCOptionListener(MCOptionUtils::getMcScale);
        mControlLayout.setModifiable(false);

        ContextExecutor.setActivity(this);
        bindService(gameServiceIntent, this, 0);
    }

    private void setupWindowInsetsAndImePanning() {
        boolean androidCompat = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q;
        if (androidCompat) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, insets) -> {
            if (mHudCoordinator != null) {
                mHudCoordinator.onWindowInsetsChanged(insets);
            }
            if (launcherGLView == null || launcherGLView.mSurface == null) {
                return insets;
            }
            ViewPropertyAnimator animSurface = launcherGLView.mSurface.animate().setDuration(100);
            ViewPropertyAnimator animCursor = launcherGLView.mCursorView.animate().setDuration(100);
            if (!insets.isVisible(WindowInsetsCompat.Type.ime())) {
                animSurface.translationY(0).start();
                animCursor.translationY(0).start();
                mImeHeight = 0;
                if (androidCompat) {
                    view.postDelayed(() -> view.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN), 150);
                }
                return insets;
            }
            if (mForcedPanningHeight == 0 && !LauncherPreferences.PREF_KEYBOARD_AUTOPANNING) {
                return insets;
            }
            mImeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int translationY;
            if (mForcedPanningHeight == 0) {
                translationY = Tools.getTranslationFromCursorY(
                        (int) (Platform.cursorY * launcherGLView.getCursorRatioY() + 100),
                        launcherGLView.getHeight(),
                        mImeHeight,
                        0);
            } else {
                translationY = mForcedPanningHeight == -1
                        ? mImeHeight
                        : Math.max(0, Math.min(mImeHeight, mImeHeight - mForcedPanningHeight));
            }
            animSurface.translationY(-translationY).start();
            animCursor.translationY(-translationY).start();
            return insets;
        });
    }

    private void setupInGameControlsEditorMenu() {
        ingameControlsEditorArrayAdapter = new ArrayAdapter<>(this,
                R.layout.item_zyrox_ingame_menu, getResources().getStringArray(R.array.menu_customcontrol));
        ingameControlsEditorListener = (parent, view, position, id) -> {
            switch (position) {
                case 0: mControlLayout.addControlButton(new ControlData("New")); break;
                case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
                case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
                case 3: mControlLayout.openLoadDialog(); break;
                case 4: mControlLayout.openSaveDialog(this); break;
                case 5: mControlLayout.openSetDefaultDialog(); break;
                case 6: mControlLayout.openExitDialog(this); break;
                default: break;
            }
        };
    }

    private void onControlJsonPicked(Uri uri) {
        if (uri == null || mControlLayout == null) return;
        try {
            File imported = ControlProfileManager.importControlJsonUri(this, uri, null);
            mControlLayout.loadLayout(imported.getAbsolutePath());
            if (mHudCoordinator != null) {
                mHudCoordinator.onControlsLayoutChanged();
            }
        } catch (Exception e) {
            Tools.showError(this, e);
        }
    }

    protected void initLayout(int resId) {
        setContentView(resId);
        bindValues();
        mControlLayout.setMenuListener(this);
        mControlLayout.setExternalDocumentPicker(() -> mOpenControlJsonLauncher.launch(null));

        mDrawerPullButton.setOnClickListener(v -> onClickedMenu());
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        launcherGLView.mCursorView.setCursorScale(LauncherPreferences.PREF_MOUSESCALE);
        weakCursor = new WeakReference<>(launcherGLView.mCursorView);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile()) {
                throw new IOException("Failed to create a new log file");
            }
            Logger.begin(latestLogFile.getAbsolutePath());
            if (mLoadingController != null) {
                mLoadingController.attachLogger();
            }
            Logger.addLogListener(mSessionLogListener);

            Intent activityIntent = getIntent();
            Bundle extras = Objects.requireNonNull(activityIntent.getExtras());
            String version = extras.getString(INTENT_LAUNCH_VERSION);
            File[] classpath = (File[]) extras.getSerializable(INTENT_LAUNCH_CLASSPATH);

            activityIntent.removeExtra(INTENT_LAUNCH_VERSION);
            activityIntent.removeExtra(INTENT_LAUNCH_CLASSPATH);
            setIntent(activityIntent);
            setTitle("Zyrox Launcher (" + version + ")");

            String[] baseActions = getResources().getStringArray(R.array.menu_ingame);
            String[] allActions = new String[baseActions.length + 2];
            System.arraycopy(baseActions, 0, allActions, 0, baseActions.length);
            allActions[baseActions.length] = getString(R.string.zyrox_toggle_perf_hud);
            allActions[baseActions.length + 1] = getString(R.string.zyrox_quick_macros);
            gameActionArrayAdapter = new ArrayAdapter<>(this,
                    R.layout.item_zyrox_ingame_menu, allActions);
            gameActionClickListener = (parent, view, position, id) -> {
                switch (position) {
                    case 0: dialogForceClose(GameActivity.this); break;
                    case 1: openLogOutput(); break;
                    case 2: dialogSendCustomKey(); break;
                    case 3: openQuickSettings(); break;
                    case 4: openCustomControls(); break;
                    case 5:
                        if (mHudCoordinator != null) mHudCoordinator.toggleEnabled();
                        break;
                    case 6:
                        QuickMacroDialog.show(GameActivity.this);
                        break;
                    default: break;
                }
                drawerLayout.closeDrawers();
            };
            navDrawer.setAdapter(gameActionArrayAdapter);
            navDrawer.setOnItemClickListener(gameActionClickListener);
            drawerLayout.closeDrawers();

            launcherGLView.setSurfaceReadyListener(() -> {
                try {
                    Tools.runOnUiThread(() -> {
                        if (PREF_VIRTUAL_MOUSE_START) {
                            launcherGLView.mCursorView.setVisibility(View.VISIBLE);
                        }
                    });
                    if (version == null || classpath == null) {
                        Tools.runOnUiThread(() -> {
                            Toast.makeText(this, R.string.main_please_restart, Toast.LENGTH_LONG).show();
                            finish();
                        });
                        return;
                    }
                    runCraft(version, classpath);
                } catch (Throwable e) {
                    Tools.showErrorRemote(e);
                }
            });
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private void loadControls() {
        String desiredLayout = instance.getLaunchControls();
        // Auto-apply per-server control profile when launched via Server Manager quick-connect
        String pendingProfile = git.artdeell.mojo.servers.ServerListManager
                .consumePendingServerControlProfile();
        String lastQuickAddr = git.artdeell.mojo.servers.ServerListManager
                .getLastQuickConnectAddress();
        if (lastQuickAddr != null && mActiveServerAddress == null) {
            mActiveServerAddress = lastQuickAddr;
            if (pendingProfile == null && instance != null) {
                pendingProfile = git.artdeell.mojo.servers.ServerListManager
                        .findControlProfileForServer(instance.getGameDirectory(), lastQuickAddr);
            }
        }
        if (pendingProfile != null) {
            File serverCtrl = ControlProfileManager.resolveControlFileForProfile(pendingProfile);
            if (serverCtrl != null) {
                desiredLayout = serverCtrl.getAbsolutePath();
            }
        }
        applyControlLayoutFile(desiredLayout, pendingProfile != null
                ? ControlProfileManager.formatProfileBadge(pendingProfile) : null);
    }

    private void applyControlLayoutFile(String layoutPath, String switchedToastBadge) {
        if (mControlLayout == null || isInEditor || layoutPath == null) return;
        if (layoutPath.equals(mActiveControlLayoutPath)) return;
        try {
            mControlLayout.loadLayout(layoutPath);
            mActiveControlLayoutPath = layoutPath;
            if (switchedToastBadge != null) {
                Toast.makeText(this,
                        "Switched Controls -> " + switchedToastBadge,
                        Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            try {
                Log.w("GameActivity", "Unable to load control layout, falling back to default", e);
                mControlLayout.loadLayout(Tools.CTRLDEF_FILE);
                mActiveControlLayoutPath = Tools.CTRLDEF_FILE;
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }
        mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        if (!mControlLayout.isShown()) {
            mControlLayout.setVisibility(View.VISIBLE);
        }
        mControlLayout.toggleControlVisible();
        if (mHudCoordinator != null) {
            mHudCoordinator.onControlsLayoutChanged();
        }
    }

    private void onLiveGameLogLine(String line) {
        if (line == null || line.isEmpty()) return;
        if (mHudCoordinator != null) {
            mHudCoordinator.onGameLogLine(line);
        }
        String connectingTarget =
                git.artdeell.mojo.servers.ServerListManager.parseConnectingLogEndpoint(line);
        if (connectingTarget != null) {
            mActiveServerAddress = connectingTarget;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                cancelAutoReconnectCountdown(false);
                mAutoReconnectAttempts = 0;
                if (mHudCoordinator != null) {
                    mHudCoordinator.setPingTargetAddress(connectingTarget);
                }
                applyPerServerControlProfileAsync(connectingTarget);
            });
            return;
        }
        if (LauncherPreferences.PREF_ZYROX_AUTO_RECONNECT
                && mActiveServerAddress != null
                && git.artdeell.mojo.servers.ServerListManager.isUnexpectedServerDisconnectLog(line)) {
            final String target = mActiveServerAddress;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    scheduleAutoReconnectCountdown(target);
                }
            });
        }
    }

    private void applyPerServerControlProfileAsync(final String serverEndpoint) {
        if (instance == null || isInEditor) return;
        final File gameDir = instance.getGameDirectory();
        final String fallbackPath = instance.getLaunchControls();
        git.artdeell.mojo.MojoApplication.sExecutorService.execute(() -> {
            String serverProfile = git.artdeell.mojo.servers.ServerListManager
                    .findControlProfileForServer(gameDir, serverEndpoint);
            File resolved = serverProfile != null
                    ? ControlProfileManager.resolveControlFileForProfile(serverProfile) : null;
            final String targetPath = resolved != null ? resolved.getAbsolutePath() : fallbackPath;
            final String badge = resolved != null
                    ? ControlProfileManager.formatProfileBadge(serverProfile)
                    : (mActiveControlLayoutPath != null && !mActiveControlLayoutPath.equals(fallbackPath)
                    ? "Instance Default" : null);
            if (targetPath != null && !targetPath.equals(mActiveControlLayoutPath)) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && mControlLayout != null && !isInEditor) {
                        try {
                            mControlLayout.loadLayout(targetPath);
                            mActiveControlLayoutPath = targetPath;
                            mControlLayout.setControlVisible(true);
                            mDrawerPullButton.setVisibility(
                                    mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
                            if (mHudCoordinator != null) {
                                mHudCoordinator.onControlsLayoutChanged();
                            }
                            if (badge != null) {
                                Toast.makeText(GameActivity.this,
                                        "Controls auto-switched: " + badge,
                                        Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable t) {
                            Log.w("GameActivity", "Per-server control auto-switch skipped", t);
                        }
                    }
                });
            }
        });
    }

    private void scheduleAutoReconnectCountdown(String targetServer) {
        if (mAutoReconnectActive || !LauncherPreferences.PREF_ZYROX_AUTO_RECONNECT) return;
        if (mAutoReconnectAttempts >= MAX_AUTO_RECONNECT_ATTEMPTS) {
            if (mHudCoordinator != null) {
                mHudCoordinator.showAutoReconnectCountdown(
                        "Auto-reconnect paused after " + MAX_AUTO_RECONNECT_ATTEMPTS + " retries",
                        () -> cancelAutoReconnectCountdown(true));
            }
            return;
        }
        mAutoReconnectActive = true;
        mAutoReconnectSecondsLeft = AUTO_RECONNECT_COUNTDOWN_SEC;
        Tools.MAIN_HANDLER.removeCallbacks(mAutoReconnectTickRunnable);
        tickAutoReconnectCountdown();
    }

    private void tickAutoReconnectCountdown() {
        if (!mAutoReconnectActive || isFinishing() || isDestroyed()
                || !LauncherPreferences.PREF_ZYROX_AUTO_RECONNECT
                || Platform.isGrabbing()) {
            cancelAutoReconnectCountdown(false);
            return;
        }
        final String target = mActiveServerAddress;
        if (target == null) {
            cancelAutoReconnectCountdown(false);
            return;
        }
        if (mAutoReconnectSecondsLeft > 0) {
            if (mHudCoordinator != null) {
                mHudCoordinator.showAutoReconnectCountdown(
                        "⚡ Reconnecting to " + target + " in " + mAutoReconnectSecondsLeft + "s…",
                        () -> cancelAutoReconnectCountdown(true));
            }
            mAutoReconnectSecondsLeft--;
            Tools.MAIN_HANDLER.postDelayed(mAutoReconnectTickRunnable, 1000L);
            return;
        }
        mAutoReconnectActive = false;
        mAutoReconnectAttempts++;
        if (mHudCoordinator != null) {
            mHudCoordinator.showAutoReconnectCountdown(
                    "⚡ Reconnecting to " + target + " (attempt "
                            + mAutoReconnectAttempts + "/" + MAX_AUTO_RECONNECT_ATTEMPTS + ")…",
                    () -> cancelAutoReconnectCountdown(true));
        }
        git.artdeell.mojo.MojoApplication.sExecutorService.execute(() -> {
            // Re-verify any P2P loopback bridge and ping the server before triggering GUI rejoin
            String resolved = git.artdeell.mojo.servers.ZyroxRoomBridge
                    .ensurePendingTunnelInGameProcess(target);
            mActiveServerAddress = resolved;
            git.artdeell.mojo.servers.ServerPinger.PingResult ping =
                    git.artdeell.mojo.servers.ServerPinger.ping(resolved);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()
                        || !LauncherPreferences.PREF_ZYROX_AUTO_RECONNECT
                        || Platform.isGrabbing()) {
                    cancelAutoReconnectCountdown(false);
                    return;
                }
                if (!ping.online) {
                    scheduleAutoReconnectCountdown(resolved);
                    return;
                }
                // Server responded online: step back from DisconnectedScreen & rejoin selected server
                CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_TAB);
                Tools.MAIN_HANDLER.postDelayed(() -> {
                    if (Platform.isGrabbing() || isFinishing()) return;
                    CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ENTER);
                    Tools.MAIN_HANDLER.postDelayed(() -> {
                        if (Platform.isGrabbing() || isFinishing()) return;
                        CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ENTER);
                        if (mHudCoordinator != null) {
                            mHudCoordinator.hideAutoReconnectCountdown();
                        }
                    }, 420L);
                }, 140L);
            });
        });
    }

    private void cancelAutoReconnectCountdown(boolean resetAttempts) {
        mAutoReconnectActive = false;
        mAutoReconnectSecondsLeft = 0;
        if (resetAttempts) {
            mAutoReconnectAttempts = 0;
        }
        Tools.MAIN_HANDLER.removeCallbacks(mAutoReconnectTickRunnable);
        if (mHudCoordinator != null) {
            mHudCoordinator.hideAutoReconnectCountdown();
        }
    }

    @Override
    public void onAttachedToWindow() {
        mControlLayout.post(() -> {
            Tools.getDisplayMetrics(this);
            loadControls();
        });
    }

    private void bindValues() {
        mControlLayout = findViewById(R.id.main_control_layout);
        launcherGLView = findViewById(R.id.main_game_render_view);
        drawerLayout = findViewById(R.id.main_drawer_options);
        if (drawerLayout != null) {
            drawerLayout.setScrimColor(0x38080D1B);
        }
        navDrawer = findViewById(R.id.main_navigation_view);
        loggerView = findViewById(R.id.mainLoggerView);
        touchCharInput = findViewById(R.id.mainTouchCharInput);
        mDrawerPullButton = findViewById(R.id.drawer_button);
        mHotbarView = findViewById(R.id.hotbar_view);

        View loadingScreen = findViewById(R.id.main_loading_screen);
        if (mLoadingController != null) {
            mLoadingController.bind(loadingScreen, launcherGLView, instance, mGameRenderer);
        }

        ViewGroup contentFrame = findViewById(R.id.content_frame);
        if (contentFrame != null && instance != null && mHudCoordinator != null) {
            String rTag = (mGameRenderer != null && mGameRenderer.getCurrentRenderer() != null)
                    ? mGameRenderer.getCurrentRenderer().tag()
                    : instance.getLaunchRenderer();
            mHudCoordinator.attach(
                    contentFrame,
                    mControlLayout,
                    mDrawerPullButton,
                    mHotbarView,
                    rTag,
                    instance.getEffectiveRamAllocationMb(),
                    this::openQuickSettings,
                    git.artdeell.mojo.servers.ServerListManager.getLastActiveServerAddress(
                            instance.getGameDirectory()));
            mHudCoordinator.configureWaypointSession(
                    instance.getGameDirectory(),
                    account != null ? account.username : null);
        }
    }

    /** Thread-safe launch lifecycle hook advancing the live Loading Screen progress bar. */
    public static void reportLoadingStage(@StringRes int stageStringRes) {
        ContextExecutor.executeActivity(activity -> {
            if (activity instanceof GameActivity) {
                GameActivity ga = (GameActivity) activity;
                if (ga.mLoadingController != null) {
                    ga.mLoadingController.onLauncherStage(stageStringRes);
                }
            }
        });
    }

    /** Called when GLFW/SDL/AWT initializes; keeps loading GIF live until Mojang's splash draws. */
    public void onNativeWindowBackendReady(PlatformBackend backend) {
        if (instance != null && mGameRenderer != null && mGameRenderer.getCurrentRenderer() != null) {
            InstanceManager.recordLaunchWindowReady(instance, mGameRenderer.getCurrentRenderer().tag());
        }
        if (mLoadingController != null) {
            mLoadingController.onNativeWindowReady();
        }
    }

    public void hideLoadingScreen() {
        if (mLoadingController != null) {
            mLoadingController.hideSmoothly();
        }
        Tools.MAIN_HANDLER.postDelayed(
                () -> GameService.reclaimLauncherMediaMemory(true), 600L);
    }

    @Override
    public void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        mSessionResumeRealtimeMs = SystemClock.elapsedRealtime();
        if (PREF_ENABLE_GYRO && mGyroControl != null) mGyroControl.enable();
        if (mLoadingController != null) mLoadingController.resumeAnimation();
        if (mHudCoordinator != null) mHudCoordinator.onResume();
        PLATFORM.setHovered(true);
    }

    @Override
    protected void onPause() {
        cancelAutoReconnectCountdown(false);
        flushPlaytimeSession();
        ContextExecutor.clearActivity();
        if (mGyroControl != null) mGyroControl.disable();
        if (mLoadingController != null) mLoadingController.pauseAnimation();
        if (mHudCoordinator != null) mHudCoordinator.onPause();
        if (Platform.isGrabbing()) {
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
        }
        if (mQuickSettingSideDialog != null) {
            mQuickSettingSideDialog.cancel();
        }
        PLATFORM.setHovered(false);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mLoadingController != null) mLoadingController.resumeAnimation();
        PLATFORM.setVisible(true);
    }

    @Override
    protected void onStop() {
        if (mLoadingController != null) mLoadingController.pauseAnimation();
        PLATFORM.setVisible(false);
        super.onStop();
    }

    private void flushPlaytimeSession() {
        if (mSessionResumeRealtimeMs > 0L) {
            long delta = SystemClock.elapsedRealtime() - mSessionResumeRealtimeMs;
            mSessionResumeRealtimeMs = 0L;
            InstancePlaytimeTracker.recordSessionElapsed(this, instance, delta);
        }
    }

    @Override
    protected void onDestroy() {
        cancelAutoReconnectCountdown(true);
        Logger.removeLogListener(mSessionLogListener);
        git.artdeell.mojo.servers.ZyroxRoomBridge.stopClientTunnel();
        flushPlaytimeSession();
        if (mThermalGovernor != null) {
            mThermalGovernor.stop();
            mThermalGovernor = null;
        }
        if (mHudCoordinator != null) {
            mHudCoordinator.onDestroy();
            mHudCoordinator = null;
        }
        if (mLoadingController != null) {
            mLoadingController.destroyImmediate();
            mLoadingController = null;
        }
        super.onDestroy();
        ContextExecutor.clearActivity();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mGyroControl != null) mGyroControl.updateOrientation();
        if (mControlLayout == null) return;
        mControlLayout.requestLayout();
        mControlLayout.post(() -> {
            launcherGLView.refreshSize();
            mControlLayout.refreshControlButtonPositions();
            if (mHudCoordinator != null) {
                mHudCoordinator.onControlsLayoutChanged();
            }
        });
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (mLoadingController != null) {
            mLoadingController.hideIfSplashAlreadyReached();
        }
        if (launcherGLView != null) {
            Tools.MAIN_HANDLER.postDelayed(() -> launcherGLView.refreshSize(), 500);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (mHudCoordinator != null) {
            mHudCoordinator.onRequestPermissionsResult(requestCode, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mHudCoordinator != null
                && mHudCoordinator.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            if (!Tools.checkStorageRoot(this)) return;
            LauncherPreferences.loadPreferences(getApplicationContext());
            try {
                mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
                if (mHudCoordinator != null) mHudCoordinator.onControlsLayoutChanged();
            } catch (IOException e) {
                Log.w("GameActivity", "Failed to reload default control layout", e);
            }
        }
    }

    private void runCraft(String versionId, File[] classpath) throws Throwable {
        GameLoadingOverlayController.resetSplashFlag();
        reportLoadingStage(R.string.zyrox_loading_preparing);
        Logger.appendToLog("--------- Starting game with Launcher Debug!");
        Tools.printLauncherInfo(versionId, instance.getLaunchArgs(), mGameRenderer.getCurrentRenderer(), this);
        JREUtils.redirectAndPrintJRELog();
        try {
            GameRunner.launchGame(this, account, instance, versionId, classpath, mGameRenderer);
        } finally {
            Tools.runOnUiThread(() -> {
                if (mLoadingController != null) mLoadingController.pauseAnimation();
                if (mServiceBinder != null) mServiceBinder.isActive = false;
            });
        }
    }

    private void dialogSendCustomKey() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.control_customkey)
                .setItems(KeycodeUtils.generateKeyName(), (d, pos) -> KeycodeUtils.execKeyIndex(pos))
                .show();
    }

    private void openCustomControls() {
        if (ingameControlsEditorListener == null || ingameControlsEditorArrayAdapter == null) return;
        mControlLayout.setModifiable(true);
        navDrawer.setAdapter(ingameControlsEditorArrayAdapter);
        navDrawer.setOnItemClickListener(ingameControlsEditorListener);
        mDrawerPullButton.setVisibility(View.VISIBLE);
        isInEditor = true;
    }

    private void openLogOutput() {
        loggerView.setVisibility(View.VISIBLE);
    }

    private void openQuickSettings() {
        if (mQuickSettingSideDialog == null) {
            mQuickSettingSideDialog = new QuickSettingSideDialog(this, mControlLayout) {
                @Override
                public void onResolutionChanged() {
                    launcherGLView.refreshSize();
                    mHotbarView.onResolutionChanged();
                }

                @Override
                public void onGyroStateChanged() {
                    mGyroControl.updateOrientation();
                    if (PREF_ENABLE_GYRO) {
                        mGyroControl.enable();
                    } else {
                        mGyroControl.disable();
                    }
                }

                @Override
                public void onButtonTransparencyChanged() {
                    mControlLayout.updateButtonOpacity();
                }

                @Override
                public void onHudPreferencesChanged() {
                    if (mHudCoordinator != null) {
                        mHudCoordinator.syncPreferencesLive();
                    }
                    if (!LauncherPreferences.PREF_ZYROX_AUTO_RECONNECT) {
                        cancelAutoReconnectCountdown(true);
                    }
                    if (mThermalGovernor != null) {
                        mThermalGovernor.setEnabled(
                                LauncherPreferences.PREF_ZYROX_THERMAL_GOVERNOR);
                    }
                }

                @Override
                public void onHudResetPositionRequested() {
                    if (mHudCoordinator != null) {
                        mHudCoordinator.resetPosition();
                    }
                }
            };
        }
        mQuickSettingSideDialog.appear(true);
    }

    public static void toggleMouse(Context ctx) {
        if (Platform.isGrabbing()) return;
        GameCursorView cursorView = Tools.getWeakReference(weakCursor);
        if (cursorView == null) return;
        int toastString;
        if (cursorView.getVisibility() == View.VISIBLE) {
            toastString = R.string.control_mouseoff;
            cursorView.setVisibility(View.GONE);
        } else {
            toastString = R.string.control_mouseon;
            cursorView.setVisibility(View.VISIBLE);
        }
        Toast.makeText(ctx, toastString, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isInEditor) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) mControlLayout.askToExit(this);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handleEvent = launcherGLView.processKeyEvent(event);
        if (!handleEvent && event.getKeyCode() == KeyEvent.KEYCODE_BACK && !touchCharInput.isEnabled()) {
            if (event.getAction() != KeyEvent.ACTION_UP) return true;
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
            return true;
        }
        return handleEvent;
    }

    public static void switchKeyboardState(boolean panning) {
        if (touchCharInput != null) {
            touchCharInput.switchKeyboardState();
            GameActivity.mForcedPanningHeight = panning ? -1 : 0;
        }
    }

    public static void toggleKeyboardState(boolean state, int panningHeight) {
        if (touchCharInput != null) {
            touchCharInput.setKeyboardState(state);
            GameActivity.mForcedPanningHeight = panningHeight;
        }
    }

    /** Triggers the hold-to-speak Voice-to-Chat recognizer from a custom touch button. */
    public static void triggerVoiceChatFromControl(boolean isDown) {
        ContextExecutor.executeActivity(activity -> {
            if (activity instanceof GameActivity) {
                GameActivity ga = (GameActivity) activity;
                if (ga.mHudCoordinator != null) {
                    ga.mHudCoordinator.onVoiceChatButtonState(isDown);
                }
            }
        });
    }

    /** Opens the 8-slot thumb-hold Radial Macro Wheel from a custom touch button. */
    public static void openQuickMacroWheelFromControl() {
        ContextExecutor.executeActivity(QuickMacroDialog::show);
    }

    @Override
    public void onClickedMenu() {
        drawerLayout.openDrawer(navDrawer);
        navDrawer.requestLayout();
    }

    @Override
    public void exitEditor() {
        try {
            mControlLayout.loadLayout((CustomControls) null);
            mControlLayout.setModifiable(false);
            System.gc();
            mControlLayout.loadLayout(instance.getLaunchControls());
            mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
            if (mHudCoordinator != null) {
                mHudCoordinator.onControlsLayoutChanged();
            }
        } catch (Exception e) {
            Tools.showError(this, e);
        }
        navDrawer.setAdapter(gameActionArrayAdapter);
        navDrawer.setOnItemClickListener(gameActionClickListener);
        isInEditor = false;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        GameService.LocalBinder localBinder = (GameService.LocalBinder) service;
        mServiceBinder = localBinder;
        launcherGLView.start(localBinder.isActive);
        localBinder.isActive = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean checkCaptureDispatchConditions(MotionEvent event) {
        int eventSource = event.getSource();
        return (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) != 0
                || (eventSource & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if (Tools.isAndroid8OrHigher() && checkCaptureDispatchConditions(ev)) {
            return launcherGLView.dispatchCapturedPointerEvent(ev);
        }
        return super.dispatchTrackballEvent(ev);
    }
}
