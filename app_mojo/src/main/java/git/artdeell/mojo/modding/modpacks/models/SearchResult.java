package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.Nullable;

public class SearchResult {
    public int totalResultCount;
    public ModItem[] results;
    /** Optional non-intrusive notice (e.g., when CurseForge falls back to Modrinth). */
    @Nullable
    public String fallbackNotice;
}
