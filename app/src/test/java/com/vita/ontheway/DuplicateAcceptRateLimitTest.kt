package com.vita.ontheway

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix U: DUPLICATE_ACCEPT_BLOCKED ledger rate-limit 테스트.
 */
class DuplicateAcceptRateLimitTest {

    @Before
    fun setup() {
        AcceptCoordinator.resetForTest()
    }

    @After
    fun teardown() {
        AcceptCoordinator.resetForTest()
    }

    @Test
    fun `a 첫 차단 → 기록 허용`() {
        assertTrue(AcceptCoordinator.shouldLogDuplicate("baemin:sess-1:BAEMIN_PROGRESS"))
    }

    @Test
    fun `b 30초 내 동일 차단 → 기록 차단`() {
        var now = 1_000_000L
        AcceptCoordinator.timeSource = { now }

        val key = "baemin:sess-1:BAEMIN_PROGRESS"
        assertTrue(AcceptCoordinator.shouldLogDuplicate(key))

        now += 30_000  // 30초 후
        assertFalse(AcceptCoordinator.shouldLogDuplicate(key))
    }

    @Test
    fun `c 60초 지난 차단 → 기록 재허용`() {
        var now = 1_000_000L
        AcceptCoordinator.timeSource = { now }

        val key = "coupang:sess-2:COUPANG_PICKUP"
        assertTrue(AcceptCoordinator.shouldLogDuplicate(key))

        now += 61_000  // 61초 후
        assertTrue(AcceptCoordinator.shouldLogDuplicate(key))
    }

    @Test
    fun `d 다른 session 차단 → 별도 기록`() {
        val key1 = "baemin:sess-a:BAEMIN_PROGRESS"
        val key2 = "baemin:sess-b:BAEMIN_PROGRESS"

        assertTrue(AcceptCoordinator.shouldLogDuplicate(key1))
        assertTrue(AcceptCoordinator.shouldLogDuplicate(key2))  // 다른 key → 허용
    }

    @Test
    fun `e 캐시 정리 — 1시간 entry 제거`() {
        var now = 1_000_000L
        AcceptCoordinator.timeSource = { now }

        // 100개 key 등록
        repeat(100) { i ->
            AcceptCoordinator.shouldLogDuplicate("test:sess-$i:SOURCE")
        }

        // 1시간 + 1초 후
        now += 3_601_000

        // 새 key 등록 시 cleanup 트리거 → 이전 100개 제거
        assertTrue(AcceptCoordinator.shouldLogDuplicate("test:new:SOURCE"))
        // 이전 key들은 expire → 다시 허용됨
        assertTrue(AcceptCoordinator.shouldLogDuplicate("test:sess-0:SOURCE"))
    }

    @Test
    fun `f 같은 session 다른 source → 별도 key`() {
        val key1 = "coupang:sess-x:COUPANG_PICKUP"
        val key2 = "coupang:sess-x:FALLBACK"

        assertTrue(AcceptCoordinator.shouldLogDuplicate(key1))
        assertTrue(AcceptCoordinator.shouldLogDuplicate(key2))
    }
}
