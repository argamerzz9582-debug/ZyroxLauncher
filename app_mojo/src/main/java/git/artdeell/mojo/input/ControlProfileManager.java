package git.artdeell.mojo.input;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Zyrox 2.0 Control Profile Manager, Touch-Target / Keybind Conflict Auditor, and Gamepad Preset
 * helper built seamlessly over {@code Tools.CTRLMAP_PATH}.
 */
public final class ControlProfileManager {
    public static final float MIN_RECOMMENDED_TOUCH_TARGET_DP = 36f;
    private static final int MAX_CONTROL_JSON_BYTES = 2 * 1024 * 1024; // 2 MiB ceiling

    public static final class ProfileSummary {
        @NonNull public final File file;
        @NonNull public final String fileName;
        @NonNull public final String displayName;
        public final int buttonCount;
        public final boolean isDefault;
        public final long lastModifiedMs;

        public ProfileSummary(@NonNull File file,
                              @NonNull String fileName,
                              @NonNull String displayName,
                              int buttonCount,
                              boolean isDefault,
                              long lastModifiedMs) {
            this.file = file;
            this.fileName = fileName;
            this.displayName = displayName;
            this.buttonCount = buttonCount;
            this.isDefault = isDefault;
            this.lastModifiedMs = lastModifiedMs;
        }
    }

    public static final class LayoutAuditReport {
        public final int totalButtons;
        public final int version;
        @NonNull public final List<String> keybindConflicts;
        @NonNull public final List<String> smallTargetWarnings;
        @NonNull public final List<String> formulaWarnings;

        public LayoutAuditReport(int totalButtons,
                                 int version,
                                 @NonNull List<String> keybindConflicts,
                                 @NonNull List<String> smallTargetWarnings,
                                 @NonNull List<String> formulaWarnings) {
            this.totalButtons = totalButtons;
            this.version = version;
            this.keybindConflicts = Collections.unmodifiableList(keybindConflicts);
            this.smallTargetWarnings = Collections.unmodifiableList(smallTargetWarnings);
            this.formulaWarnings = Collections.unmodifiableList(formulaWarnings);
        }

        public boolean isClean() {
            return keybindConflicts.isEmpty() && smallTargetWarnings.isEmpty() && formulaWarnings.isEmpty();
        }
    }

    private ControlProfileManager() {
    }

