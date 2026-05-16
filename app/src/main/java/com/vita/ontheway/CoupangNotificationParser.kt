package com.vita.ontheway

import android.util.Log

/**
 * COUPANG-NOTIFICATION-FIRST + HARDENING: 쿠팡 알림 텍스트 전용 파서.
 *
 * 3단 매칭:
 * 1. Structured (confidence=0.9): [N건 단일/묶음] 가격원 / 거리km
 * 2. Loose (confidence=0.5): 가격원 + 거리km 조합 (title 빈 경우 포함)
 * 3. NonCall (confidence=0.0): 비콜 필터
 *
 * "주문을 수락해주세요" 보조 신호: confidence +0.1
 */
object CoupangNotificationParser {

    private const val TAG = "CoupangNotiParser"

    data class CoupangNotification(
        val offeredPrice: Int,
        val distanceKm: Double?,
        val bundleCount: Int,
        val isMulti: Boolean,
        val bundleType: String,        // "단일", "묶음", "멀티", "unknown"
        val notificationKey: String,   // sbnKey
        val postTime: Long,
        val storeName: String,
        val rawText: String,
        val confidence: Double,        // 매칭 confidence (0.0~1.0)
        val sourceChannel: String,     // "notification_structured" / "notification_loose"
        val cookingStatus: String = "UNKNOWN" // COOKING_DONE / UNKNOWN
    )

    // ── Structured patterns (confidence=0.9) ──
    private val BRACKET_PATTERN = Regex("""\[(\d+)건\s*(단일|묶음)\]\s*([\d,]+)원\s*/\s*(\d+(?:\.\d+)?)\s*km""")
    private val COOK_COMPLETE_PATTERN = Regex("""\(조리완료\)\s*([\d,]+)원\s*(?:.*?)\s*배달\s*거리\s*(\d+(?:\.\d+)?)\s*km""")
    private val MULTI_PATTERN = Regex("""멀티\s*([\d,]+)원\s*(?:.*?)\s*배달\s*거리\s*(\d+(?:\.\d+)?)\s*km""")

