package git.artdeell.mojo.contracts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import git.artdeell.mojo.Tools;

/**
 * Android 14–16 Scoped Storage (SAF) document picker contract.
 *
 * <p>Android 14–16 OEM DocumentsUI providers routinely misclassify custom/archive gaming files
 * ({@code .mrpack}, {@code .jar}, {@code .json}, {@code .zyrox}, {@code .adpkg}, {@code .tar.xz})
 * as {@code application/octet-stream}, rendering files greyed-out and unselectable when strict
 * {@code MimeTypeMap} filters are attached. This contract always launches
 * {@link Intent#ACTION_OPEN_DOCUMENT} with {@code *&#47;*} + {@link Intent#CATEGORY_OPENABLE}
 * and programmatically validates the selected URI's resolved display/path extension before
 * returning it to the caller.</p>
 */
public class OpenDocumentWithExtension extends ActivityResultContract<Object, Uri> {

    /** Universal MIME fallback ensuring Android 14–16 DocumentsUI never greys out launcher files. */
    public static final String MIME_ALL_TYPES = "*/*";

    /** Standard supported Zyrox Launcher package and configuration file extensions. */
    public static final List<String> STANDARD_LAUNCHER_EXTENSIONS =
            Collections.unmodifiableList(Arrays.asList("mrpack", "zip", "jar", "json", "zyrox"));

    private final List<String> mAllowedExtensions;
    @Nullable
    private WeakReference<Context> mLastAppContextRef;

    /**
     * Creates a SAF picker contract accepting {@code extension} (plus smart companion formats such
     * as {@code .zyrox} for {@code json} control pickers and {@code .mrpack} for {@code zip}
     * archive pickers).
     */
    public OpenDocumentWithExtension(@Nullable String extension) {
        this(expandDefaultCompanions(extension));
    }

    /**
     * Creates a SAF picker contract programmatically validating against {@code allowedExtensions}.
     */
    public OpenDocumentWithExtension(@Nullable String... allowedExtensions) {
        this.mAllowedExtensions = normalizeExtensions(allowedExtensions);
    }

    @NonNull
    public List<String> getAllowedExtensions() {
        return mAllowedExtensions;
    }

    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @Nullable Object input) {
        mLastAppContextRef = new WeakReference<>(context.getApplicationContext());
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(MIME_ALL_TYPES);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    @Nullable
    @Override
    public final SynchronousResult<Uri> getSynchronousResult(@NonNull Context context,
                                                             @Nullable Object input) {
        return null;
    }

    @Nullable
    @Override
    public final Uri parseResult(int resultCode, @Nullable Intent intent) {
        if (intent == null || resultCode != Activity.RESULT_OK) return null;
        Uri uri = intent.getData();
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) return null;
        if (mAllowedExtensions.isEmpty()) return uri;

        Context appCtx = mLastAppContextRef != null ? mLastAppContextRef.get() : null;
        String resolvedName = resolveFileName(appCtx, uri);
        if (resolvedName != null && !resolvedName.isEmpty()
                && !matchesAllowedExtensions(resolvedName, mAllowedExtensions)) {
            if (appCtx != null) {
                Toast.makeText(
                        appCtx,
                        "Unsupported file \"" + resolvedName + "\". Expected: "
                                + formatExtensionList(mAllowedExtensions),
                        Toast.LENGTH_LONG
                ).show();
            }
            return null;
        }
        return uri;
    }

    /** Resolves a display filename from ContentResolver metadata with decoded URI path fallback. */
    @Nullable
    public static String resolveFileName(@Nullable Context context, @Nullable Uri uri) {
        if (uri == null) return null;
        if (context != null) {
            try {
                String fromResolver = Tools.getFileName(context, uri);
                if (fromResolver != null && !fromResolver.trim().isEmpty()) {
                    return sanitizeName(fromResolver);
                }
            } catch (Throwable ignored) {
            }
        }
        String lastSeg = uri.getLastPathSegment();
        if (lastSeg == null || lastSeg.trim().isEmpty()) return null;
        int slash = Math.max(lastSeg.lastIndexOf('/'), lastSeg.lastIndexOf(':'));
        String tail = slash >= 0 && slash + 1 < lastSeg.length()
                ? lastSeg.substring(slash + 1) : lastSeg;
        return sanitizeName(tail);
    }

    /** Programmatically verifies {@code fileName} ends with one of the allowed extensions. */
    public static boolean hasAllowedExtension(@Nullable String fileName,
                                              @Nullable String... allowedExtensions) {
        List<String> normalized = normalizeExtensions(allowedExtensions);
        if (normalized.isEmpty()) {
            normalized = STANDARD_LAUNCHER_EXTENSIONS;
        }
        return matchesAllowedExtensions(fileName, normalized);
    }

    /** Returns {@code true} if {@code fileName} is a standard Zyrox importable package/profile. */
    public static boolean isSupportedLauncherFile(@Nullable String fileName) {
        return matchesAllowedExtensions(fileName, STANDARD_LAUNCHER_EXTENSIONS);
    }

    /** Extracts the lowercase extension (without leading dot), or {@code ""} if absent. */
    @NonNull
    public static String extractExtension(@Nullable String fileName) {
        String clean = sanitizeName(fileName);
        if (clean.isEmpty()) return "";
        int dot = clean.lastIndexOf('.');
        if (dot <= 0 || dot >= clean.length() - 1) return "";
        return clean.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAllowedExtensions(@Nullable String fileName,
                                                    @NonNull List<String> allowed) {
        String clean = sanitizeName(fileName).toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return false;
        for (String ext : allowed) {
            if (clean.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String[] expandDefaultCompanions(@Nullable String ext) {
        String clean = cleanSingleExtension(ext);
        if (clean.isEmpty()) return new String[0];
        switch (clean) {
            case "json":
                return new String[]{"json", "zyrox"};
            case "zyrox":
                return new String[]{"zyrox", "json"};
            case "mrpack":
                return new String[]{"mrpack", "zip"};
            case "zip":
                return new String[]{"zip", "mrpack", "jar"};
            case "adpkg":
                return new String[]{"adpkg", "zip"};
            case "xz":
                return new String[]{"xz", "tar.xz", "txz"};
            default:
                return new String[]{clean};
        }
    }

    @NonNull
    private static List<String> normalizeExtensions(@Nullable String... raw) {
        if (raw == null || raw.length == 0) return Collections.emptyList();
        Set<String> unique = new LinkedHashSet<>();
        for (String entry : raw) {
            String clean = cleanSingleExtension(entry);
            if (!clean.isEmpty()) {
                unique.add(clean);
            }
        }
        if (unique.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    @NonNull
    private static String cleanSingleExtension(@Nullable String ext) {
        if (ext == null) return "";
        String s = ext.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("*")) s = s.substring(1);
        while (s.startsWith(".")) s = s.substring(1);
        return "*".equals(s) || "/*".equals(s) ? "" : s;
    }

    @NonNull
    private static String sanitizeName(@Nullable String name) {
        if (name == null) return "";
        return name.replace("\0", "").trim();
    }

    @NonNull
    public static String formatExtensionList(@NonNull List<String> extensions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < extensions.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('.').append(extensions.get(i));
        }
        return sb.toString();
    }
}
