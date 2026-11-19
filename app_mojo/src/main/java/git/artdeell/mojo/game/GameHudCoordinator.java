package git.artdeell.mojo.game;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.diagnostics.PerformanceHudOverlay;
import git.artdeell.mojo.diagnostics.WaypointManager;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Single-responsibility coordinator extracted from {@link GameActivity} binding
 * {@link PerformanceHudOverlay}, {@link ContextInventoryHelperBar},
 * {@link VoiceChatOverlayController}, the floating Coordinate &amp; Death Waypoint pill, and the
 * 5-second Server Auto-Reconnect banner to live display cutouts and custom control hitboxes.
 */
public final class GameHudCoordinator {

    @NonNull
    private final Activity mActivity;
    @Nullable
    private ViewGroup mContentFrame;
    @Nullable
    private PerformanceHudOverlay mOverlay;
    @Nullable
    private ContextInventoryHelperBar mInventoryHelperBar;
    @Nullable
    private ControlLayout mControlLayout;
    @Nullable
    private View mDrawerPullButton;
    @Nullable
    private View mHotbarView;
    @Nullable
    private VoiceChatOverlayController mVoiceChatOverlay;

    // v2.6.0 — Floating Coordinate & Death Waypoint Pill
    @Nullable
    private LinearLayout mWaypointPill;
    @Nullable
    private TextView mWaypointPillLabel;
    @Nullable
    private File mInstanceGameDir;
    @Nullable
    private String mActivePlayerName;
    @Nullable
    private volatile WaypointManager.CoordinateSample mLastKnownCoord;
    @Nullable
    private WaypointManager.Waypoint mPinnedWaypoint;
    private long mLastAutoDeathStampMs = 0L;

    // v2.6.0 — 5-Second Server Auto-Reconnect Countdown Pill
    @Nullable
    private LinearLayout mReconnectBanner;
    @Nullable
    private TextView mReconnectLabel;
    @Nullable
    private TextView mReconnectCancelButton;

    private final int[] mParentLoc = new int[2];
    private final int[] mViewLoc = new int[2];
    private Insets mLastSafeInsets = Insets.NONE;

    private final git.artdeell.mojo.game.platform.input.PlatformGrabListener mContextGrabListener;

    public GameHudCoordinator(@NonNull Activity activity) {
        mActivity = activity;
        mContextGrabListener = grabbing -> mActivity.runOnUiThread(() -> {
            if (mInventoryHelperBar == null) return;
            if (grabbing) {
                // First grabbed frame confirms player entered a world/server gameplay session
                mInventoryHelperBar.setGameSessionReady(true);
            }
            boolean inEditor = mControlLayout != null && mControlLayout.getModifiable();
            mInventoryHelperBar.onContextStateChanged(grabbing, inEditor);
            onControlsLayoutChanged();
        });
    }

    public void attach(@Nullable ViewGroup contentFrame,
                       @Nullable ControlLayout controlLayout,
                       @Nullable View drawerPullButton,
                       @Nullable View hotbarView,
                       @NonNull String rendererTag,
                       int effectiveHeapMb) {
        attach(contentFrame, controlLayout, drawerPullButton, hotbarView,
                rendererTag, effectiveHeapMb, null, null);
    }

