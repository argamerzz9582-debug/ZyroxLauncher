package git.artdeell.mojo.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * Safety tests for crash-analyzer auto-fix: culprit extraction must not invent mod names from
 * ordinary log prose, and quarantine must never guess between several mods or overwrite files.
 */
public class ZyroxCrashAnalyzerAutoFixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void ordinaryProseDoesNotProduceACulprit() {
        assertNull(ZyroxCrashAnalyzer.extractCulpritModToken(
                "Exception in thread \"main\" java.lang.NullPointerException\n"
                        + "\tat net.minecraft.client.main.Main.main(Main.java:42)\n"
                        + "Error occurred in render pass"));
    }

    @Test
    public void explicitAttributionsAreStillDetected() {
        assertEquals("sodium", ZyroxCrashAnalyzer.extractCulpritModToken(
                "Critical injection failure: @Inject in sodium.mixins.json"));
        assertEquals("lithium", ZyroxCrashAnalyzer.extractCulpritModToken(
                "Mixin apply failed from mod lithium"));
        assertEquals("iris-1.8.0.jar", ZyroxCrashAnalyzer.extractCulpritModToken(
                "Mod File: iris-1.8.0.jar"));
    }

    @Test
    public void libraryJarsInStackFramesAreIgnored() {
        assertEquals("create-0.5.1.jar", ZyroxCrashAnalyzer.extractCulpritModToken(
                "at com.google.common.X(X.java:1) ~[guava-31.1-jre.jar:?]\n"
                        + "at com.simibubi.Create(Create.java:9) ~[create-0.5.1.jar:?]"));
    }

    @Test
    public void ambiguousFuzzyMatchDisablesNothing() throws Exception {
        File mods = tmp.newFolder("mods");
        File a = new File(mods, "sodium-extra-0.5.jar");
        File b = new File(mods, "sodium-fabric-0.6.jar");
        assertTrue(a.createNewFile());
        assertTrue(b.createNewFile());

        assertNull(ZyroxCrashAnalyzer.disableMatchingModInDir(mods, "sodium"));
        assertTrue("Ambiguous match must leave every jar untouched", a.exists() && b.exists());
    }

    @Test
    public void uniqueMatchIsQuarantinedNonDestructively() throws Exception {
        File mods = tmp.newFolder("mods");
        File target = new File(mods, "lithium-fabric-0.14.jar");
        File other = new File(mods, "iris-1.8.jar");
        assertTrue(target.createNewFile());
        assertTrue(other.createNewFile());

        File disabled = ZyroxCrashAnalyzer.disableMatchingModInDir(mods, "lithium");
        assertNotNull(disabled);
        assertEquals("lithium-fabric-0.14.jar.disabled", disabled.getName());
        assertFalse(target.exists());
        assertTrue(other.exists());
    }

    @Test
    public void exactFilenameBeatsFuzzyCandidates() throws Exception {
        File mods = tmp.newFolder("mods");
        File exact = new File(mods, "sodium.jar");
        File fuzzy = new File(mods, "sodium-extra.jar");
        assertTrue(exact.createNewFile());
        assertTrue(fuzzy.createNewFile());

        File disabled = ZyroxCrashAnalyzer.disableMatchingModInDir(mods, "sodium.jar");
        assertNotNull(disabled);
        assertFalse(exact.exists());
        assertTrue(fuzzy.exists());
    }

    @Test
    public void existingQuarantinedCopyIsNeverOverwritten() throws Exception {
        File mods = tmp.newFolder("mods");
        File jar = new File(mods, "lithium.jar");
        File previous = new File(mods, "lithium.jar.disabled");
        assertTrue(jar.createNewFile());
        assertTrue(previous.createNewFile());
        assertTrue(previous.setLastModified(1_000_000L));

        File disabled = ZyroxCrashAnalyzer.disableMatchingModInDir(mods, "lithium.jar");
        assertNotNull(disabled);
        assertNotEquals(previous, disabled);
        assertTrue("Quarantined name must still end with .jar.disabled",
                disabled.getName().endsWith(".jar.disabled"));
        assertTrue(previous.exists());
        assertEquals(1_000_000L, previous.lastModified());
    }

    @Test
    public void protectedCoreJarsAreNeverSelected() throws Exception {
        File mods = tmp.newFolder("mods");
        File loader = new File(mods, "fabric-loader-0.16.jar");
        assertTrue(loader.createNewFile());
        assertNull(ZyroxCrashAnalyzer.disableMatchingModInDir(mods, "fabric-loader"));
        assertTrue(loader.exists());
    }
}
