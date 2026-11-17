package git.artdeell.mojo.value;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import git.artdeell.mojo.modding.ComparableVersionString;
import git.artdeell.mojo.utils.maven.MavenName;

public class SubstitutionMap {
    private static final LibrarySubstitution SKIP_SUBSTITUTION;
    static {
        SKIP_SUBSTITUTION = new LibrarySubstitution();
        SKIP_SUBSTITUTION.skip = true;
    }

    public LibraryMap libraries;
    public ExtraNameMap artifactMapping;

    public LibrarySubstitution findSubstitution(MavenName mavenName) {
        if (mavenName == null || libraries == null) return null;
        switch (mavenName.provider) {
            case "org.lwjgl": {
                LibrarySubstitution exact = libraries.get(mavenName);
                if (exact != null) return exact;
                return resolveFutureLwjglFallback(mavenName);
            }
            case "org.lwjgl.lwjgl":
            case "net.java.jinput":
                return libraries.get(mavenName);
            default:
                return null;
        }
    }

    /**
     * Future-compatibility safeguard for newly released Minecraft versions bumping {@code org.lwjgl}
     * minor/patch revisions before a dedicated substitution entry is added:
     * <ul>
     *   <li>Skips desktop Linux/macOS/Windows classifier jars ({@code :natives-*}, {@code *-natives-*})
     *       so desktop glibc shared libraries never overwrite Android Bionic LWJGL natives.</li>
     *   <li>Substitutes the highest compatible Android-built module from the same LWJGL major line
     *       (preferring matching {@code major.minor}, e.g. {@code 3.4.3} for {@code 3.4.4}).</li>
     * </ul>
     */
    @Nullable
    private LibrarySubstitution resolveFutureLwjglFallback(MavenName requested) {
        if (isDesktopNativeArtifact(requested)) {
            return SKIP_SUBSTITUTION;
        }
        // Strip qualifiers such as ":unsafe" or "-unsafe" before probing fallback candidates.
        if (requested.extra != null && !requested.extra.isEmpty()) {
            LibrarySubstitution baseExact = libraries.get(
                    new MavenName(requested.provider, requested.module, requested.version, null));
            if (baseExact != null) return baseExact;
        }
        if (requested.version == null) return null;

        String cleanReqVer = stripVersionQualifier(requested.version);
        ComparableVersionString reqParsed = ComparableVersionString.parse(cleanReqVer);
        int reqMajor = extractMajor(cleanReqVer);
        int reqMinor = extractMinor(cleanReqVer);

        LibrarySubstitution bestSameMinor = null;
        ComparableVersionString bestSameMinorVer = null;
        LibrarySubstitution bestSameMajor = null;
        ComparableVersionString bestSameMajorVer = null;

        for (Map.Entry<MavenName, LibrarySubstitution> entry : libraries.entrySet()) {
            MavenName candidateName = entry.getKey();
            LibrarySubstitution sub = entry.getValue();
            if (candidateName == null || sub == null || sub.skip) continue;
            if (!"org.lwjgl".equals(candidateName.provider)) continue;
            if (!requested.module.equals(candidateName.module)) continue;
            if (candidateName.extra != null || candidateName.version == null) continue;

            String cleanCandVer = stripVersionQualifier(candidateName.version);
            if (reqMajor > 0 && extractMajor(cleanCandVer) != reqMajor) continue;
            ComparableVersionString candParsed = ComparableVersionString.parse(cleanCandVer);
            if (!candParsed.isValid()) continue;

            if (reqParsed.isValid() && reqMinor >= 0 && extractMinor(cleanCandVer) == reqMinor) {
                if (bestSameMinorVer == null || candParsed.compareTo(bestSameMinorVer) > 0) {
                    bestSameMinorVer = candParsed;
                    bestSameMinor = sub;
                }
            }
            if (bestSameMajorVer == null || candParsed.compareTo(bestSameMajorVer) > 0) {
                bestSameMajorVer = candParsed;
                bestSameMajor = sub;
            }
        }
        return bestSameMinor != null ? bestSameMinor : bestSameMajor;
    }

    private static boolean isDesktopNativeArtifact(MavenName name) {
        if (name.extra != null && name.extra.startsWith("natives-")) {
            return true;
        }
        return name.module != null && name.module.contains("-natives-");
    }

    private static String stripVersionQualifier(String version) {
        int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }

    private static int extractMajor(String version) {
        int dot = version.indexOf('.');
        if (dot <= 0) return -1;
        try {
            return Integer.parseInt(version.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int extractMinor(String version) {
        int first = version.indexOf('.');
        if (first <= 0) return -1;
        int second = version.indexOf('.', first + 1);
        String part = second > first ? version.substring(first + 1, second) : version.substring(first + 1);
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public SubstitutionMap prepare() {
        if (artifactMapping != null && libraries != null) {
            for (Map.Entry<MavenName, MavenName> mappingPair : artifactMapping.entrySet()) {
                LibrarySubstitution target = libraries.get(mappingPair.getValue());
                if (target != null) {
                    libraries.put(mappingPair.getKey(), target);
                }
            }
            artifactMapping.clear();
        }
        return this;
    }

    public static class ExtraNameMap extends HashMap<MavenName, MavenName> {}
    public static class LibraryMap extends HashMap<MavenName, LibrarySubstitution> {}
}
