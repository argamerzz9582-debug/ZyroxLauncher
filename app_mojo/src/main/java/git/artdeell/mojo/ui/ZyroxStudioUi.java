package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

import git.artdeell.mojo.R;
import git.artdeell.mojo.skins.SkinStudioManager;

/**
 * Reusable Zyrox 2.0 Liquid Glass studio UI component factory ensuring uniform typography,
 * accessible touch targets, consistent dark-obsidian + neutral Liquid Glass styling, and zero
 * portrait/landscape layout drift across all Zyrox 2.0 studio fragments.
 */
public final class ZyroxStudioUi {

    @Nullable
    private static volatile Typeface sDisplayFont;
    @Nullable
    private static volatile Typeface sPixelFont;

    private ZyroxStudioUi() {
    }

    /** Returns the process-cached bold display typeface ({@code R.font.noto_sans_bold}) with zero repeat I/O. */
    @NonNull
    public static Typeface getDisplayFont(@NonNull Context ctx) {
        Typeface cached = sDisplayFont;
        if (cached != null) return cached;
        try {
            Typeface loaded = ResourcesCompat.getFont(ctx.getApplicationContext(), R.font.noto_sans_bold);
            if (loaded != null) {
                sDisplayFont = loaded;
                return loaded;
            }
        } catch (Throwable ignored) {
        }
        return Typeface.DEFAULT_BOLD;
    }

    /** Returns the process-cached Minecraft headline typeface ({@code R.font.minecraft_ten}) with zero repeat I/O. */
    @NonNull
    public static Typeface getPixelFont(@NonNull Context ctx) {
        Typeface cached = sPixelFont;
        if (cached != null) return cached;
        try {
            Typeface loaded = ResourcesCompat.getFont(ctx.getApplicationContext(), R.font.minecraft_ten);
            if (loaded != null) {
                sPixelFont = loaded;
                return loaded;
            }
        } catch (Throwable ignored) {
        }
        return getDisplayFont(ctx);
    }

    public static int dp(@NonNull Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    @NonNull
    public static LinearLayout createGlassScreenRoot(@NonNull Context ctx) {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        outer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(ctx);
        card.setId(android.R.id.content);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_zyrox_glass_modal);
        int pad = dp(ctx, 12);
        card.setPadding(pad, dp(ctx, 10), pad, dp(ctx, 10));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        outer.addView(card);
        return outer;
    }

