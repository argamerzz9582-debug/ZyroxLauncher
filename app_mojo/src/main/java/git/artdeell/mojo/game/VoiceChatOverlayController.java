package git.artdeell.mojo.game;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

import git.artdeell.mojo.input.keyboard.TouchCharInput;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;

/**
 * Hold-to-speak in-game Voice-to-Chat floating overlay powered by Android's native
 * {@link SpeechRecognizer}.
 *
 * <p>Holding the compact Liquid Glass Mic button captures speech without opening the screen-blocking
 * soft keyboard, previews partial text in a non-intrusive floating caption pill, and automatically
 * dispatches {@code T} &rarr; message &rarr; {@code Enter} via
 * {@link TouchCharInput#dispatchVoiceChatMessage(String)} on release.</p>
 */
public final class VoiceChatOverlayController implements View.OnTouchListener {

    public static final int REQUEST_CODE_VOICE_CHAT_MIC = 4192;

    @NonNull private final Activity mActivity;
    @NonNull private final ViewGroup mRootContainer;
    @NonNull private final TextView mMicPill;
    @NonNull private final TextView mTranscriptBubble;
    private final float mDensity;
    private final int mTouchSlop;

    @Nullable private SpeechRecognizer mRecognizer;
    private boolean mOverlayEnabled;
    private boolean mListening;
    private boolean mDragging;
    private float mDownRawX;
    private float mDownRawY;
    private float mStartViewX;
    private float mStartViewY;
    @NonNull private String mLatestPartialText = "";

    @SuppressLint("ClickableViewAccessibility")
    public VoiceChatOverlayController(@NonNull Activity activity,
                                      @NonNull ViewGroup rootContainer) {
        mActivity = activity;
        mRootContainer = rootContainer;
        mDensity = Math.max(1f, activity.getResources().getDisplayMetrics().density);
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        // Compact floating Liquid Glass Mic button
        mMicPill = new TextView(activity);
        mMicPill.setText("MIC");
        mMicPill.setGravity(Gravity.CENTER);
        mMicPill.setTypeface(Typeface.DEFAULT_BOLD);
        mMicPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        mMicPill.setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        mMicPill.setShadowLayer(2.5f * mDensity, 0f, 1f * mDensity, 0xC0050A14);
        mMicPill.setElevation(9f * mDensity);
        mMicPill.setPadding(dp(10), dp(6), dp(10), dp(6));
        applyMicVisualState(false);
        mMicPill.setOnTouchListener(this);
        mMicPill.setVisibility(View.GONE);

        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        micLp.gravity = Gravity.TOP | Gravity.END;
        micLp.topMargin = dp(52);
        micLp.rightMargin = dp(14);
        rootContainer.addView(mMicPill, micLp);

        // Live speech preview bubble (top-center, pass-through to gameplay)
        mTranscriptBubble = new TextView(activity);
        mTranscriptBubble.setGravity(Gravity.CENTER);
        mTranscriptBubble.setTypeface(Typeface.DEFAULT_BOLD);
        mTranscriptBubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mTranscriptBubble.setTextColor(0xFFF8FBFF);
        mTranscriptBubble.setShadowLayer(2.5f * mDensity, 0f, 1f * mDensity, 0xC0050A14);
        mTranscriptBubble.setPadding(dp(14), dp(6), dp(14), dp(6));
        mTranscriptBubble.setElevation(10f * mDensity);
        mTranscriptBubble.setBackground(new ZyroxLiquidGlassDrawable(mDensity, true));
        mTranscriptBubble.setClickable(false);
        mTranscriptBubble.setFocusable(false);
        mTranscriptBubble.setVisibility(View.GONE);

        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        capLp.topMargin = dp(16);
        rootContainer.addView(mTranscriptBubble, capLp);
    }

    /** Shows or hides the floating hold-to-speak microphone pill. */
    public void setOverlayEnabled(boolean enabled) {
        mOverlayEnabled = enabled;
        if (!enabled) {
            cancelListening();
            mMicPill.setVisibility(View.GONE);
        } else {
            mMicPill.setVisibility(View.VISIBLE);
            applyMicVisualState(false);
        }
    }

    public boolean isOverlayEnabled() {
        return mOverlayEnabled;
    }

    /** Temporarily hides the pill while in the control-layout editor. */
    public void setEditorHidden(boolean hidden) {
        if (hidden) {
            cancelListening();
            mMicPill.setVisibility(View.GONE);
        } else if (mOverlayEnabled) {
            mMicPill.setVisibility(View.VISIBLE);
        }
    }

    /** Handles hold/release from a custom {@code SPECIALBTN_VOICE_CHAT} control button. */
    public void onControlButtonState(boolean isDown) {
        if (isDown) {
            startHoldToSpeak();
        } else {
            finishHoldToSpeak();
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = false;
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mStartViewX = v.getX();
                mStartViewY = v.getY();
                startHoldToSpeak();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (!mDragging && Math.hypot(dx, dy) > mTouchSlop * 2.2f) {
                    // User dragged the pill to reposition — cancel voice capture cleanly
                    mDragging = true;
                    cancelListening();
                }
                if (mDragging) {
                    float maxX = Math.max(dp(6), mRootContainer.getWidth() - v.getWidth() - dp(6));
                    float maxY = Math.max(dp(6), mRootContainer.getHeight() - v.getHeight() - dp(6));
                    v.setX(Math.max(dp(6), Math.min(maxX, mStartViewX + dx)));
                    v.setY(Math.max(dp(6), Math.min(maxY, mStartViewY + dy)));
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!mDragging) {
                    finishHoldToSpeak();
                }
                mDragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                cancelListening();
                return true;

            default:
                return false;
        }
    }

