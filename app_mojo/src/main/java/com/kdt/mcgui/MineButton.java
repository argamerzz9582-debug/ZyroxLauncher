package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Primary Zyrox Liquid Glass button used across launcher call-to-action controls.
 */
public class MineButton extends androidx.appcompat.widget.AppCompatButton {

	private static final Interpolator TOUCH_INTERPOLATOR = new DecelerateInterpolator(1.6f);

	public MineButton(Context ctx) {
		this(ctx, null);
	}

	public MineButton(Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		boolean hasExplicitTextSize = attrs != null
				&& attrs.getAttributeValue("http://schemas.android.com/apk/res/android", "textSize") != null;
		init(hasExplicitTextSize);
	}

	public void init() {
		init(false);
	}

	private void init(boolean preserveXmlTextSize) {
		float density = getResources().getDisplayMetrics().density;
		int origPadStart = getPaddingStart();
		int origPadEnd = getPaddingEnd();

		setTypeface(ZyroxStudioUi.getDisplayFont(getContext()), Typeface.BOLD);
		// Disable opaque Material rectangular drop-shadow so crystal Liquid Glass stays pure
		setStateListAnimator(null);
		setElevation(0f);
		if (getBackground() == null || !(getTag() != null && "custom_bg".equals(getTag().toString()))) {
			setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.btn_zyrox_play, getContext().getTheme()));
		}
		int defaultPadH = Math.round(11f * density);
		setMinWidth(0);
		setMinHeight(0);
		setMinimumWidth(0);
		setMinimumHeight(0);
		setIncludeFontPadding(false);
		setGravity(Gravity.CENTER);
		setPaddingRelative(
				origPadStart > 0 ? origPadStart : defaultPadH,
				0,
				origPadEnd > 0 ? origPadEnd : defaultPadH,
				0
		);
		setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
		setShadowLayer(4f, 0f, 1.2f, ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
		setAllCaps(true);
		setLetterSpacing(0.045f);
		if (!preserveXmlTextSize) {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (isEnabled() && !LauncherPreferences.isReducedMotionEnabled(getContext())) {
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN) {
				animate().cancel();
				animate().scaleX(0.976f).scaleY(0.976f)
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
