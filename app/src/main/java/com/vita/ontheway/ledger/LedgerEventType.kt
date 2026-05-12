package com.vita.ontheway.ledger

/**
 * Ledger 이벤트 타입 — 콜 lifecycle 전 구간.
 * append-only 원장의 event_type 필드.
 *
 * ACCEPT lifecycle (Fix W):
 * - ACCEPT_CANDIDATE:             detector 감지, 검증 대기 (매출 미누적)
 * - ACCEPT_CONFIRMED:             GPS/state 교차 검증 완료 → 매출 누적
 * - ACCEPT_UNCONFIRMED:           60초 timeout, 신호 없음 (반증 아님, 미확정)
 * - ACCEPT_REJECTED_FALSE:        명시적 반증 (새 콜 탐색 복귀, reject 등)
 * - DRIVER_ACCEPTED:              Fix W 이전 호환용 / handleAccept 즉시 확정 시 (개발자도구 등)
 * - ACCEPT_SUSPECTED:             추정 수락 (notification + insufficient evidence)
 * - DUPLICATE_ACCEPT_BLOCKED:     이미 수락된 session에 확정 차단
 * - DUPLICATE_ACCEPT_SUSPECTED:   이미 수락된 session에 orphan 신호 차단
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
    QUARANTINED,
    DUPLICATE_ACCEPT_BLOCKED,
    DUPLICATE_ACCEPT_SUSPECTED,
    ACCEPT_SUSPECTED,
    ACCEPT_CANDIDATE,
    ACCEPT_CONFIRMED,
    ACCEPT_UNCONFIRMED,
    ACCEPT_REJECTED_FALSE,
    PICKUP_DISTANCE_LATE_UPDATED,
    STALE_PENDING_BLOCKED,
    ORPHAN_ACCEPT
}
