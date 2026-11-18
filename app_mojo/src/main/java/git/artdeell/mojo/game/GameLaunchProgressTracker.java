package git.artdeell.mojo.game;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-time, thread-safe, strictly monotonic Minecraft Java Edition launch progress &amp; ETA tracker.
 *
 * <p>Parses live JVM/renderer/modloader/Minecraft stdout &amp; stderr lines (Vanilla 1.7–1.21.x+,
 * Fabric, Quilt, Forge, NeoForge, OptiFine, Sodium, Iris, LWJGL 2/3, OpenAL, Vulkan/Zink/Turnip/LTW)
 * into smooth {@code 0..100%} progress, concise user-facing stage labels, and dynamic ETA seconds
 * calibrated with per-instance historical launch duration and mod count.
 */
public final class GameLaunchProgressTracker {

    public static final String PREF_BOOT_HISTORY = "zyrox_boot_eta_v1";
    private static final long MIN_LEARNED_BOOT_MS = 3_500L;
    private static final long MAX_LEARNED_BOOT_MS = 240_000L;

    private static final Pattern MOD_COUNT_PATTERN = Pattern.compile(
            "(?:loading|constructing|found)\\s+(\\d{1,4})\\s+mods?", Pattern.CASE_INSENSITIVE);

    /** Immutable snapshot published to the Loading Screen HUD. */
    public static final class ProgressSnapshot {
        public final int percent;
        @NonNull public final String stageText;
        @NonNull public final String etaText;
        public final int remainingSeconds;
        public final boolean completed;

        public ProgressSnapshot(int percent,
                                @NonNull String stageText,
                                @NonNull String etaText,
                                int remainingSeconds,
                                boolean completed) {
            this.percent = Math.max(0, Math.min(100, percent));
            this.stageText = stageText;
            this.etaText = etaText;
            this.remainingSeconds = remainingSeconds;
            this.completed = completed;
        }
    }

    private final long mStartMs;
    private final long mExpectedBootMs;
    private final int mInitialModCount;

    private float mCurrentPercent = 3f;
    private int mMilestoneFloor = 3;
    private int mMilestoneCeiling = 14;
    private long mLastMilestoneMs;
    private int mModEventCount = 0;
    private int mDetectedModCount = 0;
    private int mAtlasStitchCount = 0;
    private boolean mCompleted = false;
    @NonNull private String mStageText = "Preparing environment…";

    public GameLaunchProgressTracker(long startMs, int installedModCount, long learnedBootMs) {
        mStartMs = startMs;
        mLastMilestoneMs = startMs;
        mInitialModCount = Math.max(0, installedModCount);
        mDetectedModCount = mInitialModCount;
        if (learnedBootMs >= MIN_LEARNED_BOOT_MS && learnedBootMs <= MAX_LEARNED_BOOT_MS) {
            mExpectedBootMs = learnedBootMs;
        } else {
            // Heuristic baseline: ~10.5s for vanilla/light Fabric, scaling smoothly with mod count
            long modExtra = Math.min(mInitialModCount, 180) * 380L;
            mExpectedBootMs = clampLong(10_500L + modExtra, 8_000L, 120_000L);
        }
    }

    /** Computes an exponentially smoothed learned boot time for future launches of an instance. */
    public static long blendLearnedBootMs(long previousLearnedMs, long observedBootMs) {
        if (observedBootMs < MIN_LEARNED_BOOT_MS || observedBootMs > MAX_LEARNED_BOOT_MS) {
            return previousLearnedMs;
        }
        if (previousLearnedMs < MIN_LEARNED_BOOT_MS || previousLearnedMs > MAX_LEARNED_BOOT_MS) {
            return observedBootMs;
        }
        return clampLong(Math.round(previousLearnedMs * 0.35 + observedBootMs * 0.65),
                MIN_LEARNED_BOOT_MS, MAX_LEARNED_BOOT_MS);
    }

