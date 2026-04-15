package com.vita.ontheway

import android.content.Context

/** v3.3 피크 시간 감지: 최근 7일간 시간대별 콜 빈도 분석 */
object PeakDetector {
    private const val PREFS = "peak_detector"

    fun isPeakAutoEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("peak_auto", false)
    fun setPeakAuto(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("peak_auto", v).apply()

    data class PeakStatus(val isPeak: Boolean, val hourlyCount: Int, val threshold: Int, val adjustment: Int)

    /** 현재 시간대가 피크인지 판정 + 금액 조정값 반환 */
    fun getCurrentStatus(ctx: Context): PeakStatus {
        if (!isPeakAutoEnabled(ctx)) return PeakStatus(false, 0, 0, 0)

        val hourlyCounts = getHourlyCounts(ctx)
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val currentCount = hourlyCounts[currentHour]
        val nonZero = hourlyCounts.filter { it > 0 }
        if (nonZero.size < 3) return PeakStatus(false, currentCount, 0, 0)

        val sorted = nonZero.sorted()
        val top20Threshold = sorted[(sorted.size * 0.8).toInt().coerceAtMost(sorted.size - 1)]

        return if (currentCount >= top20Threshold) {
            PeakStatus(true, currentCount, top20Threshold, 500)  // 피크: +500원
        } else {
            PeakStatus(false, currentCount, top20Threshold, -500)  // 비피크: -500원
        }
    }

    /** 최근 7일간 시간대별(0~23시) 콜 수 집계 */
    private fun getHourlyCounts(ctx: Context): IntArray {
        val counts = IntArray(24)
        val entries = FilterLog.getAll(ctx)
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
        val cal = java.util.Calendar.getInstance()

        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val ts = e.optLong("ts", 0)
            if (ts < sevenDaysAgo) continue
            cal.timeInMillis = ts
            counts[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
        }
        return counts
    }

    /** 상태 표시용 텍스트 */
    fun getStatusText(ctx: Context): String {
        val status = getCurrentStatus(ctx)
        if (!isPeakAutoEnabled(ctx)) return ""
        return if (status.isPeak) "피크 타임 (기준 +500원)" else "비피크 타임 (기준 -500원)"
    }
}
