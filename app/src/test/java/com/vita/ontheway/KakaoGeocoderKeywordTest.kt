package com.vita.ontheway

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix X: KakaoGeocoder keyword search + 캐시 + haversine 테스트.
 */
class KakaoGeocoderKeywordTest {

    @Before
    fun setup() {
        KakaoGeocoder.resetMemCache()
    }

    @After
    fun teardown() {
        KakaoGeocoder.resetMemCache()
    }

    // ── parseFirstCoord ──

    @Test
    fun `parseFirstCoord keyword 응답 정상 파싱`() {
        val json = """
        {
          "documents": [
            {"place_name": "BHC 태전점", "x": "127.22804", "y": "37.37248"}
          ],
          "meta": {"total_count": 1}
        }
        """.trimIndent()
        val result = KakaoGeocoder.parseFirstCoord(json)
        assertNotNull(result)
        assertEquals(37.37248, result!!.lat, 0.0001)
        assertEquals(127.22804, result.lng, 0.0001)
    }

    @Test
    fun `parseFirstCoord address 응답 정상 파싱`() {
        val json = """
        {
          "documents": [
            {"address_name": "대전광역시 대덕구 태전동", "x": "127.43", "y": "36.39"}
          ]
        }
        """.trimIndent()
        val result = KakaoGeocoder.parseFirstCoord(json)
        assertNotNull(result)
        assertEquals(36.39, result!!.lat, 0.01)
    }

    @Test
    fun `parseFirstCoord documents 비어있음 → null`() {
        val json = """{"documents": [], "meta": {"total_count": 0}}"""
        assertNull(KakaoGeocoder.parseFirstCoord(json))
    }

    @Test
    fun `parseFirstCoord 잘못된 JSON → null`() {
        assertNull(KakaoGeocoder.parseFirstCoord("not json"))
    }

    @Test
    fun `parseFirstCoord 좌표 0,0 → null`() {
        val json = """{"documents": [{"x": "0.0", "y": "0.0"}]}"""
        assertNull(KakaoGeocoder.parseFirstCoord(json))
    }

    // ── Haversine (LocationTable 사용) ──

    @Test
    fun `haversine 1km 정확도`() {
        // 서울 시청 → ~1km 북쪽
        val dist = LocationTable.haversineKm(37.5665, 126.9780, 37.5755, 126.9780)
        assertTrue("Expected ~1km, got $dist", dist > 0.9 && dist < 1.1)
    }

    @Test
    fun `haversine 5km 정확도`() {
        // 서울 시청 → 강남역 (~5km)
        val dist = LocationTable.haversineKm(37.5665, 126.9780, 37.4979, 127.0276)
        assertTrue("Expected ~8km, got $dist", dist > 6 && dist < 10)
    }

    @Test
    fun `haversine 동일 좌표 → 0`() {
        val dist = LocationTable.haversineKm(37.5665, 126.9780, 37.5665, 126.9780)
        assertEquals(0.0, dist, 0.001)
    }

    // ── 인메모리 캐시 ──

    @Test
    fun `memCache 기본 비어있음`() {
        assertEquals(0, KakaoGeocoder.getMemCacheSize())
    }
}
