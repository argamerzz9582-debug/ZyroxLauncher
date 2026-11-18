package git.artdeell.mojo.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import git.artdeell.mojo.game.GameActivity;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ui.ZyroxGifDecoder;
import git.artdeell.mojo.ui.ZyroxLoopingVideoView;
import git.artdeell.mojo.ui.ZyroxSeamlessGifView;
import git.artdeell.mojo.ui.ZyroxShaderBackgroundView;
import git.artdeell.mojo.utils.NotificationUtils;

import java.lang.ref.WeakReference;

public class GameService extends Service {
    private static volatile WeakReference<GameService> sGameService = new WeakReference<>(null);
    private final LocalBinder mLocalBinder = new LocalBinder();

    /** Returns {@code true} while the foreground Minecraft runtime service is running. */
    public static boolean isGameServiceActive() {
        GameService svc = sGameService.get();
        return svc != null && svc.mLocalBinder.isActive;
    }

    /** Purges every background launcher GIF/video/shader media allocation to prevent LMK kills. */
    private static void reclaimLauncherMediaMemory() {
        try {
            ZyroxGifDecoder.cancelAllActiveDecodes();
            ZyroxSeamlessGifView.purgeAllActiveInstances(false);
            ZyroxLoopingVideoView.suspendInactiveDecoders(true);
            ZyroxShaderBackgroundView.releaseAllForGameStart();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        sGameService = new WeakReference<>(this);
        reclaimLauncherMediaMemory();
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.getBooleanExtra("kill", false)) {
            mLocalBinder.isActive = false;
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        // Immediately stop, recycle, and release any launcher background GIF/video/shader decoders
        // so 4 GB–6 GB RAM devices keep maximum free headroom for the JVM & GPU driver.
        reclaimLauncherMediaMemory();
        mLocalBinder.isActive = true;
        Intent killIntent = new Intent(getApplicationContext(), GameService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_GAME_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, GameActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle(getString(R.string.lazy_service_default_title))
                .setContentText(getString(R.string.notification_game_runs))
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,  getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setNotificationSilent();

        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification);
        }
        return START_NOT_STICKY; // non-sticky so android wont try restarting the game after the user uses the "Quit" button
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        reclaimLauncherMediaMemory();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        reclaimLauncherMediaMemory();
    }

    @Override
    public void onDestroy() {
        mLocalBinder.isActive = false;
        sGameService = new WeakReference<>(null);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        //At this point in time  only the game runs and the user poofed the window, time to die
        stopSelf();
        Process.killProcess(Process.myPid());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    public static class LocalBinder extends Binder {
        public boolean isActive;
    }
}
