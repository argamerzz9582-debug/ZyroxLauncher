package git.artdeell.mojo;

import static git.artdeell.mojo.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.fragments.LogInspectorFragment;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;
import git.artdeell.mojo.utils.lifecycle.AppTaskGuard;

/**
 * Post-game non-zero exit &amp; native signal inspector featuring the plain-English
 * {@link ZyroxCrashAnalyzer} Diagnosis Card, 1-Tap Fix &amp; Relaunch, collapsible "Technical
 * Details" stacktrace viewer, and strict {@link LogRedactor} token redaction.
 */
@Keep
public class ExitActivity extends AppCompatActivity {

    @SuppressLint("StringFormatInvalid")
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent() != null ? getIntent().getExtras() : null;
        if (extras != null) {
            code = extras.getInt("code", -1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        setContentView(R.layout.activity_zyrox_crash_report);
        AppTaskGuard.pruneDuplicateTasks(this);

        String message = isSignal
                ? getString(R.string.mcn_abort_title)
                : getString(R.string.mcn_exit_title, code);
        TextView subtitle = findViewById(R.id.tv_crash_subtitle);
        TextView traceView = findViewById(R.id.tv_crash_stacktrace);
        ScrollView scrollView = findViewById(R.id.sv_crash_terminal);

        subtitle.setText(LogRedactor.redact(message));
        File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        final String rawTail = LogInspectorFragment.readLogTail(logFile, 64 * 1024);
        final String displayLog = LogRedactor.redact(rawTail.isEmpty() ? message : rawTail);
        traceView.setText(ZyroxLogHighlighter.highlightBlock(displayLog));

        final ZyroxCrashAnalyzer.Diagnosis diag = ZyroxCrashAnalyzer.analyze(displayLog);
        ShowErrorActivity.bindCrashReportCard(this, diag, scrollView);

        findViewById(R.id.btn_crash_close).setOnClickListener(v -> finishAndRemoveTask());
        findViewById(R.id.btn_crash_copy).setOnClickListener(v -> {
            String combined = diag.formatCompactSummary() + "\n\n" + displayLog;
            if (LogRedactor.copyRedactedToClipboard(this, "zyrox_exit_log", combined)) {
                Toast.makeText(this, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_crash_share).setOnClickListener(v -> shareLog(this));
        findViewById(R.id.btn_crash_relaunch).setOnClickListener(v -> {
            Tools.restartLauncherActivity(this);
            finishAndRemoveTask();
        });
    }

    @SuppressWarnings("unused") // Invoked via JNI from native jre_launcher_new
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        if (!isSignal && code == 0) {
            if (ctx != null) Tools.restartLauncherActivity(ctx);
            System.exit(0);
            return;
        }
        if (ctx == null) {
            System.exit(0);
            return;
        }
        Intent i = new Intent(ctx, ExitActivity.class);
        i.putExtra("code", code);
        i.putExtra("isSignal", isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
