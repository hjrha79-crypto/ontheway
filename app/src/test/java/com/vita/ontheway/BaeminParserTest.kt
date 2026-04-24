package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
}
