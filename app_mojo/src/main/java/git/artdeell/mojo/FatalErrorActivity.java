package git.artdeell.mojo;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;
import git.artdeell.mojo.utils.lifecycle.AppTaskGuard;

/**
 * Beginner-friendly Fatal Crash Screen with a plain-English Diagnosis Card, executable
 * <b>1-Tap Fix &amp; Relaunch</b> action, collapsed "Technical Details" stacktrace accordion,
 * and strict {@link LogRedactor} token/UUID/session stripping on every copy and share path.
 */
public class FatalErrorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent() != null ? getIntent().getExtras() : null;
        if (extras == null) {
            finishAndRemoveTask();
            return;
        }
        setContentView(R.layout.activity_zyrox_crash_report);
        AppTaskGuard.pruneDuplicateTasks(this);

        boolean storageAllow = extras.getBoolean("storageAllow", false);
        Throwable throwable = (Throwable) extras.getSerializable("throwable");
        final String rawTrace = throwable != null ? Tools.printToString(throwable) : "<null>";
        final String redactedTrace = LogRedactor.redact(rawTrace);
        String strSavePath = extras.getString("savePath");
        final String errHeader = LogRedactor.redact(storageAllow
                ? "Crash report saved to " + strSavePath
                : getString(R.string.error_fatal));

        TextView subtitle = findViewById(R.id.tv_crash_subtitle);
        TextView traceView = findViewById(R.id.tv_crash_stacktrace);
        ScrollView scrollView = findViewById(R.id.sv_crash_terminal);
        subtitle.setText(errHeader);
        traceView.setText(ZyroxLogHighlighter.highlightBlock(redactedTrace));

        final ZyroxCrashAnalyzer.Diagnosis diag = ZyroxCrashAnalyzer.analyze(redactedTrace);
        ShowErrorActivity.bindCrashReportCard(this, diag, scrollView);

        findViewById(R.id.btn_crash_close).setOnClickListener(v -> finishAndRemoveTask());
        findViewById(R.id.btn_crash_copy).setOnClickListener(v -> {
            String payload = diag.formatCompactSummary() + "\n\n" + redactedTrace;
            if (LogRedactor.copyRedactedToClipboard(this, "zyrox_crash", payload)) {
                Toast.makeText(this, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_crash_share).setOnClickListener(v -> {
            String safeShare = LogRedactor.redact(
                    errHeader + "\n\n" + diag.formatCompactSummary() + "\n\n" + redactedTrace);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.zyrox_title_crash_report));
            share.putExtra(Intent.EXTRA_TEXT, safeShare);
            startActivity(Intent.createChooser(share, getString(R.string.zyrox_share_log)));
        });
        findViewById(R.id.btn_crash_relaunch).setOnClickListener(v -> {
            Tools.restartLauncherActivity(FatalErrorActivity.this);
            finishAndRemoveTask();
        });
    }

    public static void showError(Context ctx, String savePath, boolean storageAllow, Throwable th) {
        Intent fatalErrorIntent = new Intent(ctx, FatalErrorActivity.class);
        fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        fatalErrorIntent.putExtra("throwable", th);
        fatalErrorIntent.putExtra("savePath", savePath);
        fatalErrorIntent.putExtra("storageAllow", storageAllow);
        ctx.startActivity(fatalErrorIntent);
    }
}
