package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.NonNull;

import java.util.Locale;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;
    public String author;
    public long downloads;

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description, String imageUrl) {
        this(apiSource, isModpack, id, title, description, imageUrl, null, -1L);
    }

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description,
                   String imageUrl, String author, long downloads) {
        this.apiSource = apiSource;
        this.isModpack = isModpack;
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
        this.author = author;
        this.downloads = downloads;
    }

    public String getFormattedDownloads() {
        if (downloads < 0) {
            return apiSource == Constants.SOURCE_CURSEFORGE ? "CURSEFORGE" : "MODRINTH";
        }
        if (downloads >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM DL", downloads / 1_000_000.0);
        }
        if (downloads >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fK DL", downloads / 1_000.0);
        }
        return downloads + " DL";
    }

    @NonNull
    @Override
    public String toString() {
        return "ModItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", isModpack=" + isModpack +
                '}';
    }

    public String getIconCacheTag() {
        return apiSource + "_" + id;
    }
}
