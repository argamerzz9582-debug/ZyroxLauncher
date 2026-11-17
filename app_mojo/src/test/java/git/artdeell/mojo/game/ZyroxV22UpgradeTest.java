package git.artdeell.mojo.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import git.artdeell.mojo.input.ControlPresetFactory;
import git.artdeell.mojo.instances.InstancePlaytimeTracker;
import git.artdeell.mojo.performance.GameOptimizer;
import git.artdeell.mojo.updater.AppUpdateChecker;
import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Comprehensive JUnit verification suite for Zyrox Launcher v2.2.0 (Starlight Firefly Edition):
 * log-driven launch progress/ETA tracker, {@link GameOptimizer} JVM/GC &amp; {@code options.txt}
 * presets with backup/rollback, 4 built-in HUD layout presets, playtime formatting, GitHub release
 * update checker, and Android 15/16 16 KB ELF page-size alignment.
 */
public class ZyroxV22UpgradeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void tracksRealJvmModloaderAndMinecraftBootStagesMonotonically() {
        long t0 = 1_000L;
        GameLaunchProgressTracker tracker = new GameLaunchProgressTracker(t0, 24, 22_000L);
        int startPct = tracker.getPercent();
        assertTrue(startPct >= 0 && startPct <= 10);

        GameLaunchProgressTracker.ProgressSnapshot s1 =
                tracker.onLogOutput("OpenJDK 64-Bit Server VM (build 21.0.4)\n", t0 + 1_200L);
        assertTrue("JVM init must advance progress", s1.percent > startPct);

        GameLaunchProgressTracker.ProgressSnapshot s2 = tracker.onLogOutput(
                "[main/INFO]: Loading Minecraft 1.21.4 with Fabric Loader 0.16.10\n", t0 + 3_000L);
        assertTrue("Fabric Loader must advance progress", s2.percent > s1.percent);

        GameLaunchProgressTracker.ProgressSnapshot s3 = tracker.onLogOutput(
                "[main/INFO]: Loading 24 mods:\n", t0 + 5_000L);
        assertTrue("Mod discovery must advance progress", s3.percent >= s2.percent);

        // Earlier-stage line must never regress progress backwards
        GameLaunchProgressTracker.ProgressSnapshot sNoRegress =
                tracker.onLogOutput("OpenJDK 64-Bit Server VM\n", t0 + 5_100L);
        assertTrue("Progress must remain strictly monotonic", sNoRegress.percent >= s3.percent);

        GameLaunchProgressTracker.ProgressSnapshot s4 = tracker.onLogOutput(
                "[Render thread/INFO]: Setting user: Steve\n", t0 + 9_000L);
        assertTrue(s4.percent > s3.percent);

        GameLaunchProgressTracker.ProgressSnapshot s5 = tracker.onLogOutput(
                "[Render thread/INFO]: Backend library: LWJGL version 3.3.3\n", t0 + 12_500L);
        assertTrue(s5.percent > s4.percent);

        GameLaunchProgressTracker.ProgressSnapshot s6 = tracker.onLogOutput(
                "[Render thread/INFO]: Reloading ResourceManager: vanilla, fabric\n", t0 + 16_000L);
        assertTrue(s6.percent > s5.percent);

        GameLaunchProgressTracker.ProgressSnapshot s7 = tracker.onLogOutput(
                "[Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas\n",
                t0 + 19_000L);
        assertTrue("Atlas upload must reach near-completion", s7.percent >= 88);
        assertFalse("ETA formatter produces human-readable label", s7.etaText.isEmpty());
        assertEquals("~12s left", GameLaunchProgressTracker.formatEta(65, 12));
        assertEquals("Almost ready…", GameLaunchProgressTracker.formatEta(97, 2));

        GameLaunchProgressTracker.ProgressSnapshot done = tracker.markComplete(t0 + 20_500L);
        assertTrue(done.completed);
        assertEquals(100, done.percent);
        assertEquals("Ready", done.etaText);

