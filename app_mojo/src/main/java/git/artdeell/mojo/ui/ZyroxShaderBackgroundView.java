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
import android.os.SystemClock;
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
 * Global full-bleed edge-to-edge Zyrox Launcher shader background canvas:
 * 1) Muted (0% volume), overscan-cropped looping Minecraft shader VideoView when a custom
 *    zyrox_bg.mp4 (or bundled raw/zyrox_bg_loop) is present and animation is enabled.
 * 2) Built-in golden-hour Minecraft Create-Mod shader scene with VSYNC-locked full-screen
 *    Ken Burns camera drift, dual volumetric golden sun-bloom layers, and multi-depth
 *    ambient bokeh + forge ember fireflies spanning 100% of the physical display (including
 *    display-cutout zones and behind the translucent right control panel).
 * 3) Delicate atmospheric cinema vignette preserving vibrant full-screen scene visibility.
 */
public class ZyroxShaderBackgroundView extends FrameLayout {
    public static final String CUSTOM_VIDEO_FILENAME = "zyrox_bg.mp4";

    private ImageView mShaderImageView;
    private VideoView mVideoView;
    private EmberShaderLayer mEmberLayer;
    private View mVignetteView;
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

    private void init(Context context) {
        setBackgroundColor(Color.parseColor("#0D0A08"));
        setClipChildren(true);
        setClipToPadding(false);
        setPadding(0, 0, 0, 0);

        // Layer 1: Built-in high-res golden-hour Minecraft Create-Mod shader scene (full-bleed overscan)
        mShaderImageView = new ImageView(context);
        mShaderImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mShaderImageView.setImageResource(R.drawable.bg_zyrox_shader_scene);
        mShaderImageView.setScaleX(1.12f);
        mShaderImageView.setScaleY(1.12f);
        addView(mShaderImageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Layer 2: Optional muted looping VideoView (full-screen overscan center-crop, 0% volume)
        mVideoView = new VideoView(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mVideoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        }
        mVideoView.setVisibility(View.GONE);
        LayoutParams videoParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        addView(mVideoView, videoParams);

        // Layer 3: VSYNC-smooth full-screen volumetric sunbeams, bokeh & floating shader embers
        mEmberLayer = new EmberShaderLayer(context);
        addView(mEmberLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Layer 4: Subtle optical cinema vignette (keeps scene vibrant edge-to-edge)
        mVignetteView = new View(context);
        mVignetteView.setBackgroundResource(R.drawable.bg_zyrox_vignette);
        addView(mVignetteView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mVideoActive && mVideoWidth > 0 && mVideoHeight > 0 && w > 0 && h > 0) {
            post(() -> applyCenterCropToVideo(mVideoWidth, mVideoHeight));
        }
        if (mEmberLayer != null && w > 0 && h > 0) {
            mEmberLayer.onViewportResized(w, h, oldw, oldh);
        }
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
        boolean animated = LauncherPreferences.PREF_ZYROX_ANIMATED_BG
                && !LauncherPreferences.isReducedMotionEnabled(getContext())
                && !LauncherPreferences.PREF_ZYROX_LOW_EFFECTS;
        Uri videoUri = animated ? resolveVideoUri() : null;

        if (videoUri != null && mResumed) {
            startLoopingVideo(videoUri);
        } else {
            stopVideo();
        }

        if (!animated) {
            mShaderImageView.setScaleX(1.06f);
            mShaderImageView.setScaleY(1.06f);
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
                    mVideoWidth = mp.getVideoWidth();
                    mVideoHeight = mp.getVideoHeight();
                    applyCenterCropToVideo(mVideoWidth, mVideoHeight);
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

        // Slight 2% overscan guarantees zero sub-pixel black bars across ultra-wide cutout displays
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
        stopVideo();
        mEmberLayer.setAnimated(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        onPause();
        super.onDetachedFromWindow();
    }

    /**
     * Hardware-accelerated VSYNC full-screen shader bloom, bokeh & Ken Burns animator.
     */
    private class EmberShaderLayer extends View {
        private static final int EMBER_COUNT = 34;
        private final float[] mX = new float[EMBER_COUNT];
        private final float[] mY = new float[EMBER_COUNT];
        private final float[] mSpeedY = new float[EMBER_COUNT];
        private final float[] mDriftX = new float[EMBER_COUNT];
        private final float[] mRadius = new float[EMBER_COUNT];
        private final float[] mPhase = new float[EMBER_COUNT];
        private final boolean[] mIsBokeh = new boolean[EMBER_COUNT];

        private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSunbeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mAmbientRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SecureRandom mRandom = new SecureRandom();
        private boolean mRunning = false;
        private boolean mSeeded = false;
        private long mStartTimeMs = 0L;
        private long mLastFrameMs = 0L;
        private RadialGradient mSunGradient;
        private RadialGradient mAmbientRimGradient;
        private int mCachedW = -1;
        private int mCachedH = -1;

        EmberShaderLayer(Context context) {
            super(context);
            mCorePaint.setStyle(Paint.Style.FILL);
            mGlowPaint.setStyle(Paint.Style.FILL);
        }

        void onViewportResized(int w, int h, int oldw, int oldh) {
            if (!mSeeded) {
                seedParticles(w, h);
                return;
            }
            if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) {
                float sx = (float) w / (float) oldw;
                float sy = (float) h / (float) oldh;
                for (int i = 0; i < EMBER_COUNT; i++) {
                    mX[i] *= sx;
                    mY[i] *= sy;
                }
            }
            mCachedW = -1;
            mCachedH = -1;
        }

        void setAnimated(boolean running) {
            if (mRunning == running) return;
            mRunning = running;
            if (running) {
                long now = SystemClock.uptimeMillis();
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
                boolean bokeh = (i < 8);
                mIsBokeh[i] = bokeh;
                if (bokeh) {
                    mSpeedY[i] = (4f + mRandom.nextFloat() * 8f) * density;
                    mDriftX[i] = (8f + mRandom.nextFloat() * 12f) * density;
                    mRadius[i] = (5.5f + mRandom.nextFloat() * 7.5f) * density;
                } else {
                    mSpeedY[i] = (9f + mRandom.nextFloat() * 20f) * density;
                    mDriftX[i] = (7f + mRandom.nextFloat() * 15f) * density;
                    mRadius[i] = (1.0f + mRandom.nextFloat() * 2.0f) * density;
                }
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
                float diag = Math.max(w, h);
                mSunGradient = new RadialGradient(
                        w * 0.36f, h * 0.30f,
                        diag * 0.62f,
                        new int[]{0x30FFA84C, 0x14F97316, 0x00000000},
                        new float[]{0f, 0.52f, 1f},
                        Shader.TileMode.CLAMP
                );
                mSunbeamPaint.setShader(mSunGradient);

                mAmbientRimGradient = new RadialGradient(
                        w * 0.86f, h * 0.26f,
                        diag * 0.44f,
                        new int[]{0x22FF9E44, 0x0CF97316, 0x00000000},
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP
                );
                mAmbientRimPaint.setShader(mAmbientRimGradient);
            }

            long now = SystemClock.uptimeMillis();
            float elapsedSec = mStartTimeMs == 0L ? 0f : (now - mStartTimeMs) * 0.001f;
            float dt = mLastFrameMs == 0L ? 0.016f : Math.min(0.05f, (now - mLastFrameMs) * 0.001f);
            mLastFrameMs = now;

            // Full-bleed VSYNC Ken Burns drift across 100% of the physical display (zero border reveal)
            if (mRunning && !mVideoActive) {
                float zoom = 1.12f + 0.038f * (float) Math.sin(elapsedSec * 0.16f);
                float panX = (float) Math.sin(elapsedSec * 0.12f) * (w * 0.026f);
                float panY = (float) Math.cos(elapsedSec * 0.095f) * (h * 0.020f);
                mShaderImageView.setScaleX(zoom);
                mShaderImageView.setScaleY(zoom);
                mShaderImageView.setTranslationX(panX);
                mShaderImageView.setTranslationY(panY);
            }

            // Full-screen warm golden-hour volumetric sun bloom + soft right-glass caustic glow
            if (mSunGradient != null) {
                int bloomAlpha = (int) (175 + 60 * Math.sin(elapsedSec * 0.52f));
                mSunbeamPaint.setAlpha(bloomAlpha);
                canvas.drawRect(0, 0, w, h, mSunbeamPaint);
            }
            if (mAmbientRimGradient != null) {
                int rimAlpha = (int) (150 + 65 * Math.cos(elapsedSec * 0.42f + 1.1f));
                mAmbientRimPaint.setAlpha(rimAlpha);
                canvas.drawRect(0, 0, w, h, mAmbientRimPaint);
            }

            // Multi-depth warm shader bokeh orbs + drifting golden fireflies edge-to-edge
            for (int i = 0; i < EMBER_COUNT; i++) {
                if (mRunning) {
                    mY[i] -= mSpeedY[i] * dt;
                    float xWave = (float) Math.sin(elapsedSec * 0.85f + mPhase[i]) * mDriftX[i] * dt;
                    mX[i] += xWave;
                    float margin = mRadius[i] * 3.5f + 8f;
                    if (mY[i] < -margin) {
                        mY[i] = h + margin;
                        mX[i] = mRandom.nextFloat() * w;
                    }
                    if (mX[i] < -margin) mX[i] = w + margin * 0.5f;
                    if (mX[i] > w + margin) mX[i] = -margin * 0.5f;
                }

                if (mIsBokeh[i]) {
                    float pulse = 0.4f + 0.6f * (float) Math.sin(elapsedSec * 0.9f + mPhase[i]);
                    int bokehAlpha = Math.max(0, Math.min(255, (int) (26f * pulse)));
                    mGlowPaint.setColor((bokehAlpha << 24) | 0xFFBE76);
                    canvas.drawCircle(mX[i], mY[i], mRadius[i], mGlowPaint);
                } else {
                    float twinkle = 0.42f + 0.58f * (float) Math.sin(elapsedSec * 2.0f + mPhase[i]);
                    int glowAlpha = Math.max(0, Math.min(255, (int) (74f * twinkle)));
                    int coreAlpha = Math.max(0, Math.min(255, (int) (205f * twinkle)));

                    mGlowPaint.setColor((glowAlpha << 24) | 0xF97316);
                    canvas.drawCircle(mX[i], mY[i], mRadius[i] * 3.2f, mGlowPaint);

                    mCorePaint.setColor((coreAlpha << 24) | 0xFFF0D4);
                    canvas.drawCircle(mX[i], mY[i], mRadius[i], mCorePaint);
                }
            }

            if (mRunning) {
                postInvalidateOnAnimation();
            }
        }
    }
}