    public void attach(@Nullable ViewGroup contentFrame,
                       @Nullable ControlLayout controlLayout,
                       @Nullable View drawerPullButton,
                       @Nullable View hotbarView,
                       @NonNull String rendererTag,
                       int effectiveHeapMb,
                       @Nullable Runnable onQuickSettingsTap,
                       @Nullable String activeServerAddress) {
        if (contentFrame == null) return;
        mContentFrame = contentFrame;
        mControlLayout = controlLayout;
        mDrawerPullButton = drawerPullButton;
        mHotbarView = hotbarView;

        PerformanceHudOverlay hud = new PerformanceHudOverlay(mActivity);
        hud.configureSession(rendererTag, effectiveHeapMb);
        hud.setObstacleProvider(outRects -> collectLiveObstacles(contentFrame, outRects));
        hud.setOnQuickSettingsAction(onQuickSettingsTap);
        hud.setPingTargetAddress(activeServerAddress);
        hud.setOnHudHiddenAction(() -> {
            LauncherPreferences.PREF_ZYROX_PERF_HUD = false;
            if (LauncherPreferences.DEFAULT_PREF != null) {
                LauncherPreferences.DEFAULT_PREF.edit().putBoolean("zyroxPerfHud", false).apply();
            }
            if (mOverlay != null) {
                mOverlay.setHudEnabled(false);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        contentFrame.addView(hud, lp);
        mOverlay = hud;
        hud.setHudEnabled(LauncherPreferences.PREF_ZYROX_PERF_HUD);

        // Context-Aware Inventory & GUI Helper Bar (auto-appears when ungrabbed in-world)
        ContextInventoryHelperBar invBar = new ContextInventoryHelperBar(
                mActivity, () -> QuickMacroDialog.show(mActivity));
        int topMargin = dp(6f);
        FrameLayout.LayoutParams invLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        invLp.topMargin = topMargin;
        contentFrame.addView(invBar, invLp);
        mInventoryHelperBar = invBar;

        // Floating Coordinate & Death Waypoint Pill (zero client mods, zero F3 clutter)
        buildAndAttachWaypointPill(contentFrame);

        // 5-Second Server Auto-Reconnect Banner Pill (shown only during disconnect countdown)
        buildAndAttachReconnectBanner(contentFrame);

        // Hold-to-Speak In-Game Voice-to-Chat floating Mic overlay
        VoiceChatOverlayController voiceOverlay =
                new VoiceChatOverlayController(mActivity, contentFrame);
        voiceOverlay.setOverlayEnabled(LauncherPreferences.PREF_ZYROX_VOICE_CHAT_OVERLAY);
        mVoiceChatOverlay = voiceOverlay;

        git.artdeell.mojo.game.platform.Platform.addGrabListener(mContextGrabListener);
    }

    /** Configures the active instance directory &amp; player name for per-instance waypoint storage. */
    public void configureWaypointSession(@Nullable File gameDir, @Nullable String playerName) {
        mInstanceGameDir = gameDir;
        mActivePlayerName = playerName;
        reloadWaypointsAsync();
    }

    /** Updates the live ping target when the player connects to a new multiplayer server. */
    public void setPingTargetAddress(@Nullable String serverAddress) {
        if (mOverlay != null) {
            mOverlay.setPingTargetAddress(serverAddress);
        }
    }

    /**
     * Observes vanilla client log lines (invoked from logger thread) to passively track recent
     * teleport/spawn coordinates and auto-record death location markers without client mods.
     */
    public void onGameLogLine(@Nullable String line) {
        if (line == null || line.isEmpty()) return;
        WaypointManager.CoordinateSample sample = WaypointManager.parseCoordinatesFromLogLine(line);
        if (sample != null) {
            mLastKnownCoord = sample;
            if (LauncherPreferences.PREF_ZYROX_WAYPOINT_PILL && mPinnedWaypoint == null) {
                mActivity.runOnUiThread(this::refreshWaypointDisplay);
            }
        }
        if (WaypointManager.isDeathLogLine(line, mActivePlayerName)) {
            long now = System.currentTimeMillis();
            if (now - mLastAutoDeathStampMs < 4_000L) return;
            mLastAutoDeathStampMs = now;
            final WaypointManager.CoordinateSample known = mLastKnownCoord;
            final File dir = mInstanceGameDir;
            if (dir != null && known != null) {
                String stamp = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(now));
                saveWaypointAsync(dir, "Death " + stamp,
                        known.x, known.y, known.z, known.dimension, true, true);
            }
        }
    }

    private void buildAndAttachWaypointPill(@NonNull ViewGroup parent) {
        LinearLayout pill = new LinearLayout(mActivity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(10f), dp(5f), dp(6f), dp(5f));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16f));
        bg.setColor(Color.argb(214, 14, 18, 28));
        bg.setStroke(Math.max(1, dp(1f)), Color.argb(145, 255, 198, 92));
        pill.setBackground(bg);
        pill.setElevation(dp(5f));

        TextView label = new TextView(mActivity);
        label.setText("📍 XYZ Waypoints");
        label.setTextColor(Color.rgb(245, 248, 255));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setSingleLine(true);
        pill.addView(label);

        TextView pinBtn = createPillActionChip("+XYZ",
                Color.argb(85, 56, 189, 248), Color.rgb(186, 238, 255));
        pinBtn.setOnClickListener(v -> captureLiveCoordAndSave(false));
        pill.addView(pinBtn);

        TextView deathBtn = createPillActionChip("☠",
                Color.argb(85, 244, 63, 94), Color.rgb(255, 196, 206));
        deathBtn.setOnClickListener(v -> captureLiveCoordAndSave(true));
        pill.addView(deathBtn);

        attachWaypointDragAndTapHandler(pill, parent);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(14f);
        lp.topMargin = dp(46f);
        pill.setVisibility(LauncherPreferences.PREF_ZYROX_WAYPOINT_PILL ? View.VISIBLE : View.GONE);
        parent.addView(pill, lp);
        mWaypointPill = pill;
        mWaypointPillLabel = label;

        if (LauncherPreferences.DEFAULT_PREF != null) {
            float savedX = LauncherPreferences.DEFAULT_PREF.getFloat(
                    LauncherPreferences.KEY_ZYROX_WAYPOINT_POS_X, -1f);
            float savedY = LauncherPreferences.DEFAULT_PREF.getFloat(
                    LauncherPreferences.KEY_ZYROX_WAYPOINT_POS_Y, -1f);
            if (savedX >= 0f && savedY >= 0f) {
                pill.post(() -> {
                    int maxX = Math.max(0, parent.getWidth() - pill.getWidth() - mLastSafeInsets.right);
                    int maxY = Math.max(0, parent.getHeight() - pill.getHeight() - mLastSafeInsets.bottom);
                    pill.setX(Math.max(mLastSafeInsets.left, Math.min(maxX, savedX * maxX)));
                    pill.setY(Math.max(mLastSafeInsets.top, Math.min(maxY, savedY * maxY)));
                });
            }
        }
    }

