package git.artdeell.mojo.skins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.SkinHeadRenderer;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

/**
 * Zyrox 2.0 Skin Studio Manager.
 *
 * <p>Validates real Minecraft skin PNG headers and dimensions ({@code 64x64} modern Steve/Alex or
 * {@code 64x32} classic legacy), manages a persistent local skin library, renders preview avatars,
 * clearly separates local skin library entries from online account skins, and uploads to the
 * official {@code api.minecraftservices.com} HTTPS endpoint when the user is authenticated with a
 * Microsoft account and explicitly requests upload.
 */
public final class SkinStudioManager {
    public static final int MAX_SKIN_FILE_BYTES = 1024 * 1024; // 1 MiB ceiling
    private static final String OFFICIAL_SKIN_UPLOAD_URL =
            "https://api.minecraftservices.com/minecraft/profile/skins";
    private static final String INDEX_FILE = "skins_index.json";

    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public enum SkinModel {
        CLASSIC("classic", "Classic (Steve • 4px)"),
        SLIM("slim", "Slim (Alex • 3px)");

        @NonNull public final String apiVariant;
        @NonNull public final String displayLabel;

        SkinModel(@NonNull String apiVariant, @NonNull String displayLabel) {
            this.apiVariant = apiVariant;
            this.displayLabel = displayLabel;
        }
    }

    public static final class PngValidation {
        public final boolean valid;
        public final int width;
        public final int height;
        public final boolean legacy64x32;
        @NonNull public final String message;

        public PngValidation(boolean valid, int width, int height, boolean legacy64x32, @NonNull String message) {
            this.valid = valid;
            this.width = width;
            this.height = height;
            this.legacy64x32 = legacy64x32;
            this.message = message;
        }
    }

    public static class SkinEntry {
        public String id;
        public String name;
        public String fileName;
        public String model; // "classic" or "slim"
        public int width;
        public int height;
        public boolean activeLocal;
        public long importedAtMs;

        @NonNull
        public SkinModel getSkinModel() {
            return "slim".equalsIgnoreCase(model) ? SkinModel.SLIM : SkinModel.CLASSIC;
        }
    }

    public static class SkinLibraryIndex {
        public int schemaVersion = 1;
        public List<SkinEntry> skins = new ArrayList<>();
    }

    private SkinStudioManager() {
    }

    @NonNull
    public static File getLibraryDir(@NonNull File gameHome) {
        return new File(gameHome, "zyrox_skins");
    }

