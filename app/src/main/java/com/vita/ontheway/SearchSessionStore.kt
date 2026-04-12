package com.vita.ontheway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────
// SearchSessionStore v1.1
// 3단계 기록 타이밍:
// 1) 앱 실행 시 → ensureActiveSession()  ← create() 대신 사용
// 2) 콜 감지/수락 시 → updateFirstCall() / updateAccepted()
// 3) 완료 시 → complete()
//
// 핵심 원칙:
// - active session이 있으면 새로 만들지 않는다
// - firstCallAt / firstAcceptedCallAt은 1회만 기록
// - 수락 후 중복 덮어쓰기 금지
// ──────────────────────────────────────────
object SearchSessionStore {

    private const val PREF_NAME    = "ontheway_search_sessions"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_EVENTS   = "call_events"
    private const val MAX_SESSIONS = 300
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // ══════════════════════════════════════
    // 1. active session 보장
    // 이미 SEARCHING 상태 세션이 있으면 그것을 반환
    // 없을 때만 새 세션 생성
    // MainActivity.onCreate()에서 호출
    // ══════════════════════════════════════
    fun ensureActiveSession(context: Context, startLocation: String = ""): SearchSession {
        val active = getActiveSession(context)
        if (active != null) {
            android.util.Log.d("SearchSession", "기존 세션 유지: ${active.sessionId}")
            return active
        }
        val session = SearchSession(startLocation = startLocation)
        val list = loadAll(context).toMutableList()
        list.add(session)
        if (list.size > MAX_SESSIONS) list.removeAt(0)
        saveAll(context, list)
        android.util.Log.d("SearchSession", "새 세션 시작: ${session.sessionId}")
        return session
    }

    // ══════════════════════════════════════
    // 2. 첫 콜 감지 시 (1회만 기록)
    // firstCallAt이 비어 있을 때만 저장 — 중복 기록 금지
    // OnTheWayService.kt 콜 감지 지점에서 호출
    // ══════════════════════════════════════
    fun updateFirstCall(context: Context, sessionId: String, price: Int) {
        update(context, sessionId) { s ->
            if (s.firstCallAt == null) {
                android.util.Log.d("SearchSession", "첫 콜 감지 기록: ${price}원")
                s.copy(firstCallAt = System.currentTimeMillis(), firstCallPrice = price)
            } else {
                s // 이미 기록됨 → 무시
            }
        }
    }

    // ══════════════════════════════════════
    // 2. 콜 수락 시 (첫 수락만 기록)
    // firstAcceptedCallAt이 비어 있을 때만 저장 — 중복 덮어쓰기 방지
    // 콜 수락 처리 로직이 실행되는 지점에서 호출
    // ══════════════════════════════════════
    fun updateAccepted(
        context: Context,
        sessionId: String,
        callId: String,
        price: Int,
        distance: Double = 0.0,
        direction: Double = 0.0,
        isUrgent: Boolean = false,
        recommendationSessionId: String? = null
    ) {
        update(context, sessionId) { s ->
            if (s.firstAcceptedCallAt != null) {
                android.util.Log.d("SearchSession", "이미 수락된 세션 — 중복 기록 방지")
                return@update s
            }
            android.util.Log.d("SearchSession", "첫 수락 기록: ${price}원 / urgent=$isUrgent")
            s.copy(
                firstAcceptedCallAt     = System.currentTimeMillis(),
                acceptedCallId          = callId,
                acceptedCallPrice       = price,
                acceptedCallDistance    = distance,
                acceptedCallDirection   = direction,
                isUrgentAccepted        = isUrgent,
                recommendationSessionId = recommendationSessionId,
                status                  = SearchStatus.ACCEPTED
            )
        }
    }

    // ══════════════════════════════════════
    // 3. 완료 시
    // ══════════════════════════════════════
    fun complete(
        context: Context,
        sessionId: String,
        totalEarnedWon: Int,
        endLocation: String = "",
        totalMovedDistanceKm: Double = 0.0,
        idleMinutes: Int = 0
    ) {
        update(context, sessionId) { s ->
            s.copy(
                endedAt              = System.currentTimeMillis(),
                endLocation          = endLocation,
                totalEarnedWon       = totalEarnedWon,
                totalMovedDistanceKm = totalMovedDistanceKm,
                idleMinutes          = idleMinutes,
                status               = SearchStatus.COMPLETED
            )
        }
        // DailySummary 자동 갱신
        saveDailySummary(context)
    }

    // ══════════════════════════════════════
    // 콜 카운터 증가
    // ══════════════════════════════════════
    fun incrementCallsReceived(context: Context, sessionId: String, count: Int) {
        update(context, sessionId) { s ->
            s.copy(callsReceived = s.callsReceived + count)
        }
    }

