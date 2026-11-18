package git.artdeell.mojo.game;

import android.app.Activity;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

import git.artdeell.mojo.diagnostics.PerformanceHudOverlay;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Single-responsibility coordinator extracted from {@link GameActivity} binding
 * {@link PerformanceHudOverlay} to live safe-area display cutouts, top toolbar widgets, and
 * {@link ControlLayout} custom control hitboxes so the HUD never collides with interactive UI.
 */
public final class GameHudCoordinator {

    @NonNull
    private final Activity mActivity;
    @Nullable
    private PerformanceHudOverlay mOverlay;
    @Nullable
    private ContextInventoryHelperBar mInventoryHelperBar;
    @Nullable
    private ControlLayout mControlLayout;
    @Nullable
    private View mDrawerPullButton;
    @Nullable
    private View mHotbarView;
    private final int[] mParentLoc = new int[2];
    private final int[] mViewLoc = new int[2];

    private final git.artdeell.mojo.game.platform.input.PlatformGrabListener mContextGrabListener =
            grabbing -> mActivity.runOnUiThread(() -> {
                if (mInventoryHelperBar == null) return;
                if (grabbing) {
                    // First grabbed frame confirms player entered a world/server gameplay session
                    mInventoryHelperBar.setGameSessionReady(true);
                }
                boolean inEditor = mControlLayout != null && mControlLayout.getModifiable();
                mInventoryHelperBar.onContextStateChanged(grabbing, inEditor);
                onControlsLayoutChanged();
            });

    public GameHudCoordinator(@NonNull Activity activity) {
        mActivity = activity;
    }

    public void attach(@Nullable ViewGroup contentFrame,
                       @Nullable ControlLayout controlLayout,
                       @Nullable View drawerPullButton,
                       @Nullable View hotbarView,
                       @NonNull String rendererTag,
                       int effectiveHeapMb) {
        attach(contentFrame, controlLayout, drawerPullButton, hotbarView,
                rendererTag, effectiveHeapMb, null, null);
    }

