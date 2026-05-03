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

    // ── BLACKLIST 테스트 (FIX-10) ──

    @Test
    fun `BLACKLIST - UI 텍스트가 가게명으로 오인되지 않음`() {
        val texts = listOf("3,848원", "거리할증 포함", "배달거리 1.5km(실제경로)", "거절", "메뉴", "주문 수락\n23초")
        val result = CoupangParser.parse(texts)
        assertTrue(result.isNotEmpty())
        assertEquals("", result[0].storeName)
    }

    @Test
    fun `BLACKLIST - 거리할증만 있을 때 가게명 빈 문자열`() {
        val texts = listOf("거리할증 포함", "3,500원", "배달거리 1.0km(실제경로)", "거절")
        val result = CoupangParser.parse(texts)
        assertTrue(result.isNotEmpty())
        assertEquals("", result[0].storeName)
    }

    @Test
    fun `BLACKLIST - 정상 가게명 + UI 텍스트 혼재 시 정상 가게명 보존`() {
        val texts = listOf("멀티", "모현각", "3,922원", "거절", "거리할증 · 지원금 포함", "배달거리 1.6km(실제경로)", "주문 수락\n32초")
        val result = CoupangParser.parse(texts)
        assertTrue(result.isNotEmpty())
        assertEquals("모현각", result[0].storeName)
    }

    @Test
    fun `BLACKLIST - 가게명에 거절 글자 포함 시 정확일치라 차단 안 됨`() {
        // "거절식당"은 BLACKLIST "거절"과 다르므로 가게명으로 인정
        val texts = listOf("거절식당", "3,000원", "배달거리 0.5km(실제경로)", "거절", "주문 수락\n30초")
        val result = CoupangParser.parse(texts)
        assertTrue(result.isNotEmpty())
        assertEquals("거절식당", result[0].storeName)
    }
}
