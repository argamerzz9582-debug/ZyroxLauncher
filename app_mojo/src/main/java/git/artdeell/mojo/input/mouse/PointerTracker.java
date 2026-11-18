package git.artdeell.mojo.input.mouse;

import android.view.MotionEvent;

import androidx.annotation.NonNull;

/**
 * Zero-allocation multi-sample touch pointer tracker supporting both current and batched 120Hz /
 * 144Hz / 240Hz {@link MotionEvent} historical digitizer samples.
 */
public class PointerTracker {
    private boolean mColdStart = true;
    private int mTrackedPointerId;
    private int mPointerCount;
    private float mLastX;
    private float mLastY;
    private long mLastEventTimeMs;
    private final float[] mMotionVector = new float[2];

    public void startTracking(@NonNull MotionEvent motionEvent) {
        mColdStart = false;
        mTrackedPointerId = motionEvent.getPointerId(0);
        mPointerCount = motionEvent.getPointerCount();
        mLastX = motionEvent.getX();
        mLastY = motionEvent.getY();
        mLastEventTimeMs = motionEvent.getEventTime();
        mMotionVector[0] = 0f;
        mMotionVector[1] = 0f;
    }

    public void cancelTracking() {
        mColdStart = true;
        mMotionVector[0] = 0f;
        mMotionVector[1] = 0f;
    }

    /** Ensures the tracked pointer index is valid, resetting cleanly on pointer transitions. */
    public int ensurePointerIndex(@NonNull MotionEvent motionEvent) {
        int idx = motionEvent.findPointerIndex(mTrackedPointerId);
        int count = motionEvent.getPointerCount();
        if (idx == -1 || mPointerCount != count || mColdStart) {
            startTracking(motionEvent);
            return -1;
        }
        return idx;
    }

    /** Tracks a single historical sample at {@code historyPos} and populates {@link #getMotionVector()}. */
    public long trackHistoricalSample(@NonNull MotionEvent motionEvent,
                                      int pointerIndex,
                                      int historyPos) {
        float hx = motionEvent.getHistoricalX(pointerIndex, historyPos);
        float hy = motionEvent.getHistoricalY(pointerIndex, historyPos);
        long ht = motionEvent.getHistoricalEventTime(historyPos);
        long dt = Math.max(0L, ht - mLastEventTimeMs);
        mMotionVector[0] = hx - mLastX;
        mMotionVector[1] = hy - mLastY;
        mLastX = hx;
        mLastY = hy;
        mLastEventTimeMs = ht;
        return dt;
    }

    /** Tracks the terminal sample of {@code motionEvent} and populates {@link #getMotionVector()}. */
    public int trackEvent(@NonNull MotionEvent motionEvent) {
        int idx = motionEvent.findPointerIndex(mTrackedPointerId);
        int count = motionEvent.getPointerCount();
        if (idx == -1 || mPointerCount != count || mColdStart) {
            startTracking(motionEvent);
            return 0;
        }
        float trackedX = motionEvent.getX(idx);
        float trackedY = motionEvent.getY(idx);
        mMotionVector[0] = trackedX - mLastX;
        mMotionVector[1] = trackedY - mLastY;
        mLastX = trackedX;
        mLastY = trackedY;
        mLastEventTimeMs = motionEvent.getEventTime();
        return idx;
    }

    public long getLastEventTimeMs() {
        return mLastEventTimeMs;
    }

    @NonNull
    public float[] getMotionVector() {
        return mMotionVector;
    }
}
