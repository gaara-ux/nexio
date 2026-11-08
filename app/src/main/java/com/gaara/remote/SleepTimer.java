package com.gaara.remote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Sleep timer: puts the TV to sleep after N minutes, even when the app is closed. It sends SLEEP
 * (never POWER), so a TV that is already off stays off.
 */
final class SleepTimer {
    private SleepTimer() {}

    private static PendingIntent intent(Context c, String fp) {
        Intent i = new Intent(c, Fire.class).putExtra("fp", fp);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void set(Context c, Devices.Device d, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = SystemClock.elapsedRealtime() + minutes * 60_000L;
        PendingIntent pi = intent(c, d.fingerprint);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        else
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi); // may be a few minutes late
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                .putLong("sleep_at", System.currentTimeMillis() + minutes * 60_000L)
                .putString("sleep_fp", d.fingerprint).apply();
    }

    static void cancel(Context c) {
        String fp = c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("sleep_fp", "");
        c.getSystemService(AlarmManager.class).cancel(intent(c, fp));
        clear(c);
    }

    private static void clear(Context c) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().remove("sleep_at").remove("sleep_fp").apply();
    }

    /** Minutes left (rounded up) for this TV, or 0 when no timer runs. */
    static int minutesLeft(Context c, String fp) {
        android.content.SharedPreferences p = c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
        if (!fp.equals(p.getString("sleep_fp", ""))) return 0;
        long left = p.getLong("sleep_at", 0) - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** The alarm: connects to the TV, sends SLEEP, disconnects. */
    public static final class Fire extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Context c = ctx.getApplicationContext();
            clear(c);
            Devices.Device d = Devices.find(c, intent.getStringExtra("fp"));
            if (d == null) return;
            PendingResult done = goAsync();
            new Thread(() -> {
                Object lock = new Object();
                RemoteClient[] holder = new RemoteClient[1];
                try {
                    RemoteClient client = new RemoteClient(Identity.get(c), d.host, d.fingerprint, new RemoteClient.Listener() {
                        @Override public void onConnected(String vendor, String model) {
                            holder[0].key(KeyEvent.KEYCODE_SLEEP);
                            synchronized (lock) { lock.notifyAll(); }
                        }
                        @Override public void onPower(boolean on) {}
                        @Override public void onApp(String packageName) {}
                        @Override public void onVolume(int level, int max, boolean muted) {}
                        @Override public void onTextField(String value, int key) {}
                        @Override public void onVoiceBegin(int session) {}
                        @Override public void onDisconnected(String reason) {
                            synchronized (lock) { lock.notifyAll(); }
                        }
                    });
                    holder[0] = client;
                    synchronized (lock) {
                        client.connect();
                        lock.wait(8000);
                    }
                    Thread.sleep(800); // let the key leave before closing
                    client.close();
                } catch (Exception ignored) {
                } finally {
                    done.finish();
                }
            }).start();
        }
    }
}
