package git.artdeell.mojo.game;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendManager;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.BotConnectionState;
import git.artdeell.mojo.ai.AiFriendModels.BotStatusSnapshot;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.ai.AiFriendModels.ResultCallback;
import git.artdeell.mojo.ai.AiFriendModels.StateListener;
import git.artdeell.mojo.ai.AiFriendTargetResolver.ResolvedTarget;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Non-intrusive In-Game AI Friend Quick Prompt &amp; Live Status Overlay (`:game` process).
 *
 * <ul>
 *   <li>Renders a compact, draggable High-Contrast Frosted Liquid Glass pill on the gameplay HUD
 *   displaying real-time AI/Bot status ({@code ● AI · FOLLOW}, {@code ● AI · READY}).</li>
 *   <li>Provides 1-tap tactical action chips ({@code Follow}, {@code Come}, {@code Guard},
 *   {@code Wood}, {@code Mine}, {@code Torches}, {@code Idle}) that execute without opening the
 *   soft keyboard or breaking gameplay focus, plus an optional natural-language prompt bar.</li>
 *   <li>Surfaces brief auto-fading reply banners at the top of the screen when AI responses arrive.</li>
 * </ul>
 */
public final class AiFriendOverlayController implements StateListener {

    public static final String PREF_KEY_HUD_PILL = "zyrox_ai_friend_hud_pill";
    private static final String PREF_PILL_REL_X = "zyrox_ai_friend_pill_rel_x";
    private static final String PREF_PILL_REL_Y = "zyrox_ai_friend_pill_rel_y";
    private static final long REPLY_BANNER_TTL_MS = 4500L;

    private final Activity mActivity;
    private final AiFriendManager mManager;

    @Nullable private FrameLayout mHostFrame;
    @Nullable private LinearLayout mPillRoot;
    @Nullable private View mStatusDot;
    @Nullable private TextView mPillLabel;
    @Nullable private TextView mFloatingReplyBanner;

    private boolean mInEditorMode = false;
    private final Runnable mHideBannerRunnable = new Runnable() {
        @Override
        public void run() {
            if (mFloatingReplyBanner != null) {
                mFloatingReplyBanner.setVisibility(View.GONE);
            }
        }
    };

    public AiFriendOverlayController(@NonNull Activity activity) {
        mActivity = activity;
        mManager = AiFriendManager.getInstance(activity.getApplicationContext());
    }

    public void attachToFrame(@NonNull final FrameLayout contentFrame) {
        if (mPillRoot != null) return;
        mHostFrame = contentFrame;
        final float density = mActivity.getResources().getDisplayMetrics().density;

        // 1. Draggable Liquid Glass Status Pill
        LinearLayout pill = new LinearLayout(mActivity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(new ZyroxLiquidGlassDrawable(density, true));
        int padH = Math.round(9f * density);
        int padV = Math.round(5f * density);
        pill.setPadding(padH, padV, padH, padV);

        View dot = new View(mActivity);
        int dotSize = Math.round(7f * density);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.rightMargin = Math.round(5f * density);
        dot.setLayoutParams(dotLp);
        pill.addView(dot);

        TextView label = new TextView(mActivity);
        label.setText("AI · READY");
        label.setTextColor(ZyroxLiquidGlassDrawable.GLYPH_SNOW_WHITE);
        label.setShadowLayer(3f, 0f, 1f, ZyroxLiquidGlassDrawable.GLYPH_SHADOW_COLOR);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        pill.addView(label);

        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.TOP | Gravity.START;
        contentFrame.addView(pill, pillLp);

        mPillRoot = pill;
        mStatusDot = dot;
        mPillLabel = label;

        // 2. Non-clickable auto-fading reply caption banner at top-center
        TextView banner = new TextView(mActivity);
        banner.setBackgroundResource(R.drawable.bg_zyrox_glass_modal);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        banner.setMaxLines(2);
        banner.setEllipsize(TextUtils.TruncateAt.END);
        int bPadH = Math.round(12f * density);
        int bPadV = Math.round(6f * density);
        banner.setPadding(bPadH, bPadV, bPadH, bPadV);
        banner.setClickable(false);
        banner.setFocusable(false);
        banner.setVisibility(View.GONE);

        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bannerLp.topMargin = Math.round(42f * density);
        contentFrame.addView(banner, bannerLp);
        mFloatingReplyBanner = banner;

        installDragAndClickTouchListener(pill, contentFrame);

        contentFrame.post(new Runnable() {
            @Override
            public void run() {
                restoreSavedPillPosition();
            }
        });

        mManager.addStateListener(this);
        syncPillState();
    }

    @Nullable
    public View getOverlayView() {
        return mPillRoot;
    }

    public void setInEditorMode(boolean inEditor) {
        mInEditorMode = inEditor;
        syncVisibility();
    }

    public boolean isPillEnabled() {
        ConfigData cfg = mManager.getConfigStore().load();
        if (LauncherPreferences.DEFAULT_PREF != null
                && LauncherPreferences.DEFAULT_PREF.contains(PREF_KEY_HUD_PILL)) {
            return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY_HUD_PILL, cfg.inGameOverlayEnabled);
        }
        return cfg.inGameOverlayEnabled;
    }

