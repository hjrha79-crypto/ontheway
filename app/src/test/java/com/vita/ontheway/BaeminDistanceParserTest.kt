package com.vita.ontheway

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * FIX-BAEMIN-DISTANCE-CALCULATION: 배민 화면 거리 추출 테스트.
 */
class BaeminDistanceParserTest {

    @Before
    fun setup() {
        mockkObject(OtwFileLogger)
        every { OtwFileLogger.log(any(), any()) } returns Unit
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── 기존 "배달료기준거리" 패턴 (1순위) ──

    @Test
    fun `배달료기준거리 1065m → 1점065km`() {
        val texts = listOf("배달료기준거리 (1,065m)", "배달료 7,010원")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNotNull(dist)
        assertEquals(1.065, dist!!, 0.001)
    }

    @Test
    fun `배달료기준거리 4300m → 4점3km`() {
        val texts = listOf("배달료기준거리 (4,300m)", "배달료 3,500원")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(4.3, dist!!, 0.001)
    }

    // ── 화면 km 패턴 (2순위) ──

    @Test
    fun `약 5km → 5점0`() {
        val texts = listOf("약 5km", "배달료 7,170원")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(5.0, dist!!, 0.001)
    }

    @Test
    fun `약 1점2km → 1점2`() {
        val texts = listOf("약 1.2km", "가게명")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(1.2, dist!!, 0.001)
    }

    @Test
    fun `5점58km → 5점58`() {
        val texts = listOf("5.58km", "기타 텍스트")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(5.58, dist!!, 0.001)
    }

    @Test
    fun `0점8km → 0점8`() {
        val texts = listOf("0.8km")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(0.8, dist!!, 0.001)
    }

    @Test
    fun `2점1km 소문자`() {
        val texts = listOf("2.1Km")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(2.1, dist!!, 0.001)
    }

    // ── m 단위 (3순위) ──

    @Test
    fun `약 800m → 0점8km`() {
        val texts = listOf("약 800m")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(0.8, dist!!, 0.001)
    }

    @Test
    fun `1200m → 1점2km`() {
        val texts = listOf("1200m 거리")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(1.2, dist!!, 0.001)
    }

    // ── false positive 방지 ──

    @Test
    fun `5분 → null (시간 단위)`() {
        val texts = listOf("5분", "대기 시간 3분")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull(dist)
    }

    @Test
    fun `5000원만 → null (가격 텍스트)`() {
        val texts = listOf("배달료 5,000원", "총합계 12,000원")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull(dist)
    }

    @Test
    fun `38P3 → null (포인트 단위)`() {
        val texts = listOf("38.3P")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull(dist)
    }

    // ── 엣지케이스 ──

    @Test
    fun `빈 텍스트 리스트 → null`() {
        val dist = BaeminParser.extractScreenDistance(emptyList())
        assertNull(dist)
    }

    @Test
    fun `거리 텍스트 없음 → null`() {
        val texts = listOf("배민배달", "조리완료", "가게명", "배달료 4,000원")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull(dist)
    }

    @Test
    fun `범위 초과 100km → null`() {
        val texts = listOf("100km")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull(dist)
    }

    @Test
    fun `범위 미달 0점05km → null`() {
        val texts = listOf("0.05km")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull(dist)
    }

    @Test
    fun `다중 거리 — 첫 번째 유효값 사용`() {
        // 배달료기준거리가 있으면 그것 사용 (1순위)
        val texts = listOf("배달료기준거리 (3,691m)", "약 5km")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(3.691, dist!!, 0.001)
    }

    @Test
    fun `다중 km — 배달료기준거리 없으면 첫 번째 km 사용`() {
        val texts = listOf("약 5km", "1.2km 거리")
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(5.0, dist!!, 0.001)
    }

    // ── 5/9 실제 데이터 시뮬 ──

    @Test
    fun `천년가짬뽕 시뮬 — 배달료 7170원 약 5km`() {
        val texts = listOf(
            "배민배달", "조리완료", "픽업지", "천년가짬뽕 태전본점",
            "전달지", "광주광역시 광산구 월계동",
            "배달료", "7,170원", "약 5km"
        )
        val dist = BaeminParser.extractScreenDistance(texts)
        assertEquals(5.0, dist!!, 0.001)
    }

    // ── Fix B: NLS distance cache ──

    @Test
    fun `NLS distance cache — 저장 후 조회`() {
        BaeminParser.resetDedupCache()
        BaeminParser.cacheNlsDistance("baemin", 7170, 5.3)
        val cached = BaeminParser.getCachedNlsDistance("baemin", 7170)
        assertNotNull(cached)
        assertEquals(5.3, cached!!, 0.001)
    }

    @Test
    fun `NLS distance cache — 다른 가격 miss`() {
        BaeminParser.resetDedupCache()
        BaeminParser.cacheNlsDistance("baemin", 7170, 5.3)
        val cached = BaeminParser.getCachedNlsDistance("baemin", 3500)
        assertNull(cached)
    }

    @Test
    fun `NLS distance cache — 다른 플랫폼 miss`() {
        BaeminParser.resetDedupCache()
        BaeminParser.cacheNlsDistance("baemin", 7170, 5.3)
        val cached = BaeminParser.getCachedNlsDistance("coupang", 7170)
        assertNull(cached)
    }

    @Test
    fun `NLS distance cache — reset 후 miss`() {
        BaeminParser.cacheNlsDistance("baemin", 7170, 5.3)
        BaeminParser.resetDedupCache()
        val cached = BaeminParser.getCachedNlsDistance("baemin", 7170)
        assertNull(cached)
    }

    // ── Fix B: 배민 접근성 distance=null 시나리오 ──

    @Test
    fun `배민 접근성 전형적 텍스트 — distance null (정상)`() {
        // 접근성 트리에 거리 텍스트 없음 → NLS cross-source 필요
        val texts = listOf(
            "배민배달", "조리완료", "픽업지", "천년가짬뽕 태전본점",
            "전달지", "광주광역시 광산구 월계동",
            "배달료", "7,170원"
        )
        val dist = BaeminParser.extractScreenDistance(texts)
        assertNull("접근성 트리에 거리 텍스트 없으면 null", dist)
    }

    @Test
    fun `NLS distance parseNlsDistance 정상`() {
        val text = "[1건 단일] 2,700원 / 0.7km오후 4:49주문을 수락해주세요."
        val dist = BaeminParser.parseNlsDistance(text)
        assertNotNull(dist)
        assertEquals(0.7, dist!!, 0.001)
    }

    @Test
    fun `NLS distance parseNlsDistance 묶음`() {
        val text = "[2건 묶음] 12,123원 / 7.6km오전 11:56"
        val dist = BaeminParser.parseNlsDistance(text)
        assertNotNull(dist)
        assertEquals(7.6, dist!!, 0.001)
    }
}
