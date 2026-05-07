package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FilterLogDedupV2Test {

    @Test
    fun `key 우선순위 1 - eventId 있으면 eid prefix`() {
        val key = FilterLog.makeDedupKey("baemin", 3500, "KFC", eventId = "ev123", orderId = "T2CN")
        assertEquals("eid:ev123", key)
    }

    @Test
    fun `key 우선순위 2 - eventId 없고 orderId 있으면 oid prefix`() {
        val key = FilterLog.makeDedupKey("baemin", 3500, "KFC", orderId = "T2CN0000JLZW")
        assertEquals("oid:T2CN0000JLZW", key)
    }

    @Test
    fun `key 우선순위 3 - eventId+orderId 없고 storeName 있으면 pps`() {
        val key = FilterLog.makeDedupKey("baemin", 3500, "KFC 광주태전점")
        assertEquals("pps:baemin:3500:KFC 광주태전점", key)
    }

    @Test
    fun `key 우선순위 4 - 모두 없으면 pp fallback`() {
        val key = FilterLog.makeDedupKey("baemin", 3500)
        assertEquals("pp:baemin:3500", key)
    }

    @Test
    fun `같은 eventId = 같은 key (가격 달라도)`() {
        val key1 = FilterLog.makeDedupKey("baemin", 3500, eventId = "ev123")
        val key2 = FilterLog.makeDedupKey("baemin", 7000, eventId = "ev123")
        assertEquals(key1, key2)
    }

    @Test
    fun `다른 eventId = 다른 key (가격 같아도)`() {
        val key1 = FilterLog.makeDedupKey("baemin", 3500, eventId = "ev123")
        val key2 = FilterLog.makeDedupKey("baemin", 3500, eventId = "ev456")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `다른 가게 같은 가격 = 다른 key (pps 레벨)`() {
        val key1 = FilterLog.makeDedupKey("baemin", 3500, "KFC 광주태전점")
        val key2 = FilterLog.makeDedupKey("baemin", 3500, "맘스터치 태전점")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `같은 가게 같은 가격 = 같은 key`() {
        val key1 = FilterLog.makeDedupKey("baemin", 3500, "KFC 광주태전점")
        val key2 = FilterLog.makeDedupKey("baemin", 3500, "KFC 광주태전점")
        assertEquals(key1, key2)
    }
}
