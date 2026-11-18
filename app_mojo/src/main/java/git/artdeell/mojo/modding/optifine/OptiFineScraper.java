package git.artdeell.mojo.modding.optifine;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Resilient, null-safe OptiFine downloads table parser.
 *
 * <p>Tolerates DOM attribute omissions, alternate mirror/download column orderings, HTML comments,
 * and unexpected page wrappers without ever throwing {@link NullPointerException} or leaving half-
 * populated version rows. Any unparseable document converts cleanly into
 * {@link DownloadUtils.ParseException} so the UI can surface its 1-tap
 * "Install Sodium + Iris (Recommended)" fallback card.</p>
 */
public class OptiFineScraper implements DownloadUtils.ParseCallback<OptiFineUtils.OptiFineVersions> {
    private static final String TAG = "OptiFineScraper";
    private static final int MAX_DOM_DEPTH = 48;

    private final OptiFineUtils.OptiFineVersions mOptiFineVersions;
    private List<OptiFineUtils.OptiFineVersion> mListInProgress;
    private String mGameVersion;

    public OptiFineScraper() {
        mOptiFineVersions = new OptiFineUtils.OptiFineVersions();
        mOptiFineVersions.gameVersions = new ArrayList<>();
        mOptiFineVersions.optifineVersions = new ArrayList<>();
    }

