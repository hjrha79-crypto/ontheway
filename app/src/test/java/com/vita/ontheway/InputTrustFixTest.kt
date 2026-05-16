package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix IT-1: fallback overwrite 가능 구조 + LocationSnapshot 검증.
 *
 * 5개 필수 테스트:
 * 1. API timeout → fallback = low-trust
 * 2. fallback 이후 API late result → overwrite
 * 3. fallback pickupKm 상태에서 updatePickupDistanceBySessionId 동작
 * 4. distance_confidence < 0.8 → low-trust 판정
 * 5. 300m→5km fallback → API overwrite 시나리오 재현
 */
class InputTrustFixTest {

    private lateinit var ctx: Context

    private fun mockContext(
        minPrice: Int = 2500,
        minUnitPrice: Int = 1200,
        multiMinPrice: Int = 5000,
        highPriceThreshold: Int = 7000
    ): Context {
        val mockPrefs = mockk<SharedPreferences>()
        every { mockPrefs.getInt("min_price", any()) } returns minPrice
        every { mockPrefs.getInt("min_unit_price", any()) } returns minUnitPrice
        every { mockPrefs.getInt("multi_min_price", any()) } returns multiMinPrice
        every { mockPrefs.getInt("high_price_threshold", any()) } returns highPriceThreshold
        every { mockPrefs.getInt(not(match { it in listOf("min_price", "min_unit_price", "multi_min_price", "high_price_threshold") }), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }

        val c = mockk<Context>()
        every { c.getSharedPreferences(any(), any()) } returns mockPrefs
        return c
    }

    @Before
    fun setup() {
        mockkObject(OtwFileLogger)
        every { OtwFileLogger.log(any(), any()) } returns Unit
        ctx = mockContext()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── 1. API timeout → fallback = source=fallback, confidence=0.1, low-trust ──

    @Test
    fun `fallback result has low confidence and is not high trust`() {
        val result = PickupDistanceResolver.Result.Success(
            km = 5.2,
            source = KakaoGeocoder.DistanceResult.SOURCE_FALLBACK,
            confidence = 0.1
        )
        assertEquals("fallback_location_table", result.source)
        assertEquals(0.1, result.confidence, 0.001)
        assertFalse("fallback should not be high-trust", result.isHighTrust())
    }

    @Test
    fun `fallback source is in LOW_TRUST_SOURCES`() {
        assertTrue(KakaoGeocoder.DistanceResult.SOURCE_FALLBACK in PickupDistanceResolver.LOW_TRUST_SOURCES)
        assertTrue("fallback" in PickupDistanceResolver.LOW_TRUST_SOURCES)
        assertTrue("estimated" in PickupDistanceResolver.LOW_TRUST_SOURCES)
        assertTrue("" in PickupDistanceResolver.LOW_TRUST_SOURCES)
    }

    @Test
    fun `api and cache results are high trust`() {
        val apiResult = PickupDistanceResolver.Result.Success(
            km = 1.5, source = KakaoGeocoder.DistanceResult.SOURCE_API_KEYWORD, confidence = 1.0
        )
        assertTrue("api_keyword should be high-trust", apiResult.isHighTrust())

        val cacheResult = PickupDistanceResolver.Result.Success(
            km = 1.5, source = KakaoGeocoder.DistanceResult.SOURCE_CACHE_MEM, confidence = 0.8
        )
        assertTrue("cache_mem should be high-trust", cacheResult.isHighTrust())

        val cachePResult = PickupDistanceResolver.Result.Success(
            km = 1.5, source = KakaoGeocoder.DistanceResult.SOURCE_CACHE_PREFS, confidence = 0.8
        )
        assertTrue("cache_prefs should be high-trust", cachePResult.isHighTrust())

        val apiAddrResult = PickupDistanceResolver.Result.Success(
            km = 1.5, source = KakaoGeocoder.DistanceResult.SOURCE_API_ADDRESS, confidence = 1.0
        )
        assertTrue("api_address should be high-trust", apiAddrResult.isHighTrust())
    }

    // ── 2. fallback 이후 API late result → overwrite ──

    @Test
    fun `late API result can overwrite fallback via isHighTrust check`() {
        // Simulate: initial = fallback (low-trust)
        val initial = PickupDistanceResolver.Result.Success(
            km = 5.2, source = "fallback_location_table", confidence = 0.1
        )
        assertFalse(initial.isHighTrust())

        // Simulate: late = API (high-trust)
        val late = PickupDistanceResolver.Result.Success(
            km = 1.3, source = "api_keyword", confidence = 1.0
        )
        assertTrue(late.isHighTrust())

        // DB WHERE clause allows overwrite when source is low-trust:
        // pickup_distance_source IN ('fallback', 'fallback_location_table', 'estimated', '')
        assertTrue("fallback_location_table" in PickupDistanceResolver.LOW_TRUST_SOURCES)
    }

    @Test
    fun `high trust result should NOT be overwritten by another high trust`() {
        // Once we have a high-trust result, it should not be in LOW_TRUST_SOURCES
        val apiSource = "api_keyword"
        assertFalse(apiSource in PickupDistanceResolver.LOW_TRUST_SOURCES)
    }

    // ── 3. fallback pickupKm에서도 updatePickupDistanceBySessionId 동작 ──

    @Test
    fun `LOW_TRUST_SOURCES includes all fallback variants for DB WHERE clause`() {
        // The SQL WHERE clause: pickup_distance_source IN ('fallback', 'fallback_location_table', 'estimated', '')
        val dbLowTrustSources = setOf("fallback", "fallback_location_table", "estimated", "")
        assertEquals(PickupDistanceResolver.LOW_TRUST_SOURCES, dbLowTrustSources)
    }

    @Test
    fun `empty source is low trust - enables overwrite for legacy records`() {
        assertTrue("" in PickupDistanceResolver.LOW_TRUST_SOURCES)
    }

    // ── 4. distance_confidence < 0.8 → CallFilter low-trust 처리 ──

    @Test
    fun `low confidence pickup distance excluded from unitPrice calculation - coupang`() {
        // Coupang: distance_confidence=0.1 (fallback) → pickupKm should be ignored in verdict
        // Coupang includes pickupKm in unit price unlike baemin
        val call = DeliveryCall(
            price = 4000, distance = 2.0, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = 5.0,  // fallback: 5km (오염)
            distanceConfidence = 0.1  // low-trust
        )
        val result = CallFilter.judge(call, ctx)
        // Without low-trust handling: unitPrice = 4000/(5.0+2.0) = 571원/km → REJECT
        // With low-trust handling: pickupKm ignored → unitPrice via coupang estimated 1.0 + 2.0 = 3.0 → 4000/3.0 = 1333원/km → ACCEPT
        assertEquals("low-trust pickup should be excluded", CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("reason should mention 신뢰도 낮음", result.reason.contains("신뢰도 낮음"))
    }

    @Test
    fun `high confidence pickup distance included in unitPrice calculation - coupang`() {
        // Coupang includes pickupKm in unit price (unlike baemin where screen distance = total)
        val call = DeliveryCall(
            price = 4000, distance = 2.0, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = 5.0,
            distanceConfidence = 1.0  // high-trust (API)
        )
        val result = CallFilter.judge(call, ctx)
        // unitPrice = 4000/(5.0+2.0) = 571원/km < 1200원/km → REJECT
        assertEquals("high-trust pickup should be included", CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("reason should mention 단가", result.reason.contains("단가"))
    }

    @Test
    fun `zero confidence does not trigger low trust - it means no pickup distance`() {
        // distanceConfidence=0.0 means no pickup distance was measured (default)
        val call = DeliveryCall(
            price = 4000, distance = 2.0, isMulti = false,
            platform = "baemin",
            pickupDistanceKm = null,
            distanceConfidence = 0.0
        )
        val result = CallFilter.judge(call, ctx)
        // No pickup → unitPrice = 4000/2.0 = 2000원/km → ACCEPT
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertFalse("should not mention 신뢰도 낮음", result.reason.contains("신뢰도 낮음"))
    }

    // ── 5. 300m→5km fallback 시나리오 재현 ──

    @Test
    fun `300m actual distance with 5km fallback - fallback stays low trust and overwritable`() {
        // Scenario: store is 300m away, but fallback LocationTable returns coarse coord → 5km
        val fallbackResult = PickupDistanceResolver.Result.Success(
            km = 5.0,  // coarse fallback (실제 300m인데 5km로 계산)
            source = "fallback_location_table",
            confidence = 0.1
        )
        assertFalse("fallback must be low-trust", fallbackResult.isHighTrust())

        // CallFilter should not use this 5km as reliable pickup distance (coupang includes pickup in unit price)
        val call = DeliveryCall(
            price = 4000, distance = 2.0, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = fallbackResult.km,
            distanceConfidence = fallbackResult.confidence
        )
        val filterResult = CallFilter.judge(call, ctx)
        // Without fix: 4000/(5.0+2.0) = 571원/km → REJECT (wrong)
        // With fix: pickupKm ignored → coupang uses estimated 1.0 + 2.0 = 3.0 → 4000/3.0 = 1333원/km → ACCEPT (correct)
        assertEquals("fallback 5km should not cause REJECT", CallFilter.Verdict.ACCEPT, filterResult.verdict)

        // Later, API returns accurate result
        val apiResult = PickupDistanceResolver.Result.Success(
            km = 0.4,  // 실제 300m * ROAD_FACTOR
            source = "api_keyword",
            confidence = 1.0
        )
        assertTrue("API result is high-trust", apiResult.isHighTrust())

        // Verify overwrite is possible (source is in LOW_TRUST_SOURCES)
        assertTrue("fallback_location_table" in PickupDistanceResolver.LOW_TRUST_SOURCES)

        // Final pickupKm after overwrite = 0.4km (accurate)
        val finalCall = call.copy(
            pickupDistanceKm = apiResult.km,
            distanceConfidence = apiResult.confidence
        )
        val finalResult = CallFilter.judge(finalCall, ctx)
        assertEquals("accurate pickup should still ACCEPT", CallFilter.Verdict.ACCEPT, finalResult.verdict)
        assertFalse("should not have 신뢰도 낮음 tag", finalResult.reason.contains("신뢰도 낮음"))
    }

    @Test
    fun `fallback with extreme pickup distance does not affect verdict`() {
        // Extreme case: fallback calculates 8km pickup for a 300m actual (coupang includes pickup)
        val call = DeliveryCall(
            price = 4500, distance = 3.0, isMulti = false,
            platform = "coupang",
            pickupDistanceKm = 8.0,  // extreme fallback
            distanceConfidence = 0.1
        )
        val result = CallFilter.judge(call, ctx)
        // Without fix: 4500/(8.0+3.0) = 409원/km → REJECT
        // With fix: pickupKm ignored → coupang estimated 1.0 + 3.0 = 4.0 → 4500/4.0 = 1125원/km
        // 1125 < 1200 but 4500 ≥ minPrice(2500) → 단가 미달 REJECT
        // But the reason should NOT include 8km pickup
        assertFalse("reason should not treat 8km as reliable", result.reason.contains("픽업 8.0km"))
        assertTrue("reason should mention 신뢰도 낮음", result.reason.contains("신뢰도 낮음"))
    }

    // ── LocationSnapshot ──

    @Test
    fun `HIGH_TRUST_THRESHOLD is 0_8`() {
        assertEquals(0.8, PickupDistanceResolver.HIGH_TRUST_THRESHOLD, 0.001)
    }

    @Test
    fun `confidence exactly 0_8 is high trust for non-fallback source`() {
        val result = PickupDistanceResolver.Result.Success(
            km = 1.5, source = "cache_mem", confidence = 0.8
        )
        assertTrue(result.isHighTrust())
    }

    @Test
    fun `confidence 0_79 is low trust`() {
        val result = PickupDistanceResolver.Result.Success(
            km = 1.5, source = "cache_mem", confidence = 0.79
        )
        assertFalse(result.isHighTrust())
    }
}
