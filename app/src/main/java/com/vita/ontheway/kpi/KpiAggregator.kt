package com.vita.ontheway.kpi

import android.content.Context

/**
 * KPI 일/주/월 집계.
 * 일일 KpiDailySummary를 롤링 윈도우로 집계.
 */
object KpiAggregator {

    data class RollingResult(
        val windowDays: Int,
        val totalSampleSize: Int,
        val avgFalseAcceptRate: Double?,
        val avgRevenueBubbleRate: Double?,
        val avgMissingCallRate: Double?,
        val avgCoupangAcceptRate: Double?,
        val gatePass: Boolean
    )

    /**
     * 최근 N일 롤링 집계.
     */
    fun rolling(ctx: Context, days: Int = 7): RollingResult {
        val summaries = KpiMetricStore.getRecentSummaries(ctx, days)
        if (summaries.isEmpty()) {
            return RollingResult(days, 0, null, null, null, null, false)
        }

        val totalSample = summaries.sumOf { it.sampleSize }
        val avgFar = weightedAvg(summaries, { it.falseAcceptRate }, { it.sampleSize })
        val avgRbr = weightedAvg(summaries, { it.revenueBubbleRate }, { it.sampleSize })
        val avgMcr = weightedAvg(summaries, { it.missingCallRate }, { it.sampleSize })
        val avgCar = weightedAvg(summaries, { it.coupangAcceptDetectionRate }, { it.sampleSize })

        val gatePass = KpiGateEvaluator.evaluate(avgFar, avgRbr, avgMcr, avgCar, totalSample)

        return RollingResult(days, totalSample, avgFar, avgRbr, avgMcr, avgCar, gatePass)
    }

    private fun weightedAvg(
        summaries: List<KpiDailySummary>,
        valueFn: (KpiDailySummary) -> Double?,
        weightFn: (KpiDailySummary) -> Int
    ): Double? {
        var totalWeight = 0
        var weightedSum = 0.0
        for (s in summaries) {
            val v = valueFn(s) ?: continue
            val w = weightFn(s)
            if (w <= 0) continue
            weightedSum += v * w
            totalWeight += w
        }
        return if (totalWeight > 0) weightedSum / totalWeight else null
    }
}
