package com.vita.ontheway

object TtsDeduplicator {

    private val history = mutableMapOf<String, Long>()
    private const val DEDUP_MS = 5_000L

    // 묶음 총액 기록 (부분 파싱 중복 방지)
    private val bundleTotals = mutableMapOf<String, Long>()  // "platform_totalPrice" → ts
    private const val BUNDLE_DEDUP_MS = 10_000L

    /** true = 발화 가능, false = 5초 이내 중복 */
    fun shouldSpeak(platform: String, price: Int): Boolean {
        val key = "${platform}_${price}"
        val now = System.currentTimeMillis()

        // 오래된 항목 정리
        history.entries.removeIf { now - it.value > 60_000 }
        bundleTotals.entries.removeIf { now - it.value > 60_000 }

        val last = history[key]
        if (last != null && now - last < DEDUP_MS) return false

        // 묶음 총액의 부분합 체크
        for ((bKey, bTime) in bundleTotals) {
            if (now - bTime > BUNDLE_DEDUP_MS) continue
            val parts = bKey.split("_")
            if (parts.size < 2) continue
            val bPlatform = parts[0]
            val bTotal = parts[1].toIntOrNull() ?: continue
            if (bPlatform == platform && price < bTotal && price > bTotal / 3) {
                // 이 가격이 묶음 총액의 부분합일 가능성 높음
                return false
            }
        }

        history[key] = now
        return true
    }

    /** 묶음 총액 기록 */
    fun recordBundleTotal(platform: String, totalPrice: Int) {
        val key = "${platform}_${totalPrice}"
        bundleTotals[key] = System.currentTimeMillis()
    }

    /** 지정 시간(ms) 이내에 해당 키가 발화된 적 있는지 확인 (기록하지 않음) */
    fun wasSpokenWithin(platform: String, price: Int, windowMs: Long): Boolean {
        val key = "${platform}_${price}"
        val last = history[key] ?: return false
        return System.currentTimeMillis() - last < windowMs
    }
}
