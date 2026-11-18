package git.artdeell.mojo.content;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.HashUtils;
import git.artdeell.mojo.utils.JSONUtils;

/**
 * Per-instance manager for Mods, Resource Packs, Shader Packs, World Datapacks, and Config files.
 *
 * <p>Never executes mod code; uses atomic file transitions, Modrinth SHA-1 update checks against
 * the official v2 API, snapshot backup/restore for mod enable states, and automatic {@code .bak}
 * backups before config resets or edits.
 */
public final class InstanceContentManager {
    public static final String MODRINTH_HASH_API = "https://api.modrinth.com/v2/version_file/";
    private static final long MAX_CONFIG_PREVIEW_BYTES = 256 * 1024L;

    public static final class ContentItem {
        @NonNull public final File file;
        @NonNull public final String name;
        @NonNull public final String subtitle;
        public final long sizeBytes;
        public final long lastModifiedMs;
        public final boolean enabled;
        public final int packFormat;
        @NonNull public final String warning;

        public ContentItem(@NonNull File file,
                           @NonNull String name,
                           @NonNull String subtitle,
                           long sizeBytes,
                           long lastModifiedMs,
                           boolean enabled,
                           int packFormat,
                           @NonNull String warning) {
            this.file = file;
            this.name = name;
            this.subtitle = subtitle;
            this.sizeBytes = sizeBytes;
            this.lastModifiedMs = lastModifiedMs;
            this.enabled = enabled;
            this.packFormat = packFormat;
            this.warning = warning;
        }
    }

    public static final class ModUpdateInfo {
        public final boolean recognizedOnModrinth;
        public final boolean updateAvailable;
        @NonNull public final String currentVersionNumber;
        @NonNull public final String latestVersionNumber;
        @Nullable public final String latestDownloadUrl;
        @Nullable public final String latestFileName;
        @Nullable public final String latestSha1;

        public ModUpdateInfo(boolean recognizedOnModrinth,
                             boolean updateAvailable,
                             @NonNull String currentVersionNumber,
                             @NonNull String latestVersionNumber,
                             @Nullable String latestDownloadUrl,
                             @Nullable String latestFileName,
                             @Nullable String latestSha1) {
            this.recognizedOnModrinth = recognizedOnModrinth;
            this.updateAvailable = updateAvailable;
            this.currentVersionNumber = currentVersionNumber;
            this.latestVersionNumber = latestVersionNumber;
            this.latestDownloadUrl = latestDownloadUrl;
            this.latestFileName = latestFileName;
            this.latestSha1 = latestSha1;
        }
    }

    private InstanceContentManager() {
    }

