package git.artdeell.mojo.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for zero-execution static mod/pack metadata parsing ({@link ModMetadataParser}) and
 * instance content management ({@link InstanceContentManager}).
 */
public class ModMetadataAndContentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void parsesFabricNeoForgeAndResourcePackMetadataStatically() throws Exception {
        File fabricJar = new File(tmp.getRoot(), "sodium-fabric-0.6.5.jar");
        createZip(fabricJar, "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"0.6.5\",\"name\":\"Sodium\","
                        + "\"description\":\"Modern rendering engine\",\"authors\":[\"JellySquid\"],"
                        + "\"depends\":{\"fabricloader\":\">=0.16.0\",\"minecraft\":\"1.21.4\"}}");

        ModMetadataParser.ModInfo fab = ModMetadataParser.inspectModFile(fabricJar);
        assertFalse(fab.corruptJar);
        assertEquals("sodium", fab.modId);
        assertEquals("Sodium", fab.name);
        assertEquals("0.6.5", fab.version);
        assertEquals(ModMetadataParser.LoaderFamily.FABRIC, fab.loader);
        assertEquals("1.21.4", fab.minecraftConstraint);
        assertFalse(fab.requiredDependencies.contains("fabricloader")
                && fab.requiredDependencies.isEmpty());

        File neoJar = new File(tmp.getRoot(), "create-neoforge.jar");
        createZip(neoJar, "META-INF/neoforge.mods.toml",
                "modLoader=\"javafml\"\n[[mods]]\nmodId=\"create\"\nversion=\"6.0.0\"\ndisplayName=\"Create\"\n"
                        + "description='''Kinetic contraptions'''\n");

        ModMetadataParser.ModInfo neo = ModMetadataParser.inspectModFile(neoJar);
        assertFalse(neo.corruptJar);
        assertEquals("create", neo.modId);
        assertEquals("Create", neo.name);
        assertEquals("6.0.0", neo.version);
        assertEquals(ModMetadataParser.LoaderFamily.NEOFORGE, neo.loader);

        ModMetadataParser.PackMeta pack = ModMetadataParser.parsePackMcmeta(
                "{\"pack\":{\"pack_format\":34,\"description\":\"§aCrisp 32x textures\"}}");
        assertEquals(34, pack.packFormat);
        assertEquals("Crisp 32x textures", pack.description);
    }

    @Test
    public void detectsDuplicateModsMismatchTogglesSnapshotsAndConfigBackups() throws Exception {
        File gameDir = tmp.newFolder("mc_instance");
        File modsDir = new File(gameDir, "mods");
        modsDir.mkdirs();

        createZip(new File(modsDir, "lithium-a.jar"), "fabric.mod.json",
                "{\"id\":\"lithium\",\"name\":\"Lithium\",\"version\":\"0.14.0\"}");
        createZip(new File(modsDir, "lithium-b.jar"), "fabric.mod.json",
                "{\"id\":\"lithium\",\"name\":\"Lithium\",\"version\":\"0.14.1\"}");
        createZip(new File(modsDir, "jei-forge.jar"), "META-INF/mods.toml",
                "[[mods]]\nmodId=\"jei\"\ndisplayName=\"JEI\"\nversion=\"19.0\"\n");

        List<ModMetadataParser.ModInfo> mods =
                InstanceContentManager.listMods(gameDir, "fabric-loader-0.16.9-1.21.4");
        assertEquals(3, mods.size());

        boolean sawDuplicateWarning = false;
        boolean sawLoaderMismatch = false;
        for (ModMetadataParser.ModInfo m : mods) {
            for (String w : m.warnings) {
                String lower = w.toLowerCase();
                if (lower.contains("duplicate")) sawDuplicateWarning = true;
                if (lower.contains("forge") || lower.contains("fabric") || lower.contains("loader")) {
                    sawLoaderMismatch = true;
                }
            }
        }
        assertTrue(sawDuplicateWarning);
        assertTrue(sawLoaderMismatch);

        // Save snapshot, disable a mod, then roll back via latest snapshot
        File snap = InstanceContentManager.saveModStateSnapshot(gameDir);
        assertTrue(snap.isFile());
        File toggled = InstanceContentManager.setModEnabled(new File(modsDir, "lithium-b.jar"), false);
        assertTrue(toggled.getName().endsWith(".disabled"));
        int restoredCount = InstanceContentManager.restoreLatestModStateSnapshot(gameDir);
        assertTrue(restoredCount >= 1);
        assertTrue(new File(modsDir, "lithium-b.jar").isFile());

        // Safe config editing writes .bak before atomic swap
        File configDir = new File(gameDir, "config");
        configDir.mkdirs();
        File cfg = new File(configDir, "sodium-options.json");
        try (FileOutputStream out = new FileOutputStream(cfg)) {
            out.write("{\"clouds\":true}".getBytes(StandardCharsets.UTF_8));
        }
        InstanceContentManager.writeConfigFileWithBackup(cfg, "{\"clouds\":false}");
        assertEquals("{\"clouds\":false}", InstanceContentManager.readConfigFileSafe(cfg));
        assertTrue(new File(configDir, "sodium-options.json.bak").isFile());
        assertFalse(new File(configDir, "sodium-options.json.tmp").exists());
    }

    private static void createZip(File file, String entryName, String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
