package com.vita.ontheway.ledger

/**
 * Ledger 이벤트 타입 — 콜 lifecycle 전 구간.
 * append-only 원장의 event_type 필드.
 */
enum class LedgerEventType {
    RAW_ACCESSIBILITY_SEEN,
    RAW_NOTIFICATION_SEEN,
    CALL_DETECTED,
    JUDGMENT_ISSUED,
    DRIVER_ACCEPTED,
    DRIVER_REJECTED,
    TIMEOUT,
    CARD_FINALIZED,
    PICKED_UP,
    DELIVERY_COMPLETED,
    RETURN_STARTED,
    NEXT_CALL_DETECTED,
    IDLE_STARTED,
    SESSION_ENDED,
    ORPHAN_CLASSIFIED,
    CORRECTION_ISSUED,
    QUARANTINED
}
