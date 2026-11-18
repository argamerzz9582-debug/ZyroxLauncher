package git.artdeell.mojo.game;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import git.artdeell.mojo.input.mouse.LeftClickGesture;
import git.artdeell.mojo.input.mouse.RightClickGesture;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Grabbed 3D camera touch processor with full 120Hz / 144Hz / 240Hz historical {@link MotionEvent}
 * sample ingestion and velocity-continuous sub-frame interpolation.
 */
public class InGameEventProcessor extends TouchEventProcessor {
    private final Handler mGestureHandler = new Handler(Looper.getMainLooper());
    private final double mSensitivity;
    private boolean mEventTransitioned = true;
    private final LeftClickGesture mLeftClickGesture = new LeftClickGesture(mGestureHandler);
    private final RightClickGesture mRightClickGesture = new RightClickGesture(mGestureHandler);

    public InGameEventProcessor(GameView hostView, double sensitivity) {
        super(hostView);
        mSensitivity = sensitivity;
    }

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracker.startTracking(motionEvent);
                if (LauncherPreferences.PREF_DISABLE_GESTURES) break;
                mEventTransitioned = false;
                checkGestures();
                break;

            case MotionEvent.ACTION_MOVE: {
                float totalDx = 0f;
                float totalDy = 0f;

                if (LauncherPreferences.PREF_ZYROX_HIGH_RATE_TOUCH) {
                    int ptrIdx = mTracker.ensurePointerIndex(motionEvent);
                    if (ptrIdx >= 0) {
                        int histCount = motionEvent.getHistorySize();
                        for (int h = 0; h < histCount; h++) {
                            long dt = mTracker.trackHistoricalSample(motionEvent, ptrIdx, h);
                            float[] vec = mTracker.getMotionVector();
                            float sDx = (float) (vec[0] * mSensitivity);
                            float sDy = (float) (vec[1] * mSensitivity);
                            totalDx += sDx;
                            totalDy += sDy;
                            applyHighRateMoveSample(sDx, sDy, dt);
                        }
                        long prevTime = mTracker.getLastEventTimeMs();
                        mTracker.trackEvent(motionEvent);
                        long finalDt = Math.max(0L, motionEvent.getEventTime() - prevTime);
                        float[] vec = mTracker.getMotionVector();
                        float fDx = (float) (vec[0] * mSensitivity);
                        float fDy = (float) (vec[1] * mSensitivity);
                        totalDx += fDx;
                        totalDy += fDy;
                        applyHighRateMoveSample(fDx, fDy, finalDt);
                    }
                } else {
                    mTracker.trackEvent(motionEvent);
                    float[] vec = mTracker.getMotionVector();
                    totalDx = (float) (vec[0] * mSensitivity);
                    totalDy = (float) (vec[1] * mSensitivity);
                    applyMoveVector(totalDx, totalDy);
                }

                mLeftClickGesture.setMotion(totalDx, totalDy);
                mRightClickGesture.setMotion(totalDx, totalDy);
                if (!LauncherPreferences.PREF_DISABLE_GESTURES) {
                    checkGestures();
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                prepareForSwitching();
                cancelGestures(false);
                break;
        }
        return true;
    }

    @Override
    public void prepareForSwitching() {
        super.prepareForSwitching();
        cancelGestures(true);
    }

    private void checkGestures() {
        mLeftClickGesture.inputEvent();
        if (!mEventTransitioned) mRightClickGesture.inputEvent();
    }

    private void cancelGestures(boolean isSwitching) {
        mEventTransitioned = true;
        mLeftClickGesture.cancel(isSwitching);
        mRightClickGesture.cancel(isSwitching);
    }
}
