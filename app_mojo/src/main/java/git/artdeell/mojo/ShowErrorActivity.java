package git.artdeell.mojo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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

import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.AutoFix;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.AutoFixResult;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.Category;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer.Diagnosis;
import git.artdeell.mojo.fragments.LogInspectorFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.NotificationUtils;
import git.artdeell.mojo.utils.lifecycle.ContextExecutorTask;

/**
 * Beginner-friendly runtime/startup crash dialog &amp; shared Diagnosis Card binder for
 * {@link FatalErrorActivity} and {@link ExitActivity}.
 *
 * <p>Surfaces plain-English root cause cards at the top, executable <b>1-Tap Fix &amp; Relaunch</b>
 * buttons (RAM boost/rebalance, compatible Java runtime switch, missing Modrinth dependency
 * installer, crashing mod quarantine, and safe GPU recovery), collapses raw stacktraces behind
 * "Technical Details", and routes every copy/share strictly through {@link LogRedactor#redact}.</p>
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
     * Binds {@code activity_zyrox_crash_report.xml}'s Liquid Glass Diagnosis Card, primary
     * <b>1-Tap Fix &amp; Relaunch</b> button, secondary auto-fix chips, and collapsed "Technical
     * Details" toggle.
     */
    public static void bindCrashReportCard(@NonNull Activity activity,
                                           @NonNull Diagnosis diag,
                                           @Nullable ScrollView terminalScroll) {
        View guardBox = activity.findViewById(R.id.layout_zyrox_guard);
        TextView titleView = activity.findViewById(R.id.tv_zyrox_guard_title);
        TextView summaryView = activity.findViewById(R.id.tv_zyrox_guard_summary);
        TextView confBadge = activity.findViewById(R.id.tv_crash_confidence_badge);
        Button fixAndRelaunchBtn = activity.findViewById(R.id.btn_crash_fix_and_relaunch);
        View autoFixScroll = activity.findViewById(R.id.sv_zyrox_autofix_actions);
        LinearLayout autoFixRow = activity.findViewById(R.id.layout_zyrox_autofix_actions);
        Button guardFix = activity.findViewById(R.id.btn_zyrox_guard_fix);
        TextView toggleBtn = activity.findViewById(R.id.btn_toggle_technical_details);
        View collapsedSpacer = activity.findViewById(R.id.spacer_crash_collapsed);

        if (guardBox != null) {
            guardBox.setVisibility(View.VISIBLE);
        }
        if (confBadge != null && diag.category != Category.NO_CRASH_DETECTED) {
            confBadge.setText(diag.confidence.label);
            confBadge.setVisibility(View.VISIBLE);
        }
        if (titleView != null) {
            titleView.setText(diag.category == Category.NO_CRASH_DETECTED
                    ? "Minecraft Stopped Unexpectedly"
                    : LogRedactor.redact(diag.title));
        }
        if (summaryView != null) {
            StringBuilder friendly = new StringBuilder();
            friendly.append(LogRedactor.redact(diag.explanation));
            if (!diag.recommendedSteps.isEmpty()) {
                friendly.append("\n• ").append(LogRedactor.redact(diag.recommendedSteps.get(0)));
            }
            summaryView.setText(friendly.toString());
        }

        // Primary 1-Tap Fix & Relaunch button
        if (fixAndRelaunchBtn != null && !diag.autoFixes.isEmpty()) {
            final AutoFix primaryFix = diag.autoFixes.get(0);
            fixAndRelaunchBtn.setVisibility(View.VISIBLE);
            fixAndRelaunchBtn.setText("1-Tap Fix & Relaunch • " + primaryFix.buttonLabel);
            fixAndRelaunchBtn.setOnClickListener(v -> runAsyncAutoFix(
                    activity, primaryFix, true, fixAndRelaunchBtn, summaryView));
        }

        // Secondary auto-fix chips (if multiple fixes exist, e.g., install dep OR disable mod)
        if (autoFixRow != null && autoFixScroll != null && diag.autoFixes.size() > 1) {
            int added = populateAutoFixButtons(activity, autoFixRow, diag, summaryView, 1);
            if (added > 0) {
                autoFixScroll.setVisibility(View.VISIBLE);
            }
        } else if (fixAndRelaunchBtn == null && autoFixRow != null && autoFixScroll != null
                && !diag.autoFixes.isEmpty()) {
            int added = populateAutoFixButtons(activity, autoFixRow, diag, summaryView, 0);
            if (added > 0) {
                autoFixScroll.setVisibility(View.VISIBLE);
            }
        } else if ((diag.autoFixes.isEmpty())
                && guardFix != null
                && diag.primaryAction == ZyroxCrashAnalyzer.SuggestedAction.SWITCH_SAFE_RENDERER) {
            if (autoFixScroll != null) autoFixScroll.setVisibility(View.VISIBLE);
            guardFix.setVisibility(View.VISIBLE);
            guardFix.setOnClickListener(v -> {
                GraphicsAdvisor.applyProfile(GraphicsAdvisor.Profile.SAFE_COMPATIBILITY);
                Instance cur = Instances.loadSelectedInstance();
                if (cur != null && cur.lastKnownGoodRenderer != null) {
                    InstanceManager.restoreLastKnownGoodRenderer(cur);
                }
                guardFix.setEnabled(false);
                Toast.makeText(activity, R.string.zyrox_safe_mode_applied, Toast.LENGTH_LONG).show();
                ZyroxCrashAnalyzer.relaunchFixedInstance(activity);
            });
        }

        // Collapsible "Technical Details" accordion (collapsed by default)
        if (toggleBtn != null && terminalScroll != null) {
            terminalScroll.setVisibility(View.GONE);
            if (collapsedSpacer != null) collapsedSpacer.setVisibility(View.VISIBLE);
            toggleBtn.setOnClickListener(v -> {
                boolean expand = terminalScroll.getVisibility() != View.VISIBLE;
                terminalScroll.setVisibility(expand ? View.VISIBLE : View.GONE);
                if (collapsedSpacer != null) {
                    collapsedSpacer.setVisibility(expand ? View.GONE : View.VISIBLE);
                }
                toggleBtn.setText(expand
                        ? "▾ Hide Technical Details (Auto-Redacted Log & Stacktrace)"
                        : "▸ Technical Details (Auto-Redacted Log & Stacktrace)");
                if (expand) {
                    terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
                }
            });
        }
    }

    private static void runAsyncAutoFix(@NonNull Activity activity,
                                        @NonNull AutoFix fix,
                                        boolean relaunchOnSuccess,
                                        @NonNull Button triggerBtn,
                                        @Nullable TextView statusView) {
        triggerBtn.setEnabled(false);
        final CharSequence origText = triggerBtn.getText();
        triggerBtn.setText("Applying Fix…");
        final Context appCtx = activity.getApplicationContext();

        MojoApplication.sExecutorService.execute(() -> {
            Instance selected = null;
            try {
                selected = Instances.loadSelectedInstance();
            } catch (Throwable ignored) {
            }
            // SWT/Dialog-opening JRE store needs Activity context on UI thread if not installed
            final Instance target = selected;
            final AutoFixResult result;
            if (fix.type == ZyroxCrashAnalyzer.AutoFixType.INSTALL_MISSING_DEPENDENCY
                    || fix.type == ZyroxCrashAnalyzer.AutoFixType.DISABLE_CRASHING_MOD
                    || fix.type == ZyroxCrashAnalyzer.AutoFixType.ADJUST_JVM_HEAP
                    || fix.type == ZyroxCrashAnalyzer.AutoFixType.DISABLE_ACTIVE_SHADERPACK
                    || fix.type == ZyroxCrashAnalyzer.AutoFixType.APPLY_SAFE_RENDERER) {
                result = ZyroxCrashAnalyzer.executeAutoFix(appCtx, fix, target);
            } else {
                result = null;
            }

            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                AutoFixResult finalRes = result != null
                        ? result
                        : ZyroxCrashAnalyzer.executeAutoFix(activity, fix, target);
                String safeMsg = LogRedactor.redact(finalRes.message);
                if (statusView != null) {
                    statusView.setText("AUTO-FIX: " + safeMsg);
                }
                Toast.makeText(appCtx, safeMsg, Toast.LENGTH_LONG).show();
                if (finalRes.applied) {
                    triggerBtn.setText("Fixed! Relaunching…");
                    if (relaunchOnSuccess && !finalRes.openedExternalFlow) {
                        ZyroxCrashAnalyzer.relaunchFixedInstance(activity);
                    } else {
                        triggerBtn.setText("Applied: " + fix.buttonLabel);
                        triggerBtn.setAlpha(0.78f);
                    }
                } else {
                    triggerBtn.setEnabled(true);
                    triggerBtn.setText(origText);
                }
            });
        });
    }

    /**
     * Populates a horizontal action bar with one-tap auto-fix buttons synthesized by
     * {@link ZyroxCrashAnalyzer}.
     */
    public static int populateAutoFixButtons(@NonNull Context context,
                                             @NonNull LinearLayout container,
                                             @NonNull Diagnosis diagnosis,
                                             @Nullable TextView statusFeedbackView) {
        return populateAutoFixButtons(context, container, diagnosis, statusFeedbackView, 0);
    }

    public static int populateAutoFixButtons(@NonNull Context context,
                                             @NonNull LinearLayout container,
                                             @NonNull Diagnosis diagnosis,
                                             @Nullable TextView statusFeedbackView,
                                             int startIndex) {
        List<AutoFix> fixes = diagnosis.autoFixes;
        if (fixes.isEmpty() || startIndex >= fixes.size()) return 0;

        float density = context.getResources().getDisplayMetrics().density;
        int hPad = Math.round(11f * density);
        int btnHeight = Math.round(32f * density);
        int spacing = Math.round(8f * density);

        int added = 0;
        for (int i = Math.max(0, startIndex); i < fixes.size(); i++) {
            final AutoFix fix = fixes.get(i);
            Button btn = new Button(context);
            btn.setAllCaps(false);
            btn.setText("Fix: " + fix.buttonLabel);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            btn.setTextColor(Color.WHITE);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setBackgroundResource(R.drawable.btn_zyrox_secondary);
            btn.setIncludeFontPadding(false);
            btn.setMinWidth(0);
            btn.setPadding(hPad, 0, hPad, 0);
            btn.setMinHeight(btnHeight);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, btnHeight);
            if (added > 0) lp.setMarginStart(spacing);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                if (context instanceof Activity) {
                    runAsyncAutoFix((Activity) context, fix, false, btn, statusFeedbackView);
                } else {
                    final Context appCtx = context.getApplicationContext();
                    btn.setEnabled(false);
                    MojoApplication.sExecutorService.execute(() -> {
                        AutoFixResult res = ZyroxCrashAnalyzer.executeAutoFix(appCtx, fix, null);
                        String safeMsg = LogRedactor.redact(res.message);
                        Tools.runOnUiThread(() -> {
                            if (res.applied) {
                                btn.setText("Applied: " + fix.buttonLabel);
                                btn.setAlpha(0.75f);
                            } else {
                                btn.setEnabled(true);
                            }
                            if (statusFeedbackView != null) {
                                statusFeedbackView.setText("AUTO-FIX: " + safeMsg);
                            }
                            Toast.makeText(appCtx, safeMsg, Toast.LENGTH_LONG).show();
                        });
                    });
                }
            });
            container.addView(btn);
            added++;
        }
        return added;
    }

    /**
     * Presents a beginner-friendly error &amp; 1-Tap Fix &amp; Relaunch dialog with raw stacktrace
     * collapsed under "Technical Details" and full {@link LogRedactor} clipboard/display scrubbing.
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

        String combined = LogRedactor.redact((rolledMsg != null ? rolledMsg + "\n" : "") + trace
                + (logTail.isEmpty() ? "" : "\n" + logTail));
        Diagnosis diagnosis = ZyroxCrashAnalyzer.analyze(combined);

        if (diagnosis.category == Category.NO_CRASH_DETECTED || diagnosis.autoFixes.isEmpty()) {
            Tools.showError(activity, rolledMsg, throwable, exitIfOk);
            return;
        }

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            float density = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(14f * density);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad / 2, pad, pad / 2);

            TextView summaryView = new TextView(activity);
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            summaryView.setText(LogRedactor.redact(diagnosis.explanation));
            root.addView(summaryView);

            // Primary 1-Tap Fix & Relaunch button
            AutoFix primaryFix = diagnosis.autoFixes.get(0);
            Button primaryBtn = new Button(activity);
            primaryBtn.setAllCaps(false);
            primaryBtn.setText("1-Tap Fix & Relaunch • " + primaryFix.buttonLabel);
            primaryBtn.setTextColor(Color.WHITE);
            primaryBtn.setTypeface(Typeface.DEFAULT_BOLD);
            primaryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            primaryBtn.setBackgroundResource(R.drawable.btn_zyrox_play);
            LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(38f * density));
            primaryLp.topMargin = Math.round(10f * density);
            primaryBtn.setLayoutParams(primaryLp);
            primaryBtn.setOnClickListener(v -> runAsyncAutoFix(
                    activity, primaryFix, true, primaryBtn, summaryView));
            root.addView(primaryBtn);

            if (diagnosis.autoFixes.size() > 1) {
                HorizontalScrollView fixScroll = new HorizontalScrollView(activity);
                fixScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                scrollLp.topMargin = Math.round(8f * density);
                fixScroll.setLayoutParams(scrollLp);

                LinearLayout fixRow = new LinearLayout(activity);
                fixRow.setOrientation(LinearLayout.HORIZONTAL);
                fixRow.setGravity(Gravity.CENTER_VERTICAL);
                populateAutoFixButtons(activity, fixRow, diagnosis, summaryView, 1);
                fixScroll.addView(fixRow);
                root.addView(fixScroll);
            }

            // Collapsible "Technical Details" (hidden by default for beginner clarity)
            final String safeTrace = LogRedactor.redact(
                    rolledMsg != null ? rolledMsg + "\n\n" + trace : trace);
            final ScrollView traceScroll = new ScrollView(activity);
            LinearLayout.LayoutParams traceLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(140f * density));
            traceLp.topMargin = Math.round(6f * density);
            traceScroll.setLayoutParams(traceLp);
            traceScroll.setVisibility(View.GONE);

            TextView traceText = new TextView(activity);
            traceText.setTypeface(Typeface.MONOSPACE);
            traceText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            traceText.setTextIsSelectable(true);
            traceText.setText(safeTrace);
            traceScroll.addView(traceText);

            TextView detailsToggle = new TextView(activity);
            detailsToggle.setText("▸ Technical Details (Redacted Stacktrace)");
            detailsToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            detailsToggle.setTypeface(Typeface.DEFAULT_BOLD);
            detailsToggle.setPadding(0, Math.round(10f * density), 0, Math.round(4f * density));
            detailsToggle.setOnClickListener(v -> {
                boolean show = traceScroll.getVisibility() != View.VISIBLE;
                traceScroll.setVisibility(show ? View.VISIBLE : View.GONE);
                detailsToggle.setText(show
                        ? "▾ Hide Technical Details"
                        : "▸ Technical Details (Redacted Stacktrace)");
            });
            root.addView(detailsToggle);
            root.addView(traceScroll);

            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(LogRedactor.redact(diagnosis.title))
                    .setView(root)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (exitIfOk) activity.finishAndRemoveTask();
                    })
                    .setNeutralButton(android.R.string.copy, (d, w) -> {
                        String copyPayload = diagnosis.formatCompactSummary() + "\n\n" + safeTrace;
                        if (LogRedactor.copyRedactedToClipboard(
                                activity, "zyrox_error", copyPayload)) {
                            Toast.makeText(activity, R.string.zyrox_log_copied, Toast.LENGTH_SHORT).show();
                        }
                        if (exitIfOk) activity.finishAndRemoveTask();
                    })
                    .setNegativeButton(R.string.zyrox_share_log, (d, w) -> {
                        String safeShare = LogRedactor.redact(
                                diagnosis.formatCompactSummary() + "\n\n" + safeTrace);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_SUBJECT,
                                activity.getString(R.string.zyrox_title_crash_report));
                        share.putExtra(Intent.EXTRA_TEXT, safeShare);
                        activity.startActivity(Intent.createChooser(
                                share, activity.getString(R.string.zyrox_share_log)));
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
                    NotificationUtils.NOTIFICATION_ID_SHOW_ERROR
            );
        }
    }

    public static void installRemoteDialogHandling(Activity target, AlertDialog.Builder builder) {
        if (target instanceof ShowErrorActivity) {
            builder.setOnDismissListener(d -> target.finish());
        }
    }
}
