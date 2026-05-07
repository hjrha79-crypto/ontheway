package com.vita.ontheway

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * FIX-NLS-CROSS-SOURCE-DEDUP: NLS-Accessibility 간 cross-source 중복 방지.
 *
 * 같은 콜이 NLS(알림)와 Accessibility(접근성) 양쪽에서 감지되면
 * 먼저 처리된 쪽을 기준으로 나머지를 차단.
 *
 * 우선순위: eventId > orderId > platform+price+store > platform+price
 * TTL: 5분
 *
 * FIX-CROSSSOURCE-PP-GUARD: NLS source + stable key 부재 시 pp 차단 가드.
 * NLS는 orderId/eventId/storeName 없이 pp fallback만으로 차단하지 않음.
 * (같은 가격 다른 콜을 놓칠 위험 방지)
 */
object CrossSourceDedup {

    private const val TAG = "CrossSourceDedup"
    const val TTL_MS = 300_000L // 5분

    const val SOURCE_NLS = "nls"
    const val SOURCE_A11Y = "a11y"

    private val processedKeys = ConcurrentHashMap<String, Long>()

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

        // 높은 우선순위부터 체크
        if (!eventId.isNullOrBlank()) {
            val ts = processedKeys["eid:$eventId"]
            if (ts != null && now - ts < TTL_MS) {
                OtwFileLogger.log(TAG, "BLOCK source=$source keyType=eid platform=$platform price=$price")
                return true
            }
        }
        if (!orderId.isNullOrBlank()) {
            val ts = processedKeys["oid:$orderId"]
            if (ts != null && now - ts < TTL_MS) {
                OtwFileLogger.log(TAG, "BLOCK source=$source keyType=oid platform=$platform price=$price")
                return true
            }
        }
        if (!storeName.isNullOrBlank()) {
            val ts = processedKeys["pps:$platform:$price:$storeName"]
            if (ts != null && now - ts < TTL_MS) {
                OtwFileLogger.log(TAG, "BLOCK source=$source keyType=pps platform=$platform price=$price store=$storeName")
                return true
            }
        }

        // FIX-CROSSSOURCE-PP-GUARD: pp fallback 차단 가드
        // NLS에서 stable key(eventId/orderId/storeName) 없이 pp만 매칭 → 차단 X
        // (같은 가격 다른 콜을 놓칠 위험 방지)
        val hasStableKey = !eventId.isNullOrBlank() || !orderId.isNullOrBlank() || !storeName.isNullOrBlank()
        if (source == SOURCE_NLS && !hasStableKey) {
            // NLS + stable key 없음 → pp fallback 사용 안 함
            return false
        }

        val ts = processedKeys["pp:$platform:$price"]
        if (ts != null && now - ts < TTL_MS) {
            OtwFileLogger.log(TAG, "BLOCK source=$source keyType=pp platform=$platform price=$price")
            return true
        }
        return false
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        processedKeys.entries.removeAll { now - it.value > TTL_MS }
    }

    fun reset() { processedKeys.clear() }
}
