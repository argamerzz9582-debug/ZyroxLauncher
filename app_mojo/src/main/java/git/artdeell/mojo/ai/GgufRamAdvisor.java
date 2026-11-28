package git.artdeell.mojo.ai;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Hardware RAM diagnostics and local {@code .gguf} quantization advisor.
 *
 * <p>Inspects total and currently available device RAM via {@link ActivityManager.MemoryInfo}
 * and maps the device into one of four guidance tiers so users never accidentally load an
 * oversized model that triggers Android's Low-Memory Killer alongside Minecraft's JVM heap.</p>
 */
public final class GgufRamAdvisor {

    public static final long BYTES_PER_MB = 1024L * 1024L;
    public static final long BYTES_PER_GB = 1024L * BYTES_PER_MB;

    /** Estimated KV-cache + compute scratch overhead added on top of raw GGUF file size. */
    private static final long RUNTIME_SCRATCH_OVERHEAD_BYTES = 192L * BYTES_PER_MB;

    private GgufRamAdvisor() {
    }

    /** Device RAM tier classification aligned with quantized GGUF parameter targets. */
    public enum RamTier {
        ULTRA_LIGHT_4GB(
                "<= 4 GB RAM · Ultra-Light Tier",
                "0.5B – 1.5B (Q4_K_M)",
                "qwen2.5-0.5b-q4_k_m",
                "Keep models under ~900 MB and keep Auto-Unload on Game Launch enabled so Minecraft has full heap headroom."
        ),
        BALANCED_6GB(
                "6 GB RAM · Balanced Tier",
                "1.5B – 3B (Q4_K_M)",
                "qwen2.5-1.5b-q4_k_m",
                "1.5B–3B Q4 models run smoothly in the launcher; unload before launching heavy modpacks."
        ),
        CAPABLE_8GB(
                "8 GB RAM · Capable Tier",
                "3B – 4B (Q4_K_M) · 7B with caution",
                "llama3.2-3b-q4_k_m",
                "3B Q4 is ideal. 7B Q4 (~4.7 GB) fits only when background apps are closed — enable Auto-Unload before launching Minecraft."
        ),
        FLAGSHIP_12GB_PLUS(
                "12 GB+ RAM · Flagship Tier",
                "Up to 7B – 8B (Q4_K_M)",
                "qwen2.5-7b-q4_k_m",
                "High-capacity device capable of 7B–8B Q4 models. Auto-Unload is still advised when allocating >4 GB JVM RAM to Minecraft."
        );

        public final String title;
        public final String recommendedRange;
        public final String recommendedCatalogId;
        public final String guidanceText;

        RamTier(@NonNull String title,
                @NonNull String recommendedRange,
                @NonNull String recommendedCatalogId,
                @NonNull String guidanceText) {
            this.title = title;
            this.recommendedRange = recommendedRange;
            this.recommendedCatalogId = recommendedCatalogId;
            this.guidanceText = guidanceText;
        }
    }

    /** Safety verdict for loading a specific {@code .gguf} file right now. */
    public enum FitSafety {
        SAFE,
        CAUTION,
        CRITICAL_OOM_RISK
    }

    /** Snapshot of device RAM metrics and recommended GGUF model tier. */
    public static final class RamReport {
        public final long totalRamBytes;
        public final long availableRamBytes;
        public final boolean systemLowMemory;
        @NonNull public final RamTier tier;
        @NonNull public final String summaryLine;
        @NonNull public final String warningBanner;

        public RamReport(long totalRamBytes,
                         long availableRamBytes,
                         boolean systemLowMemory,
                         @NonNull RamTier tier,
                         @NonNull String summaryLine,
                         @NonNull String warningBanner) {
            this.totalRamBytes = totalRamBytes;
            this.availableRamBytes = availableRamBytes;
            this.systemLowMemory = systemLowMemory;
            this.tier = tier;
            this.summaryLine = summaryLine;
            this.warningBanner = warningBanner;
        }
    }

    /** Per-model memory safety assessment before loading into native/JVM memory. */
    public static final class ModelFitAssessment {
        @NonNull public final FitSafety safety;
        public final long modelFileBytes;
        public final long estimatedRuntimeBytes;
        @NonNull public final String message;

        public ModelFitAssessment(@NonNull FitSafety safety,
                                  long modelFileBytes,
                                  long estimatedRuntimeBytes,
                                  @NonNull String message) {
            this.safety = safety;
            this.modelFileBytes = modelFileBytes;
            this.estimatedRuntimeBytes = estimatedRuntimeBytes;
            this.message = message;
        }
    }