    /** Feeds one or more newline-separated log lines and returns an updated snapshot if changed. */
    @NonNull
    public synchronized ProgressSnapshot onLogOutput(@Nullable String rawText, long nowMs) {
        if (mCompleted) {
            return buildSnapshotLocked(nowMs);
        }
        if (rawText != null && !rawText.isEmpty()) {
            int start = 0;
            int len = rawText.length();
            while (start < len) {
                int nl = rawText.indexOf('\n', start);
                int end = (nl == -1) ? len : nl;
                if (end > start) {
                    processSingleLineLocked(rawText.substring(start, end), nowMs);
                }
                start = end + 1;
            }
        }
        applySmoothCreepLocked(nowMs);
        return buildSnapshotLocked(nowMs);
    }

    /** Advances gentle sub-milestone progress &amp; refreshes countdown ETA during quiet JVM phases. */
    @NonNull
    public synchronized ProgressSnapshot onTick(long nowMs) {
        if (!mCompleted) {
            applySmoothCreepLocked(nowMs);
        }
        return buildSnapshotLocked(nowMs);
    }

    /** Called when native GLFW/SDL/AWT window binds to the surface. */
    @NonNull
    public synchronized ProgressSnapshot onNativeWindowReady(long nowMs) {
        advanceToLocked(66, 78, "Display window initialized…", nowMs);
        return buildSnapshotLocked(nowMs);
    }

    /** Updates progress from discrete launcher pre-JVM stage callbacks. */
    @NonNull
    public synchronized ProgressSnapshot onLauncherStage(int floorPct,
                                                         int ceilingPct,
                                                         @NonNull String label,
                                                         long nowMs) {
        advanceToLocked(floorPct, ceilingPct, label, nowMs);
        return buildSnapshotLocked(nowMs);
    }

    /** Marks boot 100% ready right as Mojang's live splash screen takes over. */
    @NonNull
    public synchronized ProgressSnapshot markComplete(long nowMs) {
        mCompleted = true;
        mMilestoneFloor = 100;
        mMilestoneCeiling = 100;
        mCurrentPercent = 100f;
        mStageText = "Entering Minecraft…";
        return buildSnapshotLocked(nowMs);
    }

    public synchronized long getElapsedMs(long nowMs) {
        return Math.max(0L, nowMs - mStartMs);
    }

    public synchronized int getPercent() {
        return Math.max(0, Math.min(100, Math.round(mCurrentPercent)));
    }

