package com.vita.ontheway

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * 출력 단일 진입점.
 * TTS = 인지, Overlay = 확인 (역할 분리 병행)
 *
 * OutputMode:
 * - FULL = TTS + Overlay
 * - OVERLAY_ONLY = Overlay만
 * - SILENT = 아무것도 안 함
 */
object OutputController {

    private const val TAG = "OutputController"
    private const val COOLDOWN_MS = 500L

    private var lastEmitTime = 0L

    /**
     * 단일 출력 진입점.
     * @param ctx Context
     * @param ttsText TTS 발화 텍스트 (FULL 모드에서 사용)
     * @param overlayText Overlay 표시 텍스트 (FULL/OVERLAY_ONLY에서 사용)
     * @param mode 출력 모드
     * @param tts TextToSpeech 인스턴스 (null이면 TTS 스킵)
     * @param ttsReady TTS 준비 상태
     */
    fun emit(
        ctx: Context,
        ttsText: String?,
        overlayText: String,
        mode: OutputMode,
        tts: TextToSpeech? = null,
        ttsReady: Boolean = false
    ) {
        if (mode == OutputMode.SILENT) return

        val now = System.currentTimeMillis()
        // 동시 출력 방지 (500ms 쿨다운)
        if (now - lastEmitTime < COOLDOWN_MS) {
            OtwFileLogger.log(TAG, "쿨다운 스킵: $overlayText")
            return
        }
        lastEmitTime = now

        // Overlay (FULL + OVERLAY_ONLY)
        try {
            TopBarOverlay.show(ctx, overlayText)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay 실패: ${e.message}")
        }

        // TTS (FULL만)
        if (mode == OutputMode.FULL && ttsText != null && tts != null && ttsReady) {
            try {
                tts.speak(ttsText, TextToSpeech.QUEUE_ADD, null, "oc_$now")
            } catch (e: Exception) {
                Log.w(TAG, "TTS 실패: ${e.message}")
            }
        }

        OtwFileLogger.log(TAG, "emit: mode=$mode, overlay=\"$overlayText\"" +
            if (mode == OutputMode.FULL) ", tts=\"$ttsText\"" else "")
    }

    /**
     * Confidence 기반 OutputMode 결정.
     * - 거리 데이터 있음 + 단가 계산 가능 → FULL
     * - 거리 null → OVERLAY_ONLY
     */
    fun determineMode(call: DeliveryCall): OutputMode {
        val hasDist = call.distance != null && call.distance > 0
        return if (hasDist) OutputMode.FULL else OutputMode.OVERLAY_ONLY
    }
}
