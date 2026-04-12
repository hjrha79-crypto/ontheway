package com.vita.ontheway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceManager(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onReady: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    var continuous = false
    var isSpeaking = false

    fun start() {
        if (isSpeaking) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(listener)
            recognizer?.startListening(makeIntent())
        } catch (e: Exception) {
            if (continuous) handler.postDelayed({ start() }, 1000)
        }
    }

    fun stop() {
        try { recognizer?.stopListening(); recognizer?.cancel(); recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
    }

    private fun beep() {
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (e: Exception) {}
    }

    private fun makeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {
            handler.postDelayed({
                beep()
                onReady()
            }, 300)
        }
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) handler.post { onPartial(t) }
        }

        override fun onResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) handler.post { onResult(t) }
            if (continuous && !isSpeaking) handler.postDelayed({ start() }, 500)
        }

        override fun onError(error: Int) {
            if (continuous && !isSpeaking) handler.postDelayed({ start() }, 500)
        }
    }
}



