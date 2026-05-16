package com.vita.ontheway

import com.vita.ontheway.kpi.KpiCalculator
import com.vita.ontheway.kpi.KpiGateEvaluator
import org.junit.Assert.*
import org.junit.Test

/**
 * KPI-1: KpiCalculator + KpiGateEvaluator 단위 테스트.
 */
class KpiCalculatorTest {

    // ── false_accept_rate ──

    @Test
    fun `false_accept_rate 정상 계산`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(confirmed = 95, rejectedFalse = 5),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData()
        )
        assertNotNull(summary.falseAcceptRate)
        assertEquals(0.05, summary.falseAcceptRate!!, 0.001)
    }

    @Test
    fun `false_accept_rate 0건 → null`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(confirmed = 0, rejectedFalse = 0),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData()
        )
        assertNull(summary.falseAcceptRate)
    }

    // ── revenue_bubble_rate ──

    @Test
    fun `revenue_bubble_rate 37% 거품`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData(screenRevenue = 137000, actualRevenue = 100000)
        )
        assertNotNull(summary.revenueBubbleRate)
        assertEquals(0.37, summary.revenueBubbleRate!!, 0.001)
    }

    @Test
    fun `revenue_bubble_rate 실측 0 → null`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData(screenRevenue = 50000, actualRevenue = 0)
        )
        assertNull(summary.revenueBubbleRate)
    }

    // ── missing_call_rate ──

    @Test
    fun `missing_call_rate 20% 미감지`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(detectedCalls = 80, rawNlsCalls = 100),
            KpiCalculator.RevenueData()
        )
        assertNotNull(summary.missingCallRate)
        assertEquals(0.20, summary.missingCallRate!!, 0.001)
    }

    // ── coupang_accept_detection_rate ──

    @Test
    fun `coupang_accept_detection_rate 85%`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(coupangConfirmed = 17, coupangActual = 20),
            KpiCalculator.RevenueData()
        )
        assertNotNull(summary.coupangAcceptDetectionRate)
        assertEquals(0.85, summary.coupangAcceptDetectionRate!!, 0.001)
    }

    // ── recommendation_coverage ──

    @Test
    fun `recommendation_coverage 90%`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(detectedCalls = 100, recommendedCalls = 90),
            KpiCalculator.RevenueData()
        )
        assertEquals(0.90, summary.recommendationCoverage!!, 0.001)
    }

    // ── recommendation_latency_ms_p50 ──

    @Test
    fun `latency p50 계산`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData(),
            KpiCalculator.LatencyData(listOf(100, 200, 300, 400, 500))
        )
        assertEquals(300, summary.recommendationLatencyP50)
    }

    // ── unknown_rate ──

    @Test
    fun `unknown_rate 계산`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(confirmed = 90, unknown = 10),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData()
        )
        assertEquals(0.10, summary.unknownRate!!, 0.001)
    }

    // ── drive_time_bubble_rate ──

    @Test
    fun `drive_time_bubble_rate 97% 거품`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData(screenDriveMinutes = 197, actualDriveMinutes = 100)
        )
        assertEquals(0.97, summary.driveTimeBubbleRate!!, 0.001)
    }

    // ── KpiGateEvaluator ──

    @Test
    fun `gate 통과 — 모든 조건 충족`() {
        val result = KpiGateEvaluator.evaluateDetailed(
            falseAcceptRate = 0.03,
            revenueBubbleRate = 0.08,
            missingCallRate = 0.10,
            coupangAcceptDetectionRate = 0.85,
            sampleSize = 120
        )
        assertTrue(result.pass)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `gate 실패 — sample_size 부족`() {
        val result = KpiGateEvaluator.evaluateDetailed(
            falseAcceptRate = 0.02,
            revenueBubbleRate = 0.05,
            missingCallRate = 0.10,
            coupangAcceptDetectionRate = 0.90,
            sampleSize = 50
        )
        assertFalse(result.pass)
        assertTrue(result.failures.any { "sample_size" in it })
    }

    @Test
    fun `gate 실패 — false_accept_rate 초과`() {
        val result = KpiGateEvaluator.evaluateDetailed(
            falseAcceptRate = 0.08,
            revenueBubbleRate = 0.05,
            missingCallRate = 0.10,
            coupangAcceptDetectionRate = 0.85,
            sampleSize = 120
        )
        assertFalse(result.pass)
        assertTrue(result.failures.any { "false_accept_rate" in it })
    }

    @Test
    fun `gate 실패 — revenue_bubble_rate 초과`() {
        val result = KpiGateEvaluator.evaluateDetailed(
            falseAcceptRate = 0.03,
            revenueBubbleRate = 0.37,
            missingCallRate = 0.10,
            coupangAcceptDetectionRate = 0.85,
            sampleSize = 120
        )
        assertFalse(result.pass)
        assertTrue(result.failures.any { "revenue_bubble_rate" in it })
    }

    @Test
    fun `gate — null KPI는 차단 안 함`() {
        val result = KpiGateEvaluator.evaluateDetailed(
            falseAcceptRate = null,
            revenueBubbleRate = null,
            missingCallRate = null,
            coupangAcceptDetectionRate = null,
            sampleSize = 120
        )
        assertTrue(result.pass)
    }

    @Test
    fun `gate — 복수 실패 사유`() {
        val result = KpiGateEvaluator.evaluateDetailed(
            falseAcceptRate = 0.10,
            revenueBubbleRate = 0.50,
            missingCallRate = 0.30,
            coupangAcceptDetectionRate = 0.50,
            sampleSize = 30
        )
        assertFalse(result.pass)
        assertEquals(5, result.failures.size)
    }

    // ── 통합: calculate → gate_pass ──

    @Test
    fun `calculate gate_pass 통합`() {
        val summary = KpiCalculator.calculate(
            "2026-05-11",
            KpiCalculator.AcceptCounts(confirmed = 97, rejectedFalse = 3),
            KpiCalculator.DetectionCounts(detectedCalls = 90, rawNlsCalls = 100,
                recommendedCalls = 85, coupangConfirmed = 18, coupangActual = 20),
            KpiCalculator.RevenueData(screenRevenue = 105000, actualRevenue = 100000)
        )
        assertEquals(0.03, summary.falseAcceptRate!!, 0.001)
        assertEquals(0.05, summary.revenueBubbleRate!!, 0.001)
        assertTrue(summary.gatePass)
    }

    // ── 5/11 데이터 시뮬 ──

    @Test
    fun `5-11 시뮬 — 거품률 37% 낮 + 230% 저녁`() {
        // 낮: 실측 29,600원 → 화면 40,400원 (+36.5%)
        val day = KpiCalculator.calculate(
            "2026-05-11-day",
            KpiCalculator.AcceptCounts(confirmed = 3, rejectedFalse = 1),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData(screenRevenue = 40400, actualRevenue = 29600)
        )
        assertTrue(day.revenueBubbleRate!! > 0.35)
        assertTrue(day.falseAcceptRate!! > 0.20)  // 1/(3+1) = 25%
        assertFalse(day.gatePass)

        // 저녁: 실측 14,400원 → 화면 47,600원 (+230%)
        val evening = KpiCalculator.calculate(
            "2026-05-11-eve",
            KpiCalculator.AcceptCounts(confirmed = 2, rejectedFalse = 5),
            KpiCalculator.DetectionCounts(),
            KpiCalculator.RevenueData(screenRevenue = 47600, actualRevenue = 14400)
        )
        assertTrue(evening.revenueBubbleRate!! > 2.0)
        assertTrue(evening.falseAcceptRate!! > 0.50)
        assertFalse(evening.gatePass)
    }
}
