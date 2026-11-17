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

import git.artdeell.mojo.ui.ZyroxLogHighlighter;

/**
 * Page 7: Zyrox Fatal Crash Report Inspector with syntax-highlighted stack trace,
 * Copy Log, Share Log, and Relaunch Zyrox actions.
 */
public class FatalErrorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_zyrox_crash_report);

        boolean storageAllow = extras.getBoolean("storageAllow", false);
        Throwable throwable = (Throwable) extras.getSerializable("throwable");
        final String stackTrace = throwable != null ? Tools.printToString(throwable) : "<null>";
        String strSavePath = extras.getString("savePath");
        String errHeader = storageAllow
                ? "Crash report saved to " + strSavePath
                : getString(R.string.error_fatal);

        TextView subtitle = findViewById(R.id.tv_crash_subtitle);
        TextView traceView = findViewById(R.id.tv_crash_stacktrace);
        subtitle.setText(errHeader);
        traceView.setText(ZyroxLogHighlighter.highlightBlock(stackTrace));

        findViewById(R.id.btn_crash_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_crash_copy).setOnClickListener(v -> {
            ClipboardManager mgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (mgr != null) {
                mgr.setPrimaryClip(ClipData.newPlainText("zyrox_crash", stackTrace));
                Toast.makeText(this, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
            }
        });
        View shareBtn = findViewById(R.id.btn_crash_share);
        shareBtn.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.zyrox_title_crash_report));
            share.putExtra(Intent.EXTRA_TEXT, errHeader + "\n\n" + stackTrace);
            startActivity(Intent.createChooser(share, getString(R.string.zyrox_share_log)));
        });
        findViewById(R.id.btn_crash_relaunch).setOnClickListener(v -> {
            Intent intent = new Intent(FatalErrorActivity.this, LauncherActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
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