    /**
     * Scans {@code <gameDir>/mods} for {@code .jar} and {@code .jar.disabled} mods, parses static
     * metadata, and attaches duplicate/dependency/loader compatibility warnings.
     */
    @NonNull
    public static List<ModMetadataParser.ModInfo> listMods(@NonNull File gameDir,
                                                           @Nullable String instanceVersionId) {
        File modsDir = new File(gameDir, "mods");
        if (!modsDir.isDirectory()) return Collections.emptyList();
        File[] files = modsDir.listFiles(f -> {
            if (!f.isFile()) return false;
            String n = f.getName().toLowerCase(Locale.ROOT);
            return n.endsWith(".jar") || n.endsWith(".jar.disabled");
        });
        if (files == null || files.length == 0) return Collections.emptyList();

        List<ModMetadataParser.ModInfo> list = new ArrayList<>(files.length);
        for (File f : files) {
            list.add(ModMetadataParser.inspectModFile(f));
        }
        ModMetadataParser.analyzeCompatibility(list, instanceVersionId);
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    /**
     * Toggles a mod between active ({@code .jar}) and disabled ({@code .jar.disabled}).
     */
    @NonNull
    public static File setModEnabled(@NonNull File modFile, boolean enable) throws IOException {
        if (!modFile.isFile()) {
            throw new IOException("Mod file no longer exists: " + modFile.getName());
        }
        String name = modFile.getName();
        boolean currentlyDisabled = name.toLowerCase(Locale.ROOT).endsWith(".disabled");
        if (enable && !currentlyDisabled) return modFile;
        if (!enable && currentlyDisabled) return modFile;

        String targetName = enable
                ? name.substring(0, name.length() - ".disabled".length())
                : name + ".disabled";
        File target = new File(modFile.getParentFile(), targetName);
        if (target.exists()) {
            throw new IOException("Target file already exists: " + targetName);
        }
        if (!modFile.renameTo(target)) {
            throw new IOException("Failed to rename mod file: " + name);
        }
        return target;
    }

    /**
     * Safely imports a user-selected local file (mod JAR, resource pack ZIP, shader pack ZIP,
     * datapack ZIP, or config) into {@code destinationDir} with sanitized filename and atomic move.
     */
    @NonNull
    public static File importFileFromStream(@NonNull InputStream in,
                                            @NonNull File destinationDir,
                                            @NonNull String rawDisplayName,
                                            long maxBytes) throws IOException {
        FileUtils.ensureDirectory(destinationDir);
        String safeName = FileUtils.escapeFileName(rawDisplayName.trim());
        if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) {
            throw new IOException("Invalid filename");
        }
        File target = ArchiveSecurity.resolveSafeEntry(destinationDir, safeName);
        File temp = new File(destinationDir, "." + safeName + ".zyrox.tmp");
        ArchiveSecurity.copyBoundedToFile(in, temp, maxBytes, maxBytes);
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Cannot replace existing file: " + safeName);
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Failed to save imported file: " + safeName);
        }
        return target;
    }

    /**
     * Saves a lightweight JSON snapshot recording every installed mod's enabled/disabled state so
     * users can roll back mod toggle experiments cleanly before updating or troubleshooting.
     */
    @NonNull
    public static File saveModStateSnapshot(@NonNull File gameDir) throws IOException {
        File modsDir = new File(gameDir, "mods");
        File snapDir = new File(gameDir, ".zyrox_mod_snapshots");
        FileUtils.ensureDirectory(snapDir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File snapFile = new File(snapDir, "mods-snapshot-" + stamp + ".json");

        JsonObject root = new JsonObject();
        root.addProperty("format", "zyrox-mod-snapshot-v1");
        root.addProperty("createdAtMs", System.currentTimeMillis());
        JsonArray entries = new JsonArray();
        File[] files = modsDir.listFiles(File::isFile);
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                String low = n.toLowerCase(Locale.ROOT);
                if (!low.endsWith(".jar") && !low.endsWith(".jar.disabled")) continue;
                boolean enabled = !low.endsWith(".disabled");
                String baseJar = enabled ? n : n.substring(0, n.length() - ".disabled".length());
                JsonObject item = new JsonObject();
                item.addProperty("jar", baseJar);
                item.addProperty("enabled", enabled);
                item.addProperty("size", f.length());
                entries.add(item);
            }
        }
        root.add("mods", entries);
        JSONUtils.writeToFileAtomic(snapFile, root, false);
        return snapFile;
    }

    /**
     * Restores enabled/disabled states of installed mods from the most recent snapshot.
     *
     * @return number of mod states changed
     */
    public static int restoreLatestModStateSnapshot(@NonNull File gameDir) throws IOException {
        File snapDir = new File(gameDir, ".zyrox_mod_snapshots");
        File[] snaps = snapDir.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
        if (snaps == null || snaps.length == 0) {
            throw new IOException("No saved mod state snapshots found for this instance");
        }
        Arrays.sort(snaps, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        File latest = snaps[0];
        String text;
        try (FileInputStream in = new FileInputStream(latest)) {
            text = new String(ArchiveSecurity.readBytesBounded(in, 512 * 1024), StandardCharsets.UTF_8);
        }
        JsonObject root = new JsonParser().parse(text).getAsJsonObject();
        JsonArray mods = root.getAsJsonArray("mods");
        if (mods == null) return 0;

        File modsDir = new File(gameDir, "mods");
        int changed = 0;
        for (int i = 0; i < mods.size(); i++) {
            JsonObject item = mods.get(i).getAsJsonObject();
            String jar = item.get("jar").getAsString();
            if (jar.contains("..") || jar.contains("/") || jar.contains("\\")) continue;
            boolean wantEnabled = item.get("enabled").getAsBoolean();
            File active = new File(modsDir, jar);
            File disabled = new File(modsDir, jar + ".disabled");
            if (wantEnabled && disabled.isFile() && !active.exists()) {
                setModEnabled(disabled, true);
                changed++;
            } else if (!wantEnabled && active.isFile() && !disabled.exists()) {
                setModEnabled(active, false);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Checks the official Modrinth v2 hash endpoint to determine if a newer compatible release of
     * {@code modFile} exists for {@code mcVersion} and {@code loader} (zero hardcoded versions).
     */
    @NonNull
    public static ModUpdateInfo checkModrinthUpdate(@NonNull File modFile,
                                                    @NonNull String mcVersion,
                                                    @NonNull ModMetadataParser.LoaderFamily loader)
            throws IOException {
        String sha1 = HashUtils.getFileSHA1(modFile);
        if (sha1 == null || sha1.isEmpty()) {
            throw new IOException("Failed to compute mod SHA-1");
        }

        // 1. Identify current file via unified ModPlatformRepository (HTTP/2 pooled + cached)
        JsonObject curObj = git.artdeell.mojo.metadata.ModPlatformRepository
                .getInstance()
                .lookupModrinthVersionBySha1(sha1);
        if (curObj == null) {
            return new ModUpdateInfo(false, false, "", "", null, null, null);
        }
        String currentVersionNum = curObj.has("version_number")
                ? curObj.get("version_number").getAsString() : "";

        // 2. Query Modrinth /update for the exact MC version and loader via HTTP/2 pool
        JsonObject reqBody = new JsonObject();
        JsonArray loaders = new JsonArray();
        String loaderSlug = loader == ModMetadataParser.LoaderFamily.UNKNOWN
                ? "fabric" : loader.name().toLowerCase(Locale.ROOT);
        loaders.add(loaderSlug);
        JsonArray gameVers = new JsonArray();
        gameVers.add(mcVersion);
        reqBody.add("loaders", loaders);
        reqBody.add("game_versions", gameVers);

        String updText = git.artdeell.mojo.utils.ZyroxHttpClient.postJson(
                MODRINTH_HASH_API + sha1 + "/update?algorithm=sha1",
                reqBody.toString(),
                null);
        if (updText == null || updText.trim().isEmpty()) {
            return new ModUpdateInfo(true, false, currentVersionNum, currentVersionNum, null, null, null);
        }
        JsonObject updObj = JsonParser.parseString(updText).getAsJsonObject();
        String latestVer = updObj.has("version_number") ? updObj.get("version_number").getAsString() : "";
        String dlUrl = null;
        String dlName = null;
        String dlSha1 = null;
        if (updObj.has("files") && updObj.get("files").isJsonArray()) {
            JsonArray files = updObj.getAsJsonArray("files");
            for (int i = 0; i < files.size(); i++) {
                JsonObject f = files.get(i).getAsJsonObject();
                boolean primary = f.has("primary") && f.get("primary").getAsBoolean();
                if (i == 0 || primary) {
                    dlUrl = f.has("url") ? f.get("url").getAsString() : null;
                    dlName = f.has("filename") ? f.get("filename").getAsString() : null;
                    if (f.has("hashes") && f.get("hashes").isJsonObject()
                            && f.getAsJsonObject("hashes").has("sha1")) {
                        dlSha1 = f.getAsJsonObject("hashes").get("sha1").getAsString();
                    }
                    if (primary) break;
                }
            }
        }
        boolean hasNewer = dlSha1 != null && !dlSha1.equalsIgnoreCase(sha1) && dlUrl != null;
        return new ModUpdateInfo(true, hasNewer, currentVersionNum, latestVer, dlUrl, dlName, dlSha1);
    }

    /**
     * Lists Resource Packs in {@code <gameDir>/resourcepacks} with {@code pack.mcmeta} metadata.
     */
    @NonNull
    public static List<ContentItem> listResourcePacks(@NonNull File gameDir) {
        return listPackDirectory(new File(gameDir, "resourcepacks"));
    }

    /**
     * Lists Datapacks across a world's {@code datapacks/} directory with {@code pack.mcmeta} info.
     */
    @NonNull
    public static List<ContentItem> listWorldDatapacks(@NonNull File worldDir) {
        return listPackDirectory(new File(worldDir, "datapacks"));
    }

    @NonNull
    private static List<ContentItem> listPackDirectory(@NonNull File dir) {
        if (!dir.isDirectory()) return Collections.emptyList();
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return Collections.emptyList();
        List<ContentItem> out = new ArrayList<>();
        for (File f : children) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            String low = name.toLowerCase(Locale.ROOT);
            boolean enabled = !low.endsWith(".disabled");
            if (f.isFile() && (low.endsWith(".zip") || low.endsWith(".zip.disabled"))) {
                ModMetadataParser.PackMeta pm = new ModMetadataParser.PackMeta(-1, "");
                String warn = "";
                try (ZipFile zf = new ZipFile(f)) {
                    String mcmeta = ArchiveSecurity.readZipEntryTextBounded(zf, "pack.mcmeta", 65536);
                    if (mcmeta == null) {
                        warn = "Missing pack.mcmeta";
                    } else {
                        pm = ModMetadataParser.parsePackMcmeta(mcmeta);
                    }
                } catch (IOException e) {
                    warn = "Unreadable archive";
                }
                String sub = (pm.packFormat > 0 ? "Format " + pm.packFormat + "  •  " : "")
                        + (pm.description.isEmpty() ? "ZIP pack" : pm.description);
                out.add(new ContentItem(f, name.replaceAll("(?i)\\.disabled$", ""), sub,
                        f.length(), f.lastModified(), enabled, pm.packFormat, warn));
            } else if (f.isDirectory()) {
                File mcmetaFile = new File(f, "pack.mcmeta");
                ModMetadataParser.PackMeta pm = new ModMetadataParser.PackMeta(-1, "");
                String warn = "";
                if (mcmetaFile.isFile()) {
                    try (FileInputStream in = new FileInputStream(mcmetaFile)) {
                        pm = ModMetadataParser.parsePackMcmeta(
                                new String(ArchiveSecurity.readBytesBounded(in, 65536), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        warn = "Unreadable pack.mcmeta";
                    }
                } else {
                    warn = "Missing pack.mcmeta";
                }
                long bytes = InstanceManager.computeDirectoryBytes(f, 5000);
                String sub = (pm.packFormat > 0 ? "Format " + pm.packFormat + "  •  " : "")
                        + (pm.description.isEmpty() ? "Folder pack" : pm.description);
                out.add(new ContentItem(f, name, sub, bytes, f.lastModified(), true, pm.packFormat, warn));
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /**
     * Lists Shader Packs in {@code <gameDir>/shaderpacks}.
     */
    @NonNull
    public static List<ContentItem> listShaderPacks(@NonNull File gameDir) {
        File dir = new File(gameDir, "shaderpacks");
        if (!dir.isDirectory()) return Collections.emptyList();
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return Collections.emptyList();
        boolean shaderModPresent = hasShaderModInstalled(gameDir);
        String globalWarn = shaderModPresent
                ? ""
                : "Requires Iris, Oculus, or OptiFine + compatible renderer (LTW/Zink)";
        List<ContentItem> out = new ArrayList<>();
        for (File f : children) {
            String n = f.getName();
            if (n.startsWith(".") || n.toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
            if (f.isFile() && n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                out.add(new ContentItem(f, n, "Shader pack archive", f.length(),
                        f.lastModified(), true, -1, globalWarn));
            } else if (f.isDirectory()) {
                long bytes = InstanceManager.computeDirectoryBytes(f, 3000);
                out.add(new ContentItem(f, n, "Shader pack directory", bytes,
                        f.lastModified(), true, -1, globalWarn));
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static boolean hasShaderModInstalled(@NonNull File gameDir) {
        File modsDir = new File(gameDir, "mods");
        File[] files = modsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return false;
        for (File f : files) {
            String low = f.getName().toLowerCase(Locale.ROOT);
            if (low.contains("iris") || low.contains("oculus") || low.contains("optifine")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lists editable text configuration files inside {@code <gameDir>/config} (up to 1 subfolder deep).
     */
    @NonNull
    public static List<ContentItem> listConfigFiles(@NonNull File gameDir) {
        File cfgDir = new File(gameDir, "config");
        if (!cfgDir.isDirectory()) return Collections.emptyList();
        List<ContentItem> out = new ArrayList<>();
        collectConfigs(cfgDir, "", 0, out);
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static void collectConfigs(@NonNull File dir, @NonNull String prefix,
                                       int depth, @NonNull List<ContentItem> out) {
        if (depth > 1 || out.size() >= 250) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".bak") || n.endsWith(".tmp")) continue;
            String rel = prefix.isEmpty() ? n : (prefix + "/" + n);
            if (f.isDirectory()) {
                collectConfigs(f, rel, depth + 1, out);
            } else if (isSupportedConfigExtension(n)) {
                boolean hasBak = new File(f.getParentFile(), n + ".bak").isFile();
                out.add(new ContentItem(f, rel,
                        hasBak ? "Config file (.bak backup available)" : "Config file",
                        f.length(), f.lastModified(), true, -1, ""));
            }
        }
    }

    private static boolean isSupportedConfigExtension(@NonNull String name) {
        String low = name.toLowerCase(Locale.ROOT);
        return low.endsWith(".toml") || low.endsWith(".json") || low.endsWith(".json5")
                || low.endsWith(".properties") || low.endsWith(".cfg") || low.endsWith(".txt")
                || low.endsWith(".yml") || low.endsWith(".yaml");
    }

    @NonNull
    public static String readConfigFileSafe(@NonNull File configFile) throws IOException {
        if (configFile.length() > MAX_CONFIG_PREVIEW_BYTES) {
            throw new IOException("Config file exceeds 256 KB safe editor limit");
        }
        try (FileInputStream in = new FileInputStream(configFile)) {
            return new String(ArchiveSecurity.readBytesBounded(in, (int) MAX_CONFIG_PREVIEW_BYTES),
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * Atomically updates a config file while automatically preserving the prior text as {@code .bak}.
     */
    public static void writeConfigFileWithBackup(@NonNull File configFile, @NonNull String newContent)
            throws IOException {
        File parent = configFile.getParentFile();
        if (parent != null && configFile.isFile()) {
            File bak = new File(parent, configFile.getName() + ".bak");
            try (FileInputStream in = new FileInputStream(configFile);
                 FileOutputStream out = new FileOutputStream(bak, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
        File temp = new File(parent, configFile.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(newContent.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (configFile.exists() && !configFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Unable to overwrite config file");
        }
        if (!temp.renameTo(configFile)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Unable to finalize config save");
        }
    }

    /**
     * Resets a config file by backing it up to {@code .bak} and deleting the active file so the mod
     * regenerates clean defaults on next launch.
     */
    public static void resetConfigToDefaultWithBackup(@NonNull File configFile) throws IOException {
        if (!configFile.isFile()) return;
        File bak = new File(configFile.getParentFile(), configFile.getName() + ".bak");
        try (FileInputStream in = new FileInputStream(configFile);
             FileOutputStream out = new FileOutputStream(bak, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (!configFile.delete()) {
            throw new IOException("Failed to remove config file after creating .bak backup");
        }
    }
}
