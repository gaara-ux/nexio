package com.gaara.remote;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

/**
 * Remote-control connection to a paired Android / Google TV on port 6466.
 * Keeps one TLS connection open, answers the TV's pings, and sends keys.
 * Listener callbacks arrive on the main thread.
 */
final class RemoteClient {
    static final int PORT = 6466;

    // Feature bits exchanged in remote_configure / remote_set_active.
    private static final int F_PING = 1, F_KEY = 2, F_IME = 4, F_VOICE = 8, F_POWER = 32, F_VOLUME = 64, F_APP_LINK = 512;
    private static final int WANTED = F_PING | F_KEY | F_IME | F_VOICE | F_POWER | F_VOLUME | F_APP_LINK;

    // Voice: 16-bit PCM, mono, 8 kHz. The TV wants 8 to 20 KB per message.
    static final int VOICE_RATE = 8000;
    private static final int VOICE_MIN = 8 * 1024, VOICE_MAX = 20 * 1024;

    // RemoteDirection
    static final int SHORT = 3, START_LONG = 1, END_LONG = 2;

    interface Listener {
        void onConnected(String vendor, String model);
        void onPower(boolean on);
        void onApp(String packageName);
        void onVolume(int level, int max, boolean muted);
        /**
         * A text field got focus on the TV: value is what it already holds, key identifies the field.
         * Called with (null, 0) when the TV reports focus without a text field.
         */
        void onTextField(String value, int key);
        /** The TV opened its voice assistant and waits for audio. */
        void onVoiceBegin(int session);
        void onDisconnected(String reason);
    }

    // IME counters: the TV numbers its input sessions and fields; edits must quote them.
    private volatile int imeCounter, fieldCounter;
    private volatile boolean hasImeCounter, hasFieldCounter;

    private final Identity identity;
    private final String host;
    private final String pinnedFingerprint;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile boolean closed;
    private volatile int activeFeatures = WANTED;

    RemoteClient(Identity identity, String host, String pinnedFingerprint, Listener listener) {
        this.identity = identity;
        this.host = host;
        this.pinnedFingerprint = pinnedFingerprint;
        this.listener = listener;
    }

    void connect() {
        Thread t = new Thread(this::run, "remote-reader");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        closed = true;
        out = null;
        writer.shutdownNow();
        // Closing TLS sends a last message: never on the main thread (Android forbids it).
        SSLSocket s = socket;
        if (s != null) new Thread(() -> {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }, "remote-close").start();
    }

    boolean isOpen() {
        return out != null && !closed;
    }

    /** A short press of an Android key code (KeyEvent.KEYCODE_*). */
    void key(int keyCode) {
        key(keyCode, SHORT);
    }

    void key(int keyCode, int direction) {
        send(new Proto.Writer().message(10, new Proto.Writer()
                .varint(1, keyCode)
                .varint(2, direction)));
    }

    /**
     * Inserts text at the cursor of the focused TV text field (the TV appends it;
     * it does not replace the field). Deleting is done with KEYCODE_DEL.
     */
    void insertText(String text) {
        int n = text.length();
        send(new Proto.Writer().message(21, new Proto.Writer()
                .varint(1, imeCounter)
                .varint(2, fieldCounter)
                .message(3, new Proto.Writer()
                        .varint(1, 1)
                        .message(2, new Proto.Writer()
                                .varint(1, n)
                                .varint(2, n)
                                .string(3, text)))));
    }

    /** Asks the TV for its voice assistant; it answers with onVoiceBegin. */
    boolean voiceSupported() {
        return (activeFeatures & F_VOICE) != 0;
    }

    void voiceStart() {
        key(KeyEvent.KEYCODE_SEARCH);
    }

    /** Sends recorded PCM for the session, split and padded the way the TV accepts it. */
    void voiceAudio(int session, byte[] pcm, int length) {
        for (int off = 0; off < length; off += VOICE_MAX) {
            int n = Math.min(VOICE_MAX, length - off);
            byte[] chunk = new byte[Math.max(n, VOICE_MIN)]; // short tail padded with silence
            System.arraycopy(pcm, off, chunk, 0, n);
            send(new Proto.Writer().message(31, new Proto.Writer().varint(1, session).bytes(2, chunk)));
        }
    }

    void voiceEnd(int session) {
        send(new Proto.Writer().message(32, new Proto.Writer().varint(1, session)));
    }

    /** Opens an app link on the TV (a URL or market://launch?id=package). */
    void openLink(String link) {
        send(new Proto.Writer().message(90, new Proto.Writer().string(1, link)));
    }

    private void send(Proto.Writer msg) {
        if (closed) return;
        writer.execute(() -> {
            OutputStream o = out;
            if (o == null) return;
            try {
                Proto.send(o, msg);
            } catch (IOException e) {
                fail("envoi impossible : " + e.getMessage());
            }
        });
    }

