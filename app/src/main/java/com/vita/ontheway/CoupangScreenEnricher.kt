package com.vita.ontheway

import android.content.Context
import android.util.Log

/**
 * COUPANG-NOTIFICATION-FIRST + HARDENING: 쿠팡 후행 화면 enrichment.
 *
 * 픽업/진행/완료 화면에서 storeName / settledPrice 보강.
 * identityKey 또는 callSessionId 매칭 필수 — 가격 기반 매칭 금지.
 */
object CoupangScreenEnricher {

    private const val TAG = "CoupangEnricher"

    data class Enrichment(
        val storeName: String? = null,
        val settledPrice: Int? = null,
        val distanceKm: Double? = null
    )

    private val PROGRESS_KEYWORDS = listOf("매장 도착", "매장 픽업", "픽업 중", "배달 중")
    private val COMPLETE_KEYWORDS = listOf("배달 완료", "배달이 완료")

    /**
     * 화면 텍스트에서 enrichment 정보 추출.
     */
    fun extract(texts: List<String>): Enrichment? {
        val joined = texts.joinToString(" ")
        val isProgress = PROGRESS_KEYWORDS.any { joined.contains(it) }
        val isComplete = COMPLETE_KEYWORDS.any { joined.contains(it) }
        if (!isProgress && !isComplete) return null

        val storeName = CoupangParser.extractStoreFromProgress(texts).ifEmpty { null }
        val settledPrice = if (isComplete) extractSettledPrice(texts) else null

        if (storeName == null && settledPrice == null) return null

        OtwFileLogger.log(TAG, "enrichment: store='${storeName ?: ""}' settled=${settledPrice ?: "N/A"}")
        return Enrichment(storeName = storeName, settledPrice = settledPrice)
    }

    /**
     * identityKey 또는 callSessionId 기반 enrichment 적용.
     * 매칭 실패 → enrichment 거부 + 로그.
     *
     * @return true if applied, false if rejected
     */
    fun applyEnrichment(
        ctx: Context,
        enrichment: Enrichment,
        callSessionId: String?
    ): Boolean {
        if (callSessionId.isNullOrBlank()) {
            OtwFileLogger.log(TAG, "ENRICHMENT_REJECTED: no callSessionId")
            return false
        }

        val storeName = enrichment.storeName
        if (!storeName.isNullOrBlank()) {
            try {
                CallLogDb.get(ctx).updateStoreNameBySessionId(callSessionId, storeName)
                OtwFileLogger.log(TAG, "ENRICHMENT_APPLIED: store='$storeName' session=${callSessionId.take(8)}")
            } catch (e: Exception) {
                OtwFileLogger.log(TAG, "ENRICHMENT_FAILED: ${e.message}")
                return false
            }
        }

        return true
    }

    private fun extractSettledPrice(texts: List<String>): Int? {
        val pricePattern = Regex("""([\d,]+)\s*원""")
        for (text in texts) {
            if (text.contains("정산") || text.contains("배달료") || text.contains("수익")) {
                val match = pricePattern.find(text)
                if (match != null) {
                    val price = match.groupValues[1].replace(",", "").toIntOrNull()
                    if (price != null && price in 500..100_000) return price
                }
            }
        }
        return null
    }
}
