package com.vita.ontheway

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 시뮬레이션 시급 계산.
 *
 * 데이터 소스: CallLogDb (shadow_verdict == "recommended_accept")
 * 의미: "OnTheWay 추천 100% 따랐다면 시급 얼마"
 */
object SimulationEarnings {

    private const val RECENT_WINDOW_MS = 60 * 60 * 1000L  // 60분
    private const val IDLE_THRESHOLD_MS = 30 * 60 * 1000L // 30분 휴식

    /** 시뮬레이션 체감 시급 (최근 60분). 0 = 데이터 없음 */
    fun getRecentHourlyRate(context: Context): Int {
        val now = System.currentTimeMillis()
        return calcHourlyRate(context, now - RECENT_WINDOW_MS, now)
    }

    /** 시뮬레이션 누적 시급 (오늘). 0 = 데이터 없음 */
    fun getCumulativeHourlyRate(context: Context): Int {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calcHourlyRate(context, cal.timeInMillis, System.currentTimeMillis())
    }

    private fun calcHourlyRate(context: Context, since: Long, until: Long): Int {
        val rows = CallLogDb.get(context).getRecommendedAcceptCalls(since)
            .filter { it.ts <= until }

        if (rows.size < 2) return 0

        val totalPrice = rows.sumOf { it.price }
        val activeSeconds = calcActiveSeconds(rows.map { it.ts })

        if (activeSeconds < 300) return 0 // 5분 미만 = 표본 부족
        return (totalPrice * 3600.0 / activeSeconds).toInt()
    }

    /** 활동 시간 (30분 이상 공백 = 휴식 제외). EarningsTracker 동일 공식. */
    private fun calcActiveSeconds(timestamps: List<Long>): Long {
        if (timestamps.size < 2) return 0
        val sorted = timestamps.sorted()
        var totalMs = 0L
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap < IDLE_THRESHOLD_MS) {
                totalMs += gap
            }
        }
        return TimeUnit.MILLISECONDS.toSeconds(totalMs)
    }
}
