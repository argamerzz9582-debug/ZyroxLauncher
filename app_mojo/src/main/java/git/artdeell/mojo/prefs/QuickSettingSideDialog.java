package git.artdeell.mojo.prefs;

import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_BUTTON_TRANSPARENCY;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_DISABLE_GESTURES;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_INVERT_X;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_INVERT_Y;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_SENSITIVITY;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_LONGPRESS_TRIGGER;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_MOUSESPEED;
import static git.artdeell.mojo.prefs.LauncherPreferences.PREF_SCALE_FACTOR;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.kdt.CustomSeekbar;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.interfaces.SimpleSeekBarListener;

/**
 * Side dialog for quick settings that you can change in game
 * The implementation has to take action on some preference changes
 */
public abstract class QuickSettingSideDialog extends com.kdt.SideDialogView {

    private SharedPreferences.Editor mEditor;

    private SwitchCompat mGyroSwitch, mGyroXSwitch, mGyroYSwitch, mGestureSwitch, mHapticsSwitch, mEdgeGuardSwitch;
    private SwitchCompat mHudEnabledSwitch, mHudLockSwitch, mHudCompactSwitch;
    private CustomSeekbar mGyroSensitivityBar, mMouseSpeedBar, mGestureDelayBar, mResolutionBar, mTransparencyBar, mGyroDeadzoneBar;
    private CustomSeekbar mHudScaleBar, mHudOpacityBar;
    private TextView mGyroSensitivityText, mGyroSensitivityDisplayText, mMouseSpeedText, mGestureDelayText, mGestureDelayDisplayText, mResolutionText, mTransparencyText, mGyroDeadzoneText, mGyroDeadzoneLabel;
    private TextView mHudScaleLabel, mHudScaleText, mHudOpacityLabel, mHudOpacityText;
    private View mHudResetButton;

    private boolean mOriginalGyroEnabled, mOriginalGyroXEnabled, mOriginalGyroYEnabled, mOriginalGestureDisabled, mOriginalHaptics, mOriginalEdgeGuard;
    private boolean mOriginalHudEnabled, mOriginalHudLocked, mOriginalHudCompact;
    private float mOriginalGyroSensitivity, mOriginalMouseSpeed, mOriginalResolution, mOriginalGyroDeadzone;
    private int mOriginalGestureDelay, mOriginalHudScale, mOriginalHudOpacity;
    private short mOriginalTransparency;

    public QuickSettingSideDialog(Context context, ViewGroup parent) {
        super(context, parent, R.layout.dialog_quick_setting);
        setTitle(R.string.quick_setting_title);
        setupCancelButton();
    }

    @Override
    protected void onInflate() {
        bindLayout();
        Tools.runOnUiThread(() -> {
            this.setupListeners();
            this.updateGyroCompatibility();
        });
    }

    @Override
    protected void onDestroy() {
        removeListeners();
    }

