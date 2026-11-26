package git.artdeell.mojo.ui;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;

import java.util.Locale;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.SdkLevel;

/**
 * Catalog and playback policy for the bundled Minecraft-theme backdrops.
 *
 * <p>Every clip is a 1080p30 H.264 seamless loop (the tail crossfades into frame 0) with a
 * first-frame WebP poster, built by {@code tools/backdrops/build_backdrops.sh}. Sources and
 * licenses are listed in {@code docs/BACKDROP_CREDITS.md}.
 *
 * <p>The policy helpers are pure functions, so they are unit-tested on the host JVM without
 * Android framework stubs.
 */
public final class ZyroxBackdrops {

    /** Preference keys (stored in the default shared preferences). */
    public static final String PREF_HOME = "zyroxHomeBackdrop";
    public static final String PREF_LOADING = "zyroxLoadingBackdrop";
    public static final String PREF_DIM = "zyroxBackdropDim";
    public static final String PREF_POWER_AWARE = "zyroxBackdropPowerAware";

    /** Default scrim strength (percent) — keeps white UI text legible over bright snow scenes. */
    public static final int DEFAULT_DIM_PERCENT = 25;
    /** Upper bound so the backdrop never turns into a flat black screen. */
    public static final int MAX_DIM_PERCENT = 70;

    /** {@code PowerManager.THERMAL_STATUS_SEVERE}; from here on the OS is actively throttling. */
    static final int THERMAL_STATUS_SEVERE = 3;

    private ZyroxBackdrops() {
    }

    /** Bundled backdrops. Ids are persisted in preferences, so they must stay stable. */
    public enum Backdrop {
        /** Pixabay #168653 "Game, Play, Snow" by Qkild — calm snowy Minecraft peaks. */
        SNOWY_PEAKS("snow", R.raw.zyrox_backdrop_snow, R.drawable.bg_zyrox_backdrop_snow,
                R.string.zyrox_backdrop_snow),
        /** Pixabay #99299 "Sword, Diamond" by harold14102009 — dark enchanted diamond sword & ore. */
        DIAMOND_FORGE("diamond", R.raw.zyrox_backdrop_diamond, R.drawable.bg_zyrox_backdrop_diamond,
                R.string.zyrox_backdrop_diamond);

        @NonNull public final String id;
        @RawRes public final int videoRes;
        @DrawableRes public final int posterRes;
        @StringRes public final int titleRes;

        Backdrop(@NonNull String id, @RawRes int videoRes, @DrawableRes int posterRes,
                 @StringRes int titleRes) {
            this.id = id;
            this.videoRes = videoRes;
            this.posterRes = posterRes;
            this.titleRes = titleRes;
        }
    }

    public static final Backdrop DEFAULT_HOME = Backdrop.SNOWY_PEAKS;
    public static final Backdrop DEFAULT_LOADING = Backdrop.DIAMOND_FORGE;

    /** Resolves a persisted id (case/whitespace tolerant); unknown or removed ids fall back. */
    @NonNull
    public static Backdrop fromId(@Nullable String id, @NonNull Backdrop fallback) {
        if (id == null) return fallback;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (Backdrop b : Backdrop.values()) {
            if (b.id.equals(norm)) return b;
        }
        return fallback;
    }

    /**
     * Whether the backdrop should animate. Animation stops (holding the poster frame) when the
     * user disabled it, asked for reduced motion, or, with power-aware mode on, when Battery Saver
     * is active or the device is thermally throttling. Decoding 1080p video in those states costs
     * battery and frame time that the game needs.
     */
    public static boolean shouldAnimate(boolean animatedPref,
                                        boolean reducedMotion,
                                        boolean powerAwarePref,
                                        boolean powerSaveMode,
                                        int thermalStatus) {
        if (!animatedPref || reducedMotion) return false;
        if (!powerAwarePref) return true;
        return !powerSaveMode && thermalStatus < THERMAL_STATUS_SEVERE;
    }

    /** Clamps a dim percentage and converts it to an 8-bit scrim alpha. */
    public static int dimPercentToAlpha(int percent) {
        int p = Math.max(0, Math.min(MAX_DIM_PERCENT, percent));
        return Math.round(p * 255f / 100f);
    }

    /** Scrim colour (black at the computed alpha) for the given dim percentage. */
    public static int dimPercentToScrimColor(int percent) {
        return dimPercentToAlpha(percent) << 24;
    }

    /** Reads Battery Saver from the platform; {@code false} when unavailable. */
    public static boolean isPowerSaveMode(@Nullable Context context) {
        if (context == null) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Current thermal status (API 29+); {@code 0} (none) when unavailable. */
    public static int thermalStatus(@Nullable Context context) {
        if (context == null || !SdkLevel.atLeast(Build.VERSION_CODES.Q)) return 0;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null ? pm.getCurrentThermalStatus() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
