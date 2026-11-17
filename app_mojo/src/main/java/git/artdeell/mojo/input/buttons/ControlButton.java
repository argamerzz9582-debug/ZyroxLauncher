package git.artdeell.mojo.input.buttons;

import static git.artdeell.mojo.CallbackBridge.sendMouseButton;
import static git.artdeell.mojo.game.platform.Platform.PLATFORM;
import static git.artdeell.mojo.input.buttons.BackgroundTint.DEFAULT_TINT_LIST;
import static git.artdeell.mojo.input.buttons.BackgroundTint.TOGGLE_TINT_LIST;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.game.GameActivity;
import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.input.handleview.EditControlSideDialog;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxControlGlyphRenderer;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;

/**
 * In-game touch control button rendered with the Zyrox Liquid Glass optical engine and crisp
 * vector directional/action glyphs, while preserving exact original hitboxes, swipe D-pad support,
 * dynamic sizing, toggle behavior, and keycode dispatch.
 */
@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private static final String TAG = "ZyroxControls";

    private final ZyroxControlGlyphRenderer mGlyphRenderer = new ZyroxControlGlyphRenderer();
    private ZyroxControlGlyphRenderer.Glyph mActiveGlyph = ZyroxControlGlyphRenderer.Glyph.NONE;

    protected ControlData mProperties;
    private final ControlLayout mControlLayout;
    private boolean mHasBitmap;
    protected boolean mIsToggled = false;

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        float density = getResources().getDisplayMetrics().density;
        int padH = Math.max(4, Math.round(5f * density));
        int padV = Math.max(2, Math.round(3f * density));

        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        setShadowLayer(Math.max(2f, 2.2f * density), 0f, Math.max(1f, 1.0f * density),
                ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
        setLetterSpacing(0.025f);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setIncludeFontPadding(false);
        setPadding(padH, padV, padH, padV);

        Typeface boldSans = ResourcesCompat.getFont(getContext(), R.font.noto_sans_bold);
        if (boldSans != null) {
            setTypeface(boldSans, Typeface.BOLD);
        } else {
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        }
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
            mActiveGlyph = ZyroxControlGlyphRenderer.resolveGlyph(properties.name, properties.keycodes);
        }

        setContentDescription(properties.name != null ? properties.name : "Control");
        if (mActiveGlyph != ZyroxControlGlyphRenderer.Glyph.NONE) {
            setText("");
        } else {
            setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
            setText(properties.name != null ? properties.name : "");
            updateAdaptiveLabelSize(getWidth(), getHeight());
        }
        invalidate();
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
     * {@code SEC}, {@code GUI}, {@code INV}, and {@code TAB} stay crisp and never clip.
     */
    private void updateAdaptiveLabelSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        CharSequence raw = getText();
        if (raw == null || raw.length() == 0) return;

        String label = LauncherPreferences.PREF_BUTTON_ALL_CAPS
                ? raw.toString().toUpperCase()
                : raw.toString();
        float density = getResources().getDisplayMetrics().density;
        float maxTextPx = Math.min(h * 0.36f, 16.5f * density);
        float minTextPx = Math.max(7.5f * density, 9f);
        float availW = Math.max(8f, w - getPaddingLeft() - getPaddingRight() - 8f * density);

        // Approximate width per bold uppercase glyph (~0.66em) and refine with Paint measurement
        float candidate = Math.min(maxTextPx, availW / Math.max(2.2f, label.length() * 0.68f));
        candidate = Math.max(minTextPx, Math.min(maxTextPx, candidate));
        setTextSize(TypedValue.COMPLEX_UNIT_PX, candidate);

        float measured = getPaint().measureText(label);
        if (measured > availW && measured > 1f) {
            float scaled = Math.max(minTextPx, candidate * (availW / measured));
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaled);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mHasBitmap && mActiveGlyph != ZyroxControlGlyphRenderer.Glyph.NONE) {
            mGlyphRenderer.drawGlyph(canvas, mActiveGlyph, getWidth(), getHeight());
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

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
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
            if (keycode >= KeyEvent.KEYCODE_UNKNOWN) {
                CallbackBridge.setModifiers(keycode, isDown);
                int modifiers = CallbackBridge.getCurrentMods();
                PLATFORM.sendKeyEvent(keycode, isDown ? 1 : 0, modifiers);
            } else {
                Log.d(TAG, "sendSpecialKey(" + keycode + "," + isDown + ")");
                sendSpecialKey(keycode, isDown);
            }
        }
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
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
