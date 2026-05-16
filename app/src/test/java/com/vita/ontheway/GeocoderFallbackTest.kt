package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * FIX-KAKAO-GEOCODER-FALLBACK: distance source/confidence + UI 표시 분기 테스트.
 */
class GeocoderFallbackTest {

    // ── DistanceResult factory 테스트 ──

    @Test
    fun `cache factory confidence = 0_8`() {
        val r = KakaoGeocoder.DistanceResult.cache(3.5)
        assertEquals(3.5, r.km, 0.01)
        assertEquals("cache_mem", r.source)
        assertEquals(0.8, r.confidence, 0.01)
    }

    @Test
    fun `fallback factory confidence = 0_1`() {
        val r = KakaoGeocoder.DistanceResult.fallback(3.86)
        assertEquals(3.86, r.km, 0.01)
        assertEquals("fallback_location_table", r.source)
        assertEquals(0.1, r.confidence, 0.01)
    }

    @Test
    fun `api factory confidence = 1_0`() {
        val r = KakaoGeocoder.DistanceResult.api(2.1)
        assertEquals("api_keyword", r.source)
        assertEquals(1.0, r.confidence, 0.01)
    }

    @Test
    fun `nls factory confidence = 1_0`() {
        val r = KakaoGeocoder.DistanceResult.nls(4.2)
        assertEquals("nls", r.source)
        assertEquals(1.0, r.confidence, 0.01)
    }

    // ── DeliveryCall distanceSource 전파 ──

    @Test
    fun `DeliveryCall copy propagates distanceSource`() {
        val call = DeliveryCall(price = 3000, distance = null, isMulti = false, platform = "coupang")
        val enriched = call.copy(
            pickupDistanceKm = 5.01,
            distanceSource = KakaoGeocoder.DistanceResult.SOURCE_FALLBACK,
            distanceConfidence = 0.1
        )
        assertEquals(KakaoGeocoder.DistanceResult.SOURCE_FALLBACK, enriched.distanceSource)
        assertEquals(0.1, enriched.distanceConfidence, 0.01)
        assertEquals(5.01, enriched.pickupDistanceKm!!, 0.01)
    }

    // ── OutputController: fallback 시 "약 Xkm" 표시 ──

    private val acceptResult = CallFilter.FilterResult(CallFilter.Verdict.ACCEPT, "테스트")

    @Test
    fun `fallback pickup 표시 = 약Xkm`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = 5.01,
            distanceSource = KakaoGeocoder.DistanceResult.SOURCE_FALLBACK,
            distanceConfidence = 0.1
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("약 포함: $msg", msg!!.contains("약"))
        assertFalse("소수점 없음: $msg", msg.contains("5.0km"))
    }

    @Test
    fun `cache pickup 표시 = 정확 Xkm`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = 2.3,
            distanceSource = KakaoGeocoder.DistanceResult.SOURCE_CACHE,
            distanceConfidence = 0.9
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("2.3km 포함: $msg", msg!!.contains("2.3km"))
        assertFalse("약 없음: $msg", msg.contains("약"))
    }

    @Test
    fun `distanceSource 빈문자열 = 정확 표시 (기본값)`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = 3.0,
            distanceSource = "",
            distanceConfidence = 0.0
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("3.0km 포함: $msg", msg!!.contains("3.0km"))
    }
}
