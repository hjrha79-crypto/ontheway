package com.vita.ontheway

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.text.NumberFormat

/**
 * 출력 단일 진입점 — TTS 3단 구조.
 *
 * 계산형 (쿠팡 + distanceKm > 0): 우세/보통/주의 + 이유
 * 정보형 (배민 or distanceKm == 0): 금액 + 픽업거리
 * 침묵: 데이터 부족
 */
object OutputController {

    private const val TAG = "OutputController"
    private const val TTS_COOLDOWN_MS = 5_000L
    private const val TTS_MAX_LENGTH = 30
    private const val OVERLAY_COOLDOWN_MS = 2000L

    private var lastTtsTime = 0L
    private var lastOverlayTime = 0L
    private var lastOverlayText = ""

    private val nf = NumberFormat.getNumberInstance()

    /** 판단 금지어 — 이 단어가 포함된 메시지는 출력 차단 */
    val FORBIDDEN_WORDS = listOf(
        "잡으세요", "넘기세요", "괜찮습니다", "추천", "멈", "비추천",
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
     * TTS v3.0: [플랫폼] [금액] [이유] [verdict]
     * - verdict 맨 뒤 (한국어 자연 어순)
     * - 플랫폼/금액 항상 명시
     * - 보통도 verdict 명시
     */
    fun buildMessage(call: DeliveryCall, result: CallFilter.FilterResult): String? {
        val price = call.price
        val dist = call.distance
        val distKm = dist ?: 0.0
        val pickupForUnit = call.pickupDistanceKm ?: 0.0
        val totalForUnit = pickupForUnit + distKm
        val unitPrice = if (totalForUnit > 0) (price / totalForUnit).toInt() else 0
        val isMulti = call.isMulti
        val bundleCount = call.bundleCount.coerceAtLeast(if (isMulti) 2 else 1)
        val platform = if (call.platform == "coupang") "쿠팡" else "배민"
        val verdict = extractVerdict(call, result)

        if (price <= 0) return null

        // ── 묶음 ──
        if (isMulti) {
            return validateMessage("$platform, ${price}원, ${bundleCount}개 묶음, $verdict")
        }

        // ── 단일 (거리 있음 → 단가 표시) ──
        if (distKm > 0) {
            val pickupUnknown = call.pickupDistanceKm == null || call.pickupDistanceKm <= 0.0
            val reason = when {
                unitPrice >= 2000 && distKm <= 1.5 && !pickupUnknown -> "단거리 고단가"
                else -> "단가 ${unitPrice}원"
            }
            return validateMessage("$platform, ${price}원, $reason, $verdict")
        }

        // ── 단일 (거리 없음 → 픽업 거리 있으면 활용) ──
        val pickupKm = call.pickupDistanceKm ?: 0.0
        if (pickupKm > 0) {
            return validateMessage("$platform, ${price}원, 픽업 ${"%.1f".format(pickupKm)}km, $verdict")
        }
        return validateMessage("$platform, ${price}원, $verdict")
    }

    /** verdict 3단계 판정 (TTS용, 직관 표현) */
    private fun extractVerdict(call: DeliveryCall, result: CallFilter.FilterResult): String {
        if (result.verdict == CallFilter.Verdict.REJECT) return "주의"
        val dist = call.distance ?: 0.0
        val pickupKm = call.pickupDistanceKm ?: 0.0
        val totalKm = pickupKm + dist
        val unitPrice = if (totalKm > 0) (call.price / totalKm).toInt() else 0
        val pickupUnknown = call.pickupDistanceKm == null || call.pickupDistanceKm <= 0.0
        val raw = when {
            call.price >= 8000 -> "우세"
            dist > 0 && unitPrice >= 1700 -> "우세"
            dist > 0 && unitPrice in 1400..1699 -> "보통"
            dist == 0.0 && pickupKm > 0 && pickupKm <= 1.0 && call.price >= 3000 -> "우세"
            dist == 0.0 && pickupKm >= 5.0 -> "주의"
            else -> "보통"
        }

        // 멀티콜 보정: 단가 0.5x + 우세 강등
        if (call.isMulti) {
            val effectiveUnitPrice = if (totalKm > 0) ((call.price / totalKm) * 0.5).toInt() else 0
            OtwFileLogger.log(TAG, "[VERDICT_MULTI] 멀티 보정: " +
                "원래 단가=${if (totalKm > 0) (call.price / totalKm).toInt() else 0} → 보정 $effectiveUnitPrice")
            // 멀티는 우세/보통 → 보통 강등
            val downgraded = if (raw == "우세") "보통" else raw
            // 보정 단가 < 1500 → 주의
            if (totalKm > 0 && effectiveUnitPrice < 1500) {
                OtwFileLogger.log(TAG, "[VERDICT_MULTI] 보정단가 ${effectiveUnitPrice} < 1500 → 주의")
                return "주의"
            }
            return downgraded
        }

        // 픽업거리 미측정 시 우세 → 보통 강등 (고액 8000원+ 제외)
        if (raw == "우세" && pickupUnknown && call.price < 8000) {
            OtwFileLogger.log(TAG, "verdict 강등: 우세→보통 (pickupKm=null, ${call.price}원, dist=$dist)")
            return "보통"
        }
        return raw
    }

    /** Overlay용 이모지 추가 (TTS는 원문 그대로) */
    fun addVerdictEmoji(text: String): String = text
        .replace("우세", "\uD83D\uDFE2 우세")
        .replace("보통", "\u26AA 보통")
        .replace("주의", "\uD83D\uDD34 주의")

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
        val overlayWithEmoji = addVerdictEmoji(overlayText)
        val isSameMsg = overlayWithEmoji == lastOverlayText && now - lastOverlayTime < OVERLAY_COOLDOWN_MS
        if (!isSameMsg) {
            lastOverlayTime = now
            lastOverlayText = overlayWithEmoji
            val textColor = CardOverlay.colorForUnitPrice(pricePerKm)
            try { CardOverlay.show(ctx, overlayWithEmoji, textColor) } catch (e: Exception) {
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
                OtwFileLogger.log(TAG, "TTS 쿨다운 → 스킵: \"$ttsText\"")
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
        val totalKmEvidence = (call?.pickupDistanceKm ?: 0.0) + (dist ?: 0.0)
        val unitPrice = if (totalKmEvidence > 0) (price / totalKmEvidence).toInt() else 0
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
