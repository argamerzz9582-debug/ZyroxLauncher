package git.artdeell.mojo.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.utils.JSONUtils;

/**
 * Freely draggable, safe-area &amp; control-collision-aware, Liquid Glass Performance Telemetry HUD.
 *
 * <p>Features:
 * <ul>
 *   <li><b>Multi-state touch controller ({@link TouchState}):</b> Smooth drag-anywhere placement,
 *       tap-to-toggle compact mini-pill, magnetic edge snapping, safe-area &amp; notch clamping,
 *       automatic collision avoidance against top toolbar and {@code ControlLayout} buttons, and
 *       click-through position lock mode.</li>
 *   <li><b>Persistent customization:</b> Scale ({@code 50%..150%}), backdrop opacity
 *       ({@code 10%..100%}), lock toggle, compact mini-pill toggle, and normalized X/Y coordinates
 *       persisted via {@link LauncherPreferences}.</li>
 *   <li><b>Rich real-time telemetry:</b> Live FPS, 1% Low FPS, zero-allocation Canvas frametime
 *       graph, Java Heap &amp; ART GC activity, Linux native RSS memory, device RAM, and Android
 *       thermal throttling state + battery temperature.</li>
 * </ul>
 */
public class PerformanceHudOverlay extends AppCompatTextView
        implements Choreographer.FrameCallback, View.OnTouchListener {

    /** Multi-state interactive touch machine for free HUD dragging and mini-pill toggling. */
    public enum TouchState {
        IDLE,
        PRESSED,
        DRAGGING,
        SETTLING
    }

    /** Callback supplying live obstacle rectangles (toolbar + custom controls) in parent coords. */
    public interface ObstacleProvider {
        void collectObstacles(@NonNull List<RectF> outRects);
    }

    private static final long UPDATE_INTERVAL_MS = 750L;
    private static final int FRAME_RING_CAPACITY = 240;
    private static final int GRAPH_SAMPLES = 72;
    private static final float BASE_TEXT_SP = 9.2f;
    private static final float BASE_PAD_H_DP = 9f;
    private static final float BASE_PAD_V_DP = 5f;
    private static final float BASE_GRAPH_HEIGHT_DP = 24f;
    private static final float EDGE_SNAP_THRESHOLD_DP = 18f;
    private static final float OBSTACLE_CLEARANCE_DP = 6f;

    private final float mDensity;
    private final int mTouchSlop;
    private final float mEdgeSnapPx;
    private final float mObstacleClearancePx;
    private final ZyroxLiquidGlassDrawable mGlassBg;

    private final ActivityManager mActivityManager;
    private final ActivityManager.MemoryInfo mMemInfo = new ActivityManager.MemoryInfo();
    @Nullable
    private final PowerManager mPowerManager;
    private final StringBuilder mTextBuilder = new StringBuilder(220);

    // Zero-allocation frame telemetry ring buffers
    private final long[] mFrameDeltaRingNs = new long[FRAME_RING_CAPACITY];
    private final long[] mSortScratchNs = new long[FRAME_RING_CAPACITY];
    private int mFrameRingCount = 0;
    private int mFrameRingCursor = 0;

    private final float[] mGraphFrameMs = new float[GRAPH_SAMPLES];
    private int mGraphCount = 0;
    private int mGraphCursor = 0;

    // Zero-allocation Canvas graph paints
    private final Paint mGraphBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGraphGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGraphBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGraphLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDragOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mGraphRect = new RectF();
    private final RectF mSelfRect = new RectF();
    private final Path mGraphPath = new Path();
    private final List<RectF> mObstaclesScratch = new ArrayList<>(32);

    // Customization & placement state
    private int mScalePercent = 100;
    private int mOpacityPercent = 78;
    private boolean mLocked = false;
    private boolean mCompactMode = false;
    private float mNormalizedX = -1f;
    private float mNormalizedY = -1f;
    private final Rect mSafeInsets = new Rect();
    @Nullable
    private ObstacleProvider mObstacleProvider;

    // Multi-state drag, single-tap (Quick Settings) & long-press (compact/hide cycle) tracking
    @NonNull
    private TouchState mTouchState = TouchState.IDLE;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private float mDownRawX;
    private float mDownRawY;
    private float mStartViewX;
    private float mStartViewY;
    private boolean mLongPressConsumed = false;
    @Nullable
    private Runnable mOnQuickSettingsAction;
    @Nullable
    private Runnable mOnHudHiddenAction;

    // Lightweight multiplayer server Ping telemetry (sampled off-thread only when server set)
    @Nullable
    private volatile String mPingTargetAddress;
    private volatile int mLastServerPingMs = -1;
    private long mLastPingProbeElapsedMs = 0L;
    private volatile boolean mPingProbeInFlight = false;

    private final Runnable mLongPressRunnable = () -> {
        if (mTouchState == TouchState.PRESSED && !mLocked && getVisibility() == VISIBLE) {
            mLongPressConsumed = true;
            mTouchState = TouchState.IDLE;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            cycleCompactOrHiddenOnLongPress();
        }
    };

    /** Binds single-tap action (opens {@code QuickSettingSideDialog} in-game). */
    public void setOnQuickSettingsAction(@Nullable Runnable action) {
        mOnQuickSettingsAction = action;
    }

    /** Binds long-press hide callback so {@code GameHudCoordinator} stays synced with prefs. */
    public void setOnHudHiddenAction(@Nullable Runnable action) {
        mOnHudHiddenAction = action;
    }

    /** Configures an optional multiplayer host[:port] for live HUD Ping (ms) measurement. */
    public void setPingTargetAddress(@Nullable String serverAddress) {
        mPingTargetAddress = (serverAddress != null && !serverAddress.trim().isEmpty())
                ? serverAddress.trim() : null;
        mLastServerPingMs = -1;
    }

    /** Long-press cycle: Full HUD -> Compact Pill -> Hidden (restore anytime from side drawer). */
    private void cycleCompactOrHiddenOnLongPress() {
        if (!mCompactMode) {
            setCompactMode(true, true);
        } else {
            setCompactMode(false, true);
            if (mOnHudHiddenAction != null) {
                mOnHudHiddenAction.run();
            } else {
                setHudEnabled(false);
            }
            android.widget.Toast.makeText(
                    getContext().getApplicationContext(),
                    "HUD hidden — re-enable anytime in Quick Settings",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // Live runtime metrics state
    private String mRendererLabel = "auto";
    private int mConfiguredHeapMb = 1024;
    private boolean mMonitoring = false;
    private long mSessionStartMs = 0L;
    private long mWindowStartNs = 0L;
    private long mLastFrameNs = 0L;
    private int mWindowFrames = 0;
    private long mWindowWorstNs = 0L;

    // Java Heap & ART GC delta state
    private long mLastGcCount = -1L;
    private long mLastGcTimeMs = -1L;
    private long mLastUsedHeapBytes = -1L;
    private int mRecentGcEvents = 0;
    private long mRecentGcPauseMs = 0L;

    // Session summary counters
    private double mSumFps = 0.0;
    private double mSumOnePctLow = 0.0;
    private int mFpsSamples = 0;
    private int mMinFreeRamMb = Integer.MAX_VALUE;
    private int mPeakNativeRssMb = 0;
    private String mPeakThermalLabel = "OK";
    private int mPeakThermalRank = -1;
    @Nullable private String mThermalGovernorBadge;

    /** Updates the live Thermal &amp; Battery Governor status badge (e.g. {@code "THM 80%"}). */
    public void setThermalGovernorBadge(@Nullable String badge) {
        mThermalGovernorBadge = (badge != null && !badge.trim().isEmpty()) ? badge.trim() : null;
        if (mMonitoring && getVisibility() == VISIBLE) {
            invalidate();
        }
    }

    private final Runnable mSampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mMonitoring || getVisibility() != VISIBLE) return;
            refreshDisplayMetrics();
            postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    public PerformanceHudOverlay(@NonNull Context context) {
        super(context);
        mDensity = Math.max(1f, context.getResources().getDisplayMetrics().density);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mEdgeSnapPx = EDGE_SNAP_THRESHOLD_DP * mDensity;
        mObstacleClearancePx = OBSTACLE_CLEARANCE_DP * mDensity;

        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPowerManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? (PowerManager) context.getSystemService(Context.POWER_SERVICE) : null;

        mGraphBgPaint.setStyle(Paint.Style.FILL);
        mGraphBgPaint.setColor(0x44050B16);

        mGraphGuidePaint.setStyle(Paint.Style.STROKE);
        mGraphGuidePaint.setStrokeWidth(Math.max(1f, 0.75f * mDensity));
        mGraphGuidePaint.setColor(0x48DCEBFF);

        mGraphBarPaint.setStyle(Paint.Style.FILL);

        mGraphLinePaint.setStyle(Paint.Style.STROKE);
        mGraphLinePaint.setStrokeWidth(Math.max(1.2f, 1.15f * mDensity));
        mGraphLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mGraphLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mGraphLinePaint.setColor(0xE87CE8FF);

        mDragOutlinePaint.setStyle(Paint.Style.STROKE);
        mDragOutlinePaint.setStrokeWidth(1.8f * mDensity);
        mDragOutlinePaint.setColor(0xE87CD8FF);

        setGravity(Gravity.START | Gravity.TOP);
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        setShadowLayer(3f, 0f, 1f, Color.parseColor("#C009101C"));
        setIncludeFontPadding(false);
        setElevation(8f * mDensity);

        mGlassBg = new ZyroxLiquidGlassDrawable(mDensity, true);
        setBackground(mGlassBg);
        super.setOnTouchListener(this);

        syncFromPreferences();
        setVisibility(GONE);
    }

    /** Reloads scale, backdrop opacity, lock, compact state, and saved coordinates from prefs. */
    public void syncFromPreferences() {
        mNormalizedX = LauncherPreferences.sanitizeHudCoord(LauncherPreferences.PREF_ZYROX_HUD_X);
        mNormalizedY = LauncherPreferences.sanitizeHudCoord(LauncherPreferences.PREF_ZYROX_HUD_Y);
        mScalePercent = LauncherPreferences.clampHudScale(LauncherPreferences.PREF_ZYROX_HUD_SCALE);
        mOpacityPercent = LauncherPreferences.clampHudOpacity(LauncherPreferences.PREF_ZYROX_HUD_OPACITY);
        mLocked = LauncherPreferences.PREF_ZYROX_HUD_LOCKED;
        mCompactMode = LauncherPreferences.PREF_ZYROX_HUD_COMPACT;
        applyVisualStyle();
        post(this::applyClampedCollisionFreePlacement);
    }

    public void setObstacleProvider(@Nullable ObstacleProvider provider) {
        mObstacleProvider = provider;
    }

    /** Updates safe-area display cutout and system-bar insets and re-clamps HUD coordinates. */
    public void setSafeAreaInsets(int left, int top, int right, int bottom) {
        int margin = Math.round(6f * mDensity);
        int nl = Math.max(margin, left + margin / 2);
        int nt = Math.max(margin, top + margin / 2);
        int nr = Math.max(margin, right + margin / 2);
        int nb = Math.max(margin, bottom + margin / 2);
        if (mSafeInsets.left != nl || mSafeInsets.top != nt
                || mSafeInsets.right != nr || mSafeInsets.bottom != nb) {
            mSafeInsets.set(nl, nt, nr, nb);
            post(this::applyClampedCollisionFreePlacement);
        }
    }

    public void setScalePercent(int scalePercent, boolean persist) {
        int clamped = LauncherPreferences.clampHudScale(scalePercent);
        mScalePercent = clamped;
        if (persist) {
            LauncherPreferences.persistHudScale(clamped);
        }
        applyVisualStyle();
        post(this::applyClampedCollisionFreePlacement);
    }

    public int getScalePercent() {
        return mScalePercent;
    }

    public void setBackdropOpacityPercent(int opacityPercent, boolean persist) {
        int clamped = LauncherPreferences.clampHudOpacity(opacityPercent);
        mOpacityPercent = clamped;
        if (persist) {
            LauncherPreferences.persistHudOpacity(clamped);
        }
        mGlassBg.setHudBackdropOpacityPercent(clamped);
        invalidate();
    }

    public int getBackdropOpacityPercent() {
        return mOpacityPercent;
    }

    public void setPositionLocked(boolean locked, boolean persist) {
        mLocked = locked;
        mTouchState = TouchState.IDLE;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        setClickable(!locked);
        if (persist) {
            LauncherPreferences.persistHudLocked(locked);
        }
        invalidate();
    }

    public boolean isPositionLocked() {
        return mLocked;
    }

    public void setCompactMode(boolean compact, boolean persist) {
        if (mCompactMode != compact) {
            mCompactMode = compact;
            if (persist) {
                LauncherPreferences.persistHudCompact(compact);
            }
            applyVisualStyle();
            if (mMonitoring && getVisibility() == VISIBLE) {
                refreshDisplayMetrics();
            }
            post(this::applyClampedCollisionFreePlacement);
        }
    }

    public boolean isCompactMode() {
        return mCompactMode;
    }

    /** Resets HUD to the default collision-free safe top-right/top-center slot and persists. */
    public void resetPosition() {
        mNormalizedX = -1f;
        mNormalizedY = -1f;
        LauncherPreferences.resetHudPosition();
        applyClampedCollisionFreePlacement();
    }

    @NonNull
    public TouchState getTouchState() {
        return mTouchState;
    }

    private void applyVisualStyle() {
        float scale = mScalePercent / 100f;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SP * scale);
        int padH = Math.round(BASE_PAD_H_DP * mDensity * scale);
        int padV = Math.round(BASE_PAD_V_DP * mDensity * scale);
        int graphExtraBottom = mCompactMode
                ? 0
                : Math.round((BASE_GRAPH_HEIGHT_DP + 5f) * mDensity * scale);
        setPadding(padH, padV, padH, padV + graphExtraBottom);
        mGlassBg.setHudBackdropOpacityPercent(mOpacityPercent);
        mGlassBg.setAlpha(255);
        setClickable(!mLocked);
        setFocusable(false);
        requestLayout();
        invalidate();
    }

    // =============================================================================================
    // MULTI-STATE DRAG, EDGE-SNAP & OBSTACLE-AVOIDANCE TOUCH CONTROLLER
    // =============================================================================================

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mLocked || getVisibility() != VISIBLE) {
            mTouchState = TouchState.IDLE;
            return false; // Pass touches straight through to game surface & controls when locked
        }

        ViewParent vp = getParent();
        if (!(vp instanceof ViewGroup)) {
            return false;
        }
        ViewGroup parent = (ViewGroup) vp;
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mActivePointerId = event.getPointerId(0);
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mStartViewX = getX();
                mStartViewY = getY();
                mLongPressConsumed = false;
                mTouchState = TouchState.PRESSED;
                removeCallbacks(mLongPressRunnable);
                postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                parent.requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mLongPressConsumed) return true;
                if (mTouchState != TouchState.PRESSED && mTouchState != TouchState.DRAGGING) {
                    return false;
                }
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) return true;
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (mTouchState == TouchState.PRESSED
                        && Math.hypot(dx, dy) >= mTouchSlop) {
                    removeCallbacks(mLongPressRunnable);
                    mTouchState = TouchState.DRAGGING;
                }
                if (mTouchState == TouchState.DRAGGING) {
                    float[] clamped = clampAndSnapPosition(
                            mStartViewX + dx,
                            mStartViewY + dy,
                            getWidth(),
                            getHeight(),
                            parent.getWidth(),
                            parent.getHeight(),
                            mSafeInsets,
                            0f); // Live smooth drag; snap applied cleanly on release
                    setX(clamped[0]);
                    setY(clamped[1]);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(mLongPressRunnable);
                parent.requestDisallowInterceptTouchEvent(false);
                if (mLongPressConsumed) {
                    mLongPressConsumed = false;
                    mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                    mTouchState = TouchState.IDLE;
                    invalidate();
                    return true;
                }
                TouchState prior = mTouchState;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                if (prior == TouchState.PRESSED && action == MotionEvent.ACTION_UP) {
                    // Single-tap opens QuickSettingSideDialog (or toggles compact pill as fallback)
                    mTouchState = TouchState.IDLE;
                    if (mOnQuickSettingsAction != null) {
                        mOnQuickSettingsAction.run();
                    } else {
                        setCompactMode(!mCompactMode, true);
                    }
                    invalidate();
                    return true;
                }
                if (prior == TouchState.DRAGGING) {
                    mTouchState = TouchState.SETTLING;
                    settleAndPersistPosition(parent);
                    mTouchState = TouchState.IDLE;
                    invalidate();
                    return true;
                }
                mTouchState = TouchState.IDLE;
                invalidate();
                return false;
            }
            default:
                return mTouchState != TouchState.IDLE;
        }
    }

    private void settleAndPersistPosition(@NonNull ViewGroup parent) {
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (pw <= 0 || ph <= 0 || vw <= 0 || vh <= 0) return;

        float[] snapped = clampAndSnapPosition(
                getX(), getY(), vw, vh, pw, ph, mSafeInsets,
                LauncherPreferences.PREF_ZYROX_HUD_SNAP_GRID ? mEdgeSnapPx : mEdgeSnapPx * 0.6f);

        collectObstaclesIntoScratch();
        float[] safe = resolveCollisionFreePosition(
                snapped[0], snapped[1], vw, vh, pw, ph,
                mSafeInsets, mObstaclesScratch, mObstacleClearancePx);

        animateOrSetPosition(safe[0], safe[1]);
        saveNormalizedCoordinates(safe[0], safe[1], vw, vh, pw, ph);
    }

    /** Re-evaluates safe-area clamping and toolbar/control collision clearance (e.g. on resize). */
    public void applyClampedCollisionFreePlacement() {
        ViewParent vp = getParent();
        if (!(vp instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) vp;
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (pw <= 0 || ph <= 0 || vw <= 0 || vh <= 0) return;

        ensureSafeInsetsInitialized();

        float minX = mSafeInsets.left;
        float maxX = Math.max(minX, pw - mSafeInsets.right - vw);
        float minY = mSafeInsets.top;
        float maxY = Math.max(minY, ph - mSafeInsets.bottom - vh);

        float desiredX;
        float desiredY;
        if (mNormalizedX >= 0f && mNormalizedY >= 0f) {
            desiredX = minX + mNormalizedX * Math.max(0f, maxX - minX);
            desiredY = minY + mNormalizedY * Math.max(0f, maxY - minY);
        } else {
            // Default position: top-right inside safe area, automatically shifted off any toolbar/button
            desiredX = maxX;
            desiredY = minY;
        }

        float[] clamped = clampAndSnapPosition(
                desiredX, desiredY, vw, vh, pw, ph, mSafeInsets, 0f);
        collectObstaclesIntoScratch();
        float[] resolved = resolveCollisionFreePosition(
                clamped[0], clamped[1], vw, vh, pw, ph,
                mSafeInsets, mObstaclesScratch, mObstacleClearancePx);
        setX(resolved[0]);
        setY(resolved[1]);
    }

    private void ensureSafeInsetsInitialized() {
        if (mSafeInsets.left == 0 && mSafeInsets.top == 0
                && mSafeInsets.right == 0 && mSafeInsets.bottom == 0) {
            int m = Math.round(6f * mDensity);
            mSafeInsets.set(m, m, m, m);
        }
    }

    private void animateOrSetPosition(float targetX, float targetY) {
        if (LauncherPreferences.isReducedMotionEnabled(getContext())
                || Math.hypot(targetX - getX(), targetY - getY()) < 2f) {
            setX(targetX);
            setY(targetY);
        } else {
            animate().cancel();
            animate().x(targetX).y(targetY).setDuration(130L).start();
        }
    }

    private void saveNormalizedCoordinates(float x, float y, int vw, int vh, int pw, int ph) {
        float spanX = Math.max(1f, (pw - mSafeInsets.right - vw) - mSafeInsets.left);
        float spanY = Math.max(1f, (ph - mSafeInsets.bottom - vh) - mSafeInsets.top);
        mNormalizedX = Math.max(0f, Math.min(1f, (x - mSafeInsets.left) / spanX));
        mNormalizedY = Math.max(0f, Math.min(1f, (y - mSafeInsets.top) / spanY));
        LauncherPreferences.persistHudPosition(mNormalizedX, mNormalizedY);
    }

    private void collectObstaclesIntoScratch() {
        mObstaclesScratch.clear();
        if (mObstacleProvider != null) {
            try {
                mObstacleProvider.collectObstacles(mObstaclesScratch);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && mTouchState != TouchState.DRAGGING) {
            post(this::applyClampedCollisionFreePlacement);
        }
    }

    // =============================================================================================
    // PURE UNIT-TESTABLE GEOMETRY & TELEMETRY ALGORITHMS
    // =============================================================================================

    /**
     * Clamps {@code (x, y)} strictly within {@code [insets.left .. parentW - insets.right - viewW]}
     * and applies magnetic left/right/top/bottom edge-snapping when within {@code snapThresholdPx}.
     */
    @NonNull
    public static float[] clampAndSnapPosition(float x, float y,
                                               int viewW, int viewH,
                                               int parentW, int parentH,
                                               @Nullable Rect safeInsets,
                                               float snapThresholdPx) {
        int inL = safeInsets != null ? Math.max(0, safeInsets.left) : 0;
        int inT = safeInsets != null ? Math.max(0, safeInsets.top) : 0;
        int inR = safeInsets != null ? Math.max(0, safeInsets.right) : 0;
        int inB = safeInsets != null ? Math.max(0, safeInsets.bottom) : 0;

        float minX = inL;
        float maxX = Math.max(minX, parentW - inR - Math.max(0, viewW));
        float minY = inT;
        float maxY = Math.max(minY, parentH - inB - Math.max(0, viewH));

        float cx = Math.max(minX, Math.min(maxX, x));
        float cy = Math.max(minY, Math.min(maxY, y));

        if (snapThresholdPx > 0f) {
            if (Math.abs(cx - minX) <= snapThresholdPx) {
                cx = minX;
            } else if (Math.abs(maxX - cx) <= snapThresholdPx) {
                cx = maxX;
            }
            if (Math.abs(cy - minY) <= snapThresholdPx) {
                cy = minY;
            } else if (Math.abs(maxY - cy) <= snapThresholdPx) {
                cy = maxY;
            }
        }
        return new float[]{cx, cy};
    }

    /**
     * Finds the nearest boundary-clamped position to {@code (desiredX, desiredY)} that does not
     * overlap any rectangle in {@code obstacles} (top toolbar, drawer pull button, or visible
     * custom control buttons in {@code ControlLayout}).
     */
    @NonNull
    public static float[] resolveCollisionFreePosition(float desiredX, float desiredY,
                                                       int viewW, int viewH,
                                                       int parentW, int parentH,
                                                       @Nullable Rect safeInsets,
                                                       @Nullable List<RectF> obstacles,
                                                       float clearancePx) {
        float[] base = clampAndSnapPosition(
                desiredX, desiredY, viewW, viewH, parentW, parentH, safeInsets, 0f);
        if (obstacles == null || obstacles.isEmpty() || viewW <= 0 || viewH <= 0) {
            return base;
        }
        if (!intersectsAny(base[0], base[1], viewW, viewH, obstacles, clearancePx)) {
            return base;
        }

        int inL = safeInsets != null ? Math.max(0, safeInsets.left) : 0;
        int inT = safeInsets != null ? Math.max(0, safeInsets.top) : 0;
        int inR = safeInsets != null ? Math.max(0, safeInsets.right) : 0;
        int inB = safeInsets != null ? Math.max(0, safeInsets.bottom) : 0;
        float minX = inL;
        float maxX = Math.max(minX, parentW - inR - viewW);
        float minY = inT;
        float maxY = Math.max(minY, parentH - inB - viewH);
        float pad = Math.max(0f, clearancePx);

        float bestX = base[0];
        float bestY = base[1];
        float bestDistSq = Float.MAX_VALUE;
        boolean foundClean = false;

        // 1. Evaluate exact tangent push-outs around each obstacle (down, up, left, right)
        for (int i = 0; i < obstacles.size(); i++) {
            RectF ob = obstacles.get(i);
            if (ob == null || ob.isEmpty()) continue;
            float[][] candidates = {
                    {base[0], ob.bottom + pad},
                    {base[0], ob.top - viewH - pad},
                    {ob.right + pad, base[1]},
                    {ob.left - viewW - pad, base[1]}
            };
            for (float[] c : candidates) {
                float cx = Math.max(minX, Math.min(maxX, c[0]));
                float cy = Math.max(minY, Math.min(maxY, c[1]));
                if (!intersectsAny(cx, cy, viewW, viewH, obstacles, pad)) {
                    float dx = cx - base[0];
                    float dy = cy - base[1];
                    float d2 = dx * dx + dy * dy;
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        bestX = cx;
                        bestY = cy;
                        foundClean = true;
                    }
                }
            }
        }
        if (foundClean) {
            return new float[]{bestX, bestY};
        }

        // 2. Fallback: scan an 8×6 safe-area lattice and select closest collision-free slot
        int cols = 8;
        int rows = 6;
        float bestOverlapArea = Float.MAX_VALUE;
        for (int r = 0; r <= rows; r++) {
            float cy = minY + (maxY - minY) * ((float) r / rows);
            for (int c = 0; c <= cols; c++) {
                float cx = minX + (maxX - minX) * ((float) c / cols);
                float overlap = computeTotalOverlapArea(cx, cy, viewW, viewH, obstacles, pad);
                float dx = cx - base[0];
                float dy = cy - base[1];
                float d2 = dx * dx + dy * dy;
                if (overlap <= 0.01f) {
                    if (!foundClean || d2 < bestDistSq) {
                        foundClean = true;
                        bestDistSq = d2;
                        bestX = cx;
                        bestY = cy;
                    }
                } else if (!foundClean && overlap < bestOverlapArea) {
                    bestOverlapArea = overlap;
                    bestX = cx;
                    bestY = cy;
                }
            }
        }
        return new float[]{bestX, bestY};
    }

    private static boolean intersectsAny(float x, float y, int w, int h,
                                         @NonNull List<RectF> obstacles, float pad) {
        float l = x - pad;
        float t = y - pad;
        float r = x + w + pad;
        float b = y + h + pad;
        for (int i = 0; i < obstacles.size(); i++) {
            RectF o = obstacles.get(i);
            if (o != null && l < o.right && r > o.left && t < o.bottom && b > o.top) {
                return true;
            }
        }
        return false;
    }

    private static float computeTotalOverlapArea(float x, float y, int w, int h,
                                                 @NonNull List<RectF> obstacles, float pad) {
        float l = x - pad;
        float t = y - pad;
        float r = x + w + pad;
        float b = y + h + pad;
        float sum = 0f;
        for (int i = 0; i < obstacles.size(); i++) {
            RectF o = obstacles.get(i);
            if (o == null) continue;
            float iw = Math.min(r, o.right) - Math.max(l, o.left);
            float ih = Math.min(b, o.bottom) - Math.max(t, o.top);
            if (iw > 0f && ih > 0f) {
                sum += iw * ih;
            }
        }
        return sum;
    }

    /**
     * Computes real-time 1% Low FPS from the slowest 1% frame durations in {@code deltasNs}
     * using {@code sortScratch} so runtime telemetry performs zero heap allocation.
     */
    public static double computeOnePercentLowFps(@NonNull long[] deltasNs,
                                                 int count,
                                                 @Nullable long[] sortScratch) {
        int n = Math.min(count, deltasNs.length);
        if (n <= 0) return 0.0;
        long[] buf = (sortScratch != null && sortScratch.length >= n)
                ? sortScratch : new long[n];
        int valid = 0;
        for (int i = 0; i < n; i++) {
            long d = deltasNs[i];
            if (d >= 500_000L && d <= 2_000_000_000L) {
                buf[valid++] = d;
            }
        }
        if (valid == 0) return 0.0;
        Arrays.sort(buf, 0, valid);
        int tailCount = Math.max(1, (int) Math.ceil(valid * 0.01));
        long worstSumNs = 0L;
        for (int i = valid - tailCount; i < valid; i++) {
            worstSumNs += buf[i];
        }
        double avgWorstNs = (double) worstSumNs / (double) tailCount;
        return avgWorstNs > 0.0 ? (1_000_000_000.0 / avgWorstNs) : 0.0;
    }

    /** Parses Linux {@code VmRSS} (resident set size) in MB from {@code /proc/self/status} lines. */
    public static int parseVmRssMb(@Nullable String statusContent) {
        if (statusContent == null || statusContent.isEmpty()) return -1;
        int idx = statusContent.indexOf("VmRSS:");
        if (idx < 0) return -1;
        int p = idx + 6;
        int len = statusContent.length();
        while (p < len && (statusContent.charAt(p) == ' ' || statusContent.charAt(p) == '\t')) {
            p++;
        }
        long kb = 0L;
        boolean any = false;
        while (p < len) {
            char ch = statusContent.charAt(p);
            if (ch >= '0' && ch <= '9') {
                kb = kb * 10L + (ch - '0');
                any = true;
                p++;
            } else {
                break;
            }
        }
        return any ? (int) (kb / 1024L) : -1;
    }

    // =============================================================================================
    // REAL-TIME TELEMETRY SAMPLING (FPS, 1% LOW, FRAMETIME GRAPH, HEAP GC, NATIVE RSS, THERMAL)
    // =============================================================================================

    public void configureSession(@NonNull String rendererTag, int effectiveHeapMb) {
        mRendererLabel = rendererTag;
        mConfiguredHeapMb = effectiveHeapMb;
        if (mSessionStartMs == 0L) {
            mSessionStartMs = SystemClock.elapsedRealtime();
        }
    }

    public void setHudEnabled(boolean enabled) {
        if (enabled) {
            syncFromPreferences();
            setVisibility(VISIBLE);
            startMonitoring();
            post(this::applyClampedCollisionFreePlacement);
        } else {
            stopMonitoring(false);
            setVisibility(GONE);
        }
    }

    public boolean isHudEnabled() {
        return getVisibility() == VISIBLE;
    }

    public void startMonitoring() {
        if (mMonitoring || getVisibility() != VISIBLE) return;
        mMonitoring = true;
        if (mSessionStartMs == 0L) {
            mSessionStartMs = SystemClock.elapsedRealtime();
        }
        mWindowStartNs = System.nanoTime();
        mLastFrameNs = 0L;
        mWindowFrames = 0;
        mWindowWorstNs = 0L;
        Choreographer.getInstance().postFrameCallback(this);
        removeCallbacks(mSampleRunnable);
        postDelayed(mSampleRunnable, UPDATE_INTERVAL_MS);
    }

    public void stopMonitoring(boolean persistSummary) {
        removeCallbacks(mLongPressRunnable);
        if (mMonitoring) {
            mMonitoring = false;
            Choreographer.getInstance().removeFrameCallback(this);
            removeCallbacks(mSampleRunnable);
        }
        if (persistSummary && mFpsSamples > 0) {
            saveLastSessionSummary();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mMonitoring || getVisibility() != VISIBLE) return;
        if (mLastFrameNs > 0L) {
            long delta = frameTimeNanos - mLastFrameNs;
            if (delta > 0L && delta < 2_000_000_000L) {
                if (delta > mWindowWorstNs) mWindowWorstNs = delta;
                mFrameDeltaRingNs[mFrameRingCursor] = delta;
                mFrameRingCursor = (mFrameRingCursor + 1) % FRAME_RING_CAPACITY;
                if (mFrameRingCount < FRAME_RING_CAPACITY) mFrameRingCount++;

                float ms = Math.min(66.7f, delta / 1_000_000f);
                mGraphFrameMs[mGraphCursor] = ms;
                mGraphCursor = (mGraphCursor + 1) % GRAPH_SAMPLES;
                if (mGraphCount < GRAPH_SAMPLES) mGraphCount++;
            }
        }
        mLastFrameNs = frameTimeNanos;
        mWindowFrames++;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void refreshDisplayMetrics() {
        long nowNs = System.nanoTime();
        double elapsedSec = Math.max(0.001, (nowNs - mWindowStartNs) / 1_000_000_000.0);
        double fps = mWindowFrames / elapsedSec;
        double avgMs = mWindowFrames > 0 ? (elapsedSec * 1000.0) / mWindowFrames : 0.0;
        double worstMs = mWindowWorstNs > 0L ? (mWindowWorstNs / 1_000_000.0) : avgMs;
        double onePctLow = computeOnePercentLowFps(mFrameDeltaRingNs, mFrameRingCount, mSortScratchNs);
        if (onePctLow > fps && fps > 0.0) {
            onePctLow = fps;
        }
        mWindowStartNs = nowNs;
        mWindowFrames = 0;
        mWindowWorstNs = 0L;

        if (fps > 0.5 && fps <= 240.0) {
            mSumFps += fps;
            mSumOnePctLow += onePctLow;
            mFpsSamples++;
        }

        // Java Heap & ART GC telemetry
        Runtime rt = Runtime.getRuntime();
        long usedHeapBytes = Math.max(0L, rt.totalMemory() - rt.freeMemory());
        int usedHeapMb = (int) (usedHeapBytes / (1024L * 1024L));
        sampleGcActivity(usedHeapBytes);

        // Native Linux RSS + fallback native heap
        int rssMb = readProcessNativeRssMb();
        if (rssMb > mPeakNativeRssMb) {
            mPeakNativeRssMb = rssMb;
        }

        // System free RAM & live RAM % utilization
        int freeMb = -1;
        int ramUsedPct = -1;
        if (mActivityManager != null) {
            try {
                mActivityManager.getMemoryInfo(mMemInfo);
                freeMb = (int) (mMemInfo.availMem / (1024L * 1024L));
                if (mMemInfo.totalMem > 0L) {
                    long usedSys = Math.max(0L, mMemInfo.totalMem - mMemInfo.availMem);
                    ramUsedPct = Math.max(0, Math.min(100,
                            (int) Math.round((usedSys * 100.0) / mMemInfo.totalMem)));
                }
                if (freeMb > 0 && freeMb < mMinFreeRamMb) mMinFreeRamMb = freeMb;
            } catch (Throwable ignored) {
            }
        }

        // Non-blocking multiplayer Ping sample (every 5s when connected to a server)
        maybeScheduleServerPingProbe();
        int pingMs = mLastServerPingMs;

        // Device thermal throttling & Celsius readout
        String thermal = queryThermalLabel();
        float tempC = queryBatteryCelsius();

        mTextBuilder.setLength(0);
        if (mCompactMode) {
            mTextBuilder.append(String.format(Locale.ROOT, "%.0f FPS", fps));
            if (pingMs >= 0) {
                mTextBuilder.append(" • ").append(pingMs).append("ms");
            } else {
                mTextBuilder.append(" • Ping --");
            }
            if (ramUsedPct >= 0) {
                mTextBuilder.append(" • RAM ").append(ramUsedPct).append('%');
            }
            if (tempC > 5f && tempC < 95f) {
                mTextBuilder.append(String.format(Locale.ROOT, " • %.0f°C", tempC));
            }
            if (mThermalGovernorBadge != null) {
                mTextBuilder.append(" • ").append(mThermalGovernorBadge);
            }
        } else {
            int scalePct = Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f);
            long uptimeSec = Math.max(0L, (SystemClock.elapsedRealtime() - mSessionStartMs) / 1000L);
            mTextBuilder.append(String.format(Locale.ROOT,
                    "FPS %.0f  1%%L %.0f  FT %.1fms",
                    fps, onePctLow, avgMs));
            if (pingMs >= 0) {
                mTextBuilder.append(" • Ping ").append(pingMs).append("ms");
            }
            mTextBuilder.append('\n');
            mTextBuilder.append("Heap ").append(usedHeapMb).append('/').append(mConfiguredHeapMb)
                    .append('M');
            if (ramUsedPct >= 0) {
                mTextBuilder.append(" • RAM ").append(ramUsedPct).append('%');
            }
            if (mRecentGcEvents > 0) {
                mTextBuilder.append(" • GC:").append(mRecentGcEvents);
            }
            mTextBuilder.append(" • RSS ").append(rssMb).append('M');
            mTextBuilder.append('\n');
            mTextBuilder.append("GPU ").append(mRendererLabel).append(" @").append(scalePct).append("% • THM ")
                    .append(thermal);
            if (tempC > 5f && tempC < 95f) {
                mTextBuilder.append(String.format(Locale.ROOT, " %.1f°C", tempC));
            }
            if (mThermalGovernorBadge != null) {
                mTextBuilder.append(" [").append(mThermalGovernorBadge).append(']');
            }
            mTextBuilder.append(String.format(Locale.ROOT, " • %02d:%02d",
                    uptimeSec / 60L, uptimeSec % 60L));
            if (mLocked) {
                mTextBuilder.append(" [LOCK]");
            }
        }
        setText(mTextBuilder.toString());
        invalidate();
    }

    private void maybeScheduleServerPingProbe() {
        final String addr = mPingTargetAddress;
        if (addr == null || mPingProbeInFlight) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastPingProbeElapsedMs < 5000L) return;
        mLastPingProbeElapsedMs = now;
        mPingProbeInFlight = true;
        git.artdeell.mojo.MojoApplication.sExecutorService.execute(() -> {
            try {
                git.artdeell.mojo.servers.ServerListManager.ParsedHostPort hp =
                        git.artdeell.mojo.servers.ServerListManager.parseAndValidateAddress(addr);
                long t0 = System.nanoTime();
                try (java.net.Socket s = new java.net.Socket()) {
                    s.connect(new java.net.InetSocketAddress(hp.host, hp.port), 2200);
                    mLastServerPingMs = (int) Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                }
            } catch (Throwable ignored) {
                mLastServerPingMs = -1;
            } finally {
                mPingProbeInFlight = false;
            }
        });
    }

    private void sampleGcActivity(long currentUsedHeapBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                String countStr = Debug.getRuntimeStat("art.gc.gc-count");
                String timeStr = Debug.getRuntimeStat("art.gc.gc-time");
                if (countStr != null) {
                    long gcCount = Long.parseLong(countStr.trim());
                    long gcTime = timeStr != null ? Long.parseLong(timeStr.trim()) : 0L;
                    if (mLastGcCount >= 0L) {
                        mRecentGcEvents = (int) Math.max(0L, gcCount - mLastGcCount);
                        mRecentGcPauseMs = Math.max(0L, gcTime - mLastGcTimeMs);
                    }
                    mLastGcCount = gcCount;
                    mLastGcTimeMs = gcTime;
                    mLastUsedHeapBytes = currentUsedHeapBytes;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // API 21–22 fallback: infer heap reclamation drops > 2 MB
        if (mLastUsedHeapBytes > 0L && (mLastUsedHeapBytes - currentUsedHeapBytes) > 2L * 1024L * 1024L) {
            mRecentGcEvents = 1;
        } else {
            mRecentGcEvents = 0;
        }
        mRecentGcPauseMs = 0L;
        mLastUsedHeapBytes = currentUsedHeapBytes;
    }

    private static int readProcessNativeRssMb() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"), 1024)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    int parsed = parseVmRssMb(line);
                    if (parsed > 0) return parsed;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        long nativeBytes = Debug.getNativeHeapAllocatedSize();
        long javaBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        return (int) Math.max(1L, (nativeBytes + Math.max(0L, javaBytes)) / (1024L * 1024L));
    }

    @NonNull
    private String queryThermalLabel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mPowerManager == null) {
            return "OK";
        }
        try {
            int status = mPowerManager.getCurrentThermalStatus();
            String label;
            switch (status) {
                case PowerManager.THERMAL_STATUS_NONE: label = "OK"; break;
                case PowerManager.THERMAL_STATUS_LIGHT: label = "WARM"; break;
                case PowerManager.THERMAL_STATUS_MODERATE: label = "MOD"; break;
                case PowerManager.THERMAL_STATUS_SEVERE: label = "HOT"; break;
                case PowerManager.THERMAL_STATUS_CRITICAL:
                case PowerManager.THERMAL_STATUS_EMERGENCY:
                case PowerManager.THERMAL_STATUS_SHUTDOWN: label = "CRIT"; break;
                default: label = "OK"; break;
            }
            if (status > mPeakThermalRank) {
                mPeakThermalRank = status;
                mPeakThermalLabel = label;
            }
            return label;
        } catch (Throwable ignored) {
            return "OK";
        }
    }

    private float queryBatteryCelsius() {
        try {
            Intent sticky = getContext().registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (tenths > 0) {
                    return tenths / 10f;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 4 || vh <= 4) return;

        // Draw live real-time frametime graph (expanded mode) with zero heap allocations
        if (!mCompactMode && mGraphCount > 1) {
            float scale = mScalePercent / 100f;
            float padH = BASE_PAD_H_DP * mDensity * scale;
            float padBot = BASE_PAD_V_DP * mDensity * scale;
            float graphH = BASE_GRAPH_HEIGHT_DP * mDensity * scale;
            float left = padH;
            float right = vw - padH;
            float bottom = vh - padBot;
            float top = Math.max(padBot, bottom - graphH);

            if (right - left > 24f * mDensity && bottom - top >= 8f * mDensity) {
                mGraphRect.set(left, top, right, bottom);
                float r = 3.5f * mDensity * scale;
                canvas.drawRoundRect(mGraphRect, r, r, mGraphBgPaint);

                // Reference horizontal lines for 16.67 ms (60 FPS) and 33.33 ms (30 FPS) over 0..50 ms range
                float maxScaleMs = 50f;
                float y60 = bottom - (16.67f / maxScaleMs) * graphH;
                float y120 = bottom - (8.33f / maxScaleMs) * graphH;
                canvas.drawLine(left + 2f, y60, right - 2f, y60, mGraphGuidePaint);
                canvas.drawLine(left + 2f, y120, right - 2f, y120, mGraphGuidePaint);

                float innerW = (right - left) - 4f;
                float stepX = innerW / GRAPH_SAMPLES;
                float barW = Math.max(1f, stepX * 0.72f);
                int startIdx = (mGraphCursor - mGraphCount + GRAPH_SAMPLES) % GRAPH_SAMPLES;
                int offsetSlot = GRAPH_SAMPLES - mGraphCount;

                mGraphPath.reset();
                for (int i = 0; i < mGraphCount; i++) {
                    float ms = mGraphFrameMs[(startIdx + i) % GRAPH_SAMPLES];
                    float norm = Math.max(0.04f, Math.min(1f, ms / maxScaleMs));
                    float barTop = bottom - norm * (graphH - 2f);
                    float bx = left + 2f + (offsetSlot + i) * stepX;

                    // Color-code frame spikes: emerald <=10ms, cyan <=18ms, amber <=28ms, red >28ms
                    int barColor;
                    if (ms <= 10.5f) {
                        barColor = 0xAA4ADE80;
                    } else if (ms <= 18.0f) {
                        barColor = 0xAA60C8FF;
                    } else if (ms <= 28.5f) {
                        barColor = 0xCCFBBF24;
                    } else {
                        barColor = 0xDDF87171;
                    }
                    mGraphBarPaint.setColor(barColor);
                    canvas.drawRect(bx, barTop, bx + barW, bottom - 1f, mGraphBarPaint);

                    float cx = bx + barW * 0.5f;
                    if (i == 0) {
                        mGraphPath.moveTo(cx, barTop);
                    } else {
                        mGraphPath.lineTo(cx, barTop);
                    }
                }
                canvas.drawPath(mGraphPath, mGraphLinePaint);
            }
        }

        // Active luminous border while user drags or presses the HUD
        if (mTouchState == TouchState.DRAGGING || mTouchState == TouchState.PRESSED) {
            float inset = 1.5f * mDensity;
            mSelfRect.set(inset, inset, vw - inset, vh - inset);
            float rad = Math.min(vw, vh) * 0.28f;
            canvas.drawRoundRect(mSelfRect, rad, rad, mDragOutlinePaint);
        }
    }

    private void saveLastSessionSummary() {
        try {
            File out = new File(new File(Tools.DIR_GAME_HOME, "logs"), "last_session_metrics.json");
            JsonObject obj = new JsonObject();
            obj.addProperty("recordedAtMs", System.currentTimeMillis());
            obj.addProperty("renderer", mRendererLabel);
            obj.addProperty("jvmHeapMb", mConfiguredHeapMb);
            obj.addProperty("resolutionPercent", Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f));
            obj.addProperty("avgDisplayHz", Math.round((mSumFps / mFpsSamples) * 10.0) / 10.0);
            obj.addProperty("avgOnePercentLowFps", Math.round((mSumOnePctLow / mFpsSamples) * 10.0) / 10.0);
            if (mPeakNativeRssMb > 0) {
                obj.addProperty("peakNativeRssMb", mPeakNativeRssMb);
            }
            if (mMinFreeRamMb < Integer.MAX_VALUE) {
                obj.addProperty("minFreeDeviceRamMb", mMinFreeRamMb);
            }
            obj.addProperty("peakThermalStatus", mPeakThermalLabel);
            obj.addProperty("durationSec", Math.max(1L, (SystemClock.elapsedRealtime() - mSessionStartMs) / 1000L));
            JSONUtils.writeToFileAtomic(out, obj, false);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMonitoring(false);
        super.onDetachedFromWindow();
    }
}
