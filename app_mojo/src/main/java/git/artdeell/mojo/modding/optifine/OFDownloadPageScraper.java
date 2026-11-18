package git.artdeell.mojo.modding.optifine;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.HtmlNode;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.TagNodeVisitor;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.utils.ZyroxHttpClient;

/**
 * Resilient, timeout-bounded OptiFine mirror download page extractor.
 *
 * <p>Uses {@link ZyroxHttpClient#getHtmlOrThrow(String)} instead of blocking un-timed
 * {@code java.net.URL} streams, handles both classic {@code <span id="Download">} nodes and
 * direct {@code downloadx?f=OptiFine_...} anchor/regex fallbacks, and surfaces a descriptive
 * {@link IOException} on failure so callers can offer 1-Tap Sodium + Iris.</p>
 */
public class OFDownloadPageScraper implements TagNodeVisitor {
    private static final String TAG = "OFDownloadPageScraper";
    private static final Pattern DOWNLOADX_PATTERN = Pattern.compile(
            "(?:https?://(?:www\\.)?optifine\\.net/)?(downloadx\\?f=[^\"'\\s<>]+)",
            Pattern.CASE_INSENSITIVE);

    @NonNull
    public static String run(@Nullable String urlInput) throws IOException {
        if (urlInput == null || urlInput.trim().isEmpty()) {
            throw new IOException("OptiFine mirror URL is missing. Try Sodium + Iris instead.");
        }
        String html = ZyroxHttpClient.getHtmlOrThrow(urlInput);
        String resolved = extractDownloadUrlFromHtml(html);
        if (resolved == null || resolved.isEmpty()) {
            throw new IOException(
                    "OptiFine changed its download page or is temporarily unavailable. "
                            + "Use 1-Tap Sodium + Iris (Recommended) for higher mobile FPS.");
        }
        return resolved;
    }

    @Nullable
    public static String extractDownloadUrlFromHtml(@Nullable String html) {
        if (html == null || html.trim().isEmpty()) return null;
        OFDownloadPageScraper scraper = new OFDownloadPageScraper();
        try {
            HtmlCleaner htmlCleaner = new HtmlCleaner();
            TagNode root = htmlCleaner.clean(html);
            if (root != null) {
                root.traverse(scraper);
            }
        } catch (Throwable t) {
            Log.w(TAG, "OptiFine download page DOM traversal fallback: " + t.getMessage());
        }
        if (scraper.mDownloadFullUrl != null) {
            return scraper.mDownloadFullUrl;
        }
        if (scraper.mFallbackDownloadxUrl != null) {
            return scraper.mFallbackDownloadxUrl;
        }
        Matcher matcher = DOWNLOADX_PATTERN.matcher(html);
        if (matcher.find()) {
            String rel = matcher.group(1).replace("&amp;", "&");
            return OptiFineScraper.normalizeOptiFineHref(rel);
        }
        return null;
    }

    private String mDownloadFullUrl;
    private String mFallbackDownloadxUrl;

    @Override
    public boolean visit(TagNode parentNode, HtmlNode htmlNode) {
        if (!(htmlNode instanceof TagNode)) return true;
        TagNode tagNode = (TagNode) htmlNode;
        if (isDownloadUrl(parentNode, tagNode)) {
            String normalized = OptiFineScraper.normalizeOptiFineHref(
                    tagNode.getAttributeByName("href"));
            if (normalized != null) {
                mDownloadFullUrl = normalized;
                return false;
            }
        }
        if (mFallbackDownloadxUrl == null && "a".equalsIgnoreCase(tagNode.getName())) {
            String href = tagNode.getAttributeByName("href");
            if (href != null && href.toLowerCase(Locale.ROOT).contains("downloadx?f=")) {
                mFallbackDownloadxUrl = OptiFineScraper.normalizeOptiFineHref(href);
            }
        }
        return true;
    }

    public boolean isDownloadUrl(@Nullable TagNode parentNode, @Nullable HtmlNode htmlNode) {
        if (!(htmlNode instanceof TagNode) || parentNode == null) return false;
        TagNode tagNode = (TagNode) htmlNode;
        if (!"span".equalsIgnoreCase(parentNode.getName())
                || !"Download".equalsIgnoreCase(parentNode.getAttributeByName("id"))) {
            return false;
        }
        if (!"a".equalsIgnoreCase(tagNode.getName())) return false;
        String onclick = tagNode.getAttributeByName("onclick");
        String href = tagNode.getAttributeByName("href");
        return "onDownload()".equals(onclick)
                || (href != null && href.toLowerCase(Locale.ROOT).contains("downloadx?"));
    }
}
