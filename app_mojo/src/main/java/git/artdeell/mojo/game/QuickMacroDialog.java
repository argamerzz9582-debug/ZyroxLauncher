package git.artdeell.mojo.game;

import static git.artdeell.mojo.game.platform.Platform.PLATFORM;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.LwjglGlfwKeycode;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Floating Starlight Liquid Glass Quick-Command, Multi-Step Macro Sequencer &amp; Chat Macro panel.
 *
 * <p>Supports desktop function chords ({@code F1}, {@code F2}, {@code F3}, {@code F3+G/B/A},
 * {@code F5}, {@code F}), multi-step timed gameplay sequences ({@code SLOT:9 -> WAIT:45 -> USE ->
 * WAIT:60 -> SLOT:1}, {@code HOLD:SHIFT:220}, {@code CHORD:CTRL+Q}, {@code ATTACK}, {@code CMD:/sethome}),
 * and one-tap server/world chat macros with cancellable execution.
 */
public final class QuickMacroDialog {

    private static final String PREF_NAME = "zyrox_quick_macros_v1";
    private static final String KEY_CUSTOM_MACROS = "macros_json";
    private static final String KEY_MACRO_SEQUENCES = "sequences_json_v2";
    private static final String KEY_RADIAL_SLOTS = "radial_wheel_slots_v1";
    /** Phase 4.5 — Number of customizable wedges on the Liquid Glass Radial Command Wheel. */
    public static final int RADIAL_SLOT_COUNT = 8;
    private static final int MAX_MACROS = 16;
    public static final int MAX_SEQUENCE_STEPS = 16;
    public static final int MIN_DELAY_MS = 15;
    public static final int MAX_DELAY_MS = 5000;

    /** Monotonic token incremented on cancel/new run so stale delayed callbacks become no-ops. */
    private static final AtomicInteger sActiveRunGeneration = new AtomicInteger(0);

    public enum StepKind {
        KEY_TAP,
        KEY_HOLD,
        KEY_CHORD,
        MOUSE_CLICK,
        WAIT,
        CHAT_CMD
    }

    /** A single deterministic step in a multi-step {@link MacroSequence}. */
    public static final class MacroStep {
        @NonNull public final StepKind kind;
        public final int primaryCode;
        public final int secondaryCode;
        public final int durationMs;
        @NonNull public final String text;

        public MacroStep(@NonNull StepKind kind,
                         int primaryCode,
                         int secondaryCode,
                         int durationMs,
                         @Nullable String text) {
            this.kind = kind;
            this.primaryCode = primaryCode;
            this.secondaryCode = secondaryCode;
            this.durationMs = Math.max(0, Math.min(MAX_DELAY_MS, durationMs));
            this.text = text != null ? text : "";
        }

        @NonNull
        public String toToken() {
            switch (kind) {
                case WAIT:
                    return "WAIT:" + Math.max(MIN_DELAY_MS, durationMs);
                case MOUSE_CLICK:
                    return primaryCode == 1 ? "USE" : (primaryCode == 2 ? "PICK" : "ATTACK");
                case KEY_HOLD:
                    return "HOLD:" + keyCodeToName(primaryCode) + ":" + Math.max(MIN_DELAY_MS, durationMs);
                case KEY_CHORD:
                    return "CHORD:" + keyCodeToName(primaryCode) + "+" + keyCodeToName(secondaryCode);
                case CHAT_CMD:
                    return "CMD:" + text;
                case KEY_TAP:
                default:
                    if (primaryCode >= KeyEvent.KEYCODE_1 && primaryCode <= KeyEvent.KEYCODE_9) {
                        return "SLOT:" + (primaryCode - KeyEvent.KEYCODE_0);
                    }
                    return "KEY:" + keyCodeToName(primaryCode);
            }
        }
    }

    /** Named, serializable multi-step macro sequence. */
    public static final class MacroSequence {
        @NonNull public final String label;
        @NonNull public final List<MacroStep> steps;

        public MacroSequence(@NonNull String label, @NonNull List<MacroStep> steps) {
            this.label = label.trim().isEmpty() ? "Sequence" : label.trim();
            List<MacroStep> copy = new ArrayList<>(Math.min(MAX_SEQUENCE_STEPS, steps.size()));
            for (int i = 0; i < steps.size() && copy.size() < MAX_SEQUENCE_STEPS; i++) {
                if (steps.get(i) != null) copy.add(steps.get(i));
            }
            this.steps = Collections.unmodifiableList(copy);
        }

