package com.vita.ontheway

import com.vita.ontheway.ledger.LedgerAppender
import com.vita.ontheway.ledger.LedgerEvent
import com.vita.ontheway.ledger.LedgerEventType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * LEDGER-RAW-EVENTS: RAW 이벤트 append 로직 테스트.
 *
 * DB I/O는 Android context 필요 → 여기서는 JSON 직렬화, truncate, 데이터 모델 검증.
 */
class LedgerRawEventsTest {

    // ── Notification raw payload JSON 구조 ──

    @Test
    fun `notification raw payload JSON 정상`() {
        val json = JSONObject().apply {
            put("key", "0|com.coupang|123|null|10001")
            put("id", 123)
            put("package", "com.coupang.mobile.eats.courier")
            put("postTime", 1778068000000)
            put("title", "[1건 단일] 4,084원 / 3.3km")
            put("text", "")
            put("bigText", "주문을 수락해주세요.")
        }
        assertTrue(json.has("key"))
        assertTrue(json.has("postTime"))
        assertEquals("[1건 단일] 4,084원 / 3.3km", json.getString("title"))
    }

    // ── Accessibility raw payload JSON 구조 ──

    @Test
    fun `accessibility raw payload JSON 정상`() {
        val texts = listOf("지도", "NAVER", "멀티", "6,218원", "거절", "주문 수락\n33초")
        val json = JSONObject().apply {
            put("package", "com.coupang.mobile.eats.courier")
            put("eventType", 32)  // TYPE_WINDOW_STATE_CHANGED
            put("className", "android.widget.FrameLayout")
            put("texts", org.json.JSONArray(texts))
            put("contentDescription", "")
            put("node_count", 0)
        }
        assertEquals(32, json.getInt("eventType"))
        assertEquals(6, json.getJSONArray("texts").length())
    }

    // ── truncatePayload 5KB 이내 통과 ──

    @Test
    fun `5KB 이내 payload = 그대로 반환`() {
        val small = JSONObject().apply {
            put("key", "test")
            put("text", "짧은 텍스트")
        }.toString()
        val result = LedgerAppender.truncatePayload(small)
        assertEquals(small, result)
    }

    // ── truncatePayload 5KB 초과 시 truncate ──

    @Test
    fun `5KB 초과 payload = truncate + _truncated 플래그`() {
        val longText = "가".repeat(3000)  // 약 9KB
        val big = JSONObject().apply {
            put("key", "test")
            put("text", longText)
        }.toString()
        assertTrue("원본 > 5KB", big.toByteArray().size > 5 * 1024)

        val result = LedgerAppender.truncatePayload(big)
        assertTrue("결과 ≤ 5KB+여유", result.toByteArray().size <= 6 * 1024)
        assertTrue("_truncated 포함", result.contains("truncated"))
    }

    // ── truncatePayload 빈 JSON 안전 ──

    @Test
    fun `빈 JSON = 그대로`() {
        val empty = "{}"
        assertEquals(empty, LedgerAppender.truncatePayload(empty))
    }

    // ── LedgerEvent RAW_NOTIFICATION_SEEN 타입 ──

    @Test
    fun `RAW_NOTIFICATION_SEEN 이벤트 생성`() {
        val event = LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            eventId = "0|coupang|123",
            platform = "coupang",
            eventType = LedgerEventType.RAW_NOTIFICATION_SEEN,
            sourceChannel = "notification",
            occurredAtWall = System.currentTimeMillis(),
            identityConfidence = 0.5,
            confidence = 1.0,
            rawPayloadJson = """{"title":"[1건 단일] 4,084원"}"""
        )
        assertEquals(LedgerEventType.RAW_NOTIFICATION_SEEN, event.eventType)
        assertEquals("notification", event.sourceChannel)
        assertEquals(0.5, event.identityConfidence, 0.001)
        assertEquals(1.0, event.confidence, 0.001)
    }

    // ── LedgerEvent RAW_ACCESSIBILITY_SEEN 타입 ──

    @Test
    fun `RAW_ACCESSIBILITY_SEEN 이벤트 생성`() {
        val event = LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            eventId = "com.woowahan.bros:32:12345",
            platform = "baemin",
            eventType = LedgerEventType.RAW_ACCESSIBILITY_SEEN,
            sourceChannel = "accessibility",
            occurredAtWall = System.currentTimeMillis(),
            identityConfidence = 0.3,
            confidence = 1.0,
            rawPayloadJson = """{"package":"com.woowahan.bros","eventType":32}"""
        )
        assertEquals(LedgerEventType.RAW_ACCESSIBILITY_SEEN, event.eventType)
        assertEquals("accessibility", event.sourceChannel)
        assertEquals(0.3, event.identityConfidence, 0.001)
    }

    // ── append 실패 시 예외 X (silent) ──

    @Test
    fun `LedgerEvent 잘못된 JSON도 생성 가능`() {
        // rawPayloadJson 검증 없이 저장 — append 실패는 silent
        val event = LedgerEvent(
            ledgerEventId = "test",
            platform = "system",
            eventType = LedgerEventType.RAW_NOTIFICATION_SEEN,
            sourceChannel = "notification",
            occurredAtWall = System.currentTimeMillis(),
            rawPayloadJson = "not-valid-json"
        )
        assertNotNull(event.rawPayloadJson)
    }
}
