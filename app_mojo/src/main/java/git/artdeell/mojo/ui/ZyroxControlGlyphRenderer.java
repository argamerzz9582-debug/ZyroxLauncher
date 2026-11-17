package git.artdeell.mojo.ui;

import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.input.ControlData;

/**
 * Resolution-independent vector renderer for the signature snow-white ({@code #FAFCFF}) glyphs
 * of the Zyrox Liquid Glass control system:
 * <ul>
 *   <li>{@link Glyph#UP}, {@link Glyph#DOWN}, {@link Glyph#LEFT}, {@link Glyph#RIGHT} — rounded equilateral triangles</li>
 *   <li>{@link Glyph#CENTER} — rounded square frame with centered circle dot (sneak / D-pad center)</li>
 *   <li>{@link Glyph#JUMP} — rounded square frame with bold centered upward arrow (jump)</li>
 *   <li>{@link Glyph#GEAR} — 8-ray circular gear with centered hub dot (in-game menu / settings)</li>
 * </ul>
 */
public final class ZyroxControlGlyphRenderer {

    public enum Glyph {
        NONE,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        CENTER,
        JUMP,
        GEAR
    }

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    private float mCachedCornerRadius = -1f;

    public static final int GLYPH_IDLE_COLOR = 0xD6FAFCFF;
    public static final int GLYPH_ACTIVE_COLOR = 0xFFFFFFFF;

