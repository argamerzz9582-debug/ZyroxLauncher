package git.artdeell.mojo.utils.lifecycle;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import git.artdeell.mojo.ExitActivity;
import git.artdeell.mojo.FatalErrorActivity;
import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.ShowErrorActivity;
import git.artdeell.mojo.TestStorageActivity;
import git.artdeell.mojo.awt.AWTActivity;
import git.artdeell.mojo.game.GameActivity;

/**
 * Single-task Recents guard preventing duplicate or empty ghost "Zyrox Launcher" task cards
 * across stock Android and OEM shells (ColorOS, Realme UI, OxygenOS, OneUI, HyperOS).
 *
 * <p>Uses only permission-free {@link ActivityManager#getAppTasks()} scoped strictly to this app.
 */
public final class AppTaskGuard {

    private static final String CLS_TRAMPOLINE = TestStorageActivity.class.getName();
    private static final String CLS_LAUNCHER = LauncherActivity.class.getName();
    private static final String CLS_GAME = GameActivity.class.getName();
    private static final String CLS_AWT = AWTActivity.class.getName();
    private static final String CLS_EXIT = ExitActivity.class.getName();
    private static final String CLS_FATAL = FatalErrorActivity.class.getName();
    private static final String CLS_SHOW_ERR = ShowErrorActivity.class.getName();

    private AppTaskGuard() {}