    @Override
    public OptiFineUtils.OptiFineVersions process(String input) throws DownloadUtils.ParseException {
        if (input == null || input.trim().isEmpty()) {
            throw new DownloadUtils.ParseException(new IllegalArgumentException("Empty OptiFine HTML"));
        }
        try {
            HtmlCleaner htmlCleaner = new HtmlCleaner();
            TagNode tagNode = htmlCleaner.clean(input);
            if (tagNode != null) {
                traverseTagNode(tagNode, 0);
            }
            insertVersionContent(null);
        } catch (Throwable t) {
            Log.w(TAG, "OptiFine HTML layout changed or failed to parse: " + t.getMessage());
            Exception cause = (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
            throw new DownloadUtils.ParseException(cause);
        }
        if (mOptiFineVersions.optifineVersions.isEmpty()
                || mOptiFineVersions.gameVersions.isEmpty()) {
            throw new DownloadUtils.ParseException(
                    new IllegalStateException("No OptiFine releases found in page"));
        }
        return mOptiFineVersions;
    }

    public void traverseTagNode(@Nullable TagNode tagNode) {
        traverseTagNode(tagNode, 0);
    }

    private void traverseTagNode(@Nullable TagNode tagNode, int depth) {
        if (tagNode == null || depth > MAX_DOM_DEPTH) return;
        if (isDownloadLine(tagNode) && mGameVersion != null && mListInProgress != null) {
            traverseDownloadLine(tagNode);
        } else if (isGameVersionTag(tagNode)) {
            insertVersionContent(tagNode);
        } else {
            TagNode[] children = tagNode.getChildTags();
            if (children == null) return;
            for (TagNode child : children) {
                traverseTagNode(child, depth + 1);
            }
        }
    }

    private boolean isDownloadLine(@NonNull TagNode tagNode) {
        String name = tagNode.getName();
        if (!"tr".equalsIgnoreCase(name)) return false;
        String cls = tagNode.getAttributeByName("class");
        return cls != null && cls.trim().startsWith("downloadLine");
    }

    private boolean isGameVersionTag(@NonNull TagNode tagNode) {
        String name = tagNode.getName();
        if (!"h2".equalsIgnoreCase(name)) return false;
        CharSequence text = tagNode.getText();
        return text != null && text.toString().trim().startsWith("Minecraft ");
    }

    private void traverseDownloadLine(@NonNull TagNode tagNode) {
        OptiFineUtils.OptiFineVersion optiFineVersion = new OptiFineUtils.OptiFineVersion();
        optiFineVersion.gameVersion = mGameVersion;
        String fallbackDownloadHref = null;
        TagNode[] children = tagNode.getChildTags();
        if (children == null) return;

        for (TagNode subNode : children) {
            if (subNode == null || !"td".equalsIgnoreCase(subNode.getName())) continue;
            String cls = subNode.getAttributeByName("class");
            if (cls == null) continue;
            String cleanClass = cls.trim();
            if ("colFile".equals(cleanClass)) {
                CharSequence label = subNode.getText();
                if (label != null) {
                    optiFineVersion.versionName = label.toString().trim();
                }
            } else if ("colMirror".equals(cleanClass)) {
                optiFineVersion.downloadUrl = getLinkHref(subNode);
            } else if ("colDownload".equals(cleanClass)) {
                fallbackDownloadHref = getLinkHref(subNode);
            }
        }

        if ((optiFineVersion.downloadUrl == null || optiFineVersion.downloadUrl.isEmpty())
                && fallbackDownloadHref != null) {
            optiFineVersion.downloadUrl = fallbackDownloadHref;
        }
        if (optiFineVersion.versionName != null && !optiFineVersion.versionName.isEmpty()
                && optiFineVersion.downloadUrl != null && !optiFineVersion.downloadUrl.isEmpty()) {
            mListInProgress.add(optiFineVersion);
        }
    }

    @Nullable
    private String getLinkHref(@NonNull TagNode parent) {
        TagNode[] children = parent.getChildTags();
        if (children == null) return null;
        for (TagNode subNode : children) {
            if (subNode == null || !"a".equalsIgnoreCase(subNode.getName())) continue;
            String rawHref = subNode.getAttributeByName("href");
            String normalized = normalizeOptiFineHref(rawHref);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    @Nullable
    static String normalizeOptiFineHref(@Nullable String rawHref) {
        if (rawHref == null) return null;
        String href = rawHref.trim();
        if (href.isEmpty()) return null;
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://optifine.net/") || lower.startsWith("http://www.optifine.net/")) {
            href = "https://" + href.substring("http://".length());
            lower = href.toLowerCase(Locale.ROOT);
        }
        if (lower.startsWith("https://optifine.net/") || lower.startsWith("https://www.optifine.net/")) {
            return href;
        }
        if (lower.startsWith("adloadx?") || lower.startsWith("/adloadx?")
                || lower.startsWith("downloadx?") || lower.startsWith("/downloadx?")) {
            return href.startsWith("/")
                    ? "https://optifine.net" + href
                    : "https://optifine.net/" + href;
        }
        // Extract embedded f=OptiFine_... query parameter when wrapped in ad redirects
        int adloadIdx = lower.indexOf("optifine.net/adloadx?");
        if (adloadIdx >= 0) {
            return "https://" + href.substring(adloadIdx);
        }
        return null;
    }

    private void insertVersionContent(@Nullable TagNode tagNode) {
        if (mListInProgress != null && !mListInProgress.isEmpty() && mGameVersion != null) {
            mOptiFineVersions.gameVersions.add(mGameVersion);
            mOptiFineVersions.optifineVersions.add(mListInProgress);
        }
        if (tagNode != null) {
            CharSequence text = tagNode.getText();
            mGameVersion = text != null ? text.toString().trim() : null;
            mListInProgress = new ArrayList<>();
        }
    }

    /**
     * Returns {@code true} when {@code minecraftVersion} is 1.16 or newer (including 26.x year
     * versioning), where Fabric + Sodium + Iris outperforms legacy OptiFine on mobile GPUs.
     */
    public static boolean recommendsSodiumIris(@Nullable String minecraftVersion) {
        if (minecraftVersion == null) return true;
        String clean = minecraftVersion.trim();
        if (clean.startsWith("Minecraft ")) {
            clean = clean.substring("Minecraft ".length()).trim();
        }
        int dash = clean.indexOf('-');
        if (dash > 0) clean = clean.substring(0, dash);
        String[] parts = clean.split("\\.");
        try {
            int first = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            if (first >= 26) return true;
            if (first > 1) return true;
            if (first == 1 && parts.length >= 2) {
                int minor = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                return minor >= 16;
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
    }
}
