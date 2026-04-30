package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSelectorTest {

    private fun entry(
        price: Int, platform: String = "coupang",
        verdict: String = "ACCEPT", verdictMsg: String = "적합",
        callTs: Long = System.currentTimeMillis()
    ) = ReviewEntry(
        id = 0, callTs = callTs, platform = platform,
        price = price, verdict = verdict, verdictMsg = verdictMsg,
        userAction = "UNKNOWN", platformDistanceKm = null
    )

    @Test
    fun `최대 5건만 반환`() {
        val candidates = (1..10).map { entry(price = it * 1000, callTs = it.toLong()) }
        val result = ReviewSelector.selectFromCandidates(candidates)
        assertEquals(5, result.size)
    }

    @Test
    fun `고액 콜 우선 선정 (8000원 이상)`() {
        val candidates = listOf(
            entry(price = 3000, callTs = 1),
            entry(price = 10000, callTs = 2),
            entry(price = 8000, callTs = 3),
            entry(price = 5000, callTs = 4)
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        // 고액 콜이 먼저 와야 함
        assertTrue(result[0].price >= 8000)
        assertTrue(result[1].price >= 8000)
    }

    @Test
    fun `판정 없음 두번째 우선순위`() {
        val candidates = listOf(
            entry(price = 5000, verdict = "UNKNOWN", verdictMsg = "", callTs = 1),
            entry(price = 6000, verdict = "ACCEPT", verdictMsg = "적합", callTs = 2),
            entry(price = 4000, verdict = "", verdictMsg = "7,000원", callTs = 3)
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        // 판정 없음 콜이 나머지보다 먼저
        assertEquals(5000, result[0].price)
        assertEquals(4000, result[1].price)
    }

    @Test
    fun `배민 콜 세번째 우선순위`() {
        val candidates = listOf(
            entry(price = 5000, platform = "coupang", callTs = 1),
            entry(price = 5000, platform = "baemin", callTs = 2)
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        assertEquals("baemin", result[0].platform)
    }

    @Test
    fun `나머지는 금액 높은 순`() {
        val candidates = listOf(
            entry(price = 3000, callTs = 1),
            entry(price = 7000, callTs = 2),
            entry(price = 5000, callTs = 3)
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        assertEquals(7000, result[0].price)
        assertEquals(5000, result[1].price)
        assertEquals(3000, result[2].price)
    }

    @Test
    fun `빈 리스트 시 빈 결과`() {
        val result = ReviewSelector.selectFromCandidates(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `verdictMsg가 금액만이면 판정없음 취급`() {
        val candidates = listOf(
            entry(price = 5000, verdict = "ACCEPT", verdictMsg = "7,000원", callTs = 1),
            entry(price = 6000, verdict = "ACCEPT", verdictMsg = "적합 콜", callTs = 2)
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        // 5000원 콜은 verdictMsg="7,000원" → 판정없음 → 우선순위 높음
        assertEquals(5000, result[0].price)
    }
}
