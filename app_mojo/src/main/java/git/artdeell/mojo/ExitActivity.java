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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import git.artdeell.mojo.fragments.LogInspectorFragment;
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

        String message = isSignal ? getString(R.string.mcn_abort_title) : getString(R.string.mcn_exit_title, code);
        TextView subtitle = findViewById(R.id.tv_crash_subtitle);
        TextView traceView = findViewById(R.id.tv_crash_stacktrace);
        ScrollView scrollView = findViewById(R.id.sv_crash_terminal);

        subtitle.setText(message);
        File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        final String logTail = LogInspectorFragment.readLogTail(logFile, 64 * 1024);
        final String displayLog = logTail.isEmpty() ? message : logTail;
        traceView.setText(ZyroxLogHighlighter.highlightBlock(displayLog));
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        findViewById(R.id.btn_crash_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_crash_copy).setOnClickListener(v -> {
            ClipboardManager mgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (mgr != null) {
                mgr.setPrimaryClip(ClipData.newPlainText("zyrox_exit_log", displayLog));
                Toast.makeText(this, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_crash_share).setOnClickListener(v -> shareLog(this));
        findViewById(R.id.btn_crash_relaunch).setOnClickListener(v -> {
            Tools.restartLauncherActivity(this);
            finish();
        });
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
