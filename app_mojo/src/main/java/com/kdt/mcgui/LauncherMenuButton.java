package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;

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
        int padH = Math.round(10f * density);
        int iconPad = Math.round(10f * density);
        int iconSize = Math.round(18f * density);

        setCompoundDrawablePadding(iconPad);
        setPaddingRelative(padH, 0, padH, 0);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setAllCaps(true);
        setLetterSpacing(0.06f);
        setSingleLine(true);
        setTextColor(Color.parseColor("#FF8C42"));
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);

        Typeface pixelFont = ResourcesCompat.getFont(getContext(), R.font.minecraft_ten);
        if (pixelFont != null) {
            setTypeface(pixelFont);
        }

        int[] sizes = getExtendedViewData().getSizeCompounds();
        sizes[0] = iconSize;
        getExtendedViewData().setSizeCompounds(sizes);
        postProcessDrawables();
    }
}
