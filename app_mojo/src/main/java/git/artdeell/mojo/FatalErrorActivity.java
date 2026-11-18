package git.artdeell.mojo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.ui.ZyroxLogHighlighter;

/**
 * Zyrox 2.0 Fatal Crash Report Inspector with Zyrox Guard smart diagnosis,
 * automatic secret/token redaction on clipboard & share, and syntax-highlighted stack trace.
 */
public class FatalErrorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            finishAndRemoveTask();
            return;
        }
        setContentView(R.layout.activity_zyrox_crash_report);
        git.artdeell.mojo.utils.lifecycle.AppTaskGuard.pruneDuplicateTasks(this);

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
        subtitle.setText(errHeader);
        traceView.setText(ZyroxLogHighlighter.highlightBlock(redactedTrace));

        // Zyrox Guard local crash classification
        ZyroxCrashAnalyzer.Diagnosis diag = ZyroxCrashAnalyzer.analyze(redactedTrace);
        View guardBox = findViewById(R.id.layout_zyrox_guard);
        TextView guardSummary = findViewById(R.id.tv_zyrox_guard_summary);
        if (guardBox != null && guardSummary != null
                && diag.category != ZyroxCrashAnalyzer.Category.NO_CRASH_DETECTED) {
            guardBox.setVisibility(View.VISIBLE);
            guardSummary.setText("ZYROX GUARD: " + diag.formatCompactSummary());
        }

        findViewById(R.id.btn_crash_close).setOnClickListener(v -> finishAndRemoveTask());
        findViewById(R.id.btn_crash_copy).setOnClickListener(v -> {
            ClipboardManager mgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (mgr != null) {
                mgr.setPrimaryClip(ClipData.newPlainText("zyrox_crash",
                        diag.formatCompactSummary() + "\n\n" + redactedTrace));
                Toast.makeText(this, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
            }
        });
        View shareBtn = findViewById(R.id.btn_crash_share);
        shareBtn.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.zyrox_title_crash_report));
            share.putExtra(Intent.EXTRA_TEXT,
                    errHeader + "\n\n" + diag.formatCompactSummary() + "\n\n" + redactedTrace);
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
