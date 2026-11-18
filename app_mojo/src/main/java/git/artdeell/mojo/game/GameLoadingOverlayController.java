package git.artdeell.mojo.game;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import git.artdeell.mojo.Logger;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.game.platform.Platform;
import git.artdeell.mojo.game.platform.backend.DummyBackend;
import git.artdeell.mojo.game.renderer.GameRenderer;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.InstancePlaytimeTracker;
import git.artdeell.mojo.performance.GameOptimizer;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.MinecraftLoadingLandscapeView;

/**
 * Single-responsibility controller extracted from {@link GameActivity} managing the full-bleed
 * animated launch backdrop, stdout/stderr log-driven {@link GameLaunchProgressTracker}, ETA ticks,
 * and zero-jank cross-fade disposal once Mojang's splash renders its first frame.
 */
public final class GameLoadingOverlayController {

    private static volatile boolean sMojangSplashReached = false;

    @NonNull
    private final Activity mActivity;
    @Nullable
    private View mLoadingScreen;
    @Nullable
    private MinecraftLoadingLandscapeView mLoadingLandscape;
    @Nullable
    private ProgressBar mLoadingProgressBar;
    @Nullable
    private TextView mLoadingStageText;
    @Nullable
    private TextView mLoadingEtaText;
    @Nullable
    private TextView mLoadingPercentText;
    @Nullable
    private ObjectAnimator mLoadingBarAnimator;
    @Nullable
    private GameLaunchProgressTracker mLaunchProgressTracker;
    @Nullable
    private Logger.eventLogListener mLaunchLogListener;
    @Nullable
    private GameView mGameView;
    @Nullable
    private Instance mInstance;
    @Nullable
    private GameRenderer mGameRenderer;

    private long mLastProgressUiPushMs = 0L;
    private boolean mHiding = false;

