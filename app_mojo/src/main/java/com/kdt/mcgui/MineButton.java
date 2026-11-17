package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;

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
		// Disable opaque Material rectangular drop-shadow so crystal glass stays 100% transparent
		setStateListAnimator(null);
		setElevation(0f);
		if (getBackground() == null || !(getTag() != null && "custom_bg".equals(getTag().toString()))) {
			setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.btn_zyrox_play, getContext().getTheme()));
		}
		setTextColor(Color.parseColor("#FFF9F2"));
		setShadowLayer(6f, 0f, 1.5f, 0xCC120804);
		setAllCaps(true);
		setLetterSpacing(0.08f);
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (isEnabled()) {
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN) {
				animate().scaleX(0.97f).scaleY(0.97f).setDuration(85L).start();
			} else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
			}
		}
		return super.onTouchEvent(event);
	}
}
