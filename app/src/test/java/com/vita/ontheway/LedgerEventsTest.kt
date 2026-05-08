package com.vita.ontheway

import com.vita.ontheway.ledger.LedgerEvent
import com.vita.ontheway.ledger.LedgerEventType
import com.vita.ontheway.ledger.LedgerEventsDb
import com.vita.ontheway.ledger.LedgerEventsRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * LEDGER-EVENTS-TABLE: ledger_events 테이블 + Repository 테스트.
 *
 * DB 실제 I/O는 Android context 필요 → instrumented test.
 * 여기서는 데이터 모델, enum, contract, schema 검증.
 */
class LedgerEventsTest {

    // ── LedgerEventType: 16개 정의 확인 ──

    @Test
    fun `LedgerEventType 17개 정의`() {
        assertEquals(17, LedgerEventType.entries.size)
    }

    @Test
    fun `LedgerEventType 모든 값 사용 가능`() {
        val expected = listOf(
            "RAW_ACCESSIBILITY_SEEN", "RAW_NOTIFICATION_SEEN",
            "CALL_DETECTED", "JUDGMENT_ISSUED",
            "DRIVER_ACCEPTED", "DRIVER_REJECTED",
            "TIMEOUT", "CARD_FINALIZED",
            "PICKED_UP", "DELIVERY_COMPLETED",
            "RETURN_STARTED", "NEXT_CALL_DETECTED",
            "IDLE_STARTED", "SESSION_ENDED",
            "ORPHAN_CLASSIFIED", "CORRECTION_ISSUED",
            "QUARANTINED"
        )
        val actual = LedgerEventType.entries.map { it.name }
        assertEquals(expected, actual)
    }

    @Test
    fun `LedgerEventType valueOf 정상`() {
        assertEquals(LedgerEventType.CALL_DETECTED, LedgerEventType.valueOf("CALL_DETECTED"))
        assertEquals(LedgerEventType.CORRECTION_ISSUED, LedgerEventType.valueOf("CORRECTION_ISSUED"))
    }

    // ── LedgerEvent 데이터 클래스 ──