    private void startHoldToSpeak() {
        if (mListening || mActivity.isFinishing() || mActivity.isDestroyed()) return;

        if (ContextCompat.checkSelfPermission(mActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    mActivity,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_CODE_VOICE_CHAT_MIC);
            Toast.makeText(mActivity,
                    "Allow microphone permission to use Voice-to-Chat.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(mActivity)) {
            Toast.makeText(mActivity,
                    "Speech recognition service is unavailable on this device.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            ensureRecognizer();
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, mActivity.getPackageName());

            mLatestPartialText = "";
            mListening = true;
            pulseHaptic();
            applyMicVisualState(true);
            showBubble("Listening… release to send");
            if (mRecognizer != null) {
                mRecognizer.startListening(intent);
            }
        } catch (Throwable t) {
            mListening = false;
            applyMicVisualState(false);
            hideBubble();
            Toast.makeText(mActivity,
                    "Voice-to-Chat unavailable: " + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void finishHoldToSpeak() {
        if (!mListening) return;
        pulseHaptic();
        showBubble(mLatestPartialText.isEmpty()
                ? "Processing voice…" : ("Sending: \"" + mLatestPartialText + "\""));
        try {
            if (mRecognizer != null) {
                mRecognizer.stopListening();
            }
        } catch (Throwable ignored) {
            cancelListening();
        }
    }

    /** Stops and resets any active speech recognition session (called on pause/background). */
    public void cancelListening() {
        mListening = false;
        mLatestPartialText = "";
        applyMicVisualState(false);
        hideBubble();
        if (mRecognizer != null) {
            try {
                mRecognizer.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Releases native {@link SpeechRecognizer} and detaches views on {@code GameActivity.onDestroy()}. */
    public void destroy() {
        cancelListening();
        if (mRecognizer != null) {
            try {
                mRecognizer.destroy();
            } catch (Throwable ignored) {
            }
            mRecognizer = null;
        }
        mRootContainer.removeView(mMicPill);
        mRootContainer.removeView(mTranscriptBubble);
    }

    private void ensureRecognizer() {
        if (mRecognizer != null) return;
        mRecognizer = SpeechRecognizer.createSpeechRecognizer(mActivity);
        mRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (mListening) showBubble("Listening… speak chat message");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                applyMicVisualState(false);
            }

            @Override
            public void onError(int error) {
                boolean hadPartial = !mLatestPartialText.trim().isEmpty();
                String fallback = mLatestPartialText;
                mListening = false;
                mLatestPartialText = "";
                applyMicVisualState(false);
                if (hadPartial && (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    deliverRecognizedChat(fallback);
                    return;
                }
                hideBubble();
                if (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    Toast.makeText(mActivity,
                            "Voice-to-Chat offline — enable offline speech pack or connect to Wi-Fi.",
                            Toast.LENGTH_SHORT).show();
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Toast.makeText(mActivity,
                            "Microphone permission required for Voice-to-Chat.",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onResults(Bundle results) {
                String text = extractTopTranscript(results);
                if (text.isEmpty()) text = mLatestPartialText;
                mListening = false;
                mLatestPartialText = "";
                applyMicVisualState(false);
                if (!text.isEmpty()) {
                    deliverRecognizedChat(text);
                } else {
                    hideBubble();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String partial = extractTopTranscript(partialResults);
                if (!partial.isEmpty()) {
                    mLatestPartialText = partial;
                    showBubble("🎤 " + partial);
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void deliverRecognizedChat(@NonNull String transcript) {
        TouchCharInput input = GameActivity.touchCharInput;
        String clean = TouchCharInput.sanitizeVoiceChatText(transcript);
        if (input != null && !clean.isEmpty() && input.dispatchVoiceChatMessage(clean)) {
            showBubble("Sent: " + clean);
            mTranscriptBubble.postDelayed(this::hideBubble, 1100L);
        } else {
            hideBubble();
        }
    }

    @NonNull
    private static String extractTopTranscript(@Nullable Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> matches =
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || matches.get(0) == null) return "";
        return matches.get(0).trim();
    }

    private void applyMicVisualState(boolean recording) {
        if (recording) {
            GradientDrawable activeBg = new GradientDrawable();
            activeBg.setShape(GradientDrawable.RECTANGLE);
            activeBg.setCornerRadius(17f * mDensity);
            activeBg.setColor(0xCC991B2E);
            activeBg.setStroke(Math.max(1, dp(1.5f)), Color.parseColor("#FFFF8A9B"));
            mMicPill.setBackground(activeBg);
            mMicPill.setText("● REC");
        } else {
            mMicPill.setBackground(new ZyroxLiquidGlassDrawable(mDensity, true));
            mMicPill.setText("🎤 MIC");
        }
    }

    private void showBubble(@NonNull String text) {
        mTranscriptBubble.setText(text);
        mTranscriptBubble.setVisibility(View.VISIBLE);
    }

    private void hideBubble() {
        mTranscriptBubble.setVisibility(View.GONE);
    }

    private void pulseHaptic() {
        if (LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS) {
            try {
                mMicPill.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignored) {
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }
}
