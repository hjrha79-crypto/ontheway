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
    fun `기본 10건 반환`() {
        val candidates = (1..20).map { entry(price = it * 1000, callTs = (20 - it).toLong()) }
        val result = ReviewSelector.selectFromCandidates(candidates)
        assertEquals(10, result.size)
    }

    @Test
    fun `limit 지정 시 해당 건수 반환`() {
        val candidates = (1..20).map { entry(price = it * 1000, callTs = (20 - it).toLong()) }
        val result = ReviewSelector.selectFromCandidates(candidates, limit = 5)
        assertEquals(5, result.size)
    }

    @Test
    fun `최신순 유지 (입력 순서 보존)`() {
        val candidates = listOf(
            entry(price = 3000, callTs = 100), // 최신
            entry(price = 5000, callTs = 90),
            entry(price = 8000, callTs = 80)   // 오래됨
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        assertEquals(100L, result[0].callTs)
        assertEquals(90L, result[1].callTs)
        assertEquals(80L, result[2].callTs)
    }

    @Test
    fun `후보가 limit보다 적으면 전체 반환`() {
        val candidates = listOf(
            entry(price = 5000, callTs = 1),
            entry(price = 3000, callTs = 2)
        )
        val result = ReviewSelector.selectFromCandidates(candidates)
        assertEquals(2, result.size)
    }

    @Test
    fun `빈 리스트 시 빈 결과`() {
        val result = ReviewSelector.selectFromCandidates(emptyList())
        assertTrue(result.isEmpty())
    }
}
