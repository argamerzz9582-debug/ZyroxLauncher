package git.artdeell.mojo.input;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Zyrox Control Profile Manager — SAF JSON import/export, ZXing QR code &amp; DEFLATE-compressed
 * token sharing, touch-target ergonomics &amp; duplicate keybind auditor.
 */
public final class ControlProfileManager {
    public static final float MIN_RECOMMENDED_TOUCH_TARGET_DP = 36f;
    public static final String SHARE_TOKEN_PREFIX = "ZYROXCTRL1:";
    /** Maximum payload characters encodable in a standard Version 40-L QR Code (~2,900 chars). */
    public static final int MAX_QR_PAYLOAD_CHARS = 2850;
    private static final int MAX_CONTROL_JSON_BYTES = 2 * 1024 * 1024; // 2 MiB zip-bomb ceiling

    public interface OnProfileImportedListener {
        void onProfileImported(@NonNull File profileFile);
    }

    public static final class DecodedShareProfile {
        @NonNull public final String profileName;
        @NonNull public final String jsonText;
        @NonNull public final LayoutAuditReport audit;

        public DecodedShareProfile(@NonNull String profileName,
                                   @NonNull String jsonText,
                                   @NonNull LayoutAuditReport audit) {
            this.profileName = profileName;
            this.jsonText = jsonText;
            this.audit = audit;
        }
    }

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
            return keybindConflicts.isEmpty()
                    && smallTargetWarnings.isEmpty()
                    && formulaWarnings.isEmpty();
        }
    }

    private ControlProfileManager() {
    }

    /** Returns all saved {@code .json} control profile files in {@link Tools#CTRLMAP_PATH}. */
    @NonNull
    public static List<File> listControlFiles() {
        if (Tools.CTRLMAP_PATH == null) return Collections.emptyList();
        File dir = new File(Tools.CTRLMAP_PATH);
        if (!dir.isDirectory()) return Collections.emptyList();
        File[] files = dir.listFiles(
                f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return Arrays.asList(files);
    }

    /**
     * Imports a control profile JSON selected via {@code OpenDocumentWithExtension("json")} into
     * {@link Tools#CTRLMAP_PATH} after full syntax &amp; traversal validation.
     */
    @NonNull
    public static File importControlJsonUri(@NonNull Context context,
                                            @NonNull Uri uri,
                                            @Nullable String preferredName) throws IOException {
        if (Tools.CTRLMAP_PATH == null) {
            throw new IOException("Controls directory not initialized");
        }
        String name = preferredName;
        if (name == null || name.trim().isEmpty()) {
            name = Tools.getFileName(context, uri);
        }
        if (name == null || name.trim().isEmpty()) {
            name = "ImportedControls.json";
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to read selected JSON document");
            return importProfile(in, new File(Tools.CTRLMAP_PATH), name);
        }
    }

    /** Lists all control profiles inside {@code controlsDir}. */
    @NonNull
    public static List<ProfileSummary> listProfiles(@NonNull File controlsDir,
                                                    @Nullable String defaultControlPath) {
        if (!controlsDir.isDirectory()) return Collections.emptyList();
        File[] files = controlsDir.listFiles(
                f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        List<ProfileSummary> out = new ArrayList<>(files.length);
        for (File f : files) {
            int btnCount = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                String txt = new String(
                        ArchiveSecurity.readBytesBounded(in, MAX_CONTROL_JSON_BYTES),
                        StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(txt).getAsJsonObject();
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

    // =========================================================================
    // QR Code & Compact DEFLATE/Base64URL Profile Sharing (JVM + Android safe)
    // =========================================================================

    /**
     * Minifies, validates, and DEFLATE-compresses a control layout JSON into a compact URL-safe
     * {@code ZYROXCTRL1:<name>:<base64url>} share string suitable for QR codes or clipboard sharing.
     */
    @NonNull
    public static String encodeSharePayload(@NonNull String profileName,
                                            @NonNull String jsonText) throws IOException {
        validateAndAuditJson(jsonText);
        // Minify JSON whitespace first to maximize QR density
        String minified = JsonParser.parseString(jsonText).toString();
        String safeName = FileUtils.escapeFileName(
                profileName.trim().replaceAll("(?i)\\.json$", "")).replace(":", "_");
        if (safeName.isEmpty()) safeName = "ZyroxHUD";
        if (safeName.length() > 36) safeName = safeName.substring(0, 36);

        byte[] raw = minified.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(128, raw.length / 3));
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(raw);
        } finally {
            deflater.end();
        }
        String b64 = toUrlSafeBase64(baos.toByteArray());
        return SHARE_TOKEN_PREFIX + safeName + ":" + b64;
    }

    /**
     * Decodes and validates either a {@code ZYROXCTRL1:} compressed share token OR raw JSON text,
     * enforcing a strict 2 MiB decompression ceiling against zip-bomb payloads.
     */
    @NonNull
    public static DecodedShareProfile decodeSharePayload(@NonNull String payload) throws IOException {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("Empty control profile share code");
        }
        if (trimmed.startsWith("{")) {
            LayoutAuditReport audit = validateAndAuditJson(trimmed);
            return new DecodedShareProfile("ImportedHUD", trimmed, audit);
        }
        if (!trimmed.startsWith(SHARE_TOKEN_PREFIX)) {
            throw new IOException("Unrecognized control share token (expected ZYROXCTRL1: or JSON)");
        }
        String rest = trimmed.substring(SHARE_TOKEN_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon + 1 >= rest.length()) {
            throw new IOException("Malformed ZYROXCTRL1 share token");
        }
        String rawName = FileUtils.escapeFileName(rest.substring(0, colon));
        if (rawName.isEmpty()) rawName = "SharedHUD";
        byte[] compressed = fromUrlSafeBase64(rest.substring(colon + 1));

        final String json;
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] inflated = ArchiveSecurity.readBytesBounded(iis, MAX_CONTROL_JSON_BYTES);
            json = new String(inflated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Corrupted or oversized compressed control token", e);
        }
        LayoutAuditReport audit = validateAndAuditJson(json);
        return new DecodedShareProfile(rawName, json, audit);
    }

    /** Generates a ZXing QR {@link BitMatrix} for a control profile share payload. */
    @NonNull
    public static BitMatrix encodeQrMatrix(@NonNull String sharePayload, int dimensionPx)
            throws IOException {
        if (sharePayload.length() > MAX_QR_PAYLOAD_CHARS) {
            throw new IOException("Profile is too large for a single QR code ("
                    + sharePayload.length() + " chars) — use Copy Share Token or JSON export.");
        }
        int size = Math.max(128, Math.min(1024, dimensionPx));
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 2);
        try {
            return new QRCodeWriter().encode(sharePayload, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (Exception e) {
            throw new IOException("Failed to generate control profile QR code", e);
        }
    }

    /** Decodes a QR payload from raw {@code ARGB_8888} pixels using ZXing. */
    @NonNull
    public static String decodeQrArgbPixels(@NonNull int[] argbPixels, int width, int height)
            throws IOException {
        if (width <= 0 || height <= 0 || argbPixels.length < width * height) {
            throw new IOException("Invalid QR image dimensions");
        }
        try {
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, argbPixels);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            Result result = new QRCodeReader().decode(bitmap, hints);
            String text = result.getText();
            if (text == null || text.trim().isEmpty()) {
                throw new IOException("QR code contained empty text");
            }
            return text;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No valid Zyrox control profile QR code detected in image", e);
        }
    }

    @NonNull
    public static Bitmap renderQrBitmap(@NonNull String sharePayload, int sizePx)
            throws IOException {
        BitMatrix matrix = encodeQrMatrix(sharePayload, sizePx);
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    /** Decodes a control profile QR code PNG/JPEG selected from storage and saves it to controlsDir. */
    @NonNull
    public static File importControlFromQrImageUri(@NonNull Context context,
                                                   @NonNull Uri imageUri) throws IOException {
        if (Tools.CTRLMAP_PATH == null) {
            throw new IOException("Controls directory not initialized");
        }
        final Bitmap bmp;
        try (InputStream in = context.getContentResolver().openInputStream(imageUri)) {
            if (in == null) throw new IOException("Unable to open selected QR image");
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) {
            throw new IOException("Selected file could not be decoded as an image");
        }
        try {
            Bitmap working = bmp;
            if (Math.max(bmp.getWidth(), bmp.getHeight()) > 1200) {
                float scale = 1200f / Math.max(bmp.getWidth(), bmp.getHeight());
                working = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * scale)),
                        Math.max(1, Math.round(bmp.getHeight() * scale)),
                        true);
            }
            int w = working.getWidth();
            int h = working.getHeight();
            int[] pixels = new int[w * h];
            working.getPixels(pixels, 0, w, 0, 0, w, h);
            if (working != bmp) working.recycle();

            String qrText = decodeQrArgbPixels(pixels, w, h);
            DecodedShareProfile decoded = decodeSharePayload(qrText);
            return importProfile(
                    new ByteArrayInputStream(decoded.jsonText.getBytes(StandardCharsets.UTF_8)),
                    new File(Tools.CTRLMAP_PATH),
                    decoded.profileName);
        } finally {
            bmp.recycle();
        }
    }

    /** Displays an interactive QR Code + compact token sharing dialog for {@code profileFile}. */
    public static void showShareQrDialog(@NonNull Context context, @NonNull File profileFile) {
        try {
            byte[] raw;
            try (FileInputStream in = new FileInputStream(profileFile)) {
                raw = ArchiveSecurity.readBytesBounded(in, MAX_CONTROL_JSON_BYTES);
            }
            String json = new String(raw, StandardCharsets.UTF_8);
            final String token = encodeSharePayload(profileFile.getName(), json);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = ZyroxStudioUi.dp(context, 14);
            root.setPadding(pad, pad, pad, pad);

            if (token.length() <= MAX_QR_PAYLOAD_CHARS) {
                int qrSize = ZyroxStudioUi.dp(context, 220);
                Bitmap qrBmp = renderQrBitmap(token, qrSize);
                ImageView iv = new ImageView(context);
                iv.setImageBitmap(qrBmp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                root.addView(iv, new LinearLayout.LayoutParams(qrSize, qrSize));

                TextView caption = ZyroxStudioUi.createBody(context,
                        "Scan or screenshot this QR code to share \"" + profileFile.getName()
                                + "\" (" + token.length() + " chars compressed).");
                caption.setGravity(Gravity.CENTER_HORIZONTAL);
                root.addView(caption, ZyroxStudioUi.vParam(ZyroxStudioUi.dp(context, 8)));
            } else {
                TextView note = ZyroxStudioUi.createBody(context,
                        "This layout contains many controls (" + token.length()
                                + " compressed chars). Tap 'Copy Token' below to share the full lossless profile code!");
                root.addView(note, ZyroxStudioUi.vParam(0));
            }

            new AlertDialog.Builder(context)
                    .setTitle("Share Control Profile (QR / JSON)")
                    .setView(root)
                    .setPositiveButton("Copy Token", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager)
                                context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("zyrox_control_profile", token));
                            Toast.makeText(context,
                                    "Control share token copied to clipboard!",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Copy Raw JSON", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager)
                                context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("zyrox_control_json", json));
                            Toast.makeText(context, "Control JSON copied!", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            Tools.showError(context, e);
        }
    }

    /** Displays an import dialog accepting a QR image PNG, pasted ZYROXCTRL1 token, or raw JSON. */
    public static void showImportQrOrTokenDialog(@NonNull Context context,
                                                 @Nullable Runnable onPickQrImage,
                                                 @Nullable OnProfileImportedListener listener) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(context, 14);
        root.setPadding(pad, pad, pad, pad);

        EditText input = ZyroxStudioUi.createGlassSearchInput(
                context, "Paste ZYROXCTRL1:... token or raw control JSON");
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(6);
        root.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(context)
                .setTitle("Import Control Profile (QR / Token / JSON)")
                .setView(root)
                .setPositiveButton("Import Code", (d, w) -> {
                    try {
                        DecodedShareProfile decoded = decodeSharePayload(
                                input.getText().toString());
                        File saved = importProfile(
                                new ByteArrayInputStream(
                                        decoded.jsonText.getBytes(StandardCharsets.UTF_8)),
                                new File(Tools.CTRLMAP_PATH),
                                decoded.profileName);
                        Toast.makeText(context,
                                "Imported " + saved.getName()
                                        + " (" + decoded.audit.totalButtons + " controls)!",
                                Toast.LENGTH_SHORT).show();
                        if (listener != null) listener.onProfileImported(saved);
                    } catch (Exception e) {
                        Tools.showError(context, e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (onPickQrImage != null) {
            b.setNeutralButton("Scan QR Image…", (d, w) -> onPickQrImage.run());
        }
        b.show();
    }

    // Portable RFC 4648 URL-safe Base64 (works identically on Android API 21+ AND JVM unit tests)
    private static final char[] B64_URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] B64_INV = new int[128];
    static {
        Arrays.fill(B64_INV, -1);
        for (int i = 0; i < B64_URL.length; i++) B64_INV[B64_URL[i]] = i;
        B64_INV['+'] = 62;
        B64_INV['/'] = 63;
    }

    @NonNull
    private static String toUrlSafeBase64(@NonNull byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i++] & 0xFF) << 16) | ((data[i++] & 0xFF) << 8) | (data[i++] & 0xFF);
            sb.append(B64_URL[(n >>> 18) & 63])
                    .append(B64_URL[(n >>> 12) & 63])
                    .append(B64_URL[(n >>> 6) & 63])
                    .append(B64_URL[n & 63]);
        }
        int rem = data.length - i;
        if (rem == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(B64_URL[(n >>> 18) & 63]).append(B64_URL[(n >>> 12) & 63]);
        } else if (rem == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64_URL[(n >>> 18) & 63])
                    .append(B64_URL[(n >>> 12) & 63])
                    .append(B64_URL[(n >>> 6) & 63]);
        }
        return sb.toString();
    }

    @NonNull
    private static byte[] fromUrlSafeBase64(@NonNull String raw) throws IOException {
        String s = raw.trim();
        while (s.endsWith("=")) s = s.substring(0, s.length() - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream((s.length() * 3) / 4 + 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c >= 128 || B64_INV[c] < 0) {
                throw new IOException("Invalid Base64 character in share token");
            }
            buffer = (buffer << 6) | B64_INV[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    // =========================================================================
    // Control Layout Schema & Ergonomics Auditor
    // =========================================================================

    @NonNull
    public static LayoutAuditReport validateAndAuditJson(@NonNull String jsonText)
            throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(jsonText).getAsJsonObject();
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
            float w = btn.has("width") && btn.get("width").isJsonPrimitive()
                    ? btn.get("width").getAsFloat() : 50f;
            float h = btn.has("height") && btn.get("height").isJsonPrimitive()
                    ? btn.get("height").getAsFloat() : 50f;

            if (w < MIN_RECOMMENDED_TOUCH_TARGET_DP && h < MIN_RECOMMENDED_TOUCH_TARGET_DP) {
                smallTargets.add("\"" + name + "\" is very small ("
                        + Math.round(w) + "×" + Math.round(h) + "dp)");
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
                    if (kc == 0) continue;
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
                conflicts.add("Keycode " + describeKeycode(e.getKey())
                        + " bound to multiple controls: " + joinNames(e.getValue()));
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
