package com.vita.ontheway

object TtsDeduplicator {

    private val history = mutableMapOf<String, Long>()
    private const val DEDUP_MS = 5_000L

    /** true = 발화 가능, false = 5초 이내 중복 */
    fun shouldSpeak(platform: String, price: Int): Boolean {
        val key = "${platform}_${price}"
        val now = System.currentTimeMillis()

        // 오래된 항목 정리
        history.entries.removeIf { now - it.value > 60_000 }

        val last = history[key]
        if (last != null && now - last < DEDUP_MS) return false

        history[key] = now
        return true
    }

    /** 지정 시간(ms) 이내에 해당 키가 발화된 적 있는지 확인 (기록하지 않음) */
    fun wasSpokenWithin(platform: String, price: Int, windowMs: Long): Boolean {
        val key = "${platform}_${price}"
        val last = history[key] ?: return false
        return System.currentTimeMillis() - last < windowMs
    }
}
