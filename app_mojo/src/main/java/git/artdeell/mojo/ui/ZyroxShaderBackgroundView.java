package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Blur-isolated, full-resolution edge-to-edge Zyrox Launcher Home &amp; sub-page live backdrop:
 * <ol>
 *   <li>Default: Hardware-accelerated Full HD 1080p looping {@link ZyroxLoopingVideoView} playing the
 *       Minecraft-theme backdrop chosen in Settings ({@link ZyroxBackdrops}) with an instant frame-0
 *       WebP poster, an adjustable dim scrim, and zero audio focus interruption. With power-aware
 *       mode on it holds the poster during Battery Saver or thermal throttling.</li>
 *   <li>Custom MP4 override: Plays user-imported {@code zyrox_bg.mp4} through the same zero-flash
 *       hardware pipeline.</li>
 *   <li>Custom GIF override: Lazily instantiates {@link ZyroxSeamlessGifView} only when the user
 *       imports {@code zyrox_bg.gif}, saving heap memory during normal operation.</li>
 * </ol>
 */
public class ZyroxShaderBackgroundView extends FrameLayout {
    public static final String CUSTOM_VIDEO_FILENAME = "zyrox_bg.mp4";
    public static final String CUSTOM_GIF_FILENAME = "zyrox_bg.gif";
    private static final long MAX_CUSTOM_GIF_BYTES = 32L * 1024L * 1024L;

    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.List<java.lang.ref.WeakReference<ZyroxShaderBackgroundView>> ACTIVE_BACKDROPS =
            new java.util.ArrayList<>();