    public void setPillEnabled(boolean enabled) {
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean(PREF_KEY_HUD_PILL, enabled).apply();
        }
        ConfigData cfg = mManager.getConfigStore().load();
        cfg.inGameOverlayEnabled = enabled;
        mManager.getConfigStore().save(cfg);
        syncVisibility();
    }

    public void syncVisibility() {
        if (mPillRoot == null) return;
        boolean show = !mInEditorMode && isPillEnabled();
        mPillRoot.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onAiFriendStateChanged() {
        if (mActivity.isFinishing()) return;
        syncPillState();
    }

    private void syncPillState() {
        if (mPillLabel == null || mStatusDot == null) return;
        syncVisibility();

        mPillLabel.setText(mManager.getCompactHudBadgeLabel());

        BotStatusSnapshot bot = mManager.getBridgeClient().getLatestStatus();
        int dotColor;
        if (bot.state == BotConnectionState.CONNECTED) {
            dotColor = ContextCompat.getColor(mActivity, R.color.zyrox_success);
        } else if (bot.state == BotConnectionState.CONNECTING || bot.state == BotConnectionState.AUTH_REQUIRED) {
            dotColor = ContextCompat.getColor(mActivity, R.color.zyrox_warning);
        } else if (bot.state == BotConnectionState.ERROR) {
            dotColor = ContextCompat.getColor(mActivity, R.color.zyrox_danger);
        } else {
            dotColor = ContextCompat.getColor(mActivity, R.color.zyrox_info);
        }

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(dotColor);
        mStatusDot.setBackground(circle);
    }

    public void showReplyBanner(@NonNull String titlePrefix, @NonNull String message) {
        if (mFloatingReplyBanner == null || mInEditorMode) return;
        Tools.MAIN_HANDLER.removeCallbacks(mHideBannerRunnable);
        mFloatingReplyBanner.setText(titlePrefix + ": " + message);
        mFloatingReplyBanner.setVisibility(View.VISIBLE);
        Tools.MAIN_HANDLER.postDelayed(mHideBannerRunnable, REPLY_BANNER_TTL_MS);
    }

    /**
     * Opens the translucent Liquid Glass In-Game AI Friend Quick Command panel.
     * Avoids auto-showing the soft keyboard so 1-tap tactical chips never disturb gameplay focus!
     */
    public void showQuickCommandDialog(@Nullable final String currentServerAddress) {
        if (mActivity.isFinishing()) return;

        // If the player is connected to a multiplayer server and savedServerAddress is blank, prefill it helpfully
        if (currentServerAddress != null && !currentServerAddress.trim().isEmpty()) {
            ConfigData cfg = mManager.getConfigStore().load();
            if (cfg.savedServerAddress.isEmpty()) {
                cfg.savedServerAddress = currentServerAddress.trim();
                mManager.getConfigStore().save(cfg);
            }
        }

        final ConfigData cfg = mManager.getConfigStore().load();
        final BotStatusSnapshot botSnap = mManager.getBridgeClient().getLatestStatus();
        final AiRole activeRole = AiRole.fromWire(cfg.role);
        final ProviderMode providerMode = ProviderMode.fromWire(cfg.providerMode);

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_zyrox_glass_modal);
        int pad = ZyroxStudioUi.dp(mActivity, 14);
        card.setPadding(pad, ZyroxStudioUi.dp(mActivity, 12), pad, ZyroxStudioUi.dp(mActivity, 12));

        // Header line
        LinearLayout hdr = new LinearLayout(mActivity);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ZyroxStudioUi.createTitle(mActivity,
                cfg.botUsername + "  •  " + activeRole.displayName);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        TextView statusBadge = ZyroxStudioUi.createBadge(mActivity,
                botSnap.state == BotConnectionState.CONNECTED
                        ? "BOT CONNECTED"
                        : providerMode.displayName,
                botSnap.state == BotConnectionState.ERROR);
        hdr.addView(title);
        hdr.addView(statusBadge);
        card.addView(hdr);

        int liveLanPort = mManager.getLastDetectedLiveLanPort();
        String subText = mManager.getActiveProvider().getStatusSummary()
                + "  •  " + botSnap.detail
                + (liveLanPort > 0 ? "  •  LAN Port " + liveLanPort : "");
        TextView subtitle = ZyroxStudioUi.createSubtitle(mActivity, subText);
        subtitle.setPadding(0, ZyroxStudioUi.dp(mActivity, 3), 0, ZyroxStudioUi.dp(mActivity, 6));
        card.addView(subtitle);

        final AlertDialog[] holder = new AlertDialog[1];

        // 1-Tap Tactical Action Strip (zero soft-keyboard focus steal)
        card.addView(ZyroxStudioUi.createSectionLabel(mActivity, "1-TAP TACTICAL BOT ACTIONS"));
        HorizontalScrollView actionScroll = new HorizontalScrollView(mActivity);
        actionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actionRow = new LinearLayout(mActivity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, ZyroxStudioUi.dp(mActivity, 4), 0, ZyroxStudioUi.dp(mActivity, 8));

        for (final BotAction act : BotAction.values()) {
            boolean active = botSnap.state == BotConnectionState.CONNECTED && botSnap.activeAction == act;
            Button chip = active
                    ? ZyroxStudioUi.createPrimaryButton(mActivity, "✓ " + act.shortChipLabel)
                    : ZyroxStudioUi.createSecondaryButton(mActivity, act.shortChipLabel, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ZyroxStudioUi.dp(mActivity, 5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    mManager.dispatchQuickActionAsync(act, null, new ResultCallback<String>() {
                        @Override
                        public void onSuccess(@NonNull String summary) {
                            showReplyBanner(cfg.botUsername + " [" + act.shortChipLabel + "]", summary);
                        }

                        @Override
                        public void onError(@NonNull String message) {
                        }
                    });
                }
            });
            actionRow.addView(chip);
        }
        actionScroll.addView(actionRow);
        card.addView(actionScroll);

        // Quick Brain Mode + Role Switcher + Summon/Dismiss Row
        LinearLayout midRow = new LinearLayout(mActivity);
        midRow.setOrientation(LinearLayout.HORIZONTAL);
        midRow.setPadding(0, 0, 0, ZyroxStudioUi.dp(mActivity, 8));

        ProviderMode activeMode = ProviderMode.fromWire(cfg.providerMode);
        Button btnCycleBrain = ZyroxStudioUi.createSecondaryButton(
                mActivity, activeMode.shortTabLabel, true);
        Button btnCycleRole = ZyroxStudioUi.createSecondaryButton(
                mActivity, "ROLE: " + activeRole.displayName, true);
        Button btnSummonToggle = mManager.getBridgeClient().isBridgeSocketOpen()
                ? ZyroxStudioUi.createDangerButton(mActivity, "DISMISS", true)
                : ZyroxStudioUi.createPrimaryButton(mActivity, "SUMMON");

        LinearLayout.LayoutParams midLp0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f);
        midLp0.rightMargin = ZyroxStudioUi.dp(mActivity, 5);
        btnCycleBrain.setLayoutParams(midLp0);
        LinearLayout.LayoutParams midLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f);
        midLp1.rightMargin = ZyroxStudioUi.dp(mActivity, 5);
        btnCycleRole.setLayoutParams(midLp1);
        LinearLayout.LayoutParams midLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        btnSummonToggle.setLayoutParams(midLp2);
        midRow.addView(btnCycleBrain);
        midRow.addView(btnCycleRole);
        midRow.addView(btnSummonToggle);
        card.addView(midRow);

        btnCycleBrain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderMode[] modes = ProviderMode.values();
                ConfigData next = mManager.getConfigStore().load();
                ProviderMode cur = ProviderMode.fromWire(next.providerMode);
                ProviderMode cycled = modes[(cur.ordinal() + 1) % modes.length];
                next.providerMode = cycled.name();
                mManager.getConfigStore().save(next);
                Toast.makeText(mActivity, "AI Brain -> " + cycled.displayName, Toast.LENGTH_SHORT).show();
                if (holder[0] != null) holder[0].dismiss();
                showQuickCommandDialog(currentServerAddress);
            }
        });

        btnCycleRole.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AiRole[] roles = AiRole.values();
                ConfigData next = mManager.getConfigStore().load();
                AiRole cur = AiRole.fromWire(next.role);
                AiRole cycled = roles[(cur.ordinal() + 1) % roles.length];
                next.role = cycled.name();
                mManager.getConfigStore().save(next);
                Toast.makeText(mActivity, "AI Role -> " + cycled.displayName, Toast.LENGTH_SHORT).show();
                if (holder[0] != null) holder[0].dismiss();
                showQuickCommandDialog(currentServerAddress);
            }
        });

        btnSummonToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
                if (mManager.getBridgeClient().isBridgeSocketOpen()) {
                    mManager.dismissBotAsync(null);
                    showReplyBanner("AI Friend", "Dismissed companion bot");
                } else {
                    mManager.summonBotAsync(new ResultCallback<ResolvedTarget>() {
                        @Override
                        public void onSuccess(@NonNull ResolvedTarget target) {
                            showReplyBanner("Summoning", target.displaySummary);
                        }

                        @Override
                        public void onError(@NonNull String message) {
                            Toast.makeText(mActivity, message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });

        // Latest AI Reply Preview + 1-Tap Send to Game Chat
        final String latestAiSay = findLatestAiReply(mManager.getChatHistorySnapshot());
        if (latestAiSay != null) {
            LinearLayout lastReplyBox = ZyroxStudioUi.createItemCard(mActivity);
            lastReplyBox.addView(ZyroxStudioUi.createSubtitle(mActivity, "Latest AI Reply: \"" + latestAiSay + "\""));
            Button btnForwardChat = ZyroxStudioUi.createSecondaryButton(mActivity, "POST REPLY TO GAME CHAT", true);
            btnForwardChat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    QuickMacroDialog.executeChatMacro(latestAiSay);
                }
            });
            lastReplyBox.addView(btnForwardChat);
            card.addView(lastReplyBox);
        }

        // Natural-Language Quick Prompt Bar
        LinearLayout promptRow = new LinearLayout(mActivity);
        promptRow.setOrientation(LinearLayout.HORIZONTAL);
        promptRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etPrompt = ZyroxStudioUi.createGlassInput(mActivity, "Ask AI Friend anything...");
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        etLp.rightMargin = ZyroxStudioUi.dp(mActivity, 6);
        etPrompt.setLayoutParams(etLp);
        Button btnAsk = ZyroxStudioUi.createPrimaryButton(mActivity, "SEND");
        promptRow.addView(etPrompt);
        promptRow.addView(btnAsk);
        card.addView(promptRow);

        btnAsk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = etPrompt.getText().toString().trim();
                if (text.isEmpty()) return;
                if (holder[0] != null) holder[0].dismiss();
                showReplyBanner(cfg.botUsername, "Thinking...");
                mManager.sendPromptAsync(text, null, new ResultCallback<AiTurnResponse>() {
                    @Override
                    public void onSuccess(@NonNull AiTurnResponse turn) {
                        showReplyBanner(
                                cfg.botUsername + " [" + turn.action.shortChipLabel.toUpperCase(Locale.ROOT) + "]",
                                turn.say);
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        showReplyBanner("AI Friend Error", message);
                    }
                });
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setView(card)
                .create();
        holder[0] = dialog;

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            // Keep soft keyboard hidden until the player explicitly taps the prompt EditText
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
    }

    public void destroy() {
        Tools.MAIN_HANDLER.removeCallbacks(mHideBannerRunnable);
        mManager.removeStateListener(this);
    }

    @Nullable
    private static String findLatestAiReply(@NonNull List<ChatHistoryEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            ChatHistoryEntry e = entries.get(i);
            if (ChatHistoryEntry.SENDER_AI.equals(e.sender) && !e.message.isEmpty()) {
                return e.message;
            }
        }
        return null;
    }

    private void installDragAndClickTouchListener(@NonNull final View pill,
                                                  @NonNull final FrameLayout hostFrame) {
        final int touchSlop = ViewConfiguration.get(mActivity).getScaledTouchSlop();
        pill.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        dragging = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            float maxX = Math.max(0f, hostFrame.getWidth() - v.getWidth());
                            float maxY = Math.max(0f, hostFrame.getHeight() - v.getHeight());
                            v.setX(Math.max(0f, Math.min(maxX, startX + dx)));
                            v.setY(Math.max(0f, Math.min(maxY, startY + dy)));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (dragging) {
                            persistPillPosition(v, hostFrame);
                        } else {
                            v.performClick();
                            String srv = (mActivity instanceof GameActivity)
                                    ? ((GameActivity) mActivity).getActiveServerAddress()
                                    : null;
                            showQuickCommandDialog(srv);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void restoreSavedPillPosition() {
        if (mPillRoot == null || mHostFrame == null) return;
        int fw = mHostFrame.getWidth();
        int fh = mHostFrame.getHeight();
        int pw = mPillRoot.getWidth();
        int ph = mPillRoot.getHeight();
        if (fw <= pw || fh <= ph) return;

        float relX = 0.82f;
        float relY = 0.14f;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs != null) {
            relX = prefs.getFloat(PREF_PILL_REL_X, 0.82f);
            relY = prefs.getFloat(PREF_PILL_REL_Y, 0.14f);
        }
        mPillRoot.setX(Math.max(0f, Math.min(1f, relX)) * (fw - pw));
        mPillRoot.setY(Math.max(0f, Math.min(1f, relY)) * (fh - ph));
    }

    private static void persistPillPosition(@NonNull View pill, @NonNull FrameLayout frame) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return;
        int spanX = Math.max(1, frame.getWidth() - pill.getWidth());
        int spanY = Math.max(1, frame.getHeight() - pill.getHeight());
        prefs.edit()
                .putFloat(PREF_PILL_REL_X, Math.max(0f, Math.min(1f, pill.getX() / spanX)))
                .putFloat(PREF_PILL_REL_Y, Math.max(0f, Math.min(1f, pill.getY() / spanY)))
                .apply();
    }
}
