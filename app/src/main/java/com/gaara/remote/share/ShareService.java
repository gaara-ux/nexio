package com.gaara.remote.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.gaara.remote.R;

/**
 * Keeps file sharing running in the background on the TV, so phones and the PC can
 * browse and send files at any time. Started at boot and when the TV screen opens.
 */
public class ShareService extends Service {
    private static final String CHANNEL = "share";

    public static void start(Context c) {
        Intent i = new Intent(c, ShareService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Partage de fichiers",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_transfer)
                .setContentTitle("Partage de fichiers actif")
                .setContentText("Les appareils appairés peuvent voir et envoyer des fichiers.")
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        ShareHub.get(this).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ShareHub.get(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Starts sharing when the TV boots (TVs only: on phones it runs while the app is open). */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Share.isTv(c)) start(c);
        }
    }
}
