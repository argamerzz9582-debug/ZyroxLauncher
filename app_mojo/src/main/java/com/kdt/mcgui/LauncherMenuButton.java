package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.spse.extended_view.ExtendedButton;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Zyrox Liquid Glass quick-action menu tile button with cached typography and unclipped labels.
 */
public class LauncherMenuButton extends ExtendedButton {

    private static final Interpolator TOUCH_INTERPOLATOR = new DecelerateInterpolator(1.6f);

    public LauncherMenuButton(@NonNull Context context) {
        super(context);
        setSettings();
    }

    public LauncherMenuButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setSettings();
    }

    private void setSettings() {
        float density = getContext().getResources().getDisplayMetrics().density;
        int padStart = Math.round(7.5f * density);
        int padEnd = Math.round(5.5f * density);
        int iconPad = Math.round(6f * density);
        int iconSize = Math.round(14.5f * density);

        setStateListAnimator(null);
        setElevation(0f);
        setMinWidth(0);
        setMinHeight(0);
        setMinimumWidth(0);
        setMinimumHeight(0);
        setIncludeFontPadding(false);
        setCompoundDrawablePadding(iconPad);
        setPaddingRelative(padStart, 0, padEnd, 0);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setAllCaps(true);
        setLetterSpacing(0.02f);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        setShadowLayer(3.5f, 0f, 1.0f, ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.25f);
        setTypeface(ZyroxStudioUi.getDisplayFont(getContext()), Typeface.BOLD);

        int[] sizes = getExtendedViewData().getSizeCompounds();
        sizes[0] = iconSize;
        getExtendedViewData().setSizeCompounds(sizes);
        postProcessDrawables();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isEnabled() && !LauncherPreferences.isReducedMotionEnabled(getContext())) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                animate().cancel();
                animate().scaleX(0.972f).scaleY(0.972f)
                        .setDuration(65L)
                        .setInterpolator(TOUCH_INTERPOLATOR)
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                animate().cancel();
                animate().scaleX(1f).scaleY(1f)
                        .setDuration(110L)
                        .setInterpolator(TOUCH_INTERPOLATOR)
                        .start();
            }
        }
        return super.onTouchEvent(event);
    }
}
