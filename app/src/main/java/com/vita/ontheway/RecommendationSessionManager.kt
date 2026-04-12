package com.vita.ontheway

// ──────────────────────────────────────────
// RecommendationSessionManager
// 추천 세션 유효성 관리
// ──────────────────────────────────────────

data class RecommendationEntry(
    val callId: String,
    val dropoff: String,
    val price: Int = 0
)

data class ActiveRecommendationSession(
    val recommendations: List<RecommendationEntry> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

object RecommendationSessionManager {

    private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5분
    private var currentSession: ActiveRecommendationSession? = null

    fun startSession(recommendations: List<RecommendationEntry>) {
        currentSession = ActiveRecommendationSession(recommendations = recommendations)
    }

    fun getSession(): ActiveRecommendationSession? = currentSession

    fun isSessionValid(): Boolean {
        val session = currentSession ?: return false
        return System.currentTimeMillis() - session.createdAt < SESSION_TIMEOUT_MS
    }

    fun getExpiredMessage(): String = "추천 세션이 만료되었습니다. 새로 추천해 드릴게요."

    fun clear() {
        currentSession = null
    }
}