        long blended = GameLaunchProgressTracker.blendLearnedBootMs(20_000L, 16_000L);
        assertTrue(blended >= 16_000L && blended <= 20_000L);
    }

    @Test
    public void buildsSafeDeduplicatedJvmOptimizationFlagsAndClampsLowRamHeap() {
        List<String> existing = Arrays.asList("-Xmx3072M", "-XX:MaxGCPauseMillis=45");
        List<String> modernFlags = GameOptimizer.buildOptimizedJvmFlags(
                GameOptimizer.MODE_MAX_FPS, existing, 21, 3072, 4096, 18, true);
        assertTrue(modernFlags.contains("-XX:+UseG1GC"));
        assertTrue(modernFlags.contains("-XX:+UnlockExperimentalVMOptions"));
        // Must respect explicit user override for MaxGCPauseMillis
        for (String flag : modernFlags) {
            assertFalse("Must not override user MaxGCPauseMillis",
                    flag.startsWith("-XX:MaxGCPauseMillis="));
        }

        // MODE_OFF must inject zero extra flags
        assertTrue(GameOptimizer.buildOptimizedJvmFlags(
                GameOptimizer.MODE_OFF, Collections.emptyList(), 21, 2048, 4096, 0, true).isEmpty());

        // Low free RAM safety guard must clamp oversized allocations to prevent Android LMK kills
        int clamped = GameOptimizer.recommendSafeHeapMb(4096, 6144, 1500, false, true);
        assertTrue("Must clamp below dangerous OOM level, got " + clamped,
                clamped >= 768 && clamped < 4096);

        // 32-bit runtime stays safely bounded
        int safe32 = GameOptimizer.recommendSafeHeapMb(3072, 8192, 4096, false, false);
        assertTrue(safe32 >= 512 && safe32 <= 1152);
    }

    @Test
    public void appliesOptionsTxtPresetsWithAtomicBackupAndCleanRestore() throws Exception {
        File gameDir = tmp.newFolder("instance_game");
        File opts = new File(gameDir, "options.txt");
        try (FileOutputStream out = new FileOutputStream(opts)) {
            out.write("renderDistance:12\nenableVsync:true\nmaxFps:60\nlang:en_us\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(GameOptimizer.hasOptionsBackup(gameDir));

        // Applying Max FPS preset tunes render/FPS keys, preserves lang, and creates atomic backup
        assertTrue(GameOptimizer.applyOptionsPreset(gameDir, GameOptimizer.MODE_MAX_FPS, 8192, 120, true));
        assertTrue("Backup must exist after overwriting existing options.txt",
                GameOptimizer.hasOptionsBackup(gameDir));
        String content = readUtf8(opts);
        assertTrue(content.contains("enableVsync:false"));
        assertTrue(content.contains("maxFps:120"));
        assertTrue("Unrelated user settings must remain intact", content.contains("lang:en_us"));

        // Rollback restores original user settings identically
        assertTrue(GameOptimizer.restoreOptionsBackup(gameDir));
        String restored = readUtf8(opts);
        assertTrue(restored.contains("renderDistance:12"));
        assertTrue(restored.contains("enableVsync:true"));
    }

    @Test
    public void parsesGitHubReleaseUpdatesAndEnforcesSemanticVersioningAndHttps() {
        assertTrue(AppUpdateChecker.compareSemVer("2.2.0", "2.1.0") > 0);
        assertTrue(AppUpdateChecker.compareSemVer("v2.2.1", "2.2.0") > 0);
        assertEquals(0, AppUpdateChecker.compareSemVer("v2.2.0", "2.2.0"));
        assertTrue(AppUpdateChecker.compareSemVer("2.1.0", "2.2.0") < 0);

        String sampleJson = "{"
                + "\"tag_name\":\"v2.3.0\","
                + "\"name\":\"Zyrox Launcher v2.3.0\","
                + "\"body\":\"## Highlights\\n- Faster FPS\","
                + "\"html_url\":\"https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.3.0\","
                + "\"assets\":[{\"name\":\"Zyrox.Launcher.apk\",\"size\":104857600,"
                + "\"browser_download_url\":\"https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/download/v2.3.0/Zyrox.Launcher.apk\"}]"
                + "}";
        AppUpdateChecker.ReleaseInfo newer = AppUpdateChecker.parseReleaseJson(sampleJson, "2.2.0");
        assertTrue(newer.updateAvailable);
        assertEquals("v2.3.0", newer.tagName);
        assertNotNull(newer.apkDownloadUrl);
        assertTrue(newer.apkDownloadUrl.startsWith("https://"));
        assertEquals(104857600L, newer.apkSizeBytes);

        AppUpdateChecker.ReleaseInfo same = AppUpdateChecker.parseReleaseJson(sampleJson, "2.3.0");
        assertFalse(same.updateAvailable);
    }

    @Test
    public void verifiesBuiltInHudPresetsPlaytimeFormattingAnd16KbNativeAlignment() throws Exception {
        assertEquals(4, ControlPresetFactory.PRESET_NAMES.length);
        assertEquals("", InstancePlaytimeTracker.formatPlaytime(0L));
        assertEquals("45m", InstancePlaytimeTracker.formatPlaytime(45L * 60_000L));
        assertEquals("2h", InstancePlaytimeTracker.formatPlaytime(120L * 60_000L));
        assertEquals("2h 15m", InstancePlaytimeTracker.formatPlaytime(135L * 60_000L));
        assertEquals("Fabric", InstancePlaytimeTracker.detectLoaderLabel("fabric-loader-0.16.10-1.21.4"));
        assertEquals("NeoForge", InstancePlaytimeTracker.detectLoaderLabel("neoforge-21.1.90"));

        File cmake = resolveModuleFile("src/main/jni/CMakeLists.txt");
        assertTrue(cmake.isFile());
        String cmakeText = readUtf8(cmake);
        assertTrue("Native libraries must enforce 16 KB ELF page alignment for Android 15/16",
                cmakeText.contains("-Wl,-z,max-page-size=16384"));

        File legacyMojoPng = resolveModuleFile("src/main/assets/mojo.png");
        assertFalse("Unused legacy assets/mojo.png must be removed", legacyMojoPng.exists());
    }

    private static File resolveModuleFile(String rel) {
        File direct = new File(rel);
        if (direct.exists()) return direct;
        return new File("app_mojo/" + rel);
    }

    private static String readUtf8(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(ArchiveSecurity.readBytesBounded(in, 1024 * 1024), StandardCharsets.UTF_8);
        }
    }
}
