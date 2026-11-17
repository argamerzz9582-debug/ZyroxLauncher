package git.artdeell.mojo.diagnostics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.security.LogRedactor;

/**
 * Zyrox Guard — Local, deterministic, privacy-respecting Minecraft & JVM crash analyzer.
 *
 * <p>Classifies crash reports and runtime logs using concrete log signatures, reports honest
 * confidence levels ({@link Confidence#HIGH}, {@link Confidence#MEDIUM}, {@link Confidence#LOW}),
 * surfaces redacted verbatim evidence lines, and recommends only safe user-confirmed fixes.
 * Never invents unsupported causes or auto-uploads logs.
 */
public final class ZyroxCrashAnalyzer {

    public enum Category {
        OUT_OF_MEMORY,
        JAVA_VERSION_MISMATCH,
        MISSING_MOD_DEPENDENCY,
        DUPLICATE_MOD,
        MOD_LOADER_MISMATCH,
        MIXIN_CONFLICT,
        SHADER_INCOMPATIBILITY,
        RENDERER_OR_GL_FAILURE,
        CORRUPT_FILE_OR_HASH,
        AUTH_OR_NETWORK_ERROR,
        NATIVE_SIGNAL_CRASH,
        NO_CRASH_DETECTED,
        UNKNOWN
    }

    public enum Confidence {
        HIGH("HIGH CONFIDENCE"),
        MEDIUM("MEDIUM CONFIDENCE"),
        LOW("LOW CONFIDENCE");

        @NonNull public final String label;

        Confidence(@NonNull String label) {
            this.label = label;
        }
    }

    public enum SuggestedAction {
        OPEN_INSTANCE_STUDIO,
        SWITCH_SAFE_RENDERER,
        OPEN_MOD_MANAGER,
        OPEN_DOWNLOAD_CENTER,
        NONE
    }

    public static final class Diagnosis {
        @NonNull public final Category category;
        @NonNull public final Confidence confidence;
        @NonNull public final String title;
        @NonNull public final String explanation;
        @NonNull public final List<String> evidenceLines;
        @NonNull public final List<String> recommendedSteps;
        @NonNull public final SuggestedAction primaryAction;

        public Diagnosis(@NonNull Category category,
                         @NonNull Confidence confidence,
                         @NonNull String title,
                         @NonNull String explanation,
                         @NonNull List<String> evidenceLines,
                         @NonNull List<String> recommendedSteps,
                         @NonNull SuggestedAction primaryAction) {
            this.category = category;
            this.confidence = confidence;
            this.title = title;
            this.explanation = explanation;
            this.evidenceLines = Collections.unmodifiableList(evidenceLines);
            this.recommendedSteps = Collections.unmodifiableList(recommendedSteps);
            this.primaryAction = primaryAction;
        }

