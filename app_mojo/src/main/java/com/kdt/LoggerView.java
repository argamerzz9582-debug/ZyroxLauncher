package com.kdt;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableStringBuilder;
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
    private static final int MAX_BUFFER_CHARS = 72 * 1024;
    private static final long BATCH_FLUSH_INTERVAL_MS = 48L;

    private final Object mBatchLock = new Object();
    private final SpannableStringBuilder mPendingBatch = new SpannableStringBuilder();
    private boolean mFlushScheduled = false;

    private Logger.eventLogListener mLogListener;
    private ToggleButton mLogToggle;
    private DefocusableScrollView mScrollView;
    private TextView mLogTextView;

    private final Runnable mFlushRunnable = this::flushPendingBatch;

    private void flushPendingBatch() {
        CharSequence batch;
        synchronized (mBatchLock) {
            mFlushScheduled = false;
            if (mPendingBatch.length() == 0) return;
            batch = new SpannableStringBuilder(mPendingBatch);
            mPendingBatch.clear();
        }
        if (mLogTextView == null || mLogTextView.getVisibility() != VISIBLE) return;
        if (mLogTextView.length() + batch.length() > MAX_BUFFER_CHARS) {
            CharSequence cur = mLogTextView.getText();
            int trim = Math.min(cur.length(), Math.max(cur.length() / 3, batch.length()));
            if (cur instanceof Editable) {
                ((Editable) cur).delete(0, trim);
            } else {
                mLogTextView.setText(cur.subSequence(trim, cur.length()));
            }
        }
        mLogTextView.append(batch);
        if (mScrollView != null && mScrollView.isKeepFocusing()) {
            mScrollView.fullScroll(View.FOCUS_DOWN);
        }
    }

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
                        Logger.addLogListener(mLogListener);
                    } else {
                        mLogTextView.setText("");
                        Logger.removeLogListener(mLogListener);
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
            boolean shouldSchedule = false;
            synchronized (mBatchLock) {
                mPendingBatch.append(styled);
                if (!mFlushScheduled) {
                    mFlushScheduled = true;
                    shouldSchedule = true;
                }
            }
            if (shouldSchedule) {
                postDelayed(mFlushRunnable, BATCH_FLUSH_INTERVAL_MS);
            }
        };
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mFlushRunnable);
        if (mLogListener != null) {
            Logger.removeLogListener(mLogListener);
        }
        synchronized (mBatchLock) {
            mPendingBatch.clear();
            mFlushScheduled = false;
        }
        super.onDetachedFromWindow();
    }
}
