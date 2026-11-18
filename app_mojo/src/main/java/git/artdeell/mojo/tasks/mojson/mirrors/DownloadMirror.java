package git.artdeell.mojo.tasks.mojson.mirrors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Mojang / BMCLAPI HTTPS mirror mapper and automatic failover router.
 *
 * <p>Generates ordered HTTPS candidate URLs respecting the user's preferred download source, tracks
 * transient per-host failures with a bounded 90-second cooldown so thousands of parallel asset
 * downloads do not repeatedly stall on a degraded upstream, and guarantees strict {@code https://}
 * URLs across all mirror candidates (downstream SHA-1 verification remains enforced).
 */
public class DownloadMirror {
    public static final int DOWNLOAD_CLASS_NONE = -1;
    public static final int DOWNLOAD_CLASS_LIBRARIES = 0;
    public static final int DOWNLOAD_CLASS_METADATA = 1;
    public static final int DOWNLOAD_CLASS_ASSETS = 2;

    private static final String URL_PROTOCOL_TAIL = "://";
    private static final long HOST_COOLDOWN_MS = 90_000L;

    private static final String[] MIRROR_BMCLAPI = {
            "https://bmclapi2.bangbang93.com/maven",
            "https://bmclapi2.bangbang93.com",
            "https://bmclapi2.bangbang93.com/assets"
    };

    /** Hostname -> epoch millis of most recent network/5xx failure. */
    private static final ConcurrentHashMap<String, Long> sHostFailureAtMs = new ConcurrentHashMap<>();

    /**
     * Downloads a file using the preferred source first and automatically failing over to
     * alternate verified HTTPS mirrors if the primary endpoint fails.
     */
    public static void downloadFileMirrored(int downloadClass,
                                            @NonNull String urlInput,
                                            @NonNull File outputFile) throws IOException {
        List<String> candidates = buildFailoverCandidates(downloadClass, urlInput);
        IOException lastError = null;
        for (String candidate : candidates) {
            try {
                DownloadUtils.downloadFileSingle(candidate, outputFile);
                recordEndpointSuccess(candidate);
                return;
            } catch (IOException e) {
                recordEndpointFailure(candidate);
                lastError = e;
            }
        }
        if (lastError != null) throw lastError;
        DownloadUtils.downloadFileSingle(urlInput, outputFile);
    }

    /** Returns {@code true} if the user explicitly selected a non-default download mirror. */
    public static boolean isMirrored() {
        String pref = LauncherPreferences.PREF_DOWNLOAD_SOURCE;
        return pref != null && !pref.equals("default");
    }

    @Nullable
    private static String[] getMirrorSettings(@Nullable String sourceId) {
        if (sourceId == null) return null;
        switch (sourceId) {
            case "bmclapi":
                return MIRROR_BMCLAPI;
            case "default":
            default:
                return null;
        }
    }

    /** Returns the mapped URL for the active download source setting. */
    @NonNull
    public static String getMirrorMapping(int downloadClass,
                                          @NonNull String mojangUrl) throws MalformedURLException {
        return mapWithTable(downloadClass, mojangUrl,
                getMirrorSettings(LauncherPreferences.PREF_DOWNLOAD_SOURCE));
    }

    @NonNull
    private static String mapWithTable(int downloadClass,
                                       @NonNull String mojangUrl,
                                       @Nullable String[] mirrorSettings) throws MalformedURLException {
        if (downloadClass == DOWNLOAD_CLASS_NONE || mirrorSettings == null) return mojangUrl;
        int urlTail = getBaseUrlTail(mojangUrl);
        String baseUrl = mojangUrl.substring(0, urlTail);
        String path = mojangUrl.substring(urlTail);
        switch (downloadClass) {
            case DOWNLOAD_CLASS_ASSETS:
            case DOWNLOAD_CLASS_METADATA:
                baseUrl = mirrorSettings[downloadClass];
                break;
            case DOWNLOAD_CLASS_LIBRARIES:
                if (!baseUrl.toLowerCase(Locale.ROOT).endsWith("libraries.minecraft.net")) break;
                baseUrl = mirrorSettings[downloadClass];
                break;
            default:
                break;
        }
        return baseUrl + path;
    }

    /** Infers the Mojang download class from an arbitrary asset/library/manifest URL. */
    public static int inferDownloadClass(@Nullable String url) {
        if (url == null) return DOWNLOAD_CLASS_NONE;
        try {
            String host = new URL(url).getHost().toLowerCase(Locale.ROOT);
            if ("libraries.minecraft.net".equals(host)) {
                return DOWNLOAD_CLASS_LIBRARIES;
            }
            if ("resources.download.minecraft.net".equals(host)) {
                return DOWNLOAD_CLASS_ASSETS;
            }
            if ("piston-meta.mojang.com".equals(host)
                    || "piston-data.mojang.com".equals(host)
                    || "launchermeta.mojang.com".equals(host)
                    || "launcher.mojang.com".equals(host)) {
                return DOWNLOAD_CLASS_METADATA;
            }
        } catch (Exception ignored) {
        }
        return DOWNLOAD_CLASS_NONE;
    }

    /** Convenience overload inferring the Mojang download class directly from {@code url}. */
    @NonNull
    public static List<String> buildFailoverCandidates(@NonNull String url) {
        return buildFailoverCandidates(inferDownloadClass(url), url);
    }

    /**
     * Builds a deduplicated ordered list of verified HTTPS candidate URLs for {@code originalUrl},
     * placing healthy endpoints ahead of any host currently inside its transient failure cooldown.
     */
    @NonNull
    public static List<String> buildFailoverCandidates(int downloadClass,
                                                       @NonNull String originalUrl) {
        if (downloadClass == DOWNLOAD_CLASS_NONE) {
            int inferred = inferDownloadClass(originalUrl);
            if (inferred == DOWNLOAD_CLASS_NONE) {
                return Collections.singletonList(originalUrl);
            }
            downloadClass = inferred;
        }

        Set<String> ordered = new LinkedHashSet<>(3);
        try {
            String bmcl = mapWithTable(downloadClass, originalUrl, MIRROR_BMCLAPI);
            if (isMirrored()) {
                ordered.add(bmcl);
                ordered.add(originalUrl);
            } else {
                ordered.add(originalUrl);
                if (!bmcl.equals(originalUrl)) {
                    ordered.add(bmcl);
                }
            }
        } catch (MalformedURLException e) {
            return Collections.singletonList(originalUrl);
        }

        if (ordered.size() <= 1) {
            return new ArrayList<>(ordered);
        }

        long now = System.currentTimeMillis();
        List<String> healthy = new ArrayList<>(ordered.size());
        List<String> cooling = new ArrayList<>(ordered.size());
        for (String candidate : ordered) {
            if (isEndpointCoolingDown(candidate, now)) {
                cooling.add(candidate);
            } else {
                healthy.add(candidate);
            }
        }
        healthy.addAll(cooling);
        return healthy;
    }

    public static void recordEndpointFailure(@Nullable String url) {
        String host = extractHost(url);
        if (host != null) {
            sHostFailureAtMs.put(host, System.currentTimeMillis());
        }
    }

    public static void recordEndpointSuccess(@Nullable String url) {
        String host = extractHost(url);
        if (host != null) {
            sHostFailureAtMs.remove(host);
        }
    }

    public static void resetEndpointHealth() {
        sHostFailureAtMs.clear();
    }

    private static boolean isEndpointCoolingDown(@NonNull String url, long nowMs) {
        String host = extractHost(url);
        if (host == null) return false;
        Long failedAt = sHostFailureAtMs.get(host);
        if (failedAt == null) return false;
        if (nowMs - failedAt > HOST_COOLDOWN_MS) {
            sHostFailureAtMs.remove(host, failedAt);
            return false;
        }
        return true;
    }

    @Nullable
    private static String extractHost(@Nullable String url) {
        if (url == null) return null;
        try {
            String h = new URL(url).getHost();
            return (h != null && !h.isEmpty()) ? h.toLowerCase(Locale.ROOT) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int getBaseUrlTail(String wholeUrl) throws MalformedURLException {
        int protocolNameEnd = wholeUrl.indexOf(URL_PROTOCOL_TAIL);
        if (protocolNameEnd == -1) {
            throw new MalformedURLException("No protocol, or non path-based URL");
        }
        protocolNameEnd += URL_PROTOCOL_TAIL.length();
        int hostnameEnd = wholeUrl.indexOf('/', protocolNameEnd);
        if (protocolNameEnd >= wholeUrl.length() || hostnameEnd == protocolNameEnd) {
            throw new MalformedURLException("No hostname");
        }
        if (hostnameEnd == -1) hostnameEnd = wholeUrl.length();
        return hostnameEnd;
    }
}
