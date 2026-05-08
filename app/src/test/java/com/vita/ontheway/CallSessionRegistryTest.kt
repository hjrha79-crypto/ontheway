package com.vita.ontheway

import com.vita.ontheway.ledger.CallSessionRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * CALL-SESSION-ID-UNIFICATION: CallSessionRegistry 테스트.
 */
class CallSessionRegistryTest {

    @Before
    fun setup() {
        CallSessionRegistry.resetForTest()
    }

    // ── 같은 콜 = 같은 call_session_id ──

    @Test
    fun `같은 eventId = 같은 세션`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-1")
        val id2 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-1")
        assertEquals(id1, id2)
    }

    @Test
    fun `같은 orderId = 같은 세션`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(orderId = "T2CN-001")
        val id2 = CallSessionRegistry.getOrCreateSessionId(orderId = "T2CN-001")
        assertEquals(id1, id2)
    }

    @Test
    fun `orderId 일치 시 가격 가게명 달라도 같은 세션`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(
            eventId = "evt-a", orderId = "T2CN-001",
            fingerprint = "가게a|3000|baemin|123"
        )
        val id2 = CallSessionRegistry.getOrCreateSessionId(
            eventId = "evt-b", orderId = "T2CN-001",
            fingerprint = "가게b|4000|baemin|123"
        )
        assertEquals(id1, id2)
    }

    @Test
    fun `eventId 일치 시 orderId 없어도 같은 세션`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-1")
        val id2 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-1", orderId = "T2CN-new")
        assertEquals(id1, id2)
    }

    @Test
    fun `fingerprint 일치 시 같은 세션`() {
        val fp = "맥도날드|3000|coupang|${System.currentTimeMillis() / 60_000}"
        val id1 = CallSessionRegistry.getOrCreateSessionId(fingerprint = fp)
        val id2 = CallSessionRegistry.getOrCreateSessionId(fingerprint = fp)
        assertEquals(id1, id2)
    }

    // ── 다른 콜 = 다른 call_session_id ──

    @Test
    fun `다른 eventId = 다른 세션`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-1")
        val id2 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-2")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `다른 orderId = 다른 세션`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(orderId = "T2CN-001")
        val id2 = CallSessionRegistry.getOrCreateSessionId(orderId = "T2CN-002")
        assertNotEquals(id1, id2)
    }

    // ── lifecycle 이벤트 모두 같은 call_session_id ──

    @Test
    fun `RAW + DETECTED + JUDGMENT + ACCEPTED = 같은 세션`() {
        // RAW (eventId 없음, fingerprint로)
        val fp = "본죽|4000|coupang|${System.currentTimeMillis() / 60_000}"
        val rawId = CallSessionRegistry.getOrCreateSessionId(fingerprint = fp)

        // DETECTED (eventId 부여)
        val detectedId = CallSessionRegistry.getOrCreateSessionId(
            eventId = "evt-100", fingerprint = fp
        )
        assertEquals("RAW→DETECTED 같은 세션", rawId, detectedId)

        // JUDGMENT (같은 eventId)
        val judgmentId = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-100")
        assertEquals("DETECTED→JUDGMENT 같은 세션", detectedId, judgmentId)

        // ACCEPTED (orderId 추가)
        val acceptedId = CallSessionRegistry.getOrCreateSessionId(
            eventId = "evt-100", orderId = "T2CN-500"
        )
        assertEquals("JUDGMENT→ACCEPTED 같은 세션", judgmentId, acceptedId)
    }

    // ── 세션 종료 ──

    @Test
    fun `finalized 세션은 새 매칭에서 제외`() {
        val id1 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-fin")
        CallSessionRegistry.markFinalized(id1, "CARD_FINALIZED")

        // 같은 eventId로 다시 → 새 세션
        val id2 = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-fin")
        assertNotEquals(id1, id2)
    }

    // ── findSessionId ──

    @Test
    fun `findSessionId orderId 조회`() {
        val id = CallSessionRegistry.getOrCreateSessionId(orderId = "T2CN-FIND")
        val found = CallSessionRegistry.findSessionId(orderId = "T2CN-FIND")
        assertEquals(id, found)
    }

    @Test
    fun `findSessionId eventId 조회`() {
        val id = CallSessionRegistry.getOrCreateSessionId(eventId = "evt-find")
        val found = CallSessionRegistry.findSessionId(eventId = "evt-find")
        assertEquals(id, found)
    }

    @Test
    fun `findSessionId 미존재 = null`() {
        val found = CallSessionRegistry.findSessionId(eventId = "nonexistent")
        assertNull(found)
    }

    // ── activeCount ──

    @Test
    fun `activeCount 정상`() {
        assertEquals(0, CallSessionRegistry.activeCount())
        CallSessionRegistry.getOrCreateSessionId(eventId = "e1")
        CallSessionRegistry.getOrCreateSessionId(eventId = "e2")
        assertEquals(2, CallSessionRegistry.activeCount())
        val id = CallSessionRegistry.getOrCreateSessionId(eventId = "e1")
        assertEquals(2, CallSessionRegistry.activeCount())  // 중복이므로 그대로
        CallSessionRegistry.markFinalized(id, "TEST")
        assertEquals(1, CallSessionRegistry.activeCount())
    }

    // ── buildFingerprint ──

    @Test
    fun `buildFingerprint 정규화`() {
        val fp1 = CallSessionRegistry.buildFingerprint("BBQ 태전점", 3000, "coupang")
        val fp2 = CallSessionRegistry.buildFingerprint("  BBQ  태전점  ", 3000, "coupang")
        assertEquals(fp1, fp2)
    }
}
