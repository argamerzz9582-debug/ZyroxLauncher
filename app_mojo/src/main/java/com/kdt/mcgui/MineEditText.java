package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;

import androidx.core.content.res.ResourcesCompat;

import git.artdeell.mojo.R;

public class MineEditText extends androidx.appcompat.widget.AppCompatEditText {
	public MineEditText(Context ctx) {
		super(ctx);
		init();
	}

	public MineEditText(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.bg_zyrox_input, getContext().getTheme()));
		setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.zyrox_text_primary));
		setHintTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.zyrox_text_muted));
		int padH = Math.round(12f * getResources().getDisplayMetrics().density);
		int padV = Math.round(8f * getResources().getDisplayMetrics().density);
		setPadding(padH, padV, padH, padV);
	}
}
