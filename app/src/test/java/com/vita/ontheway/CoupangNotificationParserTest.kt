package com.vita.ontheway

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CoupangNotificationParserTest {

    @Before
    fun setup() {
        mockkObject(OtwFileLogger)
        every { OtwFileLogger.log(any(), any()) } returns Unit
    }

    @After
    fun teardown() { unmockkAll() }

    // ── Structured (confidence=0.9) ──

    @Test
    fun `1건 단일 structured`() {
        val r = CoupangNotificationParser.parse("쿠팡이츠", "[1건 단일] 3,450원 / 2.0km")!!
        assertEquals(3450, r.offeredPrice)
        assertEquals(2.0, r.distanceKm!!, 0.001)
        assertEquals(1, r.bundleCount)
        assertEquals("단일", r.bundleType)
        assertFalse(r.isMulti)
        assertEquals(0.9, r.confidence, 0.001)
        assertEquals("notification_structured", r.sourceChannel)
    }

    @Test
    fun `2건 묶음 structured`() {
        val r = CoupangNotificationParser.parse("쿠팡이츠", "[2건 묶음] 5,600원 / 2.5km")!!
        assertEquals(2, r.bundleCount)
        assertEquals("묶음", r.bundleType)
        assertTrue(r.isMulti)
        assertEquals(0.9, r.confidence, 0.001)
    }

    @Test
    fun `조리완료 structured`() {
        val r = CoupangNotificationParser.parse("", "(조리완료) 3,000원 배달 거리 0.7km")!!
        assertEquals(3000, r.offeredPrice)
        assertEquals(0.7, r.distanceKm!!, 0.001)
        assertEquals(0.9, r.confidence, 0.001)
    }

    @Test
    fun `멀티 structured`() {
        val r = CoupangNotificationParser.parse("", "멀티 3,525원 거리할증 지원금 포함 배달 거리 2.1km")!!
        assertEquals(3525, r.offeredPrice)
        assertTrue(r.isMulti)
        assertEquals(0.9, r.confidence, 0.001)
    }

    // ── Loose (confidence=0.5) ──

    @Test
    fun `단순 가격 슬래시 km loose`() {
        val r = CoupangNotificationParser.parse("", "4,500원 / 3.2km")!!
        assertEquals(4500, r.offeredPrice)
        assertEquals(3.2, r.distanceKm!!, 0.001)
        assertEquals(0.5, r.confidence, 0.001)
        assertEquals("notification_loose", r.sourceChannel)
    }

    @Test
    fun `title 빈 경우 text만 loose`() {
        val r = CoupangNotificationParser.parse("", "8,550원 / 3.1km")!!
        assertEquals(8550, r.offeredPrice)
        assertEquals(0.5, r.confidence, 0.001)
    }

    // ── 수락 요청 보조 신호 ──

    @Test
    fun `주문을 수락해주세요 보조 신호 +0점1`() {
        val r = CoupangNotificationParser.parse("", "[1건 단일] 4,000원 / 1.5km 주문을 수락해주세요")!!
        assertEquals(1.0, r.confidence, 0.001) // 0.9 + 0.1 = 1.0
    }

    // ── NonCall 필터 ──

    @Test
    fun `빈 text → null`() {
        assertNull(CoupangNotificationParser.parse("", ""))
    }

    @Test
    fun `비콜 운행이 종료 → null`() {
        assertNull(CoupangNotificationParser.parse("", "운행이 종료되었습니다"))
    }

    @Test
    fun `비콜 배달 완료 → null`() {
        assertNull(CoupangNotificationParser.parse("쿠팡이츠", "배달 완료 3,000원"))
    }

    @Test
    fun `비콜 Aggregate → null`() {
        assertNull(CoupangNotificationParser.parse("Aggregate_NormalNotificationSection", ""))
    }

    @Test
    fun `가격 범위 초과 → null`() {
        assertNull(CoupangNotificationParser.parse("", "[1건 단일] 200,000원 / 1.0km"))
    }

    // ── 기타 ──

    @Test
    fun `sbnKey postTime 전달`() {
        val r = CoupangNotificationParser.parse("", "[1건 단일] 4,000원 / 1.5km",
            sbnKey = "sbn-123", postTime = 1234567890L)!!
        assertEquals("sbn-123", r.notificationKey)
        assertEquals(1234567890L, r.postTime)
    }

    @Test
    fun `가격 천단위 콤마`() {
        val r = CoupangNotificationParser.parse("", "[1건 단일] 12,345원 / 4.5km")!!
        assertEquals(12345, r.offeredPrice)
    }
}
