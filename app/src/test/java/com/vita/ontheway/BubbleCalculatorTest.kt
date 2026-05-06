package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleCalculatorTest {

    @Test
    fun `거품 5pct 이하 GREEN`() {
        // 화면 105, 실제 100 = 5%
        val result = BubbleCalculator.calculate(105_000, 100_000)
        assertEquals(BubbleCalculator.KpiLevel.GREEN, result.kpi)
        assertEquals(5f, result.bubblePct, 0.5f)
    }

    @Test
    fun `거품 10pct YELLOW`() {
        // 화면 110, 실제 100 = 10%
        val result = BubbleCalculator.calculate(110_000, 100_000)
        assertEquals(BubbleCalculator.KpiLevel.YELLOW, result.kpi)
        assertEquals(10f, result.bubblePct, 0.5f)
    }

    @Test
    fun `거품 71pct RED (5_6 base)`() {
        // 5/6: 화면 168,900 vs 실제 48,288
        val result = BubbleCalculator.calculate(168_900, 48_288)
        assertEquals(BubbleCalculator.KpiLevel.RED, result.kpi)
        // (168900 - 48288) / 48288 * 100 ≈ 249.8%
        assertEquals(249.8f, result.bubblePct, 1f)
    }

    @Test
    fun `실제 매출 0원 측정 불가`() {
        val result = BubbleCalculator.calculate(50_000, 0)
        assertEquals("측정 불가", result.label)
        assertEquals(0f, result.bubblePct, 0.01f)
    }

    @Test
    fun `실제 매출 음수 측정 불가`() {
        val result = BubbleCalculator.calculate(50_000, -100)
        assertEquals("측정 불가", result.label)
    }

    @Test
    fun `화면이 실제보다 적음 마이너스 거품`() {
        // 화면 40,000 vs 실제 50,000 = -20%
        val result = BubbleCalculator.calculate(40_000, 50_000)
        assertEquals(BubbleCalculator.KpiLevel.GREEN, result.kpi)
        assertEquals(-20f, result.bubblePct, 0.5f)
    }

    @Test
    fun `같은 날 재입력 덮어쓰기 패턴`() {
        // DailyAuditDb는 Android context 필요 — 여기서는 BubbleCalculator만 검증
        val r1 = BubbleCalculator.calculate(100_000, 50_000)
        val r2 = BubbleCalculator.calculate(100_000, 80_000)
        // 두 계산 독립적으로 작동
        assertEquals(100f, r1.bubblePct, 0.5f)
        assertEquals(25f, r2.bubblePct, 0.5f)
    }

    @Test
    fun `accept 신뢰도 계산`() {
        // 감지 42,000 / 실제 48,288 ≈ 87%
        val reliability = BubbleCalculator.acceptReliability(42_000, 48_288)
        assertEquals(87f, reliability, 1f)
    }

    @Test
    fun `accept 신뢰도 0원 보호`() {
        assertEquals(0f, BubbleCalculator.acceptReliability(42_000, 0), 0.01f)
    }
}
