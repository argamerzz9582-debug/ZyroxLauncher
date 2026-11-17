package git.artdeell.mojo.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Inspects real runtime APK identity, signing certificate SHA-256 fingerprint, build flags, and
 * installation source for user-visible authenticity transparency.
 *
 * <p>Never bypasses, suppresses, or misrepresents Google Play Protect or Android verification.
 */
public final class AppTrustVerifier {
    public static final String OFFICIAL_REPO_URL = "https://github.com/argamerzz9582-debug/ZyroxLauncher";
    public static final String OFFICIAL_RELEASES_URL = OFFICIAL_REPO_URL + "/releases";
    public static final String OFFICIAL_SECURITY_URL = OFFICIAL_REPO_URL + "/blob/v3_openjdk/SECURITY.md";
    public static final String OFFICIAL_PRIVACY_URL = OFFICIAL_REPO_URL + "/blob/v3_openjdk/PRIVACY_POLICY.md";

    private AppTrustVerifier() {
    }

    public static final class TrustReport {
        @NonNull public final String packageName;
        @NonNull public final String versionName;
        public final long versionCode;
        public final int targetSdk;
        public final int minSdk;
        public final boolean debuggable;
        @NonNull public final String certSha256Colon;
        @NonNull public final String certSha256Hex;
        @NonNull public final String installerSource;
        public final boolean multipleSigners;

        public TrustReport(@NonNull String packageName,
                           @NonNull String versionName,
                           long versionCode,
                           int targetSdk,
                           int minSdk,
                           boolean debuggable,
                           @NonNull String certSha256Colon,
                           @NonNull String certSha256Hex,
                           @NonNull String installerSource,
                           boolean multipleSigners) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.targetSdk = targetSdk;
            this.minSdk = minSdk;
            this.debuggable = debuggable;
            this.certSha256Colon = certSha256Colon;
            this.certSha256Hex = certSha256Hex;
            this.installerSource = installerSource;
            this.multipleSigners = multipleSigners;
        }

        @NonNull
        public String buildCopyableSummary() {
            return "Zyrox Launcher Authenticity & Build Summary\n"
                    + "Package: " + packageName + "\n"
                    + "Version: " + versionName + " (" + versionCode + ")\n"
                    + "Build Mode: " + (debuggable ? "Debug / Development" : "Production Release (non-debuggable)") + "\n"
                    + "Target / Min SDK: API " + targetSdk + " / API " + minSdk + "\n"
                    + "Install Source: " + installerSource + "\n"
                    + "Signing Cert SHA-256: " + certSha256Colon + "\n"
                    + "Signing Cert SHA-256 (hex): " + certSha256Hex + "\n"
                    + "Official Releases: " + OFFICIAL_RELEASES_URL;
        }
    }

    @NonNull
    public static TrustReport inspect(@NonNull Context context) {
        String pkg = context.getPackageName();
        PackageManager pm = context.getPackageManager();
        String versionName = "unknown";
        long versionCode = 0L;
        int targetSdk = 0;
        int minSdk = 21;
        boolean debuggable = false;
        String certColon = "Unavailable";
        String certHex = "unavailable";
        boolean multi = false;

        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageInfo(pkg, flags);
            if (info.versionName != null) versionName = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            if (info.applicationInfo != null) {
                targetSdk = info.applicationInfo.targetSdkVersion;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    minSdk = info.applicationInfo.minSdkVersion;
                }
                debuggable = (info.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            }
            Signature[] sigs = extractSignatures(info);
            if (sigs != null && sigs.length > 0 && sigs[0] != null) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
                certColon = formatSha256Colon(digest);
                certHex = formatSha256Hex(digest);
                multi = sigs.length > 1;
            }
        } catch (Throwable ignored) {
        }

        String installer = describeInstaller(pm, pkg);
        return new TrustReport(pkg, versionName, versionCode, targetSdk, minSdk,
                debuggable, certColon, certHex, installer, multi);
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Signature[] extractSignatures(@NonNull PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) return null;
            if (signingInfo.hasMultipleSigners()) {
                return signingInfo.getApkContentsSigners();
            }
            return signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private static String describeInstaller(@NonNull PackageManager pm, @NonNull String pkg) {
        String raw = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo src = pm.getInstallSourceInfo(pkg);
                raw = src.getInstallingPackageName();
                if (raw == null || raw.isEmpty()) {
                    raw = src.getInitiatingPackageName();
                }
            } else {
                raw = pm.getInstallerPackageName(pkg);
            }
        } catch (Throwable ignored) {
        }
        if (raw == null || raw.isEmpty()) {
            return "Direct APK install (sideloaded / ADB)";
        }
        if ("com.android.vending".equals(raw)) {
            return "Google Play Store (com.android.vending)";
        }
        if (raw.contains("packageinstaller")) {
            return "Android Package Installer (direct APK)";
        }
        return raw;
    }

    @NonNull
    public static String formatSha256Colon(@NonNull byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02X", digest[i] & 0xFF));
        }
        return sb.toString();
    }

    @NonNull
    public static String formatSha256Hex(@NonNull byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
