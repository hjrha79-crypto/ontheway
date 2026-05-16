package com.vita.ontheway

/**
 * Repeat Critical v0.1: 조리완료 TTS 1회 발화 dedupe.
 * In-memory — 앱 재시작 시 초기화.
 */
object CookingStatusTracker {

    private val spokenKeys = mutableSetOf<String>()

    fun dedupeKey(platform: String, sessionId: String?, storeName: String, price: Int): String {
        if (!sessionId.isNullOrEmpty()) return "$platform:$sessionId"
        val bucket = System.currentTimeMillis() / 300_000 // 5min bucket
        return "$platform:$storeName:$price:$bucket"
    }

    /** true = 처음 → 발화 OK, false = 이미 발화됨 */
    fun tryMarkSpoken(key: String): Boolean = spokenKeys.add(key)

    fun hasSpooken(key: String): Boolean = spokenKeys.contains(key)

    fun clear() { spokenKeys.clear() }

    /** 테스트용 */
    internal fun spokenCount(): Int = spokenKeys.size
}
