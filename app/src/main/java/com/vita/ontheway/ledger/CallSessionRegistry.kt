package com.vita.ontheway.ledger

import android.util.Log
import java.util.UUID

/**
 * call_session_id 단일 레지스트리.
 *
 * 역할: orderId / eventId / fingerprint → 동일 call_session_id 매핑.
 * 하나의 콜 lifecycle (RAW_SEEN → DELIVERY_COMPLETED)이 하나의 call_session_id를 공유.
 *
 * ID 체계:
 * - call_session_id: 운행 콜 lifecycle ID (이 레지스트리에서 관리)
 * - event_id: 콜 fingerprint / dedup key (EventIdGenerator에서 생성)
 * - order_id: 플랫폼 native ID (T2CN 등, 신뢰도 최고)
 * - source_event_id: notification/accessibility 원본 ID
 *
 * 원칙:
 * - orderId 일치 → 같은 세션 (가격/가게명 달라도)
 * - eventId 일치 → 같은 세션 (orderId 없을 때)
 * - fingerprint 일치 (TTL 내) → 같은 세션 (둘 다 없을 때)
 * - 모두 불일치 → 새 세션 발급
 */
object CallSessionRegistry {

    private const val TAG = "CallSessionRegistry"
    private const val TTL_MS = 30L * 60 * 1000  // 30분 무활동 만료
    private const val FINGERPRINT_TTL_MS = 5L * 60 * 1000  // 5분

    data class ActiveEntry(
        val callSessionId: String,
        val orderIds: MutableSet<String> = mutableSetOf(),
        val eventIds: MutableSet<String> = mutableSetOf(),
        val fingerprints: MutableSet<String> = mutableSetOf(),
        var lastActivityAt: Long = System.currentTimeMillis(),
        var finalized: Boolean = false,
        var finalizedType: String? = null
    )

    private val entries = mutableListOf<ActiveEntry>()
    private val lock = Any()

    /**
     * call_session_id 조회 또는 생성.
     * 기존 세션 매칭 → 기존 ID 반환. 신규 → UUID 발급.
     */
    fun getOrCreateSessionId(
        eventId: String? = null,
        orderId: String? = null,
        fingerprint: String? = null
    ): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        cleanup(now)

        // 1. orderId 매칭 (최고 우선)
        if (!orderId.isNullOrBlank()) {
            val match = entries.firstOrNull { !it.finalized && orderId in it.orderIds }
            if (match != null) {
                match.lastActivityAt = now
                eventId?.let { match.eventIds.add(it) }
                fingerprint?.let { match.fingerprints.add(it) }
                return match.callSessionId
            }
        }

        // 2. eventId 매칭
        if (!eventId.isNullOrBlank()) {
            val match = entries.firstOrNull { !it.finalized && eventId in it.eventIds }
            if (match != null) {
                match.lastActivityAt = now
                orderId?.let { match.orderIds.add(it) }
                fingerprint?.let { match.fingerprints.add(it) }
                return match.callSessionId
            }
        }

        // 3. fingerprint 매칭 (5분 내)
        if (!fingerprint.isNullOrBlank()) {
            val match = entries.firstOrNull {
                !it.finalized && fingerprint in it.fingerprints &&
                    (now - it.lastActivityAt) < FINGERPRINT_TTL_MS
            }
            if (match != null) {
                match.lastActivityAt = now
                orderId?.let { match.orderIds.add(it) }
                eventId?.let { match.eventIds.add(it) }
                return match.callSessionId
            }
        }

        // 4. 신규 세션
        val entry = ActiveEntry(
            callSessionId = UUID.randomUUID().toString(),
            lastActivityAt = now
        )
        orderId?.let { entry.orderIds.add(it) }
        eventId?.let { entry.eventIds.add(it) }
        fingerprint?.let { entry.fingerprints.add(it) }
        entries.add(entry)

        Log.d(TAG, "신규 세션: ${entry.callSessionId.take(8)} " +
            "(orderId=${orderId ?: "-"}, eventId=${eventId?.take(8) ?: "-"}, fp=${fingerprint?.take(8) ?: "-"})")
        return entry.callSessionId
    }

    /**
     * 세션 종료 표시.
     */
    fun markFinalized(callSessionId: String, type: String) = synchronized(lock) {
        val entry = entries.firstOrNull { it.callSessionId == callSessionId }
        if (entry != null) {
            entry.finalized = true
            entry.finalizedType = type
            Log.d(TAG, "세션 종료: ${callSessionId.take(8)} ($type)")
        }
    }

    /**
     * 현재 활성 세션 ID (가장 최근 미종료).
     */
    fun getActiveSessionId(): String? = synchronized(lock) {
        cleanup(System.currentTimeMillis())
        entries.lastOrNull { !it.finalized }?.callSessionId
    }

    /**
     * 특정 orderId/eventId에 해당하는 세션 ID 조회 (종료 포함).
     */
    fun findSessionId(eventId: String? = null, orderId: String? = null): String? = synchronized(lock) {
        if (!orderId.isNullOrBlank()) {
            entries.firstOrNull { orderId in it.orderIds }?.callSessionId?.let { return it }
        }
        if (!eventId.isNullOrBlank()) {
            entries.firstOrNull { eventId in it.eventIds }?.callSessionId?.let { return it }
        }
        return null
    }

    /**
     * fingerprint 생성 (store + price + platform + 1분 bucket).
     */
    fun buildFingerprint(storeName: String?, price: Int, platform: String): String {
        val store = (storeName ?: "").trim().lowercase().replace(Regex("\\s+"), " ")
        val bucket = System.currentTimeMillis() / 60_000  // 1분 단위
        return "$store|$price|$platform|$bucket"
    }

    private fun cleanup(now: Long) {
        entries.removeAll { (now - it.lastActivityAt) > TTL_MS }
    }

    /** 테스트용 초기화 */
    fun resetForTest() = synchronized(lock) {
        entries.clear()
    }

    /** 디버그: 활성 세션 수 */
    fun activeCount(): Int = synchronized(lock) {
        cleanup(System.currentTimeMillis())
        entries.count { !it.finalized }
    }
}
