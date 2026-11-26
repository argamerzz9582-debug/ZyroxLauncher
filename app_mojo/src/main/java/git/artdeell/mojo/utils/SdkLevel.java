package git.artdeell.mojo.utils;

import android.os.Build;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.VisibleForTesting;

/**
 * Single seam for API-level checks in logic that is unit-tested on the host JVM.
 *
 * <p>Host unit tests run against the mockable {@code android.jar} with {@code returnDefaultValues},
 * so {@link Build.VERSION#SDK_INT} is always {@code 0} there. Code that branches on it silently
 * exercised only the "pre-Lollipop" path in tests, and the security and driver checks guarded by
 * {@code SDK_INT >= X} never ran. Routing those checks through this class lets tests pin a real
 * API level (26, 31, 34 …) with {@link #overrideForTests(int)}.
 *
 * <p>{@link ChecksSdkIntAtLeast} keeps Android Lint's NewApi flow analysis working, so
 * {@code if (SdkLevel.atLeast(O))} is treated exactly like {@code if (SDK_INT >= O)}.
 */
public final class SdkLevel {
    private static final int NO_OVERRIDE = -1;
    private static volatile int sOverride = NO_OVERRIDE;

    private SdkLevel() {
    }

    /** Effective API level: the test override when set, otherwise {@link Build.VERSION#SDK_INT}. */
    public static int get() {
        int override = sOverride;
        return override != NO_OVERRIDE ? override : Build.VERSION.SDK_INT;
    }

    /** {@code true} when the effective API level is at least {@code api}. */
    @ChecksSdkIntAtLeast(parameter = 0)
    public static boolean atLeast(int api) {
        return get() >= api;
    }

    /** Pins the API level seen by {@link #get()}; always pair with {@link #resetForTests()}. */
    @VisibleForTesting
    public static void overrideForTests(int sdkInt) {
        if (sdkInt < 0) throw new IllegalArgumentException("sdkInt must be >= 0");
        sOverride = sdkInt;
    }

    @VisibleForTesting
    public static void resetForTests() {
        sOverride = NO_OVERRIDE;
    }
}
