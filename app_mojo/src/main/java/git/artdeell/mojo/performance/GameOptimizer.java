package git.artdeell.mojo.performance;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zyrox Mobile Minecraft Optimizer — low-pause JVM/GC tuning, Android LMK off-heap headroom
 * protection, safe mobile {@code options.txt} tuning (with atomic {@code .bak} backup), high-Hz
 * display promotion (90/120/144 Hz), and pre-launch memory reclamation.
 */
public final class GameOptimizer {

    public static final String PREF_KEY_MODE = "zyroxOptimizerMode";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_MAX_FPS = "max_fps";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_SHADERS = "shaders";
    public static final String MODE_OFF = "off";

    private static final String FIRST_RUN_MARKER = ".zyrox_opts_initialized";
    private static final String OPTIONS_BACKUP_NAME = "options.txt.zyrox.bak";

    private GameOptimizer() {}

    /** Normalizes any preference string to a valid optimization mode constant. */
    @NonNull
    public static String normalizeMode(@Nullable String raw) {
        if (raw == null) return MODE_AUTO;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case MODE_MAX_FPS:
                return MODE_MAX_FPS;
            case MODE_BALANCED:
                return MODE_BALANCED;
            case MODE_SHADERS:
                return MODE_SHADERS;
            case MODE_OFF:
                return MODE_OFF;
            case MODE_AUTO:
            default:
                return MODE_AUTO;
        }
    }

    /** Human-readable label for UI cards and settings summaries. */
    @NonNull
    public static String getModeDisplayName(@Nullable String mode) {
        switch (normalizeMode(mode)) {
            case MODE_MAX_FPS:
                return "Competitive Max FPS (90/120 Hz)";
            case MODE_BALANCED:
                return "Balanced Smooth";
            case MODE_SHADERS:
                return "Shader Ready (Iris / OptiFine)";
            case MODE_OFF:
                return "Off (Unmodified)";
            case MODE_AUTO:
            default:
                return "Auto Smart (Recommended)";
        }
    }

    /** Counts enabled {@code .jar} mod files in {@code <gameDir>/mods}. */
    public static int countInstalledMods(@Nullable File gameDir) {
        if (gameDir == null) return 0;
        File modsDir = new File(gameDir, "mods");
        File[] files = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        return files == null ? 0 : files.length;
    }

    /** Detects whether Iris or OptiFine shader mod is installed in {@code <gameDir>/mods}. */
    public static boolean hasShaderModInstalled(@Nullable File gameDir) {
        if (gameDir == null) return false;
        File modsDir = new File(gameDir, "mods");
        File[] files = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return false;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.contains("iris") || n.contains("oculus") || n.contains("optifine")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Synthesizes safe, low-pause mobile JVM flags without ever duplicating or overriding any GC
     * or memory flags already configured by the user or modloader JSON.
     */
    @NonNull
    public static List<String> buildOptimizedJvmFlags(@Nullable String rawMode,
                                                      @NonNull List<String> existingArgs,
                                                      int javaMajorVersion,
                                                      int heapMb,
                                                      int freeRamMb,
                                                      int modCount,
                                                      boolean is64Bit) {
        String mode = normalizeMode(rawMode);
        if (MODE_OFF.equals(mode)) {
            return Collections.emptyList();
        }

        List<String> added = new ArrayList<>();
        boolean userSpecifiedGc = containsPrefix(existingArgs,
                "-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseShenandoahGC",
                "-XX:+UseParallelGC", "-XX:+UseSerialGC", "-XX:+UseConcMarkSweepGC");

        addIfAbsent(existingArgs, added, "-XX:+UnlockExperimentalVMOptions");
        // Prevent rogue System.gc() calls from mods causing 300-600ms frame freezes
        addIfAbsent(existingArgs, added, "-XX:+DisableExplicitGC");
        // Eliminate periodic hsperfdata flash disk writes on Android storage
        addIfAbsent(existingArgs, added, "-XX:+PerfDisableSharedMem");
        // Faster server ping & multiplayer socket resolution on mobile networks
        addIfAbsent(existingArgs, added, "-Djava.net.preferIPv4Stack=true");
        // Skip redundant Forge early splash GL context on mobile render bridges
        addIfAbsent(existingArgs, added, "-Dfml.earlyprogresswindow=false");

        if (is64Bit && heapMb >= 768 && !userSpecifiedGc) {
            // Tuned Aikar-derived mobile G1GC profile minimizing chunk-gen frame-time spikes
            int pauseTargetMs;
            int reservePct;
            if (MODE_MAX_FPS.equals(mode)) {
                pauseTargetMs = 28;
                reservePct = 15;
            } else if (MODE_SHADERS.equals(mode) || modCount >= 25) {
                pauseTargetMs = 40;
                reservePct = 20;
            } else {
                pauseTargetMs = 35;
                reservePct = 16;
            }
            int regionMb = heapMb >= 2560 ? 16 : 8;

            addIfAbsent(existingArgs, added, "-XX:+UseG1GC");
            addIfAbsent(existingArgs, added, "-XX:+ParallelRefProcEnabled");
            addIfPrefixAbsent(existingArgs, added, "-XX:MaxGCPauseMillis=",
                    "-XX:MaxGCPauseMillis=" + pauseTargetMs);
            addIfPrefixAbsent(existingArgs, added, "-XX:G1NewSizePercent=",
                    "-XX:G1NewSizePercent=20");
            addIfPrefixAbsent(existingArgs, added, "-XX:G1ReservePercent=",
                    "-XX:G1ReservePercent=" + reservePct);
            addIfPrefixAbsent(existingArgs, added, "-XX:G1HeapRegionSize=",
                    "-XX:G1HeapRegionSize=" + regionMb + "M");
            addIfPrefixAbsent(existingArgs, added, "-XX:G1MixedGCCountTarget=",
                    "-XX:G1MixedGCCountTarget=4");
            addIfPrefixAbsent(existingArgs, added, "-XX:InitiatingHeapOccupancyPercent=",
                    "-XX:InitiatingHeapOccupancyPercent=20");
            addIfPrefixAbsent(existingArgs, added, "-XX:G1MixedGCLiveThresholdPercent=",
                    "-XX:G1MixedGCLiveThresholdPercent=90");
            addIfPrefixAbsent(existingArgs, added, "-XX:G1RSetUpdatingPauseTimePercent=",
                    "-XX:G1RSetUpdatingPauseTimePercent=5");
            addIfPrefixAbsent(existingArgs, added, "-XX:SurvivorRatio=",
                    "-XX:SurvivorRatio=32");

            // Deduplicate identical identifier/NBT strings on heavy Java 17+ modpacks
            if (javaMajorVersion >= 17 && modCount >= 14) {
                addIfAbsent(existingArgs, added, "-XX:+UseStringDeduplication");
            }
            // Pre-touch heap pages ONLY when ample free RAM exists so boot stays instant on mid-range phones
            if (freeRamMb > 0 && (freeRamMb - heapMb) >= 1800 && heapMb <= 4096) {
                addIfAbsent(existingArgs, added, "-XX:+AlwaysPreTouch");
            }
        }

        return added;
    }

    /**
     * Evaluates whether requested heap risks Android Low-Memory-Killer (LMK) termination once
     * Vulkan/GLES/shader/OpenAL off-heap memory is accounted for, returning a safe recommended MB.
     */
    public static int recommendSafeHeapMb(int requestedMb,
                                          int totalDeviceRamMb,
                                          int freeDeviceRamMb,
                                          boolean shaderHeavy,
                                          boolean is64Bit) {
        if (!is64Bit) {
            return Math.max(512, Math.min(requestedMb, 1152));
        }
        if (totalDeviceRamMb <= 0) {
            return Math.max(768, requestedMb);
        }
        // Reserve native off-heap headroom for GPU driver (Turnip/Zink/LTW), textures & Android OS
        int nativeReserveMb = shaderHeavy ? 900 : 650;
        int totalCeilingMb = Math.max(1024, (int) Math.round(totalDeviceRamMb * 0.62));
        int safe = Math.min(requestedMb, totalCeilingMb);
        if (freeDeviceRamMb > 1200) {
            int freeCeiling = Math.max(1024, freeDeviceRamMb - nativeReserveMb);
            if (requestedMb > freeDeviceRamMb + 256) {
                safe = Math.min(safe, freeCeiling);
            }
        }
        // Align cleanly to 64 MB increments
        safe = (safe / 64) * 64;
        return Math.max(768, safe);
    }

    /**
     * Applies smart mobile-friendly {@code options.txt} defaults ONCE on a brand-new instance so
     * existing player video customizations are never silently overwritten on subsequent launches.
     */
    public static boolean applyFirstLaunchOptionsIfNeeded(@Nullable File gameDir,
                                                          @Nullable String rawMode,
                                                          int totalDeviceRamMb,
                                                          int displayHz) {
        String mode = normalizeMode(rawMode);
        if (gameDir == null || MODE_OFF.equals(mode)) return false;
        File marker = new File(gameDir, FIRST_RUN_MARKER);
        if (marker.exists()) return false;
        File optionsFile = new File(gameDir, "options.txt");
        if (!optionsFile.isFile()) return false;

        String effectiveMode = MODE_AUTO.equals(mode)
                ? (hasShaderModInstalled(gameDir) ? MODE_SHADERS : MODE_BALANCED)
                : mode;
        boolean updated = applyOptionsPreset(gameDir, effectiveMode, totalDeviceRamMb, displayHz, false);
        try {
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        } catch (IOException ignored) {
        }
        return updated;
    }

    /**
     * Explicitly applies a mobile FPS preset to {@code <gameDir>/options.txt} with atomic write and
     * optional {@code options.txt.zyrox.bak} rollback backup.
     */
    public static boolean applyOptionsPreset(@Nullable File gameDir,
                                             @Nullable String rawMode,
                                             int totalDeviceRamMb,
                                             int displayHz,
                                             boolean createBackup) {
        if (gameDir == null) return false;
        String mode = normalizeMode(rawMode);
        if (MODE_OFF.equals(mode)) return false;
        if (!gameDir.exists() && !gameDir.mkdirs()) return false;

        Map<String, String> overrides = buildOptionsOverrides(mode, totalDeviceRamMb, displayHz);
        File optionsFile = new File(gameDir, "options.txt");
        List<String> lines = new ArrayList<>();
        if (optionsFile.isFile()) {
            try {
                String content = readUtf8(optionsFile);
                if (createBackup) {
                    writeAtomicUtf8(new File(gameDir, OPTIONS_BACKUP_NAME), content);
                }
                for (String l : content.split("\r?\n")) {
                    lines.add(l);
                }
            } catch (IOException e) {
                return false;
            }
        }

        Map<String, String> remaining = new LinkedHashMap<>(overrides);
        List<String> outLines = new ArrayList<>(lines.size() + remaining.size());
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                if (remaining.containsKey(key)) {
                    outLines.add(key + ":" + remaining.remove(key));
                    continue;
                }
            }
            outLines.add(line);
        }
        for (Map.Entry<String, String> e : remaining.entrySet()) {
            outLines.add(e.getKey() + ":" + e.getValue());
        }

        StringBuilder sb = new StringBuilder();
        for (String l : outLines) {
            sb.append(l).append('\n');
        }
        try {
            writeAtomicUtf8(optionsFile, sb.toString());
            File marker = new File(gameDir, FIRST_RUN_MARKER);
            if (!marker.exists()) {
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Restores {@code options.txt.zyrox.bak} if present. */
    public static boolean restoreOptionsBackup(@Nullable File gameDir) {
        if (gameDir == null) return false;
        File bak = new File(gameDir, OPTIONS_BACKUP_NAME);
        File optionsFile = new File(gameDir, "options.txt");
        if (!bak.isFile()) return false;
        try {
            writeAtomicUtf8(optionsFile, readUtf8(bak));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean hasOptionsBackup(@Nullable File gameDir) {
        return gameDir != null && new File(gameDir, OPTIONS_BACKUP_NAME).isFile();
    }

    /** Produces calibrated Minecraft {@code options.txt} key-value settings for the target tier. */
    @NonNull
    public static Map<String, String> buildOptionsOverrides(@Nullable String rawMode,
                                                            int totalDeviceRamMb,
                                                            int displayHz) {
        String mode = normalizeMode(rawMode);
        boolean highRam = totalDeviceRamMb >= 7500;
        boolean midRam = totalDeviceRamMb >= 5200;
        int cappedHz = displayHz >= 115 ? 120 : (displayHz >= 85 ? 90 : 60);

        Map<String, String> m = new LinkedHashMap<>();
        if (MODE_MAX_FPS.equals(mode)) {
            int rd = highRam ? 8 : (midRam ? 6 : 5);
            int sd = Math.min(rd, 5);
            m.put("renderDistance", String.valueOf(rd));
            m.put("simulationDistance", String.valueOf(sd));
            m.put("maxFps", String.valueOf(Math.max(120, cappedHz)));
            m.put("enableVsync", "false");
            m.put("graphicsMode", "0");
            m.put("ao", "1");
            m.put("biomeBlendRadius", "1");
            m.put("cloudStatus", "\"false\"");
            m.put("particles", "2");
            m.put("entityShadows", "false");
            m.put("entityDistanceScaling", "0.65");
            m.put("mipmapLevels", "1");
            m.put("prioritizeChunkUpdates", "1");
        } else if (MODE_SHADERS.equals(mode)) {
            int rd = highRam ? 10 : (midRam ? 8 : 6);
            int sd = highRam ? 6 : 5;
            m.put("renderDistance", String.valueOf(rd));
            m.put("simulationDistance", String.valueOf(sd));
            m.put("maxFps", String.valueOf(cappedHz));
            m.put("enableVsync", "false");
            m.put("graphicsMode", "1");
            m.put("ao", "2");
            m.put("biomeBlendRadius", "2");
            m.put("cloudStatus", "\"fast\"");
            m.put("particles", "1");
            m.put("entityShadows", "true");
            m.put("entityDistanceScaling", "0.85");
            m.put("mipmapLevels", "2");
            m.put("prioritizeChunkUpdates", "1");
        } else {
            // Balanced / Auto Smart
            int rd = highRam ? 10 : (midRam ? 8 : 6);
            int sd = highRam ? 7 : 5;
            m.put("renderDistance", String.valueOf(rd));
            m.put("simulationDistance", String.valueOf(sd));
            m.put("maxFps", String.valueOf(Math.max(90, cappedHz)));
            m.put("enableVsync", "false");
            m.put("graphicsMode", midRam ? "1" : "0");
            m.put("ao", "2");
            m.put("biomeBlendRadius", "2");
            m.put("cloudStatus", "\"fast\"");
            m.put("particles", "1");
            m.put("entityShadows", midRam ? "true" : "false");
            m.put("entityDistanceScaling", "0.8");
            m.put("mipmapLevels", "2");
            m.put("prioritizeChunkUpdates", "1");
        }
        return m;
    }

    /**
     * Reclaims launcher-side bitmap memory and unlocks native high refresh rate (90/120/144 Hz)
     * when entering {@code GameActivity} (respecting Android Battery Saver).
     */
    public static int applyInGameSystemBoost(@NonNull Activity activity, @Nullable String rawMode) {
        // 1. Free launcher Glide image memory cache so maximum RAM stays available for Minecraft
        try {
            Glide.get(activity.getApplicationContext()).clearMemory();
        } catch (Throwable ignored) {
        }

        String mode = normalizeMode(rawMode);
        if (MODE_OFF.equals(mode)) {
            return 60;
        }

        // Respect system Battery Saver
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                return 60;
            }
        } catch (Throwable ignored) {
        }

        // 2. Promote window to highest native-resolution display refresh rate (90 / 120 / 144 Hz)
        int selectedHz = 60;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Window window = activity.getWindow();
                Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? activity.getDisplay()
                        : window.getWindowManager().getDefaultDisplay();
                if (display != null) {
                    Display.Mode current = display.getMode();
                    Display.Mode best = current;
                    for (Display.Mode candidate : display.getSupportedModes()) {
                        if (candidate.getPhysicalWidth() == current.getPhysicalWidth()
                                && candidate.getPhysicalHeight() == current.getPhysicalHeight()
                                && candidate.getRefreshRate() > best.getRefreshRate() + 0.5f) {
                            best = candidate;
                        }
                    }
                    selectedHz = Math.max(60, Math.round(best.getRefreshRate()));
                    if (best.getModeId() != current.getModeId()) {
                        WindowManager.LayoutParams lp = window.getAttributes();
                        lp.preferredDisplayModeId = best.getModeId();
                        window.setAttributes(lp);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return selectedHz;
    }

    private static boolean containsPrefix(@NonNull List<String> args, @NonNull String... prefixes) {
        for (String a : args) {
            if (a == null) continue;
            String trimmed = a.trim();
            for (String p : prefixes) {
                if (trimmed.startsWith(p)) return true;
            }
        }
        return false;
    }

    private static void addIfAbsent(@NonNull List<String> existing,
                                    @NonNull List<String> added,
                                    @NonNull String flag) {
        for (String a : existing) {
            if (flag.equals(a != null ? a.trim() : null)) return;
        }
        for (String a : added) {
            if (flag.equals(a)) return;
        }
        added.add(flag);
    }

    private static void addIfPrefixAbsent(@NonNull List<String> existing,
                                          @NonNull List<String> added,
                                          @NonNull String prefix,
                                          @NonNull String fullFlag) {
        if (containsPrefix(existing, prefix) || containsPrefix(added, prefix)) return;
        added.add(fullFlag);
    }

    @NonNull
    private static String readUtf8(@NonNull File file) throws IOException {
        byte[] buf = new byte[(int) Math.min(file.length(), 1024 * 1024)];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while (offset < buf.length && (n = in.read(buf, offset, buf.length - offset)) != -1) {
                offset += n;
            }
        }
        return new String(buf, 0, offset, StandardCharsets.UTF_8);
    }

    private static void writeAtomicUtf8(@NonNull File target, @NonNull String text) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".zyrox.tmp");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
                out.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