    public void attach(@Nullable ViewGroup contentFrame,
                       @Nullable ControlLayout controlLayout,
                       @Nullable View drawerPullButton,
                       @Nullable View hotbarView,
                       @NonNull String rendererTag,
                       int effectiveHeapMb,
                       @Nullable Runnable onQuickSettingsTap,
                       @Nullable String activeServerAddress) {
        if (contentFrame == null) return;
        mControlLayout = controlLayout;
        mDrawerPullButton = drawerPullButton;
        mHotbarView = hotbarView;

        PerformanceHudOverlay hud = new PerformanceHudOverlay(mActivity);
        hud.configureSession(rendererTag, effectiveHeapMb);
        hud.setObstacleProvider(outRects -> collectLiveObstacles(contentFrame, outRects));
        hud.setOnQuickSettingsAction(onQuickSettingsTap);
        hud.setPingTargetAddress(activeServerAddress);
        hud.setOnHudHiddenAction(() -> {
            LauncherPreferences.PREF_ZYROX_PERF_HUD = false;
            if (LauncherPreferences.DEFAULT_PREF != null) {
                LauncherPreferences.DEFAULT_PREF.edit().putBoolean("zyroxPerfHud", false).apply();
            }
            if (mOverlay != null) {
                mOverlay.setHudEnabled(false);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        contentFrame.addView(hud, lp);
        mOverlay = hud;
        hud.setHudEnabled(LauncherPreferences.PREF_ZYROX_PERF_HUD);

        // Phase 4.4 — Context-Aware Inventory & GUI Helper Bar (auto-appears when ungrabbed in-world)
        ContextInventoryHelperBar invBar = new ContextInventoryHelperBar(
                mActivity, () -> QuickMacroDialog.show(mActivity));
        int topMargin = Math.round(6f * Math.max(1f, mActivity.getResources().getDisplayMetrics().density));
        FrameLayout.LayoutParams invLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        invLp.topMargin = topMargin;
        contentFrame.addView(invBar, invLp);
        mInventoryHelperBar = invBar;
        git.artdeell.mojo.game.platform.Platform.addGrabListener(mContextGrabListener);
    }

    private void collectLiveObstacles(@NonNull ViewGroup hudParent, @NonNull List<RectF> outRects) {
        hudParent.getLocationOnScreen(mParentLoc);
        // 1. Top menu/toolbar drawer button exclusion zone
        appendViewRectIfVisible(mDrawerPullButton, mParentLoc, outRects);
        // 1b. Context-Aware Inventory Helper Bar exclusion zone
        appendViewRectIfVisible(mInventoryHelperBar, mParentLoc, outRects);
        // 2. Interactive hotbar overlay exclusion zone
        appendViewRectIfVisible(mHotbarView, mParentLoc, outRects);
        // 3. All visible custom control buttons, drawers, and joysticks inside ControlLayout
        if (mControlLayout != null) {
            mControlLayout.collectControlObstacleRects(hudParent, outRects);
        }
    }

    private void appendViewRectIfVisible(@Nullable View view,
                                         @NonNull int[] parentScreenOrigin,
                                         @NonNull List<RectF> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0 || view.getAlpha() <= 0.02f) return;
        view.getLocationOnScreen(mViewLoc);
        float left = mViewLoc[0] - parentScreenOrigin[0];
        float top = mViewLoc[1] - parentScreenOrigin[1];
        out.add(new RectF(left, top, left + w, top + h));
    }

    /** Applies display cutout + system bar safe-area insets to the HUD overlay. */
    public void onWindowInsetsChanged(@NonNull WindowInsetsCompat insets) {
        Insets safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.systemBars());
        if (mOverlay != null) {
            mOverlay.setSafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom);
        }
        if (mInventoryHelperBar != null
                && mInventoryHelperBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) mInventoryHelperBar.getLayoutParams();
            int base = Math.round(6f * Math.max(1f, mActivity.getResources().getDisplayMetrics().density));
            lp.topMargin = Math.max(base, safe.top + base / 2);
            mInventoryHelperBar.setLayoutParams(lp);
        }
    }

    /** Re-clamps HUD coordinates when controls load, exit editor, or screen orientation changes. */
    public void onControlsLayoutChanged() {
        if (mInventoryHelperBar != null) {
            boolean inEditor = mControlLayout != null && mControlLayout.getModifiable();
            mInventoryHelperBar.onContextStateChanged(
                    git.artdeell.mojo.game.platform.Platform.isGrabbing(), inEditor);
        }
        if (mOverlay != null && mOverlay.isHudEnabled()) {
            mOverlay.post(mOverlay::applyClampedCollisionFreePlacement);
        }
    }

    /** Applies live HUD customization updates from {@link git.artdeell.mojo.prefs.QuickSettingSideDialog}. */
    public void syncPreferencesLive() {
        if (mOverlay == null) return;
        boolean shouldShow = LauncherPreferences.PREF_ZYROX_PERF_HUD;
        if (shouldShow != mOverlay.isHudEnabled()) {
            mOverlay.setHudEnabled(shouldShow);
        } else if (shouldShow) {
            mOverlay.syncFromPreferences();
        }
    }

    public void resetPosition() {
        if (mOverlay != null) {
            mOverlay.resetPosition();
        } else {
            LauncherPreferences.resetHudPosition();
        }
    }

    public boolean toggleEnabled() {
        boolean next = !(mOverlay != null && mOverlay.isHudEnabled());
        LauncherPreferences.PREF_ZYROX_PERF_HUD = next;
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean("zyroxPerfHud", next).apply();
        }
        if (mOverlay != null) {
            mOverlay.setHudEnabled(next);
        }
        return next;
    }

    public void onResume() {
        if (mOverlay != null && mOverlay.isHudEnabled()) {
            mOverlay.syncFromPreferences();
            mOverlay.startMonitoring();
        }
    }

    public void onPause() {
        if (mInventoryHelperBar != null) {
            mInventoryHelperBar.releaseLatchedModifiers();
        }
        if (mOverlay != null) {
            mOverlay.stopMonitoring(false);
        }
    }

    public void onDestroy() {
        git.artdeell.mojo.game.platform.Platform.removeGrabListener(mContextGrabListener);
        if (mInventoryHelperBar != null) {
            mInventoryHelperBar.releaseLatchedModifiers();
            mInventoryHelperBar = null;
        }
        if (mOverlay != null) {
            mOverlay.stopMonitoring(true);
            mOverlay = null;
        }
    }

    @Nullable
    public PerformanceHudOverlay getOverlay() {
        return mOverlay;
    }
}
