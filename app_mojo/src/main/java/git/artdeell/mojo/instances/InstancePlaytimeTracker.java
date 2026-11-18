package git.artdeell.mojo.instances;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

import git.artdeell.mojo.game.GameLaunchProgressTracker;
import git.artdeell.mojo.performance.GameOptimizer;

/**
 * Lightweight per-instance playtime, session count, last-played timestamp, and learned launch-ETA
 * tracker backed by private {@link SharedPreferences}.
 */
public final class InstancePlaytimeTracker {

    private static final String PREF_NAME = "zyrox_instance_stats_v1";
    private static final String KEY_TOTAL_MS = "play_ms_";
    private static final String KEY_LAST_PLAYED = "last_at_";
    private static final String KEY_SESSIONS = "sessions_";
    private static final String KEY_BOOT_MS = "boot_ms_";

    private InstancePlaytimeTracker() {}

    @NonNull
    private static String safeKey(@Nullable DisplayInstance instance) {
        if (instance == null) return "default";
        String id = (instance.mInstanceRoot != null && !instance.mInstanceRoot.getName().isEmpty())
                ? instance.mInstanceRoot.getName()
                : (instance.name != null ? instance.name : "default");
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static void recordSessionStart(@NonNull Context ctx, @Nullable Instance instance, long nowEpochMs) {
        if (instance == null) return;
        String k = safeKey(instance);
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int sessions = sp.getInt(KEY_SESSIONS + k, 0);
        sp.edit()
                .putLong(KEY_LAST_PLAYED + k, nowEpochMs)
                .putInt(KEY_SESSIONS + k, sessions + 1)
                .apply();
    }

    public static void recordSessionElapsed(@NonNull Context ctx, @Nullable Instance instance, long deltaMs) {
        if (instance == null || deltaMs < 1_000L || deltaMs > 24L * 3600_000L) return;
        String k = safeKey(instance);
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long prev = Math.max(0L, sp.getLong(KEY_TOTAL_MS + k, 0L));
        sp.edit()
                .putLong(KEY_TOTAL_MS + k, prev + deltaMs)
                .putLong(KEY_LAST_PLAYED + k, System.currentTimeMillis())
                .apply();
    }

    public static long getTotalPlaytimeMs(@NonNull Context ctx, @Nullable DisplayInstance instance) {
        if (instance == null) return 0L;
        return Math.max(0L, ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_TOTAL_MS + safeKey(instance), 0L));
    }

    public static long getLastPlayedEpochMs(@NonNull Context ctx, @Nullable Instance instance) {
        if (instance == null) return 0L;
        return Math.max(0L, ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_PLAYED + safeKey(instance), 0L));
    }

    public static long getLearnedBootMs(@NonNull Context ctx, @Nullable Instance instance) {
        if (instance == null) return 0L;
        return Math.max(0L, ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_BOOT_MS + safeKey(instance), 0L));
    }

    public static void recordObservedBootMs(@NonNull Context ctx, @Nullable Instance instance, long observedMs) {
        if (instance == null) return;
        String k = safeKey(instance);
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long prev = sp.getLong(KEY_BOOT_MS + k, 0L);
        long blended = GameLaunchProgressTracker.blendLearnedBootMs(prev, observedMs);
        if (blended > 0L) {
            sp.edit().putLong(KEY_BOOT_MS + k, blended).apply();
        }
    }

    /** Formats duration like {@code 42m} or {@code 12h 15m}. */
    @NonNull
    public static String formatPlaytime(long totalMs) {
        if (totalMs < 60_000L) return "";
        long totalMinutes = totalMs / 60_000L;
        long hours = totalMinutes / 60L;
        long mins = totalMinutes % 60L;
        if (hours <= 0L) {
            return mins + "m";
        }
        if (mins == 0L) {
            return hours + "h";
        }
        return String.format(Locale.ROOT, "%dh %dm", hours, mins);
    }

    /** Builds a compact instance subtitle such as {@code Fabric · 12 mods · 3h 20m}. */
    @NonNull
    public static String buildCompactBadgeLine(@NonNull Context ctx, @Nullable DisplayInstance instance) {
        if (instance == null) return "";
        StringBuilder sb = new StringBuilder();
        String ver = instance.versionId != null ? instance.versionId : "";
        String loader = detectLoaderLabel(ver);
        if (!loader.isEmpty()) {
            sb.append(loader);
        }
        try {
            File gameDir = null;
            if (instance instanceof Instance) {
                gameDir = ((Instance) instance).getGameDirectory();
            } else if (instance.mInstanceRoot != null) {
                gameDir = instance.mInstanceRoot;
            }
            int mods = GameOptimizer.countInstalledMods(gameDir);
            if (mods > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(mods).append(mods == 1 ? " mod" : " mods");
            }
        } catch (Throwable ignored) {
        }
        String played = formatPlaytime(getTotalPlaytimeMs(ctx, instance));
        if (!played.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(played);
        }
        return sb.toString();
    }

    @NonNull
    public static String detectLoaderLabel(@Nullable String versionId) {
        if (versionId == null || versionId.isEmpty()) return "";
        String v = versionId.toLowerCase(Locale.ROOT);
        if (v.contains("fabric")) return "Fabric";
        if (v.contains("quilt")) return "Quilt";
        if (v.contains("neoforge")) return "NeoForge";
        if (v.contains("forge")) return "Forge";
        if (v.contains("optifine")) return "OptiFine";
        return "";
    }
}
