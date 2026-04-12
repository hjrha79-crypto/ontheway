package com.vita.ontheway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────
// DailySummary — 하루 KPI 요약
// ──────────────────────────────────────────
data class DailySummary(
    val date: String,
    val totalCalls: Int,
    val acceptedCount: Int,
    val acceptRate: Double,
    val urgentCount: Int,
    val urgentRate: Double,
    val avgLossWon: Int,
    val avgEarnedWon: Int,       // 실제 평균 수익
    val rank1Accuracy: Double,   // 1위 추천 정확도 (%)
    val badCount: Int,
    val badRate: Double
) {
    fun toDisplayText(): String = """
        ━━━━━━━━━━━━━━━━━━━━━
        $date 하루 요약
        ━━━━━━━━━━━━━━━━━━━━━
        총 콜:        ${totalCalls}건
        추천 따라감:  ${"%.0f".format(acceptRate)}% (${acceptedCount}건)
        1위 정확도:   ${"%.0f".format(rank1Accuracy)}%
        급송 선택:    ${urgentCount}건
        평균 실수익:  ${avgEarnedWon.toFormattedWon()}
        평균 손실:    ${avgLossWon.toFormattedWon()}
        실패율:       ${"%.0f".format(badRate)}%
        ━━━━━━━━━━━━━━━━━━━━━
    """.trimIndent()
}

// ──────────────────────────────────────────
// SessionStore
// 3단계 기록 타이밍:
// 1) 추천 발생 시 → create()
// 2) 사용자 클릭 시 → updateAction()
// 3) 콜 완료 시 → updateEarned()
// ──────────────────────────────────────────
object SessionStore {

    private const val PREF_NAME = "ontheway_sessions"
    private const val KEY_DATA  = "session_list"
    private const val MAX_ITEMS = 500

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // ══ 1단계: 추천 발생 시 세션 생성 ═══════
    fun create(context: Context, session: RecommendationSession) {
        val list = loadAll(context).toMutableList()
        list.removeAll { it.sessionId == session.sessionId } // 중복 방지
        list.add(session)
        if (list.size > MAX_ITEMS) list.removeAt(0)
        persist(context, list)
    }

