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

    private ZyroxStudioUi() {
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
        btn.setLetterSpacing(0.035f);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 9.5f : 10.5f);
        btn.setStateListAnimator(null);
        btn.setElevation(0f);
        Typeface bold = ResourcesCompat.getFont(ctx, R.font.noto_sans_bold);
        if (bold != null) btn.setTypeface(bold, Typeface.BOLD);
        int minH = dp(ctx, compact ? 34 : 38);
        btn.setMinHeight(minH);
        btn.setMinimumHeight(minH);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        int padH = dp(ctx, compact ? 10 : 14);
        btn.setPadding(padH, 0, padH, 0);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    @NonNull
    public static Button createPrimaryButton(@NonNull Context ctx, @NonNull String label) {
        return createGlassButton(ctx, label, R.drawable.btn_zyrox_play, false);
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
        Typeface bold = ResourcesCompat.getFont(ctx, R.font.noto_sans_bold);
        if (bold != null) tv.setTypeface(bold, Typeface.BOLD);
        else tv.setTypeface(Typeface.DEFAULT_BOLD);
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
    public static LinearLayout.LayoutParams spacedParam(int w, int h, int marginStartPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginStart(marginStartPx);
        return lp;
    }

    /**
     * Pixel-accurate, zero-filter dual-layer Minecraft skin character preview renderer (front & back
     * side-by-side) supporting both {@code 64×64} modern Steve/Alex and {@code 64×32} legacy skins.
     */
    public static final class SkinCharacterPreviewView extends View {
        private final Paint mPixelPaint = new Paint();
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect mSrc = new Rect();
        private final Rect mDst = new Rect();
        @Nullable private Bitmap mSkin;
        @NonNull private SkinStudioManager.SkinModel mModel = SkinStudioManager.SkinModel.CLASSIC;

        public SkinCharacterPreviewView(@NonNull Context context) {
            super(context);
            mPixelPaint.setFilterBitmap(false);
            mPixelPaint.setAntiAlias(false);
            mPixelPaint.setDither(false);
            mLabelPaint.setColor(0xCCDDE5EF);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTextSize(10f * context.getResources().getDisplayMetrics().density);
            setBackgroundResource(R.drawable.bg_zyrox_terminal);
        }

        public void setSkin(@Nullable Bitmap bitmap, @NonNull SkinStudioManager.SkinModel model) {
            mSkin = bitmap;
            mModel = model;
            invalidate();
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
            int armW = (modern && mModel == SkinStudioManager.SkinModel.SLIM) ? 3 : 4;
            // Character is 32 skin-pixels tall (8 head + 12 body + 12 legs), (8 + 2*armW) wide
            int unit = Math.max(2, Math.min((h - dp(getContext(), 28)) / 32, (w - dp(getContext(), 28)) / 38));
            int bodyH = 32 * unit;
            int topY = Math.max(dp(getContext(), 6), (h - bodyH - dp(getContext(), 14)) / 2);

            int frontCenterX = Math.round(w * 0.30f);
            int backCenterX = Math.round(w * 0.70f);

            drawPose(canvas, frontCenterX, topY, unit, armW, modern, true);
            drawPose(canvas, backCenterX, topY, unit, armW, modern, false);

            float labelY = topY + bodyH + dp(getContext(), 12);
            canvas.drawText("FRONT", frontCenterX, labelY, mLabelPaint);
            canvas.drawText("BACK", backCenterX, labelY, mLabelPaint);
        }

        private void drawPose(Canvas c, int cx, int topY, int u, int armW, boolean modern, boolean front) {
            int headX = cx - 4 * u;
            int torsoX = cx - 4 * u;
            int torsoY = topY + 8 * u;
            int legsY = topY + 20 * u;
            int leftDrawArmX = torsoX - armW * u;
            int rightDrawArmX = torsoX + 8 * u;

            if (front) {
                // Head + Hat overlay
                blit(c, 8, 8, 8, 8, headX, topY, 8 * u, 8 * u);
                blit(c, 40, 8, 8, 8, headX, topY, 8 * u, 8 * u);
                // Torso + Jacket overlay
                blit(c, 20, 20, 8, 12, torsoX, torsoY, 8 * u, 12 * u);
                if (modern) blit(c, 20, 36, 8, 12, torsoX, torsoY, 8 * u, 12 * u);
                // Right arm (viewer's left) + Left arm (viewer's right)
                blit(c, 44, 20, armW, 12, leftDrawArmX, torsoY, armW * u, 12 * u);
                if (modern) {
                    blit(c, 44, 36, armW, 12, leftDrawArmX, torsoY, armW * u, 12 * u);
                    blit(c, 36, 52, armW, 12, rightDrawArmX, torsoY, armW * u, 12 * u);
                    blit(c, 52, 52, armW, 12, rightDrawArmX, torsoY, armW * u, 12 * u);
                } else {
                    blit(c, 44, 20, armW, 12, rightDrawArmX, torsoY, armW * u, 12 * u);
                }
                // Right leg + Left leg
                blit(c, 4, 20, 4, 12, torsoX, legsY, 4 * u, 12 * u);
                if (modern) {
                    blit(c, 4, 36, 4, 12, torsoX, legsY, 4 * u, 12 * u);
                    blit(c, 20, 52, 4, 12, cx, legsY, 4 * u, 12 * u);
                    blit(c, 4, 52, 4, 12, cx, legsY, 4 * u, 12 * u);
                } else {
                    blit(c, 4, 20, 4, 12, cx, legsY, 4 * u, 12 * u);
                }
            } else {
                // Head back + Hat back
                blit(c, 24, 8, 8, 8, headX, topY, 8 * u, 8 * u);
                blit(c, 56, 8, 8, 8, headX, topY, 8 * u, 8 * u);
                // Torso back + Jacket back
                blit(c, 32, 20, 8, 12, torsoX, torsoY, 8 * u, 12 * u);
                if (modern) blit(c, 32, 36, 8, 12, torsoX, torsoY, 8 * u, 12 * u);
                // Arms back
                int rArmBackX = 48 + armW;
                if (modern) {
                    int lArmBackX = 40 + armW;
                    blit(c, lArmBackX, 52, armW, 12, leftDrawArmX, torsoY, armW * u, 12 * u);
                    blit(c, lArmBackX + 16, 52, armW, 12, leftDrawArmX, torsoY, armW * u, 12 * u);
                    blit(c, rArmBackX, 20, armW, 12, rightDrawArmX, torsoY, armW * u, 12 * u);
                    blit(c, rArmBackX, 36, armW, 12, rightDrawArmX, torsoY, armW * u, 12 * u);
                } else {
                    blit(c, rArmBackX, 20, armW, 12, leftDrawArmX, torsoY, armW * u, 12 * u);
                    blit(c, rArmBackX, 20, armW, 12, rightDrawArmX, torsoY, armW * u, 12 * u);
                }
                // Legs back
                if (modern) {
                    blit(c, 28, 52, 4, 12, torsoX, legsY, 4 * u, 12 * u);
                    blit(c, 12, 52, 4, 12, torsoX, legsY, 4 * u, 12 * u);
                    blit(c, 12, 20, 4, 12, cx, legsY, 4 * u, 12 * u);
                    blit(c, 12, 36, 4, 12, cx, legsY, 4 * u, 12 * u);
                } else {
                    blit(c, 12, 20, 4, 12, torsoX, legsY, 4 * u, 12 * u);
                    blit(c, 12, 20, 4, 12, cx, legsY, 4 * u, 12 * u);
                }
            }
        }

        private void blit(Canvas c, int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh) {
            if (mSkin == null) return;
            mSrc.set(sx, sy, sx + sw, sy + sh);
            mDst.set(dx, dy, dx + dw, dy + dh);
            c.drawBitmap(mSkin, mSrc, mDst, mPixelPaint);
        }
    }
}
