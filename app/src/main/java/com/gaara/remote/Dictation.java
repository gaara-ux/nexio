package com.gaara.remote;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/** Speech to text with the phone's own recognizer (French by default). */
final class Dictation {
    interface Listener {
        void onListening();
        void onPartial(String text);
        void onResult(String text);
        void onDone(String error); // null when it ended normally
    }

    private final Context ctx;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean active;

    Dictation(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    static boolean available(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    boolean isActive() {
        return active;
    }

    void start() {
        if (active) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Callbacks());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CA");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        active = true;
        recognizer.startListening(i);
    }

    void stop() {
        if (recognizer != null && active) recognizer.stopListening();
    }

    void release() {
        active = false;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private static String first(Bundle b) {
        ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private final class Callbacks implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { listener.onListening(); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onPartialResults(Bundle b) {
            String t = first(b);
            if (!t.isEmpty()) listener.onPartial(t);
        }

        @Override
        public void onResults(Bundle b) {
            active = false;
            String t = first(b);
            if (!t.isEmpty()) listener.onResult(t);
            listener.onDone(null);
        }

        @Override
        public void onError(int error) {
            active = false;
            String msg;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    msg = "Je n'ai rien entendu. Réessaie.";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    msg = "Le micro n'est pas autorisé.";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    msg = "La reconnaissance vocale a besoin d'Internet.";
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    msg = "Le micro est déjà utilisé. Réessaie.";
                    break;
                default:
                    msg = "Dictée impossible (erreur " + error + ").";
            }
            listener.onDone(msg);
        }
    }
}
