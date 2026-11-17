package git.artdeell.mojo.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Hardware-accelerated, zero-per-frame-allocation Zyrox Liquid Glass button drawable.
 *
 * <p>Implements the exact optical layer stack of the Zyrox Liquid Glass control design system:
 * <ol>
 *   <li>Soft outer frosted atmospheric halo skirt (cool neutral silver-ice normal, cool refractive
 *       ice-blue when pressed/activated)</li>
 *   <li>Translucent frosted glass body vertical gradient</li>
 *   <li>Crisp crystal-white outer specular rim ({@code #F0FFFFFF} → {@code #DCECF2F8})</li>
 *   <li>Concentric inner refractive bezel ring + vertical left/right micro-gleams</li>
 *   <li>Top pill-rounded specular light-bar capsule ({@code #82FFFFFF})</li>
 *   <li>Bottom centered pill-rounded caustic reflection capsule ({@code #4AFFFFFF})</li>
 * </ol>
 *
 * <p>Automatically simplifies to a fast single-pass frosted border in Low Visual Effects mode,
 * supports keyboard/controller focus rings, and blends custom user button tints cleanly.
 */
public class ZyroxLiquidGlassDrawable extends Drawable {
    /** Default legacy control background color treated as standard neutral Liquid Glass. */
    public static final int LEGACY_DEFAULT_CONTROL_BG = 0x4D000000;

    // Exact sampled RGBA tokens from Zyrox Liquid Glass optical specification
    public static final int NORMAL_HALO_TOP = 0x52E6EDF3;
    public static final int NORMAL_HALO_BOTTOM = 0x66B1BAC5;
    public static final int PRESSED_HALO_TOP = 0x78C9D7E4;
    public static final int PRESSED_HALO_BOTTOM = 0x86ADB9CA;

    public static final int NORMAL_BODY_TOP = 0x92B1B8C2;
    public static final int NORMAL_BODY_MID = 0x8CAAB1BB;
    public static final int NORMAL_BODY_BOTTOM = 0x92A4ACB7;

    public static final int PRESSED_BODY_TOP = 0x9EB0BCCB;
    public static final int PRESSED_BODY_MID = 0x9AA9B6C6;
    public static final int PRESSED_BODY_BOTTOM = 0x9EA6B3C4;

    public static final int OUTER_RIM_TOP = 0xF2FFFFFF;
    public static final int OUTER_RIM_MID = 0xE3F6F9FC;
    public static final int OUTER_RIM_BOTTOM = 0xE8FBFDFF;

    public static final int INNER_RIM_TOP = 0x74F4F7FA;
    public static final int INNER_RIM_BOTTOM = 0x52CBD3DC;

    public static final int TOP_SPECULAR_BAR = 0x82FFFFFF;
    public static final int TOP_SPECULAR_BAR_PRESSED = 0x96FFFFFF;
    public static final int BOTTOM_CAUSTIC_BAR = 0x48FFFFFF;
    public static final int BOTTOM_CAUSTIC_BAR_PRESSED = 0x5CFFFFFF;
    public static final int SIDE_MICRO_GLEAM = 0x62FFFFFF;
    public static final int GLYPH_SNOW_WHITE = 0xFFFAFCFF;
    public static final int GLYPH_SHADOW_COLOR = 0x621E2733;

    private final float mDensity;

    // Reusable paints & rects — zero allocations inside draw()
    private final Paint mHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOuterRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTopBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBottomBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGleamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mHaloRect = new RectF();
    private final RectF mBodyRect = new RectF();
    private final RectF mInnerRect = new RectF();
    private final RectF mTopBarRect = new RectF();
    private final RectF mBottomBarRect = new RectF();
    private final RectF mLeftGleamRect = new RectF();
    private final RectF mRightGleamRect = new RectF();

    // State & configuration
    private boolean mPressed;
    private boolean mActivated;
    private boolean mFocused;
    private boolean mDisabled;
    private int mMasterAlpha = 255;
    private float mCornerRadiusPercent = 0f; // 0 => signature 24% squircle radius
    private int mCustomBgColor = LEGACY_DEFAULT_CONTROL_BG;
    private int mCustomStrokeColor = Color.WHITE;
    private float mCustomStrokeDp = 0f;

    // Cached geometry metrics
    private float mHaloRadius;
    private float mBodyRadius;
    private float mInnerRadius;
    private float mTopBarRadius;
    private float mBottomBarRadius;
    private boolean mGeometryDirty = true;

    public ZyroxLiquidGlassDrawable(float density) {
        mDensity = density > 0f ? density : 2.75f;

        mHaloPaint.setStyle(Paint.Style.STROKE);
        mBodyPaint.setStyle(Paint.Style.FILL);
        mOuterRimPaint.setStyle(Paint.Style.STROKE);
        mInnerRimPaint.setStyle(Paint.Style.STROKE);
        mTopBarPaint.setStyle(Paint.Style.FILL);
        mBottomBarPaint.setStyle(Paint.Style.FILL);
        mGleamPaint.setStyle(Paint.Style.FILL);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setColor(0xFFFFFFFF);
        mFocusPaint.setStrokeWidth(2.1f * mDensity);
    }

    /**
     * Updates control appearance options while preserving Liquid Glass optical geometry.
     */
    public void configureControlStyle(int bgColor, int strokeColor, float strokeWidthDp, float cornerRadiusPercent) {
        if (mCustomBgColor != bgColor
                || mCustomStrokeColor != strokeColor
                || Float.compare(mCustomStrokeDp, strokeWidthDp) != 0
                || Float.compare(mCornerRadiusPercent, cornerRadiusPercent) != 0) {
            mCustomBgColor = bgColor;
            mCustomStrokeColor = strokeColor;
            mCustomStrokeDp = Math.max(0f, strokeWidthDp);
            mCornerRadiusPercent = Math.max(0f, Math.min(100f, cornerRadiusPercent));
            mGeometryDirty = true;
            invalidateSelf();
        }
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        boolean pressed = false;
        boolean activated = false;
        boolean focused = false;
        boolean enabled = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) pressed = true;
            else if (s == android.R.attr.state_activated || s == android.R.attr.state_selected
                    || s == android.R.attr.state_checked) activated = true;
            else if (s == android.R.attr.state_focused) focused = true;
            else if (s == android.R.attr.state_enabled) enabled = true;
        }
        boolean disabled = state.length > 0 && !enabled;
        if (pressed != mPressed || activated != mActivated || focused != mFocused || disabled != mDisabled) {
            mPressed = pressed;
            mActivated = activated;
            mFocused = focused;
            mDisabled = disabled;
            mGeometryDirty = true;
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        mGeometryDirty = true;
    }

    private void rebuildGeometry(@NonNull Rect bounds) {
        mGeometryDirty = false;
        float w = bounds.width();
        float h = bounds.height();
        if (w <= 2f || h <= 2f) return;

        float minSide = Math.min(w, h);
        boolean activeCool = mPressed || mActivated;
        boolean isWide = (w / Math.max(1f, h)) >= 1.45f;

        // Outer halo stroke sits strictly inside View bounds so exact touch hitboxes stay unchanged
        float haloStroke = clamp(minSide * 0.048f, 1.2f * mDensity, 2.8f * mDensity);
        float bodyInset = clamp(minSide * 0.036f, 1.1f * mDensity, 2.4f * mDensity);
        float outerStroke = mCustomStrokeDp > 0f
                ? clamp(mCustomStrokeDp * mDensity, 1.1f * mDensity, 3.2f * mDensity)
                : clamp(minSide * 0.032f, 1.25f * mDensity, 1.95f * mDensity);
        float innerGap = clamp(minSide * 0.068f, 2.2f * mDensity, 4.2f * mDensity);
        float innerStroke = clamp(minSide * 0.020f, 0.85f * mDensity, 1.25f * mDensity);

        mBodyRect.set(
                bounds.left + bodyInset,
                bounds.top + bodyInset,
                bounds.right - bodyInset,
                bounds.bottom - bodyInset
        );
        mHaloRect.set(
                bounds.left + haloStroke * 0.5f,
                bounds.top + haloStroke * 0.5f,
                bounds.right - haloStroke * 0.5f,
                bounds.bottom - haloStroke * 0.5f
        );
        mInnerRect.set(
                mBodyRect.left + innerGap,
                mBodyRect.top + innerGap,
                mBodyRect.right - innerGap,
                mBodyRect.bottom - innerGap
        );

        float bodyMin = Math.min(mBodyRect.width(), mBodyRect.height());
        if (mCornerRadiusPercent > 0.5f) {
            mBodyRadius = (bodyMin * 0.5f) * (mCornerRadiusPercent / 100f);
        } else {
            // Exact Zyrox Liquid Glass rounded-squircle curvature (~27% of shorter edge)
            mBodyRadius = bodyMin * 0.275f;
        }
        mBodyRadius = Math.min(mBodyRadius, bodyMin * 0.5f);
        mHaloRadius = Math.min(Math.min(mHaloRect.width(), mHaloRect.height()) * 0.5f,
                mBodyRadius + bodyInset * 0.65f);
        mInnerRadius = Math.max(2f, mBodyRadius - innerGap * 0.82f);

        // Top specular light-bar capsule (matches y=31..36 on 190px HTML controls)
        float bodyW = mBodyRect.width();
        float bodyH = mBodyRect.height();
        float topBarWidth = bodyW * (isWide ? 0.78f : 0.60f);
        float topBarHeight = clamp(bodyH * 0.052f, 1.5f * mDensity, 3.4f * mDensity);
        float topBarTop = mInnerRect.top + clamp(bodyH * 0.032f, 1.0f * mDensity, 2.8f * mDensity);
        float cx = mBodyRect.centerX();
        mTopBarRect.set(
                cx - topBarWidth * 0.5f,
                topBarTop,
                cx + topBarWidth * 0.5f,
                topBarTop + topBarHeight
        );
        mTopBarRadius = topBarHeight * 0.5f;

        // Bottom centered caustic reflection capsule (matches y=155..159 on 190px HTML controls)
        float bottomBarWidth = bodyW * (isWide ? 0.56f : 0.34f);
        float bottomBarHeight = clamp(bodyH * 0.044f, 1.3f * mDensity, 2.8f * mDensity);
        float bottomBarBottom = mInnerRect.bottom - clamp(bodyH * 0.030f, 0.9f * mDensity, 2.4f * mDensity);
        mBottomBarRect.set(
                cx - bottomBarWidth * 0.5f,
                bottomBarBottom - bottomBarHeight,
                cx + bottomBarWidth * 0.5f,
                bottomBarBottom
        );
        mBottomBarRadius = bottomBarHeight * 0.5f;

        // Left & right vertical refractive micro-gleams
        float gleamW = clamp(mDensity * 1.15f, 1f, 2.4f);
        float gleamH = Math.max(0f, mInnerRect.height() - mInnerRadius * 1.65f);
        float cy = mBodyRect.centerY();
        mLeftGleamRect.set(
                mInnerRect.left + gleamW * 0.6f,
                cy - gleamH * 0.5f,
                mInnerRect.left + gleamW * 1.6f,
                cy + gleamH * 0.5f
        );
        mRightGleamRect.set(
                mInnerRect.right - gleamW * 1.6f,
                cy - gleamH * 0.42f,
                mInnerRect.right - gleamW * 0.6f,
                cy + gleamH * 0.42f
        );

        // Configure stroke widths & cached shaders
        mHaloPaint.setStrokeWidth(haloStroke);
        mHaloPaint.setShader(new LinearGradient(
                0f, mHaloRect.top, 0f, mHaloRect.bottom,
                activeCool ? PRESSED_HALO_TOP : NORMAL_HALO_TOP,
                activeCool ? PRESSED_HALO_BOTTOM : NORMAL_HALO_BOTTOM,
                Shader.TileMode.CLAMP
        ));

        int bodyTop;
        int bodyMid;
        int bodyBot;
        if (mDisabled) {
            bodyTop = 0x45929AA6;
            bodyMid = 0x3E87909C;
            bodyBot = 0x427D8692;
        } else if (hasCustomUserTint()) {
            int base = activeCool ? blendArgb(mCustomBgColor, PRESSED_BODY_TOP, 0.42f)
                    : blendArgb(mCustomBgColor, NORMAL_BODY_TOP, 0.35f);
            bodyTop = brighten(base, 1.08f);
            bodyMid = base;
            bodyBot = brighten(base, 0.94f);
        } else if (activeCool) {
            bodyTop = PRESSED_BODY_TOP;
            bodyMid = PRESSED_BODY_MID;
            bodyBot = PRESSED_BODY_BOTTOM;
        } else {
            bodyTop = NORMAL_BODY_TOP;
            bodyMid = NORMAL_BODY_MID;
            bodyBot = NORMAL_BODY_BOTTOM;
        }

        mBodyPaint.setShader(new LinearGradient(
                0f, mBodyRect.top, 0f, mBodyRect.bottom,
                new int[]{scaleAlpha(bodyTop), scaleAlpha(bodyMid), scaleAlpha(bodyBot)},
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP
        ));

        int rimTop = mDisabled ? 0x6EFFFFFF
                : (mCustomStrokeDp > 0f && mCustomStrokeColor != Color.WHITE
                ? blendArgb(mCustomStrokeColor, OUTER_RIM_TOP, 0.55f) : OUTER_RIM_TOP);
        int rimMid = mDisabled ? 0x54E6ECF2 : (activeCool ? 0xF0F4F9FF : OUTER_RIM_MID);
        int rimBot = mDisabled ? 0x62EEF3F8 : (activeCool ? 0xF5FFFFFF : OUTER_RIM_BOTTOM);

        mOuterRimPaint.setStrokeWidth(outerStroke);
        mOuterRimPaint.setShader(new LinearGradient(
                0f, mBodyRect.top, 0f, mBodyRect.bottom,
                new int[]{scaleAlpha(rimTop), scaleAlpha(rimMid), scaleAlpha(rimBot)},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));

        mInnerRimPaint.setStrokeWidth(innerStroke);
        mInnerRimPaint.setShader(new LinearGradient(
                0f, mInnerRect.top, 0f, mInnerRect.bottom,
                scaleAlpha(activeCool ? 0x8EF7FAFF : INNER_RIM_TOP),
                scaleAlpha(activeCool ? 0x68D2DFEE : INNER_RIM_BOTTOM),
                Shader.TileMode.CLAMP
        ));

        mTopBarPaint.setShader(new LinearGradient(
                mTopBarRect.left, 0f, mTopBarRect.right, 0f,
                new int[]{
                        scaleAlpha(0x18FFFFFF),
                        scaleAlpha(activeCool ? TOP_SPECULAR_BAR_PRESSED : TOP_SPECULAR_BAR),
                        scaleAlpha(activeCool ? TOP_SPECULAR_BAR_PRESSED : TOP_SPECULAR_BAR),
                        scaleAlpha(0x18FFFFFF)
                },
                new float[]{0f, 0.12f, 0.88f, 1f},
                Shader.TileMode.CLAMP
        ));

        mBottomBarPaint.setShader(new LinearGradient(
                mBottomBarRect.left, 0f, mBottomBarRect.right, 0f,
                new int[]{
                        scaleAlpha(0x10FFFFFF),
                        scaleAlpha(activeCool ? BOTTOM_CAUSTIC_BAR_PRESSED : BOTTOM_CAUSTIC_BAR),
                        scaleAlpha(activeCool ? BOTTOM_CAUSTIC_BAR_PRESSED : BOTTOM_CAUSTIC_BAR),
                        scaleAlpha(0x10FFFFFF)
                },
                new float[]{0f, 0.16f, 0.84f, 1f},
                Shader.TileMode.CLAMP
        ));

        mGleamPaint.setColor(scaleAlpha(activeCool ? 0x7AFFFFFF : SIDE_MICRO_GLEAM));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || mMasterAlpha <= 0) return;
        if (mGeometryDirty) {
            rebuildGeometry(b);
        }

        boolean lowEffects = LauncherPreferences.PREF_ZYROX_LOW_EFFECTS;

        // 1. Soft outer frosted halo skirt (skipped in low-effects performance mode)
        if (!lowEffects && !mDisabled) {
            canvas.drawRoundRect(mHaloRect, mHaloRadius, mHaloRadius, mHaloPaint);
        }

        // 2. Translucent frosted liquid-glass body fill
        canvas.drawRoundRect(mBodyRect, mBodyRadius, mBodyRadius, mBodyPaint);

        if (!lowEffects && !mDisabled && mInnerRect.width() > 8f * mDensity && mInnerRect.height() > 8f * mDensity) {
            // 3. Concentric inner refractive bezel ring
            canvas.drawRoundRect(mInnerRect, mInnerRadius, mInnerRadius, mInnerRimPaint);

            // 4. Vertical side refractive micro-gleams
            if (mLeftGleamRect.height() > 3f * mDensity) {
                float gr = mLeftGleamRect.width() * 0.5f;
                canvas.drawRoundRect(mLeftGleamRect, gr, gr, mGleamPaint);
                canvas.drawRoundRect(mRightGleamRect, gr, gr, mGleamPaint);
            }

            // 5. Signature top specular light-bar capsule & bottom caustic reflection bar
            if (mTopBarRect.width() > 8f * mDensity) {
                canvas.drawRoundRect(mTopBarRect, mTopBarRadius, mTopBarRadius, mTopBarPaint);
            }
            if (mBottomBarRect.width() > 6f * mDensity) {
                canvas.drawRoundRect(mBottomBarRect, mBottomBarRadius, mBottomBarRadius, mBottomBarPaint);
            }
        }

        // 6. Crisp crystal-white outer specular rim
        canvas.drawRoundRect(mBodyRect, mBodyRadius, mBodyRadius, mOuterRimPaint);

        // 7. Accessibility focus ring for gamepad / keyboard navigation
        if (mFocused) {
            canvas.drawRoundRect(mHaloRect, mHaloRadius, mHaloRadius, mFocusPaint);
        }
    }

    private boolean hasCustomUserTint() {
        return mCustomBgColor != LEGACY_DEFAULT_CONTROL_BG
                && mCustomBgColor != 0
                && Color.alpha(mCustomBgColor) > 0;
    }

    private int scaleAlpha(int argb) {
        if (mMasterAlpha >= 255) return argb;
        int a = (Color.alpha(argb) * mMasterAlpha) / 255;
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static int blendArgb(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        int a = Math.round(Color.alpha(c1) * inv + Color.alpha(c2) * ratio);
        int r = Math.round(Color.red(c1) * inv + Color.red(c2) * ratio);
        int g = Math.round(Color.green(c1) * inv + Color.green(c2) * ratio);
        int b = Math.round(Color.blue(c1) * inv + Color.blue(c2) * ratio);
        return Color.argb(a, r, g, b);
    }

    private static int brighten(int argb, float factor) {
        int a = Color.alpha(argb);
        int r = Math.min(255, Math.round(Color.red(argb) * factor));
        int g = Math.min(255, Math.round(Color.green(argb) * factor));
        int b = Math.min(255, Math.round(Color.blue(argb) * factor));
        return Color.argb(a, r, g, b);
    }

    @Override
    public void setAlpha(int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        if (mMasterAlpha != clamped) {
            mMasterAlpha = clamped;
            mGeometryDirty = true;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mMasterAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mBodyPaint.setColorFilter(colorFilter);
        mOuterRimPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
