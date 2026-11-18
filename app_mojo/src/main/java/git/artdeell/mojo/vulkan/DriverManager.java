package git.artdeell.mojo.vulkan;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;
import git.artdeell.mojo.utils.ZipUtils;
import git.artdeell.mojo.vulkan.driver.AdrenoDriver;
import git.artdeell.mojo.vulkan.driver.BuiltinDriver;
import git.artdeell.mojo.vulkan.driver.DisplayDriver;
import git.artdeell.mojo.vulkan.driver.Driver;

/**
 * AdrenoTools Turnip ({@code .adpkg} / {@code .adpkg.zip} / {@code .zip}) Vulkan driver manager
 * with multi-version library inspection, safe path-traversal-proof extraction (supporting both flat
 * and single-folder {@code .adpkg} layouts), and per-instance driver assignment.
 */
public class DriverManager {
    private static final String METADATA_FILENAME = "meta.json";
    public static final String TAG = "DriverManager";
    /** Explicit per-instance token forcing the device's built-in system Vulkan ICD. */
    public static final String SYSTEM_DRIVER_TOKEN = "system";
    public static final BuiltinDriver DEFAULT_DRIVER = new BuiltinDriver();

    private static Boolean supported = null;

    private DriverManager() {
    }

    @NonNull
    private static File getPackagesDir() {
        String base = Tools.DIR_DATA != null ? Tools.DIR_DATA : ".";
        return new File(base, "vulkan-drivers");
    }

    public static boolean isSupportedByDevice() {
        return supported == null
                ? (supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                : supported;
    }

    /** Returns {@code true} when a filename represents a Turnip {@code .adpkg} or {@code .zip}. */
    public static boolean isSupportedDriverArchiveName(@Nullable String fileName) {
        if (fileName == null) return false;
        String lower = fileName.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".adpkg")
                || lower.endsWith(".adpkg.zip")
                || lower.endsWith(".zip");
    }

    @NonNull
    public static Driver getPreferredDriver() {
        Driver driver = LauncherPreferences.PREF_VULKAN_DRIVER == null
                ? DEFAULT_DRIVER : loadDriver(LauncherPreferences.PREF_VULKAN_DRIVER);
        if (driver == null) {
            Log.e(TAG, "Failed to load preferred driver package "
                    + LauncherPreferences.PREF_VULKAN_DRIVER);
            return DEFAULT_DRIVER;
        }
        return driver;
    }

    /**
     * Resolves the effective Vulkan / Adreno Turnip driver for a specific {@link Instance}:
     * honors {@code instance.vulkanDriver} when set ({@code "system"} for built-in ICD or an
     * installed Turnip package hash), otherwise inherits {@link #getPreferredDriver()}.
     */
    @NonNull
    public static Driver resolveDriverForInstance(@Nullable Instance instance) {
        if (instance != null && instance.hasCustomVulkanDriver()) {
            String token = instance.getVulkanDriver();
            if (SYSTEM_DRIVER_TOKEN.equalsIgnoreCase(token)
                    || "default".equalsIgnoreCase(token)) {
                return DEFAULT_DRIVER;
            }
            AdrenoDriver custom = loadDriver(token);
            if (custom != null && hasValidLibraryOnDisk(custom)) {
                return custom;
            }
            Log.w(TAG, "Per-instance Turnip driver '" + token
                    + "' missing on disk; falling back to global preference");
        }
        return getPreferredDriver();
    }

    public static boolean hasValidLibraryOnDisk(@Nullable Driver driver) {
        if (driver == null || driver instanceof BuiltinDriver) return true;
        String hash = driver.getHash();
        String lib = driver.getMainLibrary();
        if (hash == null || lib == null || lib.contains("..") || lib.contains("/")) return false;
        File libFile = new File(new File(getPackagesDir(), hash), lib);
        return libFile.isFile() && libFile.length() > 0L;
    }

    public static boolean isPreferredDriver(Driver driver) {
        String pkg = getPreferredDriverHash();
        if (pkg == null && (driver == null || driver instanceof BuiltinDriver)) return true;
        return pkg != null && driver != null && pkg.equals(driver.getHash());
    }

    @Nullable
    public static String getPreferredDriverHash() {
        return LauncherPreferences.PREF_VULKAN_DRIVER;
    }

