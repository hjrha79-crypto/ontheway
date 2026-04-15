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

    private fun todayStr() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        .format(java.util.Date())
}