    @NonNull
    private TextView createPillActionChip(@NonNull String text, int fillColor, int textColor) {
        TextView chip = new TextView(mActivity);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(7f), dp(2f), dp(7f), dp(2f));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setShape(GradientDrawable.RECTANGLE);
        chipBg.setCornerRadius(dp(11f));
        chipBg.setColor(fillColor);
        chip.setBackground(chipBg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6f);
        chip.setLayoutParams(clp);
        return chip;
    }

    private void attachWaypointDragAndTapHandler(@NonNull LinearLayout pill,
                                                 @NonNull ViewGroup parent) {
        final int touchSlop = ViewConfiguration.get(mActivity).getScaledTouchSlop();
        pill.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            float startX;
            float startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = ev.getRawX();
                        downRawY = ev.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - downRawX;
                        float dy = ev.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) >= touchSlop) {
                            dragging = true;
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        if (dragging) {
                            float minX = mLastSafeInsets.left + dp(4f);
                            float maxX = Math.max(minX, parent.getWidth() - v.getWidth()
                                    - mLastSafeInsets.right - dp(4f));
                            float minY = mLastSafeInsets.top + dp(4f);
                            float maxY = Math.max(minY, parent.getHeight() - v.getHeight()
                                    - mLastSafeInsets.bottom - dp(4f));
                            v.setX(Math.max(minX, Math.min(maxX, startX + dx)));
                            v.setY(Math.max(minY, Math.min(maxY, startY + dy)));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        parent.requestDisallowInterceptTouchEvent(false);
                        if (dragging) {
                            dragging = false;
                            int maxX = Math.max(1, parent.getWidth() - v.getWidth() - mLastSafeInsets.right);
                            int maxY = Math.max(1, parent.getHeight() - v.getHeight() - mLastSafeInsets.bottom);
                            if (LauncherPreferences.DEFAULT_PREF != null) {
                                LauncherPreferences.DEFAULT_PREF.edit()
                                        .putFloat(LauncherPreferences.KEY_ZYROX_WAYPOINT_POS_X,
                                                Math.max(0f, Math.min(1f, v.getX() / maxX)))
                                        .putFloat(LauncherPreferences.KEY_ZYROX_WAYPOINT_POS_Y,
                                                Math.max(0f, Math.min(1f, v.getY() / maxY)))
                                        .apply();
                            }
                        } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                            showWaypointsManagerDialog();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Captures exact live player coordinates via vanilla's silent {@code F3+C} clipboard shortcut
     * (never opens the F3 debug screen and requires zero client mods), falling back cleanly to the
     * manual/pre-filled waypoint dialog if unavailable (e.g. legacy pre-1.13 or paused menu).
     */
    private void captureLiveCoordAndSave(boolean asDeathMarker) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        boolean inWorld = git.artdeell.mojo.game.platform.Platform.isGrabbing();
        if (inWorld) {
            QuickMacroDialog.triggerF3Combo(KeyEvent.KEYCODE_C);
            Tools.MAIN_HANDLER.postDelayed(() -> {
                if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
                WaypointManager.CoordinateSample clipSample = readF3CoordinateFromClipboard();
                if (clipSample != null) {
                    mLastKnownCoord = clipSample;
                    if (asDeathMarker && mInstanceGameDir != null) {
                        String stamp = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
                        saveWaypointAsync(mInstanceGameDir, "Death " + stamp,
                                clipSample.x, clipSample.y, clipSample.z,
                                clipSample.dimension, true, true);
                    } else {
                        showAddWaypointDialog(clipSample, asDeathMarker);
                    }
                } else {
                    showAddWaypointDialog(mLastKnownCoord, asDeathMarker);
                }
            }, 220L);
        } else {
            WaypointManager.CoordinateSample clipSample = readF3CoordinateFromClipboard();
            showAddWaypointDialog(clipSample != null ? clipSample : mLastKnownCoord, asDeathMarker);
        }
    }

    @Nullable
    private WaypointManager.CoordinateSample readF3CoordinateFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() <= 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(mActivity);
            return text != null ? WaypointManager.parseF3ClipboardCommand(text.toString()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Opens the in-game Waypoint &amp; Death Marker manager modal. */
    public void showWaypointsManagerDialog() {
        final File dir = mInstanceGameDir != null ? mInstanceGameDir : new File(Tools.DIR_GAME_NEW);
        MojoApplication.sExecutorService.execute(() -> {
            final List<WaypointManager.Waypoint> list = WaypointManager.load(dir);
            Tools.runOnUiThread(() -> {
                if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
                if (list.isEmpty()) {
                    showAddWaypointDialog(mLastKnownCoord, false);
                    return;
                }
                String[] items = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    items[i] = list.get(i).formatListLabel();
                }
                new AlertDialog.Builder(mActivity)
                        .setTitle("Coordinates & Death Waypoints")
                        .setItems(items, (d, which) -> showSingleWaypointActions(dir, list.get(which)))
                        .setPositiveButton("+ Save XYZ", (d, w) -> captureLiveCoordAndSave(false))
                        .setNeutralButton("☠ Mark Death", (d, w) -> captureLiveCoordAndSave(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void showSingleWaypointActions(@NonNull File dir,
                                           @NonNull WaypointManager.Waypoint wp) {
        String[] actions = new String[]{
                wp.pinned ? "Unpin from HUD Pill" : "Pin to HUD Pill",
                "Send Coordinates in Chat",
                "Copy /tp Command",
                "Delete Waypoint"
        };
        new AlertDialog.Builder(mActivity)
                .setTitle(wp.name + " (" + wp.x + ", " + wp.y + ", " + wp.z + ")")
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            MojoApplication.sExecutorService.execute(() -> {
                                try {
                                    WaypointManager.setPinned(dir, wp.pinned ? null : wp.id);
                                    reloadWaypointsAsync();
                                } catch (IOException ignored) {
                                }
                            });
                            break;
                        case 1:
                            QuickMacroDialog.executeChatMacro(wp.formatChatMessage());
                            break;
                        case 2:
                            try {
                                ClipboardManager cm = (ClipboardManager)
                                        mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText(
                                            "Zyrox Waypoint", wp.formatTeleportCommand()));
                                    Toast.makeText(mActivity,
                                            "Copied: " + wp.formatTeleportCommand(),
                                            Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable ignored) {
                            }
                            break;
                        case 3:
                            MojoApplication.sExecutorService.execute(() -> {
                                try {
                                    WaypointManager.delete(dir, wp.id);
                                    reloadWaypointsAsync();
                                } catch (IOException ignored) {
                                }
                            });
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddWaypointDialog(@Nullable WaypointManager.CoordinateSample prefill,
                                       boolean deathMarker) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        final File dir = mInstanceGameDir != null ? mInstanceGameDir : new File(Tools.DIR_GAME_NEW);

        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16f);
        form.setPadding(p, dp(10f), p, dp(4f));

        final EditText etName = new EditText(mActivity);
        etName.setHint(deathMarker ? "Death marker label" : "Label (e.g. Base, Portal, Stronghold)");
        String defaultLabel = deathMarker
                ? "Death " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())
                : "Base";
        etName.setText(defaultLabel);
        form.addView(etName);

        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int flags = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        final EditText etX = createCoordField("X", prefill != null ? String.valueOf(prefill.x) : "", flags);
        final EditText etY = createCoordField("Y", prefill != null ? String.valueOf(prefill.y) : "64", flags);
        final EditText etZ = createCoordField("Z", prefill != null ? String.valueOf(prefill.z) : "", flags);
        row.addView(etX);
        row.addView(etY);
        row.addView(etZ);
        form.addView(row);

        final String dim = prefill != null ? prefill.dimension : WaypointManager.DIM_OVERWORLD;
        new AlertDialog.Builder(mActivity)
                .setTitle(deathMarker ? "☠ Pin Death Location" : "📍 Save Waypoint")
                .setView(form)
                .setPositiveButton("Save & Pin", (d, w) -> {
                    try {
                        int x = Integer.parseInt(etX.getText().toString().trim());
                        int y = Integer.parseInt(etY.getText().toString().trim());
                        int z = Integer.parseInt(etZ.getText().toString().trim());
                        String label = WaypointManager.sanitizeName(
                                etName.getText().toString(), defaultLabel);
                        mLastKnownCoord = new WaypointManager.CoordinateSample(x, y, z, dim);
                        saveWaypointAsync(dir, label, x, y, z, dim, deathMarker, true);
                    } catch (NumberFormatException e) {
                        Toast.makeText(mActivity,
                                "Enter integer X, Y, Z block coordinates.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private EditText createCoordField(@NonNull String hint, @NonNull String value, int inputType) {
        EditText et = new EditText(mActivity);
        et.setHint(hint);
        et.setText(value);
        et.setInputType(inputType);
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6f);
        et.setLayoutParams(lp);
        return et;
    }

    private void saveWaypointAsync(@NonNull File dir,
                                   @NonNull String label,
                                   int x,
                                   int y,
                                   int z,
                                   @NonNull String dim,
                                   boolean deathMarker,
                                   boolean toast) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                final WaypointManager.Waypoint saved =
                        WaypointManager.addAndPin(dir, label, x, y, z, dim, deathMarker);
                Tools.runOnUiThread(() -> {
                    if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
                    mPinnedWaypoint = saved;
                    refreshWaypointDisplay();
                    if (toast) {
                        Toast.makeText(mActivity,
                                "Pinned " + saved.formatHudBadge(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                Tools.runOnUiThread(() -> {
                    if (!mActivity.isFinishing()) {
                        Toast.makeText(mActivity,
                                "Waypoint save failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void reloadWaypointsAsync() {
        final File dir = mInstanceGameDir;
        if (dir == null) return;
        MojoApplication.sExecutorService.execute(() -> {
            final List<WaypointManager.Waypoint> list = WaypointManager.load(dir);
            final WaypointManager.Waypoint active = WaypointManager.findActiveWaypoint(list);
            Tools.runOnUiThread(() -> {
                if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
                mPinnedWaypoint = active;
                refreshWaypointDisplay();
            });
        });
    }

    private void refreshWaypointDisplay() {
        boolean showPill = LauncherPreferences.PREF_ZYROX_WAYPOINT_PILL
                && !(mControlLayout != null && mControlLayout.getModifiable());
        String badge = null;
        if (mPinnedWaypoint != null) {
            badge = mPinnedWaypoint.formatHudBadge();
        } else if (mLastKnownCoord != null) {
            badge = "📍 XYZ " + mLastKnownCoord.formatCompact()
                    + " [" + WaypointManager.shortDimensionTag(mLastKnownCoord.dimension) + "]";
        }
        if (mWaypointPill != null) {
            mWaypointPill.setVisibility(showPill ? View.VISIBLE : View.GONE);
            if (mWaypointPillLabel != null) {
                mWaypointPillLabel.setText(badge != null ? badge : "📍 XYZ Waypoints");
            }
        }
        if (mOverlay != null) {
            mOverlay.setWaypointBadge(LauncherPreferences.PREF_ZYROX_WAYPOINT_PILL ? badge : null);
        }
    }

    private void buildAndAttachReconnectBanner(@NonNull ViewGroup parent) {
        LinearLayout banner = new LinearLayout(mActivity);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12f), dp(7f), dp(8f), dp(7f));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(18f));
        bg.setColor(Color.argb(232, 16, 22, 34));
        bg.setStroke(Math.max(1, dp(1f)), Color.argb(185, 56, 189, 248));
        banner.setBackground(bg);
        banner.setElevation(dp(8f));

        TextView tv = new TextView(mActivity);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        banner.addView(tv);

        TextView cancel = createPillActionChip("Cancel",
                Color.argb(120, 239, 68, 68), Color.WHITE);
        cancel.setPadding(dp(10f), dp(4f), dp(10f), dp(4f));
        banner.addView(cancel);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(28f);
        banner.setVisibility(View.GONE);
        parent.addView(banner, lp);
        mReconnectBanner = banner;
        mReconnectLabel = tv;
        mReconnectCancelButton = cancel;
    }

    /** Shows or updates the live 5-second Server Auto-Reconnect countdown overlay. */
    public void showAutoReconnectCountdown(@NonNull String statusText,
                                           @Nullable Runnable onCancelClick) {
        if (mReconnectBanner == null || mReconnectLabel == null) return;
        mReconnectLabel.setText(statusText);
        if (mReconnectCancelButton != null) {
            mReconnectCancelButton.setVisibility(onCancelClick != null ? View.VISIBLE : View.GONE);
            mReconnectCancelButton.setOnClickListener(onCancelClick != null
                    ? v -> onCancelClick.run() : null);
        }
        mReconnectBanner.setVisibility(View.VISIBLE);
        mReconnectBanner.bringToFront();
    }

    /** Hides the Server Auto-Reconnect countdown overlay immediately. */
    public void hideAutoReconnectCountdown() {
        if (mReconnectBanner != null) {
            mReconnectBanner.setVisibility(View.GONE);
        }
    }

    private void collectLiveObstacles(@NonNull ViewGroup hudParent, @NonNull List<RectF> outRects) {
        hudParent.getLocationOnScreen(mParentLoc);
        appendViewRectIfVisible(mDrawerPullButton, mParentLoc, outRects);
        appendViewRectIfVisible(mInventoryHelperBar, mParentLoc, outRects);
        appendViewRectIfVisible(mWaypointPill, mParentLoc, outRects);
        if (mVoiceChatOverlay != null) {
            appendViewRectIfVisible(mVoiceChatOverlay.getMicPillView(), mParentLoc, outRects);
        }
        appendViewRectIfVisible(mHotbarView, mParentLoc, outRects);
        if (mControlLayout != null) {
            mControlLayout.collectControlObstacleRects(hudParent, outRects);
        }
    }

    private void appendViewRectIfVisible(@Nullable View view,
                                         @NonNull int[] parentScreenOrigin,
                                         @NonNull List<RectF> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0 || view.getAlpha() <= 0.02f) return;
        view.getLocationOnScreen(mViewLoc);
        float left = mViewLoc[0] - parentScreenOrigin[0];
        float top = mViewLoc[1] - parentScreenOrigin[1];
        out.add(new RectF(left, top, left + w, top + h));
    }

    /** Applies display cutout + system bar safe-area insets to all floating HUD elements. */
    public void onWindowInsetsChanged(@NonNull WindowInsetsCompat insets) {
        Insets safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.systemBars());
        mLastSafeInsets = safe;
        if (mOverlay != null) {
            mOverlay.setSafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom);
        }
        if (mInventoryHelperBar != null
                && mInventoryHelperBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) mInventoryHelperBar.getLayoutParams();
            int base = dp(6f);
            lp.topMargin = Math.max(base, safe.top + base / 2);
            mInventoryHelperBar.setLayoutParams(lp);
        }
    }

    /** Re-clamps HUD coordinates when controls load, exit editor, or screen orientation changes. */
    public void onControlsLayoutChanged() {
        boolean inEditor = mControlLayout != null && mControlLayout.getModifiable();
        if (mInventoryHelperBar != null) {
            mInventoryHelperBar.onContextStateChanged(
                    git.artdeell.mojo.game.platform.Platform.isGrabbing(), inEditor);
        }
        if (mVoiceChatOverlay != null) {
            mVoiceChatOverlay.setEditorHidden(inEditor);
        }
        refreshWaypointDisplay();
        if (mOverlay != null && mOverlay.isHudEnabled()) {
            mOverlay.post(mOverlay::applyClampedCollisionFreePlacement);
        }
    }

    /** Applies live HUD, Voice-to-Chat, and Waypoint updates from Quick Settings. */
    public void syncPreferencesLive() {
        if (mVoiceChatOverlay != null) {
            mVoiceChatOverlay.setOverlayEnabled(LauncherPreferences.PREF_ZYROX_VOICE_CHAT_OVERLAY);
        }
        refreshWaypointDisplay();
        if (mOverlay == null) return;
        boolean shouldShow = LauncherPreferences.PREF_ZYROX_PERF_HUD;
        if (shouldShow != mOverlay.isHudEnabled()) {
            mOverlay.setHudEnabled(shouldShow);
        } else if (shouldShow) {
            mOverlay.syncFromPreferences();
        }
    }

    /** Updates the Thermal &amp; Battery Governor dynamic resolution badge on the HUD pill. */
    public void setThermalGovernorBadge(@Nullable String badge) {
        if (mOverlay != null) {
            mOverlay.setThermalGovernorBadge(badge);
        }
    }

    /** Forwards press/release state from a bound {@code SPECIALBTN_VOICE_CHAT} control button. */
    public void onVoiceChatButtonState(boolean isDown) {
        if (mVoiceChatOverlay != null) {
            mVoiceChatOverlay.onControlButtonState(isDown);
        }
    }

    /** Forwards runtime {@code RECORD_AUDIO} permission result to Voice-to-Chat overlay. */
    public boolean onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        if (requestCode == VoiceChatOverlayController.REQUEST_CODE_VOICE_CHAT_MIC
                && mVoiceChatOverlay != null) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            mVoiceChatOverlay.onMicrophonePermissionResult(granted);
            return true;
        }
        return false;
    }

    /** Forwards fallback {@code ACTION_RECOGNIZE_SPEECH} intent result to Voice-to-Chat overlay. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == VoiceChatOverlayController.REQUEST_CODE_VOICE_CHAT_FALLBACK
                && mVoiceChatOverlay != null) {
            mVoiceChatOverlay.onFallbackSpeechResult(resultCode, data);
            return true;
        }
        return false;
    }

    public void resetPosition() {
        if (mOverlay != null) {
            mOverlay.resetPosition();
        } else {
            LauncherPreferences.resetHudPosition();
        }
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .remove(LauncherPreferences.KEY_ZYROX_WAYPOINT_POS_X)
                    .remove(LauncherPreferences.KEY_ZYROX_WAYPOINT_POS_Y)
                    .apply();
        }
        if (mWaypointPill != null) {
            mWaypointPill.setX(mLastSafeInsets.left + dp(14f));
            mWaypointPill.setY(mLastSafeInsets.top + dp(46f));
        }
    }

    public boolean toggleEnabled() {
        boolean next = !(mOverlay != null && mOverlay.isHudEnabled());
        LauncherPreferences.PREF_ZYROX_PERF_HUD = next;
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean("zyroxPerfHud", next).apply();
        }
        if (mOverlay != null) {
            mOverlay.setHudEnabled(next);
        }
        return next;
    }

    public void onResume() {
        refreshWaypointDisplay();
        if (mOverlay != null && mOverlay.isHudEnabled()) {
            mOverlay.syncFromPreferences();
            mOverlay.startMonitoring();
        }
    }

    public void onPause() {
        if (mInventoryHelperBar != null) {
            mInventoryHelperBar.releaseLatchedModifiers();
        }
        if (mVoiceChatOverlay != null) {
            mVoiceChatOverlay.cancelListening();
        }
        hideAutoReconnectCountdown();
        if (mOverlay != null) {
            mOverlay.stopMonitoring(false);
        }
    }

    public void onDestroy() {
        git.artdeell.mojo.game.platform.Platform.removeGrabListener(mContextGrabListener);
        hideAutoReconnectCountdown();
        if (mInventoryHelperBar != null) {
            mInventoryHelperBar.releaseLatchedModifiers();
            mInventoryHelperBar = null;
        }
        if (mVoiceChatOverlay != null) {
            mVoiceChatOverlay.destroy();
            mVoiceChatOverlay = null;
        }
        if (mOverlay != null) {
            mOverlay.stopMonitoring(true);
            mOverlay = null;
        }
        mWaypointPill = null;
        mWaypointPillLabel = null;
        mReconnectBanner = null;
        mContentFrame = null;
    }

    @Nullable
    public PerformanceHudOverlay getOverlay() {
        return mOverlay;
    }

    private int dp(float value) {
        return Math.round(value * Math.max(1f, mActivity.getResources().getDisplayMetrics().density));
    }
}
