package git.artdeell.mojo.content;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Safe, zero-execution static metadata inspector for Minecraft mod JARs and resource/data packs.
 *
 * <p>Supports {@code fabric.mod.json}, {@code quilt.mod.json}, {@code META-INF/neoforge.mods.toml},
 * {@code META-INF/mods.toml}, legacy {@code mcmod.info}, and {@code pack.mcmeta}. Never loads or
 * executes any class inside a JAR.
 */
public final class ModMetadataParser {
    private static final int MAX_MOD_META_BYTES = 256 * 1024;
    private static final Set<String> BUILTIN_DEP_IDS = new HashSet<>();

    static {
        Collections.addAll(BUILTIN_DEP_IDS,
                "minecraft", "java", "fabricloader", "quilt_loader",
                "forge", "neoforge", "fml", "mixinextras");
    }

    private static final Pattern TOML_KV_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9_]+)\\s*=\\s*\"([^\"]*)\""
    );
    private static final Pattern TOML_BOOL_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9_]+)\\s*=\\s*(true|false)\\b", Pattern.CASE_INSENSITIVE
    );

    public enum LoaderFamily {
        FABRIC,
        QUILT,
        FORGE,
        NEOFORGE,
        UNKNOWN
    }

    public static final class ModInfo {
        @NonNull public final File file;
        public final boolean enabled;
        @NonNull public final String modId;
        @NonNull public final String name;
        @NonNull public final String version;
        @NonNull public final String description;
        @NonNull public final String authors;
        @NonNull public final LoaderFamily loader;
        @Nullable public final String minecraftConstraint;
        @NonNull public final List<String> requiredDependencies;
        @NonNull public final List<String> providedIds;
        public final boolean corruptJar;
        public final long sizeBytes;
        public final long lastModifiedMs;
        @NonNull public final List<String> warnings = new ArrayList<>();

        public ModInfo(@NonNull File file,
                       boolean enabled,
                       @NonNull String modId,
                       @NonNull String name,
                       @NonNull String version,
                       @NonNull String description,
                       @NonNull String authors,
                       @NonNull LoaderFamily loader,
                       @Nullable String minecraftConstraint,
                       @NonNull List<String> requiredDependencies,
                       @NonNull List<String> providedIds,
                       boolean corruptJar) {
            this.file = file;
            this.enabled = enabled;
            this.modId = modId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.authors = authors;
            this.loader = loader;
            this.minecraftConstraint = minecraftConstraint;
            this.requiredDependencies = Collections.unmodifiableList(requiredDependencies);
            this.providedIds = Collections.unmodifiableList(providedIds);
            this.corruptJar = corruptJar;
            this.sizeBytes = file.length();
            this.lastModifiedMs = file.lastModified();
        }
    }

    public static final class PackMeta {
        public final int packFormat;
        @NonNull public final String description;

        public PackMeta(int packFormat, @NonNull String description) {
            this.packFormat = packFormat;
            this.description = description;
        }
    }

    private ModMetadataParser() {
    }

    /**
     * Inspects a {@code .jar} or {@code .jar.disabled} file statically without executing mod code.
     */
    @NonNull
    public static ModInfo inspectModFile(@NonNull File file) {
        String fileName = file.getName();
        boolean enabled = !fileName.toLowerCase(Locale.ROOT).endsWith(".disabled");
        String baseName = fileName
                .replaceAll("(?i)\\.disabled$", "")
                .replaceAll("(?i)\\.jar$", "");

        if (!file.isFile() || file.length() < 22L) {
            ModInfo corrupt = new ModInfo(file, enabled, baseName.toLowerCase(Locale.ROOT), baseName,
                    "unknown", "Unreadable or empty JAR archive", "", LoaderFamily.UNKNOWN,
                    null, Collections.emptyList(), Collections.emptyList(), true);
            corrupt.warnings.add("Empty or corrupted JAR file");
            return corrupt;
        }

        try (ZipFile zf = new ZipFile(file)) {
            String quiltJson = ArchiveSecurity.readZipEntryTextBounded(zf, "quilt.mod.json", MAX_MOD_META_BYTES);
            if (quiltJson != null) {
                ModInfo info = parseQuiltModJson(file, enabled, baseName, quiltJson);
                if (info != null) return info;
            }
            String fabricJson = ArchiveSecurity.readZipEntryTextBounded(zf, "fabric.mod.json", MAX_MOD_META_BYTES);
            if (fabricJson != null) {
                ModInfo info = parseFabricModJson(file, enabled, baseName, fabricJson);
                if (info != null) return info;
            }
            String neoToml = ArchiveSecurity.readZipEntryTextBounded(zf, "META-INF/neoforge.mods.toml", MAX_MOD_META_BYTES);
            if (neoToml != null) {
                ModInfo info = parseModsToml(file, enabled, baseName, neoToml, LoaderFamily.NEOFORGE);
                if (info != null) return info;
            }
            String forgeToml = ArchiveSecurity.readZipEntryTextBounded(zf, "META-INF/mods.toml", MAX_MOD_META_BYTES);
            if (forgeToml != null) {
                LoaderFamily fam = forgeToml.toLowerCase(Locale.ROOT).contains("modid=\"neoforge\"")
                        ? LoaderFamily.NEOFORGE : LoaderFamily.FORGE;
                ModInfo info = parseModsToml(file, enabled, baseName, forgeToml, fam);
                if (info != null) return info;
            }
            String mcmod = ArchiveSecurity.readZipEntryTextBounded(zf, "mcmod.info", MAX_MOD_META_BYTES);
            if (mcmod != null) {
                ModInfo info = parseMcModInfo(file, enabled, baseName, mcmod);
                if (info != null) return info;
            }
            return new ModInfo(file, enabled, baseName.toLowerCase(Locale.ROOT), baseName,
                    "unlisted", "Library / coremod / OptiFine JAR (no standard mod descriptor)",
                    "", LoaderFamily.UNKNOWN, null, Collections.emptyList(), Collections.emptyList(), false);
        } catch (IOException e) {
            ModInfo corrupt = new ModInfo(file, enabled, baseName.toLowerCase(Locale.ROOT), baseName,
                    "unknown", "Damaged archive: " + e.getMessage(), "", LoaderFamily.UNKNOWN,
                    null, Collections.emptyList(), Collections.emptyList(), true);
            corrupt.warnings.add("Corrupted ZIP/JAR archive");
            return corrupt;
        }
    }

    @Nullable
    static ModInfo parseFabricModJson(@NonNull File file, boolean enabled,
                                      @NonNull String fallbackName, @NonNull String jsonText) {
        try {
            JsonObject root = new JsonParser().parse(jsonText).getAsJsonObject();
            String id = optStr(root, "id", fallbackName.toLowerCase(Locale.ROOT));
            String name = optStr(root, "name", fallbackName);
            String ver = optStr(root, "version", "unknown");
            String desc = optStr(root, "description", "");
            String authors = extractJsonAuthors(root.get("authors"));
            String mcRange = null;
            Set<String> deps = new LinkedHashSet<>();
            if (root.has("depends") && root.get("depends").isJsonObject()) {
                JsonObject depObj = root.getAsJsonObject("depends");
                for (Map.Entry<String, JsonElement> entry : depObj.entrySet()) {
                    String depId = entry.getKey().toLowerCase(Locale.ROOT);
                    if ("minecraft".equals(depId)) {
                        mcRange = flattenVersionConstraint(entry.getValue());
                    } else if (!BUILTIN_DEP_IDS.contains(depId)) {
                        deps.add(depId);
                    }
                }
            }
            Set<String> provides = new LinkedHashSet<>();
            if (root.has("provides") && root.get("provides").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("provides")) {
                    if (el.isJsonPrimitive()) provides.add(el.getAsString().toLowerCase(Locale.ROOT));
                }
            }
            // Fabric API provides both "fabric-api" and "fabric" plus nested jar modules
            if ("fabric-api".equals(id) || "fabric".equals(id)) {
                provides.add("fabric");
                provides.add("fabric-api");
            }
            if (root.has("jars") && root.get("jars").isJsonArray()) {
                // Track bundled fabric-api modules roughly from jar file names
                for (JsonElement el : root.getAsJsonArray("jars")) {
                    if (el.isJsonObject() && el.getAsJsonObject().has("file")) {
                        String jf = el.getAsJsonObject().get("file").getAsString();
                        int slash = jf.lastIndexOf('/');
                        String base = slash >= 0 ? jf.substring(slash + 1) : jf;
                        int dashNum = base.indexOf("-0.");
                        if (dashNum < 0) dashNum = base.indexOf("-1.");
                        if (dashNum < 0) dashNum = base.indexOf("-2.");
                        if (dashNum > 0) {
                            provides.add(base.substring(0, dashNum).toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            return new ModInfo(file, enabled, id.toLowerCase(Locale.ROOT), name, ver, desc, authors,
                    LoaderFamily.FABRIC, mcRange, new ArrayList<>(deps), new ArrayList<>(provides), false);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static ModInfo parseQuiltModJson(@NonNull File file, boolean enabled,
                                     @NonNull String fallbackName, @NonNull String jsonText) {
        try {
            JsonObject root = new JsonParser().parse(jsonText).getAsJsonObject();
            JsonObject loader = root.getAsJsonObject("quilt_loader");
            if (loader == null) return null;
            String id = optStr(loader, "id", fallbackName.toLowerCase(Locale.ROOT));
            String ver = optStr(loader, "version", "unknown");
            String name = fallbackName;
            String desc = "";
            String authors = "";
            if (loader.has("metadata") && loader.get("metadata").isJsonObject()) {
                JsonObject meta = loader.getAsJsonObject("metadata");
                name = optStr(meta, "name", fallbackName);
                desc = optStr(meta, "description", "");
                if (meta.has("contributors") && meta.get("contributors").isJsonObject()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, JsonElement> c : meta.getAsJsonObject("contributors").entrySet()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(c.getKey());
                    }
                    authors = sb.toString();
                }
            }
            String mcRange = null;
            Set<String> deps = new LinkedHashSet<>();
            if (loader.has("depends") && loader.get("depends").isJsonArray()) {
                for (JsonElement el : loader.getAsJsonArray("depends")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject d = el.getAsJsonObject();
                    boolean optional = d.has("optional") && d.get("optional").getAsBoolean();
                    if (optional) continue;
                    String depId = optStr(d, "id", "").toLowerCase(Locale.ROOT);
                    if ("minecraft".equals(depId)) {
                        mcRange = flattenVersionConstraint(d.get("versions"));
                    } else if (!depId.isEmpty() && !BUILTIN_DEP_IDS.contains(depId)) {
                        deps.add(depId);
                    }
                }
            }
            List<String> provides = new ArrayList<>();
            if ("quilted_fabric_api".equals(id) || "qsl".equals(id)) {
                provides.add("fabric");
                provides.add("fabric-api");
            }
            return new ModInfo(file, enabled, id.toLowerCase(Locale.ROOT), name, ver, desc, authors,
                    LoaderFamily.QUILT, mcRange, new ArrayList<>(deps), provides, false);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static ModInfo parseModsToml(@NonNull File file, boolean enabled,
                                 @NonNull String fallbackName, @NonNull String tomlText,
                                 @NonNull LoaderFamily defaultFamily) {
        String modId = null;
        String displayName = null;
        String version = null;
        String authors = "";
        String description = "";
        String mcRange = null;
        LoaderFamily detectedFamily = defaultFamily;
        Set<String> requiredDeps = new LinkedHashSet<>();

        boolean inModsBlock = false;
        boolean inDepBlock = false;
        String currentDepId = null;
        String currentDepRange = null;
        boolean currentDepRequired = true;

        String[] lines = tomlText.split("\\r?\\n");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[[")) {
                // Flush previous dependency block
                if (inDepBlock && currentDepId != null) {
                    String depLow = currentDepId.toLowerCase(Locale.ROOT);
                    if ("neoforge".equals(depLow)) detectedFamily = LoaderFamily.NEOFORGE;
                    if ("minecraft".equals(depLow) && currentDepRange != null) {
                        mcRange = currentDepRange;
                    } else if (currentDepRequired && !BUILTIN_DEP_IDS.contains(depLow)) {
                        requiredDeps.add(depLow);
                    }
                }
                currentDepId = null;
                currentDepRange = null;
                currentDepRequired = true;

                String header = trimmed.toLowerCase(Locale.ROOT);
                inModsBlock = header.startsWith("[[mods]]");
                inDepBlock = header.startsWith("[[dependencies");
                continue;
            }
            Matcher kv = TOML_KV_PATTERN.matcher(trimmed);
            if (kv.find()) {
                String key = kv.group(1);
                String val = kv.group(2).trim();
                if (inModsBlock) {
                    if ("modId".equalsIgnoreCase(key) && modId == null) modId = val;
                    else if ("displayName".equalsIgnoreCase(key) && displayName == null) displayName = val;
                    else if ("version".equalsIgnoreCase(key) && version == null) {
                        version = "${file.jarVersion}".equals(val) ? "bundled" : val;
                    } else if ("authors".equalsIgnoreCase(key) && authors.isEmpty()) authors = val;
                    else if ("description".equalsIgnoreCase(key) && description.isEmpty()) description = val;
                } else if (inDepBlock) {
                    if ("modId".equalsIgnoreCase(key)) currentDepId = val;
                    else if ("versionRange".equalsIgnoreCase(key)) currentDepRange = val;
                    else if ("type".equalsIgnoreCase(key)) {
                        currentDepRequired = "required".equalsIgnoreCase(val);
                    }
                }
                continue;
            }
            if (inDepBlock) {
                Matcher bm = TOML_BOOL_PATTERN.matcher(trimmed);
                if (bm.find() && "mandatory".equalsIgnoreCase(bm.group(1))) {
                    currentDepRequired = Boolean.parseBoolean(bm.group(2));
                }
            }
        }
        // Flush final dependency block
        if (inDepBlock && currentDepId != null) {
            String depLow = currentDepId.toLowerCase(Locale.ROOT);
            if ("neoforge".equals(depLow)) detectedFamily = LoaderFamily.NEOFORGE;
            if ("minecraft".equals(depLow) && currentDepRange != null) {
                mcRange = currentDepRange;
            } else if (currentDepRequired && !BUILTIN_DEP_IDS.contains(depLow)) {
                requiredDeps.add(depLow);
            }
        }

        if (modId == null || modId.isEmpty()) return null;
        return new ModInfo(
                file,
                enabled,
                modId.toLowerCase(Locale.ROOT),
                displayName != null && !displayName.isEmpty() ? displayName : fallbackName,
                version != null && !version.isEmpty() ? version : "unknown",
                description,
                authors,
                detectedFamily,
                mcRange,
                new ArrayList<>(requiredDeps),
                Collections.emptyList(),
                false
        );
    }

    @Nullable
    static ModInfo parseMcModInfo(@NonNull File file, boolean enabled,
                                  @NonNull String fallbackName, @NonNull String jsonText) {
        try {
            JsonElement parsed = new JsonParser().parse(jsonText);
            JsonArray arr = null;
            if (parsed.isJsonArray()) {
                arr = parsed.getAsJsonArray();
            } else if (parsed.isJsonObject() && parsed.getAsJsonObject().has("modList")) {
                arr = parsed.getAsJsonObject().getAsJsonArray("modList");
            }
            if (arr == null || arr.size() == 0 || !arr.get(0).isJsonObject()) return null;
            JsonObject obj = arr.get(0).getAsJsonObject();
            String id = optStr(obj, "modid", fallbackName.toLowerCase(Locale.ROOT));
            String name = optStr(obj, "name", fallbackName);
            String ver = optStr(obj, "version", "unknown");
            String desc = optStr(obj, "description", "");
            String mcVer = optStr(obj, "mcversion", null);
            String authors = extractJsonAuthors(obj.has("authorList") ? obj.get("authorList") : obj.get("authors"));
            return new ModInfo(file, enabled, id.toLowerCase(Locale.ROOT), name, ver, desc, authors,
                    LoaderFamily.FORGE, mcVer, Collections.emptyList(), Collections.emptyList(), false);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Evaluates cross-mod warnings across enabled mods: duplicates, missing dependencies, and
     * loader/MC-version compatibility mismatches against the instance's configured profile.
     */
    public static void analyzeCompatibility(@NonNull List<ModInfo> mods,
                                            @Nullable String instanceVersionId) {
        LoaderFamily instanceLoader = inferLoaderFromVersionId(instanceVersionId);
        Map<String, Integer> enabledIdCounts = new HashMap<>();
        Set<String> availableIds = new HashSet<>(BUILTIN_DEP_IDS);

        for (ModInfo mod : mods) {
            if (!mod.enabled || mod.corruptJar) continue;
            Integer prev = enabledIdCounts.get(mod.modId);
            enabledIdCounts.put(mod.modId, prev == null ? 1 : prev + 1);
            availableIds.add(mod.modId);
            availableIds.addAll(mod.providedIds);
        }

        for (ModInfo mod : mods) {
            if (!mod.enabled || mod.corruptJar) continue;

            Integer count = enabledIdCounts.get(mod.modId);
            if (count != null && count > 1) {
                mod.warnings.add("Duplicate mod ID \"" + mod.modId + "\" (" + count + " enabled copies)");
            }

            if (instanceLoader != LoaderFamily.UNKNOWN && mod.loader != LoaderFamily.UNKNOWN) {
                boolean compatible = (instanceLoader == mod.loader)
                        || (instanceLoader == LoaderFamily.QUILT && mod.loader == LoaderFamily.FABRIC);
                if (!compatible) {
                    mod.warnings.add("Loader mismatch: mod targets " + mod.loader.name()
                            + ", instance uses " + instanceLoader.name());
                }
            }

            for (String dep : mod.requiredDependencies) {
                // Skip fine-grained bundled fabric-*-v0/v1 sub-modules if fabric-api is installed
                if (dep.startsWith("fabric-") && availableIds.contains("fabric-api")) continue;
                if (!availableIds.contains(dep)) {
                    mod.warnings.add("Missing dependency: " + dep);
                }
            }
        }
    }

    @NonNull
    public static LoaderFamily inferLoaderFromVersionId(@Nullable String versionId) {
        if (versionId == null) return LoaderFamily.UNKNOWN;
        String low = versionId.toLowerCase(Locale.ROOT);
        if (low.contains("quilt")) return LoaderFamily.QUILT;
        if (low.contains("fabric")) return LoaderFamily.FABRIC;
        if (low.contains("neoforge")) return LoaderFamily.NEOFORGE;
        if (low.contains("forge")) return LoaderFamily.FORGE;
        return LoaderFamily.UNKNOWN;
    }

    @NonNull
    public static PackMeta parsePackMcmeta(@Nullable String jsonText) {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            return new PackMeta(-1, "");
        }
        try {
            JsonObject root = new JsonParser().parse(jsonText).getAsJsonObject();
            JsonObject pack = root.getAsJsonObject("pack");
            if (pack == null) return new PackMeta(-1, "");
            int format = pack.has("pack_format") && pack.get("pack_format").isJsonPrimitive()
                    ? pack.get("pack_format").getAsInt() : -1;
            String desc = "";
            if (pack.has("description")) {
                JsonElement d = pack.get("description");
                desc = d.isJsonPrimitive() ? d.getAsString() : d.toString();
                // Strip legacy Minecraft § color formatting codes for clean UI text
                desc = desc.replaceAll("§[0-9A-FK-ORa-fk-or]", "").trim();
            }
            return new PackMeta(format, desc);
        } catch (Exception e) {
            return new PackMeta(-1, "");
        }
    }

    @Nullable
    private static String optStr(@NonNull JsonObject obj, @NonNull String key, @Nullable String def) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return def;
        return obj.get(key).getAsString();
    }

    @Nullable
    private static String flattenVersionConstraint(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(item.getAsString());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    @NonNull
    private static String extractJsonAuthors(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement item : el.getAsJsonArray()) {
            String name = null;
            if (item.isJsonPrimitive()) {
                name = item.getAsString();
            } else if (item.isJsonObject() && item.getAsJsonObject().has("name")) {
                name = item.getAsJsonObject().get("name").getAsString();
            }
            if (name != null && !name.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(name.trim());
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Pre-Launch Static Mod Conflict & Dependency Simulator (<1s zero-exec scan)
    // =========================================================================

    private static final Pattern MC_RELEASE_PATTERN = Pattern.compile(
            "(?:^|[^0-9])((?:1\\.[0-9]+(?:\\.[0-9]+)?)|(?:[2-9][0-9]\\.[0-9]+(?:\\.[0-9]+)?))(?:[^0-9]|$)");

    private static final Map<String, String> MODRINTH_SLUG_MAP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("fabric", "fabric-api");
        m.put("fabric-api", "fabric-api");
        m.put("fabric_api", "fabric-api");
        m.put("quilted_fabric_api", "qsl");
        m.put("cloth-config2", "cloth-config");
        m.put("cloth_config", "cloth-config");
        m.put("architectury", "architectury-api");
        m.put("yet_another_config_lib_v3", "yacl");
        m.put("yet-another-config-lib", "yacl");
        m.put("ferritecore", "ferrite-core");
        m.put("forgeconfigapiport", "forge-config-api-port");
        m.put("puzzleslib", "puzzles-lib");
        m.put("moonlight", "selene");
        m.put("geckolib3", "geckolib");
        m.put("kotlinforforge", "kotlin-for-forge");
        m.put("cardinal-components-base", "cardinal-components-api");
        m.put("owo", "owo-lib");
        MODRINTH_SLUG_MAP = Collections.unmodifiableMap(m);
    }

    /** Actionable report returned by {@link #simulatePreLaunch(File, String)}. */
    public static final class SimulationReport {
        public final int scannedModCount;
        public final long scanDurationMs;
        @NonNull public final LoaderFamily expectedLoader;
        @Nullable public final String expectedMcVersion;
        @NonNull public final List<String> missingDependencies;
        @NonNull public final List<File> duplicateFilesToDisable;
        @NonNull public final List<File> loaderMismatchFiles;
        @NonNull public final List<File> mcVersionMismatchFiles;
        @NonNull public final List<File> corruptFiles;
        @NonNull public final List<String> humanFindings;

        public SimulationReport(int scannedModCount,
                                long scanDurationMs,
                                @NonNull LoaderFamily expectedLoader,
                                @Nullable String expectedMcVersion,
                                @NonNull List<String> missingDependencies,
                                @NonNull List<File> duplicateFilesToDisable,
                                @NonNull List<File> loaderMismatchFiles,
                                @NonNull List<File> mcVersionMismatchFiles,
                                @NonNull List<File> corruptFiles,
                                @NonNull List<String> humanFindings) {
            this.scannedModCount = scannedModCount;
            this.scanDurationMs = scanDurationMs;
            this.expectedLoader = expectedLoader;
            this.expectedMcVersion = expectedMcVersion;
            this.missingDependencies = Collections.unmodifiableList(missingDependencies);
            this.duplicateFilesToDisable = Collections.unmodifiableList(duplicateFilesToDisable);
            this.loaderMismatchFiles = Collections.unmodifiableList(loaderMismatchFiles);
            this.mcVersionMismatchFiles = Collections.unmodifiableList(mcVersionMismatchFiles);
            this.corruptFiles = Collections.unmodifiableList(corruptFiles);
            this.humanFindings = Collections.unmodifiableList(humanFindings);
        }

        public boolean hasBlockingIssues() {
            return !missingDependencies.isEmpty()
                    || !duplicateFilesToDisable.isEmpty()
                    || !loaderMismatchFiles.isEmpty()
                    || !mcVersionMismatchFiles.isEmpty()
                    || !corruptFiles.isEmpty();
        }

        public boolean canAutoFix() {
            return hasBlockingIssues();
        }

        @NonNull
        public String buildHumanSummary() {
            if (humanFindings.isEmpty()) {
                return "All " + scannedModCount + " active mod(s) passed static verification ("
                        + Math.max(1L, scanDurationMs) + " ms).";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Scanned ").append(scannedModCount).append(" mod(s) in ")
                    .append(Math.max(1L, scanDurationMs)).append(" ms:\n\n");
            int cap = Math.min(8, humanFindings.size());
            for (int i = 0; i < cap; i++) {
                sb.append("• ").append(humanFindings.get(i)).append('\n');
            }
            if (humanFindings.size() > cap) {
                sb.append("• …and ").append(humanFindings.size() - cap)
                        .append(" more issue(s).\n");
            }
            return sb.toString().trim();
        }
    }

    /** Maps an internal mod dependency ID (e.g. {@code fabric}) to its Modrinth project slug. */
    @NonNull
    public static String resolveModrinthDependencySlug(@NonNull String modId) {
        String key = modId.trim().toLowerCase(Locale.ROOT);
        String mapped = MODRINTH_SLUG_MAP.get(key);
        return mapped != null ? mapped : key.replace('_', '-');
    }

    /** Extracts a canonical Minecraft release number (e.g. {@code 1.21.4}, {@code 26.1}) from a versionId. */
    @Nullable
    public static String extractMinecraftVersion(@Nullable String versionId) {
        if (versionId == null) return null;
        String trimmed = versionId.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("latest-")
                || trimmed.startsWith("zyrox-latest-")) {
            return null;
        }
        int lastDash = trimmed.lastIndexOf('-');
        if (lastDash >= 0 && lastDash + 1 < trimmed.length()) {
            String tail = trimmed.substring(lastDash + 1);
            if (tail.matches("(?:1\\.[0-9]+(?:\\.[0-9]+)?)|(?:[2-9][0-9]\\.[0-9]+(?:\\.[0-9]+)?)")) {
                return tail;
            }
        }
        Matcher m = MC_RELEASE_PATTERN.matcher(trimmed);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Evaluates whether {@code targetMc} satisfies a Fabric/Quilt SemVer or Forge/NeoForge Maven
     * version constraint (e.g. {@code >=1.21 <1.22}, {@code ~1.21.4}, {@code 1.21.x},
     * {@code [1.20.1,1.21)}). Unrecognized snapshot/wildcard constraints fail open ({@code true}).
     */
    public static boolean isMinecraftVersionCompatible(@NonNull String targetMc,
                                                       @Nullable String rawConstraint) {
        if (rawConstraint == null) return true;
        String expr = rawConstraint.trim();
        if (expr.isEmpty() || "*".equals(expr) || "x".equalsIgnoreCase(expr)
                || "any".equalsIgnoreCase(expr)) {
            return true;
        }
        int[] target = parseMcTuple(targetMc);
        if (target == null) return true;

        if (expr.contains("|")) {
            boolean checkedAny = false;
            for (String branch : expr.split("\\|\\|?")) {
                String sub = branch.trim();
                if (sub.isEmpty()) continue;
                checkedAny = true;
                if (isMinecraftVersionCompatible(targetMc, sub)) return true;
            }
            return !checkedAny;
        }

        if ((expr.startsWith("[") || expr.startsWith("("))
                && (expr.endsWith("]") || expr.endsWith(")"))) {
            boolean minInc = expr.startsWith("[");
            boolean maxInc = expr.endsWith("]");
            String inner = expr.substring(1, expr.length() - 1).trim();
            int comma = inner.indexOf(',');
            if (comma < 0) {
                int[] exact = parseMcTuple(inner);
                return exact == null || compareTuple(target, exact) == 0;
            }
            int[] lo = parseMcTuple(inner.substring(0, comma).trim());
            int[] hi = parseMcTuple(inner.substring(comma + 1).trim());
            if (lo != null) {
                int c = compareTuple(target, lo);
                if (minInc ? c < 0 : c <= 0) return false;
            }
            if (hi != null) {
                int c = compareTuple(target, hi);
                if (maxInc ? c > 0 : c >= 0) return false;
            }
            return true;
        }

        for (String atom : expr.split("\\s+")) {
            if (!evaluateSemVerAtom(target, atom.trim())) return false;
        }
        return true;
    }

    /**
     * Performs a fast static pre-launch mod scan over all enabled {@code .jar} mods inside
     * {@code <gameDir>/mods}, detecting missing required dependencies, duplicate mod IDs,
     * incompatible loaders, incompatible Minecraft versions, and corrupt archives.
     */
    @NonNull
    public static SimulationReport simulatePreLaunch(@NonNull File gameDir,
                                                     @Nullable String instanceVersionId) {
        long startNs = System.nanoTime();
        LoaderFamily instLoader = inferLoaderFromVersionId(instanceVersionId);
        String instMc = extractMinecraftVersion(instanceVersionId);
        File modsDir = new File(gameDir, "mods");
        File[] jars = modsDir.isDirectory()
                ? modsDir.listFiles(f -> f.isFile()
                    && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))
                : null;
        if (jars == null || jars.length == 0) {
            return new SimulationReport(0, 0L, instLoader, instMc,
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList());
        }
        java.util.Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        List<File> corrupt = new ArrayList<>();
        List<File> duplicates = new ArrayList<>();
        List<File> loaderBad = new ArrayList<>();
        List<File> mcBad = new ArrayList<>();
        List<String> findings = new ArrayList<>();

        Map<String, ModInfo> primaryById = new java.util.LinkedHashMap<>();
        Set<String> presentIds = new HashSet<>();

        for (File jar : jars) {
            ModInfo info = inspectModFile(jar);
            if (info.corruptJar) {
                corrupt.add(jar);
                findings.add("Corrupt or unreadable archive: " + jar.getName());
                continue;
            }
            if (instLoader != LoaderFamily.UNKNOWN && info.loader != LoaderFamily.UNKNOWN
                    && !areLoadersCompatible(instLoader, info.loader)) {
                loaderBad.add(jar);
                findings.add(info.name + " requires " + info.loader.name()
                        + ", incompatible with " + instLoader.name() + " instance.");
                continue;
            }
            if (instMc != null && info.minecraftConstraint != null
                    && !isMinecraftVersionCompatible(instMc, info.minecraftConstraint)) {
                mcBad.add(jar);
                findings.add(info.name + " (" + info.version + ") requires Minecraft "
                        + info.minecraftConstraint + ", but instance is " + instMc + ".");
                continue;
            }

            String idKey = info.modId.toLowerCase(Locale.ROOT);
            presentIds.add(idKey);
            for (String p : info.providedIds) {
                presentIds.add(p.toLowerCase(Locale.ROOT));
            }
            if ("fabric-api".equals(idKey) || "fabric".equals(idKey)) {
                presentIds.add("fabric");
                presentIds.add("fabric-api");
            } else if ("cloth-config".equals(idKey) || "cloth-config2".equals(idKey)) {
                presentIds.add("cloth-config");
                presentIds.add("cloth-config2");
            }

            ModInfo existing = primaryById.get(idKey);
            if (existing != null) {
                if (compareVersionNumbers(info.version, existing.version) > 0) {
                    duplicates.add(existing.file);
                    primaryById.put(idKey, info);
                    findings.add("Duplicate mod \"" + info.name + "\": keeping v"
                            + info.version + " and disabling " + existing.file.getName() + ".");
                } else {
                    duplicates.add(jar);
                    findings.add("Duplicate mod \"" + info.name + "\": disabling redundant "
                            + jar.getName() + ".");
                }
            } else {
                primaryById.put(idKey, info);
            }
        }

        Set<String> missingDeps = new LinkedHashSet<>();
        for (ModInfo info : primaryById.values()) {
            for (String dep : info.requiredDependencies) {
                String depLow = dep.toLowerCase(Locale.ROOT);
                if (BUILTIN_DEP_IDS.contains(depLow) || presentIds.contains(depLow)) continue;
                if (depLow.startsWith("fabric-") && !"fabric-language-kotlin".equals(depLow)
                        && presentIds.contains("fabric-api")) {
                    continue;
                }
                if (depLow.startsWith("quilt_") && presentIds.contains("qsl")) continue;
                if (missingDeps.add(depLow)) {
                    findings.add(info.name + " is missing required dependency \"" + dep + "\".");
                }
            }
        }

        long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        return new SimulationReport(
                jars.length, elapsedMs, instLoader, instMc,
                new ArrayList<>(missingDeps), duplicates, loaderBad, mcBad, corrupt, findings);
    }

    private static boolean areLoadersCompatible(@NonNull LoaderFamily instanceLoader,
                                                @NonNull LoaderFamily modLoader) {
        if (instanceLoader == modLoader) return true;
        return instanceLoader == LoaderFamily.QUILT && modLoader == LoaderFamily.FABRIC;
    }

    private static boolean evaluateSemVerAtom(@NonNull int[] target, @NonNull String atom) {
        if (atom.isEmpty() || "*".equals(atom) || "x".equalsIgnoreCase(atom)) return true;
        if (atom.startsWith(">=")) {
            int[] v = parseMcTuple(atom.substring(2));
            return v == null || compareTuple(target, v) >= 0;
        }
        if (atom.startsWith("<=")) {
            int[] v = parseMcTuple(atom.substring(2));
            return v == null || compareTuple(target, v) <= 0;
        }
        if (atom.startsWith(">")) {
            int[] v = parseMcTuple(atom.substring(1));
            return v == null || compareTuple(target, v) > 0;
        }
        if (atom.startsWith("<")) {
            int[] v = parseMcTuple(atom.substring(1));
            return v == null || compareTuple(target, v) < 0;
        }
        if (atom.startsWith("~")) {
            int[] v = parseMcTuple(atom.substring(1));
            if (v == null) return true;
            return compareTuple(target, v) >= 0
                    && compareTuple(target, new int[]{v[0], v[1] + 1, 0}) < 0;
        }
        if (atom.startsWith("^")) {
            int[] v = parseMcTuple(atom.substring(1));
            if (v == null) return true;
            return compareTuple(target, v) >= 0
                    && compareTuple(target, new int[]{v[0] + 1, 0, 0}) < 0;
        }
        String clean = atom.startsWith("=") ? atom.substring(1).trim() : atom;
        if (clean.endsWith(".x") || clean.endsWith(".X") || clean.endsWith(".*")) {
            String[] p = clean.substring(0, clean.length() - 2).split("\\.");
            try {
                if (p.length == 1) return target[0] == Integer.parseInt(p[0]);
                if (p.length >= 2) {
                    return target[0] == Integer.parseInt(p[0])
                            && target[1] == Integer.parseInt(p[1]);
                }
            } catch (NumberFormatException e) {
                return true;
            }
        }
        int[] exact = parseMcTuple(clean);
        return exact == null || compareTuple(target, exact) == 0;
    }

    @Nullable
    private static int[] parseMcTuple(@Nullable String raw) {
        if (raw == null) return null;
        String clean = raw.trim();
        if (clean.isEmpty()) return null;
        int dash = clean.indexOf('-');
        if (dash >= 0) clean = clean.substring(0, dash);
        if (!clean.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) return null;
        String[] parts = clean.split("\\.");
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    parts.length >= 3 ? Integer.parseInt(parts[2]) : 0
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compareTuple(@NonNull int[] a, @NonNull int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private static int compareVersionNumbers(@Nullable String a, @Nullable String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        int n = Math.min(4, Math.max(pa.length, pb.length));
        for (int i = 0; i < n; i++) {
            int va = (i < pa.length && !pa[i].isEmpty() && pa[i].length() <= 8)
                    ? Integer.parseInt(pa[i]) : 0;
            int vb = (i < pb.length && !pb[i].isEmpty() && pb[i].length() <= 8)
                    ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }
}
