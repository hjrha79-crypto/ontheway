package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * Memory M1: DeliveryRequestClassifier 단위 테스트.
 *
 * 5 필수 + 2 개인정보 테스트.
 */
class DeliveryRequestClassifierTest {

    // ── 1. 호수 + 문앞 → REPEAT_CRITICAL ──

    @Test
    fun `1203호 문앞에 놓아주세요 = REPEAT_CRITICAL with UNIT and DOOR_FRONT`() {
        val result = DeliveryRequestClassifier.classify("1203호 문 앞에 놓아주세요")
        assertEquals(DeliveryRequestClassifier.Classification.REPEAT_CRITICAL, result.classification)
        assertTrue("UNIT 포함", "UNIT" in result.matchedKeywords)
        assertTrue("DOOR_FRONT 포함", "DOOR_FRONT" in result.matchedKeywords)
    }

    // ── 2. 스타벅스 2층 → NAVIGATION_HINT ──

    @Test
    fun `스타벅스 2층 = NAVIGATION_HINT with LANDMARK`() {
        val result = DeliveryRequestClassifier.classify("스타벅스 2층")
        assertEquals(DeliveryRequestClassifier.Classification.NAVIGATION_HINT, result.classification)
        assertTrue("LANDMARK 포함", "LANDMARK" in result.matchedKeywords)
    }

    // ── 3. 건물 입구에서 전화주세요 → REPEAT_CRITICAL (CALL 우선) ──

    @Test
    fun `건물 입구에서 전화주세요 = REPEAT_CRITICAL with CALL`() {
        val result = DeliveryRequestClassifier.classify("건물 입구에서 전화주세요")
        assertEquals(DeliveryRequestClassifier.Classification.REPEAT_CRITICAL, result.classification)
        assertTrue("CALL 포함", "CALL" in result.matchedKeywords)
    }

    // ── 4. 지하주차장 옆 출입구 → NAVIGATION_HINT ──

    @Test
    fun `지하주차장 옆 출입구 = NAVIGATION_HINT with PARKING and ENTRANCE`() {
        val result = DeliveryRequestClassifier.classify("지하주차장 옆 출입구")
        assertEquals(DeliveryRequestClassifier.Classification.NAVIGATION_HINT, result.classification)
        assertTrue("PARKING 포함", "PARKING" in result.matchedKeywords)
        assertTrue("ENTRANCE 포함", "ENTRANCE" in result.matchedKeywords)
    }

    // ── 5. 빈 문자열/null → IGNORE ──

    @Test
    fun `empty and null = IGNORE with no keywords`() {
        val emptyResult = DeliveryRequestClassifier.classify("")
        assertEquals(DeliveryRequestClassifier.Classification.IGNORE, emptyResult.classification)
        assertTrue("empty keywords", emptyResult.matchedKeywords.isEmpty())
        assertEquals("empty hash", "", emptyResult.textHash)

        val nullResult = DeliveryRequestClassifier.classify(null)
        assertEquals(DeliveryRequestClassifier.Classification.IGNORE, nullResult.classification)
        assertTrue("null keywords", nullResult.matchedKeywords.isEmpty())
        assertEquals("null hash", "", nullResult.textHash)
    }

    // ── 6. ledger에 원문 substring 미저장 확인 ──

    @Test
    fun `matchedKeywords contains only taxonomy keys not raw text`() {
        val result = DeliveryRequestClassifier.classify("1203호 문 앞에 놓아주세요 비밀번호 1234")
        // matchedKeywords는 taxonomy key만
        for (keyword in result.matchedKeywords) {
            assertTrue("taxonomy key여야 함: $keyword",
                keyword in listOf("UNIT", "DOOR_CODE", "DOOR_FRONT", "DIRECT_HANDOFF",
                    "BELL", "CALL", "TEXT", "PHOTO",
                    "LANDMARK", "BUILDING_SIDE", "PARKING", "ENTRANCE"))
            assertFalse("원문 포함 금지: $keyword", keyword.contains("1203"))
            assertFalse("원문 포함 금지: $keyword", keyword.contains("놓아"))
            assertFalse("원문 포함 금지: $keyword", keyword.contains("1234"))
        }
    }

    // ── 7. salted hash 확인 (plain SHA X) ──

    @Test
    fun `textHash is salted hash not plain SHA`() {
        val text = "문앞"
        val result = DeliveryRequestClassifier.classify(text)

        // hash가 비어있지 않음
        assertTrue("hash 존재", result.textHash.isNotBlank())
        // 16자 hex
        assertEquals("hash 길이 16", 16, result.textHash.length)
        assertTrue("hex 문자열", result.textHash.all { it in '0'..'9' || it in 'a'..'f' })

        // plain SHA-256("문앞")과 다름 (salted)
        val plainDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
        val plainHash = plainDigest.joinToString("") { "%02x".format(it) }.take(16)
        assertNotEquals("salted hash ≠ plain hash", plainHash, result.textHash)

        // 동일 입력 → 동일 hash (결정적)
        val result2 = DeliveryRequestClassifier.classify(text)
        assertEquals("deterministic", result.textHash, result2.textHash)
    }

    // ── 추가: 복합 키워드 ──

    @Test
    fun `벨 누르지 말고 문자 주세요 = REPEAT_CRITICAL with BELL and TEXT`() {
        val result = DeliveryRequestClassifier.classify("벨 누르지 말고 문자 주세요")
        assertEquals(DeliveryRequestClassifier.Classification.REPEAT_CRITICAL, result.classification)
        assertTrue("BELL 포함", "BELL" in result.matchedKeywords)
        assertTrue("TEXT 포함", "TEXT" in result.matchedKeywords)
    }

    @Test
    fun `비대면 배달 = IGNORE (no taxonomy match)`() {
        // "비대면"은 BaeminParser의 REQUEST_SHORTEN에는 있지만
        // DeliveryRequestClassifier taxonomy에는 없음 → IGNORE
        val result = DeliveryRequestClassifier.classify("비대면 배달")
        assertEquals(DeliveryRequestClassifier.Classification.IGNORE, result.classification)
    }

    // ── BaeminParser.classifyRequest 통합 ──

    @Test
    fun `BaeminParser classifyRequest integration`() {
        val texts = listOf("배달 완료", "문 앞에 놓아주세요", "감사합니다")
        val result = BaeminParser.classifyRequest(texts)
        assertEquals(DeliveryRequestClassifier.Classification.REPEAT_CRITICAL, result.classification)
        assertTrue("DOOR_FRONT 포함", "DOOR_FRONT" in result.matchedKeywords)
    }

    // ── DeliveryCall requestClassification 필드 ──

    @Test
    fun `DeliveryCall has requestClassification field`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false, platform = "baemin",
            requestClassification = "REPEAT_CRITICAL"
        )
        assertEquals("REPEAT_CRITICAL", call.requestClassification)

        val callDefault = DeliveryCall(price = 3000, distance = null, isMulti = false, platform = "baemin")
        assertNull(callDefault.requestClassification)
    }
}
