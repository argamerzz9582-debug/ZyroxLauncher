package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;

import androidx.core.content.res.ResourcesCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;

/**
 * Primary Zyrox 2.0 Liquid Glass button used across launcher call-to-action controls.
 */
public class MineButton extends androidx.appcompat.widget.AppCompatButton {

	public MineButton(Context ctx) {
		this(ctx, null);
	}

	public MineButton(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		Typeface boldFont = ResourcesCompat.getFont(getContext(), R.font.noto_sans_bold);
		if (boldFont != null) {
			setTypeface(boldFont, Typeface.BOLD);
		} else {
			Typeface pixelFont = ResourcesCompat.getFont(getContext(), R.font.minecraft_ten);
			if (pixelFont != null) setTypeface(pixelFont);
		}
		// Disable opaque Material rectangular drop-shadow so crystal Liquid Glass stays pure
		setStateListAnimator(null);
		setElevation(0f);
		if (getBackground() == null || !(getTag() != null && "custom_bg".equals(getTag().toString()))) {
			setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.btn_zyrox_play, getContext().getTheme()));
		}
		setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
		setShadowLayer(4f, 0f, 1.2f, ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
		setAllCaps(true);
		setLetterSpacing(0.05f);
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (isEnabled() && !LauncherPreferences.isReducedMotionEnabled(getContext())) {
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN) {
				animate().scaleX(0.975f).scaleY(0.975f).setDuration(80L).start();
			} else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				animate().scaleX(1f).scaleY(1f).setDuration(130L).start();
			}
		}
		return super.onTouchEvent(event);
	}
}
