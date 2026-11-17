package git.artdeell.mojo.modding.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters passed to Modrinth & CurseForge APIs.
 */
public class SearchFilters {
    public static final int PLATFORM_ALL = 0;
    public static final int PLATFORM_MODRINTH = 1;
    public static final int PLATFORM_CURSEFORGE = 2;

    public boolean isModpack;
    public String name;
    @Nullable public String mcVersion;
    @Nullable public String category;
    @Nullable public String projectTypeOverride;
    public int platform = PLATFORM_ALL;
}
