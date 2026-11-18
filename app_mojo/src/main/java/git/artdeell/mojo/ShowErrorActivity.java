package git.artdeell.mojo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.Serializable;
import java.util.List;

import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.AutoFix;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.AutoFixResult;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.Category;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.Diagnosis;
import git.artdeell.mojo.fragments.LogInspectorFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.NotificationUtils;
import git.artdeell.mojo.utils.lifecycle.ContextExecutorTask;

/**
 * Displays runtime/startup exceptions with integrated {@link ZyroxCrashAnalyzer} diagnosis and
 * one-tap actionable auto-fix buttons (disable crashing mod, rebalance JVM heap RAM, switch JRE
 * version via {@code JavaRuntimeAdvisor}, or apply an architecture-matched GPU renderer preset).
 */
public class ShowErrorActivity extends Activity {

    private static final String ERROR_ACTIVITY_REMOTE_TASK = "remoteTask";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        RemoteErrorTask remoteErrorTask =
                (RemoteErrorTask) intent.getSerializableExtra(ERROR_ACTIVITY_REMOTE_TASK);
        if (remoteErrorTask == null) {
            finish();
            return;
        }
        remoteErrorTask.executeWithActivity(this);
    }

    /**
     * Populates a horizontal action bar with one-tap auto-fix buttons synthesized by
     * {@link ZyroxCrashAnalyzer}. Shared by {@link ShowErrorActivity} and {@link ExitActivity}.
     */
    public static int populateAutoFixButtons(@NonNull Context context,
                                             @NonNull LinearLayout container,
                                             @NonNull Diagnosis diagnosis,
                                             @Nullable TextView statusFeedbackView) {
        List<AutoFix> fixes = diagnosis.autoFixes;
        if (fixes.isEmpty()) return 0;

        float density = context.getResources().getDisplayMetrics().density;
        int hPad = Math.round(11f * density);
        int btnHeight = Math.round(32f * density);
        int spacing = Math.round(8f * density);

        Instance selected = null;
        try {
            selected = Instances.loadSelectedInstance();
        } catch (Throwable ignored) {
        }
        final Instance targetInstance = selected;

        int added = 0;
        for (int i = 0; i < fixes.size(); i++) {
            final AutoFix fix = fixes.get(i);
            Button btn = new Button(context);
            btn.setAllCaps(false);
            btn.setText("⚡ " + fix.buttonLabel);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            btn.setTextColor(Color.WHITE);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setBackgroundResource(R.drawable.btn_zyrox_secondary);
            btn.setPadding(hPad, 0, hPad, 0);
            btn.setMinHeight(btnHeight);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, btnHeight);
            if (added > 0) lp.setMarginStart(spacing);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                AutoFixResult res = ZyroxCrashAnalyzer.executeAutoFix(context, fix, targetInstance);
                if (res.applied) {
                    btn.setEnabled(false);
                    btn.setText("✓ " + fix.buttonLabel);
                    btn.setAlpha(0.75f);
                }
                if (statusFeedbackView != null) {
                    statusFeedbackView.setText("AUTO-FIX: " + res.message);
                }
                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show();
            });
            container.addView(btn);
            added++;
        }
        return added;
    }

    /**
     * Presents an actionable error &amp; auto-fix dialog whenever {@code diagnosis} contains
     * one-tap auto-fix remedies; otherwise falls back to standard redacted {@code Tools.showError}.
     */
    public static void showDiagnosedError(@NonNull Activity activity,
                                          @Nullable String rolledMsg,
                                          @NonNull Throwable throwable,
                                          boolean exitIfOk) {
        String trace = Tools.printToString(throwable);
        String logTail = "";
        try {
            if (Tools.DIR_GAME_HOME != null) {
                File latest = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
                if (latest.isFile()) {
                    logTail = LogInspectorFragment.readLogTail(latest, 32 * 1024);
                }
            }
        } catch (Throwable ignored) {
        }

        String combined = (rolledMsg != null ? rolledMsg + "\n" : "") + trace
                + (logTail.isEmpty() ? "" : "\n" + logTail);
        Diagnosis diagnosis = ZyroxCrashAnalyzer.analyze(combined);

        if (diagnosis.category == Category.NO_CRASH_DETECTED || diagnosis.autoFixes.isEmpty()) {
            Tools.showError(activity, rolledMsg, throwable, exitIfOk);
            return;
        }

        activity.runOnUiThread(() -> {
            float density = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(14f * density);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad / 2, pad, pad / 2);

            TextView summaryView = new TextView(activity);
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            summaryView.setText(diagnosis.formatCompactSummary());
            root.addView(summaryView);

            HorizontalScrollView fixScroll = new HorizontalScrollView(activity);
            fixScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollLp.topMargin = Math.round(10f * density);
            fixScroll.setLayoutParams(scrollLp);

            LinearLayout fixRow = new LinearLayout(activity);
            fixRow.setOrientation(LinearLayout.HORIZONTAL);
            fixRow.setGravity(Gravity.CENTER_VERTICAL);
            populateAutoFixButtons(activity, fixRow, diagnosis, summaryView);
            fixScroll.addView(fixRow);
            root.addView(fixScroll);

            ScrollView traceScroll = new ScrollView(activity);
            LinearLayout.LayoutParams traceLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(150f * density));
            traceLp.topMargin = Math.round(10f * density);
            traceScroll.setLayoutParams(traceLp);

            TextView traceText = new TextView(activity);
            traceText.setTypeface(Typeface.MONOSPACE);
            traceText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            String safeTrace = LogRedactor.redact(rolledMsg != null
                    ? rolledMsg + "\n\n" + trace : trace);
            traceText.setText(safeTrace);
            traceScroll.addView(traceText);
            root.addView(traceScroll);

            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle("Zyrox Auto-Fixer • " + diagnosis.title)
                    .setView(root)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (exitIfOk) activity.finishAndRemoveTask();
                    })
                    .setNeutralButton(android.R.string.copy, (d, w) -> {
                        ClipboardManager mgr = (ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (mgr != null) {
                            mgr.setPrimaryClip(ClipData.newPlainText(
                                    "zyrox_error",
                                    diagnosis.formatCompactSummary() + "\n\n" + safeTrace));
                        }
                        if (exitIfOk) activity.finishAndRemoveTask();
                    })
                    .setCancelable(!exitIfOk);
            installRemoteDialogHandling(activity, builder);
            builder.show();
        });
    }

    public static class RemoteErrorTask implements ContextExecutorTask, Serializable {
        private final Throwable mThrowable;
        private final String mRolledMsg;

        public RemoteErrorTask(Throwable mThrowable, String mRolledMsg) {
            this.mThrowable = mThrowable;
            this.mRolledMsg = mRolledMsg;
        }

        @Override
        public void executeWithActivity(Activity activity) {
            if (mThrowable instanceof ContextExecutorTask) {
                ((ContextExecutorTask) mThrowable).executeWithActivity(activity);
            } else {
                showDiagnosedError(activity, mRolledMsg, mThrowable,
                        activity instanceof ShowErrorActivity);
            }
        }

        @Override
        public void executeWithApplication(Context context) {
            Intent showErrorIntent = new Intent(context, ShowErrorActivity.class);
            showErrorIntent.putExtra(ERROR_ACTIVITY_REMOTE_TASK, this);
            NotificationUtils.sendBasicNotification(
                    context,
                    R.string.notif_error_occured,
                    R.string.notif_error_occured_desc,
                    showErrorIntent,
                    NotificationUtils.PENDINGINTENT_CODE_SHOW_ERROR,
                    NotificationUtils.NOTIFICATION_ID_SHOW_ERROR);
        }
    }

    public static void installRemoteDialogHandling(Activity callerActivity,
                                                   @NonNull AlertDialog.Builder builder) {
        if (callerActivity instanceof ShowErrorActivity) {
            builder.setOnDismissListener(d -> callerActivity.finishAndRemoveTask());
        }
    }
}
