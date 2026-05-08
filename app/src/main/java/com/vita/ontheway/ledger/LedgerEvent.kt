package com.vita.ontheway.ledger

/**
 * Ledger 이벤트 데이터 클래스 — append-only 원장 레코드.
 *
 * 원칙:
 * - INSERT만 허용, UPDATE/DELETE 금지
 * - 정정은 CORRECTION_ISSUED 이벤트로 append
 * - raw_payload_json = 원본 보존, derived_payload_json = 파싱 결과
 */
data class LedgerEvent(
    val id: Long = 0,
    val ledgerEventId: String,             // UUID, UNIQUE
    val callSessionId: String? = null,     // nullable (RAW 이벤트는 세션 미확정)
    val eventId: String? = null,           // dedup/fingerprint key
    val orderId: String? = null,           // 플랫폼 native ID
    val platform: String,                  // "baemin", "coupang", "system"
    val eventType: LedgerEventType,
    val sourceChannel: String,             // "notification", "accessibility", "internal", "user_action"
    val occurredAtWall: Long,              // System.currentTimeMillis()
    val occurredAtMonotonic: Long = 0,     // SystemClock.elapsedRealtime()
    val identityConfidence: Double = 0.0,  // 0.0~1.0
    val confidence: Double = 0.0,          // 0.0~1.0
    val rawPayloadJson: String? = null,    // 원본 보존
    val derivedPayloadJson: String? = null,// 파싱 결과
    val schemaVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)
