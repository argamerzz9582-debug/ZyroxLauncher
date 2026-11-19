package git.artdeell.mojo.game;

import static git.artdeell.mojo.game.platform.Platform.PLATFORM;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.LwjglGlfwKeycode;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;

/**
 * Phase 4.4 — Context-Aware Auto-Switching Inventory &amp; GUI Helper Bar.
 *
 * <p>Automatically appears inside the top-center safe area whenever Minecraft switches to an
 * ungrabbed cursor state (Inventory, Chest, Crafting, Furnace, Chat, or Pause menu) and vanishes
 * the instant gameplay resumes ({@code isGrabbing == true}), automatically releasing any latched
 * modifier keys so the player is never stuck sneaking.
 *
 * <p>Provides 5 high-contrast Liquid Glass one-tap actions:
 * <ul>
 *   <li><b>Shift-Move</b> — toggleable {@code LEFT_SHIFT} latch for 1-tap stack quick-transfer</li>
 *   <li><b>Split ½</b> — instant {@code RIGHT_CLICK} to split stacks in half or place 1 item</li>
 *   <li><b>Drop All</b> — {@code CTRL+Q} chord to drop the hovered stack immediately</li>
 *   <li><b>Wheel</b> — opens the 8-slot Radial Quick-Chat &amp; Command Wheel</li>
 *   <li><b>Close</b> — sends {@code ESC} to exit containers/menus cleanly</li>
 * </ul>
 */
public final class ContextInventoryHelperBar extends LinearLayout {

