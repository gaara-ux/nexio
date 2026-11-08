package com.gaara.remote;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Records the phone's microphone for the TV's voice assistant (16-bit PCM, mono, 8 kHz)
 * and stops by itself after the person stops talking, like a remote's mic button.
 */
final class VoiceCapture {
    interface Sink {
        void audio(byte[] pcm, int length);
        /** Recording ended; reason is null when it ended normally. */
        void done(String reason);
    }

    private static final int CHUNK = 8192;               // 0.5 s of audio
    private static final long MAX_MS = 10000;            // never longer than 10 s
    private static final long NO_SPEECH_MS = 7000;       // nothing said: give up
    private static final long SILENCE_AFTER_MS = 1300;   // pause after speaking: done
    private static final double SPEECH_RMS = 250;        // loudness (after gain) that counts as voice
    private static final int TARGET_PEAK = 16000;        // quiet speech is raised towards this
    private static final float MAX_GAIN = 8f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    boolean isRunning() {
        return running;
    }

    void stop() {
        running = false;
    }

    @SuppressLint("MissingPermission") // checked by the caller
    void start(Sink sink) {
        if (running) return;
        running = true;
        new Thread(() -> {
            AudioRecord rec = null;
            AutomaticGainControl agc = null;
            String error = null;
            try {
                int min = AudioRecord.getMinBufferSize(RemoteClient.VOICE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                // Plain MIC source: on Samsung, VOICE_RECOGNITION comes out very quiet (no gain).
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RemoteClient.VOICE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(rec.getAudioSessionId());
                    if (agc != null) agc.setEnabled(true);
                }
                rec.startRecording();
                long start = System.currentTimeMillis(), lastVoice = 0;
                byte[] buf = new byte[CHUNK];
                while (running) {
                    int n = 0;
                    while (n < CHUNK && running) {
                        int r = rec.read(buf, n, CHUNK - n);
                        if (r <= 0) break;
                        n += r;
                    }
                    if (n <= 0) break;
                    byte[] copy = buf.clone();
                    int peakBefore = peak(copy, n);
                    float gain = boost(copy, n, peakBefore);
                    double level = rms(copy, n);
                    Log.d("Nexio", String.format(java.util.Locale.US,
                            "voix: crete %d, gain x%.1f, niveau %.0f", peakBefore, gain, level));
                    sink.audio(copy, n);

                    long now = System.currentTimeMillis();
                    if (level > SPEECH_RMS) lastVoice = now;
                    if (now - start > MAX_MS) break;
                    if (lastVoice == 0 && now - start > NO_SPEECH_MS) {
                        error = "Je n'ai rien entendu.";
                        break;
                    }
                    if (lastVoice != 0 && now - lastVoice > SILENCE_AFTER_MS) break;
                }
            } catch (Exception e) {
                error = "Le micro du téléphone n'a pas pu démarrer.";
            } finally {
                if (agc != null) agc.release();
                if (rec != null) {
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                }
                running = false;
                String reason = error;
                main.post(() -> sink.done(reason));
            }
        }, "voice-capture").start();
    }

    private static int peak(byte[] b, int n) {
        int p = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = Math.abs((short) ((b[i] & 0xFF) | (b[i + 1] << 8)));
            if (s > p) p = s;
        }
        return p;
    }

    /** Raises quiet audio (in place) so the TV's assistant can hear it. Returns the gain used. */
    private static float boost(byte[] b, int n, int peak) {
        if (peak < 200) return 1f; // background noise only: leave it, don't amplify hiss
        float gain = Math.min(MAX_GAIN, TARGET_PEAK / (float) peak);
        if (gain <= 1.05f) return 1f;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = Math.round((short) ((b[i] & 0xFF) | (b[i + 1] << 8)) * gain);
            s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            b[i] = (byte) s;
            b[i + 1] = (byte) (s >> 8);
        }
        return gain;
    }

    private static double rms(byte[] b, int n) {
        long sum = 0;
        int count = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            sum += (long) s * s;
        }
        return count == 0 ? 0 : Math.sqrt(sum / (double) count);
    }
}
