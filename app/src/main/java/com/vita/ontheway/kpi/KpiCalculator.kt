package com.vita.ontheway.kpi

/**
 * KPI-1: 일일 KPI 계산기.
 *
 * 입력: accept lifecycle counts + ground truth + call detection counts
 * 출력: KpiDailySummary
 */
object KpiCalculator {

    data class AcceptCounts(
        val confirmed: Int = 0,
        val rejectedFalse: Int = 0,
        val unconfirmed: Int = 0,
        val unknown: Int = 0
    ) {
        val total get() = confirmed + rejectedFalse + unconfirmed + unknown
    }

    data class DetectionCounts(
        val detectedCalls: Int = 0,
        val rawNlsCalls: Int = 0,
        val recommendedCalls: Int = 0,
        val coupangConfirmed: Int = 0,
        val coupangActual: Int = 0  // ground truth
    )

    data class RevenueData(
        val screenRevenue: Int = 0,    // EarningsTracker 기준
        val actualRevenue: Int = 0,     // ground truth
        val screenDriveMinutes: Int = 0,
        val actualDriveMinutes: Int = 0
    )

    data class LatencyData(
        val latenciesMs: List<Long> = emptyList()
    )

    /**
     * 일일 KPI 계산.
     */
    fun calculate(
        date: String,
        accept: AcceptCounts,
        detection: DetectionCounts,
        revenue: RevenueData,
        latency: LatencyData = LatencyData()
    ): KpiDailySummary {
        // 1. false_accept_rate
        val falseAcceptDenom = accept.confirmed + accept.rejectedFalse
        val falseAcceptRate = if (falseAcceptDenom > 0) {
            accept.rejectedFalse.toDouble() / falseAcceptDenom
        } else null

        // 2. revenue_bubble_rate
        val revenueBubbleRate = if (revenue.actualRevenue > 0) {
            (revenue.screenRevenue - revenue.actualRevenue).toDouble() / revenue.actualRevenue
        } else null

        // 3. missing_call_rate
        val missingCallRate = if (detection.rawNlsCalls > 0) {
            (detection.rawNlsCalls - detection.detectedCalls).toDouble() / detection.rawNlsCalls
        } else null

        // 4. coupang_accept_detection_rate
        val coupangDetRate = if (detection.coupangActual > 0) {
            detection.coupangConfirmed.toDouble() / detection.coupangActual
        } else null

        // 5. recommendation_coverage
        val recCoverage = if (detection.detectedCalls > 0) {
            detection.recommendedCalls.toDouble() / detection.detectedCalls
        } else null

        // 6. recommendation_latency_ms_p50
        val latencyP50 = if (latency.latenciesMs.isNotEmpty()) {
            val sorted = latency.latenciesMs.sorted()
            sorted[sorted.size / 2].toInt()
        } else null

        // 보조: drive_time_bubble_rate
        val driveTimeBubble = if (revenue.actualDriveMinutes > 0) {
            (revenue.screenDriveMinutes - revenue.actualDriveMinutes).toDouble() / revenue.actualDriveMinutes
        } else null

        // 보조: unknown_rate
        val unknownRate = if (accept.total > 0) {
            accept.unknown.toDouble() / accept.total
        } else null

        val sampleSize = accept.total

        val gatePass = KpiGateEvaluator.evaluate(
            falseAcceptRate = falseAcceptRate,
            revenueBubbleRate = revenueBubbleRate,
            missingCallRate = missingCallRate,
            coupangAcceptDetectionRate = coupangDetRate,
            sampleSize = sampleSize
        )

        return KpiDailySummary(
            date = date,
            detectedCalls = detection.detectedCalls,
            rawNlsCalls = detection.rawNlsCalls,
            confirmedCount = accept.confirmed,
            rejectedFalseCount = accept.rejectedFalse,
            unconfirmedCount = accept.unconfirmed,
            falseAcceptRate = falseAcceptRate,
            revenueBubbleRate = revenueBubbleRate,
            driveTimeBubbleRate = driveTimeBubble,
            missingCallRate = missingCallRate,
            coupangAcceptDetectionRate = coupangDetRate,
            recommendationCoverage = recCoverage,
            recommendationLatencyP50 = latencyP50,
            unknownRate = unknownRate,
            sampleSize = sampleSize,
            gatePass = gatePass
        )
    }
}