    private final float mDensity;
    private final TextView mBtnShiftLatch;
    private final ZyroxLiquidGlassDrawable mShiftBg;
    private boolean mShiftLatched = false;
    private boolean mCtrlPulsed = false;
    private boolean mRightMousePulsed = false;
    private boolean mGameLoaded = false;
    private final Runnable mReleaseRightClickTask = () -> {
        if (!mRightMousePulsed) return;
        mRightMousePulsed = false;
        try {
            CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
        } catch (Throwable ignored) {
        }
    };
    private final Runnable mReleaseCtrlTask = () -> {
        if (!mCtrlPulsed) return;
        mCtrlPulsed = false;
        try {
            PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, false, 0);
        } catch (Throwable ignored) {
        }
    };

    public ContextInventoryHelperBar(@NonNull Context context,
                                     @Nullable Runnable onOpenRadialWheel) {
        super(context);
        mDensity = Math.max(1f, context.getResources().getDisplayMetrics().density);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(6);
        int padV = dp(4);
        setPadding(padH, padV, padH, padV);
        setElevation(7.5f * mDensity);

        ZyroxLiquidGlassDrawable barGlass = new ZyroxLiquidGlassDrawable(mDensity, true);
        barGlass.setHighContrastScrimEnabled(true);
        setBackground(barGlass);

        // 1. Shift-Move Latch (instant chest/inventory quick-move)
        mShiftBg = new ZyroxLiquidGlassDrawable(mDensity, false);
        mShiftBg.setHighContrastScrimEnabled(true);
        mBtnShiftLatch = buildChip("Shift-Move", mShiftBg, v -> toggleShiftLatch());
        addView(mBtnShiftLatch, chipLayoutParams(0));

        // 2. Split Stack (Right-Click)
        addView(buildChip("Split 1/2", null, v -> triggerSplitStack()), chipLayoutParams(dp(4)));

        // 3. Drop Stack (Ctrl + Q)
        addView(buildChip("Drop Stack", null, v -> triggerDropStack()), chipLayoutParams(dp(4)));

        // 4. 8-Slot Radial Quick-Chat & Command Wheel
        addView(buildChip("Wheel", null, v -> {
            if (onOpenRadialWheel != null) {
                onOpenRadialWheel.run();
            } else {
                QuickMacroDialog.show(getContext());
            }
        }), chipLayoutParams(dp(4)));

        // 5. Close GUI (ESC)
        addView(buildChip("Close", null, v -> triggerCloseGui()), chipLayoutParams(dp(4)));

        setVisibility(GONE);
    }

    /** Pure deterministic visibility resolver (unit-tested without Android framework state). */
    public static boolean shouldShowInventoryBar(boolean contextAwarePrefEnabled,
                                                 boolean gameSessionReady,
                                                 boolean isGrabbingGameplay,
                                                 boolean isInControlEditor) {
        return contextAwarePrefEnabled
                && gameSessionReady
                && !isGrabbingGameplay
                && !isInControlEditor;
    }

    /** Marks the game session active so the bar hides during initial bootstrap loading screen. */
    public void setGameSessionReady(boolean ready) {
        mGameLoaded = ready;
    }

    /** Synchronizes visibility and modifier latch state on LWJGL/SDL grab-state transitions. */
    public void onContextStateChanged(boolean isGrabbing, boolean isInControlEditor) {
        boolean show = shouldShowInventoryBar(
                LauncherPreferences.PREF_ZYROX_CONTEXT_CONTROLS,
                mGameLoaded,
                isGrabbing,
                isInControlEditor);
        if (!show) {
            animate().cancel();
            releaseLatchedModifiers();
            if (getVisibility() != GONE) {
                setVisibility(GONE);
            }
        } else if (getVisibility() != VISIBLE) {
            animate().cancel();
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(140L).start();
        }
    }

    /** Safely releases any held modifier/mouse state (on gameplay resume, focus loss, pause, destroy). */
    public void releaseLatchedModifiers() {
        Tools.MAIN_HANDLER.removeCallbacks(mReleaseRightClickTask);
        Tools.MAIN_HANDLER.removeCallbacks(mReleaseCtrlTask);
        if (mRightMousePulsed) {
            mRightMousePulsed = false;
            try {
                CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
            } catch (Throwable ignored) {
            }
        }
        if (mCtrlPulsed) {
            mCtrlPulsed = false;
            try {
                PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, false, 0);
            } catch (Throwable ignored) {
            }
        }
        if (mShiftLatched) {
            mShiftLatched = false;
            try {
                PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, false, 0);
            } catch (Throwable ignored) {
            }
            updateShiftVisual();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            releaseLatchedModifiers();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        releaseLatchedModifiers();
        super.onDetachedFromWindow();
    }

    public boolean isShiftLatched() {
        return mShiftLatched;
    }

    private void toggleShiftLatch() {
        mShiftLatched = !mShiftLatched;
        try {
            PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, mShiftLatched, 0);
        } catch (Throwable ignored) {
        }
        updateShiftVisual();
    }

    private void updateShiftVisual() {
        mBtnShiftLatch.setText(mShiftLatched ? "Shift: ON" : "Shift-Move");
        mBtnShiftLatch.setTextColor(mShiftLatched
                ? Color.parseColor("#8CF0FF")
                : ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        mShiftBg.setState(mShiftLatched
                ? new int[]{android.R.attr.state_selected, android.R.attr.state_enabled}
                : new int[]{android.R.attr.state_enabled});
        mBtnShiftLatch.invalidate();
    }

    private void triggerSplitStack() {
        try {
            Tools.MAIN_HANDLER.removeCallbacks(mReleaseRightClickTask);
            mRightMousePulsed = true;
            CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
            Tools.MAIN_HANDLER.postDelayed(mReleaseRightClickTask, 32L);
        } catch (Throwable ignored) {
            mRightMousePulsed = false;
        }
    }

    private void triggerDropStack() {
        try {
            Tools.MAIN_HANDLER.removeCallbacks(mReleaseCtrlTask);
            mCtrlPulsed = true;
            PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, true, 0);
            Tools.MAIN_HANDLER.postDelayed(() -> {
                if (!mCtrlPulsed) return;
                try {
                    PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_Q, true, 0);
                    PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_Q, false, 0);
                } catch (Throwable ignored) {
                }
                Tools.MAIN_HANDLER.postDelayed(mReleaseCtrlTask, 35L);
            }, 30L);
        } catch (Throwable ignored) {
            mCtrlPulsed = false;
        }
    }

    private void triggerCloseGui() {
        releaseLatchedModifiers();
        try {
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private TextView buildChip(@NonNull String label,
                               @Nullable ZyroxLiquidGlassDrawable customBg,
                               @NonNull OnClickListener action) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        tv.setShadowLayer(2.5f, 0f, 1f, 0xCC070C14);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setPadding(dp(9), dp(6), dp(9), dp(6));
        tv.setMinHeight(dp(30));
        ZyroxLiquidGlassDrawable bg = customBg != null
                ? customBg : new ZyroxLiquidGlassDrawable(mDensity, false);
        bg.setHighContrastScrimEnabled(true);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.onClick(v);
        });
        return tv;
    }

    @NonNull
    private LayoutParams chipLayoutParams(int marginStartPx) {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = marginStartPx;
        return lp;
    }

    private int dp(float val) {
        return Math.round(val * mDensity);
    }
}
