package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.VideoView;

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
 *   <li>Full-resolution hardware-accelerated {@link ZyroxSeamlessGifView} ({@code ImageDecoder} +
 *       {@link android.graphics.drawable.AnimatedImageDrawable} on API 28+, lossless 32-bit
 *       {@code ARGB_8888} on API 21–27) completely decoupled from foreground Liquid Glass blur.</li>
 *   <li>Supports both {@link ZyroxSeamlessGifView.ScalingFilter#NEAREST_NEIGHBOR} (pixel art) and
 *       {@link ZyroxSeamlessGifView.ScalingFilter#BICUBIC} (HD animated GIFs).</li>
 *   <li>Optional muted looping {@link VideoView} override when {@code zyrox_bg.mp4} is imported.</li>
 * </ol>
 */
public class ZyroxShaderBackgroundView extends FrameLayout {
    public static final String CUSTOM_VIDEO_FILENAME = "zyrox_bg.mp4";
    public static final String CUSTOM_GIF_FILENAME = "zyrox_bg.gif";
    private static final long MAX_CUSTOM_GIF_BYTES = 32L * 1024L * 1024L;

    private ZyroxSeamlessGifView mHomeGifView;
    private VideoView mVideoView;
    private View mContrastVeilView;
    private boolean mResumed = false;
    private boolean mVideoActive = false;
    private int mVideoWidth = 0;
    private int mVideoHeight = 0;

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
        setBackgroundColor(Color.parseColor("#0A101E"));
        setClipChildren(true);
        setClipToPadding(false);
        setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearRenderEffectApi31();
        }

        // Layer 1: Blur-isolated, full-native-resolution hardware AnimatedImageDrawable GIF
        mHomeGifView = new ZyroxSeamlessGifView(context);
        mHomeGifView.syncScalingFilterFromPreferences();
        mHomeGifView.setGifResource(R.raw.zyrox_home_scene);
        addView(mHomeGifView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Layer 2: Optional muted looping VideoView (only when user imports a custom zyrox_bg.mp4)
        mVideoView = new VideoView(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mVideoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        }
        mVideoView.setVisibility(View.GONE);
        LayoutParams videoParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        addView(mVideoView, videoParams);

        // Layer 3: Subtle edge-only contrast rim (zero blur, 100% transparent center so GIF stays crisp)
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

    /**
     * Prevents any window/container blur {@link RenderEffect} from being applied to the backdrop
     * hierarchy — glass blur is strictly restricted to foreground UI cards.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void setRenderEffect(@Nullable RenderEffect renderEffect) {
        super.setRenderEffect(null);
    }

    /** Returns {@code true}: this background container is permanently isolated from blur passes. */
    public boolean isBlurIsolated() {
        return true;
    }

    /** Returns the underlying blur-isolated {@link ZyroxSeamlessGifView}. */
    @NonNull
    public ZyroxSeamlessGifView getGifView() {
        return mHomeGifView;
    }

    /** Updates the GIF scaling filter ({@code NEAREST_NEIGHBOR} or {@code BICUBIC}) immediately. */
    public void setGifScalingFilter(@NonNull ZyroxSeamlessGifView.ScalingFilter filter) {
        if (mHomeGifView != null) {
            mHomeGifView.setScalingFilter(filter);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mVideoActive && mVideoWidth > 0 && mVideoHeight > 0 && w > 0 && h > 0) {
            post(() -> applyCenterCropToVideo(mVideoWidth, mVideoHeight));
        }
    }

    @Nullable
    private Uri resolveCustomVideoUri() {
        try {
            if (Tools.DIR_GAME_HOME != null) {
                File customVideo = new File(Tools.DIR_GAME_HOME, CUSTOM_VIDEO_FILENAME);
                if (customVideo.isFile() && customVideo.canRead() && customVideo.length() > 0) {
                    return Uri.fromFile(customVideo);
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
                        && customGif.length() > 16 && customGif.length() <= MAX_CUSTOM_GIF_BYTES) {
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
        mHomeGifView.syncScalingFilterFromPreferences();
        boolean animated = LauncherPreferences.PREF_ZYROX_ANIMATED_BG;
        File customGif = resolveCustomGifFile();
        Uri customVideoUri = (animated && customGif == null) ? resolveCustomVideoUri() : null;

        if (customVideoUri != null && mResumed) {
            mHomeGifView.pauseAnimation();
            mHomeGifView.setVisibility(View.GONE);
            startLoopingVideo(customVideoUri);
        } else {
            stopVideo();
            mHomeGifView.setVisibility(View.VISIBLE);
            if (customGif != null) {
                mHomeGifView.setGifFile(customGif);
            } else {
                mHomeGifView.setGifResource(R.raw.zyrox_home_scene);
            }
            if (animated && mResumed) {
                mHomeGifView.resumeAnimation();
            } else {
                mHomeGifView.setPlaybackEnabled(false);
            }
        }
    }

    private void startLoopingVideo(@NonNull Uri uri) {
        try {
            mVideoView.setVisibility(View.VISIBLE);
            mVideoView.setOnPreparedListener(mp -> {
                try {
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    mVideoWidth = mp.getVideoWidth();
                    mVideoHeight = mp.getVideoHeight();
                    applyCenterCropToVideo(mVideoWidth, mVideoHeight);
                    mVideoActive = true;
                    mp.start();
                } catch (Throwable ignored) {
                    fallbackToHomeGif();
                }
            });
            mVideoView.setOnErrorListener((mp, what, extra) -> {
                fallbackToHomeGif();
                return true;
            });
            mVideoView.setVideoURI(uri);
        } catch (Throwable ignored) {
            fallbackToHomeGif();
        }
    }

    private void fallbackToHomeGif() {
        stopVideo();
        mHomeGifView.setVisibility(View.VISIBLE);
        if (mResumed && LauncherPreferences.PREF_ZYROX_ANIMATED_BG) {
            mHomeGifView.resumeAnimation();
        }
    }

    private void applyCenterCropToVideo(int videoW, int videoH) {
        int viewW = getWidth();
        int viewH = getHeight();
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return;

        float overscan = 1.02f;
        float viewRatio = (float) viewW / (float) viewH;
        float videoRatio = (float) videoW / (float) videoH;
        LayoutParams lp = (LayoutParams) mVideoView.getLayoutParams();
        if (videoRatio > viewRatio) {
            lp.height = (int) Math.ceil(viewH * overscan);
            lp.width = (int) Math.ceil(viewH * videoRatio * overscan);
        } else {
            lp.width = (int) Math.ceil(viewW * overscan);
            lp.height = (int) Math.ceil((viewW / videoRatio) * overscan);
        }
        lp.gravity = Gravity.CENTER;
        mVideoView.setLayoutParams(lp);
    }

    private void stopVideo() {
        mVideoActive = false;
        mVideoWidth = 0;
        mVideoHeight = 0;
        try {
            if (mVideoView.isPlaying()) {
                mVideoView.stopPlayback();
            }
        } catch (Throwable ignored) {
        }
        mVideoView.setVisibility(View.GONE);
    }

    public void onResume() {
        mResumed = true;
        refreshBackgroundState();
    }

    public void onPause() {
        mResumed = false;
        mHomeGifView.pauseAnimation();
        stopVideo();
    }

    /** Frees Home GIF frame buffers when LauncherActivity is hidden (e.g. during Minecraft play). */
    public void onStop() {
        mResumed = false;
        mHomeGifView.trimFrames();
        stopVideo();
    }

    public void onDestroy() {
        mResumed = false;
        mHomeGifView.release();
        stopVideo();
    }

    @Override
    protected void onDetachedFromWindow() {
        onPause();
        super.onDetachedFromWindow();
    }
}