    @Test
    fun `LedgerEvent 생성 기본값`() {
        val event = LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            platform = "baemin",
            eventType = LedgerEventType.CALL_DETECTED,
            sourceChannel = "accessibility",
            occurredAtWall = System.currentTimeMillis()
        )
        assertEquals(0L, event.id)
        assertNull(event.callSessionId)
        assertNull(event.eventId)
        assertNull(event.orderId)
        assertEquals(0.0, event.identityConfidence, 0.001)
        assertEquals(0.0, event.confidence, 0.001)
        assertNull(event.rawPayloadJson)
        assertNull(event.derivedPayloadJson)
        assertEquals(1, event.schemaVersion)
        assertTrue(event.createdAt > 0)
    }

    @Test
    fun `LedgerEvent 모든 필드 설정`() {
        val now = System.currentTimeMillis()
        val event = LedgerEvent(
            id = 42,
            ledgerEventId = "uuid-123",
            callSessionId = "session-456",
            eventId = "evt-789",
            orderId = "T2CN-001",
            platform = "coupang",
            eventType = LedgerEventType.JUDGMENT_ISSUED,
            sourceChannel = "internal",
            occurredAtWall = now,
            occurredAtMonotonic = 123456L,
            identityConfidence = 0.95,
            confidence = 0.8,
            rawPayloadJson = """{"price":3000}""",
            derivedPayloadJson = """{"verdict":"ACCEPT"}""",
            schemaVersion = 1,
            createdAt = now
        )
        assertEquals(42L, event.id)
        assertEquals("uuid-123", event.ledgerEventId)
        assertEquals("session-456", event.callSessionId)
        assertEquals("evt-789", event.eventId)
        assertEquals("T2CN-001", event.orderId)
        assertEquals("coupang", event.platform)
        assertEquals(LedgerEventType.JUDGMENT_ISSUED, event.eventType)
        assertEquals("internal", event.sourceChannel)
        assertEquals(now, event.occurredAtWall)
        assertEquals(123456L, event.occurredAtMonotonic)
        assertEquals(0.95, event.identityConfidence, 0.001)
        assertEquals(0.8, event.confidence, 0.001)
        assertEquals("""{"price":3000}""", event.rawPayloadJson)
        assertEquals("""{"verdict":"ACCEPT"}""", event.derivedPayloadJson)
        assertEquals(1, event.schemaVersion)
    }

    @Test
    fun `schema_version 기본값 1`() {
        val event = LedgerEvent(
            ledgerEventId = "test",
            platform = "system",
            eventType = LedgerEventType.SESSION_ENDED,
            sourceChannel = "internal",
            occurredAtWall = System.currentTimeMillis()
        )
        assertEquals(1, event.schemaVersion)
    }

    @Test
    fun `confidence 범위 0_0~1_0 설정 가능`() {
        val low = LedgerEvent(
            ledgerEventId = "t1", platform = "baemin",
            eventType = LedgerEventType.RAW_NOTIFICATION_SEEN,
            sourceChannel = "notification",
            occurredAtWall = System.currentTimeMillis(),
            identityConfidence = 0.0, confidence = 0.0
        )
        val high = low.copy(
            ledgerEventId = "t2",
            identityConfidence = 1.0, confidence = 1.0
        )
        assertEquals(0.0, low.identityConfidence, 0.001)
        assertEquals(0.0, low.confidence, 0.001)
        assertEquals(1.0, high.identityConfidence, 0.001)
        assertEquals(1.0, high.confidence, 0.001)
    }

    // ── DB schema 상수 ──

    @Test
    fun `DB 이름과 버전`() {
        assertEquals("ledger.db", LedgerEventsDb.DB_NAME)
        assertEquals(1, LedgerEventsDb.DB_VERSION)
        assertEquals("ledger_events", LedgerEventsDb.TABLE)
    }

    // ── Repository: update/delete 메서드 부재 (컴파일 타임 강제) ──

    @Test
    fun `Repository에 update 메서드 없음`() {
        val methods = LedgerEventsRepository::class.java.declaredMethods.map { it.name }
        assertFalse("update 메서드 없어야 함", methods.any { it.contains("update", ignoreCase = true) })
        assertFalse("delete 메서드 없어야 함", methods.any { it.contains("delete", ignoreCase = true) })
        assertFalse("cleanup 메서드 없어야 함", methods.any { it.contains("cleanup", ignoreCase = true) })
        assertFalse("truncate 메서드 없어야 함", methods.any { it.contains("truncate", ignoreCase = true) })
    }

    @Test
    fun `Repository에 append getBySessionId getByEventId getByType count 메서드 존재`() {
        val methods = LedgerEventsRepository::class.java.declaredMethods.map { it.name }
        assertTrue("append 존재", methods.contains("append"))
        assertTrue("getBySessionId 존재", methods.contains("getBySessionId"))
        assertTrue("getByEventId 존재", methods.contains("getByEventId"))
        assertTrue("getByType 존재", methods.contains("getByType"))
        assertTrue("count 존재", methods.contains("count"))
    }

    @Test
    fun `Repository 사이즈 모니터링 메서드 존재`() {
        val methods = LedgerEventsRepository::class.java.declaredMethods.map { it.name }
        assertTrue("dbSizeBytes 존재", methods.contains("dbSizeBytes"))
        assertTrue("getSizeReport 존재", methods.contains("getSizeReport"))
    }

    // ── LedgerEventType 모든 값 event 생성 가능 ──

    @Test
    fun `모든 LedgerEventType으로 LedgerEvent 생성`() {
        val now = System.currentTimeMillis()
        LedgerEventType.entries.forEach { type ->
            val event = LedgerEvent(
                ledgerEventId = "test-${type.name}",
                platform = "baemin",
                eventType = type,
                sourceChannel = "internal",
                occurredAtWall = now
            )
            assertEquals(type, event.eventType)
            assertEquals("test-${type.name}", event.ledgerEventId)
        }
    }

    // ── CORRECTION_ISSUED 패턴 ──

    @Test
    fun `CORRECTION_ISSUED 이벤트로 정정 가능`() {
        val original = LedgerEvent(
            ledgerEventId = "orig-1",
            callSessionId = "session-1",
            platform = "baemin",
            eventType = LedgerEventType.JUDGMENT_ISSUED,
            sourceChannel = "internal",
            occurredAtWall = System.currentTimeMillis(),
            derivedPayloadJson = """{"verdict":"REJECT"}"""
        )
        val correction = LedgerEvent(
            ledgerEventId = "corr-1",
            callSessionId = "session-1",
            eventId = original.ledgerEventId,  // 원본 참조
            platform = "baemin",
            eventType = LedgerEventType.CORRECTION_ISSUED,
            sourceChannel = "internal",
            occurredAtWall = System.currentTimeMillis(),
            derivedPayloadJson = """{"corrected_verdict":"ACCEPT","reason":"거리 재측정"}"""
        )
        assertEquals(LedgerEventType.CORRECTION_ISSUED, correction.eventType)
        assertEquals(original.ledgerEventId, correction.eventId)
        assertEquals(original.callSessionId, correction.callSessionId)
    }
}
