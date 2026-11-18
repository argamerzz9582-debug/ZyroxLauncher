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
    private ControlLayout mControlLayout;
    @Nullable
    private View mDrawerPullButton;
    @Nullable
    private View mHotbarView;
    private final int[] mParentLoc = new int[2];
    private final int[] mViewLoc = new int[2];

    public GameHudCoordinator(@NonNull Activity activity) {
        mActivity = activity;
    }

    public void attach(@Nullable ViewGroup contentFrame,
                       @Nullable ControlLayout controlLayout,
                       @Nullable View drawerPullButton,
                       @Nullable View hotbarView,
                       @NonNull String rendererTag,
                       int effectiveHeapMb) {
        if (contentFrame == null) return;
        mControlLayout = controlLayout;
        mDrawerPullButton = drawerPullButton;
        mHotbarView = hotbarView;

        PerformanceHudOverlay hud = new PerformanceHudOverlay(mActivity);
        hud.configureSession(rendererTag, effectiveHeapMb);
        hud.setObstacleProvider(outRects -> collectLiveObstacles(contentFrame, outRects));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        contentFrame.addView(hud, lp);
        mOverlay = hud;
        hud.setHudEnabled(LauncherPreferences.PREF_ZYROX_PERF_HUD);
    }

    private void collectLiveObstacles(@NonNull ViewGroup hudParent, @NonNull List<RectF> outRects) {
        hudParent.getLocationOnScreen(mParentLoc);
        // 1. Top menu/toolbar drawer button exclusion zone
        appendViewRectIfVisible(mDrawerPullButton, mParentLoc, outRects);
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
        if (mOverlay == null) return;
        Insets safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.systemBars());
        mOverlay.setSafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom);
    }

    /** Re-clamps HUD coordinates when controls load, exit editor, or screen orientation changes. */
    public void onControlsLayoutChanged() {
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
        if (mOverlay != null) {
            mOverlay.stopMonitoring(false);
        }
    }

    public void onDestroy() {
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
