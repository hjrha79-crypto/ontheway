package com.vita.ontheway.ledger

/**
 * Quarantine 사유 — regret_candidate 학습 자격 격리.
 *
 * quarantine 된 데이터:
 * - join_eligible = 0 (regret 학습 제외)
 * - 삭제 X, 보존 O (디버깅 가능)
 * - ledger에 QUARANTINED 이벤트 기록
 */
enum class QuarantineReason {
    /** identity_confidence < 0.5 (콜 식별 불확실) */
    IDENTITY_LOW_CONFIDENCE,

    /** notification vs accessibility 불일치 (가격/플랫폼 모순) */
    SOURCE_CONFLICT,

    /** action_source = FALLBACK (FilterLog 매칭, 신뢰도 낮음) */
    FALLBACK_ACCEPT,

    /** eventId/orderId 중복 후 복구된 레코드 */
    DUPLICATE_RECOVERED,

    /** ACCEPTED 있는데 CALL_DETECTED 없음 */
    ACCEPTED_WITHOUT_DETECTED,

    /** DELIVERY_COMPLETED 있는데 ACCEPTED 없음 */
    DELIVERED_WITHOUT_ACCEPTED,

    /** 5분+ pending이 LOW confidence로 매칭됨 */
    STALE_TIMEOUT
}
