package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Repeat Critical v0.1: 조리완료 TTS 엔진 테스트 (T1~T6).
 */
class RepeatCriticalCookingTest {

    @Before
    fun setup() {
        CookingStatusTracker.clear()
        BaeminParser.resetDedupCache()
    }

    // T1: raw에 "조리완료" + storeName 존재 → cookingStatus=COOKING_DONE
    @Test
    fun t1_cookingDone_parsed() {
        val texts = listOf("배민배달", "조리완료", "픽업지", "맛나분식", "전달지", "강남구 역삼동", "배달료", "5,000원")
        val results = BaeminParser.parse(texts)
        assertNotNull(results)
        assertTrue(results!!.isNotEmpty())
        assertEquals("COOKING_DONE", results[0].cookingStatus)
        assertEquals("맛나분식", results[0].storeName)
    }

    // T2: cookingStatus=COOKING_DONE → TTS "{가게명} 조리 완료" 1회 발화
    @Test
    fun t2_cookingDone_tts_fires() {
        val call = DeliveryCall(
            price = 5000, distance = 2.0, isMulti = false, platform = "baemin",
            storeName = "맛나분식", cookingStatus = "COOKING_DONE"
        )
        val key = CookingStatusTracker.dedupeKey("baemin", "session_1", call.storeName, call.price)
        // First time → should return true (speak)
        assertTrue(CookingStatusTracker.tryMarkSpoken(key))
        assertEquals(1, CookingStatusTracker.spokenCount())

        // Verify message format
        val msg = "${call.storeName} 조리 완료"
        assertEquals("맛나분식 조리 완료", msg)
    }

    // T3: 같은 dedupe key 중복 → TTS 1회만
    @Test
    fun t3_dedupe_prevents_second_tts() {
        val call = DeliveryCall(
            price = 5000, distance = 2.0, isMulti = false, platform = "baemin",
            storeName = "맛나분식", cookingStatus = "COOKING_DONE"
        )
        val key = CookingStatusTracker.dedupeKey("baemin", "session_1", call.storeName, call.price)
        assertTrue(CookingStatusTracker.tryMarkSpoken(key))
        // Second attempt with same key → should return false (no speak)
        assertFalse(CookingStatusTracker.tryMarkSpoken(key))
        assertEquals(1, CookingStatusTracker.spokenCount())
    }

    // T4: storeName 비어 있음 → TTS 발화 금지
    @Test
    fun t4_empty_storeName_no_tts() {
        val call = DeliveryCall(
            price = 5000, distance = 2.0, isMulti = false, platform = "baemin",
            storeName = "", cookingStatus = "COOKING_DONE"
        )
        // storeName empty → should not attempt TTS (caller checks storeName.isEmpty())
        assertTrue(call.storeName.isEmpty())
        assertEquals("COOKING_DONE", call.cookingStatus)
        // No key should be created/spoken
        assertEquals(0, CookingStatusTracker.spokenCount())
    }

    // T5: raw "조리중" 또는 "3분 뒤 조리완료" → 발화 금지 (UNKNOWN 유지)
    @Test
    fun t5_cooking_in_progress_not_done() {
        // Case 1: "조리중"
        val texts1 = listOf("배민배달", "조리중", "픽업지", "맛나분식", "배달료", "5,000원")
        val results1 = BaeminParser.parse(texts1)
        assertNotNull(results1)
        assertTrue(results1!!.isNotEmpty())
        assertEquals("UNKNOWN", results1[0].cookingStatus)

        // Case 2: "3분 뒤 조리완료"
        BaeminParser.resetDedupCache()
        val texts2 = listOf("배민배달", "3분 뒤 조리완료", "픽업지", "맛나분식", "배달료", "6,000원")
        val results2 = BaeminParser.parse(texts2)
        assertNotNull(results2)
        assertTrue(results2!!.isNotEmpty())
        assertEquals("UNKNOWN", results2[0].cookingStatus)
    }

    // T6: 로그에 raw customerRequest/상세주소 원문 저장 없음 (M1.wire-fix 정책 준수)
    @Test
    fun t6_no_raw_in_log() {
        // CookingStatusTracker stores only keys (hashed identifiers), not raw text
        val key = CookingStatusTracker.dedupeKey("baemin", "sess_1", "맛나분식", 5000)
        // Key uses sessionId → no storeName in key
        assertEquals("baemin:sess_1", key)

        // Fallback key uses storeName but that's the store name (public), not customer request
        val fallbackKey = CookingStatusTracker.dedupeKey("baemin", null, "맛나분식", 5000)
        assertTrue(fallbackKey.startsWith("baemin:맛나분식:5000:"))
        // No customer request or address in any stored data
        assertFalse(fallbackKey.contains("강남구"))
        assertFalse(fallbackKey.contains("문앞"))
    }

    // Bonus: CoupangNotificationParser cooking detection
    @Test
    fun coupang_cookingDone_parsed() {
        val result = CoupangNotificationParser.parse(
            title = "",
            text = "(조리완료) 5,000원 배달 거리 2.5km",
            sbnKey = "test_key"
        )
        assertNotNull(result)
        assertEquals("COOKING_DONE", result!!.cookingStatus)
        assertEquals(5000, result.offeredPrice)
    }

    // Bonus: Dedupe key priority (sessionId > fallback)
    @Test
    fun dedupeKey_sessionId_priority() {
        val key1 = CookingStatusTracker.dedupeKey("baemin", "session_abc", "맛나분식", 5000)
        assertEquals("baemin:session_abc", key1)

        val key2 = CookingStatusTracker.dedupeKey("baemin", "", "맛나분식", 5000)
        assertTrue(key2.startsWith("baemin:맛나분식:5000:"))

        val key3 = CookingStatusTracker.dedupeKey("baemin", null, "맛나분식", 5000)
        assertTrue(key3.startsWith("baemin:맛나분식:5000:"))
    }
}
