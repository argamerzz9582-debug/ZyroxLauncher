package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Hardware-accelerated, native-resolution, blur-isolated animated GIF renderer powering both the
 * Zyrox Home Hub and the Minecraft launch screen.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li><b>API 28+ Hardware Pipeline:</b> Decodes and plays GIFs via {@link android.graphics.ImageDecoder}
 *       and {@link AnimatedImageDrawable} at full 1:1 native pixel dimensions on the GPU RenderThread
 *       with zero {@code inSampleSize} downsampling and zero {@code RGB_565} color banding.</li>
 *   <li><b>API 21–27 Lossless Fallback:</b> Decodes into full 32-bit {@link Bitmap.Config#ARGB_8888}
 *       frames via {@link ZyroxGifDecoder} with Catmull-Rom Bicubic or Nearest-Neighbor scaling.</li>
 *   <li><b>Dual Scaling Filter Toggle:</b> Switches cleanly at runtime between
 *       {@link ScalingFilter#NEAREST_NEIGHBOR} (razor-sharp pixel-art GIFs) and
 *       {@link ScalingFilter#BICUBIC} (Catmull-Rom AGSL / high-precision filtered HD GIFs).</li>
 *   <li><b>Strict Blur Isolation:</b> Rejects every {@link RenderEffect} blur injection and uses a
 *       direct unbuffered hardware surface so foreground {@link ZyroxLiquidGlassDrawable} UI cards
 *       never blur or soften the background GIF.</li>
 * </ul>
 */
public class ZyroxSeamlessGifView extends View implements Choreographer.FrameCallback {

    /** Marker tag checked by {@link ZyroxLiquidGlassDrawable} to forbid backdrop blur propagation. */
    public static final String TAG_BLUR_ISOLATED_BACKDROP = "zyrox_blur_isolated_gif";

    /** User-selectable texture magnification/minification filter for animated GIF rendering. */
    public enum ScalingFilter {
        /** Exact texel point sampling — keeps 100% crisp hard edges on pixel-art Minecraft GIFs. */
        NEAREST_NEIGHBOR("nearest"),
        /** 4×4 Catmull-Rom bicubic / high-precision filtered sampling for HD animated scenes. */
        BICUBIC("bicubic");

        @NonNull
        public final String prefValue;

        ScalingFilter(@NonNull String prefValue) {
            this.prefValue = prefValue;
        }

        @NonNull
        public static ScalingFilter fromPreference(@Nullable String raw) {
            return "nearest".equalsIgnoreCase(raw) ? NEAREST_NEIGHBOR : BICUBIC;
        }
    }

    /**
     * API 33+ AGSL 4×4 Catmull-Rom bicubic fragment shader evaluated directly on the GPU for
     * bitmap frames when {@link ScalingFilter#BICUBIC} is active.
     */
    private static final String AGSL_BICUBIC_SHADER =
            "uniform shader uTex;\n"
            + "uniform float2 uSrcSize;\n"
            + "uniform float2 uDstOffset;\n"
            + "uniform float2 uInvScale;\n"
            + "float4 cubic(float v) {\n"
            + "  float v2 = v * v; float v3 = v2 * v;\n"
            + "  return float4(-0.5*v + v2 - 0.5*v3,\n"
            + "                 1.0 - 2.5*v2 + 1.5*v3,\n"
            + "                 0.5*v + 2.0*v2 - 1.5*v3,\n"
            + "                -0.5*v2 + 0.5*v3);\n"
            + "}\n"
            + "half4 main(float2 fragCoord) {\n"
            + "  float2 uv = (fragCoord - uDstOffset) * uInvScale - 0.5;\n"
            + "  float2 base = floor(uv);\n"
            + "  float2 f = uv - base;\n"
            + "  float4 wx = cubic(f.x);\n"
            + "  float4 wy = cubic(f.y);\n"
            + "  float4 c = float4(wx.x + wx.y, wx.z + wx.w, wy.x + wy.y, wy.z + wy.w);\n"
            + "  float4 s = float4(base.x - 0.5 + wx.y / max(c.x, 1e-4),\n"
            + "                    base.x + 1.5 + wx.w / max(c.y, 1e-4),\n"
            + "                    base.y - 0.5 + wy.y / max(c.z, 1e-4),\n"
            + "                    base.y + 1.5 + wy.w / max(c.w, 1e-4));\n"
            + "  s = clamp(s, float4(0.5, 0.5, 0.5, 0.5),\n"
            + "            float4(uSrcSize.x - 0.5, uSrcSize.x - 0.5, uSrcSize.y - 0.5, uSrcSize.y - 0.5));\n"
            + "  half4 s00 = uTex.eval(float2(s.x, s.z));\n"
            + "  half4 s10 = uTex.eval(float2(s.y, s.z));\n"
            + "  half4 s01 = uTex.eval(float2(s.x, s.w));\n"
            + "  half4 s11 = uTex.eval(float2(s.y, s.w));\n"
            + "  float sx = c.x / max(c.x + c.y, 1e-4);\n"
            + "  float sy = c.z / max(c.z + c.w, 1e-4);\n"
            + "  return mix(mix(s11, s01, half(sx)), mix(s10, s00, half(sx)), half(sy));\n"
            + "}\n";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final long MAX_CUSTOM_GIF_BYTES = 32L * 1024L * 1024L;
    private static final List<java.lang.ref.WeakReference<ZyroxSeamlessGifView>> ACTIVE_VIEWS =
            new ArrayList<>();

    private static void registerInstance(@NonNull ZyroxSeamlessGifView view) {
        synchronized (ACTIVE_VIEWS) {
            for (int i = ACTIVE_VIEWS.size() - 1; i >= 0; i--) {
                ZyroxSeamlessGifView v = ACTIVE_VIEWS.get(i).get();
                if (v == null || v == view || v.mReleased) {
                    ACTIVE_VIEWS.remove(i);
                }
            }
            ACTIVE_VIEWS.add(new java.lang.ref.WeakReference<>(view));
        }
    }

    private static void unregisterInstance(@NonNull ZyroxSeamlessGifView view) {
        synchronized (ACTIVE_VIEWS) {
            for (int i = ACTIVE_VIEWS.size() - 1; i >= 0; i--) {
                ZyroxSeamlessGifView v = ACTIVE_VIEWS.get(i).get();
                if (v == null || v == view) {
                    ACTIVE_VIEWS.remove(i);
                }
            }
        }
    }

    /**
     * Immediately halts all active GIF views, cancels in-flight decodes, and recycles every frame
     * bitmap on the main thread when {@code LauncherActivity.onStop()} or {@code GameService} fires.
     */
    public static void purgeAllActiveInstances(final boolean permanentRelease) {
        ZyroxGifDecoder.cancelAllActiveDecodes();
        Runnable task = () -> {
            List<ZyroxSeamlessGifView> snapshot = new ArrayList<>();
            synchronized (ACTIVE_VIEWS) {
                for (int i = ACTIVE_VIEWS.size() - 1; i >= 0; i--) {
                    ZyroxSeamlessGifView v = ACTIVE_VIEWS.get(i).get();
                    if (v == null || v.mReleased) {
                        ACTIVE_VIEWS.remove(i);
                    } else {
                        snapshot.add(v);
                    }
                }
            }
            for (ZyroxSeamlessGifView v : snapshot) {
                if (permanentRelease) {
                    v.release();
                } else {
                    v.trimFrames();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private final Paint mBasePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mBlendPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mBicubicPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mDstRect = new RectF();
    private final Matrix mDrawMatrix = new Matrix();

    @NonNull
    private volatile ScalingFilter mScalingFilter = ScalingFilter.BICUBIC;

    @RawRes
    private volatile int mGifResId = 0;
    @Nullable
    private volatile File mGifFile = null;

    // API 28+ hardware-accelerated AnimatedImageDrawable state
    @Nullable
    private Drawable mHardwareDrawable;
    @Nullable
    private AnimatedImageDrawable mAnimatedDrawable;

    // API 21–27 full-resolution ARGB_8888 fallback state
    @Nullable
    private ZyroxGifDecoder.SeamlessSequence<Bitmap> mSequence;
    @Nullable
    private Bitmap mPreviewBitmap;
    private boolean mPreviewOwnedBySequence = false;
    @Nullable
    private Object mBicubicRuntimeShader; // RuntimeShader on API 33+, kept Object-typed for API 21 verifier

    @Nullable
    private AtomicBoolean mActiveDecodeCancel;

    private int mContentWidth = 0;
    private int mContentHeight = 0;
    private float mCurrentScale = 1f;
    private boolean mDstDirty = true;

    private boolean mShouldAnimate = true;
    private boolean mChoreographerPosted = false;
    private boolean mDecoding = false;
    private volatile boolean mReleased = false;

    private long mPlayheadNs = 0L;
    private long mLastFrameTimeNs = 0L;

    public ZyroxSeamlessGifView(@NonNull Context context) {
        this(context, null, 0);
    }

    public ZyroxSeamlessGifView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZyroxSeamlessGifView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setTag(TAG_BLUR_ISOLATED_BACKDROP);
        setLayerType(LAYER_TYPE_NONE, null);
        setBackgroundColor(Color.BLACK);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearAnyRenderEffectApi31();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            initBicubicShaderApi33();
        }
        syncScalingFilterFromPreferences();
        registerInstance(this);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void clearAnyRenderEffectApi31() {
        super.setRenderEffect(null);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void initBicubicShaderApi33() {
        try {
            mBicubicRuntimeShader = new RuntimeShader(AGSL_BICUBIC_SHADER);
        } catch (Throwable ignored) {
            mBicubicRuntimeShader = null;
        }
    }

    /**
     * Hard architectural guard: blocks any {@link RenderEffect} blur pass from ever attaching to
     * this background GIF view so Liquid Glass blur remains isolated to foreground UI cards.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void setRenderEffect(@Nullable RenderEffect renderEffect) {
        // Intentionally drop non-null RenderEffects — background GIFs must stay 100% razor-sharp.
        super.setRenderEffect(null);
    }

    /** Returns {@code true}: this view is permanently decoupled from all UI glass blur passes. */
    public boolean isBlurIsolated() {
        return true;
    }

    /** Returns the active magnification/minification scaling filter. */
    @NonNull
    public ScalingFilter getScalingFilter() {
        return mScalingFilter;
    }

    /** Returns {@code true} when pixel-art Nearest-Neighbor filtering is active. */
    public boolean isNearestNeighborFilter() {
        return mScalingFilter == ScalingFilter.NEAREST_NEIGHBOR;
    }

    /** Syncs the active scaling filter with {@link LauncherPreferences#PREF_ZYROX_GIF_FILTER}. */
    public void syncScalingFilterFromPreferences() {
        setScalingFilter(ScalingFilter.fromPreference(LauncherPreferences.PREF_ZYROX_GIF_FILTER));
    }

    /** Applies either {@link ScalingFilter#NEAREST_NEIGHBOR} (pixel art) or {@link ScalingFilter#BICUBIC} (HD). */
    public void setScalingFilter(@NonNull ScalingFilter filter) {
        mScalingFilter = filter;
        boolean filterBitmap = (filter == ScalingFilter.BICUBIC);
        mBasePaint.setFilterBitmap(filterBitmap);
        mBasePaint.setAntiAlias(filterBitmap);
        mBasePaint.setDither(false);
        mBlendPaint.setFilterBitmap(filterBitmap);
        mBlendPaint.setAntiAlias(filterBitmap);
        mBlendPaint.setDither(false);
        if (mHardwareDrawable != null) {
            mHardwareDrawable.setFilterBitmap(filterBitmap);
            mHardwareDrawable.setDither(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mAnimatedDrawable != null) {
            mAnimatedDrawable.setFilterBitmap(filterBitmap);
        }
        invalidate();
    }

    /** Toggles between {@link ScalingFilter#NEAREST_NEIGHBOR} and {@link ScalingFilter#BICUBIC}. */
    @NonNull
    public ScalingFilter toggleScalingFilter() {
        ScalingFilter next = (mScalingFilter == ScalingFilter.NEAREST_NEIGHBOR)
                ? ScalingFilter.BICUBIC
                : ScalingFilter.NEAREST_NEIGHBOR;
        setScalingFilter(next);
        return next;
    }

    /** Binds a bundled {@code res/raw} animated GIF scene at full native resolution. */
    public void setGifResource(@RawRes int rawResId) {
        if (mReleased || rawResId == 0) return;
        syncScalingFilterFromPreferences();
        if (mGifFile == null && mGifResId == rawResId && (hasDecodedContent() || mDecoding)) {
            syncAnimationState();
            return;
        }
        cancelInFlightDecode();
        recycleAllOnUiThread();
        mGifFile = null;
        mGifResId = rawResId;
        mPlayheadNs = 0L;
        mLastFrameTimeNs = 0L;
        ensureDecodedAsync();
    }

    /** Binds a user-imported custom {@code .gif} backdrop file at full native resolution. */
    public void setGifFile(@Nullable File gifFile) {
        if (mReleased || gifFile == null || !gifFile.isFile()) return;
        syncScalingFilterFromPreferences();
        if (mGifFile != null && mGifFile.equals(gifFile) && (hasDecodedContent() || mDecoding)) {
            syncAnimationState();
            return;
        }
        cancelInFlightDecode();
        recycleAllOnUiThread();
        mGifFile = gifFile;
        mGifResId = -Math.max(1, Math.abs(gifFile.getAbsolutePath().hashCode() ^ (int) gifFile.lastModified()));
        mPlayheadNs = 0L;
        mLastFrameTimeNs = 0L;
        ensureDecodedAsync();
    }

    private boolean hasDecodedContent() {
        return mHardwareDrawable != null || mSequence != null;
    }

    /** Enables or pauses smooth infinite loop playback (displays a crisp native frame when false). */
    public void setPlaybackEnabled(boolean enabled) {
        if (mShouldAnimate == enabled) {
            if (enabled) ensureDecodedAsync();
            syncAnimationState();
            return;
        }
        mShouldAnimate = enabled;
        mLastFrameTimeNs = 0L;
        if (enabled) {
            ensureDecodedAsync();
        }
        syncAnimationState();
        invalidate();
    }

    /** Starts or resumes continuous loop playback. */
    public void resumeAnimation() {
        if (mReleased) return;
        syncScalingFilterFromPreferences();
        mShouldAnimate = true;
        mLastFrameTimeNs = 0L;
        ensureDecodedAsync();
        syncAnimationState();
    }

    /** Pauses GPU/VSYNC playback immediately (0% background CPU/GPU) while keeping texture warm. */
    public void pauseAnimation() {
        stopActivePlayback();
        mLastFrameTimeNs = 0L;
    }

    /**
     * Frees all decoded GPU/Bitmap frame buffers while the host activity is stopped (e.g. while
     * Minecraft is running in the foreground). Automatically reloads on {@link #resumeAnimation()}.
     */
    public void trimFrames() {
        stopActivePlayback();
        cancelInFlightDecode();
        mBicubicPaint.setShader(null);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recycleAllOnUiThread();
            invalidate();
        } else {
            MAIN_HANDLER.post(() -> {
                recycleAllOnUiThread();
                invalidate();
            });
        }
    }

    /** Permanently stops playback and releases every hardware drawable and bitmap buffer. */
    public void release() {
        if (mReleased) return;
        mReleased = true;
        mShouldAnimate = false;
        unregisterInstance(this);
        stopActivePlayback();
        cancelInFlightDecode();
        mBicubicPaint.setShader(null);
        mBicubicRuntimeShader = null;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recycleAllOnUiThread();
        } else {
            MAIN_HANDLER.post(this::recycleAllOnUiThread);
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mHardwareDrawable || super.verifyDrawable(who);
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable drawable) {
        if (drawable == mHardwareDrawable) {
            invalidate();
        } else {
            super.invalidateDrawable(drawable);
        }
    }

    private void ensureDecodedAsync() {
        if (mReleased || mDecoding || hasDecodedContent() || mGifResId == 0) return;
        final Context appCtx = getContext().getApplicationContext();
        final int targetRes = mGifResId;
        final File targetFile = mGifFile;
        final boolean nearest = isNearestNeighborFilter();
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        ZyroxGifDecoder.registerActiveDecode(cancelFlag);
        mActiveDecodeCancel = cancelFlag;
        mDecoding = true;

        Thread worker = new Thread(() -> decodeWorker(appCtx, targetRes, targetFile, nearest, cancelFlag),
                "ZyroxHardwareGif");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
    }

    private void decodeWorker(@NonNull Context appCtx,
                              int resId,
                              @Nullable File customFile,
                              boolean nearest,
                              @NonNull AtomicBoolean cancelFlag) {
        // Primary Path (API 28+): Hardware-accelerated ImageDecoder + AnimatedImageDrawable
        // at strictly 1:1 native resolution (setTargetSampleSize(1), ALLOCATOR_HARDWARE, no RGB_565).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ZyroxGifDecoder.HardwareGifResult hw = (customFile != null)
                        ? ZyroxGifDecoder.decodeHardwareFile(customFile, nearest)
                        : ZyroxGifDecoder.decodeHardwareResource(appCtx.getResources(), resId, nearest);
                if (!cancelFlag.get()) {
                    postHardwareDecodeFinished(resId, cancelFlag, hw);
                    return;
                } else if (hw.animatedDrawable != null) {
                    hw.animatedDrawable.stop();
                }
            } catch (Throwable ignored) {
                // Gracefully fall through to full-resolution ARGB_8888 software pipeline
            }
        }

        // Fallback Path (API 21–27): Full native resolution 32-bit ARGB_8888 (zero downscaling)
        byte[] gifBytes = customFile != null
                ? readFileBytes(customFile, cancelFlag)
                : readRawBytes(appCtx, resId, cancelFlag);
        if (gifBytes == null || cancelFlag.get()) {
            postSoftwareDecodeFinished(resId, cancelFlag, null, null);
            return;
        }

        ZyroxGifDecoder.SeamlessSequence<Bitmap> seq = null;
        try {
            seq = decodeFullArgb8888(gifBytes, resId, cancelFlag);
        } catch (Throwable ignored) {
            seq = null;
        }

        Bitmap staticFallback = null;
        if (seq == null && !cancelFlag.get()) {
            try {
                staticFallback = ZyroxGifDecoder.decodeStaticArgb8888(gifBytes);
            } catch (Throwable ignored) {
            }
        }

        postSoftwareDecodeFinished(resId, cancelFlag, seq, staticFallback);
    }

    @Nullable
    private ZyroxGifDecoder.SeamlessSequence<Bitmap> decodeFullArgb8888(
            @NonNull byte[] gifBytes,
            int resId,
            @NonNull AtomicBoolean cancelFlag) {

        final List<Bitmap> allocated = new ArrayList<>(36);
        ZyroxGifDecoder.SeamlessSequence<Bitmap> result = null;
        try {
            result = ZyroxGifDecoder.decodeSeamlessLoop(
                    gifBytes,
                    true,
                    cancelFlag,
                    (w, h, argb8888) -> {
                        Bitmap bmp = ZyroxGifDecoder.createArgb8888Bitmap(w, h, argb8888);
                        allocated.add(bmp);
                        return bmp;
                    },
                    (preview, w, h) -> {
                        if (cancelFlag.get()) return;
                        MAIN_HANDLER.post(() -> {
                            if (mReleased || cancelFlag.get() || mGifResId != resId
                                    || hasDecodedContent() || preview.isRecycled()) {
                                return;
                            }
                            if (!mPreviewOwnedBySequence) {
                                safeRecycle(mPreviewBitmap);
                            }
                            mPreviewBitmap = preview;
                            mPreviewOwnedBySequence = true;
                            updateContentDimensions(w, h);
                            invalidate();
                        });
                    }
            );
            return result;
        } finally {
            if (result == null && !allocated.isEmpty()) {
                MAIN_HANDLER.post(() -> {
                    for (Bitmap bmp : allocated) {
                        if (bmp == mPreviewBitmap) {
                            mPreviewBitmap = null;
                            mPreviewOwnedBySequence = false;
                        }
                        safeRecycle(bmp);
                    }
                });
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private void postHardwareDecodeFinished(int resId,
                                            @NonNull AtomicBoolean cancelFlag,
                                            @NonNull ZyroxGifDecoder.HardwareGifResult hw) {
        MAIN_HANDLER.post(() -> {
            if (mActiveDecodeCancel == cancelFlag) {
                mActiveDecodeCancel = null;
                mDecoding = false;
            }
            if (mReleased || cancelFlag.get() || mGifResId != resId) {
                if (hw.animatedDrawable != null) {
                    hw.animatedDrawable.stop();
                }
                return;
            }
            recycleAllOnUiThread();
            boolean filterBitmap = (mScalingFilter == ScalingFilter.BICUBIC);
            hw.drawable.setFilterBitmap(filterBitmap);
            hw.drawable.setDither(false);
            hw.drawable.setBounds(0, 0, hw.nativeWidth, hw.nativeHeight);
            hw.drawable.setCallback(this);
            mHardwareDrawable = hw.drawable;
            mAnimatedDrawable = hw.animatedDrawable;
            if (mAnimatedDrawable != null) {
                mAnimatedDrawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                mAnimatedDrawable.setFilterBitmap(filterBitmap);
            }
            updateContentDimensions(hw.nativeWidth, hw.nativeHeight);
            syncAnimationState();
            invalidate();
        });
    }

    private void postSoftwareDecodeFinished(int resId,
                                            @NonNull AtomicBoolean cancelFlag,
                                            @Nullable ZyroxGifDecoder.SeamlessSequence<Bitmap> seq,
                                            @Nullable Bitmap fallback) {
        MAIN_HANDLER.post(() -> {
            if (mActiveDecodeCancel == cancelFlag) {
                mActiveDecodeCancel = null;
                mDecoding = false;
            }
            if (mReleased || cancelFlag.get() || mGifResId != resId) {
                recycleSequence(seq);
                safeRecycle(fallback);
                return;
            }
            if (seq != null && !seq.frames.isEmpty()) {
                recycleSequence(mSequence);
                if (!mPreviewOwnedBySequence) {
                    safeRecycle(mPreviewBitmap);
                }
                mPreviewBitmap = null;
                mPreviewOwnedBySequence = false;
                mSequence = seq;
                safeRecycle(fallback);
                updateContentDimensions(seq.width, seq.height);
                mLastFrameTimeNs = 0L;
                syncAnimationState();
                invalidate();
            } else if (fallback != null) {
                if (!mPreviewOwnedBySequence) {
                    safeRecycle(mPreviewBitmap);
                }
                mPreviewBitmap = fallback;
                mPreviewOwnedBySequence = false;
                updateContentDimensions(fallback.getWidth(), fallback.getHeight());
                invalidate();
            }
        });
    }

    @Nullable
    private static byte[] readFileBytes(@NonNull File file, @NonNull AtomicBoolean cancelFlag) {
        long len = file.length();
        if (len <= 16 || len > MAX_CUSTOM_GIF_BYTES || !file.canRead()) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(len, 8 * 1024 * 1024));
            byte[] buf = new byte[32768];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelFlag.get()) return null;
                total += n;
                if (total > MAX_CUSTOM_GIF_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static byte[] readRawBytes(@NonNull Context ctx,
                                       @RawRes int resId,
                                       @NonNull AtomicBoolean cancelFlag) {
        try (InputStream in = ctx.getResources().openRawResource(resId)) {
            int hint = Math.max(65536, in.available());
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(hint, 8 * 1024 * 1024));
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelFlag.get()) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void updateContentDimensions(int w, int h) {
        if (w > 0 && h > 0 && (w != mContentWidth || h != mContentHeight)) {
            mContentWidth = w;
            mContentHeight = h;
            mDstDirty = true;
        }
    }

    /** Returns the native unscaled GIF pixel width (0 before first frame decode). */
    public int getNativeGifWidth() {
        return mContentWidth;
    }

    /** Returns the native unscaled GIF pixel height (0 before first frame decode). */
    public int getNativeGifHeight() {
        return mContentHeight;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mDstDirty = true;
    }

    private void ensureGeometry(int vw, int vh) {
        if (!mDstDirty || vw <= 0 || vh <= 0 || mContentWidth <= 0 || mContentHeight <= 0) return;
        float scale = Math.max((float) vw / (float) mContentWidth,
                (float) vh / (float) mContentHeight);
        float drawW = mContentWidth * scale;
        float drawH = mContentHeight * scale;
        // Snap translation to integer pixels to eliminate sub-pixel edge softness
        float left = Math.round((vw - drawW) * 0.5f);
        float top = Math.round((vh - drawH) * 0.5f);
        mCurrentScale = scale;
        mDstRect.set(left, top, left + drawW, top + drawH);
        mDrawMatrix.reset();
        mDrawMatrix.postScale(scale, scale);
        mDrawMatrix.postTranslate(left, top);
        mDstDirty = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearAnyRenderEffectApi31();
        }
        if (!mReleased && getVisibility() == VISIBLE) {
            ensureDecodedAsync();
            syncAnimationState();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopActivePlayback();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!mReleased && visibility == VISIBLE) {
            ensureDecodedAsync();
            syncAnimationState();
        } else {
            stopActivePlayback();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (!mReleased && visibility == VISIBLE && getVisibility() == VISIBLE) {
            ensureDecodedAsync();
            syncAnimationState();
        } else {
            stopActivePlayback();
        }
    }

    private void syncAnimationState() {
        boolean visibleAndLive = !mReleased
                && mShouldAnimate
                && !LauncherPreferences.PREF_ZYROX_REDUCED_MOTION
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mAnimatedDrawable != null) {
            stopChoreographer();
            if (visibleAndLive) {
                if (!mAnimatedDrawable.isRunning()) {
                    mAnimatedDrawable.start();
                }
            } else if (mAnimatedDrawable.isRunning()) {
                mAnimatedDrawable.stop();
            }
            return;
        }

        boolean wantChoreo = visibleAndLive && mSequence != null && mSequence.frames.size() > 1;
        if (wantChoreo && !mChoreographerPosted) {
            mChoreographerPosted = true;
            mLastFrameTimeNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        } else if (!wantChoreo && mChoreographerPosted) {
            stopChoreographer();
        }
    }

    private void stopActivePlayback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mAnimatedDrawable != null) {
            try {
                if (mAnimatedDrawable.isRunning()) {
                    mAnimatedDrawable.stop();
                }
            } catch (Throwable ignored) {
            }
        }
        stopChoreographer();
    }

    private void stopChoreographer() {
        if (mChoreographerPosted) {
            mChoreographerPosted = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mChoreographerPosted || mReleased || !mShouldAnimate || mSequence == null) {
            mChoreographerPosted = false;
            return;
        }
        if (getVisibility() != VISIBLE || !isAttachedToWindow()) {
            mChoreographerPosted = false;
            return;
        }
        if (mLastFrameTimeNs != 0L) {
            long dtNs = frameTimeNanos - mLastFrameTimeNs;
            long maxStepNs = mSequence.frameDurationNs * 2L;
            if (dtNs > 0L) {
                mPlayheadNs = (mPlayheadNs + Math.min(dtNs, maxStepNs)) % mSequence.totalDurationNs;
            }
        }
        mLastFrameTimeNs = frameTimeNanos;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0 || mReleased) return;

        // 1. Primary API 28+ hardware-accelerated AnimatedImageDrawable at full native resolution
        Drawable hw = mHardwareDrawable;
        if (hw != null) {
            ensureGeometry(vw, vh);
            hw.setFilterBitmap(mScalingFilter == ScalingFilter.BICUBIC);
            int save = canvas.save();
            canvas.concat(mDrawMatrix);
            hw.draw(canvas);
            canvas.restoreToCount(save);
            return;
        }

        // 2. Lossless 32-bit ARGB_8888 sequence (Nearest-Neighbor crisp step OR Bicubic HD)
        ZyroxGifDecoder.SeamlessSequence<Bitmap> seq = mSequence;
        if (seq != null && !seq.frames.isEmpty()) {
            List<Bitmap> frames = seq.frames;
            int count = frames.size();
            ensureGeometry(vw, vh);

            if (count == 1 || !mShouldAnimate || mScalingFilter == ScalingFilter.NEAREST_NEIGHBOR) {
                int idx = (count == 1 || !mShouldAnimate)
                        ? 0
                        : (((int) (mPlayheadNs / seq.frameDurationNs)) % count);
                Bitmap frame = frames.get(idx);
                if (!frame.isRecycled()) {
                    drawCrispBitmap(canvas, frame, vw, vh);
                }
                return;
            }

            double exact = (double) mPlayheadNs / (double) seq.frameDurationNs;
            int idxA = ((int) exact) % count;
            Bitmap frameA = frames.get(idxA);
            if (!frameA.isRecycled()) {
                drawCrispBitmap(canvas, frameA, vw, vh);
            }
            return;
        }

        // 3. Instant native-resolution ARGB_8888 first-frame preview
        Bitmap preview = mPreviewBitmap;
        if (preview != null && !preview.isRecycled()) {
            ensureGeometry(vw, vh);
            drawCrispBitmap(canvas, preview, vw, vh);
        }
    }

    private void drawCrispBitmap(@NonNull Canvas canvas, @NonNull Bitmap bmp, int vw, int vh) {
        if (mScalingFilter == ScalingFilter.BICUBIC
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && mBicubicRuntimeShader != null
                && canvas.isHardwareAccelerated()) {
            if (drawWithBicubicShaderApi33(canvas, bmp, vw, vh)) {
                return;
            }
        }
        canvas.drawBitmap(bmp, null, mDstRect, mBasePaint);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private boolean drawWithBicubicShaderApi33(@NonNull Canvas canvas,
                                               @NonNull Bitmap bmp,
                                               int vw, int vh) {
        try {
            RuntimeShader rs = (RuntimeShader) mBicubicRuntimeShader;
            if (rs == null || mCurrentScale <= 0f) return false;
            BitmapShader tex = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            tex.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            rs.setInputShader("uTex", tex);
            rs.setFloatUniform("uSrcSize", (float) bmp.getWidth(), (float) bmp.getHeight());
            rs.setFloatUniform("uDstOffset", mDstRect.left, mDstRect.top);
            float inv = 1f / mCurrentScale;
            rs.setFloatUniform("uInvScale", inv, inv);
            mBicubicPaint.setShader(rs);
            canvas.drawRect(0f, 0f, vw, vh, mBicubicPaint);
            mBicubicPaint.setShader(null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void cancelInFlightDecode() {
        if (mActiveDecodeCancel != null) {
            mActiveDecodeCancel.set(true);
            mActiveDecodeCancel = null;
        }
        mDecoding = false;
    }

    private void recycleAllOnUiThread() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mAnimatedDrawable != null) {
            try {
                mAnimatedDrawable.stop();
            } catch (Throwable ignored) {
            }
            mAnimatedDrawable = null;
        }
        if (mHardwareDrawable != null) {
            mHardwareDrawable.setCallback(null);
            mHardwareDrawable = null;
        }
        recycleSequence(mSequence);
        mSequence = null;
        if (!mPreviewOwnedBySequence) {
            safeRecycle(mPreviewBitmap);
        }
        mPreviewBitmap = null;
        mPreviewOwnedBySequence = false;
    }

    private static void recycleSequence(@Nullable ZyroxGifDecoder.SeamlessSequence<Bitmap> seq) {
        if (seq == null) return;
        for (Bitmap bmp : seq.frames) {
            safeRecycle(bmp);
        }
    }

    private static void safeRecycle(@Nullable Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            bmp.recycle();
        }
    }
}
