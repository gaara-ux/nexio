package com.gaara.remote.share;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** All transfers of this session (sending and receiving), observed by the Transfers tab. */
public final class Transfers {
    public enum State { RUNNING, DONE, FAILED, CANCELED }

    public static final class Transfer {
        private static int nextId = 1;

        public final int id = nextId++;
        public final boolean sending;
        public volatile String peer;
        public volatile String current = "";
        public volatile int count, done;
        public volatile long total, bytes;
        public volatile State state = State.RUNNING;
        public volatile String error;
        public volatile String folder;      // where received files went
        public volatile boolean cancel;
        final long started = SystemClock.elapsedRealtime();

        Transfer(boolean sending, String peer) {
            this.sending = sending;
            this.peer = peer;
        }

        public int percent() {
            return total <= 0 ? (state == State.DONE ? 100 : 0) : (int) Math.min(100, bytes * 100 / total);
        }

        /** Bytes per second so far. */
        public long speed() {
            long ms = SystemClock.elapsedRealtime() - started;
            return ms <= 0 ? 0 : bytes * 1000 / ms;
        }

        public void cancel() {
            cancel = true;
        }
    }

    public interface Listener {
        void onTransfersChanged();
    }

    private static final List<Transfer> list = new CopyOnWriteArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean posted;
    private static long lastPost;

    private Transfers() {}

    public static Transfer start(boolean sending, String peer) {
        Transfer t = new Transfer(sending, peer);
        list.add(0, t);
        changed(true);
        return t;
    }

    /** Newest first. */
    public static List<Transfer> all() {
        return new ArrayList<>(list);
    }

    public static void clearFinished() {
        for (Transfer t : list) if (t.state != State.RUNNING) list.remove(t);
        changed(true);
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Progress updates are limited to about 8 per second; state changes go at once. */
    public static void changed(boolean now) {
        synchronized (Transfers.class) {
            long t = SystemClock.elapsedRealtime();
            if (!now && (posted || t - lastPost < 120)) return;
            posted = true;
            lastPost = t;
        }
        main.post(() -> {
            synchronized (Transfers.class) {
                posted = false;
            }
            for (Listener l : listeners) l.onTransfersChanged();
        });
    }
}
