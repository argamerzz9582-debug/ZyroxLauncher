package git.artdeell.mojo.input;

import android.content.Context;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in flagship Liquid Glass touch HUD control layout presets for {@code CustomControlsActivity}.
 *
 * <p>Generates clean schema v9 {@link CustomControls} instances using responsive dynamic expressions
 * ({@code ${margin}}, {@code ${right}}, {@code ${bottom}}, {@code ${screen_width}}, {@code ${screen_height}})
 * so every preset adapts cleanly across 16:9, 19.5:9, 20:9, and tablet screens.
 */
public final class ControlPresetFactory {

    public static final int PRESET_SURVIVAL = 0;
    public static final int PRESET_PVP_CLAW = 1;
    public static final int PRESET_BUILDER = 2;
    public static final int PRESET_MINIMAL = 3;

    public static final String[] PRESET_NAMES = {
            "Survival Balanced (Classic D-Pad)",
            "PvP / Bedwars (4-Finger Claw)",
            "Builder & Creative Pro",
            "Minimal Ultra-Clean (Thumb Friendly)"
    };

    private ControlPresetFactory() {}

    @NonNull
    public static CustomControls createPreset(@NonNull Context ctx, int presetIndex) {
        List<ControlData> btns = new ArrayList<>();
        List<ControlDrawerData> drawers = new ArrayList<>();
        List<ControlJoystickData> joys = new ArrayList<>();

        switch (presetIndex) {
            case PRESET_PVP_CLAW:
                buildPvpClaw(btns);
                break;
            case PRESET_BUILDER:
                buildBuilderPro(btns);
                break;
            case PRESET_MINIMAL:
                buildMinimal(btns);
                break;
            case PRESET_SURVIVAL:
            default:
                return new CustomControls(ctx);
        }

        CustomControls controls = new CustomControls(btns, drawers, joys);
        controls.version = 9;
        controls.scaledAt = 100f;
        return controls;
    }

    private static void buildPvpClaw(@NonNull List<ControlData> b) {
        // Upper-left claw attack (PRI) + sprint toggle; upper-right place/block (SEC) + perspective
        b.add(btn("PRI", ControlData.SPECIALBTN_MOUSEPRI, "${margin} * 2", "${margin} * 6 + 34", 64, 56, false, true));
        b.add(btn("Sprint", KeyEvent.KEYCODE_CTRL_LEFT, "${margin} * 3 + 64", "${margin} * 6 + 34", 54, 44, true, false));
        b.add(btn("SEC", ControlData.SPECIALBTN_MOUSESEC, "${right} - ${margin} * 2", "${margin} * 6 + 34", 64, 56, false, true));
        b.add(btn("3rd", KeyEvent.KEYCODE_F5, "${right} - ${margin} * 3 - 64", "${margin} * 6 + 34", 52, 42, false, false));

        // Compact top utility strip
        b.add(btn("Chat", KeyEvent.KEYCODE_T, "${margin} * 2", "${margin}", 58, 28, false, false));
        b.add(btn("Tab", KeyEvent.KEYCODE_TAB, "${margin} * 3 + 58", "${margin}", 54, 28, false, false));
        b.add(btn("GUI", ControlData.SPECIALBTN_TOGGLECTRL, "${margin} * 4 + 112", "${margin}", 52, 28, false, false));
        b.add(btn("Mouse", ControlData.SPECIALBTN_VIRTUALMOUSE, "${right} - ${margin} * 2", "${margin}", 64, 28, false, false));

        // Responsive WASD + Sneak cluster (swipeable for effortless strafe/w-tap)
        b.add(swipe("▲", KeyEvent.KEYCODE_W, "${margin} * 2 + 54", "${bottom} - ${margin} * 3 - 108", 52, 52));
        b.add(swipe("◀", KeyEvent.KEYCODE_A, "${margin}", "${bottom} - ${margin} * 2 - 54", 52, 52));
        b.add(swipe("▼", KeyEvent.KEYCODE_S, "${margin} * 2 + 54", "${bottom} - ${margin}", 52, 52));
        b.add(swipe("▶", KeyEvent.KEYCODE_D, "${margin} * 3 + 108", "${bottom} - ${margin} * 2 - 54", 52, 52));
        b.add(btn("◇", KeyEvent.KEYCODE_SHIFT_LEFT, "${margin} * 2 + 54", "${bottom} - ${margin} * 2 - 54", 50, 50, true, false));

        // Right-thumb jump, inventory, drop, offhand swap
        b.add(btn("⬛", KeyEvent.KEYCODE_SPACE, "${right} - ${margin} * 2", "${bottom} - ${margin} * 2 - 48", 62, 58, false, true));
        b.add(btn("Inv", KeyEvent.KEYCODE_E, "${right} - ${margin} * 3 - 64", "${bottom} - ${margin}", 52, 46, false, false));
        b.add(btn("Swap", KeyEvent.KEYCODE_F, "${right} - ${margin} * 3 - 64", "${bottom} - ${margin} * 2 - 50", 52, 44, false, false));
        b.add(btn("Drop", KeyEvent.KEYCODE_Q, "${margin} * 3 + 108", "${bottom} - ${margin}", 50, 46, false, false));
    }

