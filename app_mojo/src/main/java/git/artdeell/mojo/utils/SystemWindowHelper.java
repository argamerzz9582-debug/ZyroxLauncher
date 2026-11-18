package git.artdeell.mojo.utils;

import static android.os.Build.VERSION.SDK_INT;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Objects;

import git.artdeell.mojo.InsetBackground;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.memory.MemoryHoleFinder;
import git.artdeell.mojo.utils.memory.SelfMapsParser;

/**
 * Single-responsibility display, window-inset, immersive-fullscreen, and system memory helper
 * extracted from {@link Tools}.
 */
public final class SystemWindowHelper {
    private static final String TAG = "SystemWindowHelper";

    private SystemWindowHelper() {
    }

    @NonNull
    public static DisplayMetrics getDisplayMetrics(@NonNull Activity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        if (SDK_INT >= Build.VERSION_CODES.N
                && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())) {
            displayMetrics = activity.getResources().getDisplayMetrics();
        } else if (SDK_INT >= Build.VERSION_CODES.R) {
            Objects.requireNonNull(activity.getDisplay()).getRealMetrics(displayMetrics);
        } else {
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        }
        Tools.currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static void setCutoutMode(@NonNull Window window, boolean ignoreNotch) {
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        if (ignoreNotch) {
            layoutParams.layoutInDisplayCutoutMode = SDK_INT >= Build.VERSION_CODES.R
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        } else {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }
        window.setAttributes(layoutParams);
        window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
    }

    @SuppressWarnings("deprecation")
    private static void applyLegacyFullscreenFlags(@NonNull View insetView) {
        insetView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyFullscreen(@NonNull View insetView, boolean fullscreen) {
        View.OnSystemUiVisibilityChangeListener listener = visibility -> {
            if (fullscreen && (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                applyLegacyFullscreenFlags(insetView);
            } else if (!fullscreen) {
                insetView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        };
        if (fullscreen) {
            applyLegacyFullscreenFlags(insetView);
        } else {
            insetView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        insetView.setOnSystemUiVisibilityChangeListener(listener);
    }

    /** Re-applies sticky immersive system bar hiding when window focus returns. */
    public static void hideSystemBars(@NonNull Activity activity) {
        if (SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode()) return;
        Window window = activity.getWindow();
        if (window == null) return;
        if (SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            View insetView = activity.findViewById(android.R.id.content);
            if (insetView != null) {
                applyLegacyFullscreenFlags(insetView);
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static void setInsetsMode(@NonNull Activity activity,
                                     boolean noSystemBars,
                                     boolean ignoreNotch) {
        Window window = activity.getWindow();
        View insetView = activity.findViewById(android.R.id.content);
        if (window == null || insetView == null) return;

        if (SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode()) {
            noSystemBars = false;
        }
        final int bgColor = !noSystemBars
                ? activity.getResources().getColor(R.color.background_status_bar)
                : Color.TRANSPARENT;

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (SDK_INT >= Build.VERSION_CODES.P) {
            setCutoutMode(window, ignoreNotch);
        }
        if (SDK_INT < Build.VERSION_CODES.R) {
            setLegacyFullscreen(insetView, noSystemBars);
            return;
        }

        window.setDecorFitsSystemWindows(false);
        WindowInsetsController insetsController = window.getInsetsController();
        if (insetsController != null) {
            insetsController.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (noSystemBars) {
                insetsController.hide(WindowInsets.Type.systemBars());
            } else {
                insetsController.show(WindowInsets.Type.systemBars());
            }
        }

        final boolean fFullscreen = noSystemBars;
        insetView.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            int insetMask = 0;
            if (!fFullscreen) insetMask |= WindowInsets.Type.systemBars();
            if (!ignoreNotch) insetMask |= WindowInsets.Type.displayCutout();
            if (insetMask != 0) {
                Insets insets = windowInsets.getInsets(insetMask);
                v.setBackground(new InsetBackground(insets, bgColor));
                insetView.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            } else {
                insetView.setPadding(0, 0, 0, 0);
                v.setBackground(null);
            }
            return WindowInsets.CONSUMED;
        });
        insetView.requestApplyInsets();
    }

    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        int scaled = (int) (displaySideRes * scaling);
        return (scaled & 1) == 0 ? scaled : scaled - 1;
    }

    public static int getTotalDeviceMemoryMb(@NonNull Context ctx) {
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (actManager == null) return 2048;
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.totalMem / 1048576L);
    }

    public static int getFreeDeviceMemoryMb(@NonNull Context ctx) {
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (actManager == null) return 512;
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.availMem / 1048576L);
    }

    public static int getMaxContinuousAddressSpaceSizeMb() {
        try {
            MemoryHoleFinder finder = new MemoryHoleFinder();
            new SelfMapsParser(finder).run();
            long largestHole = finder.getLargestHole();
            return largestHole == -1L ? -1 : (int) (largestHole / 1048576L);
        } catch (Exception e) {
            Log.w(TAG, "Failed to find the largest uninterrupted address space");
            return -1;
        }
    }
}