    private void bindLayout() {
        // Bind layout elements
        mGyroSwitch = mDialogContent.findViewById(R.id.checkboxGyro);
        mGyroXSwitch = mDialogContent.findViewById(R.id.checkboxGyroX);
        mGyroYSwitch = mDialogContent.findViewById(R.id.checkboxGyroY);
        mGestureSwitch = mDialogContent.findViewById(R.id.checkboxGesture);

        mGyroSensitivityBar = mDialogContent.findViewById(R.id.editGyro_seekbar);
        mMouseSpeedBar = mDialogContent.findViewById(R.id.editMouseSpeed_seekbar);
        mGestureDelayBar = mDialogContent.findViewById(R.id.editGestureDelay_seekbar);
        mResolutionBar = mDialogContent.findViewById(R.id.editResolution_seekbar);
        mTransparencyBar = mDialogContent.findViewById(R.id.buttonTransparency_seekBar);

        mGyroSensitivityText = mDialogContent.findViewById(R.id.editGyro_textView_percent);
        mGyroSensitivityDisplayText = mDialogContent.findViewById(R.id.editGyro_textView);
        mMouseSpeedText = mDialogContent.findViewById(R.id.editMouseSpeed_textView_percent);
        mGestureDelayText = mDialogContent.findViewById(R.id.editGestureDelay_textView_percent);
        mGestureDelayDisplayText = mDialogContent.findViewById(R.id.editGestureDelay_textView);
        mResolutionText = mDialogContent.findViewById(R.id.editResolution_textView_percent);
        mTransparencyText = mDialogContent.findViewById(R.id.buttonTransparency_textView_percent);

        mHapticsSwitch = mDialogContent.findViewById(R.id.checkboxHaptics);
        mEdgeGuardSwitch = mDialogContent.findViewById(R.id.checkboxEdgeGuard);
        mGyroDeadzoneBar = mDialogContent.findViewById(R.id.editGyroDeadzone_seekBar);
        mGyroDeadzoneText = mDialogContent.findViewById(R.id.editGyroDeadzone_percent);
        mGyroDeadzoneLabel = mDialogContent.findViewById(R.id.editGyroDeadzone_textView);

        // Performance HUD controls (50%..150% scale, opacity, lock, mini-pill, reset)
        mHudEnabledSwitch = mDialogContent.findViewById(R.id.checkboxHudEnabled);
        mHudScaleLabel = mDialogContent.findViewById(R.id.editHudScale_textView);
        mHudScaleText = mDialogContent.findViewById(R.id.editHudScale_percent);
        mHudScaleBar = mDialogContent.findViewById(R.id.editHudScale_seekBar);
        mHudOpacityLabel = mDialogContent.findViewById(R.id.editHudOpacity_textView);
        mHudOpacityText = mDialogContent.findViewById(R.id.editHudOpacity_percent);
        mHudOpacityBar = mDialogContent.findViewById(R.id.editHudOpacity_seekBar);
        mHudLockSwitch = mDialogContent.findViewById(R.id.checkboxHudLock);
        mHudCompactSwitch = mDialogContent.findViewById(R.id.checkboxHudCompact);
        mHudResetButton = mDialogContent.findViewById(R.id.quickBtn_hudReset);
        if (mHudResetButton != null) {
            mHudResetButton.setOnClickListener(v -> {
                LauncherPreferences.resetHudPosition();
                onHudResetPositionRequested();
            });
        }

        View shotBtn = mDialogContent.findViewById(R.id.quickBtn_screenshot);
        if (shotBtn != null) {
            shotBtn.setOnClickListener(v -> {
                mEditor.apply();
                disappear(true);
                git.artdeell.mojo.game.QuickMacroDialog.triggerScreenshot(v.getContext());
            });
        }
        View macrosBtn = mDialogContent.findViewById(R.id.quickBtn_macros);
        if (macrosBtn != null) {
            macrosBtn.setOnClickListener(v -> {
                mEditor.apply();
                disappear(true);
                git.artdeell.mojo.game.QuickMacroDialog.show(v.getContext());
            });
        }
    }

