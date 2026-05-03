package com.vita.ontheway

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.text.NumberFormat

/**
 * 출력 단일 진입점 — TTS 3단 구조.
 *
 * 계산형 (쿠팡 + distanceKm > 0): 추천/애매/비추천 + 이유
 * 정보형 (배민 or distanceKm == 0): 금액 + 픽업거리
 * 침묵: 데이터 부족
 */
object OutputController {

    private const val TAG = "OutputController"
    private const val TTS_COOLDOWN_MS = 5_000L
    private const val TTS_MAX_LENGTH = 20
    private const val OVERLAY_COOLDOWN_MS = 2000L

    private var lastTtsTime = 0L
    private var lastOverlayTime = 0L
    private var lastOverlayText = ""

    private val nf = NumberFormat.getNumberInstance()

    /** 판단 금지어 — 이 단어가 포함된 메시지는 출력 차단 */
    val FORBIDDEN_WORDS = listOf(
        "잡으세요", "넘기세요", "괜찮습니다",
        "권장", "좋은 콜"
    )

    /** 사유 라이브러리 (랜덤 선택) */
    private val reasonLibrary = mapOf(
        "FAR_PICKUP" to listOf("픽업 멂", "픽업 부담"),
        "LOW_VALUE" to listOf("단가 낮음"),
        "BUNDLE_BAD" to listOf("건당 낮음"),
        "BUNDLE_GOOD" to listOf("묶음 효율 좋음"),
        "GOOD_ROUTE" to listOf("경로 비슷함", "흐름 좋음"),
        "HIGH_VALUE" to listOf("단가 높음", "수익 좋음")
    )

    fun pickReason(key: String): String =
        reasonLibrary[key]?.random() ?: key

    /**
     * TTS v2.1: verdict 먼저 + 이유 1~2단어 (6단어 이내)
     *
     * 쿠팡 단일: "추천 단거리 고단가" / "비추천 단가 낮음" / "애매 [이유]"
     * 쿠팡 멀티: "[verdict] 멀티 [건수]건"
     * 배민 단일: "[verdict] 배민 [금액]" / 비추천 시 "+단가 낮음"
     * 배민 묶음: "[verdict] 묶음 [건수]건"
     */
    fun buildMessage(call: DeliveryCall, result: CallFilter.FilterResult): String? {
        val price = call.price
        val dist = call.distance
        val distKm = dist ?: 0.0
        val unitPrice = if (distKm > 0) (price / distKm).toInt() else 0
        val isMulti = call.isMulti
        val bundleCount = call.bundleCount.coerceAtLeast(if (isMulti) 2 else 1)
        val isCoupang = call.platform == "coupang"
        val isReject = result.verdict == CallFilter.Verdict.REJECT

        // verdict 한글 (3단계)
        val verdict = extractVerdict(call, result)

        // ── 묶음/멀티 (플랫폼 무관) ──
        if (isMulti) {
            return validateMessage("$verdict 묶음 ${bundleCount}건")
        }

        // ── 쿠팡 단일 ──
        if (isCoupang) {
            val reason = when {
                distKm > 0 && unitPrice >= 1700 -> "단거리 고단가"
                distKm > 0 && unitPrice < 1400 -> "단가 낮음"
                distKm > 0 -> "단가 ${nf.format(unitPrice)}"
                price >= 8000 -> "고액"
                isReject -> "단가 낮음"
                else -> "${nf.format(price)}원"
            }
            return validateMessage("$verdict $reason")
        }

        // ── 배민 단일 ──
        val priceStr = nf.format(price)
        val msg = if (isReject) {
            "$verdict 배민 $priceStr 단가 낮음"
        } else {
            "$verdict 배민 $priceStr"
        }
        return validateMessage(msg)
    }

    /** verdict 3단계 판정 (TTS용) */
    private fun extractVerdict(call: DeliveryCall, result: CallFilter.FilterResult): String {
        if (result.verdict == CallFilter.Verdict.REJECT) return "비추천"
        val dist = call.distance ?: 0.0
        val unitPrice = if (dist > 0) (call.price / dist).toInt() else 0
        return when {
            call.price >= 8000 -> "추천"
            dist > 0 && unitPrice >= 1700 -> "추천"
            dist > 0 && unitPrice in 1400..1699 -> "애매"
            else -> "보통"
        }
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
        // 모든 콜에 TTS 발화 (배민 distance=null이어도 FULL)
        return OutputMode.FULL
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
