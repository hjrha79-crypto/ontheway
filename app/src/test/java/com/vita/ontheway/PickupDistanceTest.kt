package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PickupDistanceTest {

    @Test
    fun `Haversine - 광주 태전동 to 오포고산 약 3_5km`() {
        // 태전동 (37.3966, 127.2544) → 오포고산 (37.3636, 127.2069)
        val dist = LocationTable.haversineKm(37.3966, 127.2544, 37.3636, 127.2069)
        // 직선거리 약 5.5km
        assertTrue("거리 3~8km 범위", dist in 3.0..8.0)
    }

    @Test
    fun `Haversine - 동일 좌표 = 0km`() {
        val dist = LocationTable.haversineKm(37.3966, 127.2544, 37.3966, 127.2544)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `Haversine - 서울역 to 강남역 약 8km`() {
        // 서울역 (37.5547, 126.9706) → 강남역 (37.4979, 127.0276)
        val dist = LocationTable.haversineKm(37.5547, 126.9706, 37.4979, 127.0276)
        // 직선거리 약 8km
        assertTrue("거리 7~10km 범위", dist in 7.0..10.0)
    }

    @Test
    fun `ROAD_DISTANCE_FACTOR 적용 검증`() {
        // 직선거리 3km → 도로거리 3.9km (factor 1.3)
        val straightKm = 3.0
        val roadKm = straightKm * OnTheWayService.ROAD_DISTANCE_FACTOR
        assertEquals(3.9, roadKm, 0.01)
    }

    @Test
    fun `DeliveryCall pickupDistanceKm copy 정상 동작`() {
        val call = DeliveryCall(
            price = 3500, distance = 2.0, isMulti = false,
            platform = "baemin", storeName = "KFC 광주태전점"
        )
        assertNull(call.pickupDistanceKm)

        val enriched = call.copy(pickupDistanceKm = 1.5)
        assertEquals(1.5, enriched.pickupDistanceKm!!, 0.01)
        assertEquals("KFC 광주태전점", enriched.storeName)
        assertEquals(3500, enriched.price)
    }
}
