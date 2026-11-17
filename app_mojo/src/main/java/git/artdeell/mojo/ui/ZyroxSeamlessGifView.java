package git.artdeell.mojo.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-bleed edge-to-edge, VSYNC-locked, zero-buffer seamless GIF scene renderer used on both
 * the Zyrox Home Hub and the Minecraft launch screen.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li><b>Zero loop-end buffer/freeze:</b> All frames are pre-decoded once on a background thread
 *       into compact GPU-prefetched {@link Bitmap.Config#RGB_565} surfaces; runtime playback performs
 *       zero file I/O, zero LZW decoding, and zero heap allocations per frame.</li>
 *   <li><b>Invisible infinite loop:</b> {@link ZyroxGifDecoder} blends the tail and head frames with
 *       a C¹-continuous cosine dissolve, while {@link #onDraw(Canvas)} performs Hermite sub-frame
 *       interpolation anchored to {@link Choreographer} vsync timestamps for 60/90/120 Hz fluidity.</li>
 *   <li><b>Game-friendly memory lifecycle:</b> {@link #trimFrames()} frees all frame memory when
 *       the launcher moves to the background (e.g. while Minecraft runs) and transparently re-warms
 *       on return; {@link #release()} frees everything permanently once Mojang's splash renders.</li>
 * </ul>
 */
public class ZyroxSeamlessGifView extends View implements Choreographer.FrameCallback {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Paint mBasePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint mBlendPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF mDstRect = new RectF();

    @RawRes
    private volatile int mGifResId = 0;
    @Nullable
    private volatile java.io.File mGifFile = null;
    @Nullable
    private ZyroxGifDecoder.SeamlessSequence<Bitmap> mSequence;
    @Nullable
    private Bitmap mPreviewBitmap;
    private boolean mPreviewOwnedBySequence = false;
    @Nullable
    private AtomicBoolean mActiveDecodeCancel;

    private int mContentWidth = 0;
    private int mContentHeight = 0;
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
        setBackgroundColor(Color.BLACK);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Binds a bundled {@code res/raw} animated GIF scene and starts asynchronous pre-decoding. */
    public void setGifResource(@RawRes int rawResId) {
        if (mReleased || rawResId == 0) return;
        if (mGifFile == null && mGifResId == rawResId && (mSequence != null || mDecoding)) {
            syncChoreographer();
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

    /** Binds a user-imported custom {@code .gif} backdrop file (up to 24 MB) for seamless looping. */
    public void setGifFile(@Nullable java.io.File gifFile) {
        if (mReleased || gifFile == null || !gifFile.isFile()) return;
        if (mGifFile != null && mGifFile.equals(gifFile)
                && (mSequence != null || mDecoding)) {
            syncChoreographer();
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

    /** Enables or pauses smooth infinite loop playback (when false, displays a crisp static frame). */
    public void setPlaybackEnabled(boolean enabled) {
        if (mShouldAnimate == enabled) {
            if (enabled) ensureDecodedAsync();
            return;
        }
        mShouldAnimate = enabled;
        mLastFrameTimeNs = 0L;
        if (enabled) {
            ensureDecodedAsync();
        }
        syncChoreographer();
        invalidate();
    }

    /** Starts or resumes continuous loop playback. */
    public void resumeAnimation() {
        if (mReleased) return;
        mShouldAnimate = true;
        mLastFrameTimeNs = 0L;
        ensureDecodedAsync();
        syncChoreographer();
    }

    /** Pauses vsync callbacks immediately (0% background CPU/GPU) while keeping frames warm. */
    public void pauseAnimation() {
        stopChoreographer();
        mLastFrameTimeNs = 0L;
    }

    /**
     * Frees all cached frame bitmaps while the host activity is stopped (e.g. while Minecraft is
     * running in foreground). Automatically reloads when {@link #resumeAnimation()} is next called.
     */
    public void trimFrames() {
        stopChoreographer();
        cancelInFlightDecode();
        recycleAllOnUiThread();
    }

    /** Permanently stops playback and frees every native bitmap buffer. */
    public void release() {
        if (mReleased) return;
        mReleased = true;
        mShouldAnimate = false;
        stopChoreographer();
        cancelInFlightDecode();
        recycleAllOnUiThread();
    }

    private void ensureDecodedAsync() {
        if (mReleased || mDecoding || mSequence != null || mGifResId == 0) return;
        final Context appCtx = getContext().getApplicationContext();
        final int targetRes = mGifResId;
        final java.io.File targetFile = mGifFile;
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        mActiveDecodeCancel = cancelFlag;
        mDecoding = true;

        Thread worker = new Thread(() -> decodeWorker(appCtx, targetRes, targetFile, cancelFlag),
                "ZyroxSeamlessGif");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
    }

    private void decodeWorker(@NonNull Context appCtx,
                              int resId,
                              @Nullable java.io.File customFile,
                              @NonNull AtomicBoolean cancelFlag) {
        byte[] gifBytes = customFile != null
                ? readFileBytes(customFile, cancelFlag)
                : readRawBytes(appCtx, resId, cancelFlag);
        if (gifBytes == null || cancelFlag.get()) {
            postDecodeFinished(resId, cancelFlag, null, null);
            return;
        }

        int sampleStep = chooseSafeSampleStep(appCtx, gifBytes);
        ZyroxGifDecoder.SeamlessSequence<Bitmap> seq = null;
        try {
            seq = decodeWithStep(gifBytes, sampleStep, resId, cancelFlag);
        } catch (OutOfMemoryError oom) {
            if (!cancelFlag.get() && sampleStep < 2) {
                try {
                    seq = decodeWithStep(gifBytes, 2, resId, cancelFlag);
                } catch (Throwable ignored) {
                    seq = null;
                }
            }
        } catch (Throwable ignored) {
            seq = null;
        }

        Bitmap staticFallback = null;
        if (seq == null && !cancelFlag.get()) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                staticFallback = BitmapFactory.decodeByteArray(gifBytes, 0, gifBytes.length, opts);
                if (staticFallback != null) {
                    staticFallback.prepareToDraw();
                }
            } catch (Throwable ignored) {
            }
        }

        // Release compressed byte buffer before posting back to UI thread
        //noinspection UnusedAssignment
        gifBytes = null;
        postDecodeFinished(resId, cancelFlag, seq, staticFallback);
    }

    @Nullable
    private ZyroxGifDecoder.SeamlessSequence<Bitmap> decodeWithStep(
            @NonNull byte[] gifBytes,
            int sampleStep,
            @RawRes int resId,
            @NonNull AtomicBoolean cancelFlag) {

        final List<Bitmap> allocated = new ArrayList<>(36);
        ZyroxGifDecoder.SeamlessSequence<Bitmap> result = null;
        try {
            result = ZyroxGifDecoder.decodeSeamlessLoop(
                    gifBytes,
                    sampleStep,
                    true,
                    cancelFlag,
                    (w, h, rgb565) -> {
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                        allocated.add(bmp);
                        bmp.copyPixelsFromBuffer(ShortBuffer.wrap(rgb565));
                        bmp.setHasAlpha(false);
                        bmp.prepareToDraw();
                        return bmp;
                    },
                    (preview, w, h) -> {
                        if (cancelFlag.get()) {
                            return;
                        }
                        MAIN_HANDLER.post(() -> {
                            if (mReleased || cancelFlag.get() || mGifResId != resId
                                    || mSequence != null || preview.isRecycled()) {
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

    private void postDecodeFinished(@RawRes int resId,
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
                syncChoreographer();
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

    private static int chooseSafeSampleStep(@NonNull Context ctx, @NonNull byte[] gifBytes) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice()) {
                return 2;
            }
            ZyroxGifDecoder.GifMetadata meta = ZyroxGifDecoder.inspectMetadata(gifBytes);
            if (meta != null) {
                int cycleFrames = meta.frameCount - ZyroxGifDecoder.computeOverlapCount(meta.frameCount);
                long estimatedBytes = (long) meta.width * meta.height * 2L * Math.max(1, cycleFrames);
                Runtime rt = Runtime.getRuntime();
                long maxHeap = rt.maxMemory();
                if (estimatedBytes > 26L * 1024L * 1024L
                        || (maxHeap > 0 && maxHeap < 160L * 1024L * 1024L && estimatedBytes > 16L * 1024L * 1024L)) {
                    return 2;
                }
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static final long MAX_CUSTOM_GIF_BYTES = 24L * 1024L * 1024L;

    @Nullable
    private static byte[] readFileBytes(@NonNull java.io.File file,
                                        @NonNull AtomicBoolean cancelFlag) {
        long len = file.length();
        if (len <= 16 || len > MAX_CUSTOM_GIF_BYTES || !file.canRead()) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mDstDirty = true;
    }

    private void ensureDstRect(int vw, int vh) {
        if (!mDstDirty || vw <= 0 || vh <= 0 || mContentWidth <= 0 || mContentHeight <= 0) return;
        // Full-bleed CENTER_CROP with 1px edge guard to prevent sub-pixel letterboxing on cutouts
        float scale = Math.max((float) (vw + 2) / (float) mContentWidth,
                (float) (vh + 2) / (float) mContentHeight);
        float drawW = mContentWidth * scale;
        float drawH = mContentHeight * scale;
        float left = (vw - drawW) * 0.5f;
        float top = (vh - drawH) * 0.5f;
        mDstRect.set(left, top, left + drawW, top + drawH);
        mDstDirty = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mReleased && getVisibility() == VISIBLE) {
            ensureDecodedAsync();
            syncChoreographer();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopChoreographer();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!mReleased && visibility == VISIBLE) {
            ensureDecodedAsync();
            syncChoreographer();
        } else {
            stopChoreographer();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (!mReleased && visibility == VISIBLE && getVisibility() == VISIBLE) {
            ensureDecodedAsync();
            syncChoreographer();
        } else {
            stopChoreographer();
        }
    }

    private void syncChoreographer() {
        boolean wantRun = !mReleased
                && mShouldAnimate
                && mSequence != null
                && mSequence.frames.size() > 1
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE;
        if (wantRun && !mChoreographerPosted) {
            mChoreographerPosted = true;
            mLastFrameTimeNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        } else if (!wantRun && mChoreographerPosted) {
            stopChoreographer();
        }
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
            // Clamp single-vsync step so transient JVM startup CPU spikes never cause a visible jump
            long maxStepNs = mSequence.frameDurationNs * 2L;
            if (dtNs > 0L) {
                long step = Math.min(dtNs, maxStepNs);
                mPlayheadNs += step;
                if (mPlayheadNs >= mSequence.totalDurationNs) {
                    mPlayheadNs %= mSequence.totalDurationNs;
                }
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

        ZyroxGifDecoder.SeamlessSequence<Bitmap> seq = mSequence;
        if (seq != null && !seq.frames.isEmpty()) {
            List<Bitmap> frames = seq.frames;
            int count = frames.size();
            ensureDstRect(vw, vh);

            if (count == 1 || !mShouldAnimate) {
                Bitmap first = frames.get(0);
                if (!first.isRecycled()) {
                    canvas.drawBitmap(first, null, mDstRect, mBasePaint);
                }
                return;
            }

            double exact = (double) mPlayheadNs / (double) seq.frameDurationNs;
            int idxA = ((int) exact) % count;
            int idxB = (idxA + 1) % count;
            float frac = (float) (exact - Math.floor(exact));
            // Hermite smoothstep eliminates 20/25fps -> 60/120Hz pulldown judder with zero allocations
            int blendAlpha = Math.round(255f * frac * frac * (3f - 2f * frac));

            Bitmap frameA = frames.get(idxA);
            if (!frameA.isRecycled()) {
                canvas.drawBitmap(frameA, null, mDstRect, mBasePaint);
            }
            if (blendAlpha >= 6) {
                Bitmap frameB = frames.get(idxB);
                if (!frameB.isRecycled()) {
                    mBlendPaint.setAlpha(blendAlpha);
                    canvas.drawBitmap(frameB, null, mDstRect, mBlendPaint);
                }
            }
            return;
        }

        Bitmap preview = mPreviewBitmap;
        if (preview != null && !preview.isRecycled()) {
            ensureDstRect(vw, vh);
            canvas.drawBitmap(preview, null, mDstRect, mBasePaint);
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
