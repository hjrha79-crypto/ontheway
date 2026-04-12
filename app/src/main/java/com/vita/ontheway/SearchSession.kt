package com.vita.ontheway

import java.util.UUID

// ──────────────────────────────────────────
// OnTheWay 수익 운영 시스템
// 핵심: 쓰면 자동으로 데이터가 쌓이는 앱
// ──────────────────────────────────────────

// 세션 상태
enum class SearchStatus(val value: String) {
    SEARCHING("searching"),   // 검색 중
    ACCEPTED("accepted"),     // 콜 수락
    COMPLETED("completed"),   // 완료
    CANCELLED("cancelled")    // 취소
}

// ──────────────────────────────────────────
// SearchSession — 검색 시작부터 완료까지
// RecommendationSession의 상위 개념
// ──────────────────────────────────────────
data class SearchSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val startLocation: String = "",       // 검색 시작 위치 (주소 또는 좌표 문자열)

    // 콜 발생
    val firstCallAt: Long? = null,        // 첫 콜이 뜬 시각
    val firstCallPrice: Int? = null,      // 첫 콜 금액 (참고용)

    // 수락
    val firstAcceptedCallAt: Long? = null,
    val acceptedCallId: String? = null,
    val acceptedCallPrice: Int? = null,
    val acceptedCallDistance: Double? = null,
    val acceptedCallDirection: Double? = null,
    val isUrgentAccepted: Boolean = false,

    // 완료
    val endedAt: Long? = null,
    val endLocation: String = "",
    val totalEarnedWon: Int = 0,
    val totalMovedDistanceKm: Double = 0.0,

    // 대기
    val idleMinutes: Int = 0,

    // 콜 카운터
    val callsReceived: Int = 0,      // 화면에 뜬 총 콜 수
    val callsRejected: Int = 0,      // 사용자가 넘긴 콜 수
    val callsTimeout: Int = 0,       // 응답 없이 만료된 추천 수

    // 연결된 추천 세션
    val recommendationSessionId: String? = null,

    // 상태
    val status: SearchStatus = SearchStatus.SEARCHING
) {
    // ── 자동 계산 필드 ─────────────────────

    // 앱 켜고 첫 콜 뜨기까지 걸린 시간 (분)
    val minutesToFirstCall: Int? get() =
        firstCallAt?.let { ((it - startedAt) / 60000).toInt() }

    // 앱 켜고 콜 수락까지 걸린 시간 (분)
    val minutesToAccept: Int? get() =
        firstAcceptedCallAt?.let { ((it - startedAt) / 60000).toInt() }

    // 총 검색 시간 (분)
    val totalSearchMinutes: Int get() =
        endedAt?.let { ((it - startedAt) / 60000).toInt() } ?: 0

    // 시간당 수익
    val earnedPerHour: Int get() =
        if (totalSearchMinutes > 0) (totalEarnedWon * 60) / totalSearchMinutes else 0

    // 거리당 수익
    val earnedPerKm: Int get() =
        if (totalMovedDistanceKm > 0) (totalEarnedWon / totalMovedDistanceKm).toInt() else 0
}

// ──────────────────────────────────────────
// CallSeenEvent — 콜이 화면에 뜬 순간 기록
// ──────────────────────────────────────────
data class CallSeenEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val seenAt: Long = System.currentTimeMillis(),
    val location: String = "",
    val price: Int,
    val distance: Double,
    val direction: Double,
    val isUrgent: Boolean = false,
    val recommendedRank: Int? = null,   // 이 콜의 추천 순위 (없으면 null)
    val score: Int = 0
)
