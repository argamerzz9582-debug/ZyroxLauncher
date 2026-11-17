package git.artdeell.mojo.input.handleview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.ui.ZyroxControlGlyphRenderer;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;

/**
 * In-game quick settings pull button styled in the signature Zyrox Liquid Glass squircle with
 * 8-ray snow-white gear glyph, preserving original position and touch hitbox.
 */
public class DrawerPullButton extends View {
    private ZyroxLiquidGlassDrawable mGlassDrawable;
    private ZyroxControlGlyphRenderer mGlyphRenderer;

    public DrawerPullButton(Context context) {
        super(context);
        init();
    }

    public DrawerPullButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;
        mGlassDrawable = new ZyroxLiquidGlassDrawable(density);
        mGlyphRenderer = new ZyroxControlGlyphRenderer();
        setAlpha(0.82f);
        setContentDescription("In-game settings menu");
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            setPressed(true);
            setAlpha(0.96f);
            invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            setPressed(false);
            setAlpha(0.82f);
            invalidate();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mGlassDrawable != null && mGlassDrawable.setState(getDrawableState())) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4) return;

        // Render centered Liquid Glass squircle pill + signature snow-white 8-ray gear glyph
        int boxSize = Math.min(w, h);
        int left = (w - boxSize) / 2;
        int top = 0;
        mGlassDrawable.setBounds(left, top, left + boxSize, top + boxSize);
        mGlassDrawable.draw(canvas);

        canvas.save();
        canvas.translate(left, top);
        mGlyphRenderer.drawGlyph(canvas, ZyroxControlGlyphRenderer.Glyph.GEAR, boxSize, boxSize);
        canvas.restore();
    }

    // Move the button to the third quarter of the screen (preserves original reachable position)
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View parent = (View) getParent();
        if (parent != null) {
            setTranslationX((int) (parent.getWidth() * 0.25f));
        }
    }
}
