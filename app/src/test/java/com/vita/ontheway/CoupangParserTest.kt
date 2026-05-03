package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CoupangParserTest {

    @Test
    fun `멀티콜 + 가게명 있음`() {
        val texts = listOf("멀티", "모현각", "3,922원", "거리할증 포함", "배달거리 1.6km(실제경로)", "거절", "주문 수락\n32초")
        val result = CoupangParser.parse(texts)
        assertTrue("파싱 결과가 비어있으면 안됨", result.isNotEmpty())
        assertEquals("모현각", result[0].storeName)
        assertEquals(3922, result[0].price)
        assertTrue(result[0].isMulti)
        assertEquals(1.6, result[0].distance!!, 0.01)
    }

    @Test
    fun `단일콜 파싱 - isMulti false`() {
        val texts = listOf("3,848원", "거리할증 포함", "배달거리 1.5km(실제경로)", "거절", "주문 수락\n23초")
        val result = CoupangParser.parse(texts)
        assertTrue("파싱 결과가 비어있으면 안됨", result.isNotEmpty())
        assertFalse(result[0].isMulti)
        assertEquals(3848, result[0].price)
        assertEquals(1.5, result[0].distance!!, 0.01)
    }
}