    @NonNull
    public static LinearLayout createItemCard(@NonNull Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_zyrox_input);
        int pH = dp(ctx, 11);
        int pV = dp(ctx, 9);
        card.setPadding(pH, pV, pH, pV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 7);
        card.setLayoutParams(lp);
        return card;
    }

    @NonNull
    public static Button createGlassButton(@NonNull Context ctx,
                                           @NonNull String label,
                                           @DrawableRes int bgRes,
                                           boolean compact) {
        AppCompatButton btn = new AppCompatButton(ctx);
        btn.setText(label);
        btn.setAllCaps(true);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setBackgroundResource(bgRes);
        btn.setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        btn.setShadowLayer(3.5f, 0f, 1.0f, ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
        btn.setLetterSpacing(0.025f);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 9.5f : 10.5f);
        btn.setIncludeFontPadding(false);
        btn.setStateListAnimator(null);
        btn.setElevation(0f);
        btn.setTypeface(getDisplayFont(ctx), Typeface.BOLD);
        int minH = dp(ctx, compact ? 34 : 38);
        btn.setMinHeight(minH);
        btn.setMinimumHeight(minH);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        int padH = dp(ctx, compact ? 9 : 13);
        btn.setPadding(padH, 0, padH, 0);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    @NonNull
    public static Button createPrimaryButton(@NonNull Context ctx, @NonNull String label) {
        return createGlassButton(ctx, label, R.drawable.btn_zyrox_play, false);
    }

    @NonNull
    public static Button createSecondaryButton(@NonNull Context ctx, @NonNull String label) {
        return createGlassButton(ctx, label, R.drawable.btn_zyrox_secondary, false);
    }

    @NonNull
    public static Button createSecondaryButton(@NonNull Context ctx, @NonNull String label, boolean compact) {
        return createGlassButton(ctx, label, R.drawable.btn_zyrox_secondary, compact);
    }

    @NonNull
    public static Button createDangerButton(@NonNull Context ctx, @NonNull String label, boolean compact) {
        return createGlassButton(ctx, label, R.drawable.btn_zyrox_danger, compact);
    }

    @NonNull
    public static Button createTabButton(@NonNull Context ctx, @NonNull String label) {
        return createGlassButton(ctx, label, R.drawable.bg_zyrox_nav_item, true);
    }

    @NonNull
    public static EditText createGlassSearchInput(@NonNull Context ctx, @NonNull String hint) {
        EditText input = new EditText(ctx);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_zyrox_input);
        input.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_text_primary));
        input.setHintTextColor(ContextCompat.getColor(ctx, R.color.zyrox_text_muted));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        input.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));
        return input;
    }

    @NonNull
    public static TextView createTitle(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_text_primary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(getDisplayFont(ctx), Typeface.BOLD);
        return tv;
    }

    @NonNull
    public static TextView createSubtitle(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_text_subtle));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        return tv;
    }

    @NonNull
    public static TextView createBadge(@NonNull Context ctx, @NonNull String text, boolean warningOrDanger) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setBackgroundResource(warningOrDanger ? R.drawable.bg_zyrox_badge_danger : R.drawable.bg_zyrox_badge);
        tv.setTextColor(ContextCompat.getColor(ctx,
                warningOrDanger ? R.color.zyrox_danger : R.color.zyrox_lg_text));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.05f);
        tv.setPadding(dp(ctx, 7), dp(ctx, 2.5f), dp(ctx, 7), dp(ctx, 2.5f));
        return tv;
    }

    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    @NonNull
    public static EditText createGlassInput(@NonNull Context ctx, @NonNull String hint) {
        return createGlassSearchInput(ctx, hint);
    }

    @NonNull
    public static TextView createSectionLabel(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_amber));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tv.setLetterSpacing(0.06f);
        tv.setAllCaps(true);
        tv.setTypeface(getDisplayFont(ctx), Typeface.BOLD);
        return tv;
    }

    @NonNull
    public static TextView createBody(@NonNull Context ctx, @NonNull String text) {
        return createSubtitle(ctx, text);
    }

    @NonNull
    public static LinearLayout.LayoutParams vParam(int topMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginPx;
        return lp;
    }

    @NonNull
    public static LinearLayout.LayoutParams spacedParam(int w, int h, int marginStartPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginStart(marginStartPx);
        return lp;
    }

    /**
     * Interactive 3D touch-rotatable, pixel-crisp dual-layer Minecraft skin character renderer
     * supporting 360° yaw &amp; pitch drag, Classic (4px Steve) &amp; Slim (3px Alex) arms, full
     * outer armor/hat/jacket/sleeve/pants layers, and {@code 64×64} + {@code 64×32} skins.
     */
    public static final class SkinCharacterPreviewView extends View {
        private final Paint mPixelPaint = new Paint();
        private final Paint mShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix mFaceMatrix = new android.graphics.Matrix();
        private final android.graphics.Path mFacePath = new android.graphics.Path();
        private final float[] mSrcPts = new float[8];
        private final float[] mDstPts = new float[8];
        private final java.util.ArrayList<FaceQuad> mFaces = new java.util.ArrayList<>(72);
        @Nullable private Bitmap mSkin;
        @Nullable private Bitmap mCape;
        @NonNull private SkinStudioManager.SkinModel mModel = SkinStudioManager.SkinModel.CLASSIC;
        private float mYawDeg = -26f;
        private float mPitchDeg = 12f;
        private float mLastTouchX;
        private float mLastTouchY;
        private boolean mDragged = false;

        private static final java.util.Comparator<FaceQuad> FACE_COMPARATOR = (a, b) -> {
            int cmp = Float.compare(a.depthZ, b.depthZ);
            if (cmp != 0) return cmp;
            return Boolean.compare(a.overlay, b.overlay);
        };

        private final java.util.ArrayList<FaceQuad> mFacePool = new java.util.ArrayList<>(72);
        private int mFacePoolCursor = 0;
        private final float[] mP0 = new float[3];
        private final float[] mP1 = new float[3];
        private final float[] mP2 = new float[3];
        private final float[] mP3 = new float[3];

        private static final class FaceQuad {
            int sx, sy, sw, sh;
            float x0, y0, x1, y1, x2, y2, x3, y3;
            float depthZ;
            int shadeAlpha;
            boolean overlay;

            void set(int sx, int sy, int sw, int sh,
                     float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3,
                     float depthZ, int shadeAlpha, boolean overlay) {
                this.sx = sx; this.sy = sy; this.sw = sw; this.sh = sh;
                this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
                this.x2 = x2; this.y2 = y2; this.x3 = x3; this.y3 = y3;
                this.depthZ = depthZ;
                this.shadeAlpha = shadeAlpha;
                this.overlay = overlay;
            }
        }

        public SkinCharacterPreviewView(@NonNull Context context) {
            super(context);
            mPixelPaint.setFilterBitmap(false);
            mPixelPaint.setAntiAlias(false);
            mPixelPaint.setDither(false);
            mLabelPaint.setColor(0xD8E2ECFF);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTextSize(9.5f * context.getResources().getDisplayMetrics().density);
            setBackgroundResource(R.drawable.bg_zyrox_terminal);
            setClickable(true);
        }

        public void setSkin(@Nullable Bitmap bitmap, @NonNull SkinStudioManager.SkinModel model) {
            mSkin = bitmap;
            mModel = model;
            invalidate();
        }

        public void setCape(@Nullable Bitmap capeBitmap) {
            mCape = capeBitmap;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    mLastTouchX = event.getX();
                    mLastTouchY = event.getY();
                    mDragged = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - mLastTouchX;
                    float dy = event.getY() - mLastTouchY;
                    if (Math.abs(dx) > 2f || Math.abs(dy) > 2f) mDragged = true;
                    mYawDeg = (mYawDeg + dx * 0.85f) % 360f;
                    mPitchDeg = Math.max(-42f, Math.min(42f, mPitchDeg + dy * 0.55f));
                    mLastTouchX = event.getX();
                    mLastTouchY = event.getY();
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (!mDragged) {
                        // Quick tap toggles clean 3D front <-> back showcase angle
                        mYawDeg = (Math.abs(mYawDeg) < 90f) ? 154f : -26f;
                        mPitchDeg = 12f;
                        invalidate();
                        performClick();
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 16 || h <= 16) return;

            if (mSkin == null || mSkin.isRecycled()) {
                canvas.drawText("Import a 64×64 or 64×32 PNG skin", w * 0.5f, h * 0.52f, mLabelPaint);
                return;
            }

            boolean modern = mSkin.getHeight() >= 64;
            float armW = (modern && mModel == SkinStudioManager.SkinModel.SLIM) ? 3f : 4f;
            int armTexW = (int) armW;
            float scale = Math.max(2f, Math.min((h - dp(getContext(), 28)) / 35f,
                    (w - dp(getContext(), 24)) / 24f));
            float cx = w * 0.5f;
            float cy = (h - dp(getContext(), 12)) * 0.5f;

            double yaw = Math.toRadians(mYawDeg);
            double pitch = Math.toRadians(mPitchDeg);
            float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
            float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);

            mFaces.clear();
            mFacePoolCursor = 0;
            // Model coordinates: X right, Y down (-16 head top .. +16 feet), Z toward viewer
            // Head (8x8x8) + Hat overlay
            addBox(0f, -12f, 0f, 8f, 8f, 8f, 0, 0, 8, 8, 8, false, cx, cy, scale, cosY, sinY, cosP, sinP);
            addBox(0f, -12f, 0f, 8.7f, 8.7f, 8.7f, 32, 0, 8, 8, 8, true, cx, cy, scale, cosY, sinY, cosP, sinP);

            // Torso (8x12x4) + Jacket overlay
            addBox(0f, -2f, 0f, 8f, 12f, 4f, 16, 16, 8, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
            if (modern) {
                addBox(0f, -2f, 0f, 8.45f, 12.45f, 4.45f, 16, 32, 8, 12, 4, true, cx, cy, scale, cosY, sinY, cosP, sinP);
            }

            // Right arm (viewer's left at yaw=0) & Left arm (viewer's right at yaw=0)
            float armOffsetX = 4f + armW * 0.5f;
            addBox(-armOffsetX, -2f, 0f, armW, 12f, 4f, 40, 16, armTexW, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
            if (modern) {
                addBox(-armOffsetX, -2f, 0f, armW + 0.45f, 12.45f, 4.45f, 40, 32, armTexW, 12, 4, true, cx, cy, scale, cosY, sinY, cosP, sinP);
                addBox(armOffsetX, -2f, 0f, armW, 12f, 4f, 32, 48, armTexW, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
                addBox(armOffsetX, -2f, 0f, armW + 0.45f, 12.45f, 4.45f, 48, 48, armTexW, 12, 4, true, cx, cy, scale, cosY, sinY, cosP, sinP);
            } else {
                addBox(armOffsetX, -2f, 0f, armW, 12f, 4f, 40, 16, armTexW, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
            }

            // Right leg & Left leg
            addBox(-2f, 10f, 0f, 4f, 12f, 4f, 0, 16, 4, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
            if (modern) {
                addBox(-2f, 10f, 0f, 4.4f, 12.4f, 4.4f, 0, 32, 4, 12, 4, true, cx, cy, scale, cosY, sinY, cosP, sinP);
                addBox(2f, 10f, 0f, 4f, 12f, 4f, 16, 48, 4, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
                addBox(2f, 10f, 0f, 4.4f, 12.4f, 4.4f, 0, 48, 4, 12, 4, true, cx, cy, scale, cosY, sinY, cosP, sinP);
            } else {
                addBox(2f, 10f, 0f, 4f, 12f, 4f, 0, 16, 4, 12, 4, false, cx, cy, scale, cosY, sinY, cosP, sinP);
            }

            java.util.Collections.sort(mFaces, FACE_COMPARATOR);

            for (int i = 0, n = mFaces.size(); i < n; i++) {
                drawFaceQuad(canvas, mFaces.get(i));
            }

            // Optional 3D Cape preview draped cleanly on player's back (UV 1,1..11,17 on 64x32 cape)
            if (mCape != null && !mCape.isRecycled() && mCape.getWidth() >= 22 && mCape.getHeight() >= 17) {
                projInto(mP0, 5f, -7.8f, -2.55f, cx, cy, scale, cosY, sinY, cosP, sinP);
                projInto(mP1, -5f, -7.8f, -2.55f, cx, cy, scale, cosY, sinY, cosP, sinP);
                projInto(mP2, -5.3f, 8.2f, -3.55f, cx, cy, scale, cosY, sinY, cosP, sinP);
                projInto(mP3, 5.3f, 8.2f, -3.55f, cx, cy, scale, cosY, sinY, cosP, sinP);
                float capeCross = (mP1[0] - mP0[0]) * (mP2[1] - mP0[1]) - (mP1[1] - mP0[1]) * (mP2[0] - mP0[0]);
                if (capeCross > 0.5f) {
                    mSrcPts[0] = 1f;  mSrcPts[1] = 1f;
                    mSrcPts[2] = 11f; mSrcPts[3] = 1f;
                    mSrcPts[4] = 11f; mSrcPts[5] = 17f;
                    mSrcPts[6] = 1f;  mSrcPts[7] = 17f;
                    mDstPts[0] = mP0[0]; mDstPts[1] = mP0[1];
                    mDstPts[2] = mP1[0]; mDstPts[3] = mP1[1];
                    mDstPts[4] = mP2[0]; mDstPts[5] = mP2[1];
                    mDstPts[6] = mP3[0]; mDstPts[7] = mP3[1];
                    if (mFaceMatrix.setPolyToPoly(mSrcPts, 0, mDstPts, 0, 4)) {
                        mFacePath.reset();
                        mFacePath.moveTo(mP0[0], mP0[1]);
                        mFacePath.lineTo(mP1[0], mP1[1]);
                        mFacePath.lineTo(mP2[0], mP2[1]);
                        mFacePath.lineTo(mP3[0], mP3[1]);
                        mFacePath.close();
                        int sv = canvas.save();
                        canvas.clipPath(mFacePath);
                        canvas.drawBitmap(mCape, mFaceMatrix, mPixelPaint);
                        canvas.restoreToCount(sv);
                    }
                }
            }

            canvas.drawText("3D PREVIEW • DRAG TO ROTATE • TAP TO FLIP", w * 0.5f, h - dp(getContext(), 6), mLabelPaint);
        }

        private void addBox(float bx, float by, float bz,
                            float bw, float bh, float bd,
                            int u, int v, int tw, int th, int td,
                            boolean overlay,
                            float cx, float cy, float scale,
                            float cosY, float sinY, float cosP, float sinP) {
            float hx = bw * 0.5f, hy = bh * 0.5f, hz = bd * 0.5f;
            float x0 = bx - hx, x1 = bx + hx;
            float y0 = by - hy, y1 = by + hy;
            float z0 = bz - hz, z1 = bz + hz;

            // Front (+Z): u+td, v+td, tw x th
            emitFace(u + td, v + td, tw, th,
                    x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                    0, overlay, cx, cy, scale, cosY, sinY, cosP, sinP);
            // Back (-Z): u+2*td+tw, v+td, tw x th
            emitFace(u + td + tw + td, v + td, tw, th,
                    x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0,
                    28, overlay, cx, cy, scale, cosY, sinY, cosP, sinP);
            // Right (-X, character right side): u, v+td, td x th
            emitFace(u, v + td, td, th,
                    x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0,
                    38, overlay, cx, cy, scale, cosY, sinY, cosP, sinP);
            // Left (+X, character left side): u+td+tw, v+td, td x th
            emitFace(u + td + tw, v + td, td, th,
                    x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1,
                    22, overlay, cx, cy, scale, cosY, sinY, cosP, sinP);
            // Top (-Y): u+td, v, tw x td
            emitFace(u + td, v, tw, td,
                    x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1,
                    0, overlay, cx, cy, scale, cosY, sinY, cosP, sinP);
            // Bottom (+Y): u+td+tw, v, tw x td
            emitFace(u + td + tw, v, tw, td,
                    x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0,
                    58, overlay, cx, cy, scale, cosY, sinY, cosP, sinP);
        }

        private void emitFace(int sx, int sy, int sw, int sh,
                              float ax, float ay, float az,
                              float bx, float by, float bz,
                              float dx, float dy, float dz,
                              float ex, float ey, float ez,
                              int shadeAlpha, boolean overlay,
                              float cx, float cy, float scale,
                              float cosY, float sinY, float cosP, float sinP) {
            projInto(mP0, ax, ay, az, cx, cy, scale, cosY, sinY, cosP, sinP);
            projInto(mP1, bx, by, bz, cx, cy, scale, cosY, sinY, cosP, sinP);
            projInto(mP2, dx, dy, dz, cx, cy, scale, cosY, sinY, cosP, sinP);
            projInto(mP3, ex, ey, ez, cx, cy, scale, cosY, sinY, cosP, sinP);
            // Backface cull for solid core layers; keep front-facing outer layer quads
            float cross = (mP1[0] - mP0[0]) * (mP2[1] - mP0[1]) - (mP1[1] - mP0[1]) * (mP2[0] - mP0[0]);
            if (cross <= 0.5f) return;
            float avgZ = (mP0[2] + mP1[2] + mP2[2] + mP3[2]) * 0.25f + (overlay ? 0.12f : 0f);
            FaceQuad quad;
            if (mFacePoolCursor < mFacePool.size()) {
                quad = mFacePool.get(mFacePoolCursor);
            } else {
                quad = new FaceQuad();
                mFacePool.add(quad);
            }
            mFacePoolCursor++;
            quad.set(sx, sy, sw, sh,
                    mP0[0], mP0[1], mP1[0], mP1[1], mP2[0], mP2[1], mP3[0], mP3[1],
                    avgZ, shadeAlpha, overlay);
            mFaces.add(quad);
        }

        private static void projInto(float[] out, float x, float y, float z,
                                     float cx, float cy, float scale,
                                     float cosY, float sinY, float cosP, float sinP) {
            float rx = x * cosY + z * sinY;
            float rz = -x * sinY + z * cosY;
            float ry = y * cosP + rz * sinP;
            float fz = -y * sinP + rz * cosP;
            out[0] = cx + rx * scale;
            out[1] = cy + ry * scale;
            out[2] = fz;
        }

        private void drawFaceQuad(@NonNull Canvas c, @NonNull FaceQuad f) {
            Bitmap skin = mSkin;
            if (skin == null || f.sx < 0 || f.sy < 0
                    || f.sx + f.sw > skin.getWidth() || f.sy + f.sh > skin.getHeight()) {
                return;
            }
            mSrcPts[0] = f.sx;        mSrcPts[1] = f.sy;
            mSrcPts[2] = f.sx + f.sw; mSrcPts[3] = f.sy;
            mSrcPts[4] = f.sx + f.sw; mSrcPts[5] = f.sy + f.sh;
            mSrcPts[6] = f.sx;        mSrcPts[7] = f.sy + f.sh;

            mDstPts[0] = f.x0; mDstPts[1] = f.y0;
            mDstPts[2] = f.x1; mDstPts[3] = f.y1;
            mDstPts[4] = f.x2; mDstPts[5] = f.y2;
            mDstPts[6] = f.x3; mDstPts[7] = f.y3;

            if (!mFaceMatrix.setPolyToPoly(mSrcPts, 0, mDstPts, 0, 4)) return;
            mFacePath.reset();
            mFacePath.moveTo(f.x0, f.y0);
            mFacePath.lineTo(f.x1, f.y1);
            mFacePath.lineTo(f.x2, f.y2);
            mFacePath.lineTo(f.x3, f.y3);
            mFacePath.close();

            int save = c.save();
            c.clipPath(mFacePath);
            c.drawBitmap(skin, mFaceMatrix, mPixelPaint);
            if (!f.overlay && f.shadeAlpha > 0) {
                mShadePaint.setColor(Color.argb(f.shadeAlpha, 6, 10, 24));
                c.drawPath(mFacePath, mShadePaint);
            }
            c.restoreToCount(save);
        }
    }
}
