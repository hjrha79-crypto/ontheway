package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProximityDetectorTest {

    @Before
    fun setup() {
        FeatureFlags.proximityTTS = true
        ProximityDetector.listener = null
        ProximityDetector.clearTarget()
        ProximityDetector.resetFiredEvents()
    }

    // ── Haversine 거리 계산 ──

    @Test
    fun haversine_samePoint_isZero() {
        val dist = ProximityDetector.distanceKm(37.5, 127.0, 37.5, 127.0)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun haversine_knownDistance() {
        // 서울역(37.5547, 126.9707) → 광화문(37.5760, 126.9769) ≈ 2.4km
        val dist = ProximityDetector.distanceKm(37.5547, 126.9707, 37.5760, 126.9769)
        assertTrue("서울역→광화문 거리: ${dist}km", dist in 2.0..3.0)
    }

    @Test
    fun haversine_shortDistance_200m() {
        // 약 200m 이동 (위도 0.0018도 ≈ 200m)
        val dist = ProximityDetector.distanceKm(37.5000, 127.0000, 37.5018, 127.0000)
        assertTrue("약 200m: ${dist}km", dist in 0.15..0.25)
    }

    // ── isWithinThreshold ──

    @Test
    fun isWithinThreshold_inside() {
        // 100m 떨어진 �� (< 200m 임계값)
        assertTrue(ProximityDetector.isWithinThreshold(
            37.5000, 127.0000, 37.5009, 127.0000, 0.2
        ))
    }

    @Test
    fun isWithinThreshold_outside() {
        // 500m 떨어진 곳 (> 300m 임계값)
        assertFalse(ProximityDetector.isWithinThreshold(
            37.5000, 127.0000, 37.5045, 127.0000, 0.3
        ))
    }

    // ── DELIVERY_NEAR 이벤트 발생 ──

    @Test
    fun deliveryNear_withinThreshold_firesEvent() {
        val target = ProximityDetector.Target(
            callKey = "test_1",
            deliveryLat = 37.5000,
            deliveryLng = 127.0000,
            storeName = "테스트가게"
        )

        val events = ProximityDetector.checkAndFire(
            37.5001, 127.0000, target  // ~11m 거리
        )

        assertTrue("DELIVERY_NEAR 발생", events.contains(ProximityDetector.ProximityEvent.DELIVERY_NEAR))
    }

    @Test
    fun deliveryNear_outsideThreshold_noEvent() {
        val target = ProximityDetector.Target(
            callKey = "test_2",
            deliveryLat = 37.5000,
            deliveryLng = 127.0000,
            storeName = "테스트가게"
        )

        val events = ProximityDetector.checkAndFire(
            37.5050, 127.0000, target  // ~555m 거리
        )

        assertTrue("이벤트 없음", events.isEmpty())
    }

    // ── PICKUP_NEAR 이벤트 발생 ──

    @Test
    fun pickupNear_withinThreshold_firesEvent() {
        val target = ProximityDetector.Target(
            callKey = "test_3",
            pickupLat = 37.5000,
            pickupLng = 127.0000,
            storeName = "테스트가게"
        )

        val events = ProximityDetector.checkAndFire(
            37.5002, 127.0000, target  // ~22m
        )

        assertTrue("PICKUP_NEAR 발생", events.contains(ProximityDetector.ProximityEvent.PICKUP_NEAR))
    }

    // ── 중복 발화 방지 ──

    @Test
    fun dedup_sameCall_firesOnlyOnce() {
        val target = ProximityDetector.Target(
            callKey = "test_dedup",
            deliveryLat = 37.5000,
            deliveryLng = 127.0000
        )

        val first = ProximityDetector.checkAndFire(37.5001, 127.0000, target)
        val second = ProximityDetector.checkAndFire(37.5001, 127.0000, target)

        assertEquals("첫 번째 발화", 1, first.size)
        assertEquals("두 번째는 발화 없음", 0, second.size)
    }

    @Test
    fun dedup_differentCall_firesBoth() {
        val target1 = ProximityDetector.Target(
            callKey = "call_A",
            deliveryLat = 37.5000,
            deliveryLng = 127.0000
        )
        val target2 = ProximityDetector.Target(
            callKey = "call_B",
            deliveryLat = 37.5000,
            deliveryLng = 127.0000
        )

        val first = ProximityDetector.checkAndFire(37.5001, 127.0000, target1)
        val second = ProximityDetector.checkAndFire(37.5001, 127.0000, target2)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    // ── 좌표 없는 경우 null 처리 ──

    @Test
    fun nullCoords_noEvents() {
        val target = ProximityDetector.Target(
            callKey = "test_null",
            pickupLat = null,
            pickupLng = null,
            deliveryLat = null,
            deliveryLng = null
        )

        val events = ProximityDetector.checkAndFire(37.5000, 127.0000, target)
        assertTrue("좌표 없으면 이벤트 없음", events.isEmpty())
    }

    @Test
    fun partialCoords_onlyAvailableChecked() {
        // 픽업 좌표만 있고 배달 좌표 없음
        val target = ProximityDetector.Target(
            callKey = "test_partial",
            pickupLat = 37.5000,
            pickupLng = 127.0000,
            deliveryLat = null,
            deliveryLng = null
        )

        val events = ProximityDetector.checkAndFire(37.5001, 127.0000, target)
        assertEquals(1, events.size)
        assertEquals(ProximityDetector.ProximityEvent.PICKUP_NEAR, events[0])
    }

    // ── 리스너 콜백 확인 ──

    @Test
    fun listener_receivesCallback() {
        var receivedEvent: ProximityDetector.ProximityEvent? = null
        ProximityDetector.listener = { event, _ -> receivedEvent = event }

        val target = ProximityDetector.Target(
            callKey = "test_cb",
            deliveryLat = 37.5000,
            deliveryLng = 127.0000
        )
        ProximityDetector.checkAndFire(37.5001, 127.0000, target)

        assertEquals(ProximityDetector.ProximityEvent.DELIVERY_NEAR, receivedEvent)
    }
}
