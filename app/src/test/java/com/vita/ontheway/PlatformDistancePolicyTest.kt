package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * 플랫폼별 거리 정책 테스트.
 * v70.9.2: 쿠팡 픽업 추정 1.0km 추가.
 */
class PlatformDistancePolicyTest {

    // ── BAEMIN: 화면 거리 = 총거리 ──

    // Fix X v1.1: 배민 = 화면 거리(deliveryKm) 그대로, pickupKm 합산 X
    @Test
    fun `배민 pickup 2km + delivery 3km = 3km (pickup 무시)`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("baemin", 3.0, 2.0)
        assertEquals(3.0, dist!!, 0.001)  // Fix X v1.1: 이전 5.0 → 3.0
    }

    @Test
    fun `배민 pickup null + delivery 3km = 3km`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("baemin", 3.0, null)
        assertEquals(3.0, dist!!, 0.001)
    }

    @Test
    fun `배민 pickup 2km + delivery null = null`() {
        // Fix X v1.1: deliveryKm = null이면 null (pickup만으로는 판단 X)
        assertNull(PlatformDistancePolicy.effectiveDistanceKm("baemin", null, 2.0))
    }

    @Test
    fun `배민 둘 다 null = null`() {
        assertNull(PlatformDistancePolicy.effectiveDistanceKm("baemin", null, null))
    }

    // ── COUPANG: 배달거리 + 픽업 추정 ──

    @Test
    fun `쿠팡 delivery 2km + 픽업 추정 1km = 3km`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("coupang", 2.0, null)
        assertEquals(3.0, dist!!, 0.001) // 1.0 + 2.0
    }

    @Test
    fun `쿠팡 delivery 2km + GPS pickup 0점5km = 2점5km`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("coupang", 2.0, 0.5)
        assertEquals(2.5, dist!!, 0.001) // GPS 실측 우선
    }

    @Test
    fun `쿠팡 delivery null = null`() {
        assertNull(PlatformDistancePolicy.effectiveDistanceKm("coupang", null, null))
    }

    @Test
    fun `쿠팡 delivery 0점6km = 1점6km`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("coupang", 0.6, null)
        assertEquals(1.6, dist!!, 0.001)
    }

    // ── 단가 계산 ──

    @Test
    fun `배민 단가 = price 나누기 deliveryKm (pickup 무시)`() {
        // Fix X v1.1: 배민 effectiveDist = deliveryKm = 3.0, 7170/3.0 = 2390
        val up = PlatformDistancePolicy.unitPrice(7170, "baemin", 3.0, 2.0)
        assertEquals(2390, up)
    }

    @Test
    fun `쿠팡 단가 = price 나누기 (추정픽업 + delivery)`() {
        // 3450원 / (1.0 + 2.0) = 1150원/km
        val up = PlatformDistancePolicy.unitPrice(3450, "coupang", 2.0, null)
        assertEquals(1150, up)
    }

    @Test
    fun `쿠팡 단가 GPS pickup 사용`() {
        // 4000원 / (0.5 + 2.0) = 1600원/km
        val up = PlatformDistancePolicy.unitPrice(4000, "coupang", 2.0, 0.5)
        assertEquals(1600, up)
    }

    @Test
    fun `거리 null → 단가 0`() {
        assertEquals(0, PlatformDistancePolicy.unitPrice(5000, "baemin", null, null))
    }

    @Test
    fun `거리 0 → 단가 0`() {
        assertEquals(0, PlatformDistancePolicy.unitPrice(5000, "baemin", 0.0, 0.0))
    }

    // ── calculateTotalKm ──

    @Test
    fun `calculateTotalKm 배민 = deliveryKm 그대로`() {
        assertEquals(5.0, PlatformDistancePolicy.calculateTotalKm("baemin", 5.0), 0.001)
    }

    @Test
    fun `calculateTotalKm 쿠팡 = 1 + delivery`() {
        assertEquals(3.0, PlatformDistancePolicy.calculateTotalKm("coupang", 2.0), 0.001)
    }

    @Test
    fun `calculateTotalKm 쿠팡 GPS pickup`() {
        assertEquals(2.5, PlatformDistancePolicy.calculateTotalKm("coupang", 2.0, 0.5), 0.001)
    }

    // ── calculatePickupKm ──

    @Test
    fun `calculatePickupKm 배민 null (GPS 없으면)`() {
        assertNull(PlatformDistancePolicy.calculatePickupKm("baemin"))
    }

    @Test
    fun `calculatePickupKm 배민 GPS 있으면 반환`() {
        assertEquals(1.5, PlatformDistancePolicy.calculatePickupKm("baemin", 1.5)!!, 0.001)
    }

    @Test
    fun `calculatePickupKm 쿠팡 추정 1km`() {
        assertEquals(1.0, PlatformDistancePolicy.calculatePickupKm("coupang")!!, 0.001)
    }

    @Test
    fun `calculatePickupKm 쿠팡 GPS 우선`() {
        assertEquals(0.8, PlatformDistancePolicy.calculatePickupKm("coupang", 0.8)!!, 0.001)
    }

    // ── pickupDistanceSource ──

    @Test
    fun `pickupDistanceSource 쿠팡 추정`() {
        assertEquals("estimated", PlatformDistancePolicy.pickupDistanceSource("coupang", null))
    }

    @Test
    fun `pickupDistanceSource 쿠팡 GPS`() {
        assertEquals("gps_calculated", PlatformDistancePolicy.pickupDistanceSource("coupang", 1.5))
    }

    @Test
    fun `pickupDistanceSource 배민 null`() {
        assertNull(PlatformDistancePolicy.pickupDistanceSource("baemin", null))
    }

    // ── 5/9 시뮬 ──

    @Test
    fun `5-9 쿠팡 시뮬 3450원 2km → 단가 1150`() {
        assertEquals(1150, PlatformDistancePolicy.unitPrice(3450, "coupang", 2.0, null))
    }

    @Test
    fun `5-9 쿠팡 시뮬 3000원 0점6km → 단가 1875`() {
        assertEquals(1875, PlatformDistancePolicy.unitPrice(3000, "coupang", 0.6, null))
    }

    @Test
    fun `5-9 쿠팡 시뮬 8550원 3점1km → 단가 2085`() {
        assertEquals(2085, PlatformDistancePolicy.unitPrice(8550, "coupang", 3.1, null))
    }

    @Test
    fun `5-9 배민 시뮬 7170원 5km → 단가 1434 (변경 없음)`() {
        assertEquals(1434, PlatformDistancePolicy.unitPrice(7170, "baemin", 5.0, null))
    }

    // ── Fix D: bundleCount 반영 단가 ──

    @Test
    fun `쿠팡 2건묶음 4200원 1점8km → 건당 1167`() {
        // 4200/2 = 2100 건당, 2100 / 1.8 = 1166.67 → 1166
        val up = PlatformDistancePolicy.unitPrice(4200, "coupang", 1.8, null, 2)
        // totalKm = 1.0(추정) + 1.8 = 2.8  → 2100/2.8 = 750
        assertEquals(750, up)
    }

    @Test
    fun `쿠팡 2건묶음 4200원 delivery만 → 건당 750`() {
        // effectiveDistance = 1.0 + 1.8 = 2.8, perCall = 4200/2 = 2100
        // unitPrice = 2100 / 2.8 = 750
        assertEquals(750, PlatformDistancePolicy.unitPrice(4200, "coupang", 1.8, null, 2))
    }

    @Test
    fun `배민 2건묶음 8540원 delivery 3km → 건당 1423 (pickup 무시)`() {
        // Fix X v1.1: perCall = 8540/2 = 4270, totalKm = 3.0 (pickup 무시), 4270/3 = 1423
        assertEquals(1423, PlatformDistancePolicy.unitPrice(8540, "baemin", 3.0, 2.0, 2))
    }

    @Test
    fun `bundleCount 1 = 기존과 동일`() {
        val withBundle = PlatformDistancePolicy.unitPrice(7170, "baemin", 5.0, null, 1)
        val without = PlatformDistancePolicy.unitPrice(7170, "baemin", 5.0, null)
        assertEquals(withBundle, without)
    }

    @Test
    fun `bundleCount 0 → 1로 coerce`() {
        val up = PlatformDistancePolicy.unitPrice(7170, "baemin", 5.0, null, 0)
        assertEquals(1434, up) // same as bundleCount=1
    }

    @Test
    fun `price 0 → 0`() {
        assertEquals(0, PlatformDistancePolicy.unitPrice(0, "baemin", 5.0, null, 1))
    }

    @Test
    fun `unknown platform → deliveryKm 기반`() {
        // unknown platform: effectiveDistance = deliveryKm = 3.0, unitPrice = 5000/3 = 1666
        assertEquals(1666, PlatformDistancePolicy.unitPrice(5000, "unknown", 3.0, null, 1))
    }

    // ── calculate ──

    @Test
    fun `calculate 쿠팡 결과 — 픽업 추정 포함`() {
        val result = PlatformDistancePolicy.calculate("coupang", 2.0, null, "notification", 0.9)
        assertEquals(1.0, result.pickupKm!!, 0.001)  // 추정 1.0km
        assertEquals(2.0, result.deliveryKm!!, 0.001)
        assertEquals(3.0, result.totalKm!!, 0.001)    // 1.0 + 2.0
    }
}
