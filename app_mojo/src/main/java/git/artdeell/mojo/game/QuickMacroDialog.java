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
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Sleek floating Starlight Liquid Glass Quick-Command &amp; Gameplay Macro Wheel/Panel.
 *
 * <p>Gives mobile players 1-tap access to desktop function chords ({@code F2} screenshot,
 * {@code F5} perspective, {@code F3} debug, {@code F3+G} chunk borders, {@code F3+B} hitboxes,
 * {@code F3+A} reload chunks, {@code F1} cinematic HUD, {@code F} offhand swap) plus customizable
 * server/world chat command macros ({@code /spawn}, {@code /home}, {@code /gamemode}, etc.).
 */
public final class QuickMacroDialog {

    private static final String PREF_NAME = "zyrox_quick_macros_v1";
    private static final String KEY_CUSTOM_MACROS = "macros_json";
    private static final int MAX_MACROS = 16;

    public static final class ChatMacro {
        @NonNull public final String label;
        @NonNull public final String command;

        public ChatMacro(@NonNull String label, @NonNull String command) {
            this.label = label;
            this.command = command;
        }
    }

    private QuickMacroDialog() {}

    public static void triggerScreenshot(@NonNull Context ctx) {
        Tools.MAIN_HANDLER.postDelayed(() -> {
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F2);
            Toast.makeText(ctx.getApplicationContext(), "Screenshot captured (F2)", Toast.LENGTH_SHORT).show();
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
        // Open Minecraft chat (T), inject full Unicode command, then press Enter (unless trailing space)
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

        TextView keySection = ZyroxStudioUi.createSectionLabel(ctx, "INSTANT GAMEPLAY ACTIONS");
        root.addView(keySection, ZyroxStudioUi.vParam(0));

        String[][] quickKeys = {
                {"Screenshot (F2)", "Perspective (F5)"},
                {"Debug HUD (F3)", "Chunk Grid (F3+G)"},
                {"Hitboxes (F3+B)", "Reload Chunks (F3+A)"},
                {"Clean HUD (F1)", "Swap Offhand (F)"}
        };
        Runnable[][] keyRunnables = {
                {
                        () -> triggerScreenshot(ctx),
                        () -> Tools.MAIN_HANDLER.postDelayed(() -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F5), 80L)
                },
                {
                        () -> Tools.MAIN_HANDLER.postDelayed(() -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F3), 80L),
                        () -> triggerF3Combo(KeyEvent.KEYCODE_G)
                },
                {
                        () -> triggerF3Combo(KeyEvent.KEYCODE_B),
                        () -> triggerF3Combo(KeyEvent.KEYCODE_A)
                },
                {
                        () -> Tools.MAIN_HANDLER.postDelayed(() -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F1), 80L),
                        () -> Tools.MAIN_HANDLER.postDelayed(() -> CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_F), 80L)
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

        // Custom Chat & Server Command Macros section
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView macroSection = ZyroxStudioUi.createSectionLabel(ctx, "QUICK CHAT & COMMAND MACROS");
        headerRow.addView(macroSection, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addBtn = ZyroxStudioUi.createSecondaryButton(ctx, "+ Add");
        headerRow.addView(addBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 32)));
        root.addView(headerRow, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 12)));

        TextView hint = ZyroxStudioUi.createBody(ctx, "Tap to execute in chat · Long-press any macro to delete.");
        root.addView(hint, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 2)));

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

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("Quick Macros")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dlg;
        dlg.show();
    }

    private static void showAddMacroDialog(@NonNull Context ctx, @NonNull Runnable onSaved) {
        List<ChatMacro> current = loadMacros(ctx);
        if (current.size() >= MAX_MACROS) {
            Toast.makeText(ctx, "Maximum " + MAX_MACROS + " macros reached", Toast.LENGTH_SHORT).show();
            return;
        }
        int pad = ZyroxStudioUi.dp(ctx, 14);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, ZyroxStudioUi.dp(ctx, 4));

        EditText labelInput = ZyroxStudioUi.createGlassInput(ctx, "Button name (e.g. Home / Bedwars)");
        labelInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        form.addView(labelInput, ZyroxStudioUi.vParam(0));

        EditText cmdInput = ZyroxStudioUi.createGlassInput(ctx, "Command or chat (e.g. /home or /gamemode spectator)");
        cmdInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        form.addView(cmdInput, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(ctx, 8)));

        new AlertDialog.Builder(ctx)
                .setTitle("New Command Macro")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String cmd = cmdInput.getText() != null ? cmdInput.getText().toString() : "";
                    if (cmd.trim().isEmpty()) return;
                    String lbl = labelInput.getText() != null ? labelInput.getText().toString().trim() : "";
                    if (lbl.isEmpty()) {
                        lbl = cmd.trim().length() > 20 ? cmd.trim().substring(0, 20) : cmd.trim();
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
    public static List<ChatMacro> loadMacros(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_CUSTOM_MACROS, null);
        List<ChatMacro> list = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length() && list.size() < MAX_MACROS; i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    String l = obj.optString("label", "").trim();
                    String c = obj.optString("command", "");
                    if (!l.isEmpty() && !c.trim().isEmpty()) {
                        list.add(new ChatMacro(l, c));
                    }
                }
                return list;
            } catch (Throwable ignored) {
            }
        }
        // Built-in starter macros for Survival & Multiplayer servers
        list.add(new ChatMacro("/spawn", "/spawn"));
        list.add(new ChatMacro("/home", "/home"));
        list.add(new ChatMacro("/sethome", "/sethome"));
        list.add(new ChatMacro("Survival", "/gamemode survival"));
        list.add(new ChatMacro("Creative", "/gamemode creative"));
        list.add(new ChatMacro("Keep Inventory", "/gamerule keepInventory true"));
        return list;
    }

    private static void saveMacros(@NonNull Context ctx, @NonNull List<ChatMacro> macros) {
        try {
            JSONArray arr = new JSONArray();
            for (ChatMacro m : macros) {
                JSONObject o = new JSONObject();
                o.put("label", m.label);
                o.put("command", m.command);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CUSTOM_MACROS, arr.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void performHaptic(@NonNull View view) {
        if (LauncherPreferences.PREF_ZYROX_CONTROL_HAPTICS) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } catch (Throwable ignored) {
            }
        }
    }
}
