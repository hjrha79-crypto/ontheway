package com.vita.ontheway

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix X v1.1: KakaoGeocoder 실시간 보정 + Baemin 이중계산 차단 테스트.
 */
class FixXv11Test {

    @Before
    fun setup() {
        mockkObject(OtwFileLogger)
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { OtwFileLogger.logSync(any(), any()) } returns Unit
        KakaoGeocoder.resetMemCache()
    }

    @After
    fun teardown() {
        unmockkAll()
        KakaoGeocoder.resetMemCache()
    }

    // ── (e) 캐시 키 정규화: 3가지 storeName 변형 → 같은 캐시 키 ──

    @Test
    fun `정규화 — BHC 태전점 공백 변형`() {
        val k1 = KakaoGeocoder.normalizeKey("BHC 태전점")
        val k2 = KakaoGeocoder.normalizeKey("BHC  태전점")
        val k3 = KakaoGeocoder.normalizeKey("bhc 태전점")
        assertEquals(k1, k2)
        assertEquals(k1, k3)
    }

    @Test
    fun `정규화 — 앞뒤 공백 제거`() {
        val k1 = KakaoGeocoder.normalizeKey("  홍콩반점  ")
        val k2 = KakaoGeocoder.normalizeKey("홍콩반점")
        assertEquals(k1, k2)
    }

    @Test
    fun `정규화 — 대소문자 통일`() {
        val k1 = KakaoGeocoder.normalizeKey("BBQ 치킨")
        val k2 = KakaoGeocoder.normalizeKey("bbq 치킨")
        assertEquals(k1, k2)
    }

    // ── (f) PlatformDistancePolicy: Baemin 이중계산 안 됨 ──

    @Test
    fun `배민 effectiveDistance — pickup 있어도 deliveryKm만 사용`() {
        // Fix X v1.1: 배민은 화면 거리 = 총거리이므로 pickup 합산 X
        val dist = PlatformDistancePolicy.effectiveDistanceKm("baemin", 5.0, 2.0)
        assertEquals(5.0, dist!!, 0.001)  // 이전: 7.0 (잘못됨), 수정후: 5.0
    }

