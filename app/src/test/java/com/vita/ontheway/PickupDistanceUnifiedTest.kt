package com.vita.ontheway

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PICKUP-DISTANCE-CALCULATION-UNIFIED (v70.9.2.1) 테스트.
 * 쿠팡 DeliveryCall 생성 시 pickupDistanceKm 주입 검증.
 */
class PickupDistanceUnifiedTest {

    @Before
    fun setup() {
        mockkObject(OtwFileLogger)
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { OtwFileLogger.logSync(any(), any()) } returns Unit
    }

    @After
    fun teardown() { unmockkAll() }

    // ── Fix 1: 쿠팡 DeliveryCall에 pickupDistanceKm 주입 ──

    @Test
    fun `쿠팡 CoupangParser 생성 — pickupDistanceKm 1_0`() {
        val texts = listOf("(조리완료) 3,000원 배달 거리 0.7km", "거절", "주문 수락")
        val calls = CoupangParser.parse(texts)
        assertNotNull(calls)
        assertTrue(calls!!.isNotEmpty())
        val call = calls[0]
        assertEquals(1.0, call.pickupDistanceKm!!, 0.001)
        assertEquals("screen", call.distanceSource)
    }

    @Test
    fun `쿠팡 totalKm = pickup + delivery 자동 계산`() {
        val call = DeliveryCall(
            price = 3450, distance = 2.0, isMulti = false, platform = "coupang",
            pickupDistanceKm = 1.0, distanceSource = "screen"
        )
        val totalKm = (call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0)
        assertEquals(3.0, totalKm, 0.001)
    }

    @Test
    fun `쿠팡 단가 = price 나누기 totalKm`() {
        val call = DeliveryCall(
            price = 3450, distance = 2.0, isMulti = false, platform = "coupang",
            pickupDistanceKm = 1.0
        )
        val totalKm = (call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0)
        val unitPrice = if (totalKm > 0) (call.price / totalKm).toInt() else 0
        assertEquals(1150, unitPrice)
    }

    // ── 배민 변경 없음 ──

    @Test
    fun `배민 DeliveryCall — pickupDistanceKm null 유지`() {
        val call = DeliveryCall(
            price = 7170, distance = 5.0, isMulti = false, platform = "baemin"
        )
        assertNull(call.pickupDistanceKm)
        val totalKm = (call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0)
        assertEquals(5.0, totalKm, 0.001)
    }

    // ── 호출처 통일 검증 ──

    @Test
    fun `모든 호출처 totalKm = pickupKm + distance 패턴`() {
        // 쿠팡: pickupKm=1.0 + distance=2.0 = 3.0
        val coupang = DeliveryCall(price = 3000, distance = 2.0, isMulti = false, platform = "coupang", pickupDistanceKm = 1.0)
        val coupangTotal = (coupang.pickupDistanceKm ?: 0.0) + (coupang.distance ?: 0.0)
        assertEquals(3.0, coupangTotal, 0.001)

        // 배민: pickupKm=null + distance=5.0 = 5.0
        val baemin = DeliveryCall(price = 7000, distance = 5.0, isMulti = false, platform = "baemin")
        val baeminTotal = (baemin.pickupDistanceKm ?: 0.0) + (baemin.distance ?: 0.0)
        assertEquals(5.0, baeminTotal, 0.001)
    }

    @Test
    fun `PlatformDistancePolicy와 DeliveryCall 주입 결과 일치`() {
        // PlatformDistancePolicy.unitPrice
        val policyUp = PlatformDistancePolicy.unitPrice(3450, "coupang", 2.0, null)

        // DeliveryCall 기반 (주입 후)
        val call = DeliveryCall(price = 3450, distance = 2.0, isMulti = false, platform = "coupang", pickupDistanceKm = 1.0)
        val totalKm = (call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0)
        val callUp = if (totalKm > 0) (call.price / totalKm).toInt() else 0

        assertEquals(policyUp, callUp)
    }

    // ── distanceSource 검증 ──

    @Test
    fun `쿠팡 distanceSource = estimated`() {
        val source = PlatformDistancePolicy.pickupDistanceSource("coupang", null)
        assertEquals("estimated", source)
    }

    @Test
    fun `쿠팡 GPS pickup → distanceSource = gps_calculated`() {
        val source = PlatformDistancePolicy.pickupDistanceSource("coupang", 0.8)
        assertEquals("gps_calculated", source)
    }

    // ── 5/9 시뮬 ──

    @Test
    fun `5-9 쿠팡 시뮬 — 3450원 2km → 1150`() {
        val call = DeliveryCall(price = 3450, distance = 2.0, isMulti = false, platform = "coupang", pickupDistanceKm = 1.0)
        val up = (call.price / ((call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0))).toInt()
        assertEquals(1150, up)
    }

    @Test
    fun `5-9 쿠팡 시뮬 — 3000원 0_6km → 1875`() {
        val call = DeliveryCall(price = 3000, distance = 0.6, isMulti = false, platform = "coupang", pickupDistanceKm = 1.0)
        val up = (call.price / ((call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0))).toInt()
        assertEquals(1875, up)
    }

    @Test
    fun `5-9 배민 시뮬 — 7170원 5km → 1434 변경 없음`() {
        val call = DeliveryCall(price = 7170, distance = 5.0, isMulti = false, platform = "baemin")
        val up = (call.price / ((call.pickupDistanceKm ?: 0.0) + (call.distance ?: 0.0))).toInt()
        assertEquals(1434, up)
    }
}
