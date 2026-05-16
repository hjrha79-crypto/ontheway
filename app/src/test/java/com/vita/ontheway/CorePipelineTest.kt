package com.vita.ontheway

import com.vita.ontheway.core.ParsedEvent
import com.vita.ontheway.core.RawEvent
import com.vita.ontheway.core.TtsDecisionLog
import org.junit.Assert.*
import org.junit.Test

/**
 * Core Pipeline Phase 1 테스트.
 */
class CorePipelineTest {

    /** RawEvent.fromNotification 생성 */
    @Test
    fun rawEvent_fromNotification() {
        val e = RawEvent.fromNotification(
            "com.woowahan.bros", "신규배달", "3500원", "",
            postTime = 1000L
        )
        assertEquals("notification", e.sourceType)
        assertEquals("baemin", e.platformGuess)
        assertEquals("com.woowahan.bros", e.packageName)
        assertEquals(1000L, e.sourceTimestamp)
        assertTrue(e.rawEventId.isNotBlank())
        assertFalse(e.truncated)
        assertEquals(1, e.schemaVersion)
    }

    /** RawEvent.fromNotification — 쿠팡 */
    @Test
    fun rawEvent_coupangPlatformGuess() {
        val e = RawEvent.fromNotification(
            "com.coupang.mobile.eats.courier", "", "5500원", "",
            postTime = 2000L
        )
        assertEquals("coupang", e.platformGuess)
    }

    /** RawEvent.fromAccessibility 생성 */
    @Test
    fun rawEvent_fromAccessibility() {
        val e = RawEvent.fromAccessibility(
            "com.woowahan.bros", listOf("배민배달", "3500원"), eventTime = 500L
        )
        assertEquals("accessibility", e.sourceType)
        assertEquals("baemin", e.platformGuess)
        assertTrue(e.payloadText!!.contains("배민배달"))
    }

    /** RawEvent truncation */
    @Test
    fun rawEvent_truncation() {
        val longText = "A".repeat(300)
        val e = RawEvent.fromNotification("com.woowahan.bros", longText, "", "", 0L)
        assertTrue(e.truncated)
        assertEquals(200, e.payloadText!!.length)
    }

    /** ParsedEvent.fromDeliveryCall 변환 */
    @Test
    fun parsedEvent_fromDeliveryCall() {
        val call = DeliveryCall(
            price = 3500, distance = 1.2, isMulti = false,
            platform = "baemin", storeName = "테스트가게",
            destination = "강남구 역삼동", parseSuccess = true
        )
        val pe = ParsedEvent.fromDeliveryCall("raw123", "BaeminParser", call)
        assertEquals("raw123", pe.rawEventId)
        assertEquals("BaeminParser", pe.parserName)
        assertEquals("baemin", pe.platform)
        assertEquals("call_candidate", pe.eventType)
        assertEquals("success", pe.parseStatus)
        assertEquals(3500, pe.price)
        assertEquals(1.2, pe.distanceValue!!, 0.01)
        assertEquals("single", pe.bundleType)
        assertEquals("테스트가게", pe.storeHint)
        assertEquals(1.0, pe.confidenceScore, 0.01)
    }

    /** ParsedEvent.fromDeliveryCall — 묶음 */
    @Test
    fun parsedEvent_bundle() {
        val call = DeliveryCall(
            price = 7000, distance = 2.0, isMulti = true,
            platform = "coupang", bundleCount = 3
        )
        val pe = ParsedEvent.fromDeliveryCall("raw456", "CoupangParser", call)
        assertEquals("bundle", pe.bundleType)
        assertEquals(3, pe.bundleSize)
    }

    /** ParsedEvent.failed 생성 */
    @Test
    fun parsedEvent_failed() {
        val pe = ParsedEvent.failed("raw789", "BaeminNlsParser", "baemin", "parse_empty")
        assertEquals("failed", pe.parseStatus)
        assertEquals("parse_empty", pe.failureReason)
        assertEquals("unknown", pe.eventType)
        assertEquals(0.0, pe.confidenceScore, 0.001)
    }

    /** TtsDecisionLog 생성 — speak */
    @Test
    fun ttsDecision_speak() {
        val d = TtsDecisionLog(
            rawEventId = "raw123",
            platform = "baemin",
            decision = "speak",
            reason = "first_seen",
            messagePreview = "배민, 3500원, 보통"
        )
        assertEquals("speak", d.decision)
        assertEquals("first_seen", d.reason)
        assertTrue(d.ttsDecisionId.isNotBlank())
    }

    /** TtsDecisionLog 생성 — suppress */
    @Test
    fun ttsDecision_suppress() {
        val d = TtsDecisionLog(
            rawEventId = "raw456",
            platform = "coupang",
            decision = "suppress",
            reason = "cross_source_dedup"
        )
        assertEquals("suppress", d.decision)
        assertEquals("cross_source_dedup", d.reason)
        assertNull(d.messagePreview)
    }

    /** 각 모델 ID 고유성 */
    @Test
    fun modelIds_unique() {
        val r1 = RawEvent.fromNotification("pkg", "", "", "", 0)
        val r2 = RawEvent.fromNotification("pkg", "", "", "", 0)
        assertNotEquals(r1.rawEventId, r2.rawEventId)

        val p1 = ParsedEvent.failed("r", "p", "baemin", "f")
        val p2 = ParsedEvent.failed("r", "p", "baemin", "f")
        assertNotEquals(p1.parsedEventId, p2.parsedEventId)
    }

    /** platform guess — unknown package */
    @Test
    fun rawEvent_unknownPkg() {
        val e = RawEvent.fromNotification("com.unknown.app", "", "", "", 0)
        assertEquals("unknown", e.platformGuess)
    }
}
