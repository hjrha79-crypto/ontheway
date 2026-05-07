package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class DeliveryFlowTest {

    @Before
    fun setup() {
        DeliveryFlowManager.clearState()
        DeliveryFlowManager.speakCallback = null
    }

    // ══ DeliveryAddress 파싱 ══

    @Test
    fun `주소 파싱 - 103동 1302호`() {
        val addr = DeliveryAddress.parse("경기 광주시 오포안로 402-6 103동 1302호")
        assertEquals("103동", addr.dong)
        assertEquals("1302호", addr.ho)
        assertEquals("103동 1302호", addr.shortAddress())
    }

    @Test
    fun `주소 파싱 - B동 304호`() {
        val addr = DeliveryAddress.parse("경기 광주시 태봉로1번길 5 B동 304호")
        assertEquals("B동", addr.dong)
        assertEquals("304호", addr.ho)
    }

    @Test
    fun `주소 파싱 - 2005동 1302호`() {
        val addr = DeliveryAddress.parse("힐스테이트태전2차 2005동 1302호")
        assertEquals("2005동", addr.dong)
        assertEquals("1302호", addr.ho)
    }

    @Test
    fun `주소 파싱 - 호수만`() {
        val addr = DeliveryAddress.parse("태전동 한아람아파트 1501호")
        assertEquals("", addr.dong)
        assertEquals("1501호", addr.ho)
        assertEquals("1501호", addr.shortAddress())
    }

    @Test
    fun `주소 파싱 - 동호수 없는 주소`() {
        val addr = DeliveryAddress.parse("경기 광주시 삼지곡길 73-4")
        assertEquals("", addr.dong)
        assertEquals("", addr.ho)
        assertTrue(addr.shortAddress().isNotBlank()) // fullAddress의 앞 20자
    }

    @Test
    fun `공동현관 코드 추출`() {
        assertEquals("1234", DeliveryAddress.extractEntranceCode("공동현관 1234"))
        assertEquals("5678", DeliveryAddress.extractEntranceCode("현관 비밀번호 5678"))
        assertEquals("9012", DeliveryAddress.extractEntranceCode("공동현관 #9012#"))
        assertNull(DeliveryAddress.extractEntranceCode("문 앞에 두고 문자 주세요"))
    }

    // ══ DeliveryTtsBuilder 시나리오 A~E ══

    @Test
    fun `시나리오 A - crossing 100m`() {
        val addr = DeliveryAddress.parse("아파트 103동 1302호")
        val msg = DeliveryTtsBuilder.buildCrossing(addr)
        assertEquals("103동 1302호", msg)
    }

    @Test
    fun `시나리오 B - 도착 + 고객 요청`() {
        val addr = DeliveryAddress.parse(
            "아파트 103동 1302호",
            customerRequest = "문 앞 놓기"
        )
        val msg = DeliveryTtsBuilder.buildArrival(addr)
        assertNotNull(msg)
        assertTrue("동호수 포함", msg!!.contains("103동 1302호"))
        assertTrue("고객 요청 포함", msg.contains("문 앞 놓기"))
    }

    @Test
    fun `시나리오 B - 도착 + 공동현관 코드 포함`() {
        val addr = DeliveryAddress.parse(
            "아파트 207동 1801호",
            customerRequest = "공동현관 1234 문앞에 두고 문자 주세요"
        )
        val msg = DeliveryTtsBuilder.buildArrival(addr)
        assertNotNull(msg)
        assertTrue("공동현관 코드 포함", msg!!.contains("공동현관 1234"))
    }

    @Test
    fun `시나리오 C - 리마인더`() {
        val addr = DeliveryAddress.parse("아파트", customerRequest = "문앞에 두고 문자 주세요")
        val msg = DeliveryTtsBuilder.buildReminder(addr)
        assertEquals("문앞에 두고 문자 주세요", msg)
    }

    @Test
    fun `시나리오 C - 요청사항 없으면 null`() {
        val addr = DeliveryAddress.parse("아파트 103동 1302호")
        val msg = DeliveryTtsBuilder.buildReminder(addr)
        assertNull(msg) // 발화 X
    }

    @Test
    fun `시나리오 D - 다음 배달지`() {
        val next = DeliveryAddress.parse("아파트 207동 1801호")
        val msg = DeliveryTtsBuilder.buildNextDelivery(next)
        assertEquals("다음 207동 1801호", msg)
    }

    @Test
    fun `시나리오 D - 큐 없으면 null (발화 X)`() {
        val msg = DeliveryTtsBuilder.buildNextDelivery(null)
        assertNull(msg) // 큐 없으면 TTS 없음
    }

    @Test
    fun `시나리오 E - 멀티 픽업 배달 순서`() {
        val deliveries = listOf(
            DeliveryAddress.parse("105동 502호"),
            DeliveryAddress.parse("207동 1801호")
        )
        val msg = DeliveryTtsBuilder.buildMultiPickupNotice(deliveries)
        assertEquals("105동 502호 다음 207동 1801호", msg)
    }

    @Test
    fun `시나리오 E - 빈 리스트 → null`() {
        val msg = DeliveryTtsBuilder.buildMultiPickupNotice(emptyList())
        assertNull(msg)
    }

    // ══ DeliveryFlowManager 상태 관리 ══

    @Test
    fun `onAccepted 후 DELIVERY_NEAR 시 TTS 발화`() {
        val spoken = mutableListOf<String>()
        DeliveryFlowManager.speakCallback = { spoken.add(it) }

        val call = DeliveryCall(
            price = 3500, distance = 2.0, isMulti = false,
            platform = "baemin", destination = "오포아파트 103동 1302호",
            storeName = "KFC 광주태전점"
        )
        DeliveryFlowManager.onAccepted(call, "문 앞 놓기")
        DeliveryFlowManager.onDeliveryNear()

        assertTrue("TTS 발화됨", spoken.isNotEmpty())
        assertTrue("동호수 포함", spoken[0].contains("103동 1302호"))
    }

    @Test
    fun `DELIVERY_NEAR 중복 발화 방지`() {
        val spoken = mutableListOf<String>()
        DeliveryFlowManager.speakCallback = { spoken.add(it) }

        val call = DeliveryCall(
            price = 3500, distance = 2.0, isMulti = false,
            platform = "baemin", destination = "103동 1302호"
        )
        DeliveryFlowManager.onAccepted(call, null)
        DeliveryFlowManager.onDeliveryNear()
        DeliveryFlowManager.onDeliveryNear() // 두 번째

        assertEquals("1회만 발화", 1, spoken.size)
    }

    @Test
    fun `배달 완료 → 다음 배달지 안내`() {
        val spoken = mutableListOf<String>()
        DeliveryFlowManager.speakCallback = { spoken.add(it) }

        DeliveryFlowManager.setMultiDeliveries(listOf(
            DeliveryAddress.parse("103동 1302호"),
            DeliveryAddress.parse("207동 1801호")
        ))
        DeliveryFlowManager.onDeliveryNear() // 첫 배달지 도착
        DeliveryFlowManager.onDeliveryComplete() // 배달 완료

        assertTrue("다음 배달지 안내", spoken.any { it.contains("다음 207동 1801호") })
    }

    @Test
    fun `배달 완료 큐 없음 → 다음 안내 없음`() {
        val spoken = mutableListOf<String>()
        DeliveryFlowManager.speakCallback = { spoken.add(it) }

        val call = DeliveryCall(
            price = 3500, distance = 2.0, isMulti = false,
            platform = "baemin", destination = "103동 1302호"
        )
        DeliveryFlowManager.onAccepted(call, null)
        DeliveryFlowManager.onDeliveryComplete()

        assertFalse("'다음' 안내 없음", spoken.any { it.contains("다음") })
    }
}
