package git.artdeell.mojo;

import static git.artdeell.mojo.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.fragments.LogInspectorFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;

@Keep
public class ExitActivity extends AppCompatActivity {

    @SuppressLint("StringFormatInvalid")
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            code = extras.getInt("code", -1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        setContentView(R.layout.activity_zyrox_crash_report);
        git.artdeell.mojo.utils.lifecycle.AppTaskGuard.pruneDuplicateTasks(this);

        String message = isSignal ? getString(R.string.mcn_abort_title) : getString(R.string.mcn_exit_title, code);
        TextView subtitle = findViewById(R.id.tv_crash_subtitle);
        TextView traceView = findViewById(R.id.tv_crash_stacktrace);
        ScrollView scrollView = findViewById(R.id.sv_crash_terminal);

        subtitle.setText(message);
        File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        final String rawTail = LogInspectorFragment.readLogTail(logFile, 64 * 1024);
        final String displayLog = LogRedactor.redact(rawTail.isEmpty() ? message : rawTail);
        traceView.setText(ZyroxLogHighlighter.highlightBlock(displayLog));
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        // Zyrox Guard smart crash diagnosis + one-tap actionable Auto-Fix buttons
        // (disable crashing mod, adjust JVM heap RAM, switch JRE via JavaRuntimeAdvisor, or GPU preset)
        ZyroxCrashAnalyzer.Diagnosis diag = ZyroxCrashAnalyzer.analyze(displayLog);
        View guardBox = findViewById(R.id.layout_zyrox_guard);
        TextView guardSummary = findViewById(R.id.tv_zyrox_guard_summary);
        View autoFixScroll = findViewById(R.id.sv_zyrox_autofix_actions);
        android.widget.LinearLayout autoFixRow = findViewById(R.id.layout_zyrox_autofix_actions);
        Button guardFix = findViewById(R.id.btn_zyrox_guard_fix);
        if (guardBox != null && guardSummary != null
                && diag.category != ZyroxCrashAnalyzer.Category.NO_CRASH_DETECTED) {
            guardBox.setVisibility(View.VISIBLE);
            guardSummary.setText("ZYROX GUARD: " + diag.formatCompactSummary());
            if (autoFixRow != null && autoFixScroll != null && !diag.autoFixes.isEmpty()) {
                int added = ShowErrorActivity.populateAutoFixButtons(
                        this, autoFixRow, diag, guardSummary);
                if (added > 0) {
                    autoFixScroll.setVisibility(View.VISIBLE);
                }
            } else if (guardFix != null
                    && diag.primaryAction == ZyroxCrashAnalyzer.SuggestedAction.SWITCH_SAFE_RENDERER) {
                if (autoFixScroll != null) autoFixScroll.setVisibility(View.VISIBLE);
                guardFix.setVisibility(View.VISIBLE);
                guardFix.setOnClickListener(v -> applySafeCompatibilityRecovery(guardFix));
            }
        }

        findViewById(R.id.btn_crash_close).setOnClickListener(v -> finishAndRemoveTask());
        findViewById(R.id.btn_crash_copy).setOnClickListener(v -> {
            ClipboardManager mgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (mgr != null) {
                mgr.setPrimaryClip(ClipData.newPlainText("zyrox_exit_log",
                        diag.formatCompactSummary() + "\n\n" + displayLog));
                Toast.makeText(this, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_crash_share).setOnClickListener(v -> shareLog(this));
        findViewById(R.id.btn_crash_relaunch).setOnClickListener(v -> {
            Tools.restartLauncherActivity(this);
            finishAndRemoveTask();
        });
    }

    /**
     * User-confirmed Safe Recovery Mode: restores last-known-good renderer (if available) and
     * conservative display scaling without touching worlds, saves, or mods.
     */
    private void applySafeCompatibilityRecovery(Button btn) {
        try {
            GraphicsAdvisor.applyProfile(GraphicsAdvisor.Profile.SAFE_COMPATIBILITY);
            Instance current = Instances.loadSelectedInstance();
            if (current != null && current.lastKnownGoodRenderer != null) {
                InstanceManager.restoreLastKnownGoodRenderer(current);
            }
            btn.setEnabled(false);
            Toast.makeText(this, R.string.zyrox_safe_mode_applied, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("unused") // used by native jre_launcher_new
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        if ((!isSignal && code == 0)) {
            if (ctx != null) Tools.restartLauncherActivity(ctx);
            System.exit(0);
            return;
        }

        Object lock = new Object();
        Tools.runOnUiThread(() -> {
            Intent i = new Intent(ctx, ExitActivity.class);
            i.putExtra("code", code);
            i.putExtra("isSignal", isSignal);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            synchronized (lock) {
                lock.notify();
            }
        });
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Log.e("ExitActivity", "Waiting on lock failed: " + e);
            }
        }
        System.exit(0);
    }
}
