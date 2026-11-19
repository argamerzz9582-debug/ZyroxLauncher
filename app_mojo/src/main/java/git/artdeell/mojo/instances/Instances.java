package git.artdeell.mojo.instances;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

public class Instances {
    private static final File sInstancePath = new File(Tools.DIR_GAME_HOME, "instances");
    public static final File SHARED_DATA_DIRECTORY = new File(Tools.DIR_GAME_HOME, "shared_dir");
    private static volatile List<String> sLastCorruptedDirs = Collections.emptyList();

    public final List<DisplayInstance> list;
    public final int selectedIndex;

    private Instances(List<DisplayInstance> instances, int selectedIndex) {
        this.list = instances;
        this.selectedIndex = selectedIndex;
    }

    /**
     * Reads instance metadata with automatic recovery from {@code mojo_instance.json.bak} when the
     * primary JSON is truncated or corrupted, while preserving the damaged file as
     * {@code mojo_instance.json.corrupt} so user data is never silently destroyed.
     */
    @Nullable
    static <T extends DisplayInstance> T readWithRecovery(@Nullable File instanceRoot, @NonNull Class<T> tClass) {
        if (instanceRoot == null || !instanceRoot.isDirectory()) return null;
        File metaFile = metadataLocation(instanceRoot);
        if (metaFile.isFile() && metaFile.length() > 0L) {
            try {
                T instance = JSONUtils.readFromFile(metaFile, tClass);
                if (instance != null) {
                    instance.mInstanceRoot = instanceRoot;
                    return instance;
                }
            } catch (IOException | RuntimeException e) {
                Log.w("Instances", "Corrupted metadata in " + instanceRoot.getName() + ", attempting .bak recovery");
            }
        }

        // Preserve damaged metadata file without destroying user data
        File bakFile = new File(instanceRoot, "mojo_instance.json.bak");
        if (metaFile.isFile() && metaFile.length() > 0L) {
            File quarantine = new File(instanceRoot, "mojo_instance.json.corrupt");
            if (!quarantine.exists()) {
                try {
                    JSONUtils.writeToFileAtomic(quarantine, null, false);
                } catch (Throwable ignored) {
                }
                // Copy raw corrupt bytes for diagnostics
                copyRawQuietly(metaFile, quarantine);
            }
        }

        if (bakFile.isFile() && bakFile.length() > 0L) {
            try {
                T recovered = JSONUtils.readFromFile(bakFile, tClass);
                if (recovered != null) {
                    recovered.mInstanceRoot = instanceRoot;
                    copyRawQuietly(bakFile, metaFile);
                    Log.i("Instances", "Recovered instance metadata from .bak: " + instanceRoot.getName());
                    return recovered;
                }
            } catch (IOException | RuntimeException e) {
                Log.w("Instances", "Backup metadata also unreadable for " + instanceRoot.getName());
            }
        }
        return null;
    }

    private static void copyRawQuietly(@NonNull File src, @NonNull File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst, false)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    protected static File metadataLocation(File instanceDir) {
        return new File(instanceDir, "mojo_instance.json");
    }

    public static File getInstancesDirectory() {
        return sInstancePath;
    }

    /** Returns names of instance folders whose metadata could not be parsed during the last scan. */
    @NonNull
    public static List<String> getLastCorruptedDirs() {
        return sLastCorruptedDirs;
    }

