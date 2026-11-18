package git.artdeell.mojo.input.mouse;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.game.GameView;
import git.artdeell.mojo.game.platform.Platform;
import git.artdeell.mojo.game.platform.input.PlatformGrabListener;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Hardware-fused 6-DOF {@link Sensor#TYPE_GAME_ROTATION_VECTOR} gyroscope aiming controller with:
 * <ul>
 *   <li>Magnetometer-free accelerometer + gyroscope sensor fusion (zero compass yaw drift)</li>
 *   <li>Continuous radial deadzone filtering with smooth ramp (zero tremor, zero threshold snap)</li>
 *   <li>Adaptive 1-Euro velocity-aware low-pass filter (rock-steady fine aim, instant fast flicks)</li>
 *   <li>Sub-threshold fractional accumulator and orientation/gimbal spike rejection</li>
 * </ul>
 */
public class GyroControl implements SensorEventListener, PlatformGrabListener {
    /** Base radial deadzone in radians per sample (scaled by {@code PREF_ZYROX_GYRO_DEADZONE}). */
    public static final float BASE_RADIAL_DEADZONE_RAD = 0.00085f;
    /** Maximum plausible single-sample angular delta (~37°); rejects sensor reset spikes. */
    public static final float MAX_VALID_DELTA_RAD = 0.65f;
    /** Minimum sample rate floor (~250 Hz = 4000 µs) and ceiling (~50 Hz = 20000 µs). */
    private static final int MIN_SAMPLE_PERIOD_US = 4_000;
    private static final int MAX_SAMPLE_PERIOD_US = 20_000;
    private static final int ROTATION_VECTOR_WARMUP_PERIOD = 3;

    // 1-Euro adaptive filter tuning (when PREF_GYRO_SMOOTHING is enabled)
    private static final float ONE_EURO_MIN_CUTOFF_HZ = 2.4f;
    private static final float ONE_EURO_BETA = 18.0f;
    private static final float NS_TO_SEC = 1.0e-9f;

    private final WindowManager mWindowManager;
    private int mSurfaceRotation = -10;
    private final SensorManager mSensorManager;
    @Nullable private final Sensor mSensor;
    private final OrientationCorrectionListener mCorrectionListener;

    private boolean mShouldHandleEvents;
    private int mWarmup;
    private float mXFactor = 1f;
    private float mYFactor = 1f;
    private boolean mSwapXY;

    private final float[] mPreviousRotation = new float[16];
    private final float[] mCurrentRotation = new float[16];
    private final float[] mAngleDifference = new float[3];
    private final float[] mFilteredDelta = new float[2];
    private final float[] mDeadzonedDelta = new float[2];

    private boolean mFilterPrimed = false;
    private float mPrevFilteredX = 0f;
    private float mPrevFilteredY = 0f;
    private long mLastTimestampNs = 0L;

    /** Sub-pixel fractional cursor remainder preserved across high-frequency samples. */
    private double mSubPixelResidualX = 0.0;
    private double mSubPixelResidualY = 0.0;

    public GyroControl(@NonNull Activity activity) {
        mWindowManager = activity.getWindowManager();
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        Sensor gameRot = mSensorManager != null
                ? mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) : null;
        if (gameRot == null && mSensorManager != null) {
            // Fallback to full rotation vector only on rare devices missing GAME_ROTATION_VECTOR
            gameRot = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        mSensor = gameRot;
        mCorrectionListener = new OrientationCorrectionListener(activity);
        updateOrientation();
    }

    public void enable() {
        if (mSensor == null || mSensorManager == null) return;
        resetFilterState();
        int requestedUs = Math.max(MIN_SAMPLE_PERIOD_US,
                Math.min(MAX_SAMPLE_PERIOD_US, 1000 * LauncherPreferences.PREF_GYRO_SAMPLE_RATE));
        mSensorManager.registerListener(this, mSensor, requestedUs);
        mCorrectionListener.enable();
        mShouldHandleEvents = Platform.isGrabbing();
        Platform.addGrabListener(this);
    }

    public void disable() {
        if (mSensor == null || mSensorManager == null) return;
        mSensorManager.unregisterListener(this);
        mCorrectionListener.disable();
        Platform.removeGrabListener(this);
        resetFilterState();
    }

    /**
     * Pure continuous radial deadzone filter: suppresses sensor noise inside {@code deadzoneRad}
     * and smoothly rescales motion outside {@code deadzoneRad} so there is zero entry jump.
     */
    public static boolean applyRadialDeadzone(float rawDx,
                                              float rawDy,
                                              float deadzoneRad,
                                              @NonNull float[] out2) {
        if (!Float.isFinite(rawDx) || !Float.isFinite(rawDy)
                || Math.abs(rawDx) > MAX_VALID_DELTA_RAD
                || Math.abs(rawDy) > MAX_VALID_DELTA_RAD) {
            out2[0] = 0f;
            out2[1] = 0f;
            return false;
        }
        float dz = Math.max(0f, deadzoneRad);
        float mag = (float) Math.hypot(rawDx, rawDy);
        if (mag <= dz || mag < 1.0e-7f) {
            out2[0] = 0f;
            out2[1] = 0f;
            return false;
        }
        // Smooth radial subtraction preserving exact vector direction without step discontinuity
        float excess = mag - dz;
        // Soft knee over the first 2*dz above threshold for micro-aim precision
        float knee = Math.min(1f, excess / Math.max(1.0e-6f, dz * 1.5f));
        float gain = (excess / mag) * (0.55f + 0.45f * knee);
        out2[0] = rawDx * gain;
        out2[1] = rawDy * gain;
        return true;
    }

    /**
     * Pure 1-Euro adaptive low-pass smoothing coefficient {@code alpha in (0, 1]}.
     * Slow micro-movements yield low {@code alpha} (strong jitter rejection); rapid flicks yield
     * {@code alpha -> 1} (near-zero phase lag).
     */
    public static float computeAdaptiveSmoothingAlpha(float dtSeconds, float angularSpeedRadPerSec) {
        float safeDt = Math.max(0.001f, Math.min(0.05f, dtSeconds));
        float cutoffHz = ONE_EURO_MIN_CUTOFF_HZ + ONE_EURO_BETA * Math.max(0f, angularSpeedRadPerSec);
        float tau = (float) (1.0 / (2.0 * Math.PI * cutoffHz));
        float alpha = 1.0f / (1.0f + tau / safeDt);
        return Math.max(0.08f, Math.min(1.0f, alpha));
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (!mShouldHandleEvents || sensorEvent == null || sensorEvent.values == null) return;
        int type = sensorEvent.sensor.getType();
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR && type != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }

        System.arraycopy(mCurrentRotation, 0, mPreviousRotation, 0, 16);
        SensorManager.getRotationMatrixFromVector(mCurrentRotation, sensorEvent.values);

        float dtSec = 0.008f;
        if (mLastTimestampNs != 0L && sensorEvent.timestamp > mLastTimestampNs) {
            dtSec = Math.max(0.001f, Math.min(0.05f,
                    (sensorEvent.timestamp - mLastTimestampNs) * NS_TO_SEC));
        }
        mLastTimestampNs = sensorEvent.timestamp;

        if (mWarmup > 0) {
            mWarmup--;
            return;
        }

        SensorManager.getAngleChange(mAngleDifference, mCurrentRotation, mPreviousRotation);
        // mAngleDifference[1] = pitch delta, mAngleDifference[2] = roll/yaw delta in device frame
        float rawX = mAngleDifference[1];
        float rawY = mAngleDifference[2];

        // 1. Continuous radial deadzone rejecting stationary gyro noise & hand micro-tremor
        float dzScale = Math.max(0.10f, Math.min(5.0f, LauncherPreferences.PREF_ZYROX_GYRO_DEADZONE));
        boolean aboveDeadzone = applyRadialDeadzone(
                rawX, rawY, BASE_RADIAL_DEADZONE_RAD * dzScale, mDeadzonedDelta);
        if (!aboveDeadzone) {
            // Decay filter memory smoothly toward zero when stationary
            mPrevFilteredX *= 0.5f;
            mPrevFilteredY *= 0.5f;
            return;
        }

        // 2. Adaptive 1-Euro velocity-sensitive smoothing
        if (LauncherPreferences.PREF_GYRO_SMOOTHING) {
            float speed = (float) Math.hypot(mDeadzonedDelta[0], mDeadzonedDelta[1]) / dtSec;
            float alpha = computeAdaptiveSmoothingAlpha(dtSec, speed);
            if (!mFilterPrimed) {
                mPrevFilteredX = mDeadzonedDelta[0];
                mPrevFilteredY = mDeadzonedDelta[1];
                mFilterPrimed = true;
            } else {
                mPrevFilteredX += alpha * (mDeadzonedDelta[0] - mPrevFilteredX);
                mPrevFilteredY += alpha * (mDeadzonedDelta[1] - mPrevFilteredY);
            }
            mFilteredDelta[0] = mPrevFilteredX;
            mFilteredDelta[1] = mPrevFilteredY;
        } else {
            mFilteredDelta[0] = mDeadzonedDelta[0];
            mFilteredDelta[1] = mDeadzonedDelta[1];
        }

        // 3. Orientation-mapped high-precision cursor dispatch with sub-pixel remainder retention
        int width = GameView.getWindowWidth();
        int height = GameView.getWindowHeight();
        if (width <= 0 || height <= 0) return;

        float sens = LauncherPreferences.PREF_GYRO_SENSITIVITY;
        float scaledX = mFilteredDelta[0] * sens;
        float scaledY = mFilteredDelta[1] * sens;

        double mappedDx = -((mSwapXY ? scaledY : scaledX) * mXFactor) * width + mSubPixelResidualX;
        double mappedDy = ((mSwapXY ? scaledX : scaledY) * mYFactor) * height + mSubPixelResidualY;

        if (Math.abs(mappedDx) >= 0.08 || Math.abs(mappedDy) >= 0.08) {
            Platform.cursorX += mappedDx;
            Platform.cursorY += mappedDy;
            mSubPixelResidualX = 0.0;
            mSubPixelResidualY = 0.0;
            Platform.sendCursorPosition();
        } else {
            mSubPixelResidualX = mappedDx;
            mSubPixelResidualY = mappedDy;
        }
    }

    /** Updates sensor-to-screen axis mapping in accordance with display rotation &amp; user invert. */
    public void updateOrientation() {
        int rotation = mWindowManager.getDefaultDisplay().getRotation();
        mSurfaceRotation = rotation;
        switch (rotation) {
            case Surface.ROTATION_0:
                mSwapXY = true;
                mXFactor = 1f;
                mYFactor = 1f;
                break;
            case Surface.ROTATION_90:
                mSwapXY = false;
                mXFactor = -1f;
                mYFactor = 1f;
                break;
            case Surface.ROTATION_180:
                mSwapXY = true;
                mXFactor = -1f;
                mYFactor = -1f;
                break;
            case Surface.ROTATION_270:
            default:
                mSwapXY = false;
                mXFactor = 1f;
                mYFactor = -1f;
                break;
        }
        if (LauncherPreferences.PREF_GYRO_INVERT_X) mXFactor *= -1f;
        if (LauncherPreferences.PREF_GYRO_INVERT_Y) mYFactor *= -1f;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        resetFilterState();
        mShouldHandleEvents = isGrabbing;
    }

    private void resetFilterState() {
        mWarmup = ROTATION_VECTOR_WARMUP_PERIOD;
        mFilterPrimed = false;
        mPrevFilteredX = 0f;
        mPrevFilteredY = 0f;
        mLastTimestampNs = 0L;
        mSubPixelResidualX = 0.0;
        mSubPixelResidualY = 0.0;
    }

    /** Detects live 180° landscape flips during gameplay. */
    class OrientationCorrectionListener extends OrientationEventListener {
        OrientationCorrectionListener(Context context) {
            super(context, SensorManager.SENSOR_DELAY_NORMAL);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            if (!mShouldHandleEvents || orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                return;
            }
            switch (mSurfaceRotation) {
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if ((225 < orientation && orientation < 315)
                            || (45 < orientation && orientation < 135)) {
                        updateOrientation();
                    }
                    break;
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if ((315 < orientation || orientation < 45)
                            || (135 < orientation && orientation < 225)) {
                        updateOrientation();
                    }
                    break;
            }
        }
    }
}
