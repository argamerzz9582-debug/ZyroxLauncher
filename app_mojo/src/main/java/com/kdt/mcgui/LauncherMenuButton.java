package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import fr.spse.extended_view.ExtendedButton;
import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;

/**
 * Zyrox 2.0 Liquid Glass quick-action menu tile button.
 */
public class LauncherMenuButton extends ExtendedButton {

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
        int padH = Math.round(9f * density);
        int iconPad = Math.round(7f * density);
        int iconSize = Math.round(15f * density);

        setStateListAnimator(null);
        setElevation(0f);
        setCompoundDrawablePadding(iconPad);
        setPaddingRelative(padH, 0, padH, 0);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setAllCaps(true);
        setLetterSpacing(0.035f);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        setShadowLayer(3.5f, 0f, 1.0f, ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);

        Typeface boldFont = ResourcesCompat.getFont(getContext(), R.font.noto_sans_bold);
        if (boldFont != null) {
            setTypeface(boldFont, Typeface.BOLD);
        }

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
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                animate().scaleX(1f).scaleY(1f).setDuration(125L).start();
            }
        }
        return super.onTouchEvent(event);
    }
}
