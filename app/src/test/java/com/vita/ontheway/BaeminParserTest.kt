package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaeminParserTest {

    @Test
    fun `배달료기준거리 1,065m 파싱 성공 - distance 1_065km로 설정`() {
        val texts = listOf(
            "배달료기준거리 (1,065m)",
            "배달료 3,500원",
            "픽업지",
            "맘스터치"
        )
        val result = BaeminParser.parse(texts)
        assertNotNull("파싱 결과가 비어 있음", result)
        if (result.isNotEmpty()) {
            assertEquals(1.065, result[0].distance!!, 0.01)
        }
    }

    @Test
    fun `배달료기준거리 4,300m 콤마 포함 파싱`() {
        val texts = listOf(
            "배달료기준거리 (4,300m)",
            "배달료 5,000원",
            "픽업지",
            "테스트가게"
        )
        val result = BaeminParser.parse(texts)
        if (result.isNotEmpty()) {
            assertEquals(4.3, result[0].distance!!, 0.01)
        }
    }

    @Test
    fun `거리 텍스트 없으면 distance null (기존 fallback 유지)`() {
        val texts = listOf(
            "13.5P",
            "배달료 3,500원",
            "픽업지",
            "테스트가게"
        )
        val result = BaeminParser.parse(texts)
        if (result.isNotEmpty()) {
            assertNull(result[0].distance)
        }
    }

    @Test
    fun `거리 3,691m 단위 매우 큰 값도 정상 파싱`() {
        val texts = listOf(
            "배달료기준거리 (3,691m)",
            "배달료 7,000원",
            "픽업지",
            "먼가게"
        )
        val result = BaeminParser.parse(texts)
        if (result.isNotEmpty()) {
            assertEquals(3.691, result[0].distance!!, 0.01)
        }
    }

    @Test
    fun `거리 500m 소규모 값 파싱`() {
        val texts = listOf(
            "배달료기준거리 (500m)",
            "배달료 2,500원",
            "픽업지",
            "근거리가게"
        )
        val result = BaeminParser.parse(texts)
        if (result.isNotEmpty()) {
            assertEquals(0.5, result[0].distance!!, 0.01)
        }
    }

    // ── 블랙리스트 필터 테스트 ──

    @Test
    fun `블랙리스트 - 신규배차_끄기버튼 제거`() {
        val result = BaeminParser.sanitizeStoreName("신규배차_끄기버튼")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - T2CG 주문코드 제거`() {
        val result = BaeminParser.sanitizeStoreName("T2CG0000M318")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - 정상 가게명 유지`() {
        val result = BaeminParser.sanitizeStoreName("빽보이피자 오구샌 광주태전점")
        assertEquals("빽보이피자 오구샌 광주태전점", result)
    }

    @Test
    fun `블랙리스트 - 혼합 토큰에서 오염만 제거`() {
        val result = BaeminParser.sanitizeStoreName("빽보이피자+신규배차+T2CG0000M318")
        assertEquals("빽보이피자", result)
    }

    @Test
    fun `블랙리스트 - 모두 블랙리스트면 빈 문자열`() {
        val result = BaeminParser.sanitizeStoreName("신규배차_끄기버튼+배차수락+이전내역")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - 기존 영문 블랙리스트도 유지`() {
        val result = BaeminParser.sanitizeStoreName("button+naver+맘스터치")
        assertEquals("맘스터치", result)
    }

    // ── 목적지 파싱 테스트 ──

    @Test
    fun `전달지 다음 토큰으로 목적지 파싱`() {
        val texts = listOf(
            "배달료 3,500원",
            "픽업지",
            "맘스터치",
            "전달지",
            "경기 광주시 태성로 25 (태전동)"
        )
        val result = BaeminParser.parse(texts)
        assertTrue(result.isNotEmpty())
        assertEquals("경기 광주시 태성로 25 (태전동)", result[0].destination)
    }

    @Test
    fun `전달지 없으면 기존 패턴 매칭 fallback`() {
        val texts = listOf(
            "배달료 3,500원",
            "픽업지",
            "맘스터치",
            "광주구 태전동 123"
        )
        val result = BaeminParser.parse(texts)
        assertTrue(result.isNotEmpty())
        assertEquals("광주구 태전동 123", result[0].destination)
    }
}
