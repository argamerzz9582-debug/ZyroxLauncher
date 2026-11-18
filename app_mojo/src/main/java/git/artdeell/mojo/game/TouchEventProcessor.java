package git.artdeell.mojo.game;

import android.view.MotionEvent;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import git.artdeell.mojo.game.platform.Platform;
import git.artdeell.mojo.input.mouse.PointerTracker;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Base touch processor with 120Hz / 144Hz high-rate historical sample unpacking and distance-exact
 * sub-frame Hermite/cubic-eased camera step interpolation.
 */
public abstract class TouchEventProcessor {
    /** Maximum interpolated sub-steps emitted for a single low-rate digitizer jump. */
    public static final int MAX_INTERPOLATION_SUBSTEPS = 4;
    /** Minimum touch travel (in scaled game cursor units) required before subdividing a sample. */
    private static final float MIN_SUBDIVIDE_DISTANCE = 1.75f;

    protected final PointerTracker mTracker = new PointerTracker();
    private final GameView mHostView;

    /** Target high-refresh cadence in milliseconds (e.g. 6.94ms @ 144Hz, 8.33ms @ 120Hz). */
    private float mTargetSampleIntervalMs = 1000f / 120f;
    private float mPrevVelocityX = 0f;
    private float mPrevVelocityY = 0f;
    private final float[] mSubStepScratch = new float[MAX_INTERPOLATION_SUBSTEPS * 2];

    public TouchEventProcessor(GameView hostView) {
        mHostView = hostView;
    }

    /** Updates the target high-refresh interpolation cadence from the active display mode. */
    public void setTargetRefreshRateHz(float refreshRateHz) {
        float clampedHz = Math.max(60f, Math.min(240f,
                Float.isFinite(refreshRateHz) ? refreshRateHz : 120f));
        // Even on 60–90Hz panels, target at least 120Hz camera sampling when enabled
        float effectiveHz = Math.max(120f, clampedHz);
        mTargetSampleIntervalMs = 1000f / effectiveHz;
    }

    public float getTargetSampleIntervalMs() {
        return mTargetSampleIntervalMs;
    }

    protected void sendTouchCoordinates(float x, float y) {
        if (mHostView.cursorRatioX == 0 || mHostView.cursorRatioY == 0) return;
        Platform.cursorX = x / mHostView.cursorRatioX;
        Platform.cursorY = y / mHostView.cursorRatioY;
        Platform.sendCursorPosition();
    }

    protected void applyMoveVector(float[] vector) {
        applyMoveVector(vector[0], vector[1]);
    }

    protected void applyMoveVector(float x, float y) {
        if (x == 0f && y == 0f) return;
        Platform.cursorX += x * LauncherPreferences.PREF_MOUSESPEED;
        Platform.cursorY += y * LauncherPreferences.PREF_MOUSESPEED;
        Platform.sendCursorPosition();
    }

    /**
     * Dispatches a single touch delta sample, subdividing coarse intervals across 120Hz/144Hz
     * velocity-continuous sub-steps when {@link LauncherPreferences#PREF_ZYROX_HIGH_RATE_TOUCH}
     * is active.
     */
    protected void applyHighRateMoveSample(float dx, float dy, long dtMs) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || (dx == 0f && dy == 0f)) return;
        if (!LauncherPreferences.PREF_ZYROX_HIGH_RATE_TOUCH) {
            applyMoveVector(dx, dy);
            return;
        }
        int steps = computeInterpolatedSubSteps(
                dx, dy, dtMs, mTargetSampleIntervalMs,
                mPrevVelocityX, mPrevVelocityY, mSubStepScratch);
        for (int i = 0; i < steps; i++) {
            applyMoveVector(mSubStepScratch[i * 2], mSubStepScratch[i * 2 + 1]);
        }
        float safeDt = Math.max(2f, Math.min(50f, dtMs > 0L ? dtMs : mTargetSampleIntervalMs));
        mPrevVelocityX = dx / safeDt;
        mPrevVelocityY = dy / safeDt;
    }

    /**
     * Pure distance-preserving 120Hz/144Hz Hermite sub-step interpolator.
     *
     * <p>Guarantees that {@code sum(outPairs[2*i]) == dx} and {@code sum(outPairs[2*i+1]) == dy}
     * exactly (zero cumulative camera drift and zero overshoot), while blending entrance tangent
     * with {@code (prevVelX, prevVelY)} for smooth high-refresh camera pans.
     *
     * @return number of {@code (stepDx, stepDy)} pairs written to {@code outPairs} (1..4)
     */
    public static int computeInterpolatedSubSteps(float dx,
                                                  float dy,
                                                  long dtMs,
                                                  float targetIntervalMs,
                                                  float prevVelX,
                                                  float prevVelY,
                                                  @NonNull float[] outPairs) {
        float safeInterval = Math.max(4.0f, targetIntervalMs);
        float dist = (float) Math.hypot(dx, dy);
        if (dtMs <= 0L || dist < MIN_SUBDIVIDE_DISTANCE || dtMs <= Math.round(safeInterval * 1.25f)) {
            outPairs[0] = dx;
            outPairs[1] = dy;
            return 1;
        }

        int steps = Math.min(MAX_INTERPOLATION_SUBSTEPS,
                Math.max(1, Math.round(dtMs / safeInterval)));
        if (steps <= 1) {
            outPairs[0] = dx;
            outPairs[1] = dy;
            return 1;
        }

        // Clamped entrance tangent (m0) blended with prior sample velocity; exit tangent (m1) = chord
        float dt = (float) dtMs;
        float m0x = clampTangent(0.5f * (prevVelX * dt + dx), dx);
        float m0y = clampTangent(0.5f * (prevVelY * dt + dy), dy);

        float prevPosX = 0f;
        float prevPosY = 0f;
        for (int s = 1; s <= steps; s++) {
            final float curX;
            final float curY;
            if (s == steps) {
                // Exact endpoint lock guarantees zero cumulative drift
                curX = dx;
                curY = dy;
            } else {
                float t = (float) s / (float) steps;
                curX = evalHermiteUnit(t, dx, m0x, dx);
                curY = evalHermiteUnit(t, dy, m0y, dy);
            }
            outPairs[(s - 1) * 2] = curX - prevPosX;
            outPairs[(s - 1) * 2 + 1] = curY - prevPosY;
            prevPosX = curX;
            prevPosY = curY;
        }
        return steps;
    }

    /** Evaluates cubic Hermite spline from {@code p0=0} to {@code p1} on {@code t in [0, 1]}. */
    private static float evalHermiteUnit(float t, float p1, float m0, float m1) {
        float t2 = t * t;
        float t3 = t2 * t;
        float h10 = t3 - 2f * t2 + t;
        float h01 = -2f * t3 + 3f * t2;
        float h11 = t3 - t2;
        return h10 * m0 + h01 * p1 + h11 * m1;
    }

    /** Fritsch–Carlson monotonicity clamp preventing camera overshoot on direction reversals. */
    private static float clampTangent(float candidate, float chord) {
        if (chord == 0f || candidate * chord <= 0f) return 0f;
        float limit = 2.0f * Math.abs(chord);
        return Math.max(-limit, Math.min(limit, candidate));
    }

    @CallSuper
    public void prepareForSwitching() {
        mTracker.cancelTracking();
        mPrevVelocityX = 0f;
        mPrevVelocityY = 0f;
    }

    public abstract boolean processTouchEvent(MotionEvent motionEvent);
}
