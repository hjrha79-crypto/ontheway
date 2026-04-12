package com.vita.ontheway

import java.util.UUID

// ──────────────────────────────────────────
// OnTheWay 결과 로그 시스템
// 핵심 원칙: 추천 로그가 아니라 결과 로그를 쌓는다
// ──────────────────────────────────────────

// ── 콜 정보 ───────────────────────────────
data class SessionCall(
    val id: String,
    val price: Int,
    val distance: Double,
    val direction: Double,
    val isUrgent: Boolean,
    val score: Int = 0
)

// ── 사용자 행동 ────────────────────────────
enum class UserAction(val value: String) {
    ACCEPT("accept"),   // 이 콜 실행
    SKIP("skip"),       // 건너뛰기
    CANCEL("cancel")    // 취소
}

// ── 선택 이유 ──────────────────────────────
enum class SelectReason(val value: String) {
    PRICE("price"),
    DISTANCE("distance"),
    DIRECTION("direction"),
    URGENT("urgent"),
    INSTINCT("instinct")
}

// ── 결과 평가 ──────────────────────────────
enum class SessionResult(val value: String) {
    GOOD("good"),
    NORMAL("normal"),
    BAD("bad")
}

// ──────────────────────────────────────────
// RecommendationSession — 결과까지 포함한 단위
// ──────────────────────────────────────────
data class RecommendationSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),

    // 추천 정보
    val recommendedCall: SessionCall,
    val allCalls: List<SessionCall> = emptyList(), // 당시 전체 콜 리스트

    // 사용자 선택
    val selectedCall: SessionCall? = null,
    val userAction: UserAction = UserAction.SKIP,
    val acceptedRank: Int? = null,           // 몇 순위를 선택했는가 (1,2,3)
    val reason: SelectReason = SelectReason.INSTINCT,

    // 급송 관련
    val isUrgent: Boolean = false,
    val isUrgentAccepted: Boolean = false,   // 급송 콜을 수락했는가

    // 수익
    val expectedWon: Int = 0,                // 추천 콜 금액 (예상)
    val actualEarnedWon: Int = 0,            // 실제 완료 후 수익

    // 완료 여부
    val isCompleted: Boolean = false,        // 콜 완료됐는가

    // 결과 평가
    val result: SessionResult = SessionResult.NORMAL
) {
    // ── 자동 계산 필드 ─────────────────────

    // loss_won: 선택한 콜 - 추천 콜 (양수 = 더 좋은 선택)
    val lossWon: Int get() =
        (selectedCall?.price ?: 0) - recommendedCall.price

    // 추천 따랐는가
    val acceptedRecommended: Boolean get() =
        userAction == UserAction.ACCEPT && acceptedRank == 1

    // 실제 수익 vs 예상 수익 차이
    val earnedDiff: Int get() =
        if (isCompleted) actualEarnedWon - expectedWon else 0
}