    public ZyroxControlGlyphRenderer() {
        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(GLYPH_IDLE_COLOR);

        mShadowFillPaint.setStyle(Paint.Style.FILL);
        mShadowFillPaint.setColor(0x5E060B14);

        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setColor(GLYPH_IDLE_COLOR);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mShadowStrokePaint.setStyle(Paint.Style.STROKE);
        mShadowStrokePaint.setColor(0x5E060B14);
        mShadowStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mShadowStrokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * Maps a control's configured label and keycodes to its matching Liquid Glass vector icon, or
     * {@link Glyph#NONE} when the control should render crisp snow-white Liquid Glass typography
     * (e.g., {@code CHAT}, {@code DEBUG}, {@code KEYBOARD}, {@code MOUSE}, {@code TAB}, {@code 3RD},
     * {@code PRI}, {@code SEC}, {@code GUI}, {@code INV}, or any custom user label).
     */
    @NonNull
    public static Glyph resolveGlyph(@Nullable String rawName, @Nullable int[] keycodes) {
        if (rawName == null) return Glyph.NONE;
        String name = rawName.trim();
        if (name.isEmpty()) return Glyph.NONE;

        switch (name) {
            case "▲":
            case "△":
            case "↑":
                return Glyph.UP;
            case "▼":
            case "▽":
            case "↓":
                return Glyph.DOWN;
            case "◀":
            case "◁":
            case "◄":
            case "←":
                return Glyph.LEFT;
            case "▶":
            case "▷":
            case "►":
            case "→":
                return Glyph.RIGHT;
            case "◇":
            case "◆":
            case "◈":
            case "⊡":
                return Glyph.CENTER;
            case "⬛":
            case "⬜":
            case "■":
            case "□":
                return Glyph.JUMP;
            case "⚙":
            case "⚙️":
                return Glyph.GEAR;
            default:
                break;
        }
        // If a control is strictly the single-glyph special menu icon button named "≡" or "☰"
        if (("≡".equals(name) || "☰".equals(name)) && containsKey(keycodes, ControlData.SPECIALBTN_MENU)) {
            return Glyph.GEAR;
        }
        return Glyph.NONE;
    }

    private static boolean containsKey(@Nullable int[] keycodes, int target) {
        if (keycodes == null) return false;
        for (int k : keycodes) {
            if (k == target) return true;
        }
        return false;
    }

    /**
     * Draws {@code glyph} centered inside {@code [0, width] × [0, height]} with zero allocations
     * during steady-state rendering.
     */
    public void drawGlyph(@NonNull Canvas canvas, @NonNull Glyph glyph, int width, int height) {
        drawGlyph(canvas, glyph, width, height, false);
    }

    /**
     * Draws an etched-crystal Liquid Glass {@code glyph} centered inside {@code [0, width] × [0, height]}
     * with subtle airy translucency at rest and full luminous brilliance when pressed/activated.
     */
    public void drawGlyph(@NonNull Canvas canvas, @NonNull Glyph glyph, int width, int height, boolean active) {
        if (glyph == Glyph.NONE || width <= 4 || height <= 4) return;
        int fg = active ? GLYPH_ACTIVE_COLOR : GLYPH_IDLE_COLOR;
        mFillPaint.setColor(fg);
        mStrokePaint.setColor(fg);
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float minSide = Math.min(width, height) * 0.90f;
        float shadowDy = Math.max(0.9f, minSide * 0.020f);

        switch (glyph) {
            case UP:
            case DOWN:
            case LEFT:
            case RIGHT:
                drawDirectionalTriangle(canvas, glyph, cx, cy, minSide, shadowDy);
                break;
            case CENTER:
                drawCenterSneakGlyph(canvas, cx, cy, minSide, shadowDy);
                break;
            case JUMP:
                drawJumpGlyph(canvas, cx, cy, minSide, shadowDy);
                break;
            case GEAR:
                drawGearGlyph(canvas, cx, cy, minSide, shadowDy);
                break;
            default:
                break;
        }
    }

    private void drawDirectionalTriangle(@NonNull Canvas canvas, @NonNull Glyph dir,
                                         float cx, float cy, float minSide, float shadowDy) {
        float halfSpan = minSide * 0.165f;
        float corner = Math.max(1.5f, Math.round(minSide * 0.036f));
        if (Float.compare(corner, mCachedCornerRadius) != 0) {
            mCachedCornerRadius = corner;
            CornerPathEffect effect = new CornerPathEffect(corner);
            mFillPaint.setPathEffect(effect);
            mShadowFillPaint.setPathEffect(effect);
        }

        mPath.reset();
        switch (dir) {
            case UP:
                mPath.moveTo(cx, cy - halfSpan * 0.92f);
                mPath.lineTo(cx + halfSpan, cy + halfSpan * 0.78f);
                mPath.lineTo(cx - halfSpan, cy + halfSpan * 0.78f);
                break;
            case DOWN:
                mPath.moveTo(cx, cy + halfSpan * 0.92f);
                mPath.lineTo(cx - halfSpan, cy - halfSpan * 0.78f);
                mPath.lineTo(cx + halfSpan, cy - halfSpan * 0.78f);
                break;
            case LEFT:
                mPath.moveTo(cx - halfSpan * 0.92f, cy);
                mPath.lineTo(cx + halfSpan * 0.78f, cy - halfSpan);
                mPath.lineTo(cx + halfSpan * 0.78f, cy + halfSpan);
                break;
            case RIGHT:
                mPath.moveTo(cx + halfSpan * 0.92f, cy);
                mPath.lineTo(cx - halfSpan * 0.78f, cy + halfSpan);
                mPath.lineTo(cx - halfSpan * 0.78f, cy - halfSpan);
                break;
            default:
                return;
        }
        mPath.close();

        canvas.save();
        canvas.translate(0f, shadowDy);
        canvas.drawPath(mPath, mShadowFillPaint);
        canvas.restore();
        canvas.drawPath(mPath, mFillPaint);
    }

    private void drawCenterSneakGlyph(@NonNull Canvas canvas, float cx, float cy,
                                      float minSide, float shadowDy) {
        float boxHalf = minSide * 0.195f;
        float boxRadius = boxHalf * 0.44f;
        float stroke = Math.max(1.8f, minSide * 0.044f);
        float dotRadius = Math.max(1.6f, minSide * 0.046f);

        mStrokePaint.setStrokeWidth(stroke);
        mShadowStrokePaint.setStrokeWidth(stroke);

        mRect.set(cx - boxHalf, cy - boxHalf + shadowDy, cx + boxHalf, cy + boxHalf + shadowDy);
        canvas.drawRoundRect(mRect, boxRadius, boxRadius, mShadowStrokePaint);
        canvas.drawCircle(cx, cy + shadowDy, dotRadius, mShadowFillPaint);

        mRect.set(cx - boxHalf, cy - boxHalf, cx + boxHalf, cy + boxHalf);
        canvas.drawRoundRect(mRect, boxRadius, boxRadius, mStrokePaint);
        canvas.drawCircle(cx, cy, dotRadius, mFillPaint);
    }

    private void drawJumpGlyph(@NonNull Canvas canvas, float cx, float cy,
                               float minSide, float shadowDy) {
        float boxHalf = minSide * 0.205f;
        float boxRadius = boxHalf * 0.42f;
        float frameStroke = Math.max(1.8f, minSide * 0.042f);
        float arrowStroke = Math.max(1.8f, minSide * 0.044f);

        float shaftTop = cy - boxHalf * 0.48f;
        float shaftBottom = cy + boxHalf * 0.50f;
        float wingDx = boxHalf * 0.40f;
        float wingY = shaftTop + boxHalf * 0.40f;

        // Subtle cool-glass drop shadow pass
        mShadowStrokePaint.setStrokeWidth(frameStroke);
        mRect.set(cx - boxHalf, cy - boxHalf + shadowDy, cx + boxHalf, cy + boxHalf + shadowDy);
        canvas.drawRoundRect(mRect, boxRadius, boxRadius, mShadowStrokePaint);
        mShadowStrokePaint.setStrokeWidth(arrowStroke);
        canvas.drawLine(cx, shaftBottom + shadowDy, cx, shaftTop + shadowDy, mShadowStrokePaint);
        canvas.drawLine(cx - wingDx, wingY + shadowDy, cx, shaftTop + shadowDy, mShadowStrokePaint);
        canvas.drawLine(cx + wingDx, wingY + shadowDy, cx, shaftTop + shadowDy, mShadowStrokePaint);

        // Pure crisp #FAFCFF snow-white glyph pass
        mStrokePaint.setStrokeWidth(frameStroke);
        mRect.set(cx - boxHalf, cy - boxHalf, cx + boxHalf, cy + boxHalf);
        canvas.drawRoundRect(mRect, boxRadius, boxRadius, mStrokePaint);
        mStrokePaint.setStrokeWidth(arrowStroke);
        canvas.drawLine(cx, shaftBottom, cx, shaftTop, mStrokePaint);
        canvas.drawLine(cx - wingDx, wingY, cx, shaftTop, mStrokePaint);
        canvas.drawLine(cx + wingDx, wingY, cx, shaftTop, mStrokePaint);
    }

    private void drawGearGlyph(@NonNull Canvas canvas, float cx, float cy,
                               float minSide, float shadowDy) {
        float ringRadius = minSide * 0.145f;
        float spokeInner = ringRadius + minSide * 0.012f;
        float spokeOuter = minSide * 0.225f;
        float stroke = Math.max(1.6f, minSide * 0.040f);
        float hubRadius = Math.max(1.4f, minSide * 0.036f);

        mStrokePaint.setStrokeWidth(stroke);
        mShadowStrokePaint.setStrokeWidth(stroke);

        // Shadow pass
        canvas.drawCircle(cx, cy + shadowDy, ringRadius, mShadowStrokePaint);
        canvas.drawCircle(cx, cy + shadowDy, hubRadius, mShadowFillPaint);
        for (int i = 0; i < 8; i++) {
            double rad = i * (Math.PI * 0.25);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            canvas.drawLine(
                    cx + cos * spokeInner, cy + sin * spokeInner + shadowDy,
                    cx + cos * spokeOuter, cy + sin * spokeOuter + shadowDy,
                    mShadowStrokePaint
            );
        }

        // Crisp snow-white pass (matches 11_GEAR_Normal.png)
        canvas.drawCircle(cx, cy, ringRadius, mStrokePaint);
        canvas.drawCircle(cx, cy, hubRadius, mFillPaint);
        for (int i = 0; i < 8; i++) {
            double rad = i * (Math.PI * 0.25);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            canvas.drawLine(
                    cx + cos * spokeInner, cy + sin * spokeInner,
                    cx + cos * spokeOuter, cy + sin * spokeOuter,
                    mStrokePaint
            );
        }
    }
}
