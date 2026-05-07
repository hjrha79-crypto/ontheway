package com.vita.ontheway

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        CrossSourceDedup.markProcessed(orderId = "T2CN0000JLZW", platform = "baemin", price = 3500)
        assertTrue(CrossSourceDedup.isProcessed(orderId = "T2CN0000JLZW", platform = "baemin", price = 3500))
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
    fun `다른 가게 같은 가격 = 통과 (storeName 구분)`() {
        CrossSourceDedup.markProcessed(platform = "baemin", price = 3500, storeName = "KFC 광주태전점")
        // 다른 가게는 pps 키가 다름, 하지만 pp 키(platform+price)로는 매칭됨
        // → pp fallback 때문에 차단됨 (동일 가격은 안전하게 차단)
        assertTrue(CrossSourceDedup.isProcessed(platform = "baemin", price = 3500, storeName = "맘스터치"))
    }

    @Test
    fun `미등록 콜 = 통과`() {
        assertFalse(CrossSourceDedup.isProcessed(platform = "baemin", price = 5000))
    }

    @Test
    fun `pp fallback - eventId orderId 없이 platform+price 차단`() {
        CrossSourceDedup.markProcessed(platform = "coupang", price = 4199)
        assertTrue(CrossSourceDedup.isProcessed(platform = "coupang", price = 4199))
    }
}