    /**
     * Strictly pauses, recycles bitmaps, aborts GIF decoders, and releases hardware MediaCodec /
     * Surface buffers for every live launcher background view — invoked in {@code onStop()},
     * {@code onTrimMemory()}, pre-launch handoff, and {@code GameService.onStartCommand()}.
     */
    public static void releaseAllForGameStart() {
        ZyroxGifDecoder.cancelAllActiveDecodes();
        ZyroxLoopingVideoView.suspendLauncherBackdrops();
        Runnable task = () -> {
            java.util.List<ZyroxShaderBackgroundView> snapshot = new java.util.ArrayList<>();
            synchronized (ACTIVE_BACKDROPS) {
                for (int i = ACTIVE_BACKDROPS.size() - 1; i >= 0; i--) {
                    ZyroxShaderBackgroundView bg = ACTIVE_BACKDROPS.get(i).get();
                    if (bg == null) {
                        ACTIVE_BACKDROPS.remove(i);
                    } else {
                        snapshot.add(bg);
                    }
                }
            }
            for (ZyroxShaderBackgroundView bg : snapshot) {
                bg.onStop();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private ZyroxLoopingVideoView mHomeVideoView;
    @Nullable
    private ZyroxSeamlessGifView mCustomGifView;
    private View mContrastVeilView;
    private View mDimScrimView;
    private boolean mResumed = false;
    @Nullable
    private android.content.BroadcastReceiver mPowerSaveReceiver;
    @Nullable
    private Object mThermalListener;

    public ZyroxShaderBackgroundView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public ZyroxShaderBackgroundView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZyroxShaderBackgroundView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setTag(ZyroxSeamlessGifView.TAG_BLUR_ISOLATED_BACKDROP);
        setLayerType(LAYER_TYPE_NONE, null);
        setBackgroundColor(Color.parseColor("#060C18"));
        setClipChildren(true);
        setClipToPadding(false);
        setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearRenderEffectApi31();
        }

        // Layer 1: Full-HD 1080p hardware-accelerated Home screen.mp4 backdrop + instant poster
        mHomeVideoView = new ZyroxLoopingVideoView(context);
        mHomeVideoView.setLauncherBackdropRole(true);
        mHomeVideoView.setPosterResource(currentBackdrop().posterRes);
        mHomeVideoView.setPlaybackEnabled(false);
        addView(mHomeVideoView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Layer 1b: user-adjustable dim scrim so white text stays readable on bright scenes (snow)
        mDimScrimView = new View(context);
        mDimScrimView.setClickable(false);
        mDimScrimView.setFocusable(false);
        mDimScrimView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mDimScrimView.setBackgroundColor(
                ZyroxBackdrops.dimPercentToScrimColor(LauncherPreferences.PREF_ZYROX_BACKDROP_DIM));
        addView(mDimScrimView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        synchronized (ACTIVE_BACKDROPS) {
            ACTIVE_BACKDROPS.add(new java.lang.ref.WeakReference<>(this));
        }

        // Layer 2: Subtle edge-only contrast rim (zero blur, 100% clear center so video shines)
        mContrastVeilView = new View(context);
        mContrastVeilView.setLayerType(LAYER_TYPE_NONE, null);
        mContrastVeilView.setBackgroundResource(R.drawable.bg_zyrox_vignette);
        mContrastVeilView.setClickable(false);
        mContrastVeilView.setFocusable(false);
        addView(mContrastVeilView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void clearRenderEffectApi31() {
        super.setRenderEffect(null);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void setRenderEffect(@Nullable RenderEffect renderEffect) {
        super.setRenderEffect(null);
    }

    /** Returns {@code true}: this background container is permanently isolated from blur passes. */
    public boolean isBlurIsolated() {
        return true;
    }

    /** Returns the primary Full-HD looping video backdrop view. */
    @NonNull
    public ZyroxLoopingVideoView getVideoView() {
        return mHomeVideoView;
    }

    /** Updates the custom GIF scaling filter ({@code NEAREST_NEIGHBOR} or {@code BICUBIC}). */
    public void setGifScalingFilter(@NonNull ZyroxSeamlessGifView.ScalingFilter filter) {
        if (mCustomGifView != null) {
            mCustomGifView.setScalingFilter(filter);
        }
    }

    @NonNull
    private ZyroxSeamlessGifView ensureCustomGifView() {
        if (mCustomGifView == null) {
            mCustomGifView = new ZyroxSeamlessGifView(getContext());
            mCustomGifView.syncScalingFilterFromPreferences();
            int veilIdx = indexOfChild(mContrastVeilView);
            addView(mCustomGifView, veilIdx >= 0 ? veilIdx : getChildCount(),
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        return mCustomGifView;
    }

    @Nullable
    private File resolveCustomVideoFile() {
        try {
            if (Tools.DIR_GAME_HOME != null) {
                File customVideo = new File(Tools.DIR_GAME_HOME, CUSTOM_VIDEO_FILENAME);
                if (customVideo.isFile() && customVideo.canRead() && customVideo.length() > 64L) {
                    return customVideo;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private File resolveCustomGifFile() {
        try {
            if (Tools.DIR_GAME_HOME != null) {
                File customGif = new File(Tools.DIR_GAME_HOME, CUSTOM_GIF_FILENAME);
                if (customGif.isFile() && customGif.canRead()
                        && customGif.length() > 16L && customGif.length() <= MAX_CUSTOM_GIF_BYTES) {
                    return customGif;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @NonNull
    private static ZyroxBackdrops.Backdrop currentBackdrop() {
        return ZyroxBackdrops.fromId(LauncherPreferences.PREF_ZYROX_HOME_BACKDROP,
                ZyroxBackdrops.DEFAULT_HOME);
    }

    /** Animation policy: user prefs + reduced motion + (optional) Battery Saver / thermal state. */
    private boolean shouldAnimateNow() {
        Context ctx = getContext();
        return ZyroxBackdrops.shouldAnimate(
                LauncherPreferences.PREF_ZYROX_ANIMATED_BG,
                LauncherPreferences.isReducedMotionEnabled(ctx),
                LauncherPreferences.PREF_ZYROX_BACKDROP_POWER_AWARE,
                ZyroxBackdrops.isPowerSaveMode(ctx),
                ZyroxBackdrops.thermalStatus(ctx));
    }

    public void refreshBackgroundState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearRenderEffectApi31();
        }
        if (mDimScrimView != null) {
            mDimScrimView.setBackgroundColor(
                    ZyroxBackdrops.dimPercentToScrimColor(LauncherPreferences.PREF_ZYROX_BACKDROP_DIM));
        }
        boolean animated = shouldAnimateNow();
        File customGif = resolveCustomGifFile();

        if (customGif != null) {
            mHomeVideoView.trimDecoder();
            mHomeVideoView.setVisibility(View.GONE);
            ZyroxSeamlessGifView gifView = ensureCustomGifView();
            gifView.syncScalingFilterFromPreferences();
            gifView.setVisibility(View.VISIBLE);
            gifView.setGifFile(customGif);
            if (animated && mResumed) {
                gifView.resumeAnimation();
            } else {
                gifView.setPlaybackEnabled(false);
            }
            return;
        }

        // Release any previously allocated custom GIF view immediately
        if (mCustomGifView != null) {
            mCustomGifView.release();
            removeView(mCustomGifView);
            mCustomGifView = null;
        }

        mHomeVideoView.setVisibility(View.VISIBLE);
        File customVideo = resolveCustomVideoFile();
        ZyroxBackdrops.Backdrop backdrop = currentBackdrop();
        if (animated && mResumed) {
            if (customVideo != null) {
                mHomeVideoView.setVideoFile(customVideo, backdrop.posterRes);
            } else {
                mHomeVideoView.setVideoResource(backdrop.videoRes, backdrop.posterRes);
            }
            mHomeVideoView.resumeAnimation();
        } else {
            mHomeVideoView.setPosterResource(backdrop.posterRes);
            mHomeVideoView.setPlaybackEnabled(false);
        }
    }

    public void onResume() {
        mResumed = true;
        registerPowerListeners();
        refreshBackgroundState();
    }

    /**
     * Battery Saver / thermal changes while the launcher is visible re-evaluate the policy, so the
     * backdrop drops to its poster immediately (and resumes once the device recovers).
     */
    private void registerPowerListeners() {
        Context ctx = getContext().getApplicationContext();
        if (mPowerSaveReceiver == null) {
            mPowerSaveReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    if (mResumed) refreshBackgroundState();
                }
            };
            try {
                android.content.IntentFilter filter =
                        new android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Protected system broadcast: still delivered to a non-exported receiver.
                    ctx.registerReceiver(mPowerSaveReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    ctx.registerReceiver(mPowerSaveReceiver, filter);
                }
            } catch (Throwable t) {
                mPowerSaveReceiver = null;
            }
        }
        if (mThermalListener == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mThermalListener = registerThermalListenerApi29(ctx);
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Nullable
    private Object registerThermalListenerApi29(@NonNull Context ctx) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            android.os.PowerManager.OnThermalStatusChangedListener l = status -> {
                if (mResumed) refreshBackgroundState();
            };
            pm.addThermalStatusListener(ctx.getMainExecutor(), l);
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    private void unregisterPowerListeners() {
        Context ctx = getContext().getApplicationContext();
        if (mPowerSaveReceiver != null) {
            try {
                ctx.unregisterReceiver(mPowerSaveReceiver);
            } catch (Throwable ignored) {
            }
            mPowerSaveReceiver = null;
        }
        if (mThermalListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            unregisterThermalListenerApi29(ctx, mThermalListener);
        }
        mThermalListener = null;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void unregisterThermalListenerApi29(@NonNull Context ctx, @NonNull Object listener) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                pm.removeThermalStatusListener((android.os.PowerManager.OnThermalStatusChangedListener) listener);
            }
        } catch (Throwable ignored) {
        }
    }

    public void onPause() {
        mResumed = false;
        unregisterPowerListeners();
        mHomeVideoView.pauseAnimation();
        if (mCustomGifView != null) {
            mCustomGifView.pauseAnimation();
        }
    }

    /**
     * Strictly frees all hardware MediaCodec, Surface, poster Bitmap, and GIF frame buffers when
     * {@code LauncherActivity} stops or {@code GameService} launches on 4 GB–6 GB RAM devices.
     */
    public void onStop() {
        mResumed = false;
        unregisterPowerListeners();
        ZyroxGifDecoder.cancelAllActiveDecodes();
        mHomeVideoView.suspendForBackground(true);
        if (mCustomGifView != null) {
            mCustomGifView.trimFrames();
            mCustomGifView.release();
            removeView(mCustomGifView);
            mCustomGifView = null;
        }
    }

    public void onDestroy() {
        mResumed = false;
        unregisterPowerListeners();
        synchronized (ACTIVE_BACKDROPS) {
            for (int i = ACTIVE_BACKDROPS.size() - 1; i >= 0; i--) {
                ZyroxShaderBackgroundView cur = ACTIVE_BACKDROPS.get(i).get();
                if (cur == null || cur == this) {
                    ACTIVE_BACKDROPS.remove(i);
                }
            }
        }
        ZyroxGifDecoder.cancelAllActiveDecodes();
        mHomeVideoView.release();
        if (mCustomGifView != null) {
            mCustomGifView.release();
            removeView(mCustomGifView);
            mCustomGifView = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        onPause();
        super.onDetachedFromWindow();
    }
}
