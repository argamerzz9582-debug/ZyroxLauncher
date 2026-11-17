package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Color;
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
        setLetterSpacing(0.05f);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setTextColor(Color.parseColor("#FFF9F2"));
        setShadowLayer(4f, 0f, 1.2f, 0xBF090705);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);

        Typeface pixelFont = ResourcesCompat.getFont(getContext(), R.font.minecraft_ten);
        if (pixelFont != null) {
            setTypeface(pixelFont);
        }

        int[] sizes = getExtendedViewData().getSizeCompounds();
        sizes[0] = iconSize;
        getExtendedViewData().setSizeCompounds(sizes);
        postProcessDrawables();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isEnabled()) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                animate().scaleX(0.965f).scaleY(0.965f).setDuration(90L).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                animate().scaleX(1f).scaleY(1f).setDuration(130L).start();
            }
        }
        return super.onTouchEvent(event);
    }
}