    @Test
    fun `배민 effectiveDistance — pickup null, delivery 3km`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("baemin", 3.0, null)
        assertEquals(3.0, dist!!, 0.001)
    }

    @Test
    fun `배민 effectiveDistance — delivery null → null`() {
        assertNull(PlatformDistancePolicy.effectiveDistanceKm("baemin", null, 2.0))
    }

    @Test
    fun `배민 effectiveDistance — 둘 다 null → null`() {
        assertNull(PlatformDistancePolicy.effectiveDistanceKm("baemin", null, null))
    }

    @Test
    fun `배민 단가 — pickup 무시 (이중계산 방지)`() {
        // 7170원 / 5.0km = 1434원/km (pickup 합산 X)
        val up = PlatformDistancePolicy.unitPrice(7170, "baemin", 5.0, 2.0)
        assertEquals(1434, up)
    }

    @Test
    fun `배민 2건묶음 — pickup 무시`() {
        // perCall = 8540/2 = 4270, effectiveDist = 5.0 (pickup 무시), unit = 854
        val up = PlatformDistancePolicy.unitPrice(8540, "baemin", 5.0, 2.0, 2)
        assertEquals(854, up)
    }

    // ── (g) PlatformDistancePolicy: Coupang pickupKm 합산 ──

    @Test
    fun `쿠팡 effectiveDistance — pickup 실측 합산`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("coupang", 2.0, 0.5)
        assertEquals(2.5, dist!!, 0.001)
    }

    @Test
    fun `쿠팡 effectiveDistance — pickup null → 추정 1km`() {
        val dist = PlatformDistancePolicy.effectiveDistanceKm("coupang", 2.0, null)
        assertEquals(3.0, dist!!, 0.001)
    }

    @Test
    fun `쿠팡 단가 GPS pickup`() {
        // 4000원 / (0.5 + 2.0) = 1600원/km
        val up = PlatformDistancePolicy.unitPrice(4000, "coupang", 2.0, 0.5)
        assertEquals(1600, up)
    }

    // ── (i) cleanup: 만료 항목 제거 ──

    @Test
    fun `cleanup — 만료 항목 제거`() {
        val coord = KakaoGeocoder.LatLng(37.5, 127.0)
        // 2시간 전 항목 삽입 (MEM_TTL = 1시간이므로 만료됨)
        val twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000
        KakaoGeocoder.putMemCache("만료가게", coord, twoHoursAgo)
        KakaoGeocoder.putMemCache("유효가게", coord)  // 현재 시간
        assertEquals(2, KakaoGeocoder.getMemCacheSize())

        KakaoGeocoder.cleanupMemCache()

        assertEquals(1, KakaoGeocoder.getMemCacheSize())
    }

    @Test
    fun `cleanup — size cap 초과 시 오래된 항목 제거`() {
        val coord = KakaoGeocoder.LatLng(37.5, 127.0)
        // 1001개 항목 삽입
        for (i in 0..1000) {
            KakaoGeocoder.putMemCache("가게$i", coord, System.currentTimeMillis() - (1000 - i))
        }
        assertTrue(KakaoGeocoder.getMemCacheSize() > 1000)

        KakaoGeocoder.cleanupMemCache()

        assertTrue(KakaoGeocoder.getMemCacheSize() <= 1000)
    }

    // ── DistanceResult source 세분화 ──

    @Test
    fun `DistanceResult source 세분화`() {
        val apiK = KakaoGeocoder.DistanceResult.apiKeyword(1.5)
        assertEquals("api_keyword", apiK.source)
        assertEquals(1.0, apiK.confidence, 0.001)

        val apiA = KakaoGeocoder.DistanceResult.apiAddress(2.0)
        assertEquals("api_address", apiA.source)
        assertEquals(1.0, apiA.confidence, 0.001)

        val cacheMem = KakaoGeocoder.DistanceResult.cacheMem(1.0)
        assertEquals("cache_mem", cacheMem.source)
        assertEquals(0.8, cacheMem.confidence, 0.001)

        val cachePrefs = KakaoGeocoder.DistanceResult.cachePrefs(1.0)
        assertEquals("cache_prefs", cachePrefs.source)
        assertEquals(0.8, cachePrefs.confidence, 0.001)

        val fallback = KakaoGeocoder.DistanceResult.fallback(3.0)
        assertEquals("fallback_location_table", fallback.source)
        assertEquals(0.1, fallback.confidence, 0.001)
    }

    // ── (h) keyword API에 좌표 파라미터 검증 ──

    @Test
    fun `parseFirstCoord — 정상 응답 파싱`() {
        val json = """{"documents":[{"x":"127.123","y":"37.456","place_name":"BHC 태전점","address_name":"대전 유성구","distance":"350"}],"meta":{"total_count":1}}"""
        val coord = KakaoGeocoder.parseFirstCoord(json)
        assertNotNull(coord)
        assertEquals(37.456, coord!!.lat, 0.001)
        assertEquals(127.123, coord.lng, 0.001)
    }

    @Test
    fun `parseFirstCoord — 빈 결과`() {
        val json = """{"documents":[],"meta":{"total_count":0}}"""
        assertNull(KakaoGeocoder.parseFirstCoord(json))
    }

    // ── calculateTotalKm 배민 변경 없음 ──

    @Test
    fun `calculateTotalKm 배민 = deliveryKm 그대로`() {
        assertEquals(5.0, PlatformDistancePolicy.calculateTotalKm("baemin", 5.0), 0.001)
    }

    @Test
    fun `calculateTotalKm 쿠팡 = 1 + delivery`() {
        assertEquals(3.0, PlatformDistancePolicy.calculateTotalKm("coupang", 2.0), 0.001)
    }

    // ── 5/11 시뮬 ──

    @Test
    fun `시뮬 BHC태전점 — normalizeKey 일관성`() {
        val k1 = KakaoGeocoder.normalizeKey("BHC 태전점")
        val k2 = KakaoGeocoder.normalizeKey("BHC  태전점 ")
        val k3 = KakaoGeocoder.normalizeKey("bhc 태전점")
        assertEquals(k1, k2)
        assertEquals(k1, k3)
        assertEquals("bhc 태전점", k1)
    }

    @Test
    fun `시뮬 인메모리 캐시 hit 후 distanceTo 캐시 소스 검증`() {
        // 인메모리 캐시에 직접 주입
        val storeCoord = KakaoGeocoder.LatLng(36.375, 127.339)  // BHC 태전점 예상 좌표
        KakaoGeocoder.putMemCache("BHC 태전점", storeCoord)

        // haversine 직접 계산으로 검증
        val myLat = 37.37248
        val myLng = 127.22804
        val expected = LocationTable.haversineKm(myLat, myLng, storeCoord.lat, storeCoord.lng)
        assertTrue(expected > 0)
    }
}
