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
 *   <li>Default: Hardware-accelerated Full HD 1080p looping {@link ZyroxLoopingVideoView} playing
 *       {@code R.raw.zyrox_home_scene} with an instant frame-0 WebP poster ({@code bg_zyrox_shader_scene})
 *       and zero audio focus interruption.</li>
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

    private ZyroxLoopingVideoView mHomeVideoView;
    @Nullable
    private ZyroxSeamlessGifView mCustomGifView;
    private View mContrastVeilView;
    private boolean mResumed = false;

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
        mHomeVideoView.setPosterResource(R.drawable.bg_zyrox_shader_scene);
        mHomeVideoView.setPlaybackEnabled(false);
        addView(mHomeVideoView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

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

    public void refreshBackgroundState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearRenderEffectApi31();
        }
        boolean animated = LauncherPreferences.PREF_ZYROX_ANIMATED_BG;
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
        if (animated && mResumed) {
            if (customVideo != null) {
                mHomeVideoView.setVideoFile(customVideo, R.drawable.bg_zyrox_shader_scene);
            } else {
                mHomeVideoView.setVideoResource(R.raw.zyrox_home_scene, R.drawable.bg_zyrox_shader_scene);
            }
            mHomeVideoView.resumeAnimation();
        } else {
            mHomeVideoView.setPosterResource(R.drawable.bg_zyrox_shader_scene);
            mHomeVideoView.setPlaybackEnabled(false);
        }
    }

    public void onResume() {
        mResumed = true;
        refreshBackgroundState();
    }

    public void onPause() {
        mResumed = false;
        mHomeVideoView.pauseAnimation();
        if (mCustomGifView != null) {
            mCustomGifView.pauseAnimation();
        }
    }

    /** Frees all hardware MediaCodec / GIF buffers when LauncherActivity is hidden (e.g. in-game). */
    public void onStop() {
        mResumed = false;
        mHomeVideoView.trimDecoder();
        if (mCustomGifView != null) {
            mCustomGifView.trimFrames();
        }
    }

    public void onDestroy() {
        mResumed = false;
        mHomeVideoView.release();
        if (mCustomGifView != null) {
            mCustomGifView.release();
            mCustomGifView = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        onPause();
        super.onDetachedFromWindow();
    }
}
