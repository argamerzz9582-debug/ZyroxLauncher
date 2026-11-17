package git.artdeell.mojo.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.Locale;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.utils.JSONUtils;

/**
 * Optional, non-interactive, lightweight in-game Performance Monitor HUD overlay.
 *
 * <p>Reports strictly truthful, directly measurable Android/runtime metrics:
 * <ul>
 *   <li>Real UI/Display frame rate ({@code Hz}) and average frame interval ({@code ms}) from
 *       {@link Choreographer} (explicitly labeled {@code DISP} — never mislabeled as internal MC F3 FPS)</li>
 *   <li>Real device free & total RAM ({@link ActivityManager.MemoryInfo}) and configured JVM heap</li>
 *   <li>Real Android thermal headroom status on API 29+ ({@link PowerManager#getCurrentThermalStatus})</li>
 *   <li>Active renderer tag, resolution scale %, and session uptime</li>
 * </ul>
 *
 * <p>Polls strictly once per second ONLY while attached and visible; immediately unregisters
 * callbacks when hidden or paused; persists a lightweight local last-session summary on stop.
 */
public class PerformanceHudOverlay extends AppCompatTextView implements Choreographer.FrameCallback {
    private static final long UPDATE_INTERVAL_MS = 1000L;

    private final ActivityManager mActivityManager;
    private final ActivityManager.MemoryInfo mMemInfo = new ActivityManager.MemoryInfo();
    @Nullable private final PowerManager mPowerManager;
    private final StringBuilder mTextBuilder = new StringBuilder(160);

    private String mRendererLabel = "auto";
    private int mHeapMb = 1024;
    private boolean mMonitoring = false;
    private long mSessionStartMs = 0L;
    private long mWindowStartNs = 0L;
    private long mLastFrameNs = 0L;
    private int mFrameCount = 0;
    private long mWorstFrameNs = 0L;

    // Session aggregate counters for truthful post-session summary
    private double mSumHz = 0.0;
    private int mHzSamples = 0;
    private int mMinFreeRamMb = Integer.MAX_VALUE;
    private String mPeakThermalLabel = "N/A";
    private int mPeakThermalRank = -1;

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
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPowerManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? (PowerManager) context.getSystemService(Context.POWER_SERVICE) : null;

