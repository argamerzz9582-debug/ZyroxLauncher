package git.artdeell.mojo.ui;

import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.LwjglGlfwKeycode;
import git.artdeell.mojo.input.ControlData;

/**
 * Resolution-independent vector renderer for the signature snow-white ({@code #FAFCFF}) glyphs
 * of the Zyrox Liquid Glass control system:
 * <ul>
 *   <li>{@link Glyph#UP}, {@link Glyph#DOWN}, {@link Glyph#LEFT}, {@link Glyph#RIGHT} — bold rounded directional arrows</li>
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

    /** Precomputed 8-spoke unit cosines/sines so drawGearGlyph performs zero trig on the render loop. */
    private static final float[] GEAR_COS = new float[8];
    private static final float[] GEAR_SIN = new float[8];
    static {
        for (int i = 0; i < 8; i++) {
            double rad = i * (Math.PI * 0.25);
            GEAR_COS[i] = (float) Math.cos(rad);
            GEAR_SIN[i] = (float) Math.sin(rad);
        }
    }

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    private float mCachedCornerRadius = -1f;

    /** High-contrast snow-white idle glyph color (alpha 236) for crystal visibility on any biome. */
    public static final int GLYPH_IDLE_COLOR = 0xECFAFCFF;
    public static final int GLYPH_ACTIVE_COLOR = 0xFFFFFFFF;
    private static final int GLYPH_SHADOW_COLOR = 0x8C050A14;

    public ZyroxControlGlyphRenderer() {
        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(GLYPH_IDLE_COLOR);

        mShadowFillPaint.setStyle(Paint.Style.FILL);
        mShadowFillPaint.setColor(GLYPH_SHADOW_COLOR);

        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setColor(GLYPH_IDLE_COLOR);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mShadowStrokePaint.setStyle(Paint.Style.STROKE);
        mShadowStrokePaint.setColor(GLYPH_SHADOW_COLOR);
        mShadowStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mShadowStrokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Strips Unicode text/emoji variation selectors (FE0E/FE0F) and trims surrounding whitespace. */
    @NonNull
    public static String normalizeGlyphToken(@Nullable String rawName) {
        if (rawName == null) return "";
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.indexOf('\uFE0F') < 0 && trimmed.indexOf('\uFE0E') < 0) {
            return trimmed;
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c != '\uFE0F' && c != '\uFE0E') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Maps a control's configured label and keycodes to its matching Liquid Glass vector icon, or
     * {@link Glyph#NONE} when the control should render crisp snow-white Liquid Glass typography
     * (e.g., {@code CHAT}, {@code DEBUG}, {@code KEYBOARD}, {@code MOUSE}, {@code TAB}, {@code 3RD},
     * {@code PRI}, {@code SEC}, {@code GUI}, {@code INV}, or any custom user label).
     *
     * <p>Covers all directional arrow, jump, sneak, scroll, and menu glyphs used across bundled and
     * community-imported Pojav/Mojo/Zyrox control JSON layouts, including unlabeled arrow/scroll keys.
     */
    @NonNull
    public static Glyph resolveGlyph(@Nullable String rawName, @Nullable int[] keycodes) {
        String name = normalizeGlyphToken(rawName);
        if (name.isEmpty()) {
            return resolveUnlabeledKeyGlyph(keycodes);
        }

        switch (name) {
            case "▲":
            case "△":
            case "▴":
            case "▵":
            case "↑":
            case "⬆":
            case "⇧":
            case "⮝":
            case "∧":
            case "⌃":
            case "ᐱ":
                return Glyph.UP;
            case "▼":
            case "▽":
            case "▾":
            case "▿":
            case "↓":
            case "⬇":
            case "⇩":
            case "⮟":
            case "∨":
            case "⌄":
            case "ᐯ":
                return Glyph.DOWN;
            case "◀":
            case "◁":
            case "◄":
            case "◂":
            case "◃":
            case "←":
            case "⬅":
            case "⇦":
            case "⮜":
            case "❮":
            case "‹":
            case "«":
                return Glyph.LEFT;
            case "▶":
            case "▷":
            case "►":
            case "▸":
            case "▹":
            case "→":
            case "➡":
            case "⇨":
            case "⮞":
            case "❯":
            case "›":
            case "»":
                return Glyph.RIGHT;
            case "◇":
            case "◆":
            case "◈":
            case "⊡":
            case "❖":
            case "⊙":
            case "◉":
                return Glyph.CENTER;
            case "⬛":
            case "⬜":
            case "■":
            case "□":
            case "◼":
            case "◻":
            case "▪":
            case "▫":
                return Glyph.JUMP;
            case "⚙":
                return Glyph.GEAR;
            default:
                break;
        }
        if (("≡".equals(name) || "☰".equals(name)) && containsKey(keycodes, ControlData.SPECIALBTN_MENU)) {
            return Glyph.GEAR;
        }
        return Glyph.NONE;
    }

    /** Infers a crisp directional/action icon when a control JSON omits a text label on an arrow/scroll/menu key. */
    @NonNull
    private static Glyph resolveUnlabeledKeyGlyph(@Nullable int[] keycodes) {
        if (keycodes == null || keycodes.length == 0) return Glyph.NONE;
        int primary = 0;
        for (int k : keycodes) {
            if (k != 0) {
                if (primary != 0) return Glyph.NONE; // multi-key macro: don't guess
                primary = k;
            }
        }
        switch (primary) {
            case ControlData.SPECIALBTN_SCROLLUP:
            case LwjglGlfwKeycode.GLFW_KEY_UP:
                return Glyph.UP;
            case ControlData.SPECIALBTN_SCROLLDOWN:
            case LwjglGlfwKeycode.GLFW_KEY_DOWN:
                return Glyph.DOWN;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT:
                return Glyph.LEFT;
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT:
                return Glyph.RIGHT;
            case LwjglGlfwKeycode.GLFW_KEY_SPACE:
                return Glyph.JUMP;
            case ControlData.SPECIALBTN_MENU:
                return Glyph.GEAR;
            default:
                return Glyph.NONE;
        }
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
     * with crisp directional legibility at rest and full luminous brilliance when pressed/activated.
     */
    public void drawGlyph(@NonNull Canvas canvas, @NonNull Glyph glyph, int width, int height, boolean active) {
        if (glyph == Glyph.NONE || width <= 4 || height <= 4) return;
        int fg = active ? GLYPH_ACTIVE_COLOR : GLYPH_IDLE_COLOR;
        mFillPaint.setColor(fg);
        mStrokePaint.setColor(fg);
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float minSide = Math.min(width, height) * 0.92f;
        float shadowDy = Math.max(1.1f, minSide * 0.024f);

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
        float halfSpan = minSide * 0.235f;
        float corner = Math.max(1.6f, Math.round(minSide * 0.042f));
        if (Float.compare(corner, mCachedCornerRadius) != 0) {
            mCachedCornerRadius = corner;
            CornerPathEffect effect = new CornerPathEffect(corner);
            mFillPaint.setPathEffect(effect);
            mShadowFillPaint.setPathEffect(effect);
        }

        // Visually center triangle centroid so arrows look dead-center inside square/round keys.
        mPath.reset();
        switch (dir) {
            case UP:
                mPath.moveTo(cx, cy - halfSpan * 0.96f);
                mPath.lineTo(cx + halfSpan, cy + halfSpan * 0.76f);
                mPath.lineTo(cx - halfSpan, cy + halfSpan * 0.76f);
                break;
            case DOWN:
                mPath.moveTo(cx, cy + halfSpan * 0.96f);
                mPath.lineTo(cx - halfSpan, cy - halfSpan * 0.76f);
                mPath.lineTo(cx + halfSpan, cy - halfSpan * 0.76f);
                break;
            case LEFT:
                mPath.moveTo(cx - halfSpan * 0.96f, cy);
                mPath.lineTo(cx + halfSpan * 0.76f, cy - halfSpan);
                mPath.lineTo(cx + halfSpan * 0.76f, cy + halfSpan);
                break;
            case RIGHT:
                mPath.moveTo(cx + halfSpan * 0.96f, cy);
                mPath.lineTo(cx - halfSpan * 0.76f, cy + halfSpan);
                mPath.lineTo(cx - halfSpan * 0.76f, cy - halfSpan);
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
        float boxHalf = minSide * 0.22f;
        float boxRadius = boxHalf * 0.44f;
        float stroke = Math.max(2.0f, minSide * 0.048f);
        float dotRadius = Math.max(1.9f, minSide * 0.052f);

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
        float boxHalf = minSide * 0.23f;
        float boxRadius = boxHalf * 0.42f;
        float frameStroke = Math.max(2.0f, minSide * 0.046f);
        float arrowStroke = Math.max(2.0f, minSide * 0.050f);

        float shaftTop = cy - boxHalf * 0.50f;
        float shaftBottom = cy + boxHalf * 0.52f;
        float wingDx = boxHalf * 0.42f;
        float wingY = shaftTop + boxHalf * 0.42f;

        mShadowStrokePaint.setStrokeWidth(frameStroke);
        mRect.set(cx - boxHalf, cy - boxHalf + shadowDy, cx + boxHalf, cy + boxHalf + shadowDy);
        canvas.drawRoundRect(mRect, boxRadius, boxRadius, mShadowStrokePaint);
        mShadowStrokePaint.setStrokeWidth(arrowStroke);
        canvas.drawLine(cx, shaftBottom + shadowDy, cx, shaftTop + shadowDy, mShadowStrokePaint);
        canvas.drawLine(cx - wingDx, wingY + shadowDy, cx, shaftTop + shadowDy, mShadowStrokePaint);
        canvas.drawLine(cx + wingDx, wingY + shadowDy, cx, shaftTop + shadowDy, mShadowStrokePaint);

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
        float ringRadius = minSide * 0.155f;
        float spokeInner = ringRadius + minSide * 0.012f;
        float spokeOuter = minSide * 0.245f;
        float stroke = Math.max(1.8f, minSide * 0.044f);
        float hubRadius = Math.max(1.6f, minSide * 0.040f);

        mStrokePaint.setStrokeWidth(stroke);
        mShadowStrokePaint.setStrokeWidth(stroke);

        canvas.drawCircle(cx, cy + shadowDy, ringRadius, mShadowStrokePaint);
        canvas.drawCircle(cx, cy + shadowDy, hubRadius, mShadowFillPaint);
        for (int i = 0; i < 8; i++) {
            float cos = GEAR_COS[i];
            float sin = GEAR_SIN[i];
            canvas.drawLine(
                    cx + cos * spokeInner, cy + sin * spokeInner + shadowDy,
                    cx + cos * spokeOuter, cy + sin * spokeOuter + shadowDy,
                    mShadowStrokePaint
            );
        }

        canvas.drawCircle(cx, cy, ringRadius, mStrokePaint);
        canvas.drawCircle(cx, cy, hubRadius, mFillPaint);
        for (int i = 0; i < 8; i++) {
            float cos = GEAR_COS[i];
            float sin = GEAR_SIN[i];
            canvas.drawLine(
                    cx + cos * spokeInner, cy + sin * spokeInner,
                    cx + cos * spokeOuter, cy + sin * spokeOuter,
                    mStrokePaint
            );
        }
    }
}
