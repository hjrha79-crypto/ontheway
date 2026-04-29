package com.vita.ontheway

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.text.NumberFormat

/**
 * 출력 단일 진입점 — 계기판 철학.
 *
 * 핵심 원칙:
 * - 판단 금지, 근거만 출력
 * - 숫자 없는 메시지 = 출력 금지
 * - TTS 2초 이내 (20자), 30초 쿨다운
 */
object OutputController {

    private const val TAG = "OutputController"
    private const val TTS_COOLDOWN_MS = 30_000L
    private const val TTS_MAX_LENGTH = 20
    private const val OVERLAY_COOLDOWN_MS = 2000L

    private var lastTtsTime = 0L
    private var lastOverlayTime = 0L
    private var lastOverlayText = ""

    private val nf = NumberFormat.getNumberInstance()

    /** 판단 금지어 — 이 단어가 포함된 메시지는 출력 차단 */
    val FORBIDDEN_WORDS = listOf(
        "잡으세요", "넘기세요", "괜찮습니다",
        "추천", "권장", "좋은 콜"
    )

    /**
     * 콜 데이터로부터 근거 메시지 생성.
     * 판단어 없이 숫자 중심 메시지만 반환.
     * 근거 없으면 null (SILENT).
     */
    fun buildMessage(call: DeliveryCall, result: CallFilter.FilterResult): String? {
        val price = call.price
        val dist = call.distance
        val unitPrice = if (dist != null && dist > 0) (price / dist).toInt() else 0
        val isMulti = call.isMulti
        val bundleCount = call.bundleCount.coerceAtLeast(if (isMulti) 2 else 1)
        val perItem = if (isMulti && bundleCount > 1) price / bundleCount else 0
        val pickupKm = call.pickupDistanceKm

        val msg = when {
            // 묶음
            isMulti && perItem > 0 -> "건당 ${nf.format(perItem)}원"
            // 단가 계산 가능
            unitPrice > 0 -> "단가 ${nf.format(unitPrice)}원"
            // 고액 (단가 없지만 금액 표시)
            price >= 7000 -> "${nf.format(price)}원"
            // 동선 OK (픽업 가까울 때)
            pickupKm != null && pickupKm <= 1.5 && dist != null && dist <= 3.0 -> "동선 OK"
            // 근거 없음
            else -> return null
        }

        return validateMessage(msg)
    }

    /**
     * 상태 알림용 emit (30초 쿨다운 무시).
     */
    fun emitStatusAlert(
        ctx: Context,
        ttsText: String,
        overlayText: String,
        tts: TextToSpeech? = null,
        ttsReady: Boolean = false
    ) {
        try { CardOverlay.show(ctx, overlayText) } catch (_: Exception) {}
        if (tts != null && ttsReady) {
            try { tts.speak(ttsText, TextToSpeech.QUEUE_ADD, null, "sa_${System.currentTimeMillis()}") } catch (_: Exception) {}
        }
        OtwFileLogger.log(TAG, "statusAlert: overlay=\"$overlayText\", tts=\"$ttsText\"")
    }

    /**
     * 단일 출력 진입점.
     */
    fun emit(
        ctx: Context,
        ttsText: String?,
        overlayText: String,
        mode: OutputMode,
        tts: TextToSpeech? = null,
        ttsReady: Boolean = false,
        pricePerKm: Int? = null
    ) {
        if (mode == OutputMode.SILENT) return

        // 금지어 체크: overlayText/ttsText 어디서 왔든 차단
        if (FORBIDDEN_WORDS.any { overlayText.contains(it) }) {
            OtwFileLogger.log(TAG, "금지어 감지 → emit 차단: \"$overlayText\"")
            return
        }

        val now = System.currentTimeMillis()

        // Overlay 쿨다운 (2초) + 동일 메시지 dedup
        val isSameMsg = overlayText == lastOverlayText && now - lastOverlayTime < OVERLAY_COOLDOWN_MS
        if (!isSameMsg) {
            lastOverlayTime = now
            lastOverlayText = overlayText
            val textColor = CardOverlay.colorForUnitPrice(pricePerKm)
            try { CardOverlay.show(ctx, overlayText, textColor) } catch (e: Exception) {
                Log.w(TAG, "Overlay 실패: ${e.message}")
            }
        } else {
            OtwFileLogger.log(TAG, "Overlay 쿨다운 스킵 (동일 메시지 ${now - lastOverlayTime}ms): \"$overlayText\"")
        }

        // TTS (FULL만, 30초 쿨다운)
        if (mode == OutputMode.FULL && ttsText != null && tts != null && ttsReady) {
            if (now - lastTtsTime >= TTS_COOLDOWN_MS) {
                if (ttsText.length <= TTS_MAX_LENGTH) {
                    lastTtsTime = now
                    try { tts.speak(ttsText, TextToSpeech.QUEUE_ADD, null, "oc_$now") } catch (_: Exception) {}
                } else {
                    OtwFileLogger.log(TAG, "TTS 길이 초과 → SILENT: \"$ttsText\" (${ttsText.length}자)")
                }
            } else {
                OtwFileLogger.log(TAG, "TTS 30초 쿨다운 → 스킵: \"$ttsText\"")
            }
        }

        OtwFileLogger.log(TAG, "emit: mode=$mode, overlay=\"$overlayText\"" +
            if (mode == OutputMode.FULL && ttsText != null) ", tts=\"$ttsText\"" else "")
    }

    /**
     * Confidence 기반 OutputMode 결정.
     */
    fun determineMode(call: DeliveryCall): OutputMode {
        val hasDist = call.distance != null && call.distance > 0
        return if (hasDist) OutputMode.FULL else OutputMode.OVERLAY_ONLY
    }

    /**
     * 근거 메시지 검증.
     * 금지어 포함 시 null 반환 + 로그 기록.
     */
    private fun validateMessage(msg: String): String? {
        for (word in FORBIDDEN_WORDS) {
            if (msg.contains(word)) {
                OtwFileLogger.log(TAG, "금지어 감지 → SILENT: \"$msg\" (금지어: $word)")
                return null
            }
        }
        return msg
    }

    /**
     * 사유 메시지를 근거형으로 변환 (판정 단어 제거).
     * CallDetailDialog, UserModeActivity 공용.
     */
    fun toEvidenceReason(reason: String, price: Int, call: DeliveryCall?): String {
        val dist = call?.distance
        val unitPrice = if (dist != null && dist > 0) (price / dist).toInt() else 0
        val isMulti = call?.isMulti == true
        val bundleCount = (call?.bundleCount ?: 1).coerceAtLeast(if (isMulti) 2 else 1)
        val perItem = if (isMulti && bundleCount > 1) price / bundleCount else 0

        return when {
            isMulti && perItem > 0 -> "건당 ${nf.format(perItem)}원"
            unitPrice > 0 -> "단가 ${nf.format(unitPrice)}원/km"
            price > 0 -> "${nf.format(price)}원"
            else -> ""
        }
    }
}