    @NonNull
    public static String getPreferredDriverLibraryPath() {
        Driver driver = getPreferredDriver();
        if (driver instanceof BuiltinDriver) return driver.getMainLibrary();
        File path = new File(getPackagesDir(), driver.getHash() + "/" + driver.getMainLibrary());
        if (path.isFile()) return path.getAbsolutePath();
        Log.e(TAG, "Unable to resolve Vulkan library: " + path.getAbsolutePath());
        return DEFAULT_DRIVER.getMainLibrary();
    }

    @Nullable
    public static String getPreferredDriverRootPath() {
        Driver driver = getPreferredDriver();
        if (driver instanceof BuiltinDriver) return null;
        File path = new File(getPackagesDir(), driver.getHash());
        return path.isDirectory() ? path.getAbsolutePath() : null;
    }

    public static void setPreferredDriver(@Nullable Driver driver) {
        if (driver == null || driver instanceof BuiltinDriver) driver = DEFAULT_DRIVER;
        LauncherPreferences.PREF_VULKAN_DRIVER = driver.getHash();
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString("customVulkanDriver", LauncherPreferences.PREF_VULKAN_DRIVER)
                    .apply();
        }
    }

    public static void setPreferredDriverByHash(@Nullable String hash) {
        if (hash == null || hash.trim().isEmpty()
                || SYSTEM_DRIVER_TOKEN.equalsIgnoreCase(hash.trim())
                || "default".equalsIgnoreCase(hash.trim())) {
            setPreferredDriver(DEFAULT_DRIVER);
            return;
        }
        AdrenoDriver loaded = loadDriver(hash.trim());
        if (loaded != null) {
            setPreferredDriver(loaded);
        }
    }

    @Nullable
    public static File getDriverRoot(@Nullable Driver driver) {
        if (driver == null || driver instanceof BuiltinDriver) return null;
        File root = new File(getPackagesDir(), driver.getHash());
        return (root.exists() && root.isDirectory()) ? root : null;
    }

    /** Returns the installed disk footprint (in bytes) of a custom driver package. */
    public static long getDriverDiskBytes(@Nullable Driver driver) {
        File root = getDriverRoot(driver);
        if (root == null) return 0L;
        File[] files = root.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) {
            if (f.isFile()) total += Math.max(0L, f.length());
        }
        return total;
    }

    /** Builds a rich human-readable label (name + version + author) for version selectors. */
    @NonNull
    public static String formatDriverLabel(@NonNull Driver driver) {
        if (driver instanceof BuiltinDriver) {
            return "System Built-In Vulkan ICD";
        }
        if (driver instanceof AdrenoDriver) {
            AdrenoDriver ad = (AdrenoDriver) driver;
            StringBuilder sb = new StringBuilder();
            sb.append(ad.name != null && !ad.name.isEmpty() ? ad.name : "Turnip Driver");
            if (ad.driverVersion != null && !ad.driverVersion.isEmpty()
                    && !sb.toString().contains(ad.driverVersion)) {
                sb.append(" (").append(ad.driverVersion).append(')');
            }
            if (ad.minApi > 0 && Build.VERSION.SDK_INT < ad.minApi) {
                sb.append(" [API ").append(ad.minApi).append("+]");
            }
            return sb.toString();
        }
        return driver.getName() != null ? driver.getName() : "Custom Driver";
    }

    private static boolean ensureRootExists() {
        File dir = getPackagesDir();
        return dir.exists() || dir.mkdirs();
    }

    @NonNull
    public static List<String> getDriverPaths() {
        File root = getPackagesDir();
        if (!root.isDirectory()) return Collections.emptyList();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        List<String> packages = new ArrayList<>(dirs.length);
        for (File dir : dirs) {
            if (new File(dir, METADATA_FILENAME).isFile()) {
                packages.add(dir.getName());
            }
        }
        Collections.sort(packages);
        return packages;
    }

    @NonNull
    public static List<Driver> getDrivers() {
        List<Driver> drivers = new ArrayList<>();
        drivers.add(DEFAULT_DRIVER);
        File root = getPackagesDir();
        if (!root.isDirectory()) return drivers;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return drivers;
        List<AdrenoDriver> custom = new ArrayList<>();
        for (File dir : dirs) {
            File metadata = new File(dir, METADATA_FILENAME);
            if (!metadata.isFile()) continue;
            AdrenoDriver driver = AdrenoDriver.fromJson(metadata);
            if (driver != null) custom.add(driver);
        }
        Collections.sort(custom, Comparator.comparing(
                d -> d.name != null ? d.name : "", String.CASE_INSENSITIVE_ORDER));
        drivers.addAll(custom);
        return drivers;
    }

    public static boolean driverExists(@Nullable String hash) {
        if (hash == null || hash.isEmpty() || hash.contains("..") || hash.contains("/")) {
            return false;
        }
        return getPackagesDir().isDirectory()
                && new File(getPackagesDir(), hash + "/" + METADATA_FILENAME).isFile();
    }

    @Nullable
    public static AdrenoDriver loadDriver(@Nullable String hash) {
        if (!driverExists(hash)) return null;
        File metadata = new File(getPackagesDir(), hash + "/" + METADATA_FILENAME);
        return AdrenoDriver.fromJson(metadata);
    }

    @Nullable
    public static DisplayDriver loadDisplayDriver(@Nullable String hash) {
        if (!driverExists(hash)) return null;
        File metadata = new File(getPackagesDir(), hash + "/" + METADATA_FILENAME);
        try {
            return JSONUtils.readFromFile(metadata, DisplayDriver.class);
        } catch (IOException e) {
            Log.i(TAG, "Failed to read metadata from " + metadata.getAbsolutePath());
            return null;
        }
    }

    public static boolean validateDriver(@NonNull File path) {
        try (ZipFile zf = new ZipFile(path)) {
            ZipEntry meta = findMetadataEntry(zf);
            if (meta == null) return false;
            try (InputStream in = zf.getInputStream(meta)) {
                AdrenoDriver driver = AdrenoDriver.fromJson(in);
                return isValidAdrenoMetadata(driver);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates an AdrenoTools {@code .adpkg} or {@code .zip} stream, supporting both root-level
     * {@code meta.json} and single-folder {@code <pkg>/meta.json} layouts.
     */
    @Nullable
    public static String validateDriver(@NonNull InputStream stream) {
        ZipInputStream zip = new ZipInputStream(stream);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = normalizeEntryName(entry.getName());
                if (name == null) continue;
                if (METADATA_FILENAME.equals(name) || isSingleFolderChild(name, METADATA_FILENAME)) {
                    AdrenoDriver driver = AdrenoDriver.fromJson(zip);
                    if (!isValidAdrenoMetadata(driver)) return null;
                    return driver.getHash();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isValidAdrenoMetadata(@Nullable AdrenoDriver driver) {
        if (driver == null) return false;
        if (driver.libraryName == null || driver.libraryName.trim().isEmpty()) return false;
        if (driver.libraryName.contains("..") || driver.libraryName.contains("/")
                || driver.libraryName.contains("\\")) {
            return false;
        }
        if (!driver.libraryName.endsWith(".so")) return false;
        if (driver.minApi > 0 && Build.VERSION.SDK_INT < driver.minApi) {
            Log.w(TAG, "Driver requires Android API " + driver.minApi
                    + " (current " + Build.VERSION.SDK_INT + ")");
            return false;
        }
        return driver.getHash() != null && !driver.getHash().isEmpty();
    }

    @Nullable
    public static AdrenoDriver installDriver(@NonNull File path, boolean overwrite) {
        ensureRootExists();
        try (ZipFile zf = new ZipFile(path)) {
            ZipEntry meta = findMetadataEntry(zf);
            if (meta == null) return null;
            final AdrenoDriver driver;
            try (InputStream in = zf.getInputStream(meta)) {
                driver = AdrenoDriver.fromJson(in);
            }
            if (!isValidAdrenoMetadata(driver)) return null;
            String hash = driver.getHash();
            if (driverExists(hash)) {
                if (!overwrite) return driver;
                deleteDriverDirOnly(hash);
            }
            String prefix = extractParentPrefix(meta.getName());
            File installPath = new File(getPackagesDir(), hash);
            ZipUtils.zipExtract(zf, prefix, installPath);
            if (!new File(installPath, driver.libraryName).isFile()) {
                deleteDriverDirOnly(hash);
                throw new IOException("Driver package missing declared library " + driver.libraryName);
            }
            Log.i(TAG, "Installed Turnip/Adreno driver " + driver.getName() + " (" + hash + ")");
            return driver;
        } catch (Exception e) {
            Log.e(TAG, "Failed to install driver package at " + path.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Safely extracts a validated {@code .adpkg} / {@code .zip} stream into {@code vulkan-drivers/<hash>},
     * enforcing Zip-Slip canonical boundary checks and verifying the Vulkan {@code .so} payload.
     */
    public static void installDriverContent(@NonNull String hash,
                                            @NonNull InputStream is,
                                            boolean overwrite) throws IOException {
        if (hash.contains("..") || hash.contains("/") || hash.contains("\\")) {
            throw new IOException("Invalid driver package identifier");
        }
        ensureRootExists();
        if (driverExists(hash)) {
            if (!overwrite) return;
            deleteDriverDirOnly(hash);
        }
        File installPath = new File(getPackagesDir(), hash);
        FileUtils.ensureDirectory(installPath);
        String canonicalBase = installPath.getCanonicalPath() + File.separator;

        boolean extractedOk = false;
        try (ZipInputStream zis = new ZipInputStream(is)) {
            byte[] buf = new byte[16 * 1024];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String norm = normalizeEntryName(entry.getName());
                if (norm == null) {
                    throw new IOException("Blocked unsafe path in driver package");
                }
                // Flatten single top-level folder .adpkg layout so meta.json & lib*.so sit at root
                int slash = norm.indexOf('/');
                String targetRel = (slash > 0 && norm.indexOf('/', slash + 1) < 0)
                        ? norm.substring(slash + 1)
                        : norm;
                File dest = new File(installPath, targetRel);
                if (!dest.getCanonicalPath().startsWith(canonicalBase)) {
                    throw new IOException("Blocked Zip-Slip entry in driver package");
                }
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create driver parent directory");
                }
                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest))) {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                }
                if (dest.getName().endsWith(".so")) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.setReadable(true, true);
                    //noinspection ResultOfMethodCallIgnored
                    dest.setExecutable(true, true);
                }
            }
            AdrenoDriver verified = loadDriver(hash);
            if (verified == null || !hasValidLibraryOnDisk(verified)) {
                throw new IOException("Installed driver package is missing meta.json or "
                        + (verified != null ? verified.libraryName : "Vulkan .so"));
            }
            extractedOk = true;
            Log.i(TAG, "Installed driver package " + verified.getName() + " (" + hash + ")");
        } finally {
            if (!extractedOk) {
                deleteDriverDirOnly(hash);
            }
        }
    }

    /**
     * Removes an installed driver version by hash. Only resets the global preferred driver if the
     * removed driver was currently selected.
     */
    public static boolean removeDriver(@Nullable String name) {
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("/")) {
            return false;
        }
        boolean wasPreferred = name.equals(LauncherPreferences.PREF_VULKAN_DRIVER);
        boolean deleted = deleteDriverDirOnly(name);
        if (deleted && wasPreferred) {
            setPreferredDriver(null);
        }
        return deleted;
    }

    private static boolean deleteDriverDirOnly(@NonNull String hash) {
        File root = getPackagesDir();
        if (!root.isDirectory()) return false;
        File path = new File(root, hash);
        if (!path.isDirectory()) return false;
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(path);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Unable to remove driver package " + hash, e);
            return false;
        }
    }

    @Nullable
    private static ZipEntry findMetadataEntry(@NonNull ZipFile zf) {
        ZipEntry direct = zf.getEntry(METADATA_FILENAME);
        if (direct != null && !direct.isDirectory()) return direct;
        java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.isDirectory()) continue;
            String norm = normalizeEntryName(e.getName());
            if (norm != null && isSingleFolderChild(norm, METADATA_FILENAME)) {
                return e;
            }
        }
        return null;
    }

    @NonNull
    private static String extractParentPrefix(@NonNull String entryName) {
        String norm = entryName.replace('\\', '/');
        int idx = norm.lastIndexOf('/');
        return idx > 0 ? norm.substring(0, idx) : "";
    }

    @Nullable
    private static String normalizeEntryName(@Nullable String raw) {
        if (raw == null) return null;
        String clean = raw.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.isEmpty() || clean.contains("..")) return null;
        return clean;
    }

    private static boolean isSingleFolderChild(@NonNull String norm, @NonNull String fileName) {
        int slash = norm.indexOf('/');
        return slash > 0
                && norm.indexOf('/', slash + 1) < 0
                && fileName.equals(norm.substring(slash + 1));
    }
}
