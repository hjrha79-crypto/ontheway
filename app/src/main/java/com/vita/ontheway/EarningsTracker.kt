package com.vita.ontheway

import android.content.Context
import android.util.Log

/**
 * v3.0 수익 트래킹: 수락된 콜의 금액을 누적 추적
 * FilterLog에 "accepted" 마크를 기록하고, 오늘 수락 통계를 계산
 */
object EarningsTracker {

    private const val PREFS = "earnings_tracker"
    private const val TAG = "EarningsTracker"

    data class TodayEarnings(
        val acceptedCount: Int,
        val totalRevenue: Int,
        val hourlyRate: Int,       // 시급 (원/시간)
        val goalProgress: Float    // 0.0 ~ 1.0+
    )

    /** 수락 기록 */
    fun recordAccept(ctx: Context, price: Int, platform: String) {
        if (!AdvancedPrefs.isEarningsTrackingEnabled(ctx)) return

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        val savedDate = prefs.getString("date", "")

        // 날짜 바뀌면 리셋
        if (savedDate != today) {
            prefs.edit()
                .putString("date", today)
                .putInt("count", 1)
                .putInt("total", price)
                .putLong("first_accept", System.currentTimeMillis())
                .putLong("last_accept", System.currentTimeMillis())
                .apply()
        } else {
            val count = prefs.getInt("count", 0) + 1
            val total = prefs.getInt("total", 0) + price
            prefs.edit()
                .putInt("count", count)
                .putInt("total", total)
                .putLong("last_accept", System.currentTimeMillis())
                .apply()
        }

        // FilterLog에도 수락 기록 추가
        FilterLog.recordAccepted(ctx, price, platform)
        Log.d(TAG, "수락 기록: ${price}원 ($platform), 오늘 누적 ${getToday(ctx).totalRevenue}원")

        // 위젯 업데이트
        try { OnTheWayWidget.updateAll(ctx) } catch (e: Exception) { /* 위젯 없으면 무시 */ }
    }

    /** 오늘 수익 통계 */
    fun getToday(ctx: Context): TodayEarnings {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        if (prefs.getString("date", "") != today) {
            return TodayEarnings(0, 0, 0, 0f)
        }

        val count = prefs.getInt("count", 0)
        val total = prefs.getInt("total", 0)
        val firstAccept = prefs.getLong("first_accept", 0)
        val lastAccept = prefs.getLong("last_accept", 0)

        // 시급: 첫 수락 ~ 마지막 수락 시간
        val hourlyRate = if (count >= 2 && lastAccept > firstAccept) {
            val hours = (lastAccept - firstAccept) / 3600000.0
            if (hours >= 0.1) (total / hours).toInt() else 0
        } else 0

        val goal = EarningManager.getGoal(ctx)
        val progress = if (goal > 0) total.toFloat() / goal else 0f

        return TodayEarnings(count, total, hourlyRate, progress)
    }

    /**
     * 체감 시급: 최근 60분 내 ACCEPT 콜 수익 합 / 활동 시간
     * 30분 이상 콜 없는 구간은 분모에서 제외 (휴식)
     * @return 원/시간 (반올림), 데이터 부족 시 -1
     */
    fun getRecentHourlyRate(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600_000L
        val accepts = getTodayAccepts(ctx).filter { it.first >= oneHourAgo }
        if (accepts.isEmpty()) return -1

        val totalRevenue = accepts.sumOf { it.second }
        val activeSeconds = calcActiveSeconds(accepts, oneHourAgo, now)
        if (activeSeconds < 600) return -1  // 10분 미만 → 노이즈

        return Math.round(totalRevenue * 3600.0 / activeSeconds).toInt()
    }

    /**
     * 누적 시급: 오늘 첫 콜 ~ 현재, 30분 이상 휴식 구간 제외
     * @return 원/시간 (반올림), 데이터 부족 시 -1
     */
    fun getCumulativeHourlyRate(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val accepts = getTodayAccepts(ctx)
        if (accepts.isEmpty()) return -1

        val firstTime = accepts.first().first
        val totalRevenue = accepts.sumOf { it.second }
        val activeSeconds = calcActiveSeconds(accepts, firstTime, now)
        if (activeSeconds < 600) return -1  // 10분 미만 → 노이즈

        return Math.round(totalRevenue * 3600.0 / activeSeconds).toInt()
    }

    /** 오늘 ACCEPTED 엔트리를 (timestamp, price) 리스트로 반환 (시간순) */
    private fun getTodayAccepts(ctx: Context): List<Pair<Long, Int>> {
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val entries = FilterLog.getAll(ctx)
        val result = mutableListOf<Pair<Long, Int>>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val ts = e.optLong("ts", 0)
            if (ts < todayStart) continue
            if (e.optString("verdict") != "ACCEPTED") continue
            result.add(ts to e.optInt("price", 0))
        }
        return result.sortedBy { it.first }
    }

    /**
     * 활동 시간 계산: 전체 구간에서 30분 이상 갭(휴식)을 제외
     * @return 활동 초 수
     */
    private fun calcActiveSeconds(
        accepts: List<Pair<Long, Int>>,
        windowStart: Long,
        windowEnd: Long
    ): Long {
        if (accepts.isEmpty()) return 0
        val REST_THRESHOLD = 30 * 60 * 1000L  // 30분

        // 이벤트 시점들: windowStart, 각 accept 시점, windowEnd
        val points = mutableListOf(windowStart)
        accepts.forEach { points.add(it.first) }
        points.add(windowEnd)

        var activeMs = 0L
        for (i in 1 until points.size) {
            val gap = points[i] - points[i - 1]
            if (gap < REST_THRESHOLD) {
                activeMs += gap
            }
            // 30분 이상 갭이면 휴식 → 제외
        }
        return activeMs / 1000
    }

    private fun todayStr() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        .format(java.util.Date())
}