        @NonNull
        public String formatCompactSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(title).append("  [").append(confidence.label).append("]\n");
            sb.append(explanation);
            if (!evidenceLines.isEmpty()) {
                sb.append("\nEvidence: ").append(evidenceLines.get(0));
            }
            if (!recommendedSteps.isEmpty()) {
                sb.append("\nSuggested Fix: ").append(recommendedSteps.get(0));
            }
            return sb.toString();
        }
    }

    private static final Pattern CLASS_VERSION_PATTERN = Pattern.compile(
            "class file version\\s+(\\d+)(?:\\.\\d+)?", Pattern.CASE_INSENSITIVE
    );

    private ZyroxCrashAnalyzer() {
    }

    /**
     * Analyzes raw crash/log text locally and returns a structured, redacted {@link Diagnosis}.
     */
    @NonNull
    public static Diagnosis analyze(@Nullable String rawLog) {
        if (rawLog == null || rawLog.trim().isEmpty()) {
            return new Diagnosis(
                    Category.NO_CRASH_DETECTED,
                    Confidence.HIGH,
                    "No Log Output Recorded Yet",
                    "Launch a Minecraft instance to capture runtime output and diagnostic signals.",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    SuggestedAction.NONE
            );
        }

        String safeText = LogRedactor.redact(rawLog);
        String lower = safeText.toLowerCase(Locale.ROOT);
        String[] lines = safeText.split("\\r?\\n");

        // 1. Java Class File / Runtime Version Mismatch (HIGH confidence)
        if (lower.contains("unsupportedclassversionerror")
                || lower.contains("has been compiled by a more recent version of the java runtime")) {
            Matcher m = CLASS_VERSION_PATTERN.matcher(safeText);
            int requiredJava = -1;
            if (m.find()) {
                try {
                    requiredJava = classFileMajorToJava(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
            String reqText = requiredJava > 0 ? ("Java " + requiredJava) : "a newer Java runtime";
            return new Diagnosis(
                    Category.JAVA_VERSION_MISMATCH,
                    Confidence.HIGH,
                    "Java Runtime Version Mismatch",
                    "Minecraft or an installed mod was compiled for " + reqText
                            + ", which is newer than the active JVM runtime.",
                    findEvidence(lines, "unsupportedclassversionerror", "class file version"),
                    Arrays.asList(
                            "Open Instance Studio and select " + reqText + " (or Default auto-pick).",
                            "If a single mod requires newer Java than your Minecraft version, disable or downgrade that mod in Mod Manager."
                    ),
                    SuggestedAction.OPEN_INSTANCE_STUDIO
            );
        }

        // 2. Out of Memory (Java Heap / Direct Buffer / Native)
        if (lower.contains("java.lang.outofmemoryerror")
                || lower.contains("insufficient memory for the java runtime environment")
                || lower.contains("cannot allocate memory")) {
            boolean directOrNative = lower.contains("direct buffer") || lower.contains("insufficient memory")
                    || lower.contains("metaspace") || lower.contains("unable to create native thread");
            String advice = directOrNative
                    ? "Native/direct memory was exhausted. Slightly lower JVM heap RAM in Instance Studio so Android and the GPU driver have enough free OS memory, and lower in-game render distance."
                    : "The JVM heap ran out of memory. Increase instance RAM moderately in Instance Studio (leaving at least 1 GB free for Android), or reduce heavy mods/render distance.";
            return new Diagnosis(
                    Category.OUT_OF_MEMORY,
                    Confidence.HIGH,
                    directOrNative ? "Native / Direct Memory Exhausted" : "Java Heap Out of Memory",
                    advice,
                    findEvidence(lines, "outofmemoryerror", "insufficient memory", "cannot allocate memory"),
                    Arrays.asList(
                            "Adjust per-instance JVM RAM allocation in Instance Studio.",
                            "Close background Android apps and lower Minecraft render distance or texture resolution."
                    ),
                    SuggestedAction.OPEN_INSTANCE_STUDIO
            );
        }

        // 3. Duplicate Mods
        if (lower.contains("duplicatemodsfoundexception")
                || lower.contains("found duplicate mods")
                || lower.contains("mod id is duplicated")) {
            return new Diagnosis(
                    Category.DUPLICATE_MOD,
                    Confidence.HIGH,
                    "Duplicate Mods Detected",
                    "Two or more enabled mod JARs declare the same mod ID in this instance.",
                    findEvidence(lines, "duplicatemods", "duplicate mods", "duplicated"),
                    Arrays.asList(
                            "Open Mod Manager and disable or remove the duplicate mod copy.",
                            "Verify shared directory mode is off if mixing mods across versions."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER
            );
        }

        // 4. Missing Mod Dependency / Incompatible Mod Set
        if (lower.contains("missingmodsexception")
                || lower.contains("mod resolution encountered an incompatible mod set")
                || lower.contains("unmet dependency listing")
                || lower.contains("requires version") && lower.contains("modloadingexception")) {
            return new Diagnosis(
                    Category.MISSING_MOD_DEPENDENCY,
                    Confidence.HIGH,
                    "Missing or Incompatible Mod Dependency",
                    "The mod loader halted because an enabled mod is missing a required dependency (such as Fabric API) or expects a different Minecraft/loader version.",
                    findEvidence(lines, "incompatible mod set", "missingmodsexception", "requires ", "install ", "unmet dependency"),
                    Arrays.asList(
                            "Check the evidence lines for the required library (e.g., Fabric API, Cloth Config, Architectury) and install a matching version.",
                            "Open Mod Manager to inspect flagged mods and temporarily disable incompatible JARs."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER
            );
        }

        // 5. Mixin Injection / Bytecode Conflict
        if (lower.contains("mixinapplyerror")
                || lower.contains("mixintransformererror")
                || lower.contains("org.spongepowered.asm.mixin.transformer.throwables")
                || lower.contains("critical injection failure")) {
            return new Diagnosis(
                    Category.MIXIN_CONFLICT,
                    Confidence.HIGH,
                    "Mod Mixin Conflict",
                    "A mod failed while patching Minecraft classes — usually caused by two conflicting optimization/rendering mods or a mod built for a different patch release.",
                    findEvidence(lines, "critical injection failure", "mixinapplyerror", "mixintransformererror", "from mod "),
                    Arrays.asList(
                            "Identify the mod name mentioned in the Mixin stack trace and disable it in Mod Manager.",
                            "Ensure all installed mods match your exact Minecraft version (e.g., 1.21.1 vs 1.21.4)."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER
            );
        }

        // 6. Mod Loader Mismatch / Generic Mod Loading Error
        if (lower.contains("fml.modloadingexception") || lower.contains("net.fabricmc.loader.impl.formattedexception")) {
            return new Diagnosis(
                    Category.MOD_LOADER_MISMATCH,
                    Confidence.MEDIUM,
                    "Mod Loader Startup Failure",
                    "Forge, NeoForge, Fabric, or Quilt aborted while initializing installed mods.",
                    findEvidence(lines, "modloadingexception", "formattedexception", "failure message:"),
                    Arrays.asList(
                            "Open Mod Manager to verify all mods match this instance's mod loader and Minecraft version.",
                            "Save a mod snapshot and disable recently added mods to isolate the culprit."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER
            );
        }

        // 7. Shader Pack Compile / Compatibility Failure
        if (lower.contains("shadercompileexception")
                || lower.contains("failed to compile shader")
                || (lower.contains("net.irisshaders") && lower.contains("exception"))) {
            return new Diagnosis(
                    Category.SHADER_INCOMPATIBILITY,
                    Confidence.HIGH,
                    "Shader Compilation / Compatibility Failure",
                    "An active shader pack or shader pipeline failed to compile on the current mobile GPU renderer.",
                    findEvidence(lines, "shadercompileexception", "failed to compile shader", "irisshaders"),
                    Arrays.asList(
                            "Switch to a mobile-compatible renderer (LTW or Zink) in Instance Studio / Graphics Center.",
                            "Disable the active shader pack in Content Manager (Shaders tab) and retest."
                    ),
                    SuggestedAction.SWITCH_SAFE_RENDERER
            );
        }

        // 8. Renderer / OpenGL / EGL / Vulkan / GLFW Initialization Failure
        if (lower.contains("glfw error 65542")
                || lower.contains("glfw error 65543")
                || lower.contains("egl_bad_")
                || lower.contains("vk_error_")
                || lower.contains("failed to initialize vulkan")
                || (lower.contains("dlopen failed") && (lower.contains("gl") || lower.contains("vulkan") || lower.contains("zink") || lower.contains("ltw")))) {
            return new Diagnosis(
                    Category.RENDERER_OR_GL_FAILURE,
                    Confidence.HIGH,
                    "Graphics Renderer Initialization Failure",
                    "The selected OpenGL/Vulkan renderer could not create a compatible graphics context on this device or for this Minecraft version.",
                    findEvidence(lines, "glfw error", "egl_bad_", "vk_error_", "dlopen failed", "failed to initialize vulkan"),
                    Arrays.asList(
                            "Switch this instance to a compatible fallback renderer (LTW for 1.17+, GL4ES for legacy <=1.16.5, or Zink on Vulkan GPUs).",
                            "If using Sodium/Embeddium, ensure LTW or Zink is selected rather than GL4ES."
                    ),
                    SuggestedAction.SWITCH_SAFE_RENDERER
            );
        }

        // 9. Corrupt JAR / Asset / Checksum Failure
        if (lower.contains("java.util.zip.zipexception")
                || lower.contains("java.util.zip.ziperror")
                || lower.contains("sha-1 verification failed")
                || lower.contains("sha1 verif")
                || lower.contains("java.lang.classformaterror")) {
            return new Diagnosis(
                    Category.CORRUPT_FILE_OR_HASH,
                    Confidence.HIGH,
                    "Corrupted Archive or Failed File Verification",
                    "A game JAR, library, or mod archive is truncated, corrupted, or failed SHA-1 verification.",
                    findEvidence(lines, "zipexception", "ziperror", "verification failed", "classformaterror"),
                    Arrays.asList(
                            "Ensure file verification is enabled in Settings, clear partial download cache, and re-launch to re-download damaged game files.",
                            "Check Mod Manager for zero-byte or damaged local mod JARs."
                    ),
                    SuggestedAction.OPEN_DOWNLOAD_CENTER
            );
        }

        // 10. Network / TLS / Auth Error
        if (lower.contains("unknownhostexception")
                || lower.contains("sslhandshakeexception")
                || lower.contains("sockettimeoutexception")
                || lower.contains("http 401")
                || lower.contains("invalid session")) {
            return new Diagnosis(
                    Category.AUTH_OR_NETWORK_ERROR,
                    Confidence.MEDIUM,
                    "Network, TLS, or Session Error",
                    "A connection timed out, DNS/TLS failed, or an online authentication session expired.",
                    findEvidence(lines, "unknownhostexception", "sslhandshakeexception", "sockettimeoutexception", "http 401", "invalid session"),
                    Arrays.asList(
                            "Check your internet connection and device date/time (required for TLS certificates).",
                            "If signing in online failed, refresh your account in the Account Vault."
                    ),
                    SuggestedAction.NONE
            );
        }

        // 11. Native Signal Crash (SIGSEGV / SIGABRT / SIGBUS)
        if (lower.contains("sigsegv") || lower.contains("sigabrt") || lower.contains("sigbus")
                || lower.contains("fatal signal ") || lower.contains("a fatal error has been detected by the java runtime")) {
            return new Diagnosis(
                    Category.NATIVE_SIGNAL_CRASH,
                    Confidence.MEDIUM,
                    "Native Runtime / GPU Driver Crash (Signal)",
                    "The JVM, a native mod library, or the mobile GPU driver terminated with a native OS signal.",
                    findEvidence(lines, "sigsegv", "sigabrt", "sigbus", "fatal signal", "problematic frame"),
                    Arrays.asList(
                            "Try switching to Safe Compatibility or an alternate renderer (LTW / GL4ES / Zink) in Instance Studio.",
                            "Lower resolution scale or RAM allocation if your device was under heavy memory/thermal pressure."
                    ),
                    SuggestedAction.SWITCH_SAFE_RENDERER
            );
        }

        // 12. General unclassified crash vs healthy log
        boolean looksLikeError = lower.contains("exception") || lower.contains("crash report")
                || lower.contains("fatal") || lower.contains("exited with code");
        if (!looksLikeError) {
            return new Diagnosis(
                    Category.NO_CRASH_DETECTED,
                    Confidence.HIGH,
                    "No Crash Signature Detected",
                    "Log contains normal session output without known fatal error patterns.",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    SuggestedAction.NONE
            );
        }

        return new Diagnosis(
                Category.UNKNOWN,
                Confidence.LOW,
                "Unclassified Runtime Crash",
                "An error occurred, but no single high-confidence signature matched. Review the highlighted stack trace below before changing settings.",
                findEvidence(lines, "caused by:", "exception", "error", "fatal"),
                Arrays.asList(
                        "Inspect the first 'Caused by:' line in the log terminal below.",
                        "Share the automatically redacted log if reporting the issue to mod or launcher maintainers."
                ),
                SuggestedAction.OPEN_INSTANCE_STUDIO
        );
    }

    /** Maps JVM class file major version (e.g. 52, 60, 61, 65, 69) to Java release number (major - 44). */
    public static int classFileMajorToJava(int classMajor) {
        if (classMajor < 45) return -1;
        return classMajor - 44;
    }

    @NonNull
    private static List<String> findEvidence(@NonNull String[] lines, @NonNull String... needles) {
        List<String> matched = new ArrayList<>(3);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String low = trimmed.toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (low.contains(needle)) {
                    String clipped = trimmed.length() > 180 ? trimmed.substring(0, 177) + "..." : trimmed;
                    matched.add(clipped);
                    break;
                }
            }
            if (matched.size() >= 3) break;
        }
        return matched;
    }
}
