package git.artdeell.mojo.ui;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.Locale;

/**
 * Syntax highlighter for Zyrox Crash & Log Inspector and live JVM console.
 * Colors fatal/exception lines in red (#EF4444), warnings in warm amber (#FF8C42),
 * and info lines in readable warm parchment (#D6D3D1).
 */
public final class ZyroxLogHighlighter {
    public static final int COLOR_ERROR = 0xFFFB7185;
    public static final int COLOR_TRACE = 0xFFFDA4AF;
    public static final int COLOR_WARN = 0xFFFFD685;
    public static final int COLOR_HEADER = 0xFF98C2FF;
    public static final int COLOR_INFO = 0xFFE4EEFF;

    private ZyroxLogHighlighter() {}

    public static int classifyLineColor(String line) {
        if (line == null || line.isEmpty()) return COLOR_INFO;
        String trimmed = line.trim();
        if (trimmed.startsWith("at ") || trimmed.startsWith("... ")) {
            return COLOR_TRACE;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("FATAL")
                || upper.contains("/ERROR")
                || upper.contains("[ERROR")
                || upper.contains("ERROR:")
                || upper.contains("SEVERE")
                || upper.contains("EXCEPTION")
                || upper.contains("CAUSED BY:")
                || upper.contains("SIGSEGV")
                || upper.contains("SIGABRT")
                || upper.contains("CRASH")) {
            return COLOR_ERROR;
        }
        if (upper.contains("WARN")
                || upper.contains("WARNING")
                || upper.contains("DEPRECATED")) {
            return COLOR_WARN;
        }
        if (trimmed.startsWith("Info:")
                || trimmed.startsWith("---------")
                || upper.contains("ZYROX")) {
            return COLOR_HEADER;
        }
        return COLOR_INFO;
    }

    public static CharSequence highlightLine(String line) {
        if (line == null) line = "";
        String withNewline = line + "\n";
        SpannableStringBuilder sb = new SpannableStringBuilder(withNewline);
        int color = classifyLineColor(line);
        sb.setSpan(new ForegroundColorSpan(color), 0, withNewline.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    public static CharSequence highlightBlock(String rawLog) {
        if (rawLog == null || rawLog.isEmpty()) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = rawLog.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = sb.length();
            sb.append(line);
            if (i < lines.length - 1) sb.append('\n');
            int end = sb.length();
            if (end > start) {
                int color = classifyLineColor(line);
                sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }
}
