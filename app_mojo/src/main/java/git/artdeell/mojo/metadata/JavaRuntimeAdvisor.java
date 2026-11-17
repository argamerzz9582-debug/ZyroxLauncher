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
        if (versionJson != null) {
            return MojangVersionProvider.resolveRequiredJavaVersion(versionJson);
        }
        if (versionId == null || versionId.trim().isEmpty()) return 8;
        String id = versionId.trim();
        if ("latest_release".equalsIgnoreCase(id) || "latest_snapshot".equalsIgnoreCase(id)) {
            return 21;
        }
        JVersionList.Version probe = new JVersionList.Version();
        probe.id = extractMcVersionToken(id);
        return MojangVersionProvider.resolveRequiredJavaVersion(probe);
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