    private void run() {
        try {
            SSLSocket s = (SSLSocket) identity.sslContext().getSocketFactory().createSocket();
            socket = s;
            s.connect(new InetSocketAddress(host, PORT), 5000);
            s.startHandshake();
            X509Certificate cert = (X509Certificate) s.getSession().getPeerCertificates()[0];
            if (pinnedFingerprint != null && !pinnedFingerprint.equals(Identity.fingerprint(cert))) {
                throw new IOException("Ce n'est plus la même TV (certificat changé). Refais l'appairage.");
            }
            // The TV pings every 5 s; 20 s of silence means the link is dead.
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            out = s.getOutputStream();
            while (!closed) handle(Proto.receive(in));
        } catch (Exception e) {
            if (!closed) android.util.Log.w("Nexio", "remote " + host, e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("certificate_unknown")) {
                msg = "La TV ne reconnaît plus ce téléphone. Refais l'appairage.";
            }
            fail(msg == null ? e.getClass().getSimpleName() : msg);
        }
    }

    private void handle(Proto.Message m) {
        if (m.has(1)) { // remote_configure
            Proto.Message cfg = m.message(1);
            Proto.Message info = cfg.message(2);
            activeFeatures = WANTED & (int) cfg.varint(1, 0);
            send(new Proto.Writer().message(1, new Proto.Writer()
                    .varint(1, activeFeatures)
                    .message(2, new Proto.Writer()
                            .varint(3, 1)
                            .string(4, "1")
                            .string(5, "atvremote")
                            .string(6, "1.0.0"))));
            String vendor = info.string(2), model = info.string(1);
            main.post(() -> listener.onConnected(vendor, model));
        } else if (m.has(2)) { // remote_set_active
            send(new Proto.Writer().message(2, new Proto.Writer().varint(1, activeFeatures)));
        } else if (m.has(8)) { // remote_ping_request
            long v = m.message(8).varint(1, 0);
            send(new Proto.Writer().message(9, new Proto.Writer().varint(1, v)));
        } else if (m.has(40)) { // remote_start
            boolean on = m.message(40).varint(1, 0) != 0;
            main.post(() -> listener.onPower(on));
        } else if (m.has(20)) { // remote_ime_key_inject: app in front + its input counters
            Proto.Message inject = m.message(20);
            Proto.Message appInfo = inject.message(1);
            String pkg = appInfo.string(12);
            imeCounter = (int) appInfo.varint(1, 0);
            fieldCounter = (int) inject.message(2).varint(1, 0);
            hasImeCounter = imeCounter != 0;
            hasFieldCounter = fieldCounter != 0;
            if (!pkg.isEmpty()) main.post(() -> listener.onApp(pkg));
            // A focused text field comes with its label (e.g. "Recherchez") and an input counter.
            if (imeCounter != 0 && (appInfo.has(10) || inject.has(2))) {
                String value = inject.message(2).string(2);
                int key = (pkg + "|" + appInfo.string(10)).hashCode() | 1; // same field = same key
                main.post(() -> listener.onTextField(value, key));
            } else {
                main.post(() -> listener.onTextField(null, 0)); // no text field in focus
            }
        } else if (m.has(22)) { // remote_ime_show_request: a text field is open on the TV
            Proto.Message status = m.message(22).message(2);
            if (status.has(1)) {
                fieldCounter = (int) status.varint(1, 0);
                hasFieldCounter = true;
            }
            String value = status.string(2);
            int key = (status.string(6) + "|show").hashCode() | 1;
            main.post(() -> listener.onTextField(value, key));
        } else if (m.has(30)) { // remote_voice_begin: the assistant is listening
            int session = (int) m.message(30).varint(1, 0);
            send(new Proto.Writer().message(30, new Proto.Writer().varint(1, session)));
            main.post(() -> listener.onVoiceBegin(session));
        } else if (m.has(21)) { // remote_ime_batch_edit from the TV: counters fallback
            Proto.Message edit = m.message(21);
            if (!hasImeCounter) imeCounter = (int) edit.varint(1, 0);
            if (!hasFieldCounter) fieldCounter = (int) edit.varint(2, 0);
        } else if (m.has(50)) { // remote_set_volume_level
            Proto.Message v = m.message(50);
            int level = (int) v.varint(7, 0), max = (int) v.varint(6, 0);
            boolean muted = v.varint(8, 0) != 0;
            main.post(() -> listener.onVolume(level, max, muted));
        }
    }

    private void fail(String reason) {
        boolean wasClosed = closed;
        closed = true;
        out = null;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        writer.shutdownNow();
        if (!wasClosed) main.post(() -> listener.onDisconnected(reason));
    }
}
