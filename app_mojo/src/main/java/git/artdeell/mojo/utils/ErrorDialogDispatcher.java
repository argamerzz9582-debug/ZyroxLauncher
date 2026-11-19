package git.artdeell.mojo.utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.io.PrintWriter;
import java.io.StringWriter;

import git.artdeell.mojo.R;
import git.artdeell.mojo.ShowErrorActivity;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;
import git.artdeell.mojo.utils.lifecycle.ContextExecutorTask;
import git.artdeell.mojo.game.GameActivity;
import git.artdeell.mojo.security.LogRedactor;

/**
 * Single-responsibility error formatting, redacted dialog presentation, remote error routing, and
 * external URL dispatch helper extracted from {@link Tools}.
 */
public final class ErrorDialogDispatcher {

    private ErrorDialogDispatcher() {
    }

    @NonNull
    public static String printStackTrace(@NonNull Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.close();
        return stringWriter.toString();
    }

    public static void showError(@NonNull final Context ctx,
                                 final int titleId,
                                 @Nullable final String rolledMessage,
                                 @NonNull final Throwable e,
                                 final boolean exitIfOk,
                                 final boolean showMore) {
        if (e instanceof ContextExecutorTask) {
            ContextExecutor.execute((ContextExecutorTask) e);
            return;
        }

        Runnable runnable = () -> {
            final String rawMsg = showMore
                    ? printStackTrace(e)
                    : (rolledMessage != null ? rolledMessage : e.getMessage());
            final String errMsg = LogRedactor.redact(rawMsg);
            new AlertDialog.Builder(ctx)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> finishIfRequested(ctx, exitIfOk))
                    .setNegativeButton(
                            showMore ? R.string.error_show_less : R.string.error_show_more,
                            (p1, p2) -> showError(ctx, titleId, rolledMessage, e, exitIfOk, !showMore))
                    .setNeutralButton(android.R.string.copy, (p1, p2) -> {
                        LogRedactor.copyRedactedToClipboard(ctx, "error", printStackTrace(e));
                        finishIfRequested(ctx, exitIfOk);
                    })
                    .setCancelable(!exitIfOk)
                    .show();
        };

        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    public static void showErrorRemote(@Nullable String rolledMessage, @NonNull Throwable e) {
        ContextExecutor.execute(new ShowErrorActivity.RemoteErrorTask(e, rolledMessage));
    }

    public static void showDialog(@NonNull Context context,
                                  @NonNull CharSequence title,
                                  @NonNull CharSequence message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static void showDialog(@NonNull Context context, int title, int message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static void showForceCloseDialog(@NonNull Context ctx) {
        new AlertDialog.Builder(ctx)
                .setMessage(R.string.mcn_exit_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                    try {
                        Tools.fullyExit();
                    } catch (Throwable th) {
                        android.util.Log.w(Tools.APP_NAME, "Could not enable System.exit() method!", th);
                    }
                })
                .show();
    }

    public static void openUrl(@NonNull Activity activity, @NonNull String url) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            showError(activity, R.string.global_error, null, e, false, false);
        }
    }

    private static void finishIfRequested(@NonNull Context ctx, boolean exitIfOk) {
        if (!exitIfOk) return;
        if (ctx instanceof GameActivity) {
            Tools.fullyExit();
        } else if (ctx instanceof ShowErrorActivity) {
            ((Activity) ctx).finishAndRemoveTask();
        } else if (ctx instanceof Activity) {
            ((Activity) ctx).finish();
        }
    }
}
