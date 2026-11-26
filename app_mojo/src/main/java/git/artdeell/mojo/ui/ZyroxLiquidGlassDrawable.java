package git.artdeell.mojo.ui;

import git.artdeell.mojo.utils.SdkLevel;
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
 * Hardware-accelerated, zero-per-frame-allocation Zyrox Ultra-Clear Liquid Glass drawable.
 *
 * <p>Engineered for maximum screen visibility and minimal visual obstruction over live Minecraft
 * gameplay and animated backdrops:
 * <ol>
 *   <li>Hairline optical contact contour ({@code #26050A14}) so crystal edges stay crisp over
 *       bright skies, snow, or sunlit foliage without darkening the button interior</li>
 *   <li>Ultra-sheer refractive water-glass lens fill (~93–96% transparent in the center)</li>
 *   <li>Upper convex liquid-dome skylight sheen fading smoothly to pure transparency</li>
 *   <li>135° diagonal diamond specular rim (bright incident top-left &amp; exit bottom-right glints
 *       with airy vanishing cross-edges)</li>
 *   <li>Delicate concentric inner meniscus ring + razor-fine top specular filament &amp; bottom
 *       liquid-ice caustic reflection</li>
 *   <li>Luminous translucent ice-crystal bloom on press and glacier-prism edge tint when toggled</li>
 * </ol>
 */
public class ZyroxLiquidGlassDrawable extends Drawable {
    /** Default legacy control background color treated as standard ultra-clear Liquid Glass. */
    public static final int LEGACY_DEFAULT_CONTROL_BG = 0x4D000000;

    /** Phase 3 High-Contrast Liquid Glass dark base scrim ({@code #660B0F17}) for WCAG readability. */
    public static final int HIGH_CONTRAST_SCRIM = 0x660B0F17;
    /** Phase 3 Specular border top highlight ({@code #4DFFFFFF}). */
    public static final int SPECULAR_BORDER_TOP = 0x4DFFFFFF;
    /** Phase 3 Specular border bottom fade ({@code #0DFFFFFF}). */
    public static final int SPECULAR_BORDER_BOTTOM = 0x0DFFFFFF;
    /** Minimum device RAM (MB) required to enable Android 12+ hardware {@code RenderEffect} blur. */
    public static final int MIN_HARDWARE_BLUR_RAM_MB = 5500;

    /** Adaptive GPU blur tier selected automatically per Android API, RAM, and GPU vendor. */
    public enum GlassBlurTier {
        /** Android 12+ (API 31+) mid/high-tier Adreno/Xclipse GPU: real-time RenderEffect blur. */
        HARDWARE_RENDER_EFFECT,
        /** Android 11 or low-RAM / Mali / PowerVR device: zero-overhead frosted 2-layer gradient. */
        FROSTED_GRADIENT_FALLBACK
    }

    // Ultra-sheer Starlight Sapphire-Crystal optical Liquid Glass tokens (~94% clear, crisp edges)
    public static final int NORMAL_HALO_TOP = 0x2409101E;
    public static final int NORMAL_HALO_BOTTOM = 0x36070C18;
    public static final int PRESSED_HALO_TOP = 0x4CB8D8FF;
    public static final int PRESSED_HALO_BOTTOM = 0x3C7CB0FF;

    public static final int NORMAL_BODY_TOP = 0x1EF4F9FF;
    public static final int NORMAL_BODY_MID = 0x09E8F2FF;
    public static final int NORMAL_BODY_BOTTOM = 0x16D6E8FF;

    public static final int PRESSED_BODY_TOP = 0x40E2F0FF;
    public static final int PRESSED_BODY_MID = 0x2488B8FF;
    public static final int PRESSED_BODY_BOTTOM = 0x36B4D4FF;

    public static final int TOGGLED_BODY_TOP = 0x36D8ECFF;
    public static final int TOGGLED_BODY_MID = 0x1C74AAFF;
    public static final int TOGGLED_BODY_BOTTOM = 0x2CA4C8FF;

    public static final int OUTER_RIM_TOP = 0xD8F6FAFF;
    public static final int OUTER_RIM_MID = 0x42DCEBFF;
    public static final int OUTER_RIM_BOTTOM = 0xA8E8F3FF;

    public static final int INNER_RIM_TOP = 0x3EFFFFFF;
    public static final int INNER_RIM_BOTTOM = 0x28D8EAFF;

    public static final int TOP_SPECULAR_BAR = 0x8CFFFFFF;
    public static final int TOP_SPECULAR_BAR_PRESSED = 0xD0FFFFFF;
    public static final int BOTTOM_CAUSTIC_BAR = 0x4ADCEBFF;
    public static final int BOTTOM_CAUSTIC_BAR_PRESSED = 0x80FFE09E;
    public static final int SIDE_MICRO_GLEAM = 0x3AF2F8FF;
    public static final int GLYPH_SNOW_WHITE = 0xF2F8FBFF;
    public static final int GLYPH_PRESSED_WHITE = 0xFFFFFFFF;
    public static final int GLYPH_SHADOW_COLOR = 0x8A070C18;

    private final float mDensity;
    private final boolean mHudSurfaceMode;

    // Reusable paints & rects — strictly zero allocations inside draw()
    private final Paint mScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecularBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDomePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOuterRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTopBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBottomBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mHaloRect = new RectF();
    private final RectF mBodyRect = new RectF();
    private final RectF mDomeRect = new RectF();
    private final RectF mInnerRect = new RectF();
    private final RectF mTopBarRect = new RectF();
    private final RectF mBottomBarRect = new RectF();

    // State & configuration
    private boolean mPressed;
    private boolean mActivated;
    private boolean mFocused;
    private boolean mDisabled;
    private int mMasterAlpha = 255;
    private int mHudOpacityPercent = 78; // Adjustable 10..100% fill opacity for HUD mode
    private float mCornerRadiusPercent = 0f; // 0 => sculpted 30% liquid squircle radius
    private int mCustomBgColor = LEGACY_DEFAULT_CONTROL_BG;
    private int mCustomStrokeColor = Color.WHITE;
    private float mCustomStrokeDp = 0f;

    // Cached geometry metrics
    private float mHaloRadius;
    private float mBodyRadius;
    private float mDomeRadius;
    private float mInnerRadius;
    private float mTopBarRadius;
    private float mBottomBarRadius;
    private boolean mGeometryDirty = true;

    public ZyroxLiquidGlassDrawable(float density) {
        this(density, false);
    }

    public ZyroxLiquidGlassDrawable(float density, boolean hudSurfaceMode) {
        mDensity = density > 0f ? density : 2.75f;
        mHudSurfaceMode = hudSurfaceMode;

        mScrimPaint.setStyle(Paint.Style.FILL);
        mSpecularBorderPaint.setStyle(Paint.Style.STROKE);
        mHaloPaint.setStyle(Paint.Style.STROKE);
        mBodyPaint.setStyle(Paint.Style.FILL);
        mDomePaint.setStyle(Paint.Style.FILL);
        mOuterRimPaint.setStyle(Paint.Style.STROKE);
        mInnerRimPaint.setStyle(Paint.Style.STROKE);
        mTopBarPaint.setStyle(Paint.Style.FILL);
        mBottomBarPaint.setStyle(Paint.Style.FILL);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setColor(0xE8FFFFFF);
        mFocusPaint.setStrokeWidth(1.8f * mDensity);
    }

    /**
     * Updates control appearance options while preserving Liquid Glass optical refraction.
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

        // Hairline optical insets sit strictly inside View bounds so touch hitboxes are 100% preserved
        float haloStroke = clamp(minSide * 0.026f, 0.85f * mDensity, 1.5f * mDensity);
        float bodyInset = clamp(minSide * 0.030f, 1.0f * mDensity, 1.9f * mDensity);
        float outerStroke = mCustomStrokeDp > 0f
                ? clamp(mCustomStrokeDp * mDensity, 0.95f * mDensity, 2.8f * mDensity)
                : clamp(minSide * 0.024f, 1.0f * mDensity, 1.45f * mDensity);
        float innerGap = clamp(minSide * 0.056f, 1.8f * mDensity, 3.2f * mDensity);
        float innerStroke = clamp(minSide * 0.015f, 0.65f * mDensity, 0.95f * mDensity);

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
            // Fluid sculpted water-droplet squircle curvature (~30% of shorter side)
            mBodyRadius = bodyMin * 0.30f;
        }
        mBodyRadius = Math.min(mBodyRadius, bodyMin * 0.5f);
        mHaloRadius = Math.min(Math.min(mHaloRect.width(), mHaloRect.height()) * 0.5f,
                mBodyRadius + bodyInset * 0.6f);
        mInnerRadius = Math.max(2f, mBodyRadius - innerGap * 0.78f);

        // Upper convex liquid-glass dome highlight (top 38% of lens, fading to pure transparent)
        float bodyW = mBodyRect.width();
        float bodyH = mBodyRect.height();
        float domeInset = outerStroke * 0.7f;
        float domeH = Math.max(4f, bodyH * 0.38f);
        mDomeRect.set(
                mBodyRect.left + domeInset,
                mBodyRect.top + domeInset,
                mBodyRect.right - domeInset,
                Math.min(mBodyRect.bottom - domeInset, mBodyRect.top + domeH)
        );
        mDomeRadius = Math.max(2f, mBodyRadius - domeInset);

        // Razor-fine top specular light filament
        float cx = mBodyRect.centerX();
        float topBarWidth = bodyW * (isWide ? 0.68f : 0.54f);
        float topBarHeight = clamp(bodyH * 0.034f, 1.0f * mDensity, 1.9f * mDensity);
        float topBarTop = mBodyRect.top + clamp(bodyH * 0.048f, 1.4f * mDensity, 2.8f * mDensity);
        mTopBarRect.set(
                cx - topBarWidth * 0.5f,
                topBarTop,
                cx + topBarWidth * 0.5f,
                topBarTop + topBarHeight
        );
        mTopBarRadius = topBarHeight * 0.5f;

        // Delicate bottom underwater caustic reflection
        float bottomBarWidth = bodyW * (isWide ? 0.46f : 0.32f);
        float bottomBarHeight = clamp(bodyH * 0.028f, 0.9f * mDensity, 1.5f * mDensity);
        float bottomBarBottom = mBodyRect.bottom - clamp(bodyH * 0.045f, 1.3f * mDensity, 2.5f * mDensity);
        mBottomBarRect.set(
                cx - bottomBarWidth * 0.5f,
                bottomBarBottom - bottomBarHeight,
                cx + bottomBarWidth * 0.5f,
                bottomBarBottom
        );
        mBottomBarRadius = bottomBarHeight * 0.5f;

        // 0. High-Contrast 2-Layer Glass Foundation (#660B0F17 dark scrim + #4DFFFFFF->#0DFFFFFF border)
        int baseScrimAlpha = Color.alpha(HIGH_CONTRAST_SCRIM); // 0x66 = 102 (~40% dark WCAG scrim)
        int effectiveScrimAlpha = mHudSurfaceMode
                ? Math.max(24, Math.min(220, Math.round(baseScrimAlpha * (0.65f + (mHudOpacityPercent / 100f)))))
                : Math.round(baseScrimAlpha * 0.35f);
        mScrimPaint.setColor(scaleAlpha((effectiveScrimAlpha << 24) | (HIGH_CONTRAST_SCRIM & 0x00FFFFFF)));

        mSpecularBorderPaint.setStrokeWidth(Math.max(1.0f, 1.1f * mDensity));
        mSpecularBorderPaint.setShader(new LinearGradient(
                0f, mBodyRect.top, 0f, mBodyRect.bottom,
                scaleAlpha(SPECULAR_BORDER_TOP),
                scaleAlpha(SPECULAR_BORDER_BOTTOM),
                Shader.TileMode.CLAMP
        ));

        // 1. Outer contrast contour (subtle dark micro-shadow idle; soft ice aura pressed)
        mHaloPaint.setStrokeWidth(haloStroke);
        mHaloPaint.setShader(new LinearGradient(
                0f, mHaloRect.top, 0f, mHaloRect.bottom,
                scaleAlpha(activeCool ? PRESSED_HALO_TOP : NORMAL_HALO_TOP),
                scaleAlpha(activeCool ? PRESSED_HALO_BOTTOM : NORMAL_HALO_BOTTOM),
                Shader.TileMode.CLAMP
        ));

        // 2. Ultra-sheer optical lens body (92–96% see-through center so gameplay dominates!)
        int bodyTop;
        int bodyMid;
        int bodyBot;
        if (mDisabled) {
            bodyTop = 0x12D8E2EC;
            bodyMid = 0x08CCD8E4;
            bodyBot = 0x10C4D0DE;
        } else if (mHudSurfaceMode) {
            // Adjustable smoked-crystal telemetry surface (10%..100% opacity slider support)
            int edgeAlpha = Math.max(16, Math.min(238, Math.round(230f * (mHudOpacityPercent / 100f))));
            int midAlpha = Math.max(12, Math.min(226, Math.round(206f * (mHudOpacityPercent / 100f))));
            bodyTop = (edgeAlpha << 24) | 0x0B1320;
            bodyMid = (midAlpha << 24) | 0x080E19;
            bodyBot = (edgeAlpha << 24) | 0x0A111D;
        } else if (hasCustomUserTint()) {
            // Preserve user's chosen tint hue while keeping ultra-sheer Liquid Glass translucency
            int targetAlpha = Math.min(Color.alpha(mCustomBgColor), activeCool ? 96 : 66);
            int sheerUser = (targetAlpha << 24) | (mCustomBgColor & 0x00FFFFFF);
            bodyTop = brighten(sheerUser, 1.14f);
            bodyMid = ((Math.max(12, targetAlpha / 2)) << 24) | (mCustomBgColor & 0x00FFFFFF);
            bodyBot = brighten(sheerUser, 0.96f);
        } else if (mPressed) {
            bodyTop = PRESSED_BODY_TOP;
            bodyMid = PRESSED_BODY_MID;
            bodyBot = PRESSED_BODY_BOTTOM;
        } else if (mActivated) {
            bodyTop = TOGGLED_BODY_TOP;
            bodyMid = TOGGLED_BODY_MID;
            bodyBot = TOGGLED_BODY_BOTTOM;
        } else {
            bodyTop = NORMAL_BODY_TOP;
            bodyMid = NORMAL_BODY_MID;
            bodyBot = NORMAL_BODY_BOTTOM;
        }

        mBodyPaint.setShader(new LinearGradient(
                0f, mBodyRect.top, 0f, mBodyRect.bottom,
                new int[]{
                        scaleAlpha(bodyTop),
                        scaleAlpha(bodyMid),
                        scaleAlpha(bodyMid),
                        scaleAlpha(bodyBot)
                },
                new float[]{0f, 0.34f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));

        // 3. Curved upper liquid-dome sheen
        int domePeak = mPressed ? 0x38FFFFFF : (mActivated ? 0x2CE8F8FF : 0x20FFFFFF);
        mDomePaint.setShader(new LinearGradient(
                0f, mDomeRect.top, 0f, mDomeRect.bottom,
                new int[]{scaleAlpha(domePeak), scaleAlpha(0x08FFFFFF), 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        ));

        // 4. Signature 135° diagonal diamond specular refraction rim
        int rimTopLeft;
        int rimCrossA;
        int rimCrossB;
        int rimBotRight;
        if (mDisabled) {
            rimTopLeft = 0x52FFFFFF;
            rimCrossA = 0x1EFFFFFF;
            rimCrossB = 0x1AFFFFFF;
            rimBotRight = 0x3CFFFFFF;
        } else if (mCustomStrokeDp > 0f && mCustomStrokeColor != Color.WHITE) {
            rimTopLeft = blendArgb(mCustomStrokeColor, 0xEEFFFFFF, 0.45f);
            rimCrossA = setAlphaChannel(mCustomStrokeColor, 84);
            rimCrossB = setAlphaChannel(mCustomStrokeColor, 76);
            rimBotRight = blendArgb(mCustomStrokeColor, 0xCCE8F4FF, 0.35f);
        } else if (mPressed) {
            rimTopLeft = 0xF8FFFFFF;
            rimCrossA = 0x8CE4F2FF;
            rimCrossB = 0x7ED8ECFF;
            rimBotRight = 0xE8F0F8FF;
        } else if (mActivated) {
            rimTopLeft = 0xF0E6FAFF;
            rimCrossA = 0x78B8E8FF;
            rimCrossB = 0x6AA8E0FF;
            rimBotRight = 0xDCF0FAFF;
        } else {
            rimTopLeft = OUTER_RIM_TOP;
            rimCrossA = OUTER_RIM_MID;
            rimCrossB = 0x32D8E8F8;
            rimBotRight = OUTER_RIM_BOTTOM;
        }

        mOuterRimPaint.setStrokeWidth(outerStroke);
        mOuterRimPaint.setShader(new LinearGradient(
                mBodyRect.left, mBodyRect.top, mBodyRect.right, mBodyRect.bottom,
                new int[]{
                        scaleAlpha(rimTopLeft),
                        scaleAlpha(rimCrossA),
                        scaleAlpha(rimCrossB),
                        scaleAlpha(rimBotRight)
                },
                new float[]{0f, 0.38f, 0.64f, 1f},
                Shader.TileMode.CLAMP
        ));

        // 5. Concentric inner liquid meniscus ring
        mInnerRimPaint.setStrokeWidth(innerStroke);
        mInnerRimPaint.setShader(new LinearGradient(
                0f, mInnerRect.top, 0f, mInnerRect.bottom,
                new int[]{
                        scaleAlpha(activeCool ? 0x6CFFFFFF : INNER_RIM_TOP),
                        scaleAlpha(activeCool ? 0x1CE0F2FF : 0x0AFFFFFF),
                        scaleAlpha(activeCool ? 0x48D8F0FF : INNER_RIM_BOTTOM)
                },
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));

        // 6. Tapered top specular filament & bottom liquid-ice caustic
        int topPeak = activeCool ? TOP_SPECULAR_BAR_PRESSED : TOP_SPECULAR_BAR;
        mTopBarPaint.setShader(new LinearGradient(
                mTopBarRect.left, 0f, mTopBarRect.right, 0f,
                new int[]{0x00FFFFFF, scaleAlpha(topPeak), scaleAlpha(topPeak), 0x00FFFFFF},
                new float[]{0f, 0.24f, 0.76f, 1f},
                Shader.TileMode.CLAMP
        ));

        int botPeak = activeCool ? BOTTOM_CAUSTIC_BAR_PRESSED : BOTTOM_CAUSTIC_BAR;
        mBottomBarPaint.setShader(new LinearGradient(
                mBottomBarRect.left, 0f, mBottomBarRect.right, 0f,
                new int[]{0x00E4F6FF, scaleAlpha(botPeak), scaleAlpha(botPeak), 0x00E4F6FF},
                new float[]{0f, 0.28f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || mMasterAlpha <= 0) return;
        if (mGeometryDirty) {
            rebuildGeometry(b);
        }

        boolean lowEffects = LauncherPreferences.PREF_ZYROX_LOW_EFFECTS;

        // 1. Hairline outer contrast micro-contour / press aura
        if (!lowEffects && !mDisabled) {
            canvas.drawRoundRect(mHaloRect, mHaloRadius, mHaloRadius, mHaloPaint);
        }

        // 1b. Layer 1 of High-Contrast Glass: #660B0F17 dark readability scrim (prevents white-on-white washout)
        if (!mDisabled && (mHudSurfaceMode || mHighContrastScrimEnabled)) {
            canvas.drawRoundRect(mBodyRect, mBodyRadius, mBodyRadius, mScrimPaint);
        }

        // 2. Optical crystal liquid-glass lens fill
        canvas.drawRoundRect(mBodyRect, mBodyRadius, mBodyRadius, mBodyPaint);

        // 2b. Layer 2 of High-Contrast Glass: Top-to-bottom specular border (#4DFFFFFF -> #0DFFFFFF)
        if (mHudSurfaceMode || mHighContrastScrimEnabled) {
            canvas.drawRoundRect(mBodyRect, mBodyRadius, mBodyRadius, mSpecularBorderPaint);
        }

        if (!lowEffects && !mDisabled && mInnerRect.width() > 6f * mDensity && mInnerRect.height() > 6f * mDensity) {
            // 3. Upper curved convex dome highlight
            canvas.drawRoundRect(mDomeRect, mDomeRadius, mDomeRadius, mDomePaint);

            // 4. Concentric inner refractive meniscus ring
            canvas.drawRoundRect(mInnerRect, mInnerRadius, mInnerRadius, mInnerRimPaint);

            // 5. Tapered top specular crest filament & bottom water-caustic glint
            if (mTopBarRect.width() > 8f * mDensity) {
                canvas.drawRoundRect(mTopBarRect, mTopBarRadius, mTopBarRadius, mTopBarPaint);
            }
            if (mBottomBarRect.width() > 6f * mDensity) {
                canvas.drawRoundRect(mBottomBarRect, mBottomBarRadius, mBottomBarRadius, mBottomBarPaint);
            }
        }

        // 6. 135° diagonal diamond specular prism rim
        canvas.drawRoundRect(mBodyRect, mBodyRadius, mBodyRadius, mOuterRimPaint);

        // 7. Accessibility focus ring for gamepad / keyboard navigation
        if (mFocused) {
            canvas.drawRoundRect(mHaloRect, mHaloRadius, mHaloRadius, mFocusPaint);
        }
    }

    private boolean hasCustomUserTint() {
        return mCustomBgColor != LEGACY_DEFAULT_CONTROL_BG
                && (mCustomBgColor & 0x00FFFFFF) != 0
                && Color.alpha(mCustomBgColor) > 0;
    }

    private int scaleAlpha(int argb) {
        if (mMasterAlpha >= 255) return argb;
        int a = (Color.alpha(argb) * mMasterAlpha) / 255;
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int setAlphaChannel(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
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

    /** Adjusts the HUD crystal backdrop fill opacity ({@code 10%..100%}) while keeping crisp rims. */
    public void setHudBackdropOpacityPercent(int opacityPercent) {
        int clamped = Math.max(10, Math.min(100, opacityPercent));
        if (mHudOpacityPercent != clamped) {
            mHudOpacityPercent = clamped;
            mGeometryDirty = true;
            invalidateSelf();
        }
    }

    public int getHudBackdropOpacityPercent() {
        return mHudOpacityPercent;
    }

    /**
     * Strict blur-isolation predicate ensuring glass blur applies exclusively to foreground UI
     * cards and NEVER to {@link ZyroxSeamlessGifView}, {@link ZyroxShaderBackgroundView}, or any
     * parent container hosting an animated background GIF.
     */
    public static boolean isEligibleForForegroundBlur(@Nullable android.view.View target) {
        if (target == null) return false;
        if (target instanceof ZyroxSeamlessGifView
                || target instanceof ZyroxLoopingVideoView
                || target instanceof ZyroxShaderBackgroundView) {
            return false;
        }
        if (ZyroxSeamlessGifView.TAG_BLUR_ISOLATED_BACKDROP.equals(target.getTag())) {
            return false;
        }
        if (target instanceof android.view.ViewGroup) {
            return !containsBackdropGif((android.view.ViewGroup) target);
        }
        return true;
    }

    private static boolean containsBackdropGif(@NonNull android.view.ViewGroup group) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            android.view.View child = group.getChildAt(i);
            if (child instanceof ZyroxSeamlessGifView
                    || child instanceof ZyroxLoopingVideoView
                    || child instanceof ZyroxShaderBackgroundView
                    || ZyroxSeamlessGifView.TAG_BLUR_ISOLATED_BACKDROP.equals(child.getTag())) {
                return true;
            }
            if (child instanceof android.view.ViewGroup
                    && containsBackdropGif((android.view.ViewGroup) child)) {
                return true;
            }
        }
        return false;
    }

    private boolean mHighContrastScrimEnabled = false;

    /** Enables the full {@code #660B0F17} WCAG dark scrim + {@code #4DFFFFFF->#0DFFFFFF} specular rim. */
    public void setHighContrastScrimEnabled(boolean enabled) {
        if (mHighContrastScrimEnabled != enabled) {
            mHighContrastScrimEnabled = enabled;
            mGeometryDirty = true;
            invalidateSelf();
        }
    }

    public boolean isHighContrastScrimEnabled() {
        return mHudSurfaceMode || mHighContrastScrimEnabled;
    }

    /**
     * Pure deterministic GPU blur tier classifier: enables hardware {@code RenderEffect} ONLY on
     * Android 12+ (API 31+) mid/high-tier Adreno 7xx/8xx / Xclipse / Desktop GPUs with
     * {@code >= 5.5 GB} RAM; falls back seamlessly to zero-overhead 2-layer frosted gradient glass
     * on Android 11, low-RAM phones, Mali, PowerVR, and legacy Adreno GPUs.
     */
    @NonNull
    public static GlassBlurTier resolveGlassBlurTier(
            int sdkInt,
            int totalRamMb,
            @Nullable git.artdeell.mojo.utils.GpuUtils.GpuArchitecture architecture,
            boolean lowEffectsPref) {
        if (lowEffectsPref) {
            return GlassBlurTier.FROSTED_GRADIENT_FALLBACK;
        }
        if (sdkInt < android.os.Build.VERSION_CODES.S) {
            return GlassBlurTier.FROSTED_GRADIENT_FALLBACK;
        }
        if (totalRamMb > 0 && totalRamMb < MIN_HARDWARE_BLUR_RAM_MB) {
            return GlassBlurTier.FROSTED_GRADIENT_FALLBACK;
        }
        if (architecture == null
                || architecture.isArmFamily()
                || architecture == git.artdeell.mojo.utils.GpuUtils.GpuArchitecture.POWERVR
                || architecture == git.artdeell.mojo.utils.GpuUtils.GpuArchitecture.ADRENO_LEGACY
                || architecture == git.artdeell.mojo.utils.GpuUtils.GpuArchitecture.UNKNOWN) {
            return GlassBlurTier.FROSTED_GRADIENT_FALLBACK;
        }
        return GlassBlurTier.HARDWARE_RENDER_EFFECT;
    }

    /** Evaluates the current device's optimal {@link GlassBlurTier} without throwing on any OEM. */
    @NonNull
    public static GlassBlurTier resolveDeviceGlassBlurTier(@NonNull android.content.Context context) {
        try {
            int ramMb = git.artdeell.mojo.Tools.getTotalDeviceMemory(context);
            git.artdeell.mojo.utils.GpuUtils.GLInfo gl = git.artdeell.mojo.utils.GpuUtils.getGlInfo();
            return resolveGlassBlurTier(
                    SdkLevel.get(),
                    ramMb,
                    gl != null ? gl.architecture : null,
                    LauncherPreferences.PREF_ZYROX_LOW_EFFECTS);
        } catch (Throwable ignored) {
            return GlassBlurTier.FROSTED_GRADIENT_FALLBACK;
        }
    }

    /**
     * Applies Android 12+ hardware {@link android.graphics.RenderEffect} blur to a dedicated blur
     * underlay view when the device qualifies for {@link GlassBlurTier#HARDWARE_RENDER_EFFECT}, or
     * clears {@code RenderEffect} immediately on Android 11 / low-RAM / Mali devices and protected
     * GIF/MP4 backdrops so frame rates stay locked at native refresh rate.
     */
    public static boolean applyAdaptiveRenderEffectBlur(@Nullable android.view.View blurUnderlayView,
                                                        @NonNull android.content.Context context,
                                                        float radiusDp) {
        if (blurUnderlayView == null) return false;
        if (!SdkLevel.atLeast(android.os.Build.VERSION_CODES.S)) {
            return false;
        }
        if (!isEligibleForForegroundBlur(blurUnderlayView)
                || resolveDeviceGlassBlurTier(context) != GlassBlurTier.HARDWARE_RENDER_EFFECT
                || radiusDp <= 0.5f) {
            blurUnderlayView.setRenderEffect(null);
            return false;
        }
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float radiusPx = Math.min(36f, Math.max(2f, radiusDp * density));
        blurUnderlayView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP));
        return true;
    }

    /**
     * Attaches a foreground-isolated Liquid Glass surface to {@code cardView} while explicitly
     * clearing any accidental {@link android.graphics.RenderEffect} on background GIF hierarchies.
     */
    public static void attachForegroundCardGlass(@NonNull android.view.View cardView,
                                                 float density,
                                                 boolean hudSurfaceMode) {
        if (!isEligibleForForegroundBlur(cardView)) {
            if (SdkLevel.atLeast(android.os.Build.VERSION_CODES.S)) {
                cardView.setRenderEffect(null);
            }
            return;
        }
        ZyroxLiquidGlassDrawable glass = new ZyroxLiquidGlassDrawable(density, hudSurfaceMode);
        glass.setHighContrastScrimEnabled(true);
        cardView.setBackground(glass);
        cardView.setClipToOutline(true);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
