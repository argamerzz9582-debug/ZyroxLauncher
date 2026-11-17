package git.artdeell.mojo.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.StatFs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Network connectivity policy, bounded retry backoff with jitter, and filesystem disk-space checks.
 */
public final class NetworkPolicyHelper {
    public static final long DEFAULT_LARGE_MOBILE_DOWNLOAD_BYTES = 50L * 1024L * 1024L; // 50 MiB
    public static final long MIN_SAFE_FREE_DISK_HEADROOM_BYTES = 64L * 1024L * 1024L;   // 64 MiB

    private static final SecureRandom JITTER_RNG = new SecureRandom();

    public enum ConnectionType {
        WIFI_OR_UNMETERED,
        METERED_MOBILE,
        OFFLINE,
        UNKNOWN
    }

    private NetworkPolicyHelper() {
    }

    @NonNull
    @SuppressWarnings("deprecation")
    public static ConnectionType detectConnection(@Nullable Context context) {
        if (context == null) return ConnectionType.UNKNOWN;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return ConnectionType.UNKNOWN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return ConnectionType.OFFLINE;
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return ConnectionType.OFFLINE;
                }
                boolean unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                if (unmetered && !cm.isActiveNetworkMetered()) {
                    return ConnectionType.WIFI_OR_UNMETERED;
                }
                return cm.isActiveNetworkMetered()
                        ? ConnectionType.METERED_MOBILE
                        : ConnectionType.WIFI_OR_UNMETERED;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return ConnectionType.OFFLINE;
            return cm.isActiveNetworkMetered()
                    ? ConnectionType.METERED_MOBILE
                    : ConnectionType.WIFI_OR_UNMETERED;
        } catch (Throwable ignored) {
            return ConnectionType.UNKNOWN;
        }
    }

    public static boolean shouldWarnForMobileDownload(@Nullable Context context,
                                                      long estimatedBytes,
                                                      boolean warnSettingEnabled) {
        if (!warnSettingEnabled) return false;
        if (estimatedBytes > 0 && estimatedBytes < DEFAULT_LARGE_MOBILE_DOWNLOAD_BYTES) return false;
        return detectConnection(context) == ConnectionType.METERED_MOBILE;
    }

    /**
     * Computes bounded exponential backoff in milliseconds with randomized jitter so concurrent
     * worker retries never stampede an endpoint.
     */
    public static long computeBackoffDelayMs(int attemptIndex, long baseDelayMs, long maxDelayMs) {
        int clampedAttempt = Math.max(0, Math.min(attemptIndex, 10));
        long safeBase = Math.max(50L, baseDelayMs);
        long safeMax = Math.max(safeBase, maxDelayMs);
        long exp = safeBase * (1L << clampedAttempt);
        long capped = Math.min(safeMax, exp > 0 ? exp : safeMax);
        long jitterRange = Math.max(1L, capped / 3L);
        long jitter = Math.abs(JITTER_RNG.nextLong()) % jitterRange;
        return Math.min(safeMax, (capped - jitterRange / 2L) + jitter);
    }

    /**
     * Sleeps for a bounded retry backoff, throwing {@link InterruptedException} immediately on cancel.
     */
    public static void sleepBackoff(int attemptIndex) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        long delay = computeBackoffDelayMs(attemptIndex, 250L, 6000L);
        Thread.sleep(delay);
    }

    /**
     * Returns usable free disk bytes on the filesystem containing {@code path}, or {@code -1} if unknown.
     */
    public static long queryFreeDiskBytes(@Nullable File path) {
        if (path == null) return -1L;
        File probe = path;
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        if (probe == null) return -1L;
        try {
            long usable = probe.getUsableSpace();
            if (usable > 0L) return usable;
            StatFs statFs = new StatFs(probe.getAbsolutePath());
            return statFs.getAvailableBytes();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * Returns true if sufficient free space (plus safe OS headroom) is available for {@code requiredBytes}.
     */
    public static boolean hasEnoughDiskSpace(@Nullable File targetDir, long requiredBytes) {
        if (requiredBytes <= 0L) return true;
        long free = queryFreeDiskBytes(targetDir);
        if (free < 0L) return true; // Unknown filesystem: do not false-block
        return free >= requiredBytes + MIN_SAFE_FREE_DISK_HEADROOM_BYTES;
    }

    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    @NonNull
    public static String formatEtaSeconds(long remainingBytes, double bytesPerSec) {
        if (remainingBytes <= 0L) return "0s";
        if (bytesPerSec < 512.0 || Double.isNaN(bytesPerSec) || Double.isInfinite(bytesPerSec)) {
            return "--";
        }
        long sec = Math.max(1L, Math.round(remainingBytes / bytesPerSec));
        if (sec < 60L) return sec + "s";
        long mins = sec / 60L;
        long remSec = sec % 60L;
        if (mins < 60L) return mins + "m " + remSec + "s";
        long hours = mins / 60L;
        long remMin = mins % 60L;
        return hours + "h " + remMin + "m";
    }
}