    /**
     * Pure-Java PNG signature and IHDR dimension check (works in both JVM unit tests and Android).
     * Accepts only genuine {@code 64x64} or {@code 64x32} PNG skins within 1 MiB.
     */
    @NonNull
    public static PngValidation validateSkinPngBytes(@Nullable byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length < 33) {
            return new PngValidation(false, 0, 0, false, "File is empty or too small to be a valid PNG skin.");
        }
        if (pngBytes.length > MAX_SKIN_FILE_BYTES) {
            return new PngValidation(false, 0, 0, false, "Skin PNG exceeds the 1 MB maximum size limit.");
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (pngBytes[i] != PNG_MAGIC[i]) {
                return new PngValidation(false, 0, 0, false, "Invalid file format: expected a standard PNG image.");
            }
        }
        // Verify first chunk is IHDR (bytes 12..15)
        if (pngBytes[12] != 'I' || pngBytes[13] != 'H' || pngBytes[14] != 'D' || pngBytes[15] != 'R') {
            return new PngValidation(false, 0, 0, false, "Malformed PNG header (missing IHDR chunk).");
        }
        int width = readBigEndianInt(pngBytes, 16);
        int height = readBigEndianInt(pngBytes, 20);
        if (width == 64 && height == 64) {
            return new PngValidation(true, 64, 64, false, "Valid 64×64 dual-layer Minecraft skin");
        }
        if (width == 64 && height == 32) {
            return new PngValidation(true, 64, 32, true, "Valid 64×32 classic legacy Minecraft skin");
        }
        return new PngValidation(false, width, height, false,
                "Unsupported skin dimensions (" + width + "×" + height + "). Minecraft Java requires 64×64 or 64×32 PNG.");
    }

    /**
     * Heuristic detection of Slim (Alex, 3px arm) vs Classic (Steve, 4px arm) on a decoded 64×64 skin.
     */
    @NonNull
    public static SkinModel detectModel(@Nullable Bitmap skinBitmap) {
        if (skinBitmap == null || skinBitmap.getWidth() != 64 || skinBitmap.getHeight() != 64) {
            return SkinModel.CLASSIC;
        }
        // On slim skins, column x=54, y=20..31 (4th pixel column of right arm back) is fully transparent.
        int transparentCount = 0;
        for (int y = 20; y < 32; y++) {
            if (Color.alpha(skinBitmap.getPixel(54, y)) == 0) {
                transparentCount++;
            }
        }
        return transparentCount >= 10 ? SkinModel.SLIM : SkinModel.CLASSIC;
    }

    @NonNull
    public static List<SkinEntry> listSkins(@NonNull File libraryDir) {
        SkinLibraryIndex idx = loadIndex(libraryDir);
        List<SkinEntry> valid = new ArrayList<>();
        for (SkinEntry e : idx.skins) {
            if (e == null || e.fileName == null) continue;
            if (e.fileName.contains("..") || e.fileName.contains("/") || e.fileName.contains("\\")) continue;
            if (new File(libraryDir, e.fileName).isFile()) {
                valid.add(e);
            }
        }
        return Collections.unmodifiableList(valid);
    }

    @NonNull
    public static SkinEntry importSkin(@NonNull File libraryDir,
                                       @NonNull InputStream inputStream,
                                       @Nullable String displayName,
                                       @Nullable SkinModel explicitModel) throws IOException {
        byte[] bytes = ArchiveSecurity.readBytesBounded(inputStream, MAX_SKIN_FILE_BYTES);
        PngValidation check = validateSkinPngBytes(bytes);
        if (!check.valid) {
            throw new IOException(check.message);
        }

        SkinModel chosenModel = explicitModel;
        if (chosenModel == null) {
            if (check.legacy64x32) {
                chosenModel = SkinModel.CLASSIC;
            } else {
                try {
                    Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    chosenModel = detectModel(decoded);
                    if (decoded != null) decoded.recycle();
                } catch (Throwable ignored) {
                    chosenModel = SkinModel.CLASSIC;
                }
            }
        }

        FileUtils.ensureDirectory(libraryDir);
        String id = UUID.randomUUID().toString().substring(0, 8);
        String fileName = "skin-" + id + ".png";
        File target = new File(libraryDir, fileName);
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(bytes);
            out.flush();
        }

        String cleanName = (displayName != null && !displayName.trim().isEmpty())
                ? displayName.trim().replaceAll("(?i)\\.png$", "")
                : ("Skin " + id);

        SkinEntry entry = new SkinEntry();
        entry.id = id;
        entry.name = cleanName;
        entry.fileName = fileName;
        entry.model = check.legacy64x32 ? SkinModel.CLASSIC.apiVariant : chosenModel.apiVariant;
        entry.width = check.width;
        entry.height = check.height;
        entry.importedAtMs = System.currentTimeMillis();

        SkinLibraryIndex idx = loadIndex(libraryDir);
        if (idx.skins.isEmpty()) {
            entry.activeLocal = true;
        }
        idx.skins.add(0, entry);
        saveIndex(libraryDir, idx);
        return entry;
    }

    public static void updateSkinModel(@NonNull File libraryDir,
                                       @NonNull String skinId,
                                       @NonNull SkinModel model) throws IOException {
        SkinLibraryIndex idx = loadIndex(libraryDir);
        for (SkinEntry e : idx.skins) {
            if (skinId.equals(e.id)) {
                if (e.height == 32 && model == SkinModel.SLIM) {
                    throw new IOException("64×32 legacy skins only support Classic (Steve) 4px arms.");
                }
                e.model = model.apiVariant;
                break;
            }
        }
        saveIndex(libraryDir, idx);
    }

    public static void setActiveLocalSkin(@NonNull File libraryDir,
                                          @NonNull String skinId,
                                          @Nullable File cacheDir,
                                          @Nullable Account currentAccount) throws IOException {
        SkinLibraryIndex idx = loadIndex(libraryDir);
        SkinEntry active = null;
        for (SkinEntry e : idx.skins) {
            e.activeLocal = skinId.equals(e.id);
            if (e.activeLocal) active = e;
        }
        saveIndex(libraryDir, idx);

        // Render face preview cache for local launcher display without altering account auth tokens
        if (active != null && cacheDir != null && currentAccount != null && currentAccount.isLocal()) {
            File pngFile = new File(libraryDir, active.fileName);
            Bitmap skinBmp = BitmapFactory.decodeFile(pngFile.getAbsolutePath());
            if (skinBmp != null) {
                Bitmap face = new SkinHeadRenderer().render(100, skinBmp);
                skinBmp.recycle();
                if (face != null) {
                    File outFace = new File(cacheDir, "local-skin-face-"
                            + FileUtils.escapeFileName(currentAccount.username) + ".webp");
                    try (FileOutputStream out = new FileOutputStream(outFace, false)) {
                        face.compress(Bitmap.CompressFormat.WEBP, 90, out);
                    }
                }
            }
        }
    }

    public static void deleteSkin(@NonNull File libraryDir, @NonNull String skinId) throws IOException {
        SkinLibraryIndex idx = loadIndex(libraryDir);
        SkinEntry removed = null;
        for (int i = 0; i < idx.skins.size(); i++) {
            if (skinId.equals(idx.skins.get(i).id)) {
                removed = idx.skins.remove(i);
                break;
            }
        }
        if (removed != null && removed.fileName != null) {
            File safeFile = ArchiveSecurity.resolveSafeEntry(libraryDir, removed.fileName);
            if (safeFile.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                safeFile.delete();
            }
        }
        saveIndex(libraryDir, idx);
    }

    /**
     * Uploads {@code skinEntry} to the signed-in user's official Minecraft Java profile over HTTPS.
     * Refuses local/offline or unauthenticated accounts with an honest explanation.
     */
    public static void uploadToOfficialMinecraftProfile(@NonNull File libraryDir,
                                                        @NonNull SkinEntry skinEntry,
                                                        @Nullable Account account) throws IOException {
        if (account == null || account.isLocal() || !account.isMicrosoft
                || account.accessToken == null || account.accessToken.length() < 16) {
            throw new IOException("Online skin upload requires an active signed-in Microsoft Minecraft account. "
                    + "For offline/local accounts, skins stay in your local library (in-game offline skin replacement requires a skin mod such as CustomSkinLoader or OfflineSkins).");
        }
        File pngFile = ArchiveSecurity.resolveSafeEntry(libraryDir, skinEntry.fileName);
        if (!pngFile.isFile()) {
            throw new IOException("Skin PNG file not found in local library");
        }
        byte[] pngBytes;
        try (FileInputStream in = new FileInputStream(pngFile)) {
            pngBytes = ArchiveSecurity.readBytesBounded(in, MAX_SKIN_FILE_BYTES);
        }
        PngValidation check = validateSkinPngBytes(pngBytes);
        if (!check.valid) {
            throw new IOException(check.message);
        }

        String boundary = "----ZyroxSkinBoundary" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = (HttpURLConnection) new URL(OFFICIAL_SKIN_UPLOAD_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(25_000);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", DownloadUtils.USER_AGENT);
        // Never log the Authorization header or token!
        conn.setRequestProperty("Authorization", "Bearer " + account.accessToken);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            String variant = skinEntry.getSkinModel().apiVariant.toLowerCase(Locale.ROOT);
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"variant\"\r\n\r\n");
            out.writeBytes(variant + "\r\n");

            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
            out.writeBytes("Content-Type: image/png\r\n\r\n");
            out.write(pngBytes);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        try {
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                account.updateSkinFace();
                return;
            }
            if (code == 401 || code == 403) {
                throw new IOException("Minecraft session expired or unauthorized (HTTP " + code
                        + "). Re-select your Microsoft account to refresh login and try again.");
            }
            throw new IOException("Minecraft profile API returned HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static SkinLibraryIndex loadIndex(@NonNull File libraryDir) {
        File f = new File(libraryDir, INDEX_FILE);
        if (!f.isFile()) return new SkinLibraryIndex();
        try {
            SkinLibraryIndex idx = JSONUtils.readFromFile(f, SkinLibraryIndex.class);
            if (idx == null || idx.skins == null) return new SkinLibraryIndex();
            return idx;
        } catch (Exception e) {
            return new SkinLibraryIndex();
        }
    }

    private static void saveIndex(@NonNull File libraryDir, @NonNull SkinLibraryIndex idx) throws IOException {
        FileUtils.ensureDirectory(libraryDir);
        JSONUtils.writeToFileAtomic(new File(libraryDir, INDEX_FILE), idx, true);
    }

    private static int readBigEndianInt(@NonNull byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24)
                | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8)
                | (b[offset + 3] & 0xFF);
    }
}
