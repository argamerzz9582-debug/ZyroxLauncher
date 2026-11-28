package git.artdeell.mojo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import git.artdeell.mojo.fragments.MainMenuFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.updater.AppUpdateChecker;
import git.artdeell.mojo.utils.ZyroxHttpClient;

/**
 * Comprehensive JUnit regression suite for Zyrox Launcher v2.7.0:
 * <ol>
 *   <li>Instant cold-start &amp; tag-scoped snooze rules for automatic in-app update popups.</li>
 *   <li>Zero-API-quota CGNAT GitHub {@code /releases/tag/<tag>} redirect parsing &amp; security checks.</li>
 *   <li>Resumable APK downloader live ETA formatting.</li>
 *   <li>Modded inherited client JAR detection (no false mobile-data prompts on Fabric/NeoForge).</li>
 *   <li>Home Hub Active Instance Liquid-Glass capsule formatting &amp; v2.7.0 User-Agent check.</li>
 * </ol>
 */
public class ZyroxV270RegressionTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testAutoUpdateModalTagScopedSnoozeAndSkipLogic() {
        long now = 1_750_000_000_000L;
        long futureSnooze = now + 3_600_000L;
        long expiredSnooze = now - 1_000L;

        // Fresh release never prompted, not skipped, not snoozed -> MUST auto-popup immediately!
        assertTrue(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.7.0", "", "", 0L, null, now));

        // Same release snoozed via "Later" -> suppressed during snooze window
        assertFalse(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.7.0", "", "v2.7.0", futureSnooze, null, now));

        // Once snooze expires -> automatically pops up again on next launch!
        assertTrue(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.7.0", "", "v2.7.0", expiredSnooze, null, now));

        // Brand-new release published WHILE older tag was snoozed -> BREAKS THROUGH IMMEDIATELY!
        assertTrue(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.8.0", "", "v2.7.0", futureSnooze, null, now));

        // Brand-new release published after skipping older tag -> STILL auto-pops up!
        assertTrue(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.8.0", "v2.7.0", "", 0L, null, now));

        // Permanently skipped tag stays silent (case-insensitive)
        assertFalse(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.7.0", "V2.7.0", "", 0L, null, now));

        // Already shown once during this session -> won't spam on every back-navigation to Home,
        // unless an even newer tag arrives mid-session!
        assertFalse(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.7.0", "", "", 0L, "v2.7.0", now));
        assertTrue(AppUpdateChecker.shouldAutoPromptForTag(
                "v2.7.1", "", "", 0L, "v2.7.0", now));

        // Empty tag rejected
        assertFalse(AppUpdateChecker.shouldAutoPromptForTag(
                "   ", "", "", 0L, null, now));
    }

    @Test
    public void testZeroQuotaReleaseRedirectTagExtractionAndSecurityGuardrails() {
        assertEquals("v2.7.0", AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.7.0"));
        assertEquals("v2.8.1-rc.1", AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.8.1-rc.1?foo=bar#top"));
        assertEquals("2.7.0", AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/2.7.0/"));

        // Strictly reject cleartext HTTP, wrong repository/owner, path traversal, or non-SemVer
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "http://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.7.0"));
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://github.com/evil-fork/ZyroxLauncher/releases/tag/v9.9.9"));
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://evil.example.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.7.0"));
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.7.0/../../evil"));
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(
                "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/nightly-unverified"));
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(null));
        assertNull(AppUpdateChecker.extractTagFromReleaseRedirectUrl(""));
    }

    @Test
    public void testFormatEtaProducesCrispHumanEstimates() {
        assertEquals("~5s left", AppUpdateChecker.formatEta(5_000_000L, 1_000_000d));
        assertEquals("~1s left", AppUpdateChecker.formatEta(200_000L, 5_000_000d));
        assertEquals("~1m 15s left", AppUpdateChecker.formatEta(75_000_000L, 1_000_000d));
        assertEquals("~2m left", AppUpdateChecker.formatEta(120_000_000L, 1_000_000d));

        // Edge & safety guardrails
        assertEquals("", AppUpdateChecker.formatEta(0L, 1_000_000d));
        assertEquals("", AppUpdateChecker.formatEta(50_000_000L, 0d));
        assertEquals("", AppUpdateChecker.formatEta(50_000_000L, Double.NaN));
        assertEquals("", AppUpdateChecker.formatEta(50_000_000L, Double.POSITIVE_INFINITY));
        assertEquals("", AppUpdateChecker.formatEta(50_000_000_000L, 5_000d)); // > 1h suppressed
    }

    @Test
    public void testIsMinecraftClientJarInstalledHandlesModdedInheritedVersions() throws Exception {
        File versionsRoot = tmp.newFolder("versions");
        File baseDir = new File(versionsRoot, "1.21.4");
        assertTrue(baseDir.mkdirs());
        File baseJar = new File(baseDir, "1.21.4.jar");
        try (FileOutputStream out = new FileOutputStream(baseJar)) {
            out.write(new byte[80_000]);
        }

        // Direct Vanilla 1.21.4 -> installed
        assertTrue(LauncherActivity.isMinecraftClientJarInstalled(versionsRoot, "1.21.4"));
        // Fabric, Quilt, Forge, NeoForge inheriting 1.21.4 -> installed (zero false mobile popup!)
        assertTrue(LauncherActivity.isMinecraftClientJarInstalled(
                versionsRoot, "fabric-loader-0.16.10-1.21.4"));
        assertTrue(LauncherActivity.isMinecraftClientJarInstalled(
                versionsRoot, "quilt-loader-0.27.1-1.21.4"));
        assertTrue(LauncherActivity.isMinecraftClientJarInstalled(
                versionsRoot, "1.21.4-forge-54.0.12"));
        assertTrue(LauncherActivity.isMinecraftClientJarInstalled(
                versionsRoot, "neoforge-21.4.88"));

        // Uninstalled version & symbolic aliases -> false (so true downloads still warn properly)
        assertFalse(LauncherActivity.isMinecraftClientJarInstalled(
                versionsRoot, "fabric-loader-0.16.10-1.20.1"));
        assertFalse(LauncherActivity.isMinecraftClientJarInstalled(
                versionsRoot, Instance.VERSION_LATEST_FABRIC));
        assertFalse(LauncherActivity.isMinecraftClientJarInstalled(null, "1.21.4"));
    }

    @Test
    public void testHomeInstanceCapsuleAndV270Metadata() {
        assertEquals("Fabric 1.21.4  ·  6 Mods  ·  4096 MB",
                MainMenuFragment.formatHomeInstanceCapsule(
                        Instance.LOADER_FABRIC, "1.21.4", 6, 4096));
        assertEquals("Fabric 1.21.4  ·  1 Mod  ·  2048 MB",
                MainMenuFragment.formatHomeInstanceCapsule(
                        Instance.LOADER_FABRIC, "1.21.4", 1, 2048));
        assertEquals("Vanilla 1.21.4  ·  3072 MB",
                MainMenuFragment.formatHomeInstanceCapsule(
                        Instance.LOADER_VANILLA, "1.21.4", 0, 3072));

        assertEquals(15L * 60_000L, AppUpdateChecker.WARM_RESUME_CHECK_INTERVAL_MS);
        assertTrue(AppUpdateChecker.compareSemVer("v2.7.0", "2.6.0") > 0);
        assertTrue(ZyroxHttpClient.USER_AGENT.startsWith("ZyroxLauncher/2.10.0"));
    }
}
