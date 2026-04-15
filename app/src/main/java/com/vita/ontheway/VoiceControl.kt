package com.vita.ontheway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** v3.5 음성 제어: 잡아/넘겨/오늘얼마/기준올려/기준내려 */
object VoiceControl {

    private const val TAG = "VoiceControl"
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences("advanced_prefs", Context.MODE_PRIVATE).getBoolean("voice_control", false)
    fun setEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("advanced_prefs", Context.MODE_PRIVATE).edit().putBoolean("voice_control", v).apply()

    fun start(ctx: Context) {
        if (!isEnabled(ctx)) return
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Log.w(TAG, "음성 인식 사용 불가")
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val command = texts?.firstOrNull()?.lowercase() ?: ""
                Log.d(TAG, "음성 명령: $command")
                processCommand(ctx, command)
                // 연속 리스닝
                if (isEnabled(ctx)) startListening(ctx)
            }
            override fun onError(error: Int) {
                Log.d(TAG, "음성 인식 에러: $error")
                if (isEnabled(ctx) && isListening) startListening(ctx)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        isListening = true
        startListening(ctx)
    }

    private fun startListening(ctx: Context) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "리스닝 시작 실패: ${e.message}")
        }
    }

    private fun processCommand(ctx: Context, command: String) {
        val service = OnTheWayService.instance ?: return
        val fmt = java.text.NumberFormat.getNumberInstance()

        when {
            command.contains("잡아") || command.contains("수락") -> {
                service.acceptCurrentCall()
                Log.d(TAG, "음성 수락")
            }
            command.contains("넘겨") || command.contains("넘기") -> {
                Log.d(TAG, "음성 넘김 확인")
            }
            command.contains("오늘") && (command.contains("얼마") || command.contains("매출") || command.contains("수익")) -> {
                val earnings = EarningsTracker.getToday(ctx)
                val hourly = if (earnings.hourlyRate > 0) "${fmt.format(earnings.hourlyRate)}원" else "측정 중"
                service.speakTtsPublic("오늘 ${earnings.acceptedCount}건, 매출 ${fmt.format(earnings.totalRevenue)}원, 시급 $hourly")
            }
            command.contains("기준") && (command.contains("올려") || command.contains("높여")) -> {
                val cur = CallFilter.getMinPrice(ctx)
                CallFilter.setMinPrice(ctx, cur + 500)
                service.speakTtsPublic("최소 금액 ${fmt.format(cur + 500)}원으로 올렸습니다")
            }
            command.contains("기준") && (command.contains("내려") || command.contains("낮춰")) -> {
                val cur = CallFilter.getMinPrice(ctx)
                val newVal = (cur - 500).coerceAtLeast(1000)
                CallFilter.setMinPrice(ctx, newVal)
                service.speakTtsPublic("최소 금액 ${fmt.format(newVal)}원으로 내렸습니다")
            }
        }
    }

    fun stop() {
        isListening = false
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        } catch (e: Exception) { /* ignore */ }
    }
}