    private final Runnable mProgressTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (mLoadingScreen == null || mHiding || mLaunchProgressTracker == null) {
                return;
            }
            applySnapshotOnUi(mLaunchProgressTracker.onTick(SystemClock.elapsedRealtime()));
            Tools.MAIN_HANDLER.postDelayed(this, 260L);
        }
    };

    public GameLoadingOverlayController(@NonNull Activity activity) {
        mActivity = activity;
    }

    public static void resetSplashFlag() {
        sMojangSplashReached = false;
    }

    public static boolean isMojangSplashReached() {
        return sMojangSplashReached;
    }

    public void bind(@Nullable View loadingScreen,
                     @Nullable GameView gameView,
                     @Nullable Instance instance,
                     @Nullable GameRenderer renderer) {
        mLoadingScreen = loadingScreen;
        mGameView = gameView;
        mInstance = instance;
        mGameRenderer = renderer;
        if (loadingScreen == null) return;

        mLoadingLandscape = loadingScreen.findViewById(R.id.main_loading_landscape);
        mLoadingProgressBar = loadingScreen.findViewById(R.id.main_loading_progress_bar);
        mLoadingStageText = loadingScreen.findViewById(R.id.main_loading_stage_text);
        mLoadingEtaText = loadingScreen.findViewById(R.id.main_loading_eta_text);
        mLoadingPercentText = loadingScreen.findViewById(R.id.main_loading_percent_text);

        if (sMojangSplashReached && !(Platform.PLATFORM instanceof DummyBackend)) {
            destroyImmediate();
            return;
        }

        initProgressTracking();
        if (gameView != null) {
            gameView.setFirstSplashFrameListener(() -> {
                sMojangSplashReached = true;
                hideSmoothly();
            });
            if (!(Platform.PLATFORM instanceof DummyBackend)) {
                gameView.notifyNativeWindowBackendReady();
            }
        }
    }

    public void attachLogger() {
        if (mLaunchLogListener != null) {
            Logger.addLogListener(mLaunchLogListener);
        }
    }

    private void initProgressTracking() {
        long now = SystemClock.elapsedRealtime();
        int modCount = 0;
        long learnedBootMs = 0L;
        if (mInstance != null) {
            try {
                modCount = GameOptimizer.countInstalledMods(mInstance.getGameDirectory());
            } catch (Throwable ignored) {
            }
            learnedBootMs = InstancePlaytimeTracker.getLearnedBootMs(mActivity, mInstance);
        }
        mLaunchProgressTracker = new GameLaunchProgressTracker(now, modCount, learnedBootMs);
        applySnapshotOnUi(mLaunchProgressTracker.onTick(now));

        mLaunchLogListener = text -> {
            GameLaunchProgressTracker tracker = mLaunchProgressTracker;
            if (tracker == null || mHiding) return;
            long ts = SystemClock.elapsedRealtime();
            int prevPct = tracker.getPercent();
            GameLaunchProgressTracker.ProgressSnapshot snap = tracker.onLogOutput(text, ts);
            if (snap.percent > prevPct || (ts - mLastProgressUiPushMs) >= 85L) {
                mLastProgressUiPushMs = ts;
                Tools.runOnUiThread(() -> applySnapshotOnUi(snap));
            }
        };
        Tools.MAIN_HANDLER.removeCallbacks(mProgressTickRunnable);
        Tools.MAIN_HANDLER.postDelayed(mProgressTickRunnable, 260L);
    }

    public void onLauncherStage(@StringRes int stageStringRes) {
        if (mLoadingLandscape != null && !mHiding) {
            mLoadingLandscape.resumeAnimation();
        }
        if (mLaunchProgressTracker == null || mHiding) return;
        int floor = 5;
        int ceil = 14;
        if (stageStringRes == R.string.zyrox_loading_resources) {
            floor = 8;
            ceil = 16;
        } else if (stageStringRes == R.string.zyrox_loading_runtime) {
            floor = 12;
            ceil = 20;
        } else if (stageStringRes == R.string.zyrox_loading_renderer) {
            floor = 15;
            ceil = 24;
        } else if (stageStringRes == R.string.zyrox_loading_starting) {
            floor = 19;
            ceil = 32;
        }
        String label = mActivity.getString(stageStringRes);
        applySnapshotOnUi(mLaunchProgressTracker.onLauncherStage(
                floor, ceil, label, SystemClock.elapsedRealtime()));
    }

    public void onNativeWindowReady() {
        if (mLaunchProgressTracker != null && !mHiding) {
            GameLaunchProgressTracker.ProgressSnapshot snap =
                    mLaunchProgressTracker.onNativeWindowReady(SystemClock.elapsedRealtime());
            Tools.runOnUiThread(() -> applySnapshotOnUi(snap));
        }
        if (mGameView != null) {
            mGameView.notifyNativeWindowBackendReady();
        }
    }

    public void hideSmoothly() {
        if (mLoadingScreen == null || mHiding) return;
        mHiding = true;
        sMojangSplashReached = true;
        if (mLaunchProgressTracker != null) {
            applySnapshotOnUi(mLaunchProgressTracker.markComplete(SystemClock.elapsedRealtime()));
        }
        stopProgressTracking(true);
        if (mGameView != null) {
            mGameView.releaseSplashProbe();
        }
        if (mInstance != null && mGameRenderer != null && mGameRenderer.getCurrentRenderer() != null) {
            InstanceManager.recordLaunchWindowReady(mInstance, mGameRenderer.getCurrentRenderer().tag());
        }
        if (LauncherPreferences.isReducedMotionEnabled(mActivity)) {
            destroyImmediate();
        } else {
            mLoadingScreen.animate()
                    .alpha(0f)
                    .setDuration(320L)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(this::destroyImmediate)
                    .start();
        }
    }

    public void hideIfSplashAlreadyReached() {
        if (mLoadingScreen != null && sMojangSplashReached && !(Platform.PLATFORM instanceof DummyBackend)) {
            hideSmoothly();
        }
    }

    public void resumeAnimation() {
        if (mLoadingLandscape != null && !mHiding) {
            mLoadingLandscape.resumeAnimation();
        }
    }

    public void pauseAnimation() {
        if (mLoadingLandscape != null) {
            mLoadingLandscape.pauseAnimation();
        }
    }

    public void destroyImmediate() {
        stopProgressTracking(false);
        if (mGameView != null) {
            mGameView.releaseSplashProbe();
        }
        if (mLoadingLandscape != null) {
            mLoadingLandscape.release();
            mLoadingLandscape = null;
        }
        mLoadingProgressBar = null;
        mLoadingStageText = null;
        mLoadingEtaText = null;
        mLoadingPercentText = null;
        if (mLoadingScreen != null) {
            mLoadingScreen.animate().cancel();
            if (mLoadingScreen.getParent() instanceof ViewGroup) {
                ((ViewGroup) mLoadingScreen.getParent()).removeView(mLoadingScreen);
            }
            mLoadingScreen = null;
        }
        mHiding = false;
    }

    private void stopProgressTracking(boolean recordSuccessDuration) {
        Tools.MAIN_HANDLER.removeCallbacks(mProgressTickRunnable);
        if (mLaunchLogListener != null) {
            Logger.removeLogListener(mLaunchLogListener);
            mLaunchLogListener = null;
        }
        if (mLoadingBarAnimator != null) {
            mLoadingBarAnimator.cancel();
            mLoadingBarAnimator = null;
        }
        if (recordSuccessDuration && mLaunchProgressTracker != null && mInstance != null) {
            long elapsed = mLaunchProgressTracker.getElapsedMs(SystemClock.elapsedRealtime());
            InstancePlaytimeTracker.recordObservedBootMs(mActivity, mInstance, elapsed);
        }
        mLaunchProgressTracker = null;
    }

    private void applySnapshotOnUi(@NonNull GameLaunchProgressTracker.ProgressSnapshot snap) {
        if (mLoadingScreen == null) return;
        if (mLoadingStageText != null) mLoadingStageText.setText(snap.stageText);
        if (mLoadingEtaText != null) mLoadingEtaText.setText(snap.etaText);
        if (mLoadingPercentText != null) mLoadingPercentText.setText(snap.percent + "%");
        if (mLoadingProgressBar != null) {
            int target = Math.max(mLoadingProgressBar.getProgress(), snap.percent * 10);
            if (LauncherPreferences.isReducedMotionEnabled(mActivity)) {
                mLoadingProgressBar.setProgress(target);
            } else if (target > mLoadingProgressBar.getProgress()) {
                if (mLoadingBarAnimator != null) {
                    mLoadingBarAnimator.cancel();
                }
                mLoadingBarAnimator = ObjectAnimator.ofInt(
                        mLoadingProgressBar, "progress", mLoadingProgressBar.getProgress(), target);
                mLoadingBarAnimator.setDuration(snap.completed ? 160L : 220L);
                mLoadingBarAnimator.setInterpolator(new DecelerateInterpolator());
                mLoadingBarAnimator.start();
            }
        }
    }
}
