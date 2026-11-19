package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.Nullable;

/**
 * Search filters passed to Modrinth &amp; CurseForge APIs, including active-instance Minecraft
 * version and modloader strict compatibility locking.
 */
public class SearchFilters {
    public static final int PLATFORM_ALL = 0;
    public static final int PLATFORM_MODRINTH = 1;
    public static final int PLATFORM_CURSEFORGE = 2;

    public boolean isModpack;
    public String name = "";
    @Nullable public String mcVersion;
    /** Active instance modloader slug ({@code fabric}, {@code quilt}, {@code neoforge}, {@code forge}). */
    @Nullable public String loader;
    /** Optional content category tag (e.g. {@code optimization}, {@code adventure}). */
    @Nullable public String category;
    /**
     * Optional Modrinth/CurseForge project type override: {@code "modpack"} (default when null),
     * {@code "mod"}, {@code "shader"}, or {@code "resourcepack"}.
     */
    @Nullable public String projectTypeOverride;
    /**
     * When {@code true} (default), strictly binds searches and version pickers to the active
     * instance's clean {@link #mcVersion} and {@link #loader}.
     */
    public boolean strictCompatibility = true;
    public int platform = PLATFORM_ALL;
}
