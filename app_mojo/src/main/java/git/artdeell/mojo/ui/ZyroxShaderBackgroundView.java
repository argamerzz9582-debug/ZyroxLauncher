package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Color;
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

import java.io.File;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Global 100% edge-to-edge Zyrox Launcher Home &amp; sub-page live backdrop:
 * <ol>
 *   <li>Full-bleed, VSYNC-locked, zero-buffer seamless cherry-blossom lakeside gazebo GIF
 *       ({@code R.raw.zyrox_home_scene}) rendered via {@link ZyroxSeamlessGifView} with zero
 *       procedural particle/Ken-Burns overlay clutter.</li>
 *   <li>Optional muted looping {@link VideoView} override when the user explicitly imports a custom
 *       {@code zyrox_bg.mp4} in Miscellaneous Settings.</li>
 *   <li>Whisper-light optical edge vignette keeping snow-white Liquid Glass labels legible over
 *       sunlit sky while letting the full cherry-blossom scene shine vividly.</li>
 * </ol>
 */
public class ZyroxShaderBackgroundView extends FrameLayout {
    public static final String CUSTOM_VIDEO_FILENAME = "zyrox_bg.mp4";

    private ZyroxSeamlessGifView mHomeGifView;
    private VideoView mVideoView;
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
        setBackgroundColor(Color.parseColor("#0B0910"));
        setClipChildren(true);
        setClipToPadding(false);
        setPadding(0, 0, 0, 0);

        // Layer 1: Seamless, zero-buffer full-bleed cherry-blossom gazebo GIF (default Home scene)
        mHomeGifView = new ZyroxSeamlessGifView(context);
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

        // Layer 3: Delicate optical contrast veil so Liquid Glass controls stay crisp on sunlit sky
        View vignetteView = new View(context);
        vignetteView.setBackgroundResource(R.drawable.bg_zyrox_vignette);
        vignetteView.setClickable(false);
        vignetteView.setFocusable(false);
        addView(vignetteView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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

    public void refreshBackgroundState() {
        boolean animated = LauncherPreferences.PREF_ZYROX_ANIMATED_BG;
        Uri customVideoUri = animated ? resolveCustomVideoUri() : null;

        if (customVideoUri != null && mResumed) {
            mHomeGifView.pauseAnimation();
            mHomeGifView.setVisibility(View.GONE);
            startLoopingVideo(customVideoUri);
        } else {
            stopVideo();
            mHomeGifView.setVisibility(View.VISIBLE);
            mHomeGifView.setGifResource(R.raw.zyrox_home_scene);
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