        @NonNull
        public String formatScript() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) sb.append(" -> ");
                sb.append(steps.get(i).toToken());
            }
            return sb.toString();
        }
    }

    public static final class ChatMacro {
        @NonNull public final String label;
        @NonNull public final String command;

        public ChatMacro(@NonNull String label, @NonNull String command) {
            this.label = label;
            this.command = command;
        }
    }

    private QuickMacroDialog() {
    }

    /**
     * Parses a human-friendly multi-step sequence script (separated by {@code ->}, {@code ;}, or
     * {@code ,}) into bounded, validated {@link MacroStep}s.
     *
     * <p>Supported step syntax:
     * <ul>
     *   <li>{@code SLOT:1}..{@code SLOT:9} — switch hotbar slot</li>
     *   <li>{@code USE} / {@code ATTACK} / {@code PICK} — right / left / middle mouse click</li>
     *   <li>{@code KEY:F}, {@code KEY:Q}, {@code KEY:SPACE}, {@code KEY:SHIFT}, {@code KEY:F5}, etc.</li>
     *   <li>{@code HOLD:SHIFT:220} — hold key down for clamped milliseconds (15–5000ms)</li>
     *   <li>{@code CHORD:CTRL+Q}, {@code CHORD:F3+G} — simultaneous modifier + key combo</li>
     *   <li>{@code WAIT:50} — pause N milliseconds (15–5000ms) before next step</li>
     *   <li>{@code CMD:/home} — type and submit chat command</li>
     * </ul>
     */
    @NonNull
    public static List<MacroStep> parseSequenceScript(@Nullable String script) {
        if (script == null || script.trim().isEmpty()) return Collections.emptyList();
        String[] rawTokens = script.split("\\s*(?:->|;|\\r?\\n)\\s*");
        List<MacroStep> steps = new ArrayList<>();
        for (String raw : rawTokens) {
            if (steps.size() >= MAX_SEQUENCE_STEPS) break;
            String tok = raw.trim();
            if (tok.isEmpty()) continue;
            String upper = tok.toUpperCase(Locale.ROOT);

            if ("USE".equals(upper) || "RCLICK".equals(upper)) {
                steps.add(new MacroStep(StepKind.MOUSE_CLICK, 1, 0, 0, null));
            } else if ("ATTACK".equals(upper) || "LCLICK".equals(upper)) {
                steps.add(new MacroStep(StepKind.MOUSE_CLICK, 0, 0, 0, null));
            } else if ("PICK".equals(upper) || "MCLICK".equals(upper)) {
                steps.add(new MacroStep(StepKind.MOUSE_CLICK, 2, 0, 0, null));
            } else if (upper.startsWith("WAIT:") || upper.startsWith("DELAY:")) {
                int ms = parseClampedDelay(tok.substring(tok.indexOf(':') + 1));
                steps.add(new MacroStep(StepKind.WAIT, 0, 0, ms, null));
            } else if (upper.startsWith("SLOT:")) {
                int slot = parseIntSafe(tok.substring(5), -1);
                if (slot >= 1 && slot <= 9) {
                    steps.add(new MacroStep(StepKind.KEY_TAP,
                            KeyEvent.KEYCODE_0 + slot, 0, 0, null));
                }
            } else if (upper.startsWith("KEY:")) {
                int kc = nameToKeyCode(tok.substring(4));
                if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                    steps.add(new MacroStep(StepKind.KEY_TAP, kc, 0, 0, null));
                }
            } else if (upper.startsWith("HOLD:")) {
                String[] parts = tok.substring(5).split(":");
                if (parts.length >= 2) {
                    int kc = nameToKeyCode(parts[0]);
                    int ms = parseClampedDelay(parts[1]);
                    if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                        steps.add(new MacroStep(StepKind.KEY_HOLD, kc, 0, ms, null));
                    }
                }
            } else if (upper.startsWith("CHORD:")) {
                String[] parts = tok.substring(6).split("\\+");
                if (parts.length == 2) {
                    int mod = nameToKeyCode(parts[0]);
                    int key = nameToKeyCode(parts[1]);
                    if (mod != KeyEvent.KEYCODE_UNKNOWN && key != KeyEvent.KEYCODE_UNKNOWN) {
                        steps.add(new MacroStep(StepKind.KEY_CHORD, mod, key, 40, null));
                    }
                }
            } else if (upper.startsWith("CMD:") || upper.startsWith("CHAT:")) {
                String cmd = tok.substring(tok.indexOf(':') + 1).trim();
                if (!cmd.isEmpty() && cmd.length() <= 160) {
                    steps.add(new MacroStep(StepKind.CHAT_CMD, 0, 0, 0, cmd));
                }
            }
        }
        return steps;
    }

    @NonNull
    public static String serializeSequencesJson(@NonNull List<MacroSequence> sequences) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < sequences.size() && i < MAX_MACROS; i++) {
            MacroSequence seq = sequences.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("label", seq.label);
            obj.addProperty("script", seq.formatScript());
            arr.add(obj);
        }
        return arr.toString();
    }

    @NonNull
    public static List<MacroSequence> deserializeSequencesJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        List<MacroSequence> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return out;
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String label = o.has("label") ? o.get("label").getAsString().trim() : "";
                String script = o.has("script") ? o.get("script").getAsString() : "";
                List<MacroStep> steps = parseSequenceScript(script);
                if (!label.isEmpty() && !steps.isEmpty()) {
                    out.add(new MacroSequence(label, steps));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Cancels any currently running multi-step macro sequence cleanly. */
    public static void cancelRunningSequence() {
        sActiveRunGeneration.incrementAndGet();
    }

    /** Executes all steps in {@code sequence} asynchronously on the main dispatcher with exact delays. */
    public static void executeSequence(@NonNull MacroSequence sequence) {
        if (sequence.steps.isEmpty()) return;
        final int runGen = sActiveRunGeneration.incrementAndGet();
        Tools.MAIN_HANDLER.postDelayed(() -> runStepAt(sequence.steps, 0, runGen), 75L);
    }

    private static void runStepAt(@NonNull List<MacroStep> steps, int index, int runGen) {
        if (runGen != sActiveRunGeneration.get() || index >= steps.size()) return;
        MacroStep step = steps.get(index);
        long nextDelayMs = 30L;

        switch (step.kind) {
            case WAIT:
                nextDelayMs = Math.max(MIN_DELAY_MS, step.durationMs);
                break;
            case KEY_TAP:
                CallbackBridge.sendKeyPress(step.primaryCode);
                nextDelayMs = 35L;
                break;
            case KEY_HOLD:
                PLATFORM.sendKeyEvent(step.primaryCode, true, 0);
                nextDelayMs = Math.max(MIN_DELAY_MS, step.durationMs);
                final int holdKey = step.primaryCode;
                Tools.MAIN_HANDLER.postDelayed(
                        () -> PLATFORM.sendKeyEvent(holdKey, false, 0), nextDelayMs);
                nextDelayMs += 20L;
                break;
            case KEY_CHORD:
                PLATFORM.sendKeyEvent(step.primaryCode, true, 0);
                final int modKey = step.primaryCode;
                final int subKey = step.secondaryCode;
                Tools.MAIN_HANDLER.postDelayed(() -> {
                    PLATFORM.sendKeyEvent(subKey, true, 0);
                    PLATFORM.sendKeyEvent(subKey, false, 0);
                    Tools.MAIN_HANDLER.postDelayed(
                            () -> PLATFORM.sendKeyEvent(modKey, false, 0), 35L);
                }, 35L);
                nextDelayMs = 95L;
                break;
            case MOUSE_CLICK: {
                int glfwBtn = step.primaryCode == 1
                        ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
                        : (step.primaryCode == 2
                        ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE
                        : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT);
                CallbackBridge.sendMouseButton(glfwBtn, true);
                Tools.MAIN_HANDLER.postDelayed(
                        () -> CallbackBridge.sendMouseButton(glfwBtn, false), 28L);
                nextDelayMs = 45L;
                break;
            }
            case CHAT_CMD:
                executeChatMacro(step.text);
                nextDelayMs = 240L;
                break;
        }

        if (index + 1 < steps.size()) {
            Tools.MAIN_HANDLER.postDelayed(
                    () -> runStepAt(steps, index + 1, runGen), nextDelayMs);
        }
    }

    public static void triggerScreenshot(@NonNull Context ctx) {
        Tools.MAIN_HANDLER.postDelayed(() -> {
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F2);
            Toast.makeText(ctx.getApplicationContext(),
                    "Screenshot captured (F2)", Toast.LENGTH_SHORT).show();
        }, 90L);
    }

    public static void triggerF3Combo(int comboKeyCode) {
        Tools.MAIN_HANDLER.postDelayed(() -> {
            PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_F3, true, 0);
            Tools.MAIN_HANDLER.postDelayed(() -> {
                PLATFORM.sendKeyEvent(comboKeyCode, true, 0);
                PLATFORM.sendKeyEvent(comboKeyCode, false, 0);
                Tools.MAIN_HANDLER.postDelayed(
                        () -> PLATFORM.sendKeyEvent(KeyEvent.KEYCODE_F3, false, 0), 40L);
            }, 40L);
        }, 80L);
    }

    public static void executeChatMacro(@NonNull String rawCommand) {
        final String cmd = rawCommand.trim();
        if (cmd.isEmpty()) return;
        final boolean autoSubmit = !rawCommand.endsWith(" ");
        final String textToSend = autoSubmit ? cmd : rawCommand;
        Tools.MAIN_HANDLER.postDelayed(() -> {
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_T);
            Tools.MAIN_HANDLER.postDelayed(() -> {
                PLATFORM.sendBulkUnicodeEvent(textToSend, 0);
                if (autoSubmit) {
                    Tools.MAIN_HANDLER.postDelayed(
                            () -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ENTER), 55L);
                }
            }, 115L);
        }, 95L);
    }

    public static void show(@NonNull Context ctx) {
        int pad = ZyroxStudioUi.dp(ctx, 14);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        final AlertDialog[] holder = new AlertDialog[1];

        // 0. Phase 4.5 — 8-Slot Liquid Glass Radial Quick-Chat & Command Wheel
        root.addView(ZyroxStudioUi.createSectionLabel(ctx,
                "RADIAL COMMAND WHEEL (SWIPE OR TAP • ZERO KEYBOARD LAG)"),
                ZyroxStudioUi.vParam(0));

        final List<MacroSequence> radialSlots = new ArrayList<>(loadRadialSlots(ctx));
        final RadialCommandWheelView wheelView = new RadialCommandWheelView(ctx, radialSlots,
                selectedIndex -> {
                    if (selectedIndex >= 0 && selectedIndex < radialSlots.size()) {
                        MacroSequence chosen = radialSlots.get(selectedIndex);
                        if (holder[0] != null) holder[0].dismiss();
                        executeSequence(chosen);
                    }
                });
        int wheelSize = ZyroxStudioUi.dp(ctx, 232);
        LinearLayout.LayoutParams wheelLp = new LinearLayout.LayoutParams(wheelSize, wheelSize);
        wheelLp.gravity = Gravity.CENTER_HORIZONTAL;
        wheelLp.topMargin = ZyroxStudioUi.dp(ctx, 6);
        root.addView(wheelView, wheelLp);

        Button editWheelBtn = ZyroxStudioUi.createSecondaryButton(ctx, "Customize 8 Radial Wheel Slots");
        editWheelBtn.setOnClickListener(v ->
                showRadialSlotPickerDialog(ctx, radialSlots, wheelView));
        root.addView(editWheelBtn, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));

        if (ctx instanceof android.app.Activity) {
            Button aiFriendBtn = ZyroxStudioUi.createPrimaryButton(ctx, "AI Friend Co-Pilot & Bot Actions");
            aiFriendBtn.setOnClickListener(v -> {
                performHaptic(v);
                if (holder[0] != null) holder[0].dismiss();
                android.app.Activity act = (android.app.Activity) ctx;
                String srv = (act instanceof GameActivity)
                        ? ((GameActivity) act).getActiveServerAddress() : null;
                new AiFriendOverlayController(act).showQuickCommandDialog(srv);
            });
            root.addView(aiFriendBtn, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));
        }

        // 1. Instant Function Key Chords
        root.addView(ZyroxStudioUi.createSectionLabel(ctx, "INSTANT GAMEPLAY ACTIONS"),
                ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 10)));

        String[][] quickKeys = {
                {"Screenshot (F2)", "Perspective (F5)"},
                {"Debug HUD (F3)", "Chunk Grid (F3+G)"},
                {"Hitboxes (F3+B)", "Reload Chunks (F3+A)"},
                {"Clean HUD (F1)", "Swap Offhand (F)"}
        };
        Runnable[][] keyRunnables = {
                {
                        () -> triggerScreenshot(ctx),
                        () -> Tools.MAIN_HANDLER.postDelayed(
                                () -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F5), 80L)
                },
                {
                        () -> Tools.MAIN_HANDLER.postDelayed(
                                () -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F3), 80L),
                        () -> triggerF3Combo(KeyEvent.KEYCODE_G)
                },
                {
                        () -> triggerF3Combo(KeyEvent.KEYCODE_B),
                        () -> triggerF3Combo(KeyEvent.KEYCODE_A)
                },
                {
                        () -> Tools.MAIN_HANDLER.postDelayed(
                                () -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F1), 80L),
                        () -> Tools.MAIN_HANDLER.postDelayed(
                                () -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F), 80L)
                }
        };

        for (int r = 0; r < quickKeys.length; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                final Runnable action = keyRunnables[r][c];
                Button btn = (r == 0 && c == 0)
                        ? ZyroxStudioUi.createPrimaryButton(ctx, quickKeys[r][c])
                        : ZyroxStudioUi.createSecondaryButton(ctx, quickKeys[r][c]);
                btn.setOnClickListener(v -> {
                    performHaptic(v);
                    if (holder[0] != null) holder[0].dismiss();
                    action.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ZyroxStudioUi.dp(ctx, 38), 1f);
                if (c > 0) lp.leftMargin = ZyroxStudioUi.dp(ctx, 8);
                row.addView(btn, lp);
            }
            root.addView(row, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));
        }

        // 2. Multi-Step Macro Sequencer
        LinearLayout seqHeader = new LinearLayout(ctx);
        seqHeader.setOrientation(LinearLayout.HORIZONTAL);
        seqHeader.setGravity(Gravity.CENTER_VERTICAL);
        seqHeader.addView(ZyroxStudioUi.createSectionLabel(ctx, "MULTI-STEP MACRO SEQUENCES"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addSeqBtn = ZyroxStudioUi.createSecondaryButton(ctx, "+ Sequence");
        seqHeader.addView(addSeqBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 32)));
        root.addView(seqHeader, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 12)));

        root.addView(ZyroxStudioUi.createBody(ctx,
                        "Timed multi-step combos (tap to run · long-press to inspect/delete)."),
                ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 2)));

        LinearLayout seqContainer = new LinearLayout(ctx);
        seqContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(seqContainer, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));

        final Runnable[] rebuildSequences = new Runnable[1];
        rebuildSequences[0] = () -> {
            seqContainer.removeAllViews();
            List<MacroSequence> sequences = loadSequences(ctx);
            for (int i = 0; i < sequences.size(); i += 2) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 2 && (i + col) < sequences.size(); col++) {
                    final int idx = i + col;
                    final MacroSequence seq = sequences.get(idx);
                    Button b = ZyroxStudioUi.createSecondaryButton(
                            ctx, seq.label + " (" + seq.steps.size() + ")");
                    b.setAllCaps(false);
                    b.setOnClickListener(v -> {
                        performHaptic(v);
                        if (holder[0] != null) holder[0].dismiss();
                        executeSequence(seq);
                    });
                    b.setOnLongClickListener(v -> {
                        new AlertDialog.Builder(ctx)
                                .setTitle(seq.label)
                                .setMessage(seq.formatScript())
                                .setPositiveButton("Delete", (d, w) -> {
                                    List<MacroSequence> updated = loadSequences(ctx);
                                    if (idx >= 0 && idx < updated.size()) {
                                        updated.remove(idx);
                                        saveSequences(ctx, updated);
                                        rebuildSequences[0].run();
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return true;
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ZyroxStudioUi.dp(ctx, 38), 1f);
                    if (col > 0) lp.leftMargin = ZyroxStudioUi.dp(ctx, 8);
                    row.addView(b, lp);
                }
                seqContainer.addView(row, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));
            }
        };
        rebuildSequences[0].run();
        addSeqBtn.setOnClickListener(v -> showAddSequenceDialog(ctx, rebuildSequences[0]));

        // 3. Quick Chat & Server Command Macros
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(ZyroxStudioUi.createSectionLabel(ctx, "QUICK CHAT & COMMAND MACROS"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addBtn = ZyroxStudioUi.createSecondaryButton(ctx, "+ Add");
        headerRow.addView(addBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 32)));
        root.addView(headerRow, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 12)));

        LinearLayout macroContainer = new LinearLayout(ctx);
        macroContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(macroContainer, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));

        final Runnable[] rebuildMacros = new Runnable[1];
        rebuildMacros[0] = () -> {
            macroContainer.removeAllViews();
            List<ChatMacro> macros = loadMacros(ctx);
            for (int i = 0; i < macros.size(); i += 2) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 2 && (i + col) < macros.size(); col++) {
                    final int idx = i + col;
                    final ChatMacro m = macros.get(idx);
                    Button b = ZyroxStudioUi.createSecondaryButton(ctx, m.label);
                    b.setAllCaps(false);
                    b.setOnClickListener(v -> {
                        performHaptic(v);
                        if (holder[0] != null) holder[0].dismiss();
                        executeChatMacro(m.command);
                    });
                    b.setOnLongClickListener(v -> {
                        new AlertDialog.Builder(ctx)
                                .setTitle("Remove Macro?")
                                .setMessage(m.label + " (" + m.command + ")")
                                .setPositiveButton("Remove", (d, w) -> {
                                    List<ChatMacro> updated = loadMacros(ctx);
                                    if (idx >= 0 && idx < updated.size()) {
                                        updated.remove(idx);
                                        saveMacros(ctx, updated);
                                        rebuildMacros[0].run();
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return true;
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ZyroxStudioUi.dp(ctx, 38), 1f);
                    if (col > 0) lp.leftMargin = ZyroxStudioUi.dp(ctx, 8);
                    row.addView(b, lp);
                }
                macroContainer.addView(row, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));
            }
        };
        rebuildMacros[0].run();
        addBtn.setOnClickListener(v -> showAddMacroDialog(ctx, rebuildMacros[0]));

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Zyrox Quick Actions & Macro Sequencer")
                .setView(scroll)
                .setNeutralButton("Stop Macro", (d, w) -> {
                    cancelRunningSequence();
                    Toast.makeText(ctx, "Active macro sequence stopped.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    private static void showAddSequenceDialog(@NonNull Context ctx, @NonNull Runnable onSaved) {
        List<MacroSequence> current = loadSequences(ctx);
        if (current.size() >= MAX_MACROS) {
            Toast.makeText(ctx, "Maximum of " + MAX_MACROS + " sequences reached.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int pad = ZyroxStudioUi.dp(ctx, 14);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        EditText nameInput = ZyroxStudioUi.createGlassSearchInput(
                ctx, "Sequence name (e.g. Hotbar 9 Clutch)");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(28)});
        form.addView(nameInput);

        EditText scriptInput = ZyroxStudioUi.createGlassSearchInput(
                ctx, "SLOT:9 -> WAIT:45 -> USE -> WAIT:60 -> SLOT:1");
        scriptInput.setSingleLine(false);
        scriptInput.setMinLines(2);
        scriptInput.setMaxLines(4);
        form.addView(scriptInput, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 8)));

        TextView help = ZyroxStudioUi.createBody(ctx,
                "Steps: SLOT:1..9 · USE · ATTACK · KEY:F/Q/E/SPACE/SHIFT/F5 · HOLD:SHIFT:200 · CHORD:CTRL+Q · WAIT:50 · CMD:/spawn");
        form.addView(help, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 6)));

        new AlertDialog.Builder(ctx)
                .setTitle("New Multi-Step Macro Sequence")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    List<MacroStep> parsed = parseSequenceScript(scriptInput.getText().toString());
                    if (name.isEmpty() || parsed.isEmpty()) {
                        Toast.makeText(ctx, "Enter a name and at least 1 valid step.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<MacroSequence> list = loadSequences(ctx);
                    list.add(new MacroSequence(name, parsed));
                    saveSequences(ctx, list);
                    onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showAddMacroDialog(@NonNull Context ctx, @NonNull Runnable onSaved) {
        List<ChatMacro> current = loadMacros(ctx);
        if (current.size() >= MAX_MACROS) {
            Toast.makeText(ctx, "Maximum of " + MAX_MACROS + " macros reached.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int pad = ZyroxStudioUi.dp(ctx, 14);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        EditText labelInput = ZyroxStudioUi.createGlassSearchInput(
                ctx, "Button label (e.g. Home Base)");
        labelInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        form.addView(labelInput);

        EditText cmdInput = ZyroxStudioUi.createGlassSearchInput(
                ctx, "Command or chat (e.g. /home or /tp )");
        cmdInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        form.addView(cmdInput, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 8)));

        new AlertDialog.Builder(ctx)
                .setTitle("Add Quick Command Macro")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String lbl = labelInput.getText().toString().trim();
                    String cmd = cmdInput.getText().toString();
                    if (lbl.isEmpty() || cmd.trim().isEmpty()) {
                        Toast.makeText(ctx, "Label and command are required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<ChatMacro> list = loadMacros(ctx);
                    list.add(new ChatMacro(lbl, cmd));
                    saveMacros(ctx, list);
                    onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private static List<MacroSequence> loadSequences(@NonNull Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_MACRO_SEQUENCES, null);
        if (raw == null) {
            List<MacroSequence> defaults = new ArrayList<>();
            defaults.add(new MacroSequence("Slot 9 Quick Use",
                    parseSequenceScript("SLOT:9 -> WAIT:40 -> USE -> WAIT:55 -> SLOT:1")));
            defaults.add(new MacroSequence("Drop Stack (Ctrl+Q)",
                    parseSequenceScript("CHORD:CTRL+Q")));
            defaults.add(new MacroSequence("Offhand Use Swap",
                    parseSequenceScript("KEY:F -> WAIT:45 -> USE -> WAIT:55 -> KEY:F")));
            defaults.add(new MacroSequence("F3 Debug Bundle",
                    parseSequenceScript("CHORD:F3+G -> WAIT:90 -> CHORD:F3+B")));
            return defaults;
        }
        return new ArrayList<>(deserializeSequencesJson(raw));
    }

    private static void saveSequences(@NonNull Context ctx, @NonNull List<MacroSequence> list) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MACRO_SEQUENCES, serializeSequencesJson(list))
                .apply();
    }

    @NonNull
    private static List<ChatMacro> loadMacros(@NonNull Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_CUSTOM_MACROS, null);
        List<ChatMacro> out = new ArrayList<>();
        if (raw == null) {
            out.add(new ChatMacro("/spawn", "/spawn"));
            out.add(new ChatMacro("/home", "/home"));
            out.add(new ChatMacro("Day + Clear", "/time set day"));
            out.add(new ChatMacro("Survival", "/gamemode survival"));
            out.add(new ChatMacro("Creative", "/gamemode creative"));
            out.add(new ChatMacro("Spectator", "/gamemode spectator"));
            return out;
        }
        try {
            JsonArray arr = JsonParser.parseString(raw).getAsJsonArray();
            for (int i = 0; i < arr.size() && out.size() < MAX_MACROS; i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                String l = o.has("label") ? o.get("label").getAsString().trim() : "";
                String c = o.has("command") ? o.get("command").getAsString() : "";
                if (!l.isEmpty() && !c.trim().isEmpty()) {
                    out.add(new ChatMacro(l, c));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveMacros(@NonNull Context ctx, @NonNull List<ChatMacro> list) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < MAX_MACROS; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("label", list.get(i).label);
            o.addProperty("command", list.get(i).command);
            arr.add(o);
        }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_MACROS, arr.toString())
                .apply();
    }

    private static int nameToKeyCode(@NonNull String raw) {
        String k = raw.trim().toUpperCase(Locale.ROOT);
        switch (k) {
            case "SPACE": case "JUMP": return KeyEvent.KEYCODE_SPACE;
            case "SHIFT": case "SNEAK": return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "CTRL": case "SPRINT": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "TAB": return KeyEvent.KEYCODE_TAB;
            case "ESC": return KeyEvent.KEYCODE_ESCAPE;
            case "ENTER": return KeyEvent.KEYCODE_ENTER;
            case "F1": return KeyEvent.KEYCODE_F1;
            case "F2": return KeyEvent.KEYCODE_F2;
            case "F3": return KeyEvent.KEYCODE_F3;
            case "F5": return KeyEvent.KEYCODE_F5;
            default:
                if (k.length() == 1) {
                    char ch = k.charAt(0);
                    if (ch >= 'A' && ch <= 'Z') return KeyEvent.KEYCODE_A + (ch - 'A');
                    if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
                }
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    @NonNull
    private static String keyCodeToName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "SHIFT";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "CTRL";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_ESCAPE: return "ESC";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_F1: return "F1";
            case KeyEvent.KEYCODE_F2: return "F2";
            case KeyEvent.KEYCODE_F3: return "F3";
            case KeyEvent.KEYCODE_F5: return "F5";
            default: return "K" + keyCode;
        }
    }

    private static int parseClampedDelay(@NonNull String raw) {
        int val = parseIntSafe(raw, 50);
        return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, val));
    }

    private static int parseIntSafe(@NonNull String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void performHaptic(@NonNull View v) {
        if (LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    // =============================================================================================
    // PHASE 4.5 — 8-SLOT LIQUID GLASS RADIAL QUICK-CHAT & COMMAND WHEEL
    // =============================================================================================

    public interface OnRadialSlotSelectedListener {
        void onSlotSelected(int slotIndex);
    }

    /**
     * Pure deterministic 8-wedge radial hit-test (0 = North/Top, clockwise in 45° sectors;
     * returns {@code -1} inside the center cancel dead-zone or outside {@code outerRadiusPx}).
     */
    public static int resolveRadialSector(float dx,
                                          float dy,
                                          float deadZoneRadiusPx,
                                          float outerRadiusPx) {
        double dist = Math.hypot(dx, dy);
        if (dist < Math.max(1f, deadZoneRadiusPx)
                || (outerRadiusPx > 0f && dist > outerRadiusPx * 1.18f)) {
            return -1;
        }
        // Shift origin to 12 o'clock (North = 0°), increasing clockwise [0, 360)
        double deg = Math.toDegrees(Math.atan2(dy, dx)) + 90.0;
        if (deg < 0.0) deg += 360.0;
        double sectorSpan = 360.0 / RADIAL_SLOT_COUNT; // 45°
        int sector = (int) Math.floor(((deg + sectorSpan * 0.5) % 360.0) / sectorSpan);
        return Math.max(0, Math.min(RADIAL_SLOT_COUNT - 1, sector));
    }

    /** Default 8 high-utility server chat &amp; F3 debug wedges (zero soft-keyboard stall). */
    @NonNull
    public static List<MacroSequence> getDefaultRadialSlots() {
        List<MacroSequence> defaults = new ArrayList<>(RADIAL_SLOT_COUNT);
        defaults.add(new MacroSequence("/spawn", parseSequenceScript("CMD:/spawn")));
        defaults.add(new MacroSequence("/home", parseSequenceScript("CMD:/home")));
        defaults.add(new MacroSequence("/tpa", parseSequenceScript("CMD:/tpa ")));
        defaults.add(new MacroSequence("GG", parseSequenceScript("CMD:GG")));
        defaults.add(new MacroSequence("/hub", parseSequenceScript("CMD:/hub")));
        defaults.add(new MacroSequence("F3+A", parseSequenceScript("CHORD:F3+A")));
        defaults.add(new MacroSequence("F3+B", parseSequenceScript("CHORD:F3+B")));
        defaults.add(new MacroSequence("F3+G", parseSequenceScript("CHORD:F3+G")));
        return defaults;
    }

    @NonNull
    public static List<MacroSequence> loadRadialSlots(@NonNull Context ctx) {
        List<MacroSequence> defaults = getDefaultRadialSlots();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_RADIAL_SLOTS, null);
            List<MacroSequence> saved = deserializeSequencesJson(raw);
            if (saved.size() == RADIAL_SLOT_COUNT) {
                return saved;
            }
        } catch (Throwable ignored) {
        }
        return defaults;
    }

    private static void saveRadialSlots(@NonNull Context ctx, @NonNull List<MacroSequence> slots) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RADIAL_SLOTS, serializeSequencesJson(slots))
                .apply();
    }

    private static void showRadialSlotPickerDialog(@NonNull Context ctx,
                                                   @NonNull List<MacroSequence> slots,
                                                   @NonNull RadialCommandWheelView wheelView) {
        final String[] compass = {"N (Top)", "NE", "E (Right)", "SE", "S (Bottom)", "SW", "W (Left)", "NW"};
        String[] items = new String[RADIAL_SLOT_COUNT];
        for (int i = 0; i < RADIAL_SLOT_COUNT; i++) {
            MacroSequence s = slots.get(i);
            items[i] = (i + 1) + ". " + compass[i] + " — " + s.label + "  [" + s.formatScript() + "]";
        }
        new AlertDialog.Builder(ctx)
                .setTitle("Customize Radial Wheel Slot")
                .setItems(items, (d, which) -> showEditSingleRadialSlotDialog(ctx, slots, which, wheelView))
                .setNeutralButton("Reset Defaults", (d, w) -> {
                    slots.clear();
                    slots.addAll(getDefaultRadialSlots());
                    saveRadialSlots(ctx, slots);
                    wheelView.invalidate();
                    Toast.makeText(ctx, "Radial wheel reset to defaults.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showEditSingleRadialSlotDialog(@NonNull Context ctx,
                                                       @NonNull List<MacroSequence> slots,
                                                       int slotIndex,
                                                       @NonNull RadialCommandWheelView wheelView) {
        MacroSequence current = slots.get(slotIndex);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ZyroxStudioUi.dp(ctx, 14);
        box.setPadding(p, ZyroxStudioUi.dp(ctx, 8), p, ZyroxStudioUi.dp(ctx, 4));

        EditText etLabel = ZyroxStudioUi.createGlassInput(ctx, "Short wheel label (e.g. /warp)");
        etLabel.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        etLabel.setText(current.label);
        box.addView(etLabel);

        EditText etAction = ZyroxStudioUi.createGlassInput(
                ctx, "Command (e.g. /spawn, GG) or script (CHORD:F3+A)");
        etAction.setText(current.formatScript());
        box.addView(etAction, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 8)));

        new AlertDialog.Builder(ctx)
                .setTitle("Edit Radial Slot #" + (slotIndex + 1))
                .setView(box)
                .setPositiveButton("Save Slot", (d, w) -> {
                    String lbl = etLabel.getText().toString().trim();
                    String raw = etAction.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    // Allow plain chat/slash input like "/bed" or "GLHF" without forcing CMD: prefix
                    List<MacroStep> parsed = parseSequenceScript(raw);
                    if (parsed.isEmpty()) {
                        parsed = parseSequenceScript("CMD:" + raw);
                    }
                    if (parsed.isEmpty()) {
                        Toast.makeText(ctx, "Invalid command or macro script.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String finalLabel = lbl.isEmpty()
                            ? (raw.length() > 8 ? raw.substring(0, 8) : raw) : lbl;
                    slots.set(slotIndex, new MacroSequence(finalLabel, parsed));
                    saveRadialSlots(ctx, slots);
                    wheelView.invalidate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Zero-allocation Canvas 8-wedge High-Contrast Liquid Glass radial wheel. */
    private static final class RadialCommandWheelView extends View {
        private final List<MacroSequence> mSlots;
        private final OnRadialSlotSelectedListener mListener;
        private final float mDensity;
        private final android.graphics.Paint mScrimPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mActiveWedgePaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mRimPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mSpokePaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mHubPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mLabelPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mSubPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF mOuterOval = new android.graphics.RectF();
        private int mHoveredSector = -1;

        RadialCommandWheelView(@NonNull Context ctx,
                               @NonNull List<MacroSequence> slots,
                               @NonNull OnRadialSlotSelectedListener listener) {
            super(ctx);
            mSlots = slots;
            mListener = listener;
            mDensity = Math.max(1f, ctx.getResources().getDisplayMetrics().density);

            // High-Contrast #660B0F17 Liquid Glass dark scrim base
            mScrimPaint.setStyle(android.graphics.Paint.Style.FILL);
            mScrimPaint.setColor(0x990B0F17);

            mActiveWedgePaint.setStyle(android.graphics.Paint.Style.FILL);
            mActiveWedgePaint.setColor(0x5872E4FF);

            mRimPaint.setStyle(android.graphics.Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.5f * mDensity);
            mRimPaint.setColor(git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable.SPECULAR_BORDER_TOP);

            mSpokePaint.setStyle(android.graphics.Paint.Style.STROKE);
            mSpokePaint.setStrokeWidth(1.0f * mDensity);
            mSpokePaint.setColor(0x2EFFFFFF);

            mHubPaint.setStyle(android.graphics.Paint.Style.FILL);
            mHubPaint.setColor(0xCC0B0F17);

            mLabelPaint.setColor(0xFFF6FAFF);
            mLabelPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            mLabelPaint.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
            mLabelPaint.setTextSize(11.5f * mDensity);
            mLabelPaint.setShadowLayer(2.5f, 0f, 1f, 0xCC05080E);

            mSubPaint.setColor(0xFFA8E8FF);
            mSubPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            mSubPaint.setTextSize(9.5f * mDensity);
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float outerR = Math.min(cx, cy) - 4f * mDensity;
            if (outerR <= 16f) return;
            float innerR = outerR * 0.30f;
            float labelR = innerR + (outerR - innerR) * 0.56f;

            mOuterOval.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
            canvas.drawCircle(cx, cy, outerR, mScrimPaint);

            float span = 360f / RADIAL_SLOT_COUNT;
            if (mHoveredSector >= 0 && mHoveredSector < RADIAL_SLOT_COUNT) {
                float startDeg = -90f - span * 0.5f + mHoveredSector * span;
                canvas.drawArc(mOuterOval, startDeg, span, true, mActiveWedgePaint);
            }

            for (int i = 0; i < RADIAL_SLOT_COUNT; i++) {
                double spokeRad = Math.toRadians(-90.0 - span * 0.5 + i * span);
                float sx0 = cx + (float) Math.cos(spokeRad) * innerR;
                float sy0 = cy + (float) Math.sin(spokeRad) * innerR;
                float sx1 = cx + (float) Math.cos(spokeRad) * outerR;
                float sy1 = cy + (float) Math.sin(spokeRad) * outerR;
                canvas.drawLine(sx0, sy0, sx1, sy1, mSpokePaint);

                if (i < mSlots.size()) {
                    double midRad = Math.toRadians(-90.0 + i * span);
                    float lx = cx + (float) Math.cos(midRad) * labelR;
                    float ly = cy + (float) Math.sin(midRad) * labelR + 4f * mDensity;
                    mLabelPaint.setColor(i == mHoveredSector ? 0xFFFFFFFF : 0xFFE6F0FF);
                    canvas.drawText(mSlots.get(i).label, lx, ly, mLabelPaint);
                }
            }

            canvas.drawCircle(cx, cy, outerR, mRimPaint);
            canvas.drawCircle(cx, cy, innerR, mHubPaint);
            canvas.drawCircle(cx, cy, innerR, mRimPaint);

            String centerLabel = (mHoveredSector >= 0 && mHoveredSector < mSlots.size())
                    ? mSlots.get(mHoveredSector).label : "SWIPE";
            canvas.drawText(centerLabel, cx, cy + 3.5f * mDensity, mSubPaint);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float outerR = Math.min(cx, cy) - 4f * mDensity;
            float innerR = outerR * 0.30f;
            int action = event.getActionMasked();

            if (action == android.view.MotionEvent.ACTION_DOWN
                    || action == android.view.MotionEvent.ACTION_MOVE) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                int sec = resolveRadialSector(event.getX() - cx, event.getY() - cy, innerR, outerR);
                if (sec != mHoveredSector) {
                    mHoveredSector = sec;
                    if (sec >= 0) performHaptic(this);
                    invalidate();
                }
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                int chosen = mHoveredSector;
                mHoveredSector = -1;
                invalidate();
                if (chosen >= 0) {
                    performHaptic(this);
                    mListener.onSlotSelected(chosen);
                }
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_CANCEL) {
                mHoveredSector = -1;
                invalidate();
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