    /** Inspects live {@link ActivityManager.MemoryInfo} on Android. */
    @NonNull
    public static RamReport inspectDevice(@Nullable Context context) {
        long total = 4L * BYTES_PER_GB;
        long avail = 1536L * BYTES_PER_MB;
        boolean low = false;
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    if (mi.totalMem > 0L) total = mi.totalMem;
                    if (mi.availMem > 0L) avail = mi.availMem;
                    low = mi.lowMemory;
                }
            } catch (Throwable ignored) {
            }
        }
        return evaluateMemory(total, avail, low);
    }

    /**
     * Pure deterministic evaluator (also exercised directly in host-JVM JUnit tests).
     * Note: Android devices marketed as "4 GB", "6 GB", "8 GB", or "12 GB" report slightly lower
     * kernel-usable {@code totalMem} (e.g. ~3.65 GB for 4 GB hardware, ~5.55 GB for 6 GB, ~7.45 GB
     * for 8 GB, ~11.2 GB for 12 GB) because carveouts reserve GPU/modem memory.
     */
    @NonNull
    public static RamReport evaluateMemory(long totalRamBytes,
                                           long availableRamBytes,
                                           boolean systemLowMemory) {
        long safeTotal = Math.max(512L * BYTES_PER_MB, totalRamBytes);
        long safeAvail = Math.max(0L, Math.min(safeTotal, availableRamBytes));
        double totalGb = safeTotal / (double) BYTES_PER_GB;

        RamTier tier;
        if (totalGb <= 4.45) {
            tier = RamTier.ULTRA_LIGHT_4GB;
        } else if (totalGb <= 6.55) {
            tier = RamTier.BALANCED_6GB;
        } else if (totalGb <= 10.60) {
            tier = RamTier.CAPABLE_8GB;
        } else {
            tier = RamTier.FLAGSHIP_12GB_PLUS;
        }

        String summary = String.format(Locale.US,
                "Available RAM: %s / %s Total  •  Recommended: %s",
                formatBytes(safeAvail), formatBytes(safeTotal), tier.recommendedRange);

        String warning;
        if (systemLowMemory || safeAvail < 900L * BYTES_PER_MB) {
            warning = "LOW AVAILABLE RAM (" + formatBytes(safeAvail) + " free): Android is near memory pressure. "
                    + "Prefer Online Free API or an ultra-light 0.5B Q4 model, and unload models before launching Minecraft.";
        } else if (safeAvail < 1800L * BYTES_PER_MB) {
            warning = "Moderate free RAM (" + formatBytes(safeAvail) + "): models above 1.5B Q4 may stall or get killed. "
                    + tier.guidanceText;
        } else {
            warning = tier.guidanceText;
        }

        return new RamReport(safeTotal, safeAvail, systemLowMemory, tier, summary, warning);
    }

    /** Estimates peak RAM needed to load a {@code .gguf} file and checks live available headroom. */
    @NonNull
    public static ModelFitAssessment assessModelFit(long modelFileBytes,
                                                    @NonNull RamReport report) {
        long safeModelBytes = Math.max(0L, modelFileBytes);
        long estimatedWorkingSet = Math.round(safeModelBytes * 1.22d) + RUNTIME_SCRATCH_OVERHEAD_BYTES;

        if (report.systemLowMemory || estimatedWorkingSet >= report.availableRamBytes) {
            return new ModelFitAssessment(
                    FitSafety.CRITICAL_OOM_RISK,
                    safeModelBytes,
                    estimatedWorkingSet,
                    "High OOM risk: needs ~" + formatBytes(estimatedWorkingSet)
                            + " RAM, but only " + formatBytes(report.availableRamBytes)
                            + " is currently free. Close background apps or choose a smaller Q4 GGUF."
            );
        }

        // Warn if loading leaves < 750 MB free for Android OS + Zyrox UI
        long remainingAfterLoad = report.availableRamBytes - estimatedWorkingSet;
        if (remainingAfterLoad < 750L * BYTES_PER_MB || estimatedWorkingSet > (report.totalRamBytes * 55L / 100L)) {
            return new ModelFitAssessment(
                    FitSafety.CAUTION,
                    safeModelBytes,
                    estimatedWorkingSet,
                    "Caution: needs ~" + formatBytes(estimatedWorkingSet)
                            + " RAM (leaves ~" + formatBytes(remainingAfterLoad)
                            + " free). Keep Auto-Unload on Game Launch enabled!"
            );
        }

        return new ModelFitAssessment(
                FitSafety.SAFE,
                safeModelBytes,
                estimatedWorkingSet,
                "Fits comfortably (~" + formatBytes(estimatedWorkingSet)
                        + " est. working set · " + formatBytes(remainingAfterLoad) + " headroom left)."
        );
    }

    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        if (bytes >= BYTES_PER_GB) {
            return String.format(Locale.US, "%.2f GB", bytes / (double) BYTES_PER_GB);
        }
        return String.format(Locale.US, "%d MB", Math.max(1L, bytes / BYTES_PER_MB));
    }
}
