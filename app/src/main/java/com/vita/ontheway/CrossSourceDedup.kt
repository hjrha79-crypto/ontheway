package com.vita.ontheway

import java.util.concurrent.ConcurrentHashMap

/**
 * FIX-NLS-CROSS-SOURCE-DEDUP: NLS-Accessibility 간 cross-source 중복 방지.
 *
 * 같은 콜이 NLS(알림)와 Accessibility(접근성) 양쪽에서 감지되면
 * 먼저 처리된 쪽을 기준으로 나머지를 차단.
 *
 * 우선순위: eventId > orderId > platform+price+store > platform+price
 * TTL: 5분
 */
object CrossSourceDedup {

    const val TTL_MS = 300_000L // 5분

    private val processedKeys = ConcurrentHashMap<String, Long>()

    /**
     * 콜 처리 완료 시 등록.
     * NLS, Accessibility 양쪽에서 호출.
     */
    fun markProcessed(
        eventId: String? = null,
        orderId: String? = null,
        platform: String,
        price: Int,
        storeName: String? = null
    ) {
        cleanup()
        val now = System.currentTimeMillis()
        // 가능한 모든 레벨의 키 등록 (하위 키도 등록하여 fallback 매칭)
        if (!eventId.isNullOrBlank()) processedKeys["eid:$eventId"] = now
        if (!orderId.isNullOrBlank()) processedKeys["oid:$orderId"] = now
        if (!storeName.isNullOrBlank()) processedKeys["pps:$platform:$price:$storeName"] = now
        processedKeys["pp:$platform:$price"] = now
    }

    /**
     * 콜이 이미 다른 소스에서 처리되었는지 확인.
     * @return true = 이미 처리됨 (차단), false = 미처리 (통과)
     */
    fun isProcessed(
        eventId: String? = null,
        orderId: String? = null,
        platform: String,
        price: Int,
        storeName: String? = null
    ): Boolean {
        cleanup()
        val now = System.currentTimeMillis()
        // 높은 우선순위부터 체크
        if (!eventId.isNullOrBlank()) {
            val ts = processedKeys["eid:$eventId"]
            if (ts != null && now - ts < TTL_MS) return true
        }
        if (!orderId.isNullOrBlank()) {
            val ts = processedKeys["oid:$orderId"]
            if (ts != null && now - ts < TTL_MS) return true
        }
        if (!storeName.isNullOrBlank()) {
            val ts = processedKeys["pps:$platform:$price:$storeName"]
            if (ts != null && now - ts < TTL_MS) return true
        }
        val ts = processedKeys["pp:$platform:$price"]
        if (ts != null && now - ts < TTL_MS) return true
        return false
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        processedKeys.entries.removeAll { now - it.value > TTL_MS }
    }

    /** 테스트용 초기화 */
    fun reset() { processedKeys.clear() }
}
