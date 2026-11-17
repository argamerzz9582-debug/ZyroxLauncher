package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.core.content.res.ResourcesCompat;

import git.artdeell.mojo.R;

public class MineButton extends androidx.appcompat.widget.AppCompatButton {

	public MineButton(Context ctx) {
		this(ctx, null);
	}

	public MineButton(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		Typeface pixelFont = ResourcesCompat.getFont(getContext(), R.font.minecraft_ten);
		if (pixelFont != null) {
			setTypeface(pixelFont);
		} else {
			setTypeface(ResourcesCompat.getFont(getContext(), R.font.noto_sans_bold));
		}
		if (getBackground() == null || !(getTag() != null && "custom_bg".equals(getTag().toString()))) {
			setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.btn_zyrox_play, getContext().getTheme()));
		}
		setTextColor(Color.parseColor("#FFF7ED"));
		setAllCaps(true);
		setLetterSpacing(0.06f);
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
	}
}
