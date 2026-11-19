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
    /**
     * Tracks foreground service lifetime independently of {@link LocalBinder#isActive}.
     * IMPORTANT: {@code mLocalBinder.isActive} MUST remain {@code false} until
     * {@link GameActivity#onServiceConnected} passes it into {@code GameView.start(isActive)},
     * otherwise {@code GameView} treats a fresh launch as a surface-only refresh and never boots the JVM.
     */
    private volatile boolean mServiceRunning = false;

    /** Returns {@code true} while the foreground Minecraft runtime service is running. */
    public static boolean isGameServiceActive() {
        GameService svc = sGameService.get();
        return svc != null && (svc.mServiceRunning || svc.mLocalBinder.isActive);
    }

    /**
     * Releases all background GIF decode workers, recycled bitmap frames, looping video decoders,
     * and shader backdrop surfaces so zero launcher media memory remains pinned when the JVM runs.
     */
    public static void reclaimLauncherMediaMemory(boolean releaseAllGifBitmaps) {
        try {
            ZyroxGifDecoder.cancelAllActiveDecodes();
            ZyroxLoopingVideoView.suspendLauncherBackdrops();
            ZyroxShaderBackgroundView.releaseAllForGameStart();
            if (releaseAllGifBitmaps) {
                ZyroxSeamlessGifView.purgeAllActiveInstances(true);
                ZyroxLoopingVideoView.suspendInactiveDecoders(true);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        sGameService = new WeakReference<>(this);
        reclaimLauncherMediaMemory(false);
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.getBooleanExtra("kill", false)) {
            mServiceRunning = false;
            mLocalBinder.isActive = false;
            reclaimLauncherMediaMemory(true);
            git.artdeell.mojo.servers.ZyroxRoomBridge.stopAll();
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        // Release any leftover launcher-shell background media so JVM & GPU keep full headroom.
        // Do NOT set mLocalBinder.isActive here — GameActivity.onServiceConnected owns that handshake!
        reclaimLauncherMediaMemory(false);
        mServiceRunning = true;
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
        boolean critical = level == TRIM_MEMORY_RUNNING_LOW
                || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND;
        reclaimLauncherMediaMemory(critical);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        reclaimLauncherMediaMemory(true);
    }

    @Override
    public void onDestroy() {
        mServiceRunning = false;
        mLocalBinder.isActive = false;
        sGameService = new WeakReference<>(null);
        reclaimLauncherMediaMemory(true);
        git.artdeell.mojo.servers.ZyroxRoomBridge.stopAll();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        //At this point in time only the game runs and the user closed the task; release sockets & bitmaps
        reclaimLauncherMediaMemory(true);
        git.artdeell.mojo.servers.ZyroxRoomBridge.stopAll();
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
