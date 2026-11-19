package git.artdeell.mojo.input.buttons;

import static git.artdeell.mojo.CallbackBridge.sendMouseButton;
import static git.artdeell.mojo.game.platform.Platform.PLATFORM;
import static git.artdeell.mojo.input.buttons.BackgroundTint.DEFAULT_TINT_LIST;
import static git.artdeell.mojo.input.buttons.BackgroundTint.TOGGLE_TINT_LIST;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.LwjglGlfwKeycode;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.game.GameActivity;
import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.input.handleview.EditControlSideDialog;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxControlGlyphRenderer;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * In-game touch control button rendered with the Zyrox Liquid Glass optical engine and crisp
 * vector directional/action glyphs, while preserving exact original hitboxes, swipe D-pad support,
 * dynamic sizing, toggle behavior, and keycode dispatch.
 */
@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private static final String TAG = "ZyroxControls";

    private static final ColorStateList CONTROL_LABEL_COLORS = new ColorStateList(
            new int[][]{
                    new int[]{android.R.attr.state_pressed},
                    new int[]{android.R.attr.state_activated},
                    new int[]{}
            },
            new int[]{
                    ZyroxLiquidGlassDrawable.GLYPH_PRESSED_WHITE,
                    ZyroxLiquidGlassDrawable.GLYPH_PRESSED_WHITE,
                    0xF2FAFCFF
            }
    );

    private final ZyroxControlGlyphRenderer mGlyphRenderer = new ZyroxControlGlyphRenderer();
    protected ZyroxControlGlyphRenderer.Glyph mActiveGlyph = ZyroxControlGlyphRenderer.Glyph.NONE;

    /** Tracks the player's last active hotbar slot (1..9) so Panic Macros snap right back. */
    private static volatile int sLastActiveHotbarSlot = 1;

    protected ControlData mProperties;
    private final ControlLayout mControlLayout;
    private boolean mHasBitmap;
    protected boolean mIsToggled = false;

    // Smart Claw & PvP multi-gesture touch state (zero overhead when no combo bindings are set)
    private float mTouchDownX;
    private float mTouchDownY;
    private boolean mLongPressFired;
    private int mActiveSwipeKeycode = KeyEvent.KEYCODE_UNKNOWN;
    private boolean mPanicMacroBusy;
    private final Paint mComboIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable mLongPressRunnable = () -> {
        ControlData props = getProperties();
        if (props == null || props.longPressKeycode == KeyEvent.KEYCODE_UNKNOWN
                || mActiveSwipeKeycode != KeyEvent.KEYCODE_UNKNOWN || !isPressed()) {
            return;
        }
        mLongPressFired = true;
        triggerControlHaptic();
        dispatchSingleKeycode(props.longPressKeycode, true);
    };

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        float density = getResources().getDisplayMetrics().density;
        int padH = Math.max(2, Math.round(3f * density));
        int padV = Math.max(1, Math.round(2f * density));

        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(CONTROL_LABEL_COLORS);
        setShadowLayer(Math.max(2.2f, 2.4f * density), 0f, Math.max(1f, 1.0f * density),
                0xA8050A14);
        setLetterSpacing(0.025f);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setIncludeFontPadding(false);
        setMinWidth(0);
        setMinHeight(0);
        setMinimumWidth(0);
        setMinimumHeight(0);
        setPadding(padH, padV, padH, padV);

        setTypeface(ZyroxStudioUi.getDisplayFont(getContext()), Typeface.BOLD);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        setOutlineProvider(null);

        setProperties(preProcessProperties(properties, layout));
        injectBehaviors();
    }

    @Override
    public View getControlView() {
        return this;
    }

    @Override
    public ControlData getProperties() {
        return mProperties;
    }

    private void setupBitmapTint() {
        BackgroundTint.applyToggleTint(getContext());
        ColorStateList tintStateList = mProperties.isToggle ? TOGGLE_TINT_LIST : DEFAULT_TINT_LIST;
        setBackgroundTintList(tintStateList);
        setBackgroundTintMode(PorterDuff.Mode.SRC_ATOP);
    }

    private void setupLiquidGlassStyle() {
        setBackgroundTintList(null);
        setBackground();
    }

    @Override
    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);

        mHasBitmap = Tools.isValidString(mProperties.bitmapTag);
        if (mHasBitmap) {
            setupBitmapTint();
            mActiveGlyph = ZyroxControlGlyphRenderer.Glyph.NONE;
        } else {
            setupLiquidGlassStyle();
            mActiveGlyph = resolveControlGlyph(properties);
        }

        String displayLabel = resolveDisplayLabel(properties);
        setContentDescription(!displayLabel.isEmpty() ? displayLabel
                : (properties.name != null && !properties.name.isEmpty() ? properties.name : "Control"));
        if (mActiveGlyph != ZyroxControlGlyphRenderer.Glyph.NONE) {
            setText("");
        } else {
            setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
            applyGlyphSafeTypeface(displayLabel);
            setText(displayLabel);
            updateAdaptiveLabelSize(getWidth(), getHeight());
        }
        invalidate();
    }

    @NonNull
    protected ZyroxControlGlyphRenderer.Glyph resolveControlGlyph(@NonNull ControlData properties) {
        return ZyroxControlGlyphRenderer.resolveGlyph(properties.name, properties.keycodes);
    }

    /**
     * Returns the visible label for text controls, automatically generating a crisp short fallback
     * label (e.g. {@code PRI}, {@code SEC}, {@code F5}, {@code ESC}, {@code Q}) if an imported or
     * newly created control has an empty name alongside a configured keybind.
     */
    @NonNull
    protected String resolveDisplayLabel(@NonNull ControlData properties) {
        if (properties.name != null) {
            // Preserve intentional blank space (" ") if a user explicitly wants an unlabeled pad.
            if (!properties.name.isEmpty() && properties.name.trim().isEmpty()) {
                return "";
            }
            String trimmed = properties.name.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return inferKeybindFallbackLabel(properties.keycodes);
    }

    /** Switches to the full system bold font whenever a label contains symbols outside noto_sans_bold. */
    private void applyGlyphSafeTypeface(@NonNull String label) {
        Typeface preferred = ZyroxStudioUi.getDisplayFont(getContext());
        if (label.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            setTypeface(preferred, Typeface.BOLD);
            return;
        }
        Paint p = getPaint();
        p.setTypeface(preferred);
        boolean allSupported = true;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            int len = Character.charCount(cp);
            if (!Character.isWhitespace(cp) && !p.hasGlyph(label.substring(i, i + len))) {
                allSupported = false;
                break;
            }
            i += len;
        }
        setTypeface(allSupported ? preferred : Typeface.DEFAULT_BOLD, Typeface.BOLD);
    }

    @NonNull
    public static String inferKeybindFallbackLabel(@Nullable int[] keycodes) {
        if (keycodes == null) return "";
        int key = 0;
        for (int k : keycodes) {
            if (k != 0) {
                key = k;
                break;
            }
        }
        if (key == 0) return "";
        switch (key) {
            case ControlData.SPECIALBTN_KEYBOARD:
            case ControlData.SPECIALBTN_KEYBOARDPAN:
                return "KBD";
            case ControlData.SPECIALBTN_TOGGLECTRL:
                return "GUI";
            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                return "MOUSE";
            case ControlData.SPECIALBTN_MOUSEPRI:
                return "PRI";
            case ControlData.SPECIALBTN_MOUSESEC:
                return "SEC";
            case ControlData.SPECIALBTN_MOUSEMID:
                return "MID";
            case ControlData.SPECIALBTN_PANIC_PEARL:
                return "PEARL";
            case ControlData.SPECIALBTN_PANIC_TOTEM:
                return "TOTEM";
            case ControlData.SPECIALBTN_VOICE_CHAT:
                return "MIC";
            case ControlData.SPECIALBTN_RADIAL_MACRO:
                return "WHEEL";
            case LwjglGlfwKeycode.GLFW_KEY_ESCAPE:
                return "ESC";
            case LwjglGlfwKeycode.GLFW_KEY_TAB:
                return "TAB";
            case LwjglGlfwKeycode.GLFW_KEY_ENTER:
            case LwjglGlfwKeycode.GLFW_KEY_KP_ENTER:
                return "ENT";
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT:
                return "SHIFT";
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL:
                return "CTRL";
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT:
                return "ALT";
            case LwjglGlfwKeycode.GLFW_KEY_BACKSPACE:
                return "BKSP";
            default:
                if (key >= LwjglGlfwKeycode.GLFW_KEY_A && key <= LwjglGlfwKeycode.GLFW_KEY_Z) {
                    return String.valueOf((char) ('A' + (key - LwjglGlfwKeycode.GLFW_KEY_A)));
                }
                if (key >= LwjglGlfwKeycode.GLFW_KEY_0 && key <= LwjglGlfwKeycode.GLFW_KEY_9) {
                    return String.valueOf((char) ('0' + (key - LwjglGlfwKeycode.GLFW_KEY_0)));
                }
                if (key >= LwjglGlfwKeycode.GLFW_KEY_F1 && key <= LwjglGlfwKeycode.GLFW_KEY_F12) {
                    return "F" + (1 + (key - LwjglGlfwKeycode.GLFW_KEY_F1));
                }
                return "";
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mActiveGlyph == ZyroxControlGlyphRenderer.Glyph.NONE) {
            updateAdaptiveLabelSize(w, h);
        }
    }

    /**
     * Scales Liquid Glass label typography proportionally to button bounds so labels like
     * {@code KEYBOARD}, {@code DEBUG}, {@code MOUSE}, {@code CHAT}, {@code 3RD}, {@code PRI},
     * {@code SEC}, {@code GUI}, {@code INV}, and {@code TAB} stay crisp and never clip or vanish.
     */
    private void updateAdaptiveLabelSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        CharSequence raw = getText();
        if (raw == null || raw.length() == 0) return;

        String label = LauncherPreferences.PREF_BUTTON_ALL_CAPS
                ? raw.toString().toUpperCase(Locale.ROOT)
                : raw.toString();
        float density = getResources().getDisplayMetrics().density;
        float maxTextPx = Math.min(h * 0.38f, 15.5f * density);
        float minTextPx = Math.max(6.5f * density, 8f);
        float availW = Math.max(10f, w - getPaddingLeft() - getPaddingRight() - 2f * density);

        float candidate = Math.min(maxTextPx, availW / Math.max(1.8f, label.length() * 0.64f));
        candidate = Math.max(minTextPx, Math.min(maxTextPx, candidate));
        setTextSize(TypedValue.COMPLEX_UNIT_PX, candidate);

        float measured = getPaint().measureText(label);
        if (measured > availW && measured > 1f) {
            float scaled = Math.max(minTextPx, candidate * (availW / measured) * 0.97f);
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaled);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mHasBitmap && mActiveGlyph != ZyroxControlGlyphRenderer.Glyph.NONE) {
            mGlyphRenderer.drawGlyph(canvas, mActiveGlyph, getWidth(), getHeight(),
                    isPressed() || isActivated());
        }
        if (mProperties != null && mProperties.hasGestureCombos()) {
            drawGestureComboIndicators(canvas);
        }
    }

    /** Renders subtle specular micro-chevrons indicating configured Swipe-Up / Swipe-Down / Hold combos. */
    private void drawGestureComboIndicators(@NonNull Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float cx = getWidth() * 0.5f;
        float chevW = Math.max(3f, 3.4f * d);
        float chevH = Math.max(1.8f, 2.1f * d);
        float inset = Math.max(2.5f, 3.0f * d);
        mComboIndicatorPaint.setColor(0xB8F6D37A);
        mComboIndicatorPaint.setStyle(Paint.Style.STROKE);
        mComboIndicatorPaint.setStrokeWidth(Math.max(1.1f, 1.25f * d));
        mComboIndicatorPaint.setStrokeCap(Paint.Cap.ROUND);
        if (mProperties.swipeUpKeycode != KeyEvent.KEYCODE_UNKNOWN) {
            canvas.drawLine(cx - chevW, inset + chevH, cx, inset, mComboIndicatorPaint);
            canvas.drawLine(cx, inset, cx + chevW, inset + chevH, mComboIndicatorPaint);
        }
        if (mProperties.swipeDownKeycode != KeyEvent.KEYCODE_UNKNOWN) {
            float by = getHeight() - inset;
            canvas.drawLine(cx - chevW, by - chevH, cx, by, mComboIndicatorPaint);
            canvas.drawLine(cx, by, cx + chevW, by - chevH, mComboIndicatorPaint);
        }
        if (mProperties.longPressKeycode != KeyEvent.KEYCODE_UNKNOWN) {
            mComboIndicatorPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(getWidth() - inset - (1.8f * d), inset + (1.8f * d),
                    Math.max(1.4f, 1.5f * d), mComboIndicatorPaint);
        }
    }

    @Override
    public boolean isActivated() {
        return super.isActivated() || (mProperties != null && mProperties.isToggle && mIsToggled);
    }

    @Override
    public void loadEditValues(EditControlSideDialog editControlPopup) {
        editControlPopup.loadValues(getProperties());
    }

    @Override
    public void cloneButton() {
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    @Override
    public void removeButton() {
        ControlLayout parent = getControlLayoutParent();
        if (parent == null) return;
        parent.getLayout().mControlDataList.remove(getProperties());
        parent.removeView(this);
    }

    @Override
    public void handlePressed() {
        setPressed(true);
        if (!getProperties().isToggle) {
            sendKeyPresses(true);
        }
    }

    @Override
    public void handleReleased() {
        setPressed(false);
        if (!triggerToggle()) {
            sendKeyPresses(false);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ControlData properties = getProperties();
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (properties.passThruEnabled) {
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if (gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                break;
        }

        if (properties.isSwipeable) {
            getControlLayoutParent().onTouch(this, event);
            return true;
        }

        if (properties.hasGestureCombos()) {
            return handleMultiGestureTouch(properties, event, action);
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                triggerControlHaptic();
                handlePressed();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                handleReleased();
                break;
            default:
                return false;
        }

        return super.onTouchEvent(event);
    }

    private boolean handleMultiGestureTouch(@NonNull ControlData props,
                                            @NonNull MotionEvent event,
                                            int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownX = event.getX();
                mTouchDownY = event.getY();
                mLongPressFired = false;
                mActiveSwipeKeycode = KeyEvent.KEYCODE_UNKNOWN;
                setPressed(true);
                triggerControlHaptic();
                if (props.longPressKeycode != KeyEvent.KEYCODE_UNKNOWN) {
                    long delayMs = Math.max(160L,
                            Math.min(800L, LauncherPreferences.PREF_LONGPRESS_TRIGGER));
                    removeCallbacks(mLongPressRunnable);
                    postDelayed(mLongPressRunnable, delayMs);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mLongPressFired || mActiveSwipeKeycode != KeyEvent.KEYCODE_UNKNOWN) {
                    return true;
                }
                float dx = event.getX() - mTouchDownX;
                float dy = event.getY() - mTouchDownY;
                float swipeThresholdPx = Math.max(24f,
                        18f * getResources().getDisplayMetrics().density);
                if (Math.abs(dy) >= swipeThresholdPx && Math.abs(dy) > Math.abs(dx) * 0.85f) {
                    int candidate = dy < 0 ? props.swipeUpKeycode : props.swipeDownKeycode;
                    if (candidate != KeyEvent.KEYCODE_UNKNOWN) {
                        removeCallbacks(mLongPressRunnable);
                        mActiveSwipeKeycode = candidate;
                        triggerControlHaptic();
                        dispatchSingleKeycode(candidate, true);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(mLongPressRunnable);
                setPressed(false);
                if (mLongPressFired) {
                    dispatchSingleKeycode(props.longPressKeycode, false);
                    mLongPressFired = false;
                } else if (mActiveSwipeKeycode != KeyEvent.KEYCODE_UNKNOWN) {
                    dispatchSingleKeycode(mActiveSwipeKeycode, false);
                    mActiveSwipeKeycode = KeyEvent.KEYCODE_UNKNOWN;
                } else if (!triggerToggle()) {
                    // Clean tap: fire primary bound action immediately
                    sendKeyPresses(true);
                    postDelayed(() -> sendKeyPresses(false), 32L);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mLongPressRunnable);
                setPressed(false);
                if (mLongPressFired) {
                    dispatchSingleKeycode(props.longPressKeycode, false);
                    mLongPressFired = false;
                }
                if (mActiveSwipeKeycode != KeyEvent.KEYCODE_UNKNOWN) {
                    dispatchSingleKeycode(mActiveSwipeKeycode, false);
                    mActiveSwipeKeycode = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;

            default:
                return false;
        }
    }

    private void triggerControlHaptic() {
        if (LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS) {
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle() {
        if (mProperties.isToggle) {
            mIsToggled = !mIsToggled;
            refreshDrawableState();
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown) {
        setActivated(isDown || (mProperties.isToggle && mIsToggled));
        refreshDrawableState();
        for (int keycode : mProperties.keycodes) {
            dispatchSingleKeycode(keycode, isDown);
        }
    }

    private void dispatchSingleKeycode(int keycode, boolean isDown) {
        if (keycode == KeyEvent.KEYCODE_UNKNOWN) return;
        if (keycode > KeyEvent.KEYCODE_UNKNOWN) {
            if (isDown && keycode >= LwjglGlfwKeycode.GLFW_KEY_1
                    && keycode <= LwjglGlfwKeycode.GLFW_KEY_9) {
                sLastActiveHotbarSlot = 1 + (keycode - LwjglGlfwKeycode.GLFW_KEY_1);
            }
            CallbackBridge.setModifiers(keycode, isDown);
            int modifiers = CallbackBridge.getCurrentMods();
            PLATFORM.sendKeyEvent(keycode, isDown ? 1 : 0, modifiers);
        } else {
            Log.d(TAG, "sendSpecialKey(" + keycode + "," + isDown + ")");
            sendSpecialKey(keycode, isDown);
        }
    }

    /**
     * Executes a 1-gesture PvP Panic Macro: switches to the configured utility hotbar slot,
     * either right-clicks to throw/clutch (Pearl/Water/Splash Potion) or taps F to swap Totem
     * into offhand, then immediately restores the player's previous weapon hotbar slot.
     */
    private void executeSmartPanicMacro(boolean offhandTotemSwap) {
        if (mPanicMacroBusy || mProperties == null) return;
        mPanicMacroBusy = true;
        final int targetSlot = mProperties.getEffectivePanicMacroSlot();
        final int returnSlot = (sLastActiveHotbarSlot >= 1 && sLastActiveHotbarSlot <= 9)
                ? sLastActiveHotbarSlot : 1;
        final int targetKey = LwjglGlfwKeycode.GLFW_KEY_1 + (targetSlot - 1);
        final int returnKey = LwjglGlfwKeycode.GLFW_KEY_1 + (returnSlot - 1);

        // Step 1: Select utility slot
        tapGlfwKey(targetKey);

        // Step 2: Use item (Pearl / Water / Splash Potion) or swap into offhand (Totem)
        postDelayed(() -> {
            if (offhandTotemSwap) {
                tapGlfwKey(LwjglGlfwKeycode.GLFW_KEY_F);
            } else {
                sendMouseButton(MotionEvent.BUTTON_SECONDARY, true);
                postDelayed(() -> sendMouseButton(MotionEvent.BUTTON_SECONDARY, false), 28L);
            }
            // Step 3: Snap back to previous combat hotbar slot
            postDelayed(() -> {
                if (returnSlot != targetSlot) {
                    tapGlfwKey(returnKey);
                }
                mPanicMacroBusy = false;
            }, 48L);
        }, 28L);
    }

    private static void tapGlfwKey(int glfwKey) {
        int mods = CallbackBridge.getCurrentMods();
        PLATFORM.sendKeyEvent(glfwKey, 1, mods);
        PLATFORM.sendKeyEvent(glfwKey, 0, mods);
    }

    private void sendSpecialKey(int keycode, boolean isDown) {
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if (isDown) GameActivity.switchKeyboardState(false);
                break;
            case ControlData.SPECIALBTN_KEYBOARDPAN:
                if (isDown) GameActivity.switchKeyboardState(true);
                break;
            case ControlData.SPECIALBTN_TOGGLECTRL:
                if (isDown) getControlLayoutParent().toggleControlVisible();
                break;
            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if (isDown) GameActivity.toggleMouse(getContext());
                break;
            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(MotionEvent.BUTTON_PRIMARY, isDown);
                break;
            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(MotionEvent.BUTTON_TERTIARY, isDown);
                break;
            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(MotionEvent.BUTTON_SECONDARY, isDown);
                break;
            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;
            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;
            case ControlData.SPECIALBTN_PANIC_PEARL:
                if (isDown) executeSmartPanicMacro(false);
                break;
            case ControlData.SPECIALBTN_PANIC_TOTEM:
                if (isDown) executeSmartPanicMacro(true);
                break;
            case ControlData.SPECIALBTN_VOICE_CHAT:
                GameActivity.triggerVoiceChatFromControl(isDown);
                break;
            case ControlData.SPECIALBTN_RADIAL_MACRO:
                if (isDown) GameActivity.openQuickMacroWheelFromControl();
                break;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mLongPressRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