    // ══ 2단계: 사용자 클릭 시 행동 업데이트 ══
    fun updateAction(
        context: Context,
        sessionId: String,
        userAction: UserAction,
        selectedCall: SessionCall? = null,
        acceptedRank: Int? = null,
        reason: SelectReason = SelectReason.INSTINCT,
        isUrgentAccepted: Boolean = false
    ) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.sessionId == sessionId }
        if (idx < 0) return

        list[idx] = list[idx].copy(
            userAction       = userAction,
            selectedCall     = selectedCall,
            acceptedRank     = acceptedRank,
            reason           = reason,
            isUrgentAccepted = isUrgentAccepted
        )
        persist(context, list)
    }

    // ══ 3단계: 콜 완료 시 실수익 업데이트 ════
    fun updateEarned(
        context: Context,
        sessionId: String,
        actualEarnedWon: Int,
        result: SessionResult = SessionResult.NORMAL
    ) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.sessionId == sessionId }
        if (idx < 0) return

        list[idx] = list[idx].copy(
            actualEarnedWon = actualEarnedWon,
            isCompleted     = true,
            result          = result
        )
        persist(context, list)
    }

    // ══ 전체 로드 ══════════════════════════
    fun loadAll(context: Context): List<RecommendationSession> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_DATA, null) ?: return emptyList()
        return try { fromJson(json) } catch (e: Exception) { emptyList() }
    }

    fun loadRecent(context: Context, n: Int = 20): List<RecommendationSession> =
        loadAll(context).takeLast(n).reversed()

    // ══ KPI 계산 ═══════════════════════════

    // 추천 따라간 비율
    fun getAcceptRate(sessions: List<RecommendationSession>): Double {
        if (sessions.isEmpty()) return 0.0
        return (sessions.count { it.userAction == UserAction.ACCEPT }.toDouble() / sessions.size) * 100
    }

    // 급송 선택 비율
    fun getUrgentRate(sessions: List<RecommendationSession>): Double {
        if (sessions.isEmpty()) return 0.0
        return (sessions.count { it.isUrgent }.toDouble() / sessions.size) * 100
    }

    // 평균 loss_won
    fun getAverageLossWon(sessions: List<RecommendationSession>): Int {
        if (sessions.isEmpty()) return 0
        return sessions.sumOf { it.lossWon } / sessions.size
    }

    // 실패율
    fun getBadRate(sessions: List<RecommendationSession>): Double {
        if (sessions.isEmpty()) return 0.0
        return (sessions.count { it.result == SessionResult.BAD }.toDouble() / sessions.size) * 100
    }

    // ★ 평균 실수익 (완료된 콜만)
    fun getAvgEarnedWon(sessions: List<RecommendationSession>): Int {
        val completed = sessions.filter { it.isCompleted }
        if (completed.isEmpty()) return 0
        return completed.sumOf { it.actualEarnedWon } / completed.size
    }

    // ★ 1위 추천 정확도
    // = 1위를 선택했을 때 result가 GOOD인 비율
    fun getRank1Accuracy(sessions: List<RecommendationSession>): Double {
        val rank1Sessions = sessions.filter { it.acceptedRank == 1 }
        if (rank1Sessions.isEmpty()) return 0.0
        val good = rank1Sessions.count { it.result == SessionResult.GOOD }
        return (good.toDouble() / rank1Sessions.size) * 100
    }

    // ══ 하루 요약 ══════════════════════════
    fun getDailySummary(context: Context, date: String? = null): DailySummary? {
        val targetDate = date ?: dayFormat.format(Date())
        val sessions = loadAll(context).filter {
            dayFormat.format(Date(it.timestamp)) == targetDate
        }
        if (sessions.isEmpty()) return null

        val total    = sessions.size
        val accepted = sessions.count { it.userAction == UserAction.ACCEPT }
        val urgent   = sessions.count { it.isUrgent }
        val bad      = sessions.count { it.result == SessionResult.BAD }

        return DailySummary(
            date          = targetDate,
            totalCalls    = total,
            acceptedCount = accepted,
            acceptRate    = (accepted.toDouble() / total) * 100,
            urgentCount   = urgent,
            urgentRate    = (urgent.toDouble() / total) * 100,
            avgLossWon    = getAverageLossWon(sessions),
            avgEarnedWon  = getAvgEarnedWon(sessions),
            rank1Accuracy = getRank1Accuracy(sessions),
            badCount      = bad,
            badRate       = (bad.toDouble() / total) * 100
        )
    }

    // ══ 초기화 ═════════════════════════════
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DATA).apply()
    }

    // ══ JSON 직렬화 ════════════════════════
    private fun persist(context: Context, sessions: List<RecommendationSession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            val o = JSONObject()
            o.put("sessionId", s.sessionId)
            o.put("timestamp", s.timestamp)
            o.put("userAction", s.userAction.value)
            o.put("acceptedRank", s.acceptedRank ?: -1)
            o.put("reason", s.reason.value)
            o.put("result", s.result.value)
            o.put("isUrgent", s.isUrgent)
            o.put("isUrgentAccepted", s.isUrgentAccepted)
            o.put("expectedWon", s.expectedWon)
            o.put("actualEarnedWon", s.actualEarnedWon)
            o.put("isCompleted", s.isCompleted)
            o.put("lossWon", s.lossWon)

            // 추천 콜
            o.put("rec_id", s.recommendedCall.id)
            o.put("rec_price", s.recommendedCall.price)
            o.put("rec_distance", s.recommendedCall.distance)
            o.put("rec_direction", s.recommendedCall.direction)
            o.put("rec_isUrgent", s.recommendedCall.isUrgent)
            o.put("rec_score", s.recommendedCall.score)

            // 선택 콜
            s.selectedCall?.let { sc ->
                o.put("sel_id", sc.id)
                o.put("sel_price", sc.price)
                o.put("sel_distance", sc.distance)
                o.put("sel_direction", sc.direction)
                o.put("sel_isUrgent", sc.isUrgent)
            }
            arr.put(o)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA, arr.toString()).apply()
    }

    private fun fromJson(json: String): List<RecommendationSession> {
        val arr  = JSONArray(json)
        val list = mutableListOf<RecommendationSession>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val rec = SessionCall(
                id        = o.getString("rec_id"),
                price     = o.getInt("rec_price"),
                distance  = o.getDouble("rec_distance"),
                direction = o.getDouble("rec_direction"),
                isUrgent  = o.getBoolean("rec_isUrgent"),
                score     = o.optInt("rec_score", 0)
            )
            val sel = if (o.has("sel_id")) SessionCall(
                id        = o.getString("sel_id"),
                price     = o.getInt("sel_price"),
                distance  = o.getDouble("sel_distance"),
                direction = o.getDouble("sel_direction"),
                isUrgent  = o.getBoolean("sel_isUrgent")
            ) else null

            val rank = o.optInt("acceptedRank", -1).let { if (it == -1) null else it }

            list.add(RecommendationSession(
                sessionId        = o.getString("sessionId"),
                timestamp        = o.getLong("timestamp"),
                recommendedCall  = rec,
                selectedCall     = sel,
                userAction       = UserAction.values().first { it.value == o.getString("userAction") },
                acceptedRank     = rank,
                reason           = SelectReason.values().first { it.value == o.getString("reason") },
                result           = SessionResult.values().first { it.value == o.getString("result") },
                isUrgent         = o.getBoolean("isUrgent"),
                isUrgentAccepted = o.optBoolean("isUrgentAccepted", false),
                expectedWon      = o.optInt("expectedWon", 0),
                actualEarnedWon  = o.optInt("actualEarnedWon", 0),
                isCompleted      = o.optBoolean("isCompleted", false)
            ))
        }
        return list
    }
}
