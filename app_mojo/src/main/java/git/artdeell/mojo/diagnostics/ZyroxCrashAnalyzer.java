package git.artdeell.mojo.diagnostics;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.content.InstanceContentManager;
import git.artdeell.mojo.downloader.RuntimeDriverStoreDialog;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.security.LogRedactor;

/**
 * Zyrox Guard — Deterministic local Minecraft &amp; JVM crash analyzer with one-tap actionable,
 * non-destructive auto-fixes (disable crashing mod JAR, rebalance JVM heap RAM, switch JRE via
 * {@link JavaRuntimeAdvisor}, or apply safe/architecture-matched GPU renderer preset).
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

    /** Concrete one-tap auto-fix operations surfaced on {@code ShowErrorActivity}, {@code FatalErrorActivity} &amp; {@code ExitActivity}. */
    public enum AutoFixType {
        INSTALL_MISSING_DEPENDENCY,
        DISABLE_CRASHING_MOD,
        ADJUST_JVM_HEAP,
        SWITCH_JRE_VERSION,
        APPLY_SAFE_RENDERER,
        DISABLE_ACTIVE_SHADERPACK
    }

    /** Descriptor for a single reversible one-tap auto-fix button. */
    public static final class AutoFix {
        @NonNull public final AutoFixType type;
        @NonNull public final String buttonLabel;
        @Nullable public final String targetModToken;
        public final int requiredJavaMajor;
        public final boolean nativeOrDirectOom;

        public AutoFix(@NonNull AutoFixType type,
                       @NonNull String buttonLabel,
                       @Nullable String targetModToken,
                       int requiredJavaMajor,
                       boolean nativeOrDirectOom) {
            this.type = type;
            this.buttonLabel = buttonLabel;
            this.targetModToken = targetModToken;
            this.requiredJavaMajor = requiredJavaMajor;
            this.nativeOrDirectOom = nativeOrDirectOom;
        }
    }

    /** Result returned after executing a one-tap {@link AutoFix}. */
    public static final class AutoFixResult {
        public final boolean applied;
        public final boolean openedExternalFlow;
        @NonNull public final String message;

        public AutoFixResult(boolean applied, boolean openedExternalFlow, @NonNull String message) {
            this.applied = applied;
            this.openedExternalFlow = openedExternalFlow;
            this.message = message;
        }
    }

    public static final class Diagnosis {
        @NonNull public final Category category;
        @NonNull public final Confidence confidence;
        @NonNull public final String title;
        @NonNull public final String explanation;
        @NonNull public final List<String> evidenceLines;
        @NonNull public final List<String> recommendedSteps;
        @NonNull public final SuggestedAction primaryAction;
        @NonNull public final List<AutoFix> autoFixes;

        public Diagnosis(@NonNull Category category,
                         @NonNull Confidence confidence,
                         @NonNull String title,
                         @NonNull String explanation,
                         @NonNull List<String> evidenceLines,
                         @NonNull List<String> recommendedSteps,
                         @NonNull SuggestedAction primaryAction) {
            this(category, confidence, title, explanation, evidenceLines, recommendedSteps,
                    primaryAction, Collections.emptyList());
        }

        public Diagnosis(@NonNull Category category,
                         @NonNull Confidence confidence,
                         @NonNull String title,
                         @NonNull String explanation,
                         @NonNull List<String> evidenceLines,
                         @NonNull List<String> recommendedSteps,
                         @NonNull SuggestedAction primaryAction,
                         @NonNull List<AutoFix> autoFixes) {
            this.category = category;
            this.confidence = confidence;
            this.title = title;
            this.explanation = explanation;
            this.evidenceLines = Collections.unmodifiableList(evidenceLines);
            this.recommendedSteps = Collections.unmodifiableList(recommendedSteps);
            this.primaryAction = primaryAction;
            this.autoFixes = Collections.unmodifiableList(new ArrayList<>(autoFixes));
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
            "class file version\\s+(\\d+)(?:\\.\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOD_JAR_PATTERN = Pattern.compile(
            "(?:Mod File:\\s*|mods[/\\\\]|~\\[)([A-Za-z0-9._+\\-]{2,96}\\.jar)",
            Pattern.CASE_INSENSITIVE);
    // Only explicit mod attributions. The previous "in\s+<word>" alternative matched ordinary
    // English ("Exception in thread", "in main") and produced bogus culprits for auto-disable.
    private static final Pattern MIXIN_FROM_MOD_PATTERN = Pattern.compile(
            "(?:from mod\\s+'?([a-z0-9_\\-]{3,48})'?|\\b([a-z0-9_\\-]{3,48})\\.mixins\\.json)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FABRIC_MOD_ID_PATTERN = Pattern.compile(
            "(?:Mod\\s+'|mod\\s+'|Provided by\\s+')([a-z0-9_\\-]{3,48})'",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> PROTECTED_CORE_TOKENS = Arrays.asList(
            "minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader",
            "forge", "neoforge", "fml", "mixin", "mixinextras", "sponge", "lwjgl", "client",
            "authlib", "jopt-simple", "guava", "gson", "netty", "log4j", "slf4j");

    private ZyroxCrashAnalyzer() {
    }

    /**
     * Extracts a probable culprit mod JAR filename or mod ID from a crash log, ignoring core
     * Minecraft / loader / LWJGL frames so auto-disable never touches engine jars.
     */
    @Nullable
    public static String extractCulpritModToken(@Nullable String logText) {
        if (logText == null || logText.isEmpty()) return null;

        Matcher jarMatcher = MOD_JAR_PATTERN.matcher(logText);
        while (jarMatcher.find()) {
            String jar = jarMatcher.group(1);
            if (jar != null && !isProtectedCoreToken(jar)) {
                return jar;
            }
        }
        Matcher mixinMatcher = MIXIN_FROM_MOD_PATTERN.matcher(logText);
        while (mixinMatcher.find()) {
            String modId = mixinMatcher.group(1) != null ? mixinMatcher.group(1) : mixinMatcher.group(2);
            if (modId != null && !isProtectedCoreToken(modId)) {
                return modId;
            }
        }
        Matcher fabricMatcher = FABRIC_MOD_ID_PATTERN.matcher(logText);
        while (fabricMatcher.find()) {
            String modId = fabricMatcher.group(1);
            if (modId != null && !isProtectedCoreToken(modId)) {
                return modId;
            }
        }
        return null;
    }

    private static boolean isProtectedCoreToken(@NonNull String token) {
        String low = token.toLowerCase(Locale.ROOT).replace(".jar", "");
        for (String core : PROTECTED_CORE_TOKENS) {
            if (low.equals(core) || low.startsWith(core + "-")) return true;
        }
        return false;
    }

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
                    SuggestedAction.NONE);
        }

        String safeText = LogRedactor.redact(rawLog);
        String lower = safeText.toLowerCase(Locale.ROOT);
        String[] lines = safeText.split("\\r?\\n");
        String culpritMod = extractCulpritModToken(safeText);

        // 1. Java Class File / Runtime Version Mismatch
        if (lower.contains("unsupportedclassversionerror")
                || lower.contains("has been compiled by a more recent version of the java runtime")) {
            Matcher m = CLASS_VERSION_PATTERN.matcher(safeText);
            int requiredJava = 21;
            if (m.find()) {
                try {
                    int parsed = classFileMajorToJava(Integer.parseInt(m.group(1)));
                    if (parsed > 0) requiredJava = parsed;
                } catch (NumberFormatException ignored) {
                }
            }
            String reqText = "Java " + requiredJava;
            List<AutoFix> fixes = new ArrayList<>(2);
            fixes.add(new AutoFix(AutoFixType.SWITCH_JRE_VERSION,
                    "Switch to " + reqText, null, requiredJava, false));
            if (culpritMod != null) {
                fixes.add(buildDisableModFix(culpritMod));
            }
            return new Diagnosis(
                    Category.JAVA_VERSION_MISMATCH,
                    Confidence.HIGH,
                    "Java Runtime Version Mismatch",
                    "Minecraft or an installed mod was compiled for " + reqText
                            + ", which is newer than the active JVM runtime.",
                    findEvidence(lines, "unsupportedclassversionerror", "class file version"),
                    Arrays.asList(
                            "Tap 'Switch to " + reqText + "' to auto-select or install a compatible JRE.",
                            "If a single mod requires newer Java than your Minecraft version, disable that mod."
                    ),
                    SuggestedAction.OPEN_INSTANCE_STUDIO,
                    fixes);
        }

        // 2. Out of Memory (Java Heap vs Direct/Native)
        if (lower.contains("java.lang.outofmemoryerror")
                || lower.contains("insufficient memory for the java runtime environment")
                || lower.contains("cannot allocate memory")) {
            boolean directOrNative = lower.contains("direct buffer")
                    || lower.contains("insufficient memory")
                    || lower.contains("metaspace")
                    || lower.contains("unable to create native thread");
            String advice = directOrNative
                    ? "Native/direct memory was exhausted. Lower JVM heap RAM slightly so Android and the GPU driver retain free headroom."
                    : "The JVM heap ran out of memory. Increase instance JVM heap RAM safely or disable heavy mods.";
            String btnLabel = directOrNative ? "Rebalance Heap RAM (-20%)" : "Boost Heap RAM (+35%)";
            List<AutoFix> fixes = new ArrayList<>(2);
            fixes.add(new AutoFix(AutoFixType.ADJUST_JVM_HEAP, btnLabel, null, -1, directOrNative));
            if (culpritMod != null) {
                fixes.add(buildDisableModFix(culpritMod));
            }
            return new Diagnosis(
                    Category.OUT_OF_MEMORY,
                    Confidence.HIGH,
                    directOrNative ? "Native / Direct Memory Exhausted" : "Java Heap Out of Memory",
                    advice,
                    findEvidence(lines, "outofmemoryerror", "insufficient memory", "cannot allocate memory"),
                    Arrays.asList(
                            "Tap '" + btnLabel + "' to automatically calibrate per-instance JVM memory.",
                            "Close background apps and lower Minecraft render distance."
                    ),
                    SuggestedAction.OPEN_INSTANCE_STUDIO,
                    fixes);
        }

        // 3. Duplicate Mods
        if (lower.contains("duplicatemodsfoundexception")
                || lower.contains("found duplicate mods")
                || lower.contains("duplicate mods found")
                || lower.contains("mod id is duplicated")) {
            List<AutoFix> fixes = culpritMod != null
                    ? Collections.singletonList(buildDisableModFix(culpritMod))
                    : Collections.emptyList();
            return new Diagnosis(
                    Category.DUPLICATE_MOD,
                    Confidence.HIGH,
                    "Duplicate Mods Detected",
                    "Two or more enabled mod JARs declare the same mod ID in this instance.",
                    findEvidence(lines, "duplicatemods", "duplicate mods", "duplicated"),
                    Arrays.asList(
                            "Disable the duplicate mod copy in 1 tap or via Mod Manager.",
                            "Verify shared directory mode is off if mixing mods across versions."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER,
                    fixes);
        }

        // 4. Missing Mod Dependency / Incompatible Mod Set
        if (lower.contains("missingmodsexception")
                || lower.contains("mod resolution encountered an incompatible mod set")
                || lower.contains("unmet dependency listing")
                || (lower.contains("requires version") && lower.contains("modloadingexception"))
                || (lower.contains("requires ") && lower.contains("which is missing"))) {
            String missingSlug = extractMissingDependencySlug(safeText);
            List<AutoFix> fixes = new ArrayList<>(2);
            if (missingSlug != null) {
                fixes.add(new AutoFix(
                        AutoFixType.INSTALL_MISSING_DEPENDENCY,
                        "Install " + shortenToken(missingSlug),
                        missingSlug,
                        -1,
                        false));
            }
            if (culpritMod != null && (missingSlug == null
                    || !culpritMod.equalsIgnoreCase(missingSlug))) {
                fixes.add(buildDisableModFix(culpritMod));
            }
            String friendlyDesc = missingSlug != null
                    ? "An enabled mod requires '" + missingSlug
                    + "', which is not installed yet. Tap Fix & Relaunch to download it automatically from Modrinth."
                    : "An enabled mod is missing a required dependency (such as Fabric API) or targets another Minecraft version.";
            return new Diagnosis(
                    Category.MISSING_MOD_DEPENDENCY,
                    Confidence.HIGH,
                    missingSlug != null
                            ? "Missing Mod Dependency (" + missingSlug + ")"
                            : "Missing or Incompatible Mod Dependency",
                    friendlyDesc,
                    findEvidence(lines, "incompatible mod set", "missingmodsexception", "requires ", "install ", "unmet dependency"),
                    Arrays.asList(
                            missingSlug != null
                                    ? "Tap '1-Tap Fix & Relaunch' to install '" + missingSlug + "' automatically."
                                    : "Install the required dependency listed below, or tap Disable Mod to launch immediately.",
                            "Open Mod Manager to verify all mods match your Minecraft version."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER,
                    fixes);
        }

        // 5. Mixin Injection Conflict
        if (lower.contains("mixinapplyerror")
                || lower.contains("mixintransformererror")
                || lower.contains("org.spongepowered.asm.mixin.transformer.throwables")
                || lower.contains("critical injection failure")) {
            List<AutoFix> fixes = culpritMod != null
                    ? Collections.singletonList(buildDisableModFix(culpritMod))
                    : Collections.emptyList();
            return new Diagnosis(
                    Category.MIXIN_CONFLICT,
                    Confidence.HIGH,
                    "Mod Mixin Conflict" + (culpritMod != null ? " (" + culpritMod + ")" : ""),
                    "A mod failed while patching Minecraft bytecode — typically an incompatible mod release or conflicting optimization mod.",
                    findEvidence(lines, "critical injection failure", "mixinapplyerror", "mixintransformererror", "from mod "),
                    Arrays.asList(
                            culpritMod != null
                                    ? "Tap 'Disable " + shortenToken(culpritMod) + "' to quarantine the crashing mod (.jar.disabled)."
                                    : "Disable the conflicting mod mentioned in the Mixin stack trace.",
                            "Ensure all installed mods match your exact Minecraft patch release."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER,
                    fixes);
        }

        // 6. Mod Loader Startup Failure
        if (lower.contains("fml.modloadingexception")
                || lower.contains("net.fabricmc.loader.impl.formattedexception")) {
            List<AutoFix> fixes = new ArrayList<>(2);
            if (culpritMod != null) fixes.add(buildDisableModFix(culpritMod));
            if (lower.contains("inaccessibleobjectexception") || lower.contains("illegalaccessexception")) {
                fixes.add(new AutoFix(AutoFixType.SWITCH_JRE_VERSION, "Switch to Java 8", null, 8, false));
            }
            return new Diagnosis(
                    Category.MOD_LOADER_MISMATCH,
                    Confidence.MEDIUM,
                    "Mod Loader Startup Failure",
                    "Forge, NeoForge, Fabric, or Quilt aborted while initializing installed mods.",
                    findEvidence(lines, "modloadingexception", "formattedexception", "failure message:"),
                    Arrays.asList(
                            "Disable the offending mod in 1 tap or review enabled JARs in Mod Manager.",
                            "Save a mod snapshot before changing multiple mods."
                    ),
                    SuggestedAction.OPEN_MOD_MANAGER,
                    fixes);
        }

        // 7. Shader Pack Compile Failure
        if (lower.contains("shadercompileexception")
                || lower.contains("failed to compile shader")
                || (lower.contains("net.irisshaders") && lower.contains("exception"))) {
            List<AutoFix> fixes = new ArrayList<>(2);
            fixes.add(new AutoFix(AutoFixType.DISABLE_ACTIVE_SHADERPACK,
                    "Disable Active Shader", null, -1, false));
            fixes.add(new AutoFix(AutoFixType.APPLY_SAFE_RENDERER,
                    "Apply Optimal GPU Preset", null, -1, false));
            return new Diagnosis(
                    Category.SHADER_INCOMPATIBILITY,
                    Confidence.HIGH,
                    "Shader Compilation / Compatibility Failure",
                    "An active shader pack failed to compile on the current mobile GPU pipeline.",
                    findEvidence(lines, "shadercompileexception", "failed to compile shader", "irisshaders"),
                    Arrays.asList(
                            "Tap 'Disable Active Shader' or 'Apply Optimal GPU Preset' (Zink + Turnip / LTW).",
                            "Ensure your shader pack supports mobile OpenGL 4.6 (Zink) or Iris LTW."
                    ),
                    SuggestedAction.SWITCH_SAFE_RENDERER,
                    fixes);
        }

        // 8. Renderer / GL / EGL / Vulkan / GLFW Failure
        if (lower.contains("glfw error 65542")
                || lower.contains("glfw error 65543")
                || lower.contains("egl_bad_")
                || lower.contains("vk_error_")
                || lower.contains("failed to initialize vulkan")
                || (lower.contains("dlopen failed") && (lower.contains("gl")
                || lower.contains("vulkan") || lower.contains("zink") || lower.contains("ltw")))) {
            List<AutoFix> fixes = Collections.singletonList(
                    new AutoFix(AutoFixType.APPLY_SAFE_RENDERER,
                            "Auto-Fix GPU Renderer", null, -1, false));
            return new Diagnosis(
                    Category.RENDERER_OR_GL_FAILURE,
                    Confidence.HIGH,
                    "Graphics Renderer Initialization Failure",
                    "The selected OpenGL/Vulkan renderer or driver failed to initialize on this GPU.",
                    findEvidence(lines, "glfw error", "egl_bad_", "vk_error_", "dlopen failed", "failed to initialize vulkan"),
                    Arrays.asList(
                            "Tap 'Auto-Fix GPU Renderer' to switch to a verified renderer for your GPU architecture.",
                            "If using Sodium/Embeddium, ensure LTW or Zink is selected instead of GL4ES."
                    ),
                    SuggestedAction.SWITCH_SAFE_RENDERER,
                    fixes);
        }

        // 9. Corrupt JAR / Checksum Failure
        if (lower.contains("java.util.zip.zipexception")
                || lower.contains("java.util.zip.ziperror")
                || lower.contains("sha-1 verification failed")
                || lower.contains("sha1 verif")
                || lower.contains("java.lang.classformaterror")) {
            List<AutoFix> fixes = culpritMod != null
                    ? Collections.singletonList(buildDisableModFix(culpritMod))
                    : Collections.emptyList();
            return new Diagnosis(
                    Category.CORRUPT_FILE_OR_HASH,
                    Confidence.HIGH,
                    "Corrupted Archive or Failed File Verification",
                    "A game JAR, library, or mod archive is truncated, corrupted, or failed SHA-1 verification.",
                    findEvidence(lines, "zipexception", "ziperror", "verification failed", "classformaterror"),
                    Arrays.asList(
                            "Disable any corrupted mod JAR or re-launch with file verification enabled.",
                            "Check Mod Manager for zero-byte mod archives."
                    ),
                    SuggestedAction.OPEN_DOWNLOAD_CENTER,
                    fixes);
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
                    findEvidence(lines, "unknownhostexception", "sslhandshakeexception",
                            "sockettimeoutexception", "http 401", "invalid session"),
                    Arrays.asList(
                            "Check your internet connection and device date/time.",
                            "Refresh your account in the Account Vault if online sign-in expired."
                    ),
                    SuggestedAction.NONE);
        }

        // 11. Native Signal Crash
        if (lower.contains("sigsegv") || lower.contains("sigabrt") || lower.contains("sigbus")
                || lower.contains("fatal signal ")
                || lower.contains("a fatal error has been detected by the java runtime")) {
            List<AutoFix> fixes = Arrays.asList(
                    new AutoFix(AutoFixType.APPLY_SAFE_RENDERER,
                            "Safe GPU Preset", null, -1, false),
                    new AutoFix(AutoFixType.ADJUST_JVM_HEAP,
                            "Rebalance Heap RAM", null, -1, true));
            return new Diagnosis(
                    Category.NATIVE_SIGNAL_CRASH,
                    Confidence.MEDIUM,
                    "Native Runtime / GPU Driver Crash (Signal)",
                    "The JVM, a native mod library, or the GPU driver terminated with an OS signal.",
                    findEvidence(lines, "sigsegv", "sigabrt", "sigbus", "fatal signal", "problematic frame"),
                    Arrays.asList(
                            "Tap 'Safe GPU Preset' to reset custom Vulkan/renderer overrides to a verified config.",
                            "Tap 'Rebalance Heap RAM' if native driver memory was exhausted."
                    ),
                    SuggestedAction.SWITCH_SAFE_RENDERER,
                    fixes);
        }

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
                    SuggestedAction.NONE);
        }

        List<AutoFix> genericFixes = new ArrayList<>();
        if (culpritMod != null) genericFixes.add(buildDisableModFix(culpritMod));
        return new Diagnosis(
                Category.UNKNOWN,
                Confidence.LOW,
                "Unclassified Runtime Crash",
                "An error occurred without a single high-confidence signature. Review the highlighted stack trace below.",
                findEvidence(lines, "caused by:", "exception", "error", "fatal"),
                Arrays.asList(
                        "Inspect the first 'Caused by:' line in the log terminal below.",
                        "Share the redacted log if reporting the issue to mod maintainers."
                ),
                SuggestedAction.OPEN_INSTANCE_STUDIO,
                genericFixes);
    }

    /** Simple alias result for callers like {@code LogInspectorFragment}. */
    public static final class FixOutcome {
        public final boolean applied;
        @NonNull public final String message;

        public FixOutcome(boolean applied, @NonNull String message) {
            this.applied = applied;
            this.message = message;
        }
    }

    /** Returns {@code true} when {@code diagnosis} offers an executable 1-tap auto-fix remedy. */
    public static boolean supportsOneTapFix(@Nullable Diagnosis diagnosis) {
        return diagnosis != null && !diagnosis.autoFixes.isEmpty();
    }

    /** Returns a clear, beginner-friendly label for the primary 1-Tap Fix &amp; Relaunch button. */
    @NonNull
    public static String getOneTapFixButtonLabel(@Nullable Diagnosis diagnosis) {
        if (diagnosis == null || diagnosis.autoFixes.isEmpty()) {
            return "1-Tap Fix & Relaunch";
        }
        return "1-Tap Fix & Relaunch (" + diagnosis.autoFixes.get(0).buttonLabel + ")";
    }

    /** Executes the highest-confidence primary {@link AutoFix} for {@code diagnosis}. */
    @NonNull
    public static FixOutcome executeOneTapFix(@NonNull Context context,
                                              @NonNull Diagnosis diagnosis) {
        if (diagnosis.autoFixes.isEmpty()) {
            return new FixOutcome(false, "No automatic fix available for this error.");
        }
        AutoFixResult res = executeAutoFix(context, diagnosis.autoFixes.get(0), null);
        return new FixOutcome(res.applied, res.message);
    }

    /** Relaunches {@link git.artdeell.mojo.LauncherActivity} and immediately starts the fixed game. */
    public static void relaunchFixedInstance(@NonNull android.app.Activity hostActivity) {
        android.content.Intent intent = new android.content.Intent(
                hostActivity, git.artdeell.mojo.LauncherActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(git.artdeell.mojo.LauncherActivity.EXTRA_AUTO_LAUNCH_ON_START, true);
        hostActivity.startActivity(intent);
        hostActivity.finishAndRemoveTask();
    }

    private static final Pattern MISSING_DEP_PATTERN = Pattern.compile(
            "(?i)(?:requires\\s+(?:any\\s+version\\s+of\\s+|version\\s+[^\\s]+\\s+of\\s+)?['\"]?([a-z0-9_\\-]{3,40})['\"]?"
                    + "|Install\\s+([a-z0-9_\\-]{3,40})(?:,|\\s+version|\\s+or)"
                    + "|Mod\\s+ID:\\s*['\"]([a-z0-9_\\-]{3,40})['\"]"
                    + "|Missing\\s+(?:mandatory\\s+)?dependency[:\\s]+['\"]?([a-z0-9_\\-]{3,40})['\"]?)");

    /** Extracts and maps a missing mod ID from Fabric/Quilt/Forge/NeoForge logs to a Modrinth slug. */
    @Nullable
    public static String extractMissingDependencySlug(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = MISSING_DEP_PATTERN.matcher(text);
        while (m.find()) {
            String raw = null;
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    raw = m.group(g);
                    break;
                }
            }
            if (raw == null) continue;
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if ("minecraft".equals(id) || "java".equals(id) || "fabricloader".equals(id)
                    || "forge".equals(id) || "neoforge".equals(id) || "quilt_loader".equals(id)
                    || "version".equals(id) || "any".equals(id) || "mod".equals(id)) {
                continue;
            }
            switch (id) {
                case "fabric":
                case "fabric-api":
                case "fabric_api":
                    return "fabric-api";
                case "cloth-config":
                case "cloth-config2":
                case "cloth_config":
                    return "cloth-config";
                case "architectury":
                case "architectury-api":
                    return "architectury-api";
                case "yet_another_config_lib_v3":
                case "yet-another-config-lib":
                case "yacl":
                    return "yacl";
                case "forgeconfigapiport":
                case "forge-config-api-port":
                    return "forge-config-api-port";
                case "quilted_fabric_api":
                case "qsl":
                    return "qsl";
                default:
                    return id.replace('_', '-');
            }
        }
        return null;
    }

    /**
     * Executes a one-tap reversible {@link AutoFix} on {@code instance} (loading the currently
     * selected instance when {@code null}). Never permanently deletes user files.
     */
    @NonNull
    public static AutoFixResult executeAutoFix(@NonNull Context context,
                                               @NonNull AutoFix fix,
                                               @Nullable Instance instance) {
        Instance target = instance;
        if (target == null) {
            try {
                target = Instances.loadSelectedInstance();
            } catch (Throwable ignored) {
            }
        }

        switch (fix.type) {
            case INSTALL_MISSING_DEPENDENCY: {
                if (target == null || fix.targetModToken == null || fix.targetModToken.isEmpty()) {
                    return new AutoFixResult(false, false, "No active instance found for dependency install.");
                }
                try {
                    git.artdeell.mojo.instances.InstanceBackupService.createPreModSnapshotQuietly(
                            target, "auto-fix-dep");
                    git.artdeell.mojo.content.ModrinthContentInstaller.InstallSummary summary =
                            git.artdeell.mojo.content.ModrinthContentInstaller.installModBySlug(
                                    target, fix.targetModToken, null);
                    return new AutoFixResult(summary.installedCount > 0, false, summary.message);
                } catch (Exception e) {
                    return new AutoFixResult(false, false,
                            "Could not auto-install '" + fix.targetModToken + "': "
                                    + LogRedactor.redact(e.getMessage()));
                }
            }

            case DISABLE_CRASHING_MOD: {
                if (target == null || fix.targetModToken == null) {
                    return new AutoFixResult(false, false, "No active instance found for mod quarantine.");
                }
                git.artdeell.mojo.instances.InstanceBackupService.createPreModSnapshotQuietly(
                        target, "auto-fix-disable");
                File disabled = disableMatchingModInDir(
                        new File(target.getGameDirectory(), "mods"), fix.targetModToken);
                if (disabled != null) {
                    String origName = disabled.getName().replaceAll("(?i)\\.disabled$", "");
                    return new AutoFixResult(true, false,
                            "Disabled " + origName + " (renamed to .disabled — re-enable anytime in Mod Manager).");
                }
                return new AutoFixResult(false, false,
                        "Could not match '" + fix.targetModToken + "' in mods/ — disable manually in Mod Manager.");
            }

            case ADJUST_JVM_HEAP: {
                int totalRam = Tools.getTotalDeviceMemory(context);
                int currentHeap = target != null
                        ? target.getEffectiveRamAllocationMb()
                        : Math.max(512, LauncherPreferences.PREF_RAM_ALLOCATION);
                int newHeap = JavaRuntimeAdvisor.computeAutoFixedHeapMb(
                        currentHeap, totalRam, fix.nativeOrDirectOom);
                if (target != null) {
                    target.ramAllocationMb = newHeap;
                    target.maybeWrite();
                } else {
                    LauncherPreferences.PREF_RAM_ALLOCATION = newHeap;
                    if (LauncherPreferences.DEFAULT_PREF != null) {
                        LauncherPreferences.DEFAULT_PREF.edit()
                                .putInt("allocation", newHeap).apply();
                    }
                }
                return new AutoFixResult(true, false,
                        "Adjusted JVM heap RAM: " + currentHeap + " MB -> " + newHeap + " MB.");
            }

            case SWITCH_JRE_VERSION: {
                int reqJava = Math.max(8, fix.requiredJavaMajor);
                String arch = Architecture.archAsString(Architecture.getDeviceArchitecture());
                List<Runtime> installed = MultiRTUtils.getRuntimes();
                Runtime switched = JavaRuntimeAdvisor.switchToCompatibleRuntime(
                        target, reqJava, installed, arch);
                if (switched == null) {
                    // Retry without strict arch tag in case user imported multiarch runtime
                    switched = JavaRuntimeAdvisor.switchToCompatibleRuntime(
                            target, reqJava, installed, null);
                }
                if (switched != null) {
                    return new AutoFixResult(true, false,
                            "Switched runtime to " + switched.name
                                    + " (Java " + switched.javaVersion + "). Ready to relaunch!");
                }
                RuntimeDriverStoreDialog.showJreStore(context, null);
                return new AutoFixResult(false, true,
                        "Java " + reqJava + " is not installed yet — opened 1-click OpenJDK Store.");
            }

            case DISABLE_ACTIVE_SHADERPACK: {
                if (target == null) {
                    return new AutoFixResult(false, false, "No active instance found.");
                }
                File optionsShader = new File(target.getGameDirectory(), "config/iris.properties");
                if (optionsShader.isFile()) {
                    try {
                        String txt = InstanceContentManager.readConfigFileSafe(optionsShader);
                        String patched = txt.replaceAll("(?m)^enableShaders\\s*=\\s*true", "enableShaders=false")
                                .replaceAll("(?m)^shaderPack\\s*=.*$", "shaderPack=(off)");
                        InstanceContentManager.writeConfigFileWithBackup(optionsShader, patched);
                        return new AutoFixResult(true, false,
                                "Disabled active Iris shader pack in config/iris.properties (.bak saved).");
                    } catch (Exception ignored) {
                    }
                }
                return applyGpuRecovery(context, target);
            }

            case APPLY_SAFE_RENDERER:
            default:
                return applyGpuRecovery(context, target);
        }
    }

    /**
     * Non-destructively quarantines a matching {@code .jar} inside {@code modsDir} by renaming it
     * to {@code .jar.disabled}. Returns the new disabled file, or {@code null} if no match.
     */
    @Nullable
    public static File disableMatchingModInDir(@Nullable File modsDir, @NonNull String token) {
        File target = findUniqueModMatch(modsDir, token);
        if (target == null) return null;
        File dst = uniqueDisabledName(modsDir, target.getName());
        return target.renameTo(dst) ? dst : null;
    }

    /**
     * Finds the single {@code .jar} matching {@code token}. Matching is tiered — exact filename,
     * then normalized prefix, then normalized substring (min 4 chars) — and a tier only wins if it
     * yields exactly ONE jar. Ambiguous matches return {@code null} so auto-fix never guesses which
     * mod to disable. Protected core/loader jars are never returned.
     */
    @Nullable
    static File findUniqueModMatch(@Nullable File modsDir, @NonNull String token) {
        if (modsDir == null || !modsDir.isDirectory()) return null;
        File[] files = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null || files.length == 0) return null;

        String needle = token.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || isProtectedCoreToken(needle)) return null;
        String needleNormalized = normalizeModName(needle);
        if (needleNormalized.length() < 3) return null;

        List<File> exact = new ArrayList<>();
        List<File> prefix = new ArrayList<>();
        List<File> contains = new ArrayList<>();
        for (File f : files) {
            String lowName = f.getName().toLowerCase(Locale.ROOT);
            if (isProtectedCoreToken(lowName)) continue;
            String normName = normalizeModName(lowName);
            if (lowName.equals(needle) || lowName.equals(needle + ".jar")) {
                exact.add(f);
            } else if (normName.startsWith(needleNormalized)) {
                prefix.add(f);
            } else if (needleNormalized.length() >= 4 && normName.contains(needleNormalized)) {
                contains.add(f);
            }
        }
        if (exact.size() == 1) return exact.get(0);
        if (!exact.isEmpty()) return null;
        if (prefix.size() == 1) return prefix.get(0);
        if (!prefix.isEmpty()) return null;
        return contains.size() == 1 ? contains.get(0) : null;
    }

    @NonNull
    private static String normalizeModName(@NonNull String lowName) {
        String n = lowName.endsWith(".jar") ? lowName.substring(0, lowName.length() - 4) : lowName;
        return n.replace("_", "").replace("-", "").replace(" ", "");
    }

    /** Never overwrite an existing quarantined copy (File.renameTo replaces targets on Linux). */
    @NonNull
    private static File uniqueDisabledName(@NonNull File modsDir, @NonNull String jarName) {
        File dst = new File(modsDir, jarName + ".disabled");
        String base = jarName.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? jarName.substring(0, jarName.length() - 4) : jarName;
        int i = 1;
        while (dst.exists()) {
            // Keeps the ".jar.disabled" suffix so Mod Manager can still re-enable it as a .jar.
            dst = new File(modsDir, base + "~" + i + ".jar.disabled");
            i++;
        }
        return dst;
    }

    @NonNull
    private static AutoFixResult applyGpuRecovery(@NonNull Context context,
                                                  @Nullable Instance target) {
        if (target != null) {
            target.rendererLocked = false;
            // Clear any broken custom per-instance Turnip override
            target.vulkanDriver = null;
            if (InstanceManager.restoreLastKnownGoodRenderer(target)) {
                GraphicsAdvisor.applyProfile(GraphicsAdvisor.Profile.SAFE_COMPATIBILITY);
                return new AutoFixResult(true, false,
                        "Restored last-known-good renderer (" + target.renderer
                                + ") & Safe Compatibility scaling.");
            }
        }
        GraphicsAdvisor.RendererPreset preset = GraphicsAdvisor.applyOptimalPreset(
                context, target, target != null ? target.versionId : null);
        return new AutoFixResult(true, false,
                "Applied " + preset.presetName + " (" + preset.rendererId + ").");
    }

    @NonNull
    private static AutoFix buildDisableModFix(@NonNull String token) {
        return new AutoFix(AutoFixType.DISABLE_CRASHING_MOD,
                "Disable " + shortenToken(token), token, -1, false);
    }

    @NonNull
    private static String shortenToken(@NonNull String token) {
        String clean = token.replaceAll("(?i)\\.jar$", "");
        return clean.length() > 22 ? clean.substring(0, 20) + "…" : clean;
    }

    /** Maps JVM class file major version (e.g. 52→8, 61→17, 65→21, 69→25). */
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
                    String clipped = trimmed.length() > 180
                            ? trimmed.substring(0, 177) + "..." : trimmed;
                    matched.add(clipped);
                    break;
                }
            }
            if (matched.size() >= 3) break;
        }
        return matched;
    }
}
