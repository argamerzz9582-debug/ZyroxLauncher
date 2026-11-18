package git.artdeell.mojo.utils;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.maven.MavenName;
import git.artdeell.mojo.value.DependentLibrary;
import git.artdeell.mojo.value.LibraryArtifact;

/**
 * Single-responsibility Minecraft version JSON inheritance merger and native-compatible library
 * override resolver extracted from {@link Tools}.
 */
public final class VersionManifestResolver {
    private static final String[] INHERITED_FIELDS = new String[]{
            "assetIndex", "assets", "id",
            "mainClass", "minecraftArguments",
            "releaseTime", "time", "type", "environment",
            "disableRendererChecks"
    };

    private VersionManifestResolver() {
    }

    public static void createLibraryInfo(@NonNull DependentLibrary library) {
        if (library.downloads == null || library.downloads.artifact == null) {
            library.downloads = new DependentLibrary.LibraryDownloads(new LibraryArtifact());
        }
    }

    /** Rewrites incompatible legacy JNA / OSHI coordinates to bundled Android-native releases. */
    public static void preProcessLibrary(@NonNull DependentLibrary libItem) {
        MavenName libName = libItem.name;
        if (libName == null || libName.version == null) return;
        String[] version = libName.version.split("\\.");
        if (version.length < 2) return;

        if ("jna".equals(libName.module) && "net.java.dev.jna".equals(libName.provider)) {
            if (parseIntSafe(version[0]) >= 5 && parseIntSafe(version[1]) >= 13) return;
            Log.d(Tools.APP_NAME, "Library " + libItem.name + " upgraded to 5.13.0");
            createLibraryInfo(libItem);
            libItem.name = new MavenName(libName.provider, libName.module, "5.13.0");
            libItem.downloads.artifact.path = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
            libItem.downloads.artifact.sha1 = "1200e7ebeedbe0d10062093f32925a912020e747";
            libItem.downloads.artifact.url = Tools.MAVEN_CENTRAL + "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
            libItem.downloads.artifact.size = 1879325;
            libItem.replaced = true;
        } else if ("oshi-core".equals(libName.module) && "com.github.oshi".equals(libName.provider)) {
            if (parseIntSafe(version[0]) != 6 || parseIntSafe(version[1]) != 2) return;
            Log.d(Tools.APP_NAME, "Library " + libItem.name + " upgraded to 6.3.0");
            createLibraryInfo(libItem);
            libItem.name = new MavenName(libName.provider, libName.module, "6.3.0");
            libItem.downloads.artifact.path = "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
            libItem.downloads.artifact.sha1 = "9e98cf55be371cafdb9c70c35d04ec2a8c2b42ac";
            libItem.downloads.artifact.url = Tools.MAVEN_CENTRAL + "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
            libItem.downloads.artifact.size = 957945;
            libItem.replaced = true;
        }
    }

    @NonNull
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JVersionList.Version resolveVersionInfo(@NonNull String versionName,
                                                          boolean skipInheriting) {
        try {
            String jsonPath = Tools.DIR_HOME_VERSION + "/" + versionName + "/" + versionName + ".json";
            JVersionList.Version customVer = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(jsonPath), JVersionList.Version.class);

            if (!skipInheriting
                    && customVer.inheritsFrom != null
                    && !customVer.inheritsFrom.equals(customVer.id)) {
                final JVersionList.Version inheritsVer;
                try {
                    String basePath = Tools.DIR_HOME_VERSION + "/"
                            + customVer.inheritsFrom + "/" + customVer.inheritsFrom + ".json";
                    inheritsVer = Tools.GLOBAL_GSON.fromJson(
                            Tools.read(basePath), JVersionList.Version.class);
                } catch (IOException e) {
                    throw new RuntimeException("Can't find the source version for "
                            + versionName + " (req version=" + customVer.inheritsFrom + ")");
                }

                mergeInheritedFields(inheritsVer, customVer, INHERITED_FIELDS);

                if (inheritsVer.arguments != null && customVer.arguments != null
                        && inheritsVer.arguments.game != null && customVer.arguments.game != null) {
                    List totalArgList = new ArrayList(Arrays.asList(inheritsVer.arguments.game));
                    int nskip = 0;
                    for (int i = 0; i < customVer.arguments.game.length; i++) {
                        if (nskip > 0) {
                            nskip--;
                            continue;
                        }
                        Object perCustomArg = customVer.arguments.game[i];
                        if (perCustomArg instanceof String) {
                            String argStr = (String) perCustomArg;
                            if (argStr.startsWith("--") && totalArgList.contains(argStr)) {
                                if (i + 1 < customVer.arguments.game.length
                                        && customVer.arguments.game[i + 1] instanceof String
                                        && !((String) customVer.arguments.game[i + 1]).startsWith("--")) {
                                    nskip++;
                                }
                            } else {
                                totalArgList.add(argStr);
                            }
                        } else if (!totalArgList.contains(perCustomArg)) {
                            totalArgList.add(perCustomArg);
                        }
                    }
                    inheritsVer.arguments.game = totalArgList.toArray(new Object[0]);
                }
                customVer = inheritsVer;
            }

            if (customVer.javaVersion != null && customVer.javaVersion.majorVersion == 0) {
                customVer.javaVersion.majorVersion = customVer.javaVersion.version;
            }
            return customVer;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void mergeInheritedFields(@NonNull JVersionList.Version targetVer,
                                             @NonNull JVersionList.Version fromVer,
                                             @NonNull String[] keys) {
        for (String key : keys) {
            Object value = null;
            try {
                Field fieldA = fromVer.getClass().getDeclaredField(key);
                value = fieldA.get(fromVer);
                if (value != null && (!(value instanceof String) || !((String) value).isEmpty())) {
                    Field fieldB = targetVer.getClass().getDeclaredField(key);
                    fieldB.set(targetVer, value);
                }
            } catch (Throwable th) {
                Log.w(Tools.APP_NAME, "Unable to insert " + key + "=" + value, th);
            }
        }
    }

    private static int parseIntSafe(@NonNull String raw) {
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
