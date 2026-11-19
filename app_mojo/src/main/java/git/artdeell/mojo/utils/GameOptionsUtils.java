package git.artdeell.mojo.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Atomic, order-preserving Minecraft {@code options.txt} optimizer for Zyrox's 1-Tap Zero-Lag
 * FPS Boost Pack, PvP Ready setup, and Vanilla Smooth onboarding profile.
 *
 * <p>Preserves all existing user keybindings, resource packs, language, and sound levels while
 * safely overriding targeted graphics and input latency keys.
 */
public final class GameOptionsUtils {

    private static final long MAX_OPTIONS_BYTES = 1024L * 1024L;

    private GameOptionsUtils() {
    }

    /** Parses an integer or returns {@code defaultValue} when {@code value} is null/invalid. */
    public static int parseIntDefault(@Nullable String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Applies pre-launch Mali cloud range, fullscreen, and narrator safety fixes. */
    public static void fixOptions(boolean isLtw) {
        try {
            MCOptionUtils.load();
        } catch (Exception e) {
            android.util.Log.e("Tools", "Failed to load config", e);
        }
        if (isLtw) {
            GpuUtils.GLInfo info = GpuUtils.getGlInfo();
            if (info != null && info.isArm()) {
                int cloudRange = parseIntDefault(MCOptionUtils.get("cloudRange"), 128);
                if (cloudRange > 64) {
                    MCOptionUtils.set("cloudRange", "64");
                }
            }
        }
        String fullscreen = MCOptionUtils.get("fullscreen");
        if ("true".equals(fullscreen)) {
            MCOptionUtils.set("fullscreen", "false");
        } else if ("1".equals(fullscreen)) {
            MCOptionUtils.set("fullscreen", "0");
        }
        if (parseIntDefault(MCOptionUtils.get("narrator"), 0) != 0) {
            MCOptionUtils.set("narrator", "0");
        }
        try {
            MCOptionUtils.save();
        } catch (Exception e) {
            android.util.Log.e("Tools", "Failed to save config", e);
        }
    }

    /**
     * Applies Zyrox Zero-Lag FPS {@code options.txt} optimizations (Render Distance 8, Simulation
     * Distance 6, Entity Shadows OFF, Particles Decreased, VSync OFF, Clouds OFF, Biome Blend 1)
     * and optional competitive PvP tuning (View Bobbing OFF, Auto-Jump OFF, Raw Input ON, bright
     * gamma, reduced FOV distortion).
     */
    public static boolean applyZeroLagFpsOptions(@Nullable File gameDir, boolean pvpMode) {
        if (gameDir == null) return false;
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("renderDistance", "8");
        overrides.put("simulationDistance", "6");
        overrides.put("entityShadows", "false");
        overrides.put("particles", "1");
        overrides.put("enableVsync", "false");
        overrides.put("maxFps", "120");
        overrides.put("graphicsMode", "0");
        overrides.put("renderClouds", "\"false\"");
        overrides.put("biomeBlendRadius", "1");
        overrides.put("mipmapLevels", "2");
        overrides.put("entityDistanceScaling", "0.75");
        overrides.put("fullscreen", "false");
        overrides.put("narrator", "0");
        overrides.put("onboardAccessibility", "false");
        if (pvpMode) {
            overrides.put("bobView", "false");
            overrides.put("autoJump", "false");
            overrides.put("rawMouseInput", "true");
            overrides.put("gamma", "1.5");
            overrides.put("fovEffectScale", "0.4");
            overrides.put("screenEffectScale", "0.5");
        }
        return applyOptionOverrides(gameDir, overrides);
    }

    /**
     * Applies balanced Vanilla Smooth defaults for first-time mobile survival play without
     * sacrificing visual fidelity (Render Distance 10, Simulation 8, VSync ON, Biome Blend 2).
     */
    public static boolean applyVanillaSmoothOptions(@Nullable File gameDir) {
        if (gameDir == null) return false;
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("renderDistance", "10");
        overrides.put("simulationDistance", "8");
        overrides.put("entityShadows", "true");
        overrides.put("particles", "0");
        overrides.put("enableVsync", "true");
        overrides.put("maxFps", "120");
        overrides.put("biomeBlendRadius", "2");
        overrides.put("autoJump", "false");
        overrides.put("fullscreen", "false");
        overrides.put("narrator", "0");
        overrides.put("onboardAccessibility", "false");
        return applyOptionOverrides(gameDir, overrides);
    }

    /** Atomically merges {@code overrides} into {@code <gameDir>/options.txt}. */
    public static boolean applyOptionOverrides(@NonNull File gameDir,
                                               @NonNull Map<String, String> overrides) {
        if (overrides.isEmpty()) return true;
        try {
            FileUtils.ensureDirectory(gameDir);
            File optionsFile = new File(gameDir, "options.txt");
            Map<String, String> merged = readOptionsMap(optionsFile);
            merged.putAll(overrides);
            writeOptionsAtomically(optionsFile, merged);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads {@code options.txt} key:value pairs in insertion order. */
    @NonNull
    public static Map<String, String> readOptionsMap(@Nullable File optionsFile) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (optionsFile == null || !optionsFile.isFile()) return map;
        if (optionsFile.length() > MAX_OPTIONS_BYTES) {
            throw new IOException("options.txt exceeds safe size ceiling");
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(optionsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1);
                if (!key.isEmpty()) {
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    private static void writeOptionsAtomically(@NonNull File optionsFile,
                                               @NonNull Map<String, String> entries) throws IOException {
        File parent = optionsFile.getParentFile();
        if (parent != null) FileUtils.ensureDirectory(parent);
        File tmpFile = new File(optionsFile.getParentFile(), optionsFile.getName() + ".zyrox.tmp");
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmpFile, false), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                bw.write(e.getKey());
                bw.write(':');
                bw.write(e.getValue() != null ? e.getValue() : "");
                bw.newLine();
            }
            bw.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            throw e;
        }
        if (optionsFile.exists() && !optionsFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            throw new IOException("Could not replace options.txt");
        }
        if (!tmpFile.renameTo(optionsFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            throw new IOException("Could not finalize options.txt");
        }
    }
}
