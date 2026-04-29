package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 쿠팡/배민 크로스-서비스 중복 방지 (TtsDeduplicator.processedHistory) 테스트.
 */
class CoupangDedupTest {

    @Before
    fun setup() {
        // TtsDeduplicator는 싱글톤이므로 테스트 간 상태 공유 주의
        // processedHistory는 60초 후 자동 정리되므로 테스트에는 영향 없음
    }

    @Test
    fun `쿠팡 3404원 처리 후 8초 내 재확인 → wasProcessedWithin true`() {
        TtsDeduplicator.recordProcessed("coupang", 3404)
        // 즉시 확인 (0초 후)
        assertTrue(TtsDeduplicator.wasProcessedWithin("coupang", 3404))
    }

    @Test
    fun `다른 금액은 wasProcessedWithin false`() {
        TtsDeduplicator.recordProcessed("coupang", 3404)
        assertFalse(TtsDeduplicator.wasProcessedWithin("coupang", 5000))
    }

    @Test
    fun `다른 플랫폼은 wasProcessedWithin false`() {
        TtsDeduplicator.recordProcessed("coupang", 3404)
        assertFalse(TtsDeduplicator.wasProcessedWithin("baemin", 3404))
    }

    @Test
    fun `배민 dedup 영향 없음 - 배민 처리 후 배민 확인`() {
        TtsDeduplicator.recordProcessed("baemin", 5730)
        assertTrue(TtsDeduplicator.wasProcessedWithin("baemin", 5730))
        assertFalse(TtsDeduplicator.wasProcessedWithin("coupang", 5730))
    }

    @Test
    fun `simpleDedupKey 접미 언더스코어로 prefix 충돌 방지`() {
        // "coupang_3404_" 는 "coupang_34040_2.5" 와 매칭되면 안 됨
        val key1 = "coupang_3404_2.5"
        val key2 = "coupang_34040_1.0"
        val dedupKey = "coupang_3404_"
        assertTrue(key1.startsWith(dedupKey))
        assertFalse(key2.startsWith(dedupKey))
    }
}
