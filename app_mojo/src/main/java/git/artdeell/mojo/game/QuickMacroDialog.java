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
            } else if (upper.startsWith("CMD:")) {
                String cmd = tok.substring(4).trim();
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

        // 1. Instant Function Key Chords
        root.addView(ZyroxStudioUi.createSectionLabel(ctx, "INSTANT GAMEPLAY ACTIONS"),
                ZyroxStudioUi.vParam(0));

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
        if (LauncherPreferences.PREF_ZYROX_HAPTICS) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }
}
