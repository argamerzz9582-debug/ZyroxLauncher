package git.artdeell.mojo.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.multirt.Runtime;

/**
 * Unit tests for {@link ZyroxCrashAnalyzer} (Zyrox Guard) and {@link JavaRuntimeAdvisor}.
 */
public class ZyroxCrashAnalyzerTest {

    @Test
    public void classifiesJavaVersionMismatchForJava21AndFutureJava25() {
        String java21Crash = "Exception in thread \"main\" java.lang.UnsupportedClassVersionError: "
                + "net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime "
                + "(class file version 65.0), this version of the Java Runtime only recognizes up to 61.0";
        ZyroxCrashAnalyzer.Diagnosis d21 = ZyroxCrashAnalyzer.analyze(java21Crash);
        assertEquals(ZyroxCrashAnalyzer.Category.JAVA_VERSION_MISMATCH, d21.category);
        assertEquals(ZyroxCrashAnalyzer.Confidence.HIGH, d21.confidence);
        assertTrue(d21.explanation.contains("Java 21"));

        // Future Java 25 = class file major 69 (non-hardcoded arithmetic)
        assertEquals(25, ZyroxCrashAnalyzer.classFileMajorToJava(69));
    }

    @Test
    public void classifiesOomMixinMissingDepShaderRendererAndNativeSignals() {
        assertEquals(ZyroxCrashAnalyzer.Category.OUT_OF_MEMORY,
                ZyroxCrashAnalyzer.analyze("java.lang.OutOfMemoryError: Java heap space").category);

        assertEquals(ZyroxCrashAnalyzer.Category.MISSING_MOD_DEPENDENCY,
                ZyroxCrashAnalyzer.analyze("net.fabricmc.loader.impl.FormattedException: "
                        + "Mod resolution encountered an incompatible mod set!\n"
                        + "\t- Mod 'Sodium' requires version 0.100.0 or later of fabric-api").category);

        assertEquals(ZyroxCrashAnalyzer.Category.DUPLICATE_MOD,
                ZyroxCrashAnalyzer.analyze("net.minecraftforge.fml.loading.EarlyLoadingException: Duplicate mods found").category);

        assertEquals(ZyroxCrashAnalyzer.Category.MIXIN_CONFLICT,
                ZyroxCrashAnalyzer.analyze("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: "
                        + "Critical injection failure: @Inject in sodium.mixins.json").category);

        assertEquals(ZyroxCrashAnalyzer.Category.SHADER_INCOMPATIBILITY,
                ZyroxCrashAnalyzer.analyze("net.irisshaders.iris.gl.shader.ShaderCompileException: failed to compile shader").category);

        assertEquals(ZyroxCrashAnalyzer.Category.RENDERER_OR_GL_FAILURE,
                ZyroxCrashAnalyzer.analyze("GLFW error 65542: WGL/EGL driver does not support OpenGL").category);

        assertEquals(ZyroxCrashAnalyzer.Category.CORRUPT_FILE_OR_HASH,
                ZyroxCrashAnalyzer.analyze("Caused by: java.util.zip.ZipException: zip END header not found").category);

        assertEquals(ZyroxCrashAnalyzer.Category.NATIVE_SIGNAL_CRASH,
                ZyroxCrashAnalyzer.analyze("Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)").category);
    }

    @Test
    public void avoidsFalsePositivesHandlesUnknownWithLowConfidenceAndRedactsEvidence() {
        ZyroxCrashAnalyzer.Diagnosis clean = ZyroxCrashAnalyzer.analyze(
                "[12:00:01] [Render thread/INFO]: Setting user: Player\n[12:00:04] [Render thread/INFO]: Backend library: LWJGL");
        assertEquals(ZyroxCrashAnalyzer.Category.NO_CRASH_DETECTED, clean.category);

        ZyroxCrashAnalyzer.Diagnosis unknown = ZyroxCrashAnalyzer.analyze(
                "java.lang.IllegalStateException: custom failure --accessToken super_secret_token_999999");
        assertEquals(ZyroxCrashAnalyzer.Category.UNKNOWN, unknown.category);
        assertEquals(ZyroxCrashAnalyzer.Confidence.LOW, unknown.confidence);
        assertFalse(unknown.formatCompactSummary().contains("super_secret_token_999999"));
    }

    @Test
    public void javaRuntimeAdvisorPicksNearestCompatibleRuntimeWithoutHardcoding() {
        Runtime jre8 = new Runtime("Internal-8", "1.8.0_422", "arm64", 8);
        Runtime jre17 = new Runtime("Internal-17", "17.0.12", "arm64", 17);
        Runtime jre21 = new Runtime("Internal-21", "21.0.5", "arm64", 21);

        JavaRuntimeAdvisor.Advice a21 = JavaRuntimeAdvisor.evaluate(
                21, "1.21.4", jre17, Arrays.asList(jre8, jre17, jre21), "arm64");
        assertEquals(JavaRuntimeAdvisor.Compatibility.INCOMPATIBLE_TOO_OLD, a21.compatibility);
        assertEquals("Internal-21", a21.recommendedRuntimeName);

        JavaRuntimeAdvisor.Advice aFuture = JavaRuntimeAdvisor.evaluate(
                25, "26.1", null, Arrays.asList(jre17, jre21), "arm64");
        assertEquals(JavaRuntimeAdvisor.Compatibility.MISSING_OR_CORRUPT, aFuture.compatibility);
        assertTrue(aFuture.explanation.contains("25") || aFuture.badgeText.contains("25"));

        JavaRuntimeAdvisor.Advice empty = JavaRuntimeAdvisor.evaluate(
                17, "1.20.4", null, Collections.emptyList(), "arm64");
        assertEquals(JavaRuntimeAdvisor.Compatibility.MISSING_OR_CORRUPT, empty.compatibility);
    }
}
