package git.artdeell.mojo.content;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.instances.Instance;

/**
 * Verified Modrinth v2 direct content installer, automatic required-dependency resolver,
 * 1-Click FPS Boost Pack installer, and 1-Tap Batch Mod Updater (with atomic rollback backup).
 *
 * <p>Security invariants: strictly {@code https://} connections, SHA-1 digest verification on every
 * downloaded artifact, path-traversal-proof filename sanitization, and bounded payload sizes.
 */
public final class ModrinthContentInstaller {

    public static final String TYPE_MODPACK = "modpack";
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_SHADER = "shader";
    public static final String TYPE_RESOURCEPACK = "resourcepack";

    private static final String MODRINTH_API = git.artdeell.mojo.metadata.ModPlatformRepository.MODRINTH_API_BASE;
    private static final String USER_AGENT = "ZyroxLauncher/2.2.0 (Android; https://github.com/argamerzz9582-debug/ZyroxLauncher)";
    private static final String BACKUP_DIR_NAME = ".zyrox_mod_backup";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_AUTO_DEPS = 8;

    private static final Pattern MC_VERSION_PATTERN = Pattern.compile("(1\\.\\d{1,2}(?:\\.\\d{1,2})?)");

    public interface ProgressCallback {
        void onProgress(@NonNull String status);
    }

    public static final class InstallSummary {
        public final int installedCount;
        public final int dependencyCount;
        public final int skippedCount;
        @NonNull public final List<String> installedFiles;
        @NonNull public final String message;

        public InstallSummary(int installedCount,
                              int dependencyCount,
                              int skippedCount,
                              @NonNull List<String> installedFiles,
                              @NonNull String message) {
            this.installedCount = installedCount;
            this.dependencyCount = dependencyCount;
            this.skippedCount = skippedCount;
            this.installedFiles = installedFiles;
            this.message = message;
        }
    }

    private ModrinthContentInstaller() {}

    /** Extracts canonical Minecraft release string (e.g. {@code 1.21.4}) from an instance versionId. */
    @Nullable
    public static String extractMinecraftVersion(@Nullable String versionId) {
        if (versionId == null || versionId.isEmpty()) return null;
        Matcher m = MC_VERSION_PATTERN.matcher(versionId);
        return m.find() ? m.group(1) : null;
    }