    fun incrementCallsRejected(context: Context, sessionId: String) {
        update(context, sessionId) { s ->
            android.util.Log.d("SearchSession", "콜 거절 기록: rejected=${s.callsRejected + 1}")
            s.copy(callsRejected = s.callsRejected + 1)
        }
    }

    fun incrementCallsTimeout(context: Context, sessionId: String) {
        update(context, sessionId) { s ->
            android.util.Log.d("SearchSession", "콜 타임아웃 기록: timeout=${s.callsTimeout + 1}")
            s.copy(callsTimeout = s.callsTimeout + 1)
        }
    }

    // ══════════════════════════════════════
    // 취소
    // ══════════════════════════════════════
    fun cancel(context: Context, sessionId: String) {
        update(context, sessionId) { s ->
            s.copy(endedAt = System.currentTimeMillis(), status = SearchStatus.CANCELLED)
        }
    }

    // ══════════════════════════════════════
    // 로드
    // ══════════════════════════════════════
    fun loadAll(context: Context): List<SearchSession> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return try { fromJson(json) } catch (e: Exception) { emptyList() }
    }

    fun loadRecent(context: Context, n: Int = 20): List<SearchSession> =
        loadAll(context).takeLast(n).reversed()

    fun getActiveSession(context: Context): SearchSession? =
        loadAll(context).lastOrNull { it.status == SearchStatus.SEARCHING }

    // ══════════════════════════════════════
    // KPI 7개
    // ══════════════════════════════════════

    // KPI 1: 평균 콜 획득 시간 (분)
    fun getAvgMinutesToAccept(sessions: List<SearchSession>): Double {
        val valid = sessions.mapNotNull { it.minutesToAccept }
        return if (valid.isEmpty()) 0.0 else valid.average()
    }

    // KPI 2: 평균 대기 시간 (분)
    fun getAvgIdleMinutes(sessions: List<SearchSession>): Double {
        val completed = sessions.filter { it.status == SearchStatus.COMPLETED }
        return if (completed.isEmpty()) 0.0 else completed.map { it.idleMinutes }.average()
    }

    // KPI 3: 시간당 평균 수익
    fun getAvgEarnedPerHour(sessions: List<SearchSession>): Int {
        val valid = sessions.filter { it.status == SearchStatus.COMPLETED && it.earnedPerHour > 0 }
        return if (valid.isEmpty()) 0 else valid.map { it.earnedPerHour }.average().toInt()
    }

    // KPI 4: 거리당 평균 수익
    fun getAvgEarnedPerKm(sessions: List<SearchSession>): Int {
        val valid = sessions.filter { it.status == SearchStatus.COMPLETED && it.earnedPerKm > 0 }
        return if (valid.isEmpty()) 0 else valid.map { it.earnedPerKm }.average().toInt()
    }

    // KPI 5: 첫 콜까지 평균 시간 (분)
    fun getAvgMinutesToFirstCall(sessions: List<SearchSession>): Double {
        val valid = sessions.mapNotNull { it.minutesToFirstCall }
        return if (valid.isEmpty()) 0.0 else valid.average()
    }

    // KPI 6: 급송 수락 비율
    fun getUrgentAcceptRate(sessions: List<SearchSession>): Double {
        val accepted = sessions.filter {
            it.status == SearchStatus.ACCEPTED || it.status == SearchStatus.COMPLETED
        }
        return if (accepted.isEmpty()) 0.0
        else (accepted.count { it.isUrgentAccepted }.toDouble() / accepted.size) * 100
    }

    // KPI 7: 핫스팟 효율 (위치별 평균 콜 발생 속도)
    // 데이터 쌓인 후 의미 있어짐 — 지금은 구조만 유지
    fun getHotspotEfficiency(sessions: List<SearchSession>): Map<String, Double> {
        return sessions
            .filter { it.minutesToFirstCall != null && it.startLocation.isNotBlank() }
            .groupBy { it.startLocation.take(3) }
            .mapValues { (_, list) -> list.mapNotNull { it.minutesToFirstCall }.average() }
            .toList().sortedBy { it.second }.toMap()
    }

    // ══════════════════════════════════════
    // 하루 요약
    // ══════════════════════════════════════
    fun getDailySummary(context: Context, date: String? = null): SearchDailySummary? {
        val targetDate = date ?: dayFormat.format(Date())
        val sessions = loadAll(context).filter {
            dayFormat.format(Date(it.startedAt)) == targetDate
        }
        if (sessions.isEmpty()) return null

        return SearchDailySummary(
            date                  = targetDate,
            totalSessions         = sessions.size,
            completedCount        = sessions.count { it.status == SearchStatus.COMPLETED },
            avgMinutesToAccept    = getAvgMinutesToAccept(sessions),
            avgMinutesToFirstCall = getAvgMinutesToFirstCall(sessions),
            avgIdleMinutes        = getAvgIdleMinutes(sessions),
            avgEarnedPerHour      = getAvgEarnedPerHour(sessions),
            avgEarnedPerKm        = getAvgEarnedPerKm(sessions),
            urgentAcceptRate      = getUrgentAcceptRate(sessions),
            totalEarnedWon        = sessions.sumOf { it.totalEarnedWon }
        )
    }

    // ══════════════════════════════════════
    // DailySummary 자동 저장
    // 세션 완료 시 호출 → 오늘 요약 자동 갱신
    // ══════════════════════════════════════
    private const val KEY_DAILY = "daily_summaries"

    fun saveDailySummary(context: Context) {
        val summary = getDailySummary(context) ?: return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DAILY, null)?.let {
            try { JSONArray(it) } catch (e: Exception) { JSONArray() }
        } ?: JSONArray()

        // 같은 날짜 엔트리 제거 후 최신으로 교체
        val updated = JSONArray()
        for (i in 0 until existing.length()) {
            val o = existing.getJSONObject(i)
            if (o.getString("date") != summary.date) updated.put(o)
        }
        // 최대 90일 유지
        while (updated.length() >= 90) updated.remove(0)

        val o = JSONObject()
        o.put("date", summary.date)
        o.put("totalSessions", summary.totalSessions)
        o.put("completedCount", summary.completedCount)
        o.put("avgMinutesToAccept", summary.avgMinutesToAccept)
        o.put("avgMinutesToFirstCall", summary.avgMinutesToFirstCall)
        o.put("avgIdleMinutes", summary.avgIdleMinutes)
        o.put("avgEarnedPerHour", summary.avgEarnedPerHour)
        o.put("avgEarnedPerKm", summary.avgEarnedPerKm)
        o.put("urgentAcceptRate", summary.urgentAcceptRate)
        o.put("totalEarnedWon", summary.totalEarnedWon)
        updated.put(o)

        prefs.edit().putString(KEY_DAILY, updated.toString()).apply()
        android.util.Log.d("SearchSession", "DailySummary 자동 저장: ${summary.date}")
    }

    fun loadDailySummaries(context: Context): List<SearchDailySummary> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DAILY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SearchDailySummary(
                    date                  = o.getString("date"),
                    totalSessions         = o.getInt("totalSessions"),
                    completedCount        = o.getInt("completedCount"),
                    avgMinutesToAccept    = o.getDouble("avgMinutesToAccept"),
                    avgMinutesToFirstCall = o.getDouble("avgMinutesToFirstCall"),
                    avgIdleMinutes        = o.getDouble("avgIdleMinutes"),
                    avgEarnedPerHour      = o.getInt("avgEarnedPerHour"),
                    avgEarnedPerKm        = o.getInt("avgEarnedPerKm"),
                    urgentAcceptRate      = o.getDouble("urgentAcceptRate"),
                    totalEarnedWon        = o.getInt("totalEarnedWon")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ══════════════════════════════════════
    // data_quality 계산 (0.0 ~ 1.0)
    // 세션 필드 완성도 기반
    // ══════════════════════════════════════
    fun getDataQuality(sessions: List<SearchSession>): Double {
        if (sessions.isEmpty()) return 0.0
        val scores = sessions.map { s ->
            var filled = 0
            var total = 7
            if (s.firstCallAt != null) filled++
            if (s.firstAcceptedCallAt != null) filled++
            if (s.acceptedCallPrice != null && s.acceptedCallPrice > 0) filled++
            if (s.totalEarnedWon > 0) filled++
            if (s.callsReceived > 0) filled++
            if (s.endedAt != null) filled++
            if (s.status == SearchStatus.COMPLETED || s.status == SearchStatus.ACCEPTED) filled++
            filled.toDouble() / total
        }
        return scores.average()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ══════════════════════════════════════
    // 내부 유틸
    // ══════════════════════════════════════
    private fun update(context: Context, sessionId: String, transform: (SearchSession) -> SearchSession) {
        val list = loadAll(context).toMutableList()
        val idx  = list.indexOfFirst { it.sessionId == sessionId }
        if (idx >= 0) { list[idx] = transform(list[idx]); saveAll(context, list) }
    }

    private fun saveAll(context: Context, sessions: List<SearchSession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            val o = JSONObject()
            o.put("sessionId", s.sessionId)
            o.put("startedAt", s.startedAt)
            o.put("startLocation", s.startLocation)
            o.put("firstCallAt", s.firstCallAt ?: -1L)
            o.put("firstCallPrice", s.firstCallPrice ?: -1)
            o.put("firstAcceptedCallAt", s.firstAcceptedCallAt ?: -1L)
            o.put("acceptedCallId", s.acceptedCallId ?: "")
            o.put("acceptedCallPrice", s.acceptedCallPrice ?: -1)
            o.put("acceptedCallDistance", s.acceptedCallDistance ?: -1.0)
            o.put("acceptedCallDirection", s.acceptedCallDirection ?: -1.0)
            o.put("isUrgentAccepted", s.isUrgentAccepted)
            o.put("endedAt", s.endedAt ?: -1L)
            o.put("endLocation", s.endLocation)
            o.put("totalEarnedWon", s.totalEarnedWon)
            o.put("totalMovedDistanceKm", s.totalMovedDistanceKm)
            o.put("idleMinutes", s.idleMinutes)
            o.put("callsReceived", s.callsReceived)
            o.put("callsRejected", s.callsRejected)
            o.put("callsTimeout", s.callsTimeout)
            o.put("recommendationSessionId", s.recommendationSessionId ?: "")
            o.put("status", s.status.value)
            arr.put(o)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    private fun fromJson(json: String): List<SearchSession> {
        val arr  = JSONArray(json)
        val list = mutableListOf<SearchSession>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(SearchSession(
                sessionId               = o.getString("sessionId"),
                startedAt               = o.getLong("startedAt"),
                startLocation           = o.optString("startLocation", ""),
                firstCallAt             = o.getLong("firstCallAt").takeIf { it != -1L },
                firstCallPrice          = o.getInt("firstCallPrice").takeIf { it != -1 },
                firstAcceptedCallAt     = o.getLong("firstAcceptedCallAt").takeIf { it != -1L },
                acceptedCallId          = o.getString("acceptedCallId").ifEmpty { null },
                acceptedCallPrice       = o.getInt("acceptedCallPrice").takeIf { it != -1 },
                acceptedCallDistance    = o.getDouble("acceptedCallDistance").takeIf { it != -1.0 },
                acceptedCallDirection   = o.getDouble("acceptedCallDirection").takeIf { it != -1.0 },
                isUrgentAccepted        = o.getBoolean("isUrgentAccepted"),
                endedAt                 = o.getLong("endedAt").takeIf { it != -1L },
                endLocation             = o.optString("endLocation", ""),
                totalEarnedWon          = o.getInt("totalEarnedWon"),
                totalMovedDistanceKm    = o.getDouble("totalMovedDistanceKm"),
                idleMinutes             = o.getInt("idleMinutes"),
                callsReceived           = o.optInt("callsReceived", 0),
                callsRejected           = o.optInt("callsRejected", 0),
                callsTimeout            = o.optInt("callsTimeout", 0),
                recommendationSessionId = o.getString("recommendationSessionId").ifEmpty { null },
                status                  = SearchStatus.values().firstOrNull {
                    it.value == o.getString("status")
                } ?: SearchStatus.SEARCHING
            ))
        }
        return list
    }
}

// ──────────────────────────────────────────
// SearchDailySummary
// ──────────────────────────────────────────
data class SearchDailySummary(
    val date: String,
    val totalSessions: Int,
    val completedCount: Int,
    val avgMinutesToAccept: Double,
    val avgMinutesToFirstCall: Double,
    val avgIdleMinutes: Double,
    val avgEarnedPerHour: Int,
    val avgEarnedPerKm: Int,
    val urgentAcceptRate: Double,
    val totalEarnedWon: Int
) {
    fun toDisplayText(): String = """
        ━━━━━━━━━━━━━━━━━━━━━━━━
        $date 수익 운영 요약
        ━━━━━━━━━━━━━━━━━━━━━━━━
        총 세션:       ${totalSessions}회
        완료:          ${completedCount}회
        콜 획득 시간:  ${"%.1f".format(avgMinutesToAccept)}분
        첫 콜까지:     ${"%.1f".format(avgMinutesToFirstCall)}분
        평균 대기:     ${"%.0f".format(avgIdleMinutes)}분
        시간당 수익:   ${avgEarnedPerHour.toFormattedWon()}
        거리당 수익:   ${avgEarnedPerKm.toFormattedWon()}
        오늘 총 수익:  ${totalEarnedWon.toFormattedWon()}
        ━━━━━━━━━━━━━━━━━━━━━━━━
    """.trimIndent()
}
