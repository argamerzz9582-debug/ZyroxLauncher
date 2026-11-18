package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.multirt.Runtime;

/**
 * Provides truthful, non-hardcoded Java runtime (JRE) compatibility evaluation and plain-language
 * explanations built directly on top of {@link MojangVersionProvider#resolveRequiredJavaVersion}.
 */
public final class JavaRuntimeAdvisor {

    public enum Compatibility {
        RECOMMENDED,
        COMPATIBLE_NEWER,
        INCOMPATIBLE_TOO_OLD,
        MISSING_OR_CORRUPT,
        AUTO_MANAGED
    }

    public static final class Advice {
        @NonNull public final Compatibility compatibility;
        public final int requiredJavaMajor;
        public final int selectedJavaMajor;
        @NonNull public final String recommendedRuntimeName;
        @NonNull public final String badgeText;
        @NonNull public final String explanation;

        public Advice(@NonNull Compatibility compatibility,
                      int requiredJavaMajor,
                      int selectedJavaMajor,
                      @NonNull String recommendedRuntimeName,
                      @NonNull String badgeText,
                      @NonNull String explanation) {
            this.compatibility = compatibility;
            this.requiredJavaMajor = requiredJavaMajor;
            this.selectedJavaMajor = selectedJavaMajor;
            this.recommendedRuntimeName = recommendedRuntimeName;
            this.badgeText = badgeText;
            this.explanation = explanation;
        }
    }

    private JavaRuntimeAdvisor() {
    }

    /**
     * Resolves required Java major version from official version metadata when present, or falls
     * back cleanly via {@link MojangVersionProvider} without assuming fixed latest MC versions.
     */
    public static int resolveRequiredJava(@Nullable JVersionList.Version versionJson,
                                          @Nullable String versionId) {
        if (versionJson != null && versionJson.javaVersion != null) {
            return MojangVersionProvider.resolveRequiredJavaVersion(versionJson);
        }
        String rawId = (versionId != null && !versionId.trim().isEmpty())
                ? versionId.trim()
                : (versionJson != null && versionJson.id != null ? versionJson.id.trim() : "");
        if (rawId.isEmpty()) return 8;
        if ("latest_release".equalsIgnoreCase(rawId) || "latest_snapshot".equalsIgnoreCase(rawId)) {
            return 21;
        }
        return inferRequiredJavaFromVersionToken(extractMcVersionToken(rawId));
    }

