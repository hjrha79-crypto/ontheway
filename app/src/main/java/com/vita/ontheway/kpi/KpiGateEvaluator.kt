package com.vita.ontheway.kpi

/**
 * KPI-1: 70.12 진입 게이트 평가.
 *
 * 통과 조건 (모두 충족):
 * - sample_size >= 100
 * - false_accept_rate <= 5%
 * - revenue_bubble_rate <= 10%
 * - missing_call_rate <= 15%
 * - coupang_accept_detection_rate >= 80%
 */
object KpiGateEvaluator {

    const val MIN_SAMPLE_SIZE = 100
    const val MAX_FALSE_ACCEPT_RATE = 0.05
    const val MAX_REVENUE_BUBBLE_RATE = 0.10
    const val MAX_MISSING_CALL_RATE = 0.15
    const val MIN_COUPANG_ACCEPT_RATE = 0.80

    data class GateResult(
        val pass: Boolean,
        val failures: List<String>
    )

    fun evaluate(
        falseAcceptRate: Double?,
        revenueBubbleRate: Double?,
        missingCallRate: Double?,
        coupangAcceptDetectionRate: Double?,
        sampleSize: Int
    ): Boolean {
        return evaluateDetailed(falseAcceptRate, revenueBubbleRate, missingCallRate,
            coupangAcceptDetectionRate, sampleSize).pass
    }

    fun evaluateDetailed(
        falseAcceptRate: Double?,
        revenueBubbleRate: Double?,
        missingCallRate: Double?,
        coupangAcceptDetectionRate: Double?,
        sampleSize: Int
    ): GateResult {
        val failures = mutableListOf<String>()

        if (sampleSize < MIN_SAMPLE_SIZE) {
            failures.add("sample_size=$sampleSize < $MIN_SAMPLE_SIZE")
        }
        if (falseAcceptRate != null && falseAcceptRate > MAX_FALSE_ACCEPT_RATE) {
            failures.add("false_accept_rate=${"%.1f".format(falseAcceptRate * 100)}% > ${(MAX_FALSE_ACCEPT_RATE * 100).toInt()}%")
        }
        if (revenueBubbleRate != null && revenueBubbleRate > MAX_REVENUE_BUBBLE_RATE) {
            failures.add("revenue_bubble_rate=${"%.1f".format(revenueBubbleRate * 100)}% > ${(MAX_REVENUE_BUBBLE_RATE * 100).toInt()}%")
        }
        if (missingCallRate != null && missingCallRate > MAX_MISSING_CALL_RATE) {
            failures.add("missing_call_rate=${"%.1f".format(missingCallRate * 100)}% > ${(MAX_MISSING_CALL_RATE * 100).toInt()}%")
        }
        if (coupangAcceptDetectionRate != null && coupangAcceptDetectionRate < MIN_COUPANG_ACCEPT_RATE) {
            failures.add("coupang_accept_rate=${"%.1f".format(coupangAcceptDetectionRate * 100)}% < ${(MIN_COUPANG_ACCEPT_RATE * 100).toInt()}%")
        }

        // null인 KPI는 데이터 부족 → 게이트 차단 안 함 (sample_size로 제어)
        return GateResult(pass = failures.isEmpty(), failures = failures)
    }
}
