package git.artdeell.mojo.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hardware-accelerated, blur-isolated, zero-black-flash full-bleed looping MP4 backdrop view used
 * on both the Zyrox Home screen and the Minecraft Loading screen (clips listed in
 * {@link ZyroxBackdrops}).
 *
 * <h3>Smoothness &amp; Memory Architecture</h3>
 * <ul>
 *   <li><b>Zero-copy APK mmap</b>: Opens uncompressed {@code res/raw/*.mp4} via
 *       {@link android.content.res.Resources#openRawResourceFd(int)} and prepares asynchronously
 *       with hardware {@code MediaCodec} offload (near-zero CPU &amp; Java heap overhead).</li>
 *   <li><b>Zero black flash</b>: Displays an instant frame-0 WebP poster until the hardware decoder
 *       renders real video frames, then hides the poster so steady-state GPU overdraw is 1.0x.</li>
 *   <li><b>Zero audio-focus theft</b>: Strictly muted ({@code 0f, 0f}) without requesting system
 *       audio focus so background music, Discord voice, and OpenAL game audio are never paused.</li>
 *   <li><b>Non-blocking teardown</b>: Releases {@link MediaPlayer} and {@link Surface} instances on
 *       a dedicated background thread so Activity transitions and Mojang splash handoff never block
 *       the main UI thread, freeing 100% of VPU/RAM resources while in-game.</li>
 * </ul>
 */
public class ZyroxLoopingVideoView extends FrameLayout implements TextureView.SurfaceTextureListener {

    /** Shared daemon thread for non-blocking MediaPlayer Binder teardown. */
    private static final ExecutorService MEDIA_RELEASER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZyroxVideoReleaser");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.List<java.lang.ref.WeakReference<ZyroxLoopingVideoView>> ACTIVE_VIDEO_VIEWS =
            new java.util.ArrayList<>();

    private static void registerVideoView(@NonNull ZyroxLoopingVideoView view) {
        synchronized (ACTIVE_VIDEO_VIEWS) {
            for (int i = ACTIVE_VIDEO_VIEWS.size() - 1; i >= 0; i--) {
                ZyroxLoopingVideoView cur = ACTIVE_VIDEO_VIEWS.get(i).get();
                if (cur == null || cur == view || cur.mReleased) {
                    ACTIVE_VIDEO_VIEWS.remove(i);
                }
            }
            ACTIVE_VIDEO_VIEWS.add(new java.lang.ref.WeakReference<>(view));
        }
    }

    private static void unregisterVideoView(@NonNull ZyroxLoopingVideoView view) {
        synchronized (ACTIVE_VIDEO_VIEWS) {
            for (int i = ACTIVE_VIDEO_VIEWS.size() - 1; i >= 0; i--) {
                ZyroxLoopingVideoView cur = ACTIVE_VIDEO_VIEWS.get(i).get();
                if (cur == null || cur == view) {
                    ACTIVE_VIDEO_VIEWS.remove(i);
                }
            }
        }
    }

    /**
     * Suspends strictly launcher-shell video backdrops ({@link #mLauncherBackdropRole}) without
     * interfering with {@code GameActivity}'s live loading screen view before its first layout pass.
     */
    public static void suspendLauncherBackdrops() {
        Runnable task = () -> {
            java.util.List<ZyroxLoopingVideoView> targets = new java.util.ArrayList<>();
            synchronized (ACTIVE_VIDEO_VIEWS) {
                for (int i = ACTIVE_VIDEO_VIEWS.size() - 1; i >= 0; i--) {
                    ZyroxLoopingVideoView cur = ACTIVE_VIDEO_VIEWS.get(i).get();
                    if (cur == null || cur.mReleased) {
                        ACTIVE_VIDEO_VIEWS.remove(i);
                    } else if (cur.mLauncherBackdropRole) {
                        targets.add(cur);
                    }
                }
            }
            for (ZyroxLoopingVideoView v : targets) {
                v.suspendForBackground(true);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    /**
     * Immediately pauses and releases hardware {@link MediaPlayer} decoders, {@link Surface}s,
     * and background poster bitmaps across all detached/hidden (or launcher-scoped) video views.
     */
    public static void suspendInactiveDecoders(final boolean includeLauncherBackdrops) {
        Runnable task = () -> {
            java.util.List<ZyroxLoopingVideoView> targets = new java.util.ArrayList<>();
            synchronized (ACTIVE_VIDEO_VIEWS) {
                for (int i = ACTIVE_VIDEO_VIEWS.size() - 1; i >= 0; i--) {
                    ZyroxLoopingVideoView cur = ACTIVE_VIDEO_VIEWS.get(i).get();
                    if (cur == null || cur.mReleased) {
                        ACTIVE_VIDEO_VIEWS.remove(i);
                    } else if ((cur.getVisibility() != View.VISIBLE)
                            || (includeLauncherBackdrops && cur.mLauncherBackdropRole)) {
                        targets.add(cur);
                    }
                }
            }
            for (ZyroxLoopingVideoView v : targets) {
                v.suspendForBackground(true);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private boolean mLauncherBackdropRole = false;

    /** Marks this video view as belonging to the launcher home shell for immediate game-start purge. */
    public void setLauncherBackdropRole(boolean launcherBackdropRole) {
        mLauncherBackdropRole = launcherBackdropRole;
    }

    private ImageView mPosterView;
    private TextureView mTextureView;
    private final Matrix mCropMatrix = new Matrix();
    private final float[] mScaleScratch = new float[2];

    @Nullable
    private SurfaceTexture mAvailableSurfaceTexture;
    @Nullable
    private Surface mRenderSurface;
    @Nullable
    private MediaPlayer mMediaPlayer;

    @RawRes
    private int mRawVideoResId = 0;
    @Nullable
    private File mCustomVideoFile = null;
    @DrawableRes
    private int mPosterResId = 0;

    private int mVideoWidth = 1920;
    private int mVideoHeight = 1080;
    private int mSessionToken = 0;
    private int mRenderedFrameCount = 0;

    private boolean mPlaybackDesired = true;
    private boolean mPrepared = false;
    private boolean mPreparing = false;
    private boolean mFirstFrameShown = false;
    private boolean mReleased = false;

    public ZyroxLoopingVideoView(@NonNull Context context) {
        this(context, null, 0);
    }

    public ZyroxLoopingVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZyroxLoopingVideoView(@NonNull Context context,
                                 @Nullable AttributeSet attrs,
                                 int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setTag(ZyroxSeamlessGifView.TAG_BLUR_ISOLATED_BACKDROP);
        setLayerType(LAYER_TYPE_NONE, null);
        setBackgroundColor(Color.parseColor("#060C18"));
        setClipChildren(true);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearRenderEffectApi31();
        }

        mPosterView = new ImageView(context);
        mPosterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mPosterView.setClickable(false);
        mPosterView.setFocusable(false);
        mPosterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(mPosterView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mTextureView = new TextureView(context);
        mTextureView.setOpaque(false);
        mTextureView.setAlpha(0f);
        mTextureView.setClickable(false);
        mTextureView.setFocusable(false);
        mTextureView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mTextureView.setSurfaceTextureListener(this);
        addView(mTextureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        registerVideoView(this);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void clearRenderEffectApi31() {
        super.setRenderEffect(null);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void setRenderEffect(@Nullable RenderEffect renderEffect) {
        // Strictly isolate live video backdrop from foreground glass blur effects.
        super.setRenderEffect(null);
    }

    /** Configures an instant frame-0 static poster image displayed prior to video decode or on pause. */
    public void setPosterResource(@DrawableRes int posterResId) {
        if (mPosterResId == posterResId) return;
        mPosterResId = posterResId;
        if (mPosterView != null) {
            if (posterResId != 0) {
                mPosterView.setImageResource(posterResId);
            } else {
                mPosterView.setImageDrawable(null);
            }
        }
    }

    /** Binds a bundled uncompressed {@code res/raw/*.mp4} video source. */
    public void setVideoResource(@RawRes int rawResId, @DrawableRes int posterResId) {
        mReleased = false;
        setPosterResource(posterResId);
        if (mRawVideoResId == rawResId && mCustomVideoFile == null && (mPrepared || mPreparing)) {
            syncPlaybackState();
            return;
        }
        mRawVideoResId = rawResId;
        mCustomVideoFile = null;
        resetPlayerOnly(true);
        syncPlaybackState();
    }

    /** Binds a user-imported custom {@code .mp4} wallpaper file with instant poster fallback. */
    public void setVideoFile(@NonNull File videoFile, @DrawableRes int fallbackPosterResId) {
        mReleased = false;
        setPosterResource(fallbackPosterResId);
        if (mCustomVideoFile != null
                && mCustomVideoFile.getAbsolutePath().equals(videoFile.getAbsolutePath())
                && (mPrepared || mPreparing)) {
            syncPlaybackState();
            return;
        }
        mCustomVideoFile = videoFile;
        mRawVideoResId = 0;
        resetPlayerOnly(true);
        syncPlaybackState();
    }

    /** Enables or disables animated video playback (when disabled, holds cleanly on the poster). */
    public void setPlaybackEnabled(boolean enabled) {
        if (mPlaybackDesired == enabled) {
            if (enabled) syncPlaybackState();
            return;
        }
        mPlaybackDesired = enabled;
        if (enabled) {
            syncPlaybackState();
        } else {
            pauseAnimation();
        }
    }

    /** Starts or resumes gapless muted hardware video playback. */
    public void resumeAnimation() {
        if (mReleased) return;
        mPlaybackDesired = true;
        if (!mFirstFrameShown && mPosterResId != 0
                && mPosterView != null && mPosterView.getDrawable() == null) {
            revealPoster();
        }
        syncPlaybackState();
    }

    /** Pauses video decoding immediately while preserving the current rendered frame on screen. */
    public void pauseAnimation() {
        mPlaybackDesired = false;
        MediaPlayer mp = mMediaPlayer;
        if (mp != null && mPrepared) {
            try {
                if (mp.isPlaying()) {
                    mp.pause();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Releases the active hardware {@link MediaPlayer} decoder AND its bound {@link Surface} so
     * backgrounded Activities hold zero VPU/GraphicBuffer memory (recreated cleanly on resume).
     */
    public void trimDecoder() {
        suspendForBackground(false);
    }

    /**
     * Strictly stops playback, releases both {@link MediaPlayer} and {@link Surface} off-thread,
     * and optionally drops the cached poster {@link android.graphics.drawable.Drawable} so
     * stopped Activities / game sessions consume zero background media RAM on 4 GB–6 GB devices.
     */
    public void suspendForBackground(boolean dropPosterBitmap) {
        mPlaybackDesired = false;
        mSessionToken++;
        MediaPlayer mp = mMediaPlayer;
        Surface surface = mRenderSurface;
        mMediaPlayer = null;
        mRenderSurface = null;
        mPrepared = false;
        mPreparing = false;
        mFirstFrameShown = false;
        mRenderedFrameCount = 0;
        if (mTextureView != null) {
            mTextureView.setAlpha(0f);
        }
        if (dropPosterBitmap) {
            if (mPosterView != null) {
                mPosterView.setImageDrawable(null);
            }
        } else {
            revealPoster();
        }
        if (mp != null || surface != null) {
            releaseMediaAsync(mp, surface);
        }
    }

    /** Permanently releases decoder, surface, and poster bitmap references. */
    public void release() {
        mReleased = true;
        mPlaybackDesired = false;
        unregisterVideoView(this);
        mSessionToken++;
        MediaPlayer mp = mMediaPlayer;
        Surface surface = mRenderSurface;
        mMediaPlayer = null;
        mRenderSurface = null;
        mAvailableSurfaceTexture = null;
        mPrepared = false;
        mPreparing = false;
        mFirstFrameShown = false;
        releaseMediaAsync(mp, surface);
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(null);
        }
        if (mPosterView != null) {
            mPosterView.setImageDrawable(null);
        }
    }

    private void resetPlayerOnly(boolean showPoster) {
        mSessionToken++;
        MediaPlayer mp = mMediaPlayer;
        mMediaPlayer = null;
        mPrepared = false;
        mPreparing = false;
        mFirstFrameShown = false;
        mRenderedFrameCount = 0;
        if (showPoster) {
            revealPoster();
        }
        if (mp != null) {
            releaseMediaAsync(mp, null);
        }
    }

    private void revealPoster() {
        if (mPosterView != null && mPosterResId != 0) {
            if (mPosterView.getDrawable() == null) {
                mPosterView.setImageResource(mPosterResId);
            }
            mPosterView.setVisibility(View.VISIBLE);
        }
        if (mTextureView != null) {
            mTextureView.setAlpha(0f);
        }
    }

    private void revealVideoFrame() {
        if (mFirstFrameShown || mReleased) return;
        mFirstFrameShown = true;
        if (mTextureView != null) {
            mTextureView.setAlpha(1f);
        }
        if (mPosterView != null) {
            // Hide underlying poster once opaque video frames are live to cut GPU overdraw to 1.0x.
            mPosterView.setVisibility(View.INVISIBLE);
        }
    }

    private void syncPlaybackState() {
        if (mReleased || !mPlaybackDesired || !isAttachedToWindow() || getVisibility() != View.VISIBLE) {
            return;
        }
        if (mRawVideoResId == 0 && (mCustomVideoFile == null || !mCustomVideoFile.isFile())) {
            revealPoster();
            return;
        }
        if (mMediaPlayer != null) {
            if (mPrepared) {
                try {
                    mMediaPlayer.setVolume(0f, 0f);
                    if (!mMediaPlayer.isPlaying()) {
                        mMediaPlayer.start();
                    }
                    if (mFirstFrameShown && mPosterView != null) {
                        mPosterView.setVisibility(View.INVISIBLE);
                        mTextureView.setAlpha(1f);
                    }
                } catch (Throwable ignored) {
                    resetPlayerOnly(true);
                }
            }
            return;
        }
        if (mPreparing) return;

        SurfaceTexture st = mAvailableSurfaceTexture;
        if (st == null && mTextureView != null && mTextureView.isAvailable()) {
            st = mTextureView.getSurfaceTexture();
            mAvailableSurfaceTexture = st;
        }
        if (st == null) {
            return;
        }
        if (mRenderSurface == null || !mRenderSurface.isValid()) {
            releaseMediaAsync(null, mRenderSurface);
            try {
                mRenderSurface = new Surface(st);
            } catch (Throwable ignored) {
                mRenderSurface = null;
                revealPoster();
                return;
            }
        }
        startPrepareAsync(mRenderSurface);
    }

    private void startPrepareAsync(@NonNull Surface surface) {
        final int token = ++mSessionToken;
        mPreparing = true;
        mPrepared = false;
        mFirstFrameShown = false;
        mRenderedFrameCount = 0;

        MediaPlayer player = new MediaPlayer();
        mMediaPlayer = player;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
            }
            if (mCustomVideoFile != null) {
                try (FileInputStream fis = new FileInputStream(mCustomVideoFile)) {
                    player.setDataSource(fis.getFD());
                }
            } else {
                AssetFileDescriptor afd = getContext().getResources().openRawResourceFd(mRawVideoResId);
                if (afd == null) {
                    resetPlayerOnly(true);
                    return;
                }
                try {
                    player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } finally {
                    try {
                        afd.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            player.setSurface(surface);
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setScreenOnWhilePlaying(false);

            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (token != mSessionToken || width <= 0 || height <= 0) return;
                mVideoWidth = width;
                mVideoHeight = height;
                applyCenterCropTransform();
            });

            player.setOnInfoListener((mp, what, extra) -> {
                if (token == mSessionToken && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    revealVideoFrame();
                }
                return false;
            });

            player.setOnPreparedListener(mp -> {
                if (token != mSessionToken || mReleased || mp != mMediaPlayer) {
                    return;
                }
                mPreparing = false;
                mPrepared = true;
                try {
                    int vw = mp.getVideoWidth();
                    int vh = mp.getVideoHeight();
                    if (vw > 0 && vh > 0) {
                        mVideoWidth = vw;
                        mVideoHeight = vh;
                    }
                    applyCenterCropTransform();
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    }
                    if (mPlaybackDesired && isAttachedToWindow() && getVisibility() == View.VISIBLE) {
                        mp.start();
                    }
                } catch (Throwable ignored) {
                    resetPlayerOnly(true);
                }
            });

            player.setOnCompletionListener(mp -> {
                // Safety net for OEM MediaCodec builds where setLooping(true) emits onCompletion
                if (token != mSessionToken || mReleased || !mPlaybackDesired || !mPrepared) return;
                try {
                    mp.seekTo(0);
                    mp.start();
                } catch (Throwable ignored) {
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                if (token == mSessionToken) {
                    resetPlayerOnly(true);
                }
                // Return true so Android never spawns a modal MediaPlayer error dialog.
                return true;
            });

            player.prepareAsync();
        } catch (Throwable ignored) {
            resetPlayerOnly(true);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            applyCenterCropTransform();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPlaybackDesired && !mReleased) {
            syncPlaybackState();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        resetPlayerOnly(true);
        Surface s = mRenderSurface;
        mRenderSurface = null;
        releaseMediaAsync(null, s);
        super.onDetachedFromWindow();
    }

    private void applyCenterCropTransform() {
        if (mTextureView == null) return;
        int viewW = mTextureView.getWidth() > 0 ? mTextureView.getWidth() : getWidth();
        int viewH = mTextureView.getHeight() > 0 ? mTextureView.getHeight() : getHeight();
        if (!computeCenterCropScales(viewW, viewH, mVideoWidth, mVideoHeight, mScaleScratch)) {
            return;
        }
        mCropMatrix.reset();
        mCropMatrix.setScale(mScaleScratch[0], mScaleScratch[1], viewW * 0.5f, viewH * 0.5f);
        mTextureView.setTransform(mCropMatrix);
    }

    /**
     * Computes distortion-free {@link TextureView} center-crop pivot scale factors
     * {@code [scaleX, scaleY] >= 1.0f} filling 100% of {@code viewW x viewH} across any phone or
     * tablet aspect ratio (16:9, 18:9, 19.5:9, 20:9, 21:9, 4:3, portrait, or split-screen).
     */
    public static boolean computeCenterCropScales(int viewW, int viewH,
                                                  int videoW, int videoH,
                                                  @NonNull float[] outScaleXY) {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0 || outScaleXY.length < 2) {
            return false;
        }
        float viewAspect = (float) viewW / (float) viewH;
        float videoAspect = (float) videoW / (float) videoH;
        // Tiny 1.004f overscan prevents sub-pixel edge hairlines on curved/cutout displays.
        final float overscan = 1.004f;
        if (videoAspect > viewAspect) {
            outScaleXY[0] = (videoAspect / viewAspect) * overscan;
            outScaleXY[1] = overscan;
        } else {
            outScaleXY[0] = overscan;
            outScaleXY[1] = (viewAspect / videoAspect) * overscan;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        mAvailableSurfaceTexture = surface;
        applyCenterCropTransform();
        syncPlaybackState();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        applyCenterCropTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        mAvailableSurfaceTexture = null;
        resetPlayerOnly(true);
        Surface s = mRenderSurface;
        mRenderSurface = null;
        releaseMediaAsync(null, s);
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        if (!mFirstFrameShown && mPrepared) {
            mRenderedFrameCount++;
            if (mRenderedFrameCount >= 1) {
                revealVideoFrame();
            }
        }
    }

    private static void releaseMediaAsync(@Nullable MediaPlayer mp, @Nullable Surface surface) {
        if (mp == null && surface == null) return;
        try {
            MEDIA_RELEASER.execute(() -> {
                if (mp != null) {
                    try {
                        mp.setOnPreparedListener(null);
                        mp.setOnErrorListener(null);
                        mp.setOnCompletionListener(null);
                        mp.setOnInfoListener(null);
                        mp.setOnVideoSizeChangedListener(null);
                        mp.setSurface(null);
                    } catch (Throwable ignored) {
                    }
                    try {
                        mp.reset();
                    } catch (Throwable ignored) {
                    }
                    try {
                        mp.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (surface != null) {
                    try {
                        surface.release();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable fallback) {
            if (mp != null) {
                try {
                    mp.release();
                } catch (Throwable ignored) {
                }
            }
            if (surface != null) {
                try {
                    surface.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
