package com.vita.ontheway

// ──────────────────────────────────────────
// RecommendedCall — 추천 카드 UI용 콜 데이터
// RecommendCardView / CallCardMeta에서 사용
// ──────────────────────────────────────────
data class RecommendedCall(
    val rank: Int,
    val pickup: String,
    val dropoff: String,
    val price: Int,
    val distanceKm: Double,
    val score: Int,
    val isUrgent: Boolean = false,
    val reason: String = ""
)