    private void setupListeners() {
        mEditor = LauncherPreferences.DEFAULT_PREF.edit();

        mOriginalGyroEnabled = PREF_ENABLE_GYRO;
        mOriginalGyroXEnabled = PREF_GYRO_INVERT_X;
        mOriginalGyroYEnabled = PREF_GYRO_INVERT_Y;
        mOriginalGestureDisabled = PREF_DISABLE_GESTURES;

        mOriginalGyroSensitivity = PREF_GYRO_SENSITIVITY;
        mOriginalMouseSpeed = PREF_MOUSESPEED;
        mOriginalGestureDelay = PREF_LONGPRESS_TRIGGER;
        mOriginalResolution = PREF_SCALE_FACTOR;
        mOriginalTransparency = PREF_BUTTON_TRANSPARENCY;

        mGyroSwitch.setChecked(mOriginalGyroEnabled);
        mGyroXSwitch.setChecked(mOriginalGyroXEnabled);
        mGyroYSwitch.setChecked(mOriginalGyroYEnabled);
        mGestureSwitch.setChecked(mOriginalGestureDisabled);

        mGyroSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_ENABLE_GYRO = isChecked;
            onGyroStateChanged();
            updateGyroVisibility(isChecked);
            mEditor.putBoolean("enableGyro", isChecked);
        });

        mGyroXSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_GYRO_INVERT_X = isChecked;
            onGyroStateChanged();
            mEditor.putBoolean("gyroInvertX", isChecked);
        });

        mGyroYSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_GYRO_INVERT_Y = isChecked;
            onGyroStateChanged();
            mEditor.putBoolean("gyroInvertY", isChecked);
        });

        mGestureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_DISABLE_GESTURES = isChecked;
            updateGestureVisibility(isChecked);
            mEditor.putBoolean("disableGestures", isChecked);
        });

        mGyroSensitivityBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_GYRO_SENSITIVITY = progress / 100f;
            mEditor.putInt("gyroSensitivity", progress);
            setSeekTextPercent(mGyroSensitivityText, progress);
        });
        mGyroSensitivityBar.setProgress((int) (mOriginalGyroSensitivity * 100f));
        setSeekTextPercent(mGyroSensitivityText, mGyroSensitivityBar.getProgress());

        mMouseSpeedBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_MOUSESPEED = progress / 100f;
            mEditor.putInt("mousespeed", progress);
            setSeekTextPercent(mMouseSpeedText, progress);
        });
        mMouseSpeedBar.setProgress((int) (mOriginalMouseSpeed * 100f));
        setSeekTextPercent(mMouseSpeedText, mMouseSpeedBar.getProgress());

        mGestureDelayBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_LONGPRESS_TRIGGER = progress;
            mEditor.putInt("timeLongPressTrigger", progress);
            setSeekTextMillisecond(mGestureDelayText, progress);
        });
        mGestureDelayBar.setProgress(mOriginalGestureDelay);
        setSeekTextMillisecond(mGestureDelayText, mGestureDelayBar.getProgress());

        mResolutionBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_SCALE_FACTOR = progress/100f;
            mEditor.putInt("resolutionRatio", progress);
            setSeekTextPercent(mResolutionText, progress);
            onResolutionChanged();
        });
        mResolutionBar.setProgress((int) (mOriginalResolution * 100));
        setSeekTextPercent(mResolutionText, mResolutionBar.getProgress());

        mTransparencyBar.setProgress(PREF_BUTTON_TRANSPARENCY);
        setSeekTextPercent(mTransparencyText, mTransparencyBar.getProgress());
        mTransparencyBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_BUTTON_TRANSPARENCY = (short) progress;
            mEditor.putInt("buttonTransparency", progress);
            setSeekTextPercent(mTransparencyText, progress);
            onButtonTransparencyChanged();
        });

        mOriginalHaptics = LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS;
        mOriginalEdgeGuard = LauncherPreferences.PREF_ZYROX_EDGE_TOUCH_GUARD;
        mOriginalGyroDeadzone = LauncherPreferences.PREF_ZYROX_GYRO_DEADZONE;

        if (mHapticsSwitch != null) {
            mHapticsSwitch.setChecked(mOriginalHaptics);
            mHapticsSwitch.setOnCheckedChangeListener((b, checked) -> {
                LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS = checked;
                mEditor.putBoolean("zyroxControlHaptics", checked);
            });
        }
        if (mEdgeGuardSwitch != null) {
            mEdgeGuardSwitch.setChecked(mOriginalEdgeGuard);
            mEdgeGuardSwitch.setOnCheckedChangeListener((b, checked) -> {
                LauncherPreferences.PREF_ZYROX_EDGE_TOUCH_GUARD = checked;
                mEditor.putBoolean("zyroxEdgeTouchGuard", checked);
            });
        }
        if (mGyroDeadzoneBar != null && mGyroDeadzoneText != null) {
            int dzInit = Math.max(20, Math.min(250, Math.round(mOriginalGyroDeadzone * 100f)));
            mGyroDeadzoneBar.setProgress(dzInit);
            setSeekTextPercent(mGyroDeadzoneText, dzInit);
            mGyroDeadzoneBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
                LauncherPreferences.PREF_ZYROX_GYRO_DEADZONE = progress / 100f;
                mEditor.putInt("zyroxGyroDeadzone", progress);
                setSeekTextPercent(mGyroDeadzoneText, progress);
            });
        }

        updateGyroVisibility(mOriginalGyroEnabled);
        updateGestureVisibility(mOriginalGestureDisabled);

        mOriginalHudEnabled = LauncherPreferences.PREF_ZYROX_PERF_HUD;
        mOriginalHudScale = LauncherPreferences.clampHudScale(LauncherPreferences.PREF_ZYROX_HUD_SCALE);
        mOriginalHudOpacity = LauncherPreferences.clampHudOpacity(LauncherPreferences.PREF_ZYROX_HUD_OPACITY);
        mOriginalHudLocked = LauncherPreferences.PREF_ZYROX_HUD_LOCKED;
        mOriginalHudCompact = LauncherPreferences.PREF_ZYROX_HUD_COMPACT;

        if (mHudEnabledSwitch != null) {
            mHudEnabledSwitch.setChecked(mOriginalHudEnabled);
            mHudEnabledSwitch.setOnCheckedChangeListener((b, checked) -> {
                LauncherPreferences.PREF_ZYROX_PERF_HUD = checked;
                mEditor.putBoolean("zyroxPerfHud", checked);
                updateHudControlsVisibility(checked);
                onHudPreferencesChanged();
            });
        }
        if (mHudScaleBar != null && mHudScaleText != null) {
            mHudScaleBar.setProgress(mOriginalHudScale);
            setSeekTextPercent(mHudScaleText, mOriginalHudScale);
            mHudScaleBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
                int clamped = LauncherPreferences.clampHudScale(progress);
                LauncherPreferences.PREF_ZYROX_HUD_SCALE = clamped;
                mEditor.putInt(LauncherPreferences.KEY_ZYROX_HUD_SCALE, clamped);
                setSeekTextPercent(mHudScaleText, clamped);
                onHudPreferencesChanged();
            });
        }
        if (mHudOpacityBar != null && mHudOpacityText != null) {
            mHudOpacityBar.setProgress(mOriginalHudOpacity);
            setSeekTextPercent(mHudOpacityText, mOriginalHudOpacity);
            mHudOpacityBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
                int clamped = LauncherPreferences.clampHudOpacity(progress);
                LauncherPreferences.PREF_ZYROX_HUD_OPACITY = clamped;
                mEditor.putInt(LauncherPreferences.KEY_ZYROX_HUD_OPACITY, clamped);
                setSeekTextPercent(mHudOpacityText, clamped);
                onHudPreferencesChanged();
            });
        }
        if (mHudLockSwitch != null) {
            mHudLockSwitch.setChecked(mOriginalHudLocked);
            mHudLockSwitch.setOnCheckedChangeListener((b, checked) -> {
                LauncherPreferences.PREF_ZYROX_HUD_LOCKED = checked;
                mEditor.putBoolean(LauncherPreferences.KEY_ZYROX_HUD_LOCKED, checked);
                onHudPreferencesChanged();
            });
        }
        if (mHudCompactSwitch != null) {
            mHudCompactSwitch.setChecked(mOriginalHudCompact);
            mHudCompactSwitch.setOnCheckedChangeListener((b, checked) -> {
                LauncherPreferences.PREF_ZYROX_HUD_COMPACT = checked;
                mEditor.putBoolean(LauncherPreferences.KEY_ZYROX_HUD_COMPACT, checked);
                onHudPreferencesChanged();
            });
        }
        updateHudControlsVisibility(mOriginalHudEnabled);
    }

    private void updateHudControlsVisibility(boolean hudEnabled) {
        int vis = hudEnabled ? View.VISIBLE : View.GONE;
        if (mHudScaleLabel != null) mHudScaleLabel.setVisibility(vis);
        if (mHudScaleText != null) mHudScaleText.setVisibility(vis);
        if (mHudScaleBar != null) mHudScaleBar.setVisibility(vis);
        if (mHudOpacityLabel != null) mHudOpacityLabel.setVisibility(vis);
        if (mHudOpacityText != null) mHudOpacityText.setVisibility(vis);
        if (mHudOpacityBar != null) mHudOpacityBar.setVisibility(vis);
        if (mHudLockSwitch != null) mHudLockSwitch.setVisibility(vis);
        if (mHudCompactSwitch != null) mHudCompactSwitch.setVisibility(vis);
        if (mHudResetButton != null) mHudResetButton.setVisibility(vis);
    }

    private static void setSeekTextMillisecond(TextView target, int value) {
        setSeekText(target, R.string.millisecond_format, value);
    }

    private static void setSeekTextPercent(TextView target, int value) {
        setSeekText(target, R.string.percent_format, value);
    }

    private static void setSeekText(TextView target, int format, int value) {
        target.setText(target.getContext().getString(format, value));
    }

    private void updateGyroVisibility(boolean isEnabled) {
        int visibility = isEnabled ? View.VISIBLE : View.GONE;
        mGyroXSwitch.setVisibility(visibility);
        mGyroYSwitch.setVisibility(visibility);

        mGyroSensitivityBar.setVisibility(visibility);
        mGyroSensitivityText.setVisibility(visibility);
        mGyroSensitivityDisplayText.setVisibility(visibility);
        if (mGyroDeadzoneBar != null) mGyroDeadzoneBar.setVisibility(visibility);
        if (mGyroDeadzoneText != null) mGyroDeadzoneText.setVisibility(visibility);
        if (mGyroDeadzoneLabel != null) mGyroDeadzoneLabel.setVisibility(visibility);
    }

    private void updateGyroCompatibility() {
        boolean isGyroAvailable = Tools.deviceSupportsGyro(mDialogContent.getContext());
        if (!isGyroAvailable) {
            mGyroSwitch.setVisibility(View.GONE);
            updateGestureVisibility(false);
        }
    }

    private void updateGestureVisibility(boolean isDisabled) {
        int visibility = isDisabled ? View.GONE : View.VISIBLE;
        mGestureDelayBar.setVisibility(visibility);
        mGestureDelayText.setVisibility(visibility);
        mGestureDelayDisplayText.setVisibility(visibility);
    }

    private void removeListeners() {
        mGyroSwitch.setOnCheckedChangeListener(null);
        mGyroXSwitch.setOnCheckedChangeListener(null);
        mGyroYSwitch.setOnCheckedChangeListener(null);
        mGestureSwitch.setOnCheckedChangeListener(null);
        if (mHapticsSwitch != null) mHapticsSwitch.setOnCheckedChangeListener(null);
        if (mEdgeGuardSwitch != null) mEdgeGuardSwitch.setOnCheckedChangeListener(null);
        if (mHudEnabledSwitch != null) mHudEnabledSwitch.setOnCheckedChangeListener(null);
        if (mHudLockSwitch != null) mHudLockSwitch.setOnCheckedChangeListener(null);
        if (mHudCompactSwitch != null) mHudCompactSwitch.setOnCheckedChangeListener(null);

        mGyroSensitivityBar.setOnSeekBarChangeListener(null);
        if (mGyroDeadzoneBar != null) mGyroDeadzoneBar.setOnSeekBarChangeListener(null);
        if (mHudScaleBar != null) mHudScaleBar.setOnSeekBarChangeListener(null);
        if (mHudOpacityBar != null) mHudOpacityBar.setOnSeekBarChangeListener(null);
        mMouseSpeedBar.setOnSeekBarChangeListener(null);
        mGestureDelayBar.setOnSeekBarChangeListener(null);
        mResolutionBar.setOnSeekBarChangeListener(null);
        mTransparencyBar.setOnSeekBarChangeListener(null);
    }

    private void setupCancelButton() {
        setStartButtonListener(android.R.string.cancel, v -> cancel());
        setEndButtonListener(android.R.string.ok, v -> {
            mEditor.apply();
            disappear(true);
        });
    }

    /** Resets all settings to their original values */
    public void cancel() {
        // Reset all settings if we were editing
        if(isDisplaying()) {
            PREF_ENABLE_GYRO = mOriginalGyroEnabled;
            PREF_GYRO_INVERT_X = mOriginalGyroXEnabled;
            PREF_GYRO_INVERT_Y = mOriginalGyroYEnabled;
            PREF_DISABLE_GESTURES = mOriginalGestureDisabled;

            PREF_GYRO_SENSITIVITY = mOriginalGyroSensitivity;
            PREF_MOUSESPEED = mOriginalMouseSpeed;
            PREF_LONGPRESS_TRIGGER = mOriginalGestureDelay;
            PREF_SCALE_FACTOR = mOriginalResolution;
            PREF_BUTTON_TRANSPARENCY = mOriginalTransparency;
            LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS = mOriginalHaptics;
            LauncherPreferences.PREF_ZYROX_EDGE_TOUCH_GUARD = mOriginalEdgeGuard;
            LauncherPreferences.PREF_ZYROX_GYRO_DEADZONE = mOriginalGyroDeadzone;
            LauncherPreferences.PREF_ZYROX_PERF_HUD = mOriginalHudEnabled;
            LauncherPreferences.PREF_ZYROX_HUD_SCALE = mOriginalHudScale;
            LauncherPreferences.PREF_ZYROX_HUD_OPACITY = mOriginalHudOpacity;
            LauncherPreferences.PREF_ZYROX_HUD_LOCKED = mOriginalHudLocked;
            LauncherPreferences.PREF_ZYROX_HUD_COMPACT = mOriginalHudCompact;

            onGyroStateChanged();
            onResolutionChanged();
            onButtonTransparencyChanged();
            onHudPreferencesChanged();
        }

        disappear(true);
    }

    /** Called when the resolution is changed. Use {@link LauncherPreferences#PREF_SCALE_FACTOR} */
    public abstract void onResolutionChanged();

    /** Called when the gyro state is changed.
     * Use {@link LauncherPreferences#PREF_ENABLE_GYRO}
     * Use {@link LauncherPreferences#PREF_GYRO_INVERT_X}
     * Use {@link LauncherPreferences#PREF_GYRO_INVERT_Y}
     */
    public abstract void onGyroStateChanged();

    /**
     * Called when the button transparency state is changed. Use {@link LauncherPreferences#PREF_BUTTON_TRANSPARENCY}
     */
    public abstract void onButtonTransparencyChanged();

    /** Called when HUD visibility, scale (50–150%), opacity, lock, or compact mode changes. */
    public void onHudPreferencesChanged() {
    }

    /** Called when the user clicks Reset HUD Position. */
    public void onHudResetPositionRequested() {
    }

}
