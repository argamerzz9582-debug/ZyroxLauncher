package git.artdeell.mojo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Unobtrusive full-screen alignment grid canvas for the Zyrox Touch HUD Editor.
 */
public class ZyroxGridOverlayView extends View {
    private final Paint mMinorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mStepPx;

    public ZyroxGridOverlayView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public ZyroxGridOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZyroxGridOverlayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mStepPx = Math.max(16f, 24f * density);

        mMinorPaint.setColor(0x14FCEAF4);
        mMinorPaint.setStrokeWidth(1f);

        mMajorPaint.setColor(0x2CF9CEE5);
        mMajorPaint.setStrokeWidth(Math.max(1f, 1.05f * density));

        mCenterPaint.setColor(0x6CFFE2F1);
        mCenterPaint.setStrokeWidth(Math.max(1.2f, 1.35f * density));
        mCenterPaint.setPathEffect(new DashPathEffect(new float[]{6f * density, 4f * density}, 0f));

        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int idx = 0;
        for (float x = 0f; x <= w; x += mStepPx, idx++) {
            canvas.drawLine(x, 0f, x, h, (idx % 4 == 0) ? mMajorPaint : mMinorPaint);
        }
        idx = 0;
        for (float y = 0f; y <= h; y += mStepPx, idx++) {
            canvas.drawLine(0f, y, w, y, (idx % 4 == 0) ? mMajorPaint : mMinorPaint);
        }

        // Center alignment crosshair lines
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        canvas.drawLine(cx, 0f, cx, h, mCenterPaint);
        canvas.drawLine(0f, cy, w, cy, mCenterPaint);
    }
}