    private static void buildBuilderPro(@NonNull List<ControlData> b) {
        // Top builder bar: Debug, Chat, Keyboard, Pick Block (middle mouse), Fly/Perspective, Mouse
        b.add(btn("Debug", KeyEvent.KEYCODE_F3, "${margin}", "${margin}", 62, 28, false, false));
        b.add(btn("Chat", KeyEvent.KEYCODE_T, "${margin} * 2 + 62", "${margin}", 58, 28, false, false));
        b.add(btn("Keyboard", ControlData.SPECIALBTN_KEYBOARD, "${margin} * 3 + 120", "${margin}", 72, 28, false, false));
        b.add(btn("Pick", ControlData.SPECIALBTN_MOUSEMID, "${right} - ${margin} * 3 - 134", "${margin}", 58, 28, false, false));
        b.add(btn("3rd", KeyEvent.KEYCODE_F5, "${right} - ${margin} * 2 - 70", "${margin}", 56, 28, false, false));
        b.add(btn("Mouse", ControlData.SPECIALBTN_VIRTUALMOUSE, "${right}", "${margin}", 66, 28, false, false));

        // Left WASD + Sneak + Break/Place
        b.add(btn("PRI", ControlData.SPECIALBTN_MOUSEPRI, "${margin}", "${bottom} - ${margin} * 4 - 156", 54, 48, false, false));
        b.add(btn("SEC", ControlData.SPECIALBTN_MOUSESEC, "${margin} * 3 + 104", "${bottom} - ${margin} * 4 - 156", 54, 48, false, false));
        b.add(swipe("▲", KeyEvent.KEYCODE_W, "${margin} * 2 + 52", "${bottom} - ${margin} * 3 - 104", 50, 50));
        b.add(swipe("◀", KeyEvent.KEYCODE_A, "${margin}", "${bottom} - ${margin} * 2 - 52", 50, 50));
        b.add(swipe("▼", KeyEvent.KEYCODE_S, "${margin} * 2 + 52", "${bottom} - ${margin}", 50, 50));
        b.add(swipe("▶", KeyEvent.KEYCODE_D, "${margin} * 3 + 104", "${bottom} - ${margin} * 2 - 52", 50, 50));
        b.add(btn("◇", KeyEvent.KEYCODE_SHIFT_LEFT, "${margin} * 2 + 52", "${bottom} - ${margin} * 2 - 52", 50, 50, true, false));
        b.add(btn("GUI", ControlData.SPECIALBTN_TOGGLECTRL, "${margin}", "${bottom} - ${margin}", 50, 50, false, false));
        b.add(btn("Inv", KeyEvent.KEYCODE_E, "${margin} * 3 + 104", "${bottom} - ${margin}", 50, 50, false, false));

        // Right precision placement, jump/fly, offhand, screenshot
        b.add(btn("⬛", KeyEvent.KEYCODE_SPACE, "${right} - ${margin} * 2", "${bottom} - ${margin} * 2 - 52", 58, 56, false, true));
        b.add(btn("Swap", KeyEvent.KEYCODE_F, "${right} - ${margin} * 3 - 60", "${bottom} - ${margin} * 2 - 52", 50, 46, false, false));
        b.add(btn("Shot", KeyEvent.KEYCODE_F2, "${right} - ${margin} * 2", "${bottom} - ${margin} * 3 - 110", 54, 34, false, false));
    }

    private static void buildMinimal(@NonNull List<ControlData> b) {
        // Maximum unobstructed scenery — only essential movement, jump, inv, chat & GUI toggle
        b.add(btn("Chat", KeyEvent.KEYCODE_T, "${margin}", "${margin}", 56, 28, false, false));
        b.add(btn("Mouse", ControlData.SPECIALBTN_VIRTUALMOUSE, "${right}", "${margin}", 62, 28, false, false));
        b.add(btn("GUI", ControlData.SPECIALBTN_TOGGLECTRL, "${margin}", "${bottom} - ${margin}", 46, 46, false, false));

        b.add(swipe("▲", KeyEvent.KEYCODE_W, "${margin} * 2 + 48", "${bottom} - ${margin} * 3 - 96", 48, 48));
        b.add(swipe("◀", KeyEvent.KEYCODE_A, "${margin}", "${bottom} - ${margin} * 2 - 48", 48, 48));
        b.add(swipe("▼", KeyEvent.KEYCODE_S, "${margin} * 2 + 48", "${bottom} - ${margin}", 48, 48));
        b.add(swipe("▶", KeyEvent.KEYCODE_D, "${margin} * 3 + 96", "${bottom} - ${margin} * 2 - 48", 48, 48));
        b.add(btn("◇", KeyEvent.KEYCODE_SHIFT_LEFT, "${margin} * 2 + 48", "${bottom} - ${margin} * 2 - 48", 48, 48, true, false));
        b.add(btn("Inv", KeyEvent.KEYCODE_E, "${margin} * 3 + 96", "${bottom} - ${margin}", 48, 48, false, false));
        b.add(btn("⬛", KeyEvent.KEYCODE_SPACE, "${right} - ${margin} * 2", "${bottom} - ${margin} * 2 - 48", 54, 54, false, true));
    }

    @NonNull
    private static ControlData btn(@NonNull String name, int key,
                                   @NonNull String dx, @NonNull String dy,
                                   float w, float h, boolean toggle, boolean passthru) {
        ControlData d = new ControlData(name, new int[]{key}, dx, dy, w, h, toggle);
        d.passThruEnabled = passthru;
        return d;
    }

    @NonNull
    private static ControlData swipe(@NonNull String name, int key,
                                     @NonNull String dx, @NonNull String dy,
                                     float w, float h) {
        ControlData d = new ControlData(name, new int[]{key}, dx, dy, w, h, false);
        d.isSwipeable = true;
        return d;
    }
}