    /**
     * Invoked at the start of {@link TestStorageActivity#onCreate}:
     * <ol>
     *   <li>Eliminates duplicate {@code MAIN/LAUNCHER} re-entry when launched atop an existing stack
     *       (e.g. after tapping "Open" in the Android Package Installer).</li>
     *   <li>Removes any empty/orphaned Recents cards left behind by prior sessions.</li>
     *   <li>If a separate live task is already running {@link GameActivity}, {@link AWTActivity},
     *       or {@link LauncherActivity} (e.g. forced via OEM Game Space), brings that single task
     *       straight to the foreground and removes the duplicate trampoline task immediately.</li>
     * </ol>
     *
     * @return {@code true} if an existing task was resumed and the caller has already finished.
     */
    public static boolean resumeExistingAppTask(@NonNull Activity trampoline) {
        Intent launchIntent = trampoline.getIntent();
        if (!trampoline.isTaskRoot()
                && launchIntent != null
                && Intent.ACTION_MAIN.equals(launchIntent.getAction())
                && launchIntent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            trampoline.finish();
            trampoline.overridePendingTransition(0, 0);
            return true;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        try {
            ActivityManager am = (ActivityManager) trampoline.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null || tasks.isEmpty()) return false;

            int myTaskId = trampoline.getTaskId();
            ActivityManager.AppTask liveGameOrToolTask = null;
            ActivityManager.AppTask liveLauncherTask = null;

            for (ActivityManager.AppTask task : tasks) {
                if (task == null) continue;
                ActivityManager.RecentTaskInfo info;
                try {
                    info = task.getTaskInfo();
                } catch (Throwable ignored) {
                    continue;
                }
                if (info == null) continue;
                int otherId = extractTaskId(info);
                if (otherId == myTaskId) continue;

                String baseCls = classNameOf(info.baseActivity);
                String topCls = classNameOf(info.topActivity);
                int count = info.numActivities;

                if (count <= 0 || topCls == null || CLS_TRAMPOLINE.equals(topCls)) {
                    safeFinishTask(task);
                    continue;
                }
                if (CLS_GAME.equals(topCls) || CLS_GAME.equals(baseCls)
                        || CLS_AWT.equals(topCls) || CLS_AWT.equals(baseCls)) {
                    liveGameOrToolTask = task;
                } else if (CLS_LAUNCHER.equals(baseCls) || CLS_LAUNCHER.equals(topCls)) {
                    if (liveLauncherTask == null) {
                        liveLauncherTask = task;
                    } else {
                        safeFinishTask(task);
                    }
                }
            }

            ActivityManager.AppTask target = liveGameOrToolTask != null ? liveGameOrToolTask : liveLauncherTask;
            if (target != null) {
                target.moveToFront();
                trampoline.finishAndRemoveTask();
                trampoline.overridePendingTransition(0, 0);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Removes any empty, stale, or duplicate Recents task cards belonging to this app while keeping
     * {@code activeActivity}'s own task untouched.
     */
    public static void pruneDuplicateTasks(@NonNull Activity activeActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) activeActivity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null || tasks.size() <= 1) return;

            int keepTaskId = activeActivity.getTaskId();
            String activeClass = activeActivity.getClass().getName();

            for (ActivityManager.AppTask task : tasks) {
                if (task == null) continue;
                ActivityManager.RecentTaskInfo info;
                try {
                    info = task.getTaskInfo();
                } catch (Throwable ignored) {
                    continue;
                }
                if (info == null) continue;
                int otherTaskId = extractTaskId(info);
                if (otherTaskId == keepTaskId) continue;

                if (shouldPruneOtherTask(
                        activeClass,
                        info.numActivities,
                        classNameOf(info.baseActivity),
                        classNameOf(info.topActivity))) {
                    safeFinishTask(task);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Pure decision helper (unit-tested) determining whether another app task should be removed
     * from Android Recents when {@code activeClassName} becomes active.
     */
    public static boolean shouldPruneOtherTask(@NonNull String activeClassName,
                                               int otherNumActivities,
                                               @Nullable String otherBaseClass,
                                               @Nullable String otherTopClass) {
        // 1. Dead / empty black ghost card in Recents (0 live activities or no top activity)
        if (otherNumActivities <= 0 || otherTopClass == null) {
            return true;
        }
        // 2. Orphan trampoline or transient error dialog task
        if (CLS_TRAMPOLINE.equals(otherTopClass)
                || (CLS_TRAMPOLINE.equals(otherBaseClass) && otherNumActivities == 1)
                || CLS_SHOW_ERR.equals(otherBaseClass)) {
            return true;
        }
        // 3. GameActivity became active -> remove any separate Launcher/Exit/Fatal/duplicate Game card
        if (CLS_GAME.equals(activeClassName)) {
            return CLS_LAUNCHER.equals(otherBaseClass)
                    || CLS_LAUNCHER.equals(otherTopClass)
                    || CLS_TRAMPOLINE.equals(otherBaseClass)
                    || CLS_EXIT.equals(otherBaseClass)
                    || CLS_FATAL.equals(otherBaseClass)
                    || CLS_GAME.equals(otherBaseClass);
        }
        // 4. LauncherActivity became active -> remove duplicate Launcher/Exit/Fatal cards,
        //    but never kill an actively running GameActivity or AWTActivity task.
        if (CLS_LAUNCHER.equals(activeClassName)) {
            if (CLS_GAME.equals(otherTopClass) || CLS_GAME.equals(otherBaseClass)
                    || CLS_AWT.equals(otherTopClass) || CLS_AWT.equals(otherBaseClass)) {
                return false;
            }
            return CLS_LAUNCHER.equals(otherBaseClass)
                    || CLS_LAUNCHER.equals(otherTopClass)
                    || CLS_TRAMPOLINE.equals(otherBaseClass)
                    || CLS_EXIT.equals(otherBaseClass)
                    || CLS_FATAL.equals(otherBaseClass);
        }
        // 5. ExitActivity or FatalErrorActivity became active -> clean up stale duplicate cards
        if (CLS_EXIT.equals(activeClassName) || CLS_FATAL.equals(activeClassName)) {
            return CLS_TRAMPOLINE.equals(otherBaseClass)
                    || CLS_EXIT.equals(otherBaseClass)
                    || CLS_FATAL.equals(otherBaseClass)
                    || CLS_GAME.equals(otherBaseClass);
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static int extractTaskId(@NonNull ActivityManager.RecentTaskInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.taskId;
        }
        return info.id;
    }

    @Nullable
    private static String classNameOf(@Nullable ComponentName cn) {
        return cn != null ? cn.getClassName() : null;
    }

    private static void safeFinishTask(@NonNull ActivityManager.AppTask task) {
        try {
            task.finishAndRemoveTask();
        } catch (Throwable ignored) {
        }
    }
}