    // ── Loose patterns (confidence=0.5) ──
    private val SIMPLE_PATTERN = Regex("""([\d,]+)원\s*/\s*(\d+(?:\.\d+)?)\s*km""")
    private val PRICE_PATTERN = Regex("""([\d,]+)\s*원""")
    private val DISTANCE_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*km""")

    // ── NonCall filter ──
    private val NON_CALL_KEYWORDS = listOf(
        "배달 완료", "픽업 완료", "배달이 완료", "출근", "퇴근",
        "깜짝 미션", "지역 한정", "완료 시 최대", "배달 현황",
        "운행이 종료", "배달이 많은 곳", "Aggregate_NormalNotificationSection"
    )

    // 수락 요청 보조 신호
    private val ACCEPT_HINT = "주문을 수락해주세요"
    private const val ACCEPT_HINT_BONUS = 0.1

    fun parse(
        title: String,
        text: String,
        bigText: String = "",
        sbnKey: String = "",
        postTime: Long = System.currentTimeMillis()
    ): CoupangNotification? {
        val combined = "$title $text $bigText".trim()
        if (combined.isBlank()) return null

        // NonCall 필터
        if (NON_CALL_KEYWORDS.any { combined.contains(it) }) {
            OtwFileLogger.log(TAG, "NON_CALL: '${combined.take(60)}'")
            return null
        }

        val acceptBonus = if (combined.contains(ACCEPT_HINT)) ACCEPT_HINT_BONUS else 0.0

        // ── Structured (confidence=0.9) ──
        val structured = tryStructured(combined, sbnKey, postTime, acceptBonus)
        if (structured != null) {
            logParsedEvent(sbnKey, "matched", structured.offeredPrice,
                structured.distanceKm, structured.bundleCount, structured.bundleType, combined)
            return structured
        }

        // ── Loose (confidence=0.5) ──
        val loose = tryLoose(combined, sbnKey, postTime, acceptBonus)
        if (loose != null) {
            logParsedEvent(sbnKey, "partial", loose.offeredPrice,
                loose.distanceKm, loose.bundleCount, loose.bundleType, combined)
            return loose
        }

        Log.d(TAG, "NO_MATCH: '${combined.take(60)}'")
        logParsedEvent(sbnKey, "unmatched", null, null, null, null, combined)
        return null
    }

    private fun tryStructured(combined: String, sbnKey: String, postTime: Long, bonus: Double): CoupangNotification? {
        val baseConf = 0.9 + bonus

        // [N건 단일/묶음] 가격원 / 거리km
        BRACKET_PATTERN.find(combined)?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 1
            val type = m.groupValues[2]
            val price = m.groupValues[3].replace(",", "").toIntOrNull() ?: return null
            val dist = m.groupValues[4].toDoubleOrNull()
            if (price !in 500..100_000) return null
            OtwFileLogger.log(TAG, "STRUCTURED_BRACKET: ${count}건 $type ${price}원 ${dist}km conf=${"%.2f".format(baseConf)}")
            return build(price, dist, count, count > 1 || type == "묶음", type,
                sbnKey, postTime, combined, baseConf, "notification_structured")
        }

        // (조리완료) 가격원 배달 거리 X.Xkm
        COOK_COMPLETE_PATTERN.find(combined)?.let { m ->
            val price = m.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            val dist = m.groupValues[2].toDoubleOrNull()
            if (price !in 500..100_000) return null
            OtwFileLogger.log(TAG, "STRUCTURED_COOK: ${price}원 ${dist}km")
            return build(price, dist, 1, false, "단일",
                sbnKey, postTime, combined, baseConf, "notification_structured")
        }

        // 멀티 가격원 ... 배달 거리 X.Xkm
        MULTI_PATTERN.find(combined)?.let { m ->
            val price = m.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            val dist = m.groupValues[2].toDoubleOrNull()
            if (price !in 500..100_000) return null
            OtwFileLogger.log(TAG, "STRUCTURED_MULTI: ${price}원 ${dist}km")
            return build(price, dist, 2, true, "멀티",
                sbnKey, postTime, combined, baseConf, "notification_structured")
        }

        return null
    }

    private fun tryLoose(combined: String, sbnKey: String, postTime: Long, bonus: Double): CoupangNotification? {
        val baseConf = 0.5 + bonus

        // 가격원 / 거리km (슬래시 구분)
        SIMPLE_PATTERN.find(combined)?.let { m ->
            val price = m.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            val dist = m.groupValues[2].toDoubleOrNull()
            if (price !in 500..100_000) return null
            val isMulti = combined.contains("멀티")
            OtwFileLogger.log(TAG, "LOOSE_SIMPLE: ${price}원 ${dist}km conf=${"%.2f".format(baseConf)}")
            return build(price, dist, if (isMulti) 2 else 1, isMulti,
                if (isMulti) "멀티" else "unknown",
                sbnKey, postTime, combined, baseConf, "notification_loose")
        }

        // 가격만 + 거리 별도 (title 빈 경우 등)
        val priceMatch = PRICE_PATTERN.find(combined)
        if (priceMatch != null) {
            val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            if (price !in 500..100_000) return null
            val dist = DISTANCE_PATTERN.find(combined)?.groupValues?.get(1)?.toDoubleOrNull()
            val isMulti = combined.contains("멀티")
            OtwFileLogger.log(TAG, "LOOSE_PRICE: ${price}원 ${dist}km conf=${"%.2f".format(baseConf)}")
            return build(price, dist, if (isMulti) 2 else 1, isMulti,
                if (isMulti) "멀티" else "unknown",
                sbnKey, postTime, combined, baseConf, "notification_loose")
        }

        return null
    }

    private fun build(
        price: Int, dist: Double?, count: Int, isMulti: Boolean, type: String,
        sbnKey: String, postTime: Long, raw: String, confidence: Double, source: String
    ): CoupangNotification {
        val cooking = if (raw.contains("(조리완료)") || raw.contains("조리완료")) "COOKING_DONE" else "UNKNOWN"
        return CoupangNotification(
            offeredPrice = price, distanceKm = dist,
            bundleCount = count, isMulti = isMulti, bundleType = type,
            notificationKey = sbnKey, postTime = postTime,
            storeName = extractStoreName(raw, price),
            rawText = raw, confidence = confidence.coerceAtMost(1.0),
            sourceChannel = source, cookingStatus = cooking
        )
    }

    /**
     * 계측 hook: parsed_event_candidates INSERT.
     * 프로덕션에서는 InstrumentationDb, 테스트에서는 lambda 교체.
     */
    var parsedEventLogger: ((ref: String?, matchResult: String, price: Int?,
        distKm: Double?, bundleSize: Int?, bundleType: String?, snippet: String) -> Unit)? = null

    private fun logParsedEvent(
        ref: String?, matchResult: String, price: Int?,
        distKm: Double?, bundleSize: Int?, bundleType: String?, raw: String
    ) {
        val status = if (matchResult == "unmatched") "failed" else "success"
        try {
            parsedEventLogger?.invoke(ref, matchResult, price, distKm, bundleSize, bundleType, raw.take(100))
        } catch (_: Exception) {}
    }

    /** 묶음 정보만 추출 (parseCoupangNotification에서 사용) */
    data class BundleInfo(val bundleCount: Int, val bundleType: String, val isMulti: Boolean)

    private val BUNDLE_EXTRACT_PATTERN = Regex("""\[(\d+)건\s*(단일|묶음|멀티)\]""")

    fun parseBundleInfo(text: String): BundleInfo {
        val m = BUNDLE_EXTRACT_PATTERN.find(text)
        if (m != null) {
            val count = m.groupValues[1].toIntOrNull() ?: 1
            val type = m.groupValues[2]
            val isMulti = count > 1 || type == "묶음" || type == "멀티"
            return BundleInfo(count, type, isMulti)
        }
        if (text.contains("멀티")) return BundleInfo(2, "멀티", true)
        if (text.contains("주문 두 건")) return BundleInfo(2, "묶음", true)
        return BundleInfo(1, "unknown", false)
    }

    /** parser version (계측용) */
    const val PARSER_VERSION = "coupang_parser_v1.0"

    private fun extractStoreName(text: String, price: Int): String {
        val priceStr = "%,d원".format(price)
        val altPriceStr = "${price}원"
        val beforePrice = text.substringBefore(priceStr).let {
            if (it == text) text.substringBefore(altPriceStr) else it
        }
        val cleaned = beforePrice
            .replace(Regex("""\[?\d+건\s*(단일|묶음)\]?\s*"""), "")
            .replace(Regex("""^(멀티|단일|일반|대량\s*주문,?)\s*"""), "")
            .replace(Regex("""\(조리완료\)\s*"""), "")
            .trim()
        if (cleaned.length in 2..30 && cleaned.any { it in '\uAC00'..'\uD7A3' || it in 'a'..'z' || it in 'A'..'Z' }) {
            if (cleaned.contains("거리할증") || cleaned.contains("지원금")) return ""
            return cleaned
        }
        return ""
    }
}