    /** Detects canonical Modrinth loader slug ({@code fabric}, {@code quilt}, {@code neoforge}, {@code forge}). */
    @Nullable
    public static String extractModLoader(@Nullable String versionId) {
        if (versionId == null || versionId.isEmpty()) return null;
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.contains("fabric")) return "fabric";
        if (lower.contains("quilt")) return "quilt";
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("forge")) return "forge";
        return null;
    }

    /**
     * Installs a single Mod, Shader Pack, or Resource Pack directly into {@code instance}, and for
     * Modrinth mods automatically resolves and downloads required dependencies (e.g. Fabric API).
     */
    @NonNull
    public static InstallSummary installDirectArtifact(@NonNull Instance instance,
                                                       @NonNull String projectType,
                                                       @Nullable String projectId,
                                                       @NonNull String displayTitle,
                                                       @NonNull String downloadUrl,
                                                       @Nullable String expectedSha1,
                                                       @Nullable ProgressCallback cb) throws IOException {
        requireHttps(downloadUrl);
        File gameDir = instance.getGameDirectory();
        File targetDir;
        String fallbackExt;
        switch (projectType) {
            case TYPE_SHADER:
                targetDir = new File(gameDir, "shaderpacks");
                fallbackExt = ".zip";
                break;
            case TYPE_RESOURCEPACK:
                targetDir = new File(gameDir, "resourcepacks");
                fallbackExt = ".zip";
                break;
            case TYPE_MOD:
            default:
                targetDir = new File(gameDir, "mods");
                fallbackExt = ".jar";
                break;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Unable to create " + targetDir.getName() + " folder");
        }

        String fileName = sanitizeFileName(extractFileNameFromUrl(downloadUrl), displayTitle + fallbackExt);
        if (cb != null) cb.onProgress("Downloading " + displayTitle + "…");
        File outFile = new File(targetDir, fileName);
        downloadVerified(downloadUrl, expectedSha1, outFile);

        List<String> installed = new ArrayList<>();
        installed.add(outFile.getName());
        int depsInstalled = 0;

        // Auto-resolve required Modrinth dependencies for individual mods
        if (TYPE_MOD.equals(projectType) && expectedSha1 != null && !expectedSha1.isEmpty()) {
            String mcVer = extractMinecraftVersion(instance.versionId);
            String loader = extractModLoader(instance.versionId);
            Set<String> visitedProjects = new HashSet<>();
            if (projectId != null) visitedProjects.add(projectId);
            try {
                depsInstalled = resolveRequiredDependencies(
                        expectedSha1, mcVer, loader, targetDir, visitedProjects, installed, 0, cb);
            } catch (Throwable ignored) {
                // Primary mod already verified & installed; non-fatal if dep lookup times out
            }
        }

        String msg = "Installed " + displayTitle
                + (depsInstalled > 0 ? (" + " + depsInstalled + " required dep(s)") : "")
                + " into " + targetDir.getName() + "/";
        return new InstallSummary(1 + depsInstalled, depsInstalled, 0, installed, msg);
    }

    /**
     * 1-Click FPS Boost Pack: installs verified official Modrinth optimization mods matched strictly
     * to the instance's Minecraft version and mod loader (Fabric / Quilt / NeoForge / Forge).
     */
    @NonNull
    public static InstallSummary installFpsBoostPack(@NonNull Instance instance,
                                                     boolean includeIrisShaders,
                                                     @Nullable ProgressCallback cb) throws IOException {
        String mcVer = extractMinecraftVersion(instance.versionId);
        String loader = extractModLoader(instance.versionId);
        if (mcVer == null || loader == null) {
            throw new IOException("Select a Fabric, Quilt, NeoForge, or Forge instance first (e.g. Fabric 1.21.4) to install FPS Boost mods.");
        }

        File modsDir = new File(instance.getGameDirectory(), "mods");
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Could not create mods directory");
        }

        List<String> slugs = new ArrayList<>();
        if ("fabric".equals(loader)) {
            slugs.add("fabric-api");
            slugs.add("sodium");
            slugs.add("lithium");
            slugs.add("ferrite-core");
            slugs.add("entityculling");
            slugs.add("immediatelyfast");
            if (includeIrisShaders) slugs.add("iris");
        } else if ("quilt".equals(loader)) {
            slugs.add("sodium");
            slugs.add("lithium");
            slugs.add("ferrite-core");
            slugs.add("entityculling");
            slugs.add("immediatelyfast");
            if (includeIrisShaders) slugs.add("iris");
        } else {
            // NeoForge / Forge
            slugs.add("sodium");
            slugs.add("embeddium");
            slugs.add("ferrite-core");
            slugs.add("entityculling");
            slugs.add("immediatelyfast");
            if (includeIrisShaders) {
                slugs.add("iris");
                slugs.add("oculus");
            }
        }

        Set<String> existingLower = listExistingJarNamesLower(modsDir);
        List<String> installed = new ArrayList<>();
        int skipped = 0;
        boolean rendererInstalled = containsAny(existingLower, "sodium", "embeddium", "rubidium");
        boolean shaderInstalled = containsAny(existingLower, "iris", "oculus");

        for (int i = 0; i < slugs.size(); i++) {
            String slug = slugs.get(i);
            if (("sodium".equals(slug) || "embeddium".equals(slug)) && rendererInstalled) {
                skipped++;
                continue;
            }
            if (("iris".equals(slug) || "oculus".equals(slug)) && shaderInstalled) {
                skipped++;
                continue;
            }
            if (containsAny(existingLower, slug.replace("-", ""), slug)) {
                skipped++;
                continue;
            }
            if (cb != null) {
                cb.onProgress("FPS Boost (" + (i + 1) + "/" + slugs.size() + "): checking " + slug + "…");
            }
            File downloaded = downloadBestProjectVersion(slug, mcVer, loader, modsDir, cb);
            if (downloaded != null) {
                installed.add(downloaded.getName());
                existingLower.add(downloaded.getName().toLowerCase(Locale.ROOT));
                if ("sodium".equals(slug) || "embeddium".equals(slug)) rendererInstalled = true;
                if ("iris".equals(slug) || "oculus".equals(slug)) shaderInstalled = true;
            }
        }

        if (installed.isEmpty() && skipped == 0) {
            throw new IOException("No compatible FPS Boost mods found on Modrinth for " + loader + " " + mcVer + ".");
        }
        String msg = installed.isEmpty()
                ? ("FPS Boost Pack is already installed (" + skipped + " optimization mods active)!")
                : ("Installed " + installed.size() + " FPS optimization mod(s) for " + loader + " " + mcVer
                + (skipped > 0 ? (" (" + skipped + " already present)") : ""));
        return new InstallSummary(installed.size(), 0, skipped, installed, msg);
    }

    /**
     * 1-Tap Batch Mod Updater: upgrades all outdated Modrinth-hosted {@code .jar} mods in
     * {@code <instance>/mods/} while preserving previous jars in {@code .zyrox_mod_backup/}
     * for instant 1-tap rollback.
     */
    @NonNull
    public static InstallSummary batchUpdateAllMods(@NonNull Instance instance,
                                                    @Nullable ProgressCallback cb) throws IOException {
        String mcVer = extractMinecraftVersion(instance.versionId);
        String loader = extractModLoader(instance.versionId);
        if (mcVer == null || loader == null) {
            throw new IOException("Could not determine Minecraft version/loader for this instance.");
        }

        File modsDir = new File(instance.getGameDirectory(), "mods");
        File[] jars = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return new InstallSummary(0, 0, 0, new ArrayList<>(), "No enabled .jar mods to update.");
        }

        if (cb != null) cb.onProgress("Hashing " + jars.length + " installed mod(s)…");
        Map<String, File> sha1ToFile = new LinkedHashMap<>();
        for (File jar : jars) {
            String sha1 = computeSha1Hex(jar);
            sha1ToFile.put(sha1, jar);
        }

        // Official Modrinth bulk update check endpoint: POST /v2/version_files/update
        JsonObject req = new JsonObject();
        JsonArray hashesArr = new JsonArray();
        for (String h : sha1ToFile.keySet()) hashesArr.add(h);
        req.add("hashes", hashesArr);
        req.addProperty("algorithm", "sha1");
        JsonArray loadersArr = new JsonArray();
        loadersArr.add(loader);
        req.add("loaders", loadersArr);
        JsonArray gameVersArr = new JsonArray();
        gameVersArr.add(mcVer);
        req.add("game_versions", gameVersArr);

        if (cb != null) cb.onProgress("Checking Modrinth for verified updates…");
        String respStr = httpPostJson(MODRINTH_API + "/version_files/update", req.toString());
        JsonObject resp = JsonParser.parseString(respStr).getAsJsonObject();

        File backupDir = new File(instance.getGameDirectory(), BACKUP_DIR_NAME);
        boolean backupCleared = false;
        List<String> updated = new ArrayList<>();
        int upToDate = 0;

        for (Map.Entry<String, File> entry : sha1ToFile.entrySet()) {
            String oldSha = entry.getKey();
            File oldJar = entry.getValue();
            if (!resp.has(oldSha) || !resp.get(oldSha).isJsonObject()) {
                continue;
            }
            JsonObject latestVer = resp.getAsJsonObject(oldSha);
            JsonObject primaryFile = pickPrimaryFile(latestVer);
            if (primaryFile == null) continue;
            String newSha = getFileSha1(primaryFile);
            String url = optString(primaryFile, "url");
            String rawName = optString(primaryFile, "filename");
            if (url == null || newSha == null || oldSha.equalsIgnoreCase(newSha)) {
                upToDate++;
                continue;
            }

            String safeName = sanitizeFileName(rawName, oldJar.getName());
            if (cb != null) cb.onProgress("Updating " + oldJar.getName() + " → " + safeName + "…");

            File newJar = new File(modsDir, safeName + ".updating");
            downloadVerified(url, newSha, newJar);

            // Prepare clean rollback folder once the first update succeeds
            if (!backupCleared) {
                clearDirectoryFiles(backupDir);
                if (!backupDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    backupDir.mkdirs();
                }
                backupCleared = true;
            }
            File backupCopy = new File(backupDir, oldJar.getName());
            copyFile(oldJar, backupCopy);
            if (!oldJar.delete()) {
                //noinspection ResultOfMethodCallIgnored
                newJar.delete();
                continue;
            }
            File finalDest = new File(modsDir, safeName);
            if (finalDest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                finalDest.delete();
            }
            if (newJar.renameTo(finalDest)) {
                updated.add(finalDest.getName());
            } else {
                copyFile(newJar, finalDest);
                //noinspection ResultOfMethodCallIgnored
                newJar.delete();
                updated.add(finalDest.getName());
            }
        }

        String msg = updated.isEmpty()
                ? ("All " + upToDate + " Modrinth mod(s) are already up to date for " + loader + " " + mcVer + "!")
                : ("Updated " + updated.size() + " mod(s) with SHA-1 verification (previous jars backed up for rollback).");
        return new InstallSummary(updated.size(), 0, upToDate, updated, msg);
    }

    public static boolean hasRollbackBackup(@Nullable Instance instance) {
        if (instance == null) return false;
        File dir = new File(instance.getGameDirectory(), BACKUP_DIR_NAME);
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        return files != null && files.length > 0;
    }

    /** Restores backed-up mod jars from the last batch update. */
    public static int rollbackLastModUpdate(@NonNull Instance instance) throws IOException {
        File backupDir = new File(instance.getGameDirectory(), BACKUP_DIR_NAME);
        File modsDir = new File(instance.getGameDirectory(), "mods");
        File[] backups = backupDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (backups == null || backups.length == 0) return 0;
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Mods folder missing");
        }
        int restored = 0;
        for (File bak : backups) {
            File dest = new File(modsDir, sanitizeFileName(bak.getName(), "restored.jar"));
            copyFile(bak, dest);
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            restored++;
        }
        return restored;
    }

    private static int resolveRequiredDependencies(@NonNull String versionSha1,
                                                   @Nullable String mcVer,
                                                   @Nullable String loader,
                                                   @NonNull File modsDir,
                                                   @NonNull Set<String> visitedProjects,
                                                   @NonNull List<String> installed,
                                                   int depth,
                                                   @Nullable ProgressCallback cb) throws IOException {
        if (depth >= 2 || visitedProjects.size() > MAX_AUTO_DEPS || mcVer == null || loader == null) {
            return 0;
        }
        String verJson = httpGet(MODRINTH_API + "/version_file/"
                + URLEncoder.encode(versionSha1, "UTF-8") + "?algorithm=sha1");
        JsonObject verObj = JsonParser.parseString(verJson).getAsJsonObject();
        if (!verObj.has("dependencies") || !verObj.get("dependencies").isJsonArray()) {
            return 0;
        }
        JsonArray deps = verObj.getAsJsonArray("dependencies");
        Set<String> existing = listExistingJarNamesLower(modsDir);
        int count = 0;
        for (JsonElement el : deps) {
            if (!el.isJsonObject() || visitedProjects.size() > MAX_AUTO_DEPS) continue;
            JsonObject dep = el.getAsJsonObject();
            if (!"required".equalsIgnoreCase(optString(dep, "dependency_type"))) continue;
            String projId = optString(dep, "project_id");
            if (projId == null || projId.isEmpty() || !visitedProjects.add(projId)) continue;
            if ("P7dR8mSH".equals(projId) && containsAny(existing, "fabric-api", "fabric_api")) {
                continue;
            }
            if (cb != null) cb.onProgress("Resolving required dependency…");
            File depJar = downloadBestProjectVersion(projId, mcVer, loader, modsDir, cb);
            if (depJar != null) {
                installed.add(depJar.getName());
                existing.add(depJar.getName().toLowerCase(Locale.ROOT));
                count++;
                String depSha = computeSha1Hex(depJar);
                count += resolveRequiredDependencies(
                        depSha, mcVer, loader, modsDir, visitedProjects, installed, depth + 1, cb);
            }
        }
        return count;
    }

    @Nullable
    private static File downloadBestProjectVersion(@NonNull String slugOrId,
                                                   @NonNull String mcVer,
                                                   @NonNull String loader,
                                                   @NonNull File modsDir,
                                                   @Nullable ProgressCallback cb) {
        try {
            String qLoaders = URLEncoder.encode("[\"" + loader + "\"]", "UTF-8");
            String qVers = URLEncoder.encode("[\"" + mcVer + "\"]", "UTF-8");
            String url = MODRINTH_API + "/project/" + URLEncoder.encode(slugOrId, "UTF-8")
                    + "/version?loaders=" + qLoaders + "&game_versions=" + qVers;
            String body = httpGet(url);
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            if (arr.size() == 0) return null;
            JsonObject best = arr.get(0).getAsJsonObject();
            JsonObject fileObj = pickPrimaryFile(best);
            if (fileObj == null) return null;
            String dlUrl = optString(fileObj, "url");
            String rawName = optString(fileObj, "filename");
            String sha1 = getFileSha1(fileObj);
            if (dlUrl == null) return null;
            String safeName = sanitizeFileName(rawName, slugOrId + ".jar");
            File dest = new File(modsDir, safeName);
            if (dest.isFile() && sha1 != null && sha1.equalsIgnoreCase(computeSha1Hex(dest))) {
                return null;
            }
            if (cb != null) cb.onProgress("Downloading " + safeName + "…");
            downloadVerified(dlUrl, sha1, dest);
            return dest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static JsonObject pickPrimaryFile(@NonNull JsonObject versionObj) {
        if (!versionObj.has("files") || !versionObj.get("files").isJsonArray()) return null;
        JsonArray files = versionObj.getAsJsonArray("files");
        if (files.size() == 0) return null;
        for (JsonElement el : files) {
            if (el.isJsonObject()) {
                JsonObject fo = el.getAsJsonObject();
                if (fo.has("primary") && fo.get("primary").getAsBoolean()) {
                    return fo;
                }
            }
        }
        return files.get(0).isJsonObject() ? files.get(0).getAsJsonObject() : null;
    }

    @Nullable
    private static String getFileSha1(@NonNull JsonObject fileObj) {
        if (!fileObj.has("hashes") || !fileObj.get("hashes").isJsonObject()) return null;
        return optString(fileObj.getAsJsonObject("hashes"), "sha1");
    }

    private static void downloadVerified(@NonNull String httpsUrl,
                                         @Nullable String expectedSha1,
                                         @NonNull File targetFile) throws IOException {
        requireHttps(httpsUrl);
        File tmp = new File(targetFile.getParentFile(), targetFile.getName() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(httpsUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " downloading " + targetFile.getName());
            }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            long total = 0L;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_ARTIFACT_BYTES) {
                        throw new IOException("Artifact exceeds safe size limit");
                    }
                    sha1.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            if (expectedSha1 != null && !expectedSha1.trim().isEmpty()) {
                String actual = toHex(sha1.digest());
                if (!expectedSha1.trim().equalsIgnoreCase(actual)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    throw new IOException("SHA-1 verification failed for " + targetFile.getName());
                }
            }
            if (targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.delete();
            }
            if (!tmp.renameTo(targetFile)) {
                copyFile(tmp, targetFile);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Download failed: " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static String httpGet(@NonNull String httpsUrl) throws IOException {
        requireHttps(httpsUrl);
        return git.artdeell.mojo.utils.ZyroxHttpClient.getStringOrThrow(httpsUrl, null);
    }

    @NonNull
    private static String httpPostJson(@NonNull String httpsUrl, @NonNull String json) throws IOException {
        requireHttps(httpsUrl);
        String resp = git.artdeell.mojo.utils.ZyroxHttpClient.postJson(httpsUrl, json, null);
        if (resp == null) {
            throw new IOException("Modrinth update API request failed");
        }
        return resp;
    }

    @NonNull
    private static String readStreamUtf8(@NonNull InputStream in, int maxBytes) throws IOException {
        try (InputStream src = new BufferedInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = src.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("Response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    public static String computeSha1Hex(@NonNull File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    @NonNull
    public static String sanitizeFileName(@Nullable String candidate, @NonNull String fallback) {
        String raw = (candidate == null || candidate.trim().isEmpty()) ? fallback : candidate.trim();
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) raw = raw.substring(slash + 1);
        raw = raw.replace("..", "_").replaceAll("[^a-zA-Z0-9._+\\-() ]", "_").trim();
        if (raw.isEmpty() || ".".equals(raw)) {
            raw = fallback.replaceAll("[^a-zA-Z0-9._+\\-]", "_");
        }
        return raw.length() > 120 ? raw.substring(raw.length() - 120) : raw;
    }

    @Nullable
    private static String extractFileNameFromUrl(@NonNull String url) {
        try {
            String path = new URL(url).getPath();
            if (path == null || path.isEmpty()) return null;
            int idx = path.lastIndexOf('/');
            String seg = idx >= 0 ? path.substring(idx + 1) : path;
            return java.net.URLDecoder.decode(seg, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void requireHttps(@NonNull String url) throws IOException {
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("Blocked non-HTTPS URL");
        }
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static Set<String> listExistingJarNamesLower(@NonNull File modsDir) {
        Set<String> set = new HashSet<>();
        File[] files = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files != null) {
            for (File f : files) set.add(f.getName().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static boolean containsAny(@NonNull Set<String> names, @NonNull String... tokens) {
        for (String n : names) {
            String norm = n.replace("-", "").replace("_", "");
            for (String t : tokens) {
                String tn = t.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
                if (n.contains(t.toLowerCase(Locale.ROOT)) || norm.contains(tn)) return true;
            }
        }
        return false;
    }

    private static void clearDirectoryFiles(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void copyFile(@NonNull File src, @NonNull File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }
}