    private static File selectedInstanceLocation() {
        String directoryName = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_INSTANCE, "");
        if (directoryName.isEmpty() || directoryName.contains("..") || directoryName.contains("/") || directoryName.contains("\\")) {
            return null;
        }
        File instanceRoot = new File(sInstancePath, directoryName);
        if (!instanceRoot.exists()) {
            Log.w("Instances", "Selected instance directory missing: " + directoryName);
            return null;
        }
        File meta = metadataLocation(instanceRoot);
        File bak = new File(instanceRoot, "mojo_instance.json.bak");
        if (!meta.exists() && !bak.exists()) return null;
        return instanceRoot;
    }

    private static boolean filterInstanceDirectories(File instanceDir) {
        if (!instanceDir.canRead() || !instanceDir.canWrite()) return false;
        if (!instanceDir.isDirectory()) return false;
        File instanceMetadata = metadataLocation(instanceDir);
        File bakMetadata = new File(instanceDir, "mojo_instance.json.bak");
        return (instanceMetadata.isFile() && instanceMetadata.canRead())
                || (bakMetadata.isFile() && bakMetadata.canRead());
    }

    private static <T extends DisplayInstance> List<T> loadInstances(Class<T> tClass, int[] selectionDst)
            throws IOException {
        synchronized (sInstancePath) {
            FileUtils.ensureDirectory(sInstancePath);
        }
        File[] instanceDirectories = sInstancePath.listFiles(Instances::filterInstanceDirectories);
        if (instanceDirectories == null) throw new IOException("Failed to enumerate instances");
        File selectedInstanceLocation = selectionDst != null ? selectedInstanceLocation() : null;
        ArrayList<T> instances = new ArrayList<>(instanceDirectories.length);
        ArrayList<String> corrupted = new ArrayList<>();

        for (File instanceDir : instanceDirectories) {
            T instance = readWithRecovery(instanceDir, tClass);
            if (instance == null) {
                corrupted.add(instanceDir.getName());
                continue;
            }
            instance.sanitize();
            instances.add(instance);

            if (selectionDst != null && instanceDir.equals(selectedInstanceLocation)) {
                selectionDst[0] = instances.size() - 1;
            }
        }
        sLastCorruptedDirs = corrupted.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(corrupted);
        instances.trimToSize();
        return instances;
    }

    public static Instances loadDisplay() throws IOException {
        int[] selectionIndex = new int[]{-1};
        List<DisplayInstance> instances = loadInstances(DisplayInstance.class, selectionIndex);
        if (instances.isEmpty()) {
            createFirstTimeInstance();
            return loadDisplay();
        } else if (selectionIndex[0] == -1) {
            setSelectedInstance(instances.get(0));
            selectionIndex[0] = 0;
        }
        return new Instances(Collections.unmodifiableList(instances), selectionIndex[0]);
    }

    public static List<Instance> loadAllInstances() throws IOException {
        return loadInstances(Instance.class, null);
    }

    static File findNewInstanceRoot(File baseDir, String prefix) {
        File instanceRoot;
        String safePrefix = (prefix != null && !prefix.trim().isEmpty())
                ? FileUtils.escapeFileName(prefix.trim())
                : null;
        do {
            String proposedDirectoryName = UUID.randomUUID().toString();
            if (safePrefix != null && !safePrefix.isEmpty()) {
                proposedDirectoryName = safePrefix + "-" + proposedDirectoryName;
            }
            instanceRoot = new File(baseDir, proposedDirectoryName);
        } while (instanceRoot.exists());
        return instanceRoot;
    }

    private static File findNewInstanceRoot(String prefix) {
        return findNewInstanceRoot(sInstancePath, prefix);
    }

    public static void setSelectedInstance(DisplayInstance instance) {
        if (instance == null || instance.mInstanceRoot == null) return;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(
                        LauncherPreferences.PREF_KEY_CURRENT_INSTANCE,
                        instance.mInstanceRoot.getName()
                ).apply();
    }

    public static void removeInstance(Instance instance) throws IOException {
        if (instance == null) return;
        File instanceDirectory = instance.mInstanceRoot;
        if (instanceDirectory == null) return;
        // Safety guard: never delete the shared game directory when removing a profile!
        if (instanceDirectory.getCanonicalFile().equals(SHARED_DATA_DIRECTORY.getCanonicalFile())) {
            throw new IOException("Refusing to delete shared game directory");
        }
        org.apache.commons.io.FileUtils.deleteDirectory(instanceDirectory);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sDefaultFabricSyncInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static final String DEFAULT_FABRIC_PLACEHOLDER_NAME = "Fabric (Latest)";

    private static void createFirstTimeInstance() throws IOException {
        Instance created = internalCreateInstance(instance -> {
            instance.sharedData = true;
            instance.versionId = Instance.VERSION_LATEST_FABRIC;
            instance.name = DEFAULT_FABRIC_PLACEHOLDER_NAME;
            instance.icon = "fabric";
        }, "fabric-latest");
        setSelectedInstance(created);
        markDefaultFabricInitialized();
    }

    public static Instance createDefaultInstance() throws IOException {
        return createInstance(instance -> {
            instance.sharedData = false;
            instance.versionId = Instance.VERSION_LATEST_FABRIC;
            instance.icon = "fabric";
        }, null);
    }

    private static void markDefaultFabricInitialized() {
        LauncherPreferences.PREF_ZYROX_DEFAULT_FABRIC_READY = true;
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(LauncherPreferences.KEY_ZYROX_DEFAULT_FABRIC_READY, true)
                    .apply();
        }
    }

    /**
     * Ensures the launcher's default selected profile targets the latest stable Fabric release
     * and materializes any symbolic {@link Instance#VERSION_LATEST_FABRIC} profile into an
     * installed {@code fabric-loader-<loader>-<mc>} profile JSON asynchronously without blocking UI.
     */
    public static void ensureDefaultLatestFabricAsync() {
        if (!sDefaultFabricSyncInFlight.compareAndSet(false, true)) {
            return;
        }
        git.artdeell.mojo.MojoApplication.sExecutorService.execute(() -> {
            try {
                boolean spinnerNeedsRefresh = false;
                List<Instance> all = loadAllInstances();
                if (all.isEmpty()) {
                    createFirstTimeInstance();
                    all = loadAllInstances();
                    spinnerNeedsRefresh = true;
                }

                // One-time default Fabric selection for fresh & upgrading installs
                if (!LauncherPreferences.PREF_ZYROX_DEFAULT_FABRIC_READY) {
                    Instance existingFabric = null;
                    for (Instance inst : all) {
                        if (inst != null && (Instance.isLatestFabricId(inst.versionId)
                                || (inst.versionId != null && inst.versionId.startsWith("fabric-loader-")))) {
                            existingFabric = inst;
                            break;
                        }
                    }
                    if (existingFabric != null) {
                        setSelectedInstance(existingFabric);
                    } else {
                        Instance selected = loadSelectedInstance();
                        if (selected != null && Instance.isLatestReleaseId(selected.versionId)
                                && selected.launchCount == 0) {
                            selected.versionId = Instance.VERSION_LATEST_FABRIC;
                            selected.name = DEFAULT_FABRIC_PLACEHOLDER_NAME;
                            selected.icon = "fabric";
                            selected.sanitize();
                            selected.write();
                            all = loadAllInstances();
                        } else {
                            Instance fabricDefault = internalCreateInstance(inst -> {
                                inst.sharedData = true;
                                inst.versionId = Instance.VERSION_LATEST_FABRIC;
                                inst.name = DEFAULT_FABRIC_PLACEHOLDER_NAME;
                                inst.icon = "fabric";
                            }, "fabric-latest");
                            setSelectedInstance(fabricDefault);
                            all = loadAllInstances();
                        }
                    }
                    markDefaultFabricInitialized();
                    spinnerNeedsRefresh = true;
                }

                // Resolve any symbolic "latest_fabric" profile into the newest stable Fabric release
                boolean hasSymbolicFabric = false;
                for (Instance inst : all) {
                    if (inst != null && Instance.isLatestFabricId(inst.versionId)) {
                        hasSymbolicFabric = true;
                        break;
                    }
                }
                if (hasSymbolicFabric) {
                    try {
                        git.artdeell.mojo.modding.fabric.FabriclikeUtils.ResolvedFabricRelease latest =
                                git.artdeell.mojo.modding.fabric.FabriclikeUtils.FABRIC_UTILS
                                        .resolveAndInstallLatestStable();
                        // Re-read current instances so any profile edits made mid-fetch stay intact
                        for (Instance inst : loadAllInstances()) {
                            if (inst != null && Instance.isLatestFabricId(inst.versionId)) {
                                inst.versionId = latest.versionId;
                                if (inst.name == null || inst.name.trim().isEmpty()
                                        || DEFAULT_FABRIC_PLACEHOLDER_NAME.equalsIgnoreCase(inst.name.trim())
                                        || "Latest Release".equalsIgnoreCase(inst.name.trim())) {
                                    inst.name = "Fabric " + latest.minecraftVersion;
                                }
                                if (inst.icon == null || InstanceIconProvider.FALLBACK_ICON_NAME.equals(inst.icon)) {
                                    inst.icon = "fabric";
                                }
                                inst.sanitize();
                                inst.write();
                                spinnerNeedsRefresh = true;
                            }
                        }
                    } catch (IOException e) {
                        Log.w("Instances", "Deferred latest Fabric resolution until network is ready: "
                                + e.getMessage());
                    }
                }

                if (spinnerNeedsRefresh) {
                    git.artdeell.mojo.Tools.runOnUiThread(() ->
                            git.artdeell.mojo.extra.ExtraCore.setValue(
                                    git.artdeell.mojo.extra.ExtraConstants.REFRESH_VERSION_SPINNER, null));
                }
            } catch (Throwable t) {
                Log.w("Instances", "ensureDefaultLatestFabricAsync failed", t);
            } finally {
                sDefaultFabricSyncInFlight.set(false);
            }
        });
    }

    private static Instance internalCreateInstance(InstanceSetter instanceSetter, String namePrefix) throws IOException {
        File root = findNewInstanceRoot(namePrefix);
        FileUtils.ensureDirectory(root);
        Instance instance = new Instance();
        instance.mInstanceRoot = root;
        instance.createdAtMs = System.currentTimeMillis();
        instanceSetter.setInstanceProperties(instance);
        instance.sanitize();
        instance.write();
        return instance;
    }

    public static Instance createInstance(InstanceSetter instanceSetter, String namePrefix) throws IOException {
        return internalCreateInstance(instanceSetter, namePrefix);
    }

    public static Instance loadSelectedInstance() {
        File selectedInstanceLocation = selectedInstanceLocation();
        Instance instance = readWithRecovery(selectedInstanceLocation, Instance.class);
        if (instance == null) return null;
        instance.sanitize();
        return instance;
    }

    public static void renameInstanceDirectory(Instance instance, String newName) {
        if (instance == null || instance.mInstanceRoot == null || newName == null) return;
        String cleaned = newName.trim().isEmpty()
                ? String.valueOf(UUID.randomUUID())
                : FileUtils.escapeFileName(newName.trim());
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            cleaned = String.valueOf(UUID.randomUUID());
        }
        File currentCanonical = instance.mInstanceRoot;
        if (cleaned.equals(currentCanonical.getName())) return;
        File targetDirectory = new File(sInstancePath, cleaned);
        if (targetDirectory.exists()) {
            targetDirectory = findNewInstanceRoot(cleaned);
        }
        String oldName = currentCanonical.getName();
        if (!currentCanonical.renameTo(targetDirectory)) {
            throw new RuntimeException("Failed to rename instance directory");
        }
        instance.mInstanceRoot = targetDirectory;
        if (LauncherPreferences.DEFAULT_PREF != null
                && oldName.equals(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_INSTANCE, ""))) {
            setSelectedInstance(instance);
        }
    }
}