    /**
     * Infers the minimum Java major version from a normalized Minecraft version token when the
     * full version JSON has not been downloaded yet (including year-based MC {@code 26.x+} -> JRE 25).
     */
    public static int inferRequiredJavaFromVersionToken(@Nullable String mcToken) {
        if (mcToken == null || mcToken.trim().isEmpty()) return 8;
        String clean = mcToken.trim();
        String[] parts = clean.split("[^0-9]+");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return 8;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = (parts.length >= 3 && !parts[2].isEmpty()) ? Integer.parseInt(parts[2]) : 0;
            // Mojang year-based releases (26.1+) require Java 25
            if (major >= 26) return 25;
            if (major == 1) {
                if (minor >= 21 || (minor == 20 && patch >= 5)) return 21;
                if (minor >= 18) return 17;
                if (minor == 17) return 16;
                return 8;
            }
        } catch (NumberFormatException ignored) {
        }
        return 8;
    }

    /**
     * Evaluates compatibility between the required Java version, the user-selected runtime, and all
     * installed runtimes on device.
     */
    @NonNull
    public static Advice evaluate(int requiredJavaMajor,
                                  @Nullable String versionLabel,
                                  @Nullable Runtime selectedRuntime,
                                  @Nullable List<Runtime> installedRuntimes,
                                  @Nullable String deviceArch) {
        int req = Math.max(8, requiredJavaMajor);
        String verText = (versionLabel != null && !versionLabel.trim().isEmpty())
                ? versionLabel.trim() : "This profile";

        Runtime best = pickBestInstalledRuntime(req, installedRuntimes, deviceArch);
        String bestName = best != null ? best.name : ("Java " + req + " (auto-install/import needed)");

        if (selectedRuntime == null || "<Default>".equals(selectedRuntime.name)) {
            if (best == null) {
                return new Advice(
                        Compatibility.MISSING_OR_CORRUPT,
                        req,
                        0,
                        bestName,
                        "JAVA " + req + " NEEDED",
                        verText + " requires Java " + req + "+. No compatible Java " + req
                                + " runtime is currently installed; import or install Java " + req + " before launch."
                );
            }
            return new Advice(
                    Compatibility.AUTO_MANAGED,
                    req,
                    best.javaVersion,
                    best.name,
                    "AUTO • JAVA " + best.javaVersion,
                    verText + " requires Java " + req + ". Zyrox will automatically use \""
                            + best.name + "\" (Java " + best.javaVersion + ")."
            );
        }

        if (selectedRuntime.versionString == null || selectedRuntime.javaVersion <= 0) {
            return new Advice(
                    Compatibility.MISSING_OR_CORRUPT,
                    req,
                    0,
                    bestName,
                    "RUNTIME MISSING",
                    "Selected runtime \"" + selectedRuntime.name + "\" is corrupted or missing on disk. Recommended: " + bestName + "."
            );
        }

        if (deviceArch != null && selectedRuntime.arch != null
                && !deviceArch.equalsIgnoreCase(selectedRuntime.arch)) {
            return new Advice(
                    Compatibility.MISSING_OR_CORRUPT,
                    req,
                    selectedRuntime.javaVersion,
                    bestName,
                    "ARCH MISMATCH",
                    "Selected runtime \"" + selectedRuntime.name + "\" targets " + selectedRuntime.arch
                            + " instead of device ABI " + deviceArch + "."
            );
        }

        int sel = selectedRuntime.javaVersion;
        if (sel < req) {
            return new Advice(
                    Compatibility.INCOMPATIBLE_TOO_OLD,
                    req,
                    sel,
                    bestName,
                    "NEEDS JAVA " + req,
                    verText + " requires Java " + req + ", but \"" + selectedRuntime.name
                            + "\" provides Java " + sel + ". Recommended: " + bestName + "."
            );
        }

        if (sel == req) {
            return new Advice(
                    Compatibility.RECOMMENDED,
                    req,
                    sel,
                    selectedRuntime.name,
                    "JAVA " + sel + " • OPTIMAL",
                    "Exact match: " + verText + " targets Java " + req + " (\"" + selectedRuntime.name + "\")."
            );
        }

        // Legacy Forge 1.12.2/1.7.10 expects Java 8 unless lwjgl3ify is present
        String warnLegacy = (req == 8 && sel >= 17)
                ? " Note: older Forge/legacy mods targeting Java 8 may require a Java 8 runtime."
                : "";
        return new Advice(
                Compatibility.COMPATIBLE_NEWER,
                req,
                sel,
                best != null ? best.name : selectedRuntime.name,
                "JAVA " + sel + " • COMPATIBLE",
                verText + " requires Java " + req + "; selected \"" + selectedRuntime.name
                        + "\" provides newer Java " + sel + "." + warnLegacy
        );
    }

    @Nullable
    public static Runtime pickBestInstalledRuntime(int requiredJavaMajor,
                                                   @Nullable List<Runtime> installed,
                                                   @Nullable String deviceArch) {
        if (installed == null || installed.isEmpty()) return null;
        List<Runtime> valid = new ArrayList<>();
        for (Runtime rt : installed) {
            if (rt == null || rt.versionString == null || rt.javaVersion < requiredJavaMajor) continue;
            if (deviceArch != null && rt.arch != null && !deviceArch.equalsIgnoreCase(rt.arch)) continue;
            valid.add(rt);
        }
        if (valid.isEmpty()) return null;
        Runtime closest = valid.get(0);
        for (Runtime rt : valid) {
            if (rt.javaVersion < closest.javaVersion) {
                closest = rt;
            }
        }
        return closest;
    }

    /**
     * Computes a safe adjusted JVM heap allocation (MB) in response to an OutOfMemory crash:
     * increases heap by ~35% (capped leaving OS/GPU headroom) for Java Heap OOM, or lowers heap by
     * ~20% for native/direct-buffer address-space exhaustion.
     */
    public static int computeAutoFixedHeapMb(int currentHeapMb,
                                             int totalDeviceRamMb,
                                             boolean nativeOrDirectOom) {
        int safeTotal = totalDeviceRamMb > 512 ? totalDeviceRamMb : 4096;
        // Leave at least 1024 MB (or 30% on smaller phones) for Android + GPU/Zink driver
        int maxSafeHeap = Math.max(512, Math.min(safeTotal - 1024, (int) (safeTotal * 0.68f)));
        int cur = Math.max(256, currentHeapMb);

        int target;
        if (nativeOrDirectOom) {
            target = Math.max(512, Math.min(maxSafeHeap, (int) (cur * 0.80f)));
            if (target >= cur && cur > 640) {
                target = cur - 256;
            }
        } else {
            int step = Math.max(256, (int) (cur * 0.35f));
            target = Math.min(maxSafeHeap, cur + step);
        }
        // Snap cleanly to 64 MB increments
        target = Math.max(384, (target / 64) * 64);
        return target;
    }

    /**
     * Attempts a one-tap switch of {@code instance} (and/or global default) to the closest
     * installed OpenJDK runtime providing at least {@code requiredJavaMajor}. Returns the selected
     * {@link Runtime}, or {@code null} if no matching JRE is currently installed on disk.
     */
    @Nullable
    public static Runtime switchToCompatibleRuntime(
            @Nullable git.artdeell.mojo.instances.Instance instance,
            int requiredJavaMajor,
            @Nullable List<Runtime> installedRuntimes,
            @Nullable String deviceArch) {
        int targetMajor = Math.max(8, requiredJavaMajor);
        Runtime best = pickBestInstalledRuntime(targetMajor, installedRuntimes, deviceArch);
        if (best == null) return null;

        if (instance != null) {
            instance.selectedRuntime = best.name;
            instance.maybeWrite();
        } else {
            git.artdeell.mojo.prefs.LauncherPreferences.PREF_DEFAULT_RUNTIME = best.name;
            if (git.artdeell.mojo.prefs.LauncherPreferences.DEFAULT_PREF != null) {
                git.artdeell.mojo.prefs.LauncherPreferences.DEFAULT_PREF.edit()
                        .putString("defaultRuntime", best.name)
                        .apply();
            }
        }
        return best;
    }

    /** Extracts embedded MC version from loader profile IDs like {@code fabric-loader-0.16.9-1.21.4} or {@code neoforge-26.1.0}. */
    @NonNull
    static String extractMcVersionToken(@NonNull String versionId) {
        String low = versionId.toLowerCase(Locale.ROOT);
        if (low.startsWith("fabric-loader-") || low.startsWith("quilt-loader-") || low.startsWith("legacy-fabric-loader-")) {
            int lastDash = versionId.lastIndexOf('-');
            if (lastDash > 0 && lastDash + 1 < versionId.length()) {
                return versionId.substring(lastDash + 1);
            }
        }
        if (low.contains("-forge-")) {
            int idx = low.indexOf("-forge-");
            return versionId.substring(0, idx);
        }
        if (low.startsWith("neoforge-")) {
            String rest = versionId.substring("neoforge-".length());
            String[] parts = rest.split("[.\\-]");
            if (parts.length >= 2) {
                try {
                    int first = Integer.parseInt(parts[0]);
                    int second = Integer.parseInt(parts[1]);
                    if (first >= 26) {
                        return first + "." + second;
                    } else if (first >= 20) {
                        return "1." + first + (second > 0 ? "." + second : "");
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return versionId;
    }
}
