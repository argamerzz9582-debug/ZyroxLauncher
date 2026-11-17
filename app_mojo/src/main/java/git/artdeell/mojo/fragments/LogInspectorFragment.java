package git.artdeell.mojo.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;

/**
 * Page 7: Zyrox Crash & Log Inspector — Full-width dark glass monospace terminal
 * with error (#EF4444) and warning (#FF8C42) syntax highlighting, copy, share, clear & relaunch.
 */
public class LogInspectorFragment extends Fragment {
    public static final String TAG = "LOG_INSPECTOR_FRAGMENT";
    private static final int MAX_PREVIEW_BYTES = 96 * 1024;

    private TextView mStatusBadge;
    private TextView mMetaView;
    private TextView mLogContentView;
    private ScrollView mTerminalScroll;
    private String mCurrentRawLog = "";

    public LogInspectorFragment() {
        super(R.layout.fragment_log_inspector);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStatusBadge = view.findViewById(R.id.tv_log_status_badge);
        mMetaView = view.findViewById(R.id.tv_log_meta);
        mLogContentView = view.findViewById(R.id.tv_log_content);
        mTerminalScroll = view.findViewById(R.id.sv_log_terminal);

        view.findViewById(R.id.btn_log_refresh).setOnClickListener(v -> loadLatestLog());
        view.findViewById(R.id.btn_log_clear).setOnClickListener(v -> clearLatestLog());
        view.findViewById(R.id.btn_log_copy).setOnClickListener(v -> copyLog(v.getContext()));
        view.findViewById(R.id.btn_log_share).setOnClickListener(v -> Tools.shareLog(v.getContext()));
        view.findViewById(R.id.btn_log_relaunch).setOnClickListener(v -> {
            Tools.backToMainMenu(requireActivity());
            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        loadLatestLog();
    }

    private void loadLatestLog() {
        MojoApplication.sExecutorService.execute(() -> {
            File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (!logFile.isFile() || logFile.length() == 0) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mCurrentRawLog = "";
                    mMetaView.setText("latestlog.txt  •  0 KB");
                    mLogContentView.setText(R.string.zyrox_log_empty);
                    applyBadge(false);
                });
                return;
            }

            long totalLen = logFile.length();
            String content = readLogTail(logFile, MAX_PREVIEW_BYTES);
            CharSequence highlighted = ZyroxLogHighlighter.highlightBlock(content);
            String upper = content.toUpperCase(Locale.ROOT);
            boolean hasCrash = upper.contains("FATAL") || upper.contains("EXCEPTION")
                    || upper.contains("SIGSEGV") || upper.contains("SIGABRT")
                    || upper.contains("CRASH REPORT");
            String meta = String.format(Locale.ROOT, "latestlog.txt  •  %.1f KB", totalLen / 1024f);

            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mCurrentRawLog = content;
                mMetaView.setText(meta);
                mLogContentView.setText(highlighted);
                applyBadge(hasCrash);
                mTerminalScroll.post(() -> mTerminalScroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    private void applyBadge(boolean isCrash) {
        Context ctx = getContext();
        if (ctx == null || mStatusBadge == null) return;
        if (isCrash) {
            mStatusBadge.setText(R.string.zyrox_title_crash_report);
            mStatusBadge.setBackgroundResource(R.drawable.bg_zyrox_badge_danger);
            mStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_danger));
        } else {
            mStatusBadge.setText(R.string.zyrox_console_title);
            mStatusBadge.setBackgroundResource(R.drawable.bg_zyrox_badge);
            mStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_amber));
        }
    }

    private void clearLatestLog() {
        MojoApplication.sExecutorService.execute(() -> {
            File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (logFile.isFile()) {
                try (FileOutputStream fos = new FileOutputStream(logFile, false)) {
                    fos.write(new byte[0]);
                } catch (IOException ignored) {
                }
            }
            loadLatestLog();
        });
    }

    private void copyLog(Context context) {
        ClipboardManager mgr = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (mgr != null) {
            mgr.setPrimaryClip(ClipData.newPlainText("zyrox_log",
                    mCurrentRawLog.isEmpty() ? mLogContentView.getText().toString() : mCurrentRawLog));
            Toast.makeText(context, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
        }
    }

    public static String readLogTail(File file, int maxBytes) {
        if (file == null || !file.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len <= 0) return "";
            int toRead = (int) Math.min(len, maxBytes);
            long offset = len - toRead;
            raf.seek(offset);
            byte[] buf = new byte[toRead];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            if (offset > 0) {
                int firstNl = text.indexOf('\n');
                if (firstNl >= 0 && firstNl + 1 < text.length()) {
                    text = "... [earlier lines truncated — use SHARE LOG for full file]\n" + text.substring(firstNl + 1);
                }
            }
            return text;
        } catch (IOException e) {
            return e.toString();
        }
    }
}