    private void processSingleLineLocked(@NonNull String line, long nowMs) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.length() > 600) return;
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // 10. Final readiness / Title screen / Advancements / Narrator (96-99%)
        if (lower.contains("stopping worker threads")
                || lower.contains("narrator library")
                || lower.contains("titlescreen")
                || lower.contains("connecting to ")
                || (lower.contains("loaded ") && lower.contains("advancements"))) {
            advanceToLocked(96, 99, "Opening Minecraft…", nowMs);
            return;
        }

        // 9. Texture atlas stitching (84% -> 95%, increments with each stitched atlas)
        if (lower.contains("textures/atlas")
                || (lower.contains("created:") && lower.contains("atlas"))
                || lower.contains("stitching:")) {
            mAtlasStitchCount++;
            int target = Math.min(95, 83 + mAtlasStitchCount * 2);
            String atlasLabel = "Stitching game textures…";
            if (lower.contains("gui")) {
                target = Math.max(target, 94);
                atlasLabel = "Finalizing HUD & GUI textures…";
            } else if (lower.contains("blocks")) {
                atlasLabel = "Stitching block textures…";
            } else if (lower.contains("particles")) {
                atlasLabel = "Stitching particle textures…";
            }
            advanceToLocked(target, 97, atlasLabel, nowMs);
            return;
        }

        // 8. OpenAL / Sound engine (80-86%)
        if (lower.contains("openal initialized")
                || lower.contains("sound engine started")
                || lower.contains("starting up soundsystem")
                || lower.contains("initializing lwjgl openal")) {
            advanceToLocked(80, 88, "Starting audio engine…", nowMs);
            return;
        }

        // 7. Resource packs & shader pipeline (70-82%)
        if (lower.contains("reloading resourcemanager")
                || lower.contains("loading resource packs")
                || lower.contains("reloading listeners")) {
            advanceToLocked(72, 83, "Loading resource packs…", nowMs);
            return;
        }
        if (lower.contains("[iris]") || lower.contains("iris ")
                || lower.contains("shaderpack") || lower.contains("optifine_")) {
            advanceToLocked(68, 79, "Preparing shader pipeline…", nowMs);
            return;
        }

        // 6. LWJGL / OpenGL / Vulkan window creation (58-72%)
        if (lower.contains("backend library: lwjgl")
                || lower.contains("lwjgl version")
                || lower.contains("glfw")
                || lower.contains("gl_renderer")
                || lower.contains("opengl debug message")
                || lower.contains("using framebuffer")) {
            advanceToLocked(60, 72, "Initializing graphics window…", nowMs);
            return;
        }

        // 5. Player authentication & client init (50-61%)
        if (lower.contains("setting user:")
                || lower.contains("(session id is")) {
            advanceToLocked(52, 62, "Preparing player session…", nowMs);
            return;
        }

        // 4. DataFixerUpper & game registries (40-52%)
        if (lower.contains("datafixer")
                || lower.contains("environment: env[")
                || lower.contains("environment: authhost")
                || lower.contains("bootstrapping")
                || lower.contains("building unoptimized")) {
            advanceToLocked(42, 53, "Building Minecraft registries…", nowMs);
            return;
        }

        // 3. Mod discovery, Mixins & optimization mods (24-42%)
        if (lower.contains("loading ") && lower.contains(" mod")) {
            Matcher m = MOD_COUNT_PATTERN.matcher(trimmed);
            if (m.find()) {
                try {
                    int parsed = Integer.parseInt(m.group(1));
                    if (parsed > 0) mDetectedModCount = Math.max(mDetectedModCount, parsed);
                } catch (NumberFormatException ignored) {
                }
            }
            String label = mDetectedModCount > 0
                    ? ("Loading " + mDetectedModCount + " mods…")
                    : "Loading mods…";
            advanceToLocked(28, 43, label, nowMs);
            return;
        }
        if (lower.contains("spongepowered mixin")
                || lower.contains("compatibility level set to")
                || lower.contains("found mod file")
                || lower.contains("constructing ")
                || lower.contains("sodium")
                || lower.contains("embeddium")
                || lower.contains("lithium")
                || lower.contains("ferritecore")) {
            mModEventCount++;
            int modPct = Math.min(40, 26 + (mModEventCount * 2));
            String label = lower.contains("sodium") || lower.contains("embeddium")
                    ? "Activating FPS optimization mods…"
                    : "Applying mod mixins…";
            advanceToLocked(modPct, 43, label, nowMs);
            return;
        }

        // 2. ModLoader / Minecraft main class bootstrap (20-32%)
        if (lower.contains("loading minecraft ")
                || lower.contains("fabricloader")
                || lower.contains("quilt loader")
                || lower.contains("modlauncher running")
                || lower.contains("neoforge")
                || lower.contains("forge mod loader")) {
            advanceToLocked(22, 34, "Bootstrapping Minecraft loader…", nowMs);
            return;
        }

        // 1. JVM & Renderer startup (8-22%)
        if (lower.contains("zyrox optimizer")
                || lower.contains("openjdk")
                || lower.contains("java.version")
                || lower.contains("running with")) {
            advanceToLocked(16, 26, "Starting optimized Java VM…", nowMs);
            return;
        }
        if (lower.contains("vulkan")
                || lower.contains("zink")
                || lower.contains("turnip")
                || lower.contains("angle")
                || lower.contains("beginning opengl")
                || lower.contains("egl")) {
            advanceToLocked(11, 22, "Loading GPU renderer…", nowMs);
            return;
        }
        if (lower.contains("starting game")
                || lower.contains("architecture:")
                || lower.contains("launcher version")) {
            advanceToLocked(6, 16, "Configuring launch pipeline…", nowMs);
        }
    }

    private void advanceToLocked(int newFloor, int newCeiling, @NonNull String label, long nowMs) {
        if (mCompleted) return;
        int clampedFloor = Math.max(0, Math.min(99, newFloor));
        int clampedCeil = Math.max(clampedFloor, Math.min(99, newCeiling));
        if (clampedFloor >= mMilestoneFloor) {
            if (clampedFloor > mMilestoneFloor) {
                mLastMilestoneMs = nowMs;
            }
            mMilestoneFloor = clampedFloor;
            mMilestoneCeiling = Math.max(mMilestoneCeiling, clampedCeil);
            if (mCurrentPercent < clampedFloor) {
                mCurrentPercent = clampedFloor;
            }
            mStageText = label;
        }
    }

    private void applySmoothCreepLocked(long nowMs) {
        int maxCreep = Math.max(mMilestoneFloor, mMilestoneCeiling - 1);
        if (mCurrentPercent >= maxCreep) return;
        long sinceMilestone = Math.max(0L, nowMs - mLastMilestoneMs);
        // Asymptotic exponential ease: glides smoothly toward (ceiling - 1) without stalling or overshooting
        float span = maxCreep - mMilestoneFloor;
        double tauMs = Math.max(2_400.0, mExpectedBootMs * 0.22);
        float eased = (float) (1.0 - Math.exp(-sinceMilestone / tauMs));
        float candidate = mMilestoneFloor + span * eased;
        if (candidate > mCurrentPercent) {
            mCurrentPercent = Math.min(maxCreep, candidate);
        }
    }

    @NonNull
    private ProgressSnapshot buildSnapshotLocked(long nowMs) {
        int pct = mCompleted ? 100 : Math.max(1, Math.min(99, Math.round(mCurrentPercent)));
        if (mCompleted) {
            return new ProgressSnapshot(100, mStageText, "Ready", 0, true);
        }
        int etaSec = computeEtaSecondsLocked(pct, nowMs);
        String etaText = formatEta(pct, etaSec);
        return new ProgressSnapshot(pct, mStageText, etaText, etaSec, false);
    }

    private int computeEtaSecondsLocked(int pct, long nowMs) {
        if (pct >= 96) return 1;
        long elapsedMs = Math.max(0L, nowMs - mStartMs);

        // Baseline model scaled to remaining percentage
        double modelTotalMs = mExpectedBootMs;
        if (mDetectedModCount > mInitialModCount) {
            modelTotalMs += Math.min(150, mDetectedModCount - mInitialModCount) * 340.0;
        }
        double modelRemainingMs = modelTotalMs * ((100.0 - pct) / 100.0);

        // Once enough real log progress has elapsed, blend smoothly with observed launch velocity
        double blendedRemainingMs;
        if (pct >= 18 && elapsedMs >= 1_800L) {
            double velocityRemainingMs = elapsedMs * ((100.0 - pct) / Math.max(10.0, pct));
            double velocityWeight = Math.min(0.75, (pct - 14.0) / 80.0);
            blendedRemainingMs = modelRemainingMs * (1.0 - velocityWeight)
                    + velocityRemainingMs * velocityWeight;
        } else {
            blendedRemainingMs = Math.max(modelRemainingMs, modelTotalMs - elapsedMs);
        }

        int sec = (int) Math.ceil(blendedRemainingMs / 1000.0);
        return Math.max(1, Math.min(300, sec));
    }

    @NonNull
    public static String formatEta(int percent, int remainingSeconds) {
        if (percent >= 100) return "Ready";
        if (percent >= 96 || remainingSeconds <= 2) return "Almost ready…";
        if (remainingSeconds < 60) {
            return "~" + remainingSeconds + "s left";
        }
        int mins = remainingSeconds / 60;
        int secs = remainingSeconds % 60;
        if (secs == 0) {
            return "~" + mins + "m left";
        }
        return "~" + mins + "m " + secs + "s left";
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
