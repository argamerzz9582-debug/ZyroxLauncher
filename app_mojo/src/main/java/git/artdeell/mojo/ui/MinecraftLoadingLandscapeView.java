package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import java.io.InputStream;

import git.artdeell.mojo.R;

/**
 * Full-bleed edge-to-edge animated cherry-blossom Minecraft loading screen view.
 *
 * <p>Plays {@code R.raw.zyrox_loading_scene} continuously at native frame timing from game launch
 * until Mojang's official loading screen renders its first live frame:
 * <ul>
 *   <li><b>Android 9+ (API 28–36):</b> Hardware-accelerated {@link AnimatedImageDrawable} decoded
 *       via {@link ImageDecoder} with {@link AnimatedImageDrawable#REPEAT_INFINITE} on RenderThread,
 *       keeping animation smooth even while JVM/Mixin/asset startup loads CPU cores.</li>
 *   <li><b>Android 5–8.1 (API 21–27):</b> Smooth bilinear center-cropped {@link Movie} loop fallback.</li>
 *   <li><b>Zero post-load cost:</b> {@link #release()} stops playback and releases all native
 *       buffers immediately once Mojang's official loading screen appears.</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public class MinecraftLoadingLandscapeView extends AppCompatImageView {

    @Nullable
    private Drawable mAnimatedDrawable;
    @Nullable
    private Movie mLegacyMovie;
    @Nullable
    private Paint mLegacyPaint;
    private long mMovieStartMs = 0L;
    private int mMovieDurationMs = 1520;
    private boolean mRunning = false;
    private boolean mReleased = false;

    public MinecraftLoadingLandscapeView(@NonNull Context context) {
        this(context, null);
    }

    public MinecraftLoadingLandscapeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MinecraftLoadingLandscapeView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setBackgroundColor(Color.BLACK);
        setScaleType(ScaleType.CENTER_CROP);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        loadSceneGif(context);
    }

    private void loadSceneGif(@NonNull Context context) {
        if (mReleased) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(
                        context.getResources(), R.raw.zyrox_loading_scene);
                Drawable decoded = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_DEFAULT);
                    decoder.setMutableRequired(false);
                });
                decoded.setFilterBitmap(true);
                mAnimatedDrawable = decoded;
                setImageDrawable(decoded);
                if (decoded instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable anim = (AnimatedImageDrawable) decoded;
                    anim.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    anim.start();
                    mRunning = true;
                }
                return;
            } catch (Throwable ignored) {
                // Fall through to legacy Movie / static first-frame fallback
            }
        }

        // Legacy API 21–27 Movie streaming decoder
        try (InputStream is = context.getResources().openRawResource(R.raw.zyrox_loading_scene)) {
            Movie movie = Movie.decodeStream(is);
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                mLegacyMovie = movie;
                int dur = movie.duration();
                mMovieDurationMs = dur > 0 ? dur : 1520;
                mLegacyPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                mRunning = true;
                mMovieStartMs = SystemClock.uptimeMillis();
                invalidate();
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to static frame decode
        }

        try (InputStream is = context.getResources().openRawResource(R.raw.zyrox_loading_scene)) {
            Bitmap fallback = BitmapFactory.decodeStream(is);
            if (fallback != null) {
                setImageBitmap(fallback);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mReleased && getVisibility() == VISIBLE) {
            resumeAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        pauseAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && !mReleased) {
            resumeAnimation();
        } else {
            pauseAnimation();
        }
    }

    /** Starts or resumes the continuous GIF loop until Mojang's splash screen appears. */
    public void resumeAnimation() {
        if (mReleased) return;
        mRunning = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && mAnimatedDrawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable anim = (AnimatedImageDrawable) mAnimatedDrawable;
            anim.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            if (!anim.isRunning()) {
                anim.start();
            }
        } else if (mLegacyMovie != null) {
            if (mMovieStartMs == 0L) {
                mMovieStartMs = SystemClock.uptimeMillis();
            }
            postInvalidateOnAnimation();
        }
    }

    /** Pauses the GIF loop when the activity goes to background. */
    public void pauseAnimation() {
        mRunning = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && mAnimatedDrawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable anim = (AnimatedImageDrawable) mAnimatedDrawable;
            if (anim.isRunning()) {
                anim.stop();
            }
        }
    }

    /** Stops animation and releases all decoded GIF resources once Mojang's loading screen appears. */
    public void release() {
        if (mReleased) return;
        mReleased = true;
        pauseAnimation();
        if (mAnimatedDrawable != null) {
            mAnimatedDrawable.setCallback(null);
            mAnimatedDrawable = null;
        }
        setImageDrawable(null);
        mLegacyMovie = null;
        mLegacyPaint = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mLegacyMovie != null && !mReleased) {
            int vw = getWidth();
            int vh = getHeight();
            int mw = mLegacyMovie.width();
            int mh = mLegacyMovie.height();
            if (vw > 0 && vh > 0 && mw > 0 && mh > 0) {
                long now = SystemClock.uptimeMillis();
                if (mMovieStartMs == 0L) mMovieStartMs = now;
                int relTime = mRunning ? (int) ((now - mMovieStartMs) % mMovieDurationMs) : 0;
                mLegacyMovie.setTime(relTime);

                float scale = Math.max((float) vw / (float) mw, (float) vh / (float) mh);
                float dx = (vw - mw * scale) * 0.5f;
                float dy = (vh - mh * scale) * 0.5f;

                int save = canvas.save();
                canvas.translate(dx, dy);
                canvas.scale(scale, scale);
                mLegacyMovie.draw(canvas, 0f, 0f, mLegacyPaint);
                canvas.restoreToCount(save);

                if (mRunning && getVisibility() == VISIBLE && isAttachedToWindow()) {
                    postInvalidateOnAnimation();
                }
                return;
            }
        }
        super.onDraw(canvas);
    }
}