        float d = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        setShadowLayer(3f, 0f, 1f, Color.parseColor("#B0101824"));
        int padH = Math.round(9f * d);
        int padV = Math.round(5f * d);
        setPadding(padH, padV, padH, padV);
        ZyroxLiquidGlassDrawable bg = new ZyroxLiquidGlassDrawable(d, true);
        bg.setAlpha(215);
        setBackground(bg);
        setVisibility(GONE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    public void configureSession(@NonNull String rendererTag, int effectiveHeapMb) {
        mRendererLabel = rendererTag;
        mHeapMb = effectiveHeapMb;
        if (mSessionStartMs == 0L) {
            mSessionStartMs = SystemClock.elapsedRealtime();
        }
    }

    public void setHudEnabled(boolean enabled) {
        if (enabled) {
            setVisibility(VISIBLE);
            startMonitoring();
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
        mFrameCount = 0;
        mWorstFrameNs = 0L;
        Choreographer.getInstance().postFrameCallback(this);
        removeCallbacks(mSampleRunnable);
        postDelayed(mSampleRunnable, UPDATE_INTERVAL_MS);
    }

    public void stopMonitoring(boolean persistSummary) {
        if (mMonitoring) {
            mMonitoring = false;
            Choreographer.getInstance().removeFrameCallback(this);
            removeCallbacks(mSampleRunnable);
        }
        if (persistSummary && mHzSamples > 0) {
            saveLastSessionSummary();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mMonitoring || getVisibility() != VISIBLE) return;
        if (mLastFrameNs > 0L) {
            long delta = frameTimeNanos - mLastFrameNs;
            if (delta > mWorstFrameNs) mWorstFrameNs = delta;
        }
        mLastFrameNs = frameTimeNanos;
        mFrameCount++;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void refreshDisplayMetrics() {
        long nowNs = System.nanoTime();
        double elapsedSec = Math.max(0.001, (nowNs - mWindowStartNs) / 1_000_000_000.0);
        double hz = mFrameCount / elapsedSec;
        double avgMs = mFrameCount > 0 ? (elapsedSec * 1000.0) / mFrameCount : 0.0;
        mWindowStartNs = nowNs;
        mFrameCount = 0;
        mWorstFrameNs = 0L;

        if (hz > 0.5 && hz <= 240.0) {
            mSumHz += hz;
            mHzSamples++;
        }

        int freeMb = -1;
        int totalMb = -1;
        if (mActivityManager != null) {
            try {
                mActivityManager.getMemoryInfo(mMemInfo);
                freeMb = (int) (mMemInfo.availMem / (1024L * 1024L));
                totalMb = (int) (mMemInfo.totalMem / (1024L * 1024L));
                if (freeMb > 0 && freeMb < mMinFreeRamMb) mMinFreeRamMb = freeMb;
            } catch (Throwable ignored) {
            }
        }

        String thermal = queryThermalLabel();
        int scalePct = Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f);
        long uptimeSec = Math.max(0L, (SystemClock.elapsedRealtime() - mSessionStartMs) / 1000L);
        long mins = uptimeSec / 60L;
        long secs = uptimeSec % 60L;

        mTextBuilder.setLength(0);
        mTextBuilder.append(String.format(Locale.ROOT, "DISP %.0f Hz (%.1f ms)", hz, avgMs));
        if (freeMb >= 0 && totalMb > 0) {
            mTextBuilder.append(" • RAM ").append(freeMb).append('/').append(totalMb)
                    .append(" MB (JVM ").append(mHeapMb).append("M)");
        }
        mTextBuilder.append('\n');
        mTextBuilder.append("GPU ").append(mRendererLabel)
                .append(" @").append(scalePct).append('%');
        if (!"N/A".equals(thermal)) {
            mTextBuilder.append(" • THERM ").append(thermal);
        }
        mTextBuilder.append(String.format(Locale.ROOT, " • %02d:%02d", mins, secs));
        setText(mTextBuilder.toString());
    }

    @NonNull
    private String queryThermalLabel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mPowerManager == null) {
            return "N/A";
        }
        try {
            int status = mPowerManager.getCurrentThermalStatus();
            String label;
            switch (status) {
                case PowerManager.THERMAL_STATUS_NONE: label = "OK"; break;
                case PowerManager.THERMAL_STATUS_LIGHT: label = "LIGHT"; break;
                case PowerManager.THERMAL_STATUS_MODERATE: label = "MOD"; break;
                case PowerManager.THERMAL_STATUS_SEVERE: label = "HIGH"; break;
                case PowerManager.THERMAL_STATUS_CRITICAL:
                case PowerManager.THERMAL_STATUS_EMERGENCY:
                case PowerManager.THERMAL_STATUS_SHUTDOWN: label = "CRIT"; break;
                default: label = "N/A"; break;
            }
            if (status > mPeakThermalRank && !"N/A".equals(label)) {
                mPeakThermalRank = status;
                mPeakThermalLabel = label;
            }
            return label;
        } catch (Throwable ignored) {
            return "N/A";
        }
    }

    private void saveLastSessionSummary() {
        try {
            File out = new File(new File(Tools.DIR_GAME_HOME, "logs"), "last_session_metrics.json");
            JsonObject obj = new JsonObject();
            obj.addProperty("recordedAtMs", System.currentTimeMillis());
            obj.addProperty("renderer", mRendererLabel);
            obj.addProperty("jvmHeapMb", mHeapMb);
            obj.addProperty("resolutionPercent", Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f));
            obj.addProperty("avgDisplayHz", Math.round((mSumHz / mHzSamples) * 10.0) / 10.0);
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
