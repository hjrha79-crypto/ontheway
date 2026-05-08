package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * FIX-COUPANG-STORENAME-V2: 쿠팡 가게명 추출 개선 테스트.
 *
 * 5/8 raw fixture 기반:
 * - 가게명 있는 트리: "본죽&비빔밥 경기광주태전점" 추출
 * - 헤더 텍스트 "[1건 단일]" 절대 가게명 X
 * - UI 오염 텍스트 절대 가게명 X
 */
class CoupangStoreNameV2Test {

    // ── 정상 가게명 추출 ──

    @Test
    fun `멀티 트리에서 가게명 추출 피자헛`() {
        val texts = listOf(
            "지도", "NAVER", "멀티", "6,218원", "거리할증 포함",
            "주문 2건", "배달거리 4.1km(실제경로)", "메뉴",
            "피자헛 광주태전점", "3,900원",
            "본죽&비빔밥 경기광주태전점", "2,318원",
            "거절", "조건에 맞거나 진행중인 미션이 없습니다", "주문 수락\n33초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("피자헛 광주태전점", store)
    }

    @Test
    fun `단건 트리에서 가게명 추출`() {
        val texts = listOf(
            "지도", "NAVER", "4,084원",
            "본죽&비빔밥 경기광주태전힐스테이트점",
            "배달거리 3.3km(실제경로)",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("본죽&비빔밥 경기광주태전힐스테이트점", store)
    }

    // ── 가게명 없을 때 빈 값 ──

    @Test
    fun `가게명 없는 트리 = 빈 문자열`() {
        val texts = listOf(
            "지도", "NAVER", "4,084원",
            "배달거리 3.3km(실제경로)",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    // ── 헤더/UI 오염 차단 ──

    @Test
    fun `1건 단일 = 가게명 아님`() {
        val texts = listOf(
            "1건 단일", "4,084원", "배달거리 3.3km(실제경로)",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    @Test
    fun `bracket 1건 단일 = 가게명 아님`() {
        val texts = listOf(
            "[1건 단일]", "4,084원",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        // STORE_PATTERN does not include brackets, so "[1건 단일]" won't match anyway
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    @Test
    fun `2건 묶음 = 가게명 아님`() {
        val texts = listOf(
            "2건 묶음", "6,218원", "배달거리 4.1km(실제경로)",
            "거절", "주문 수락\n30초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    @Test
    fun `주문 수락 33초 = 가게명 아님`() {
        // "주문 수락" is in CALL_SCREEN_BUTTONS, but "주문 수락 33초" (without newline)
        // should not match as store
        val texts = listOf(
            "4,084원", "주문 수락 33초",
            "거절"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertNotEquals("주문 수락 33초", store)
    }

    @Test
    fun `배달거리 4_1km 실제경로 = 가게명 아님`() {
        val texts = listOf(
            "4,084원", "배달거리 4.1km(실제경로)",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    @Test
    fun `17퍼센트 = 가게명 아님`() {
        val texts = listOf(
            "17%", "4,084원",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    @Test
    fun `거리할증 포함 = 가게명 아님`() {
        val texts = listOf(
            "거리할증 포함", "4,084원",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    @Test
    fun `가까운 주문을 찾는 중 = 가게명 아님`() {
        val texts = listOf(
            "가까운 주문을 찾는 중", "4,084원",
            "거절", "주문 수락\n25초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("", store)
    }

    // ── 가게명 패턴 다양성 ──

    @Test
    fun `영문 포함 가게명 BBQ 광주태전점`() {
        val texts = listOf(
            "지도", "NAVER", "3,500원",
            "BBQ 광주태전점",
            "거절", "주문 수락\n20초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("BBQ 광주태전점", store)
    }

    @Test
    fun `앰퍼샌드 가게명 본죽앤비빔밥`() {
        val texts = listOf(
            "지도", "NAVER", "3,000원",
            "본죽&비빔밥 오포점",
            "거절", "주문 수락\n20초"
        )
        val joined = texts.joinToString(" ")
        val store = CoupangParser.extractStoreName(texts, joined)
        assertEquals("본죽&비빔밥 오포점", store)
    }
}
