package com.vita.ontheway

import java.util.concurrent.ConcurrentHashMap

/**
 * 콜 감지 단계 NLS/A11Y 중복 방지.
 *
 * 같은 콜이 NLS(알림)와 Accessibility(접근성) 양쪽에서 감지되면
 * 먼저 처리된 쪽을 기준으로 나머지를 차단.
 *
 * 역할 범위: 콜 감지(CALL_DETECTED) 단계 dedup 전용.
 * ACCEPT 통계 dedup은 AcceptCoordinator가 담당 — 혼동 주의.
 *
 * 우선순위: eventId > orderId > platform+price+store > platform+price
 * TTL: 15 seconds
 */
object CrossSourceCallDetectionDedup {

    private const val TAG = "CrossSourceCallDedup"
    const val TTL_MS = 15_000L // 15 seconds

    const val SOURCE_NLS = "nls"
    const val SOURCE_A11Y = "a11y"

    private val processedKeys = ConcurrentHashMap<String, Long>()

    /**
     * 계측 hook: dedup_decisions INSERT.
     * 프로덕션에서는 InstrumentationDb, 테스트에서는 lambda 교체.
     */
    var dedupDecisionLogger: ((decision: String, reason: String?,
        sourceChain: String?, price: Int, platform: String, timeGapMs: Long?) -> Unit)? = null

    fun markProcessed(
        eventId: String? = null,
        orderId: String? = null,
        platform: String,
        price: Int,
        storeName: String? = null,
        source: String = ""
    ) {
        cleanup()
        val now = System.currentTimeMillis()
        if (!eventId.isNullOrBlank()) processedKeys["eid:$eventId"] = now
        if (!orderId.isNullOrBlank()) processedKeys["oid:$orderId"] = now
        if (!storeName.isNullOrBlank()) processedKeys["pps:$platform:$price:$storeName"] = now
        processedKeys["pp:$platform:$price"] = now

        val keyType = when {
            !eventId.isNullOrBlank() -> "eid"
            !orderId.isNullOrBlank() -> "oid"
            !storeName.isNullOrBlank() -> "pps"
            else -> "pp"
        }
        OtwFileLogger.log(TAG, "MARK source=$source keyType=$keyType platform=$platform price=$price store=${storeName ?: ""}")
        try {
            dedupDecisionLogger?.invoke("new_session", "first_detection_$source",
                source, price, platform, null)
        } catch (_: Exception) {}
    }

    fun isProcessed(
        eventId: String? = null,
        orderId: String? = null,
        platform: String,
        price: Int,
        storeName: String? = null,
        source: String = ""
    ): Boolean {
        cleanup()
        val now = System.currentTimeMillis()

        if (!eventId.isNullOrBlank()) {
            val ts = processedKeys["eid:$eventId"]
            if (ts != null && now - ts < TTL_MS) {
                OtwFileLogger.log(TAG, "BLOCK source=$source keyType=eid platform=$platform price=$price")
                logMerge(source, "eid_match", price, platform, now - ts)
                return true
            }
        }
        if (!orderId.isNullOrBlank()) {
            val ts = processedKeys["oid:$orderId"]
            if (ts != null && now - ts < TTL_MS) {
                OtwFileLogger.log(TAG, "BLOCK source=$source keyType=oid platform=$platform price=$price")
                logMerge(source, "oid_match", price, platform, now - ts)
                return true
            }
        }
        if (!storeName.isNullOrBlank()) {
            val ts = processedKeys["pps:$platform:$price:$storeName"]
            if (ts != null && now - ts < TTL_MS) {
                OtwFileLogger.log(TAG, "BLOCK source=$source keyType=pps platform=$platform price=$price store=$storeName")
                logMerge(source, "pps_match", price, platform, now - ts)
                return true
            }
        }

        // NLS에서 stable key 없이 pp만 매칭 → 차단 X
        val hasStableKey = !eventId.isNullOrBlank() || !orderId.isNullOrBlank() || !storeName.isNullOrBlank()
        if (source == SOURCE_NLS && !hasStableKey) {
            return false
        }

        val ts = processedKeys["pp:$platform:$price"]
        if (ts != null && now - ts < TTL_MS) {
            OtwFileLogger.log(TAG, "BLOCK source=$source keyType=pp platform=$platform price=$price")
            logMerge(source, "price_match_within_${(now - ts) / 1000}s", price, platform, now - ts)
            return true
        }
        return false
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        processedKeys.entries.removeAll { now - it.value > TTL_MS }
    }

    private fun logMerge(source: String, reason: String, price: Int, platform: String, gapMs: Long) {
        val otherSource = if (source == SOURCE_NLS) SOURCE_A11Y else SOURCE_NLS
        try {
            dedupDecisionLogger?.invoke("merge_to_existing", reason,
                "$otherSource→${source}", price, platform, gapMs)
        } catch (_: Exception) {}
    }

    fun reset() { processedKeys.clear() }
}
