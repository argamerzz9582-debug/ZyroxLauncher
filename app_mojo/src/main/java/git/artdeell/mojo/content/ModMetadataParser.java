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
}
