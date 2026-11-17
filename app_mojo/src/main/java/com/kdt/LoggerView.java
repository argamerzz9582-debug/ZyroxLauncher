package com.kdt;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import git.artdeell.mojo.Logger;
import git.artdeell.mojo.R;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;

/**
 * Zyrox Live JVM Console overlay view with real-time error (#EF4444) and warning (#FF8C42) highlighting.
 */
public class LoggerView extends ConstraintLayout {
    private static final int MAX_BUFFER_CHARS = 96 * 1024;

    private Logger.eventLogListener mLogListener;
    private ToggleButton mLogToggle;
    private DefocusableScrollView mScrollView;
    private TextView mLogTextView;

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        mLogToggle.setChecked(visibility == VISIBLE);
    }

    private void init() {
        inflate(getContext(), R.layout.view_logger, this);
        mLogTextView = findViewById(R.id.content_log_view);
        mLogTextView.setTypeface(Typeface.MONOSPACE);
        mLogTextView.setMaxLines(Integer.MAX_VALUE);
        mLogTextView.setEllipsize(null);
        mLogTextView.setVisibility(GONE);

        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mLogTextView.setVisibility(isChecked ? VISIBLE : GONE);
                    if (isChecked) {
                        Logger.setLogListener(mLogListener);
                    } else {
                        mLogTextView.setText("");
                        Logger.setLogListener(null);
                    }
                });
        mLogToggle.setChecked(false);

        ImageButton cancelButton = findViewById(R.id.log_view_cancel);
        cancelButton.setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        mScrollView = findViewById(R.id.content_log_scroll);
        mScrollView.setKeepFocusing(true);

        ToggleButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if (isChecked) mScrollView.fullScroll(View.FOCUS_DOWN);
                    mScrollView.setKeepFocusing(isChecked);
                }
        );
        autoscrollToggle.setChecked(true);

        mLogListener = text -> {
            if (mLogTextView.getVisibility() != VISIBLE) return;
            CharSequence styled = ZyroxLogHighlighter.highlightLine(text);
            post(() -> {
                if (mLogTextView.length() > MAX_BUFFER_CHARS) {
                    CharSequence cur = mLogTextView.getText();
                    if (cur instanceof Editable) {
                        ((Editable) cur).delete(0, cur.length() / 3);
                    } else {
                        mLogTextView.setText(cur.subSequence(cur.length() / 3, cur.length()));
                    }
                }
                mLogTextView.append(styled);
                if (mScrollView.isKeepFocusing()) mScrollView.fullScroll(View.FOCUS_DOWN);
            });
        };
    }
}
