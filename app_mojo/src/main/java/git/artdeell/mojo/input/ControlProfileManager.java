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
    /** Phase 4.2 — Universal Control &amp; Settings Share Code prefix ({@code ZYROX://...}). */
    public static final String SETUP_CODE_PREFIX = "ZYROX://";
    /** Version marker inside {@code ZYROX://} bundles ({@code ZYROX://v1.<sha256_16>.<b64url>}). */
    private static final String SETUP_CODE_HEADER = "ZYROX://v1.";
    /** Maximum payload characters encodable in a standard Version 40-L QR Code (~2,900 chars). */
    public static final int MAX_QR_PAYLOAD_CHARS = 2850;
    private static final int MAX_CONTROL_JSON_BYTES = 2 * 1024 * 1024; // 2 MiB zip-bomb ceiling

    public interface SetupExportCallback {
        void onSaveFileRequested(@NonNull String suggestedName);
    }

    public interface SetupImportCallback {
        void onSetupImported(@NonNull SetupImportResult result);
    }

    /** Parsed universal Zyrox setup bundle (control layout + touch/gyro + renderer/scale). */
    public static final class ZyroxSetupBundle {
        @NonNull public final String profileName;
        @NonNull public final String controlJson;
        public final int buttonScalePct;
        public final int mouseScalePct;
        public final int mouseSpeedPct;
        public final boolean gyroEnabled;
        public final float gyroSensitivity;
        public final boolean gyroInvertX;
        public final boolean gyroInvertY;
        @NonNull public final String rendererTag;
        @NonNull public final String performancePreset;
        public final int resolutionScalePct;
        public final boolean contextAwareControls;

        public ZyroxSetupBundle(@NonNull String profileName,
                                @NonNull String controlJson,
                                int buttonScalePct,
                                int mouseScalePct,
                                int mouseSpeedPct,
                                boolean gyroEnabled,
                                float gyroSensitivity,
                                boolean gyroInvertX,
                                boolean gyroInvertY,
                                @NonNull String rendererTag,
                                @NonNull String performancePreset,
                                int resolutionScalePct,
                                boolean contextAwareControls) {
            this.profileName = profileName;
            this.controlJson = controlJson;
            this.buttonScalePct = buttonScalePct;
            this.mouseScalePct = mouseScalePct;
            this.mouseSpeedPct = mouseSpeedPct;
            this.gyroEnabled = gyroEnabled;
            this.gyroSensitivity = gyroSensitivity;
            this.gyroInvertX = gyroInvertX;
            this.gyroInvertY = gyroInvertY;
            this.rendererTag = rendererTag;
            this.performancePreset = performancePreset;
            this.resolutionScalePct = resolutionScalePct;
            this.contextAwareControls = contextAwareControls;
        }
    }

    /** Result returned after importing and verifying a {@code ZYROX://} code or {@code .zyrox} file. */
    public static final class SetupImportResult {
        @NonNull public final String profileName;
        @Nullable public final File controlFile;
        @NonNull public final ZyroxSetupBundle bundle;
        @NonNull public final String summary;

        public SetupImportResult(@NonNull String profileName,
                                 @Nullable File controlFile,
                                 @NonNull ZyroxSetupBundle bundle,
                                 @NonNull String summary) {
            this.profileName = profileName;
            this.controlFile = controlFile;
            this.bundle = bundle;
            this.summary = summary;
        }
    }

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

    // =============================================================================================
    // PHASE 4.2 — UNIVERSAL ZYROX:// CONTROL + TOUCH/GYRO + RENDERER SHARE CODES & .zyrox BUNDLES
    // =============================================================================================

    /** Returns {@code true} when {@code raw} starts with {@code ZYROX://} or legacy {@code ZYROXCTRL1:}. */
    public static boolean isValidZyroxSetupCode(@Nullable String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        return trimmed.startsWith(SETUP_CODE_PREFIX) || trimmed.startsWith(SHARE_TOKEN_PREFIX);
    }

    /**
     * Pure deterministic encoder: serializes a {@link ZyroxSetupBundle}, compresses with max
     * DEFLATE, computes a truncated 64-bit SHA-256 integrity tag, and formats a shareable
     * {@code ZYROX://v1.<sha256_16>.<base64url>} code suitable for Discord/YouTube/QR/files.
     */
    @NonNull
    public static String encodeZyroxSetupBundle(@NonNull ZyroxSetupBundle bundle) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "zyrox-setup-v1");
        root.addProperty("name", bundle.profileName);
        root.addProperty("buttonScale", Math.max(40, Math.min(250, bundle.buttonScalePct)));
        root.addProperty("mouseScale", Math.max(25, Math.min(300, bundle.mouseScalePct)));
        root.addProperty("mouseSpeed", Math.max(25, Math.min(300, bundle.mouseSpeedPct)));
        root.addProperty("gyroEnabled", bundle.gyroEnabled);
        root.addProperty("gyroSensitivity", Math.max(0.1f, Math.min(5.0f, bundle.gyroSensitivity)));
        root.addProperty("gyroInvertX", bundle.gyroInvertX);
        root.addProperty("gyroInvertY", bundle.gyroInvertY);
        root.addProperty("renderer", bundle.rendererTag);
        root.addProperty("preset", bundle.performancePreset);
        root.addProperty("resolutionScale", Math.max(25, Math.min(100, bundle.resolutionScalePct)));
        root.addProperty("contextAwareControls", bundle.contextAwareControls);
        if (!bundle.controlJson.trim().isEmpty()) {
            JsonObject parsedLayout = new JsonParser().parse(bundle.controlJson).getAsJsonObject();
            root.add("layout", parsedLayout);
        }

        byte[] utf8 = root.toString().getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_CONTROL_JSON_BYTES) {
            throw new IOException("Setup bundle exceeds safe 2 MiB size ceiling.");
        }
        String shaTag = sha256Prefix16(utf8);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(256, utf8.length / 3));
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed, deflater)) {
            dos.write(utf8);
        } finally {
            deflater.end();
        }
        String b64 = toUrlSafeBase64NoPad(compressed.toByteArray());
        return SETUP_CODE_HEADER + shaTag + "." + b64;
    }

    /**
     * Pure deterministic decoder: validates format, bounds decompression at 2 MiB (zip-bomb safe),
     * verifies SHA-256 checksum integrity, and reconstructs the {@link ZyroxSetupBundle}.
     */
    @NonNull
    public static ZyroxSetupBundle decodeZyroxSetupBundle(@NonNull String rawCode) throws IOException {
        String trimmed = rawCode.trim();
        if (!trimmed.startsWith(SETUP_CODE_HEADER)) {
            throw new IOException("Invalid setup code — expected ZYROX://v1.<checksum>.<payload>.");
        }
        String rest = trimmed.substring(SETUP_CODE_HEADER.length());
        int dot = rest.indexOf('.');
        if (dot != 16 || dot + 1 >= rest.length()) {
            throw new IOException("Malformed ZYROX:// checksum header.");
        }
        String expectedSha = rest.substring(0, 16).toLowerCase(Locale.ROOT);
        String b64 = rest.substring(dot + 1).replaceAll("\\s+", "");
        byte[] compressed = fromUrlSafeBase64(b64);
        if (compressed.length == 0 || compressed.length > MAX_CONTROL_JSON_BYTES) {
            throw new IOException("Setup code payload out of bounds.");
        }

        byte[] utf8 = inflateRawBounded(compressed, MAX_CONTROL_JSON_BYTES);
        String actualSha = sha256Prefix16(utf8);
        if (!expectedSha.equals(actualSha)) {
            throw new IOException("ZYROX:// checksum mismatch — code was truncated or modified.");
        }

        final JsonObject root;
        try {
            root = new JsonParser().parse(new String(utf8, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Corrupted setup bundle JSON.", e);
        }
        String name = root.has("name") && !root.get("name").isJsonNull()
                ? root.get("name").getAsString().trim() : "Zyrox Shared Setup";
        if (name.isEmpty()) name = "Zyrox Shared Setup";
        String layoutJson = (root.has("layout") && root.get("layout").isJsonObject())
                ? root.getAsJsonObject("layout").toString() : "";

        return new ZyroxSetupBundle(
                name,
                layoutJson,
                optClampedInt(root, "buttonScale", 100, 40, 250),
                optClampedInt(root, "mouseScale", 100, 25, 300),
                optClampedInt(root, "mouseSpeed", 100, 25, 300),
                optBool(root, "gyroEnabled", false),
                optClampedFloat(root, "gyroSensitivity", 1.0f, 0.1f, 5.0f),
                optBool(root, "gyroInvertX", false),
                optBool(root, "gyroInvertY", false),
                optSafeString(root, "renderer", ""),
                optSafeString(root, "preset", "balanced"),
                optClampedInt(root, "resolutionScale", 100, 25, 100),
                optBool(root, "contextAwareControls", true)
        );
    }

    /** Builds a shareable {@code ZYROX://...} code from the active {@link git.artdeell.mojo.instances.Instance} &amp; prefs. */
    @NonNull
    public static String exportZyroxSetupCode(
            @Nullable git.artdeell.mojo.instances.Instance instance) throws IOException {
        File ctrlFile = null;
        if (instance != null && Tools.isValidString(instance.controlLayout)) {
            File direct = new File(instance.controlLayout);
            File candidate = direct.isFile()
                    ? direct : new File(Tools.CTRLMAP_PATH, instance.controlLayout);
            if (candidate.isFile()) ctrlFile = candidate;
        }
        if (ctrlFile == null && Tools.isValidString(
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_DEFAULTCTRL_PATH)) {
            File def = new File(git.artdeell.mojo.prefs.LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            if (def.isFile()) ctrlFile = def;
        }
        String controlJson = "";
        String profileName = (instance != null && Tools.isValidString(instance.name))
                ? instance.name : "Zyrox Setup";
        if (ctrlFile != null && ctrlFile.isFile() && ctrlFile.length() <= MAX_CONTROL_JSON_BYTES) {
            try (FileInputStream in = new FileInputStream(ctrlFile)) {
                controlJson = new String(
                        ArchiveSecurity.readBytesBounded(in, MAX_CONTROL_JSON_BYTES),
                        StandardCharsets.UTF_8);
            }
            if (!Tools.isValidString(profileName)) {
                profileName = FileUtils.removeExtension(ctrlFile.getName());
            }
        }
        int resScale = (instance != null && instance.resolutionScalePercent >= 50
                && instance.resolutionScalePercent <= 100)
                ? instance.resolutionScalePercent
                : Math.round(git.artdeell.mojo.prefs.LauncherPreferences.PREF_SCALE_FACTOR * 100f);
        String renderer = (instance != null && Tools.isValidString(instance.renderer))
                ? instance.renderer
                : (git.artdeell.mojo.prefs.LauncherPreferences.PREF_RENDERER != null
                ? git.artdeell.mojo.prefs.LauncherPreferences.PREF_RENDERER : "");
        String preset = (instance != null && Tools.isValidString(instance.performancePreset))
                ? instance.performancePreset : "balanced";

        ZyroxSetupBundle bundle = new ZyroxSetupBundle(
                profileName,
                controlJson,
                Math.round(git.artdeell.mojo.prefs.LauncherPreferences.PREF_BUTTONSIZE),
                Math.round(git.artdeell.mojo.prefs.LauncherPreferences.PREF_MOUSESCALE * 100f),
                Math.round(git.artdeell.mojo.prefs.LauncherPreferences.PREF_MOUSESPEED * 100f),
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_ENABLE_GYRO,
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_SENSITIVITY,
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_INVERT_X,
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_INVERT_Y,
                renderer,
                preset,
                resScale,
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_ZYROX_CONTEXT_AWARE_CONTROLS
        );
        return encodeZyroxSetupBundle(bundle);
    }

    /**
     * Verifies and imports a {@code ZYROX://} code (or legacy {@code ZYROXCTRL1:} token), writes
     * the sanitized control layout JSON into {@code CTRLMAP_PATH}, and optionally applies touch,
     * gyro, and resolution settings to {@code LauncherPreferences}.
     */
    @NonNull
    public static SetupImportResult importZyroxSetupCode(@NonNull String payload,
                                                         @Nullable String targetFileName,
                                                         boolean applySettings) throws IOException {
        String trimmed = payload.trim();
        if (trimmed.startsWith(SHARE_TOKEN_PREFIX)) {
            DecodedShareProfile legacy = decodeShareToken(trimmed);
            File saved = saveControlJsonAtomically(
                    targetFileName != null ? targetFileName : legacy.profileName, legacy.jsonText);
            ZyroxSetupBundle legacyBundle = new ZyroxSetupBundle(
                    legacy.profileName, legacy.jsonText,
                    100, 100, 100, false, 1.0f, false, false, "", "balanced", 100, true);
            return new SetupImportResult(
                    legacy.profileName, saved, legacyBundle,
                    "Imported control profile '" + saved.getName() + "'.");
        }

        ZyroxSetupBundle bundle = decodeZyroxSetupBundle(trimmed);
        File savedFile = null;
        if (!bundle.controlJson.trim().isEmpty() && Tools.CTRLMAP_PATH != null) {
            savedFile = saveControlJsonAtomically(
                    targetFileName != null ? targetFileName : bundle.profileName,
                    bundle.controlJson);
        }

        if (applySettings && git.artdeell.mojo.prefs.LauncherPreferences.DEFAULT_PREF != null) {
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_BUTTONSIZE = bundle.buttonScalePct;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_MOUSESCALE = bundle.mouseScalePct / 100f;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_MOUSESPEED = bundle.mouseSpeedPct / 100f;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_ENABLE_GYRO = bundle.gyroEnabled;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_SENSITIVITY = bundle.gyroSensitivity;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_INVERT_X = bundle.gyroInvertX;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_GYRO_INVERT_Y = bundle.gyroInvertY;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_SCALE_FACTOR = bundle.resolutionScalePct / 100f;
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_ZYROX_CONTEXT_AWARE_CONTROLS =
                    bundle.contextAwareControls;
            android.content.SharedPreferences.Editor ed =
                    git.artdeell.mojo.prefs.LauncherPreferences.DEFAULT_PREF.edit();
            ed.putInt("buttonscale", bundle.buttonScalePct);
            ed.putInt("mousescale", bundle.mouseScalePct);
            ed.putInt("mousespeed", bundle.mouseSpeedPct);
            ed.putBoolean("enableGyro", bundle.gyroEnabled);
            ed.putInt("gyroSensitivity", Math.round(bundle.gyroSensitivity * 100f));
            ed.putBoolean("gyroInvertX", bundle.gyroInvertX);
            ed.putBoolean("gyroInvertY", bundle.gyroInvertY);
            ed.putInt("resolutionRatio", bundle.resolutionScalePct);
            ed.putBoolean("zyroxContextAwareControls", bundle.contextAwareControls);
            if (savedFile != null) {
                git.artdeell.mojo.prefs.LauncherPreferences.PREF_DEFAULTCTRL_PATH =
                        savedFile.getAbsolutePath();
                ed.putString("defaultCtrl", savedFile.getAbsolutePath());
            }
            ed.apply();
        }

        StringBuilder summary = new StringBuilder("Imported '").append(bundle.profileName).append('\'');
        if (savedFile != null) {
            summary.append(" • HUD: ").append(savedFile.getName());
        }
        summary.append(" • Scale ").append(bundle.resolutionScalePct).append('%');
        if (bundle.gyroEnabled) {
            summary.append(String.format(Locale.ROOT, " • Gyro %.1fx", bundle.gyroSensitivity));
        }
        return new SetupImportResult(bundle.profileName, savedFile, bundle, summary.toString());
    }

    /** Shows the interactive Share Zyrox Setup dialog (Copy {@code ZYROX://} code, QR preview, Save {@code .zyrox}). */
    public static void showZyroxSetupExportDialog(
            @NonNull Context ctx,
            @Nullable git.artdeell.mojo.instances.Instance instance,
            @Nullable SetupExportCallback exportCallback) {
        final String code;
        try {
            code = exportZyroxSetupCode(instance);
        } catch (Exception e) {
            Toast.makeText(ctx, "Could not build setup code: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        body.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 6));

        TextView hint = new TextView(ctx);
        hint.setText("Bundles your control layout, button scale, gyro sensitivity, renderer preset & resolution scale into a verified ZYROX:// code.");
        hint.setTextColor(0xFFD6E4F8);
        hint.setTextSize(12.5f);
        body.addView(hint);

        if (code.length() <= MAX_QR_PAYLOAD_CHARS) {
            try {
                Bitmap qr = renderQrBitmap(code, ZyroxStudioUi.dp(ctx, 180));
                ImageView iv = new ImageView(ctx);
                iv.setImageBitmap(qr);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ZyroxStudioUi.dp(ctx, 180), ZyroxStudioUi.dp(ctx, 180));
                lp.topMargin = ZyroxStudioUi.dp(ctx, 10);
                body.addView(iv, lp);
            } catch (Exception ignored) {
            }
        }

        TextView preview = new TextView(ctx);
        String shortCode = code.length() > 84
                ? (code.substring(0, 56) + "…" + code.substring(code.length() - 18))
                : code;
        preview.setText(shortCode + " (" + code.length() + " chars • SHA-256 verified)");
        preview.setTextColor(0xFFA8E8FF);
        preview.setTextSize(11f);
        preview.setPadding(0, ZyroxStudioUi.dp(ctx, 8), 0, 0);
        body.addView(preview);

        String baseName = (instance != null && Tools.isValidString(instance.name))
                ? FileUtils.escapeFileName(instance.name) : "zyrox-setup";
        if (baseName.isEmpty()) baseName = "zyrox-setup";
        final String suggestedFile = baseName + ".zyrox";

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle("Share Zyrox Setup (ZYROX://)")
                .setView(body)
                .setPositiveButton("Copy ZYROX:// Code", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Zyrox Setup Code", code));
                    }
                    Toast.makeText(ctx, "ZYROX:// setup code copied to clipboard!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.close, null);
        if (exportCallback != null) {
            b.setNeutralButton("Save .zyrox File", (d, w) ->
                    exportCallback.onSaveFileRequested(suggestedFile));
        }
        b.show();
    }

    /** Shows the interactive Import {@code ZYROX://} Setup dialog (auto-prefills from clipboard). */
    public static void showZyroxSetupImportDialog(
            @NonNull Context ctx,
            @Nullable git.artdeell.mojo.instances.Instance instance,
            @Nullable SetupImportCallback callback) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        body.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 6));

        EditText input = ZyroxStudioUi.createStyledInput(ctx, "Paste ZYROX://v1... code");
        input.setSingleLine(false);
        input.setMaxLines(5);
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && isValidZyroxSetupCode(clip.toString())) {
                    input.setText(clip.toString().trim());
                }
            }
        } catch (Throwable ignored) {
        }
        body.addView(input);

        new AlertDialog.Builder(ctx)
                .setTitle("Import Zyrox Setup Code")
                .setView(body)
                .setPositiveButton("Verify & Apply", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (!isValidZyroxSetupCode(raw)) {
                        Toast.makeText(ctx, "Paste a valid ZYROX:// setup code.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        SetupImportResult res = importZyroxSetupCode(raw, null, true);
                        if (instance != null) {
                            if (res.controlFile != null) {
                                instance.controlLayout = res.controlFile.getAbsolutePath();
                            }
                            if (!res.bundle.rendererTag.isEmpty()) {
                                instance.renderer = res.bundle.rendererTag;
                            }
                            instance.performancePreset = res.bundle.performancePreset;
                            instance.resolutionScalePercent =
                                    Math.max(50, Math.min(100, res.bundle.resolutionScalePct));
                            instance.maybeWrite();
                        }
                        Toast.makeText(ctx, res.summary, Toast.LENGTH_LONG).show();
                        if (callback != null) callback.onSetupImported(res);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private static File saveControlJsonAtomically(@NonNull String rawName,
                                                  @NonNull String jsonText) throws IOException {
        File dir = new File(Tools.CTRLMAP_PATH);
        FileUtils.ensureDirectory(dir);
        String clean = FileUtils.escapeFileName(FileUtils.removeExtension(rawName));
        if (clean.isEmpty()) clean = "zyrox_imported";
        if (clean.length() > 48) clean = clean.substring(0, 48);
        File target = new File(dir, clean + ".json");
        File tmp = new File(dir, clean + ".json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(jsonText.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not replace " + target.getName());
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not finalize " + target.getName());
        }
        return target;
    }

    @NonNull
    private static byte[] inflateRawBounded(@NonNull byte[] compressed, int maxBytes) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try (InflaterInputStream iis = new InflaterInputStream(
                new ByteArrayInputStream(compressed), inflater)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = iis.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("Setup bundle exceeds safe decompression limit.");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Corrupted compressed ZYROX:// stream.", e);
        } finally {
            inflater.end();
        }
    }

    @NonNull
    private static String sha256Prefix16(@NonNull byte[] data) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[16];
            for (int i = 0; i < 8; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    @NonNull
    private static String toUrlSafeBase64NoPad(@NonNull byte[] data) {
        // Portable JVM + Android API 21+ URL-safe Base64 encoder (no android.util.Base64 stub trap!)
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i++] & 0xFF) << 16) | ((data[i++] & 0xFF) << 8) | (data[i++] & 0xFF);
            sb.append(alphabet[(n >>> 18) & 63])
                    .append(alphabet[(n >>> 12) & 63])
                    .append(alphabet[(n >>> 6) & 63])
                    .append(alphabet[n & 63]);
        }
        int rem = data.length - i;
        if (rem == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(alphabet[(n >>> 18) & 63]).append(alphabet[(n >>> 12) & 63]);
        } else if (rem == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(alphabet[(n >>> 18) & 63])
                    .append(alphabet[(n >>> 12) & 63])
                    .append(alphabet[(n >>> 6) & 63]);
        }
        return sb.toString();
    }

    @NonNull
    private static byte[] fromUrlSafeBase64(@NonNull String s) throws IOException {
        int[] rev = new int[128];
        Arrays.fill(rev, -1);
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < alpha.length(); i++) rev[alpha.charAt(i)] = i;
        rev['+'] = 62;
        rev['/'] = 63;

        ByteArrayOutputStream out = new ByteArrayOutputStream((s.length() * 3) / 4);
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (c >= 128 || rev[c] < 0) {
                throw new IOException("Invalid Base64 character in ZYROX:// code.");
            }
            acc = (acc << 6) | rev[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static int optClampedInt(@NonNull JsonObject obj, @NonNull String key,
                                     int def, int min, int max) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return Math.max(min, Math.min(max, obj.get(key).getAsInt()));
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static float optClampedFloat(@NonNull JsonObject obj, @NonNull String key,
                                         float def, float min, float max) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                float v = obj.get(key).getAsFloat();
                if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                    return Math.max(min, Math.min(max, v));
                }
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static boolean optBool(@NonNull JsonObject obj, @NonNull String key, boolean def) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsBoolean();
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    @NonNull
    private static String optSafeString(@NonNull JsonObject obj, @NonNull String key, @NonNull String def) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString().trim();
            }
        } catch (Throwable ignored) {
        }
        return def;
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
