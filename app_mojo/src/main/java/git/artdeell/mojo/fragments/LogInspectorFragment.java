package git.artdeell.mojo.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
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
import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;

/**
 * Zyrox 2.0 Smart Crash & Log Inspector (Zyrox Guard) — Dark glass monospace terminal with
 * deterministic local crash classification, confidence scoring, redacted clipboard/file export,
 * and safe recovery shortcuts.
 */
public class LogInspectorFragment extends Fragment {
    public static final String TAG = "LOG_INSPECTOR_FRAGMENT";
    private static final int MAX_PREVIEW_BYTES = 96 * 1024;

    private TextView mStatusBadge;
    private TextView mMetaView;
    private TextView mLogContentView;
    private ScrollView mTerminalScroll;
    private View mGuardContainer;
    private TextView mGuardSummary;
    private Button mGuardActionBtn;
    private String mCurrentRedactedLog = "";
    private ZyroxCrashAnalyzer.Diagnosis mCurrentDiagnosis;

    public LogInspectorFragment() {
        super(R.layout.fragment_log_inspector);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStatusBadge = view.findViewById(R.id.tv_log_status_badge);
        mMetaView = view.findViewById(R.id.tv_log_meta);
        mLogContentView = view.findViewById(R.id.tv_log_content);
        mTerminalScroll = view.findViewById(R.id.sv_log_terminal);
        mGuardContainer = view.findViewById(R.id.layout_log_guard);
        mGuardSummary = view.findViewById(R.id.tv_log_guard_summary);
        mGuardActionBtn = view.findViewById(R.id.btn_log_guard_action);

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
                    mCurrentRedactedLog = "";
                    mCurrentDiagnosis = null;
                    mMetaView.setText("latestlog.txt  •  0 KB  •  REDACTED");
                    mLogContentView.setText(R.string.zyrox_log_empty);
                    if (mGuardContainer != null) mGuardContainer.setVisibility(View.GONE);
                    applyBadge(false);
                });
                return;
            }

            long totalLen = logFile.length();
            String rawTail = readLogTail(logFile, MAX_PREVIEW_BYTES);
            String redacted = LogRedactor.redact(rawTail);
            ZyroxCrashAnalyzer.Diagnosis diagnosis = ZyroxCrashAnalyzer.analyze(redacted);
            CharSequence highlighted = ZyroxLogHighlighter.highlightBlock(redacted);
            boolean hasCrash = diagnosis.category != ZyroxCrashAnalyzer.Category.NO_CRASH_DETECTED;
            String meta = String.format(Locale.ROOT, "latestlog.txt  •  %.1f KB  •  AUTO-REDACTED", totalLen / 1024f);

            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mCurrentRedactedLog = redacted;
                mCurrentDiagnosis = diagnosis;
                mMetaView.setText(meta);
                mLogContentView.setText(highlighted);
                applyBadge(hasCrash);
                bindGuardBanner(diagnosis);
                mTerminalScroll.post(() -> mTerminalScroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    private void bindGuardBanner(@NonNull ZyroxCrashAnalyzer.Diagnosis diag) {
        if (mGuardContainer == null || mGuardSummary == null) return;
        if (diag.category == ZyroxCrashAnalyzer.Category.NO_CRASH_DETECTED) {
            mGuardContainer.setVisibility(View.GONE);
            return;
        }
        mGuardContainer.setVisibility(View.VISIBLE);
        mGuardSummary.setText("ZYROX GUARD: " + diag.formatCompactSummary());
        if (mGuardActionBtn == null) return;

        switch (diag.primaryAction) {
            case OPEN_INSTANCE_STUDIO:
                mGuardActionBtn.setVisibility(View.VISIBLE);
                mGuardActionBtn.setText(R.string.zyrox_nav_instances);
                mGuardActionBtn.setOnClickListener(v -> Tools.swapFragment(
                        requireActivity(), InstanceEditorFragment.class, InstanceEditorFragment.TAG, null));
                break;
            case OPEN_MOD_MANAGER:
                mGuardActionBtn.setVisibility(View.VISIBLE);
                mGuardActionBtn.setText(R.string.zyrox_title_content_manager);
                mGuardActionBtn.setOnClickListener(v -> Tools.swapFragment(
                        requireActivity(), ContentManagerFragment.class, ContentManagerFragment.TAG, null));
                break;
            case OPEN_DOWNLOAD_CENTER:
                mGuardActionBtn.setVisibility(View.VISIBLE);
                mGuardActionBtn.setText(R.string.zyrox_title_downloads);
                mGuardActionBtn.setOnClickListener(v -> Tools.swapFragment(
                        requireActivity(), DownloadCenterFragment.class, DownloadCenterFragment.TAG, null));
                break;
            case SWITCH_SAFE_RENDERER:
                mGuardActionBtn.setVisibility(View.VISIBLE);
                mGuardActionBtn.setText(R.string.zyrox_safe_mode_apply);
                mGuardActionBtn.setOnClickListener(v -> {
                    GraphicsAdvisor.applyProfile(GraphicsAdvisor.Profile.SAFE_COMPATIBILITY);
                    Instance cur = Instances.loadSelectedInstance();
                    if (cur != null && cur.lastKnownGoodRenderer != null) {
                        InstanceManager.restoreLastKnownGoodRenderer(cur);
                    }
                    Toast.makeText(requireContext(), R.string.zyrox_safe_mode_applied, Toast.LENGTH_LONG).show();
                });
                break;
            default:
                mGuardActionBtn.setVisibility(View.GONE);
                break;
        }
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
            mStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.zyrox_lg_text));
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
            String safePayload = LogRedactor.redact(
                    mCurrentRedactedLog.isEmpty() ? mLogContentView.getText().toString() : mCurrentRedactedLog);
            if (mCurrentDiagnosis != null
                    && mCurrentDiagnosis.category != ZyroxCrashAnalyzer.Category.NO_CRASH_DETECTED) {
                safePayload = mCurrentDiagnosis.formatCompactSummary() + "\n\n" + safePayload;
            }
            mgr.setPrimaryClip(ClipData.newPlainText("zyrox_log", safePayload));
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
                    text = "... [earlier lines truncated — use SHARE LOG for full redacted file]\n"
                            + text.substring(firstNl + 1);
                }
            }
            return text;
        } catch (IOException e) {
            return e.toString();
        }
    }
}
