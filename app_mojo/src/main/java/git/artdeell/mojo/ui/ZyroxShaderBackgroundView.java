package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.security.SecureRandom;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Global Zyrox Launcher background canvas:
 * 1) Muted (0% volume), center-cropped looping Minecraft shader VideoView when a custom
 *    zyrox_bg.mp4 (or bundled raw/zyrox_bg_loop) is present and animation is enabled.
 * 2) Built-in golden-hour Minecraft Create-Mod shader scene with smooth cinematic Ken Burns
 *    motion, warm volumetric sunbeam pulse, and drifting glowing amber shader embers.
 * 3) Warm dark obsidian cinematic vignette (#B30A0807 -> #330A0807) for high contrast readability.
 */
public class ZyroxShaderBackgroundView extends FrameLayout {
    public static final String CUSTOM_VIDEO_FILENAME = "zyrox_bg.mp4";

    private ImageView mShaderImageView;
    private VideoView mVideoView;
    private EmberShaderLayer mEmberLayer;
    private View mVignetteView;
    private boolean mResumed = false;
    private boolean mVideoActive = false;

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

    private void init(Context context) {
        setBackgroundColor(Color.parseColor("#0D0B0A"));
        setClipChildren(true);

        // Layer 1: Built-in high-res golden-hour Minecraft Create-Mod shader scene
        mShaderImageView = new ImageView(context);
        mShaderImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mShaderImageView.setImageResource(R.drawable.bg_zyrox_shader_scene);
        mShaderImageView.setScaleX(1.08f);
        mShaderImageView.setScaleY(1.08f);
        addView(mShaderImageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Layer 2: Optional muted looping VideoView (center-cropped, 0% volume)
        mVideoView = new VideoView(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mVideoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        }
        mVideoView.setVisibility(View.GONE);
        LayoutParams videoParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        addView(mVideoView, videoParams);

        // Layer 3: Real-time warm volumetric glow + floating Minecraft shader embers
        mEmberLayer = new EmberShaderLayer(context);
        addView(mEmberLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Layer 4: Dark cinematic warm vignette overlay
        mVignetteView = new View(context);
        mVignetteView.setBackgroundResource(R.drawable.bg_zyrox_vignette);
        addView(mVignetteView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Nullable
    private Uri resolveVideoUri() {
        try {
            if (Tools.DIR_GAME_HOME != null) {
                File customVideo = new File(Tools.DIR_GAME_HOME, CUSTOM_VIDEO_FILENAME);
                if (customVideo.isFile() && customVideo.canRead() && customVideo.length() > 0) {
                    return Uri.fromFile(customVideo);
                }
            }
            int rawId = getResources().getIdentifier("zyrox_bg_loop", "raw", getContext().getPackageName());
            if (rawId != 0) {
                return Uri.parse("android.resource://" + getContext().getPackageName() + "/" + rawId);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void refreshBackgroundState() {
        boolean animated = LauncherPreferences.PREF_ZYROX_ANIMATED_BG;
        Uri videoUri = animated ? resolveVideoUri() : null;

        if (videoUri != null && mResumed) {
            startLoopingVideo(videoUri);
        } else {
            stopVideo();
        }

        if (!animated) {
            mShaderImageView.setScaleX(1.04f);
            mShaderImageView.setScaleY(1.04f);
            mShaderImageView.setTranslationX(0f);
            mShaderImageView.setTranslationY(0f);
        }
        mEmberLayer.setAnimated(animated && mResumed);
    }

    private void startLoopingVideo(@NonNull Uri uri) {
        try {
            mVideoView.setVisibility(View.VISIBLE);
            mVideoView.setOnPreparedListener(mp -> {
                try {
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    applyCenterCropToVideo(mp.getVideoWidth(), mp.getVideoHeight());
                    mVideoActive = true;
                    mp.start();
                } catch (Throwable ignored) {
                    stopVideo();
                }
            });
            mVideoView.setOnErrorListener((mp, what, extra) -> {
                stopVideo();
                return true; // Suppress default Android VideoView error dialog
            });
            mVideoView.setVideoURI(uri);
        } catch (Throwable ignored) {
            stopVideo();
        }
    }

    private void applyCenterCropToVideo(int videoW, int videoH) {
        int viewW = getWidth();
        int viewH = getHeight();
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return;

        float viewRatio = (float) viewW / (float) viewH;
        float videoRatio = (float) videoW / (float) videoH;
        LayoutParams lp = (LayoutParams) mVideoView.getLayoutParams();
        if (videoRatio > viewRatio) {
            lp.height = viewH;
            lp.width = (int) (viewH * videoRatio);
        } else {
            lp.width = viewW;
            lp.height = (int) (viewW / videoRatio);
        }
        lp.gravity = Gravity.CENTER;
        mVideoView.setLayoutParams(lp);
    }

    private void stopVideo() {
        mVideoActive = false;
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
        stopVideo();
        mEmberLayer.setAnimated(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        onPause();
        super.onDetachedFromWindow();
    }

    /**
     * Lightweight hardware-accelerated shader particle & Ken Burns controller.
     */
    private class EmberShaderLayer extends View {
        private static final int EMBER_COUNT = 26;
        private final float[] mX = new float[EMBER_COUNT];
        private final float[] mY = new float[EMBER_COUNT];
        private final float[] mSpeedY = new float[EMBER_COUNT];
        private final float[] mDriftX = new float[EMBER_COUNT];
        private final float[] mRadius = new float[EMBER_COUNT];
        private final float[] mPhase = new float[EMBER_COUNT];

        private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSunbeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SecureRandom mRandom = new SecureRandom();
        private boolean mRunning = false;
        private boolean mSeeded = false;
        private long mStartTimeMs = 0L;
        private long mLastFrameMs = 0L;
        private RadialGradient mSunGradient;
        private int mCachedW = -1;
        private int mCachedH = -1;

        EmberShaderLayer(Context context) {
            super(context);
            mCorePaint.setStyle(Paint.Style.FILL);
            mGlowPaint.setStyle(Paint.Style.FILL);
        }

        void setAnimated(boolean running) {
            if (mRunning == running) return;
            mRunning = running;
            if (running) {
                long now = System.currentTimeMillis();
                if (mStartTimeMs == 0L) mStartTimeMs = now;
                mLastFrameMs = now;
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private void seedParticles(int w, int h) {
            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < EMBER_COUNT; i++) {
                mX[i] = mRandom.nextFloat() * w;
                mY[i] = mRandom.nextFloat() * h;
                mSpeedY[i] = (8f + mRandom.nextFloat() * 18f) * density;
                mDriftX[i] = (6f + mRandom.nextFloat() * 14f) * density;
                mRadius[i] = (1.1f + mRandom.nextFloat() * 2.1f) * density;
                mPhase[i] = mRandom.nextFloat() * 6.28318f;
            }
            mSeeded = true;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (!mSeeded) seedParticles(w, h);
            if (w != mCachedW || h != mCachedH) {
                mCachedW = w;
                mCachedH = h;
                mSunGradient = new RadialGradient(
                        w * 0.34f, h * 0.34f,
                        Math.max(w, h) * 0.55f,
                        new int[]{0x2EFF9E44, 0x12F97316, 0x00000000},
                        new float[]{0f, 0.48f, 1f},
                        Shader.TileMode.CLAMP
                );
                mSunbeamPaint.setShader(mSunGradient);
            }

            long now = System.currentTimeMillis();
            float elapsedSec = mStartTimeMs == 0L ? 0f : (now - mStartTimeMs) * 0.001f;
            float dt = mLastFrameMs == 0L ? 0.016f : Math.min(0.05f, (now - mLastFrameMs) * 0.001f);
            mLastFrameMs = now;

            // Subtle cinematic Ken Burns motion on the built-in shader scene
            if (mRunning && !mVideoActive) {
                float zoom = 1.085f + 0.03f * (float) Math.sin(elapsedSec * 0.14f);
                float panX = (float) Math.sin(elapsedSec * 0.11f) * (w * 0.022f);
                float panY = (float) Math.cos(elapsedSec * 0.09f) * (h * 0.016f);
                mShaderImageView.setScaleX(zoom);
                mShaderImageView.setScaleY(zoom);
                mShaderImageView.setTranslationX(panX);
                mShaderImageView.setTranslationY(panY);
            }

            // Warm golden-hour volumetric sun bloom
            if (mSunGradient != null) {
                int bloomAlpha = (int) (180 + 55 * Math.sin(elapsedSec * 0.55f));
                mSunbeamPaint.setAlpha(bloomAlpha);
                canvas.drawRect(0, 0, w, h, mSunbeamPaint);
            }

            // Drifting warm amber shader fireflies / forge embers
            for (int i = 0; i < EMBER_COUNT; i++) {
                if (mRunning) {
                    mY[i] -= mSpeedY[i] * dt;
                    float xWave = (float) Math.sin(elapsedSec * 0.9f + mPhase[i]) * mDriftX[i] * dt;
                    mX[i] += xWave;
                    if (mY[i] < -16f) {
                        mY[i] = h + 12f;
                        mX[i] = mRandom.nextFloat() * w;
                    }
                    if (mX[i] < -16f) mX[i] = w + 8f;
                    if (mX[i] > w + 16f) mX[i] = -8f;
                }

                float twinkle = 0.45f + 0.55f * (float) Math.sin(elapsedSec * 2.1f + mPhase[i]);
                int glowAlpha = Math.max(0, Math.min(255, (int) (72f * twinkle)));
                int coreAlpha = Math.max(0, Math.min(255, (int) (195f * twinkle)));

                mGlowPaint.setColor((glowAlpha << 24) | 0xF97316);
                canvas.drawCircle(mX[i], mY[i], mRadius[i] * 3.1f, mGlowPaint);

                mCorePaint.setColor((coreAlpha << 24) | 0xFED7AA);
                canvas.drawCircle(mX[i], mY[i], mRadius[i], mCorePaint);
            }

            if (mRunning) {
                postInvalidateDelayed(33L); // Smooth ~30fps low-power ambient shader loop
            }
        }
    }
}