    /**
     * Lists all control profiles inside {@code controlsDir}.
     */
    @NonNull
    public static List<ProfileSummary> listProfiles(@NonNull File controlsDir,
                                                    @Nullable String defaultControlPath) {
        if (!controlsDir.isDirectory()) return Collections.emptyList();
        File[] files = controlsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        List<ProfileSummary> out = new ArrayList<>(files.length);
        for (File f : files) {
            int btnCount = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                String txt = new String(ArchiveSecurity.readBytesBounded(in, MAX_CONTROL_JSON_BYTES),
                        StandardCharsets.UTF_8);
                JsonObject obj = new JsonParser().parse(txt).getAsJsonObject();
                if (obj.has("mControlDataList") && obj.get("mControlDataList").isJsonArray()) {
                    btnCount = obj.getAsJsonArray("mControlDataList").size();
                }
            } catch (Exception ignored) {
            }
            boolean isDef = defaultControlPath != null
                    && f.getAbsolutePath().equals(new File(defaultControlPath).getAbsolutePath());
            String label = f.getName().replaceAll("(?i)\\.json$", "");
            out.add(new ProfileSummary(f, f.getName(), label, btnCount, isDef, f.lastModified()));
        }
        return out;
    }

    /**
     * Duplicates an existing control profile under a new safe name without altering the original.
     */
    @NonNull
    public static File duplicateProfile(@NonNull File sourceFile,
                                        @NonNull File controlsDir,
                                        @NonNull String desiredName) throws IOException {
        if (!sourceFile.isFile()) {
            throw new IOException("Source control profile does not exist");
        }
        FileUtils.ensureDirectory(controlsDir);
        String base = FileUtils.escapeFileName(desiredName.trim().replaceAll("(?i)\\.json$", ""));
        if (base.isEmpty()) base = "CustomControls";
        File target = new File(controlsDir, base + ".json");
        int idx = 2;
        while (target.exists()) {
            target = new File(controlsDir, base + "_" + idx + ".json");
            idx++;
        }
        try (FileInputStream in = new FileInputStream(sourceFile)) {
            byte[] data = ArchiveSecurity.readBytesBounded(in, MAX_CONTROL_JSON_BYTES);
            validateAndAuditJson(new String(data, StandardCharsets.UTF_8));
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(data);
                out.flush();
            }
        }
        return target;
    }

    /**
     * Validates and imports a control layout JSON from an input stream into {@code controlsDir}.
     */
    @NonNull
    public static File importProfile(@NonNull InputStream inputStream,
                                     @NonNull File controlsDir,
                                     @NonNull String rawName) throws IOException {
        byte[] data = ArchiveSecurity.readBytesBounded(inputStream, MAX_CONTROL_JSON_BYTES);
        String text = new String(data, StandardCharsets.UTF_8);
        validateAndAuditJson(text);

        FileUtils.ensureDirectory(controlsDir);
        String base = FileUtils.escapeFileName(rawName.trim().replaceAll("(?i)\\.json$", ""));
        if (base.isEmpty()) base = "ImportedControls";
        File target = ArchiveSecurity.resolveSafeEntry(controlsDir, base + ".json");
        if (target.exists()) {
            int n = 2;
            while (target.exists()) {
                target = ArchiveSecurity.resolveSafeEntry(controlsDir, base + "_" + n + ".json");
                n++;
            }
        }
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(data);
            out.flush();
        }
        return target;
    }

    /**
     * Validates control layout JSON schema and inspects duplicate keybind conflicts, undersized
     * touch targets, and malformed position expressions.
     */
    @NonNull
    public static LayoutAuditReport validateAndAuditJson(@NonNull String jsonText) throws IOException {
        JsonObject root;
        try {
            root = new JsonParser().parse(jsonText).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Invalid control profile JSON syntax", e);
        }
        if (!root.has("mControlDataList") || !root.get("mControlDataList").isJsonArray()) {
            throw new IOException("Invalid control profile: missing mControlDataList array");
        }

        int version = root.has("version") && root.get("version").isJsonPrimitive()
                ? root.get("version").getAsInt() : 1;
        JsonArray list = root.getAsJsonArray("mControlDataList");

        Map<Integer, List<String>> keyToButtons = new LinkedHashMap<>();
        List<String> smallTargets = new ArrayList<>();
        List<String> badFormulas = new ArrayList<>();

        for (int i = 0; i < list.size(); i++) {
            JsonElement el = list.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject btn = el.getAsJsonObject();
            String name = btn.has("name") && btn.get("name").isJsonPrimitive()
                    ? btn.get("name").getAsString() : ("Button #" + (i + 1));
            float w = btn.has("width") && btn.get("width").isJsonPrimitive() ? btn.get("width").getAsFloat() : 50f;
            float h = btn.has("height") && btn.get("height").isJsonPrimitive() ? btn.get("height").getAsFloat() : 50f;

            if (w < MIN_RECOMMENDED_TOUCH_TARGET_DP && h < MIN_RECOMMENDED_TOUCH_TARGET_DP) {
                smallTargets.add("\"" + name + "\" is very small (" + Math.round(w) + "×" + Math.round(h) + "dp)");
            }

            String dx = btn.has("dynamicX") && btn.get("dynamicX").isJsonPrimitive()
                    ? btn.get("dynamicX").getAsString() : "";
            String dy = btn.has("dynamicY") && btn.get("dynamicY").isJsonPrimitive()
                    ? btn.get("dynamicY").getAsString() : "";
            if (dx.trim().isEmpty() || dy.trim().isEmpty()) {
                badFormulas.add("\"" + name + "\" is missing dynamic landscape X/Y position");
            }

            if (btn.has("keycodes") && btn.get("keycodes").isJsonArray()) {
                JsonArray kcs = btn.getAsJsonArray("keycodes");
                for (JsonElement kcEl : kcs) {
                    if (!kcEl.isJsonPrimitive()) continue;
                    int kc = kcEl.getAsInt();
                    if (kc == 0) continue; // KEYCODE_UNKNOWN / unset slot
                    List<String> owners = keyToButtons.get(kc);
                    if (owners == null) {
                        owners = new ArrayList<>();
                        keyToButtons.put(kc, owners);
                    }
                    if (!owners.contains(name)) owners.add(name);
                }
            }
        }

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : keyToButtons.entrySet()) {
            if (e.getValue().size() > 1) {
                conflicts.add("Keycode " + describeKeycode(e.getKey()) + " bound to multiple controls: "
                        + joinNames(e.getValue()));
            }
        }

        return new LayoutAuditReport(list.size(), version, conflicts, smallTargets, badFormulas);
    }

    @NonNull
    private static String describeKeycode(int kc) {
        switch (kc) {
            case ControlData.SPECIALBTN_KEYBOARD: return "SPECIAL_KEYBOARD";
            case ControlData.SPECIALBTN_TOGGLECTRL: return "SPECIAL_GUI";
            case ControlData.SPECIALBTN_MOUSEPRI: return "MOUSE_PRI";
            case ControlData.SPECIALBTN_MOUSESEC: return "MOUSE_SEC";
            case ControlData.SPECIALBTN_VIRTUALMOUSE: return "VIRTUAL_MOUSE";
            case ControlData.SPECIALBTN_MOUSEMID: return "MOUSE_MID";
            case ControlData.SPECIALBTN_SCROLLUP: return "SCROLL_UP";
            case ControlData.SPECIALBTN_SCROLLDOWN: return "SCROLL_DOWN";
            case ControlData.SPECIALBTN_MENU: return "APP_MENU";
            case 32: return "SPACE (Jump)";
            case 65: return "A (Left)";
            case 68: return "D (Right)";
            case 69: return "E (Inventory)";
            case 83: return "S (Back)";
            case 84: return "T (Chat)";
            case 87: return "W (Forward)";
            case 258: return "TAB";
            case 292: return "F3 (Debug)";
            case 294: return "F5 (3rd Person)";
            case 340: return "LEFT_SHIFT (Sneak)";
            default: return "#" + kc;
        }
    }

    @NonNull
    private static String joinNames(@NonNull List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(names.get(i)).append('"');
        }
        return sb.toString();
    }
}
