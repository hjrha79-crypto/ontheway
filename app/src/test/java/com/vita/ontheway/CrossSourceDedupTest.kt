package com.vita.ontheway

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CrossSourceDedupTest {

    @Before
    fun setup() {
        CrossSourceDedup.reset()
    }

    @Test
    fun `NLS 먼저 처리 → Accessibility 같은 eventId 차단`() {
        CrossSourceDedup.markProcessed(eventId = "ev-001", platform = "baemin", price = 3500, storeName = "KFC")
        assertTrue(CrossSourceDedup.isProcessed(eventId = "ev-001", platform = "baemin", price = 3500))
    }

    @Test
    fun `Accessibility 먼저 처리 → NLS 같은 orderId 차단`() {
        CrossSourceDedup.markProcessed(orderId = "T2CN0000JLZW", platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_A11Y)
        assertTrue(CrossSourceDedup.isProcessed(orderId = "T2CN0000JLZW", platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_NLS))
    }

    @Test
    fun `다른 eventId 다른 가격 = 통과`() {
        CrossSourceDedup.markProcessed(eventId = "ev-001", platform = "baemin", price = 3500)
        assertFalse(CrossSourceDedup.isProcessed(eventId = "ev-002", platform = "baemin", price = 4200))
    }

    @Test
    fun `eventId 없음 → platform+price+store 차단`() {
        CrossSourceDedup.markProcessed(platform = "baemin", price = 3500, storeName = "KFC 광주태전점")
        assertTrue(CrossSourceDedup.isProcessed(platform = "baemin", price = 3500, storeName = "KFC 광주태전점"))
    }

    @Test
    fun `미등록 콜 = 통과`() {
        assertFalse(CrossSourceDedup.isProcessed(platform = "baemin", price = 5000))
    }

    @Test
    fun `A11y pp fallback 차단 유지 (regression)`() {
        CrossSourceDedup.markProcessed(platform = "coupang", price = 4199, source = CrossSourceDedup.SOURCE_A11Y)
        // A11y source에서는 pp fallback 차단 그대로 작동
        assertTrue(CrossSourceDedup.isProcessed(platform = "coupang", price = 4199, source = CrossSourceDedup.SOURCE_A11Y))
    }

    // ══ FIX-CROSSSOURCE-PP-GUARD ══

    @Test
    fun `PP-GUARD NLS stable key 없으면 pp 차단 X`() {
        // A11y에서 pp:baemin:3500 등록
        CrossSourceDedup.markProcessed(platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_A11Y)
        // NLS 진입: orderId/eventId/storeName 모두 없음 → pp guard 작동 → 통과!
        assertFalse("NLS + no stable key → pp guard 통과",
            CrossSourceDedup.isProcessed(platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_NLS))
    }

    @Test
    fun `PP-GUARD NLS storeName 있으면 pps 차단 O`() {
        CrossSourceDedup.markProcessed(platform = "baemin", price = 3500, storeName = "KFC 광주태전점", source = CrossSourceDedup.SOURCE_A11Y)
        // NLS 진입: storeName 있음 → pps 레벨에서 차단
        assertTrue("NLS + storeName → pps 차단",
            CrossSourceDedup.isProcessed(platform = "baemin", price = 3500, storeName = "KFC 광주태전점", source = CrossSourceDedup.SOURCE_NLS))
    }

    @Test
    fun `PP-GUARD NLS orderId 있으면 oid 차단 O`() {
        CrossSourceDedup.markProcessed(orderId = "T2CN0000ABCD", platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_A11Y)
        assertTrue("NLS + orderId → oid 차단",
            CrossSourceDedup.isProcessed(orderId = "T2CN0000ABCD", platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_NLS))
    }

    @Test
    fun `PP-GUARD A11y는 pp fallback 차단 유지`() {
        CrossSourceDedup.markProcessed(platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_NLS)
        // A11y source에서는 pp 가드 적용 X → 여전히 차단
        assertTrue("A11y + pp → 차단 유지",
            CrossSourceDedup.isProcessed(platform = "baemin", price = 3500, source = CrossSourceDedup.SOURCE_A11Y))
    }

    // ══ FIX-NLS-ORDERID ══

    @Test
    fun `NLS orderId 추출 - T2CN 있음`() {
        assertEquals("T2CN0000JLZW", BaeminParser.parseNlsOrderId("T2CN0000JLZW 픽업지 KFC 배달료 3,500원"))
    }

    @Test
    fun `NLS orderId 추출 - T2CN 없음 = null`() {
        assertNull(BaeminParser.parseNlsOrderId("[1건 단일] 3,740원 / 3.4km오후 2:30주문을 수락해주세요."))
    }
}
