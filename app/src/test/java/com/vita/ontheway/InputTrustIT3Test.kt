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
 * Fix IT-3: Baemin point*0.15 legacy heuristic ACCEPT 차단.
 *
 * 3 필수 테스트:
 * 1. distance=null + pickupKm=null + point=17 → ACCEPT 근거에 추정거리 X
 * 2. reason에 "추정거리"/"추정단가" 포함 X
 * 3. distance 실제 존재 → 기존 동작 유지
 */
class InputTrustIT3Test {

    private lateinit var ctx: Context

    private fun mockContext(
        minPrice: Int = 3000,
        minUnitPrice: Int = 1400,
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

    // ── 1. 증거 재현: distance=null + pickupKm=null + point=17 ──

    @Test
    fun `baemin distance_null pickupKm_null point17 - no ACCEPT based on estimated distance`() {
        // 증거: 동대문엽기떡볶이 4530원, point=17P
        // 이전: 17*0.15=2.55km → 추정단가 1776원/km → ACCEPT + 추정거리
        // Fix IT-3: point*0.15 미사용 → price 4530 >= minPrice 3000 → ACCEPT (가격 기준만)
        val call = DeliveryCall(
            price = 4530, distance = null, isMulti = false,
            platform = "baemin", point = 17.0,
            pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)

        // ACCEPT은 가격 기준으로 가능하지만, reason에 추정거리/추정단가 없어야 함
        assertFalse("추정거리 포함 금지", result.reason.contains("추정거리"))
        assertFalse("추정단가 포함 금지", result.reason.contains("추정단가"))
        assertTrue("최소배달료 통과 사유", result.reason.contains("최소배달료 통과"))
    }

    // ── 2. reason에 추정거리/추정단가 표현 금지 (다양한 케이스) ──

    @Test
    fun `baemin no_distance - reason never contains estimated distance or unit price`() {
        val testCases = listOf(
            // 일반 통과
            DeliveryCall(price = 4000, distance = null, isMulti = false,
                platform = "baemin", point = 10.0, pickupDistanceKm = null),
            // 최소배달료 미달 (REJECT)
            DeliveryCall(price = 2500, distance = null, isMulti = false,
                platform = "baemin", point = 30.0, pickupDistanceKm = null),
            // 고액 (ACCEPT)
            DeliveryCall(price = 8000, distance = null, isMulti = false,
                platform = "baemin", point = 50.0, pickupDistanceKm = null),
            // point=0 (거리/포인트 모두 없음)
            DeliveryCall(price = 5000, distance = null, isMulti = false,
                platform = "baemin", point = 0.0, pickupDistanceKm = null),
        )

        for (call in testCases) {
            val result = CallFilter.judge(call, ctx)
            assertFalse("추정거리 포함 금지: ${result.reason}", result.reason.contains("추정거리"))
            assertFalse("추정단가 포함 금지: ${result.reason}", result.reason.contains("추정단가"))
            assertFalse("거리 추정 포함 금지: ${result.reason}", result.reason.contains("거리 추정"))
        }
    }

    // ── 3. distance 실제 존재 케이스 = 기존 동작 유지 ──

    @Test
    fun `baemin with real distance - existing behavior preserved`() {
        // 고단가 근거리 → ACCEPT
        val acceptCall = DeliveryCall(
            price = 5000, distance = 2.0, isMulti = false,
            platform = "baemin", point = 13.0
        )
        val acceptResult = CallFilter.judge(acceptCall, ctx)
        assertEquals("거리 있으면 ACCEPT 유지", CallFilter.Verdict.ACCEPT, acceptResult.verdict)
        assertTrue("단가 정보 포함", acceptResult.reason.contains("단가") || acceptResult.reason.contains("금액"))

        // 단가 미달 → REJECT
        val rejectCall = DeliveryCall(
            price = 3100, distance = 5.0, isMulti = false,
            platform = "baemin", point = 33.0
        )
        val rejectResult = CallFilter.judge(rejectCall, ctx)
        assertEquals("단가 미달 REJECT 유지", CallFilter.Verdict.REJECT, rejectResult.verdict)
        assertTrue("단가 미달 사유", rejectResult.reason.contains("단가") && rejectResult.reason.contains("미달"))
    }

    // ── 4. 묶음배달: point*0.15 → 효율 ACCEPT 차단 ──

    @Test
    fun `bundle with point but no distance - no efficiency ACCEPT from estimated distance`() {
        // 묶음 2건, 10000원, point=20 (old: 20*0.15=3.0km → 건당 1.5km ≤ 3km → 효율 ACCEPT)
        // Fix IT-3: distance=null → bundleDist=null → 효율 판정 스킵 → 묶음 통과(가격 기준)
        // price=10000 → perPrice=5000 ≥ BUNDLE_PER_ITEM_MIN_2(4500) → 건당단가 통과
        val call = DeliveryCall(
            price = 10000, distance = null, isMulti = true,
            platform = "baemin", point = 20.0, bundleCount = 2
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertFalse("묶음 효율 사유 없어야 함", result.reason.contains("묶음 효율"))
    }

    // ── 5. 쿠팡 영향 없음 확인 ──

    @Test
    fun `coupang not affected by IT3 fix`() {
        // coupang: effectiveDist = 3.0 + 1.0(추정픽업) = 4.0km
        // unitPrice = 6000/4.0 = 1500 >= minUnitPrice(1400) → ACCEPT
        val call = DeliveryCall(
            price = 6000, distance = 3.0, isMulti = false,
            platform = "coupang"
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
    }

    // ── 6. OutputController verdict 일관성: 거리 미측정 → "보통" ──

    @Test
    fun `outputController verdict consistent with no distance`() {
        val call = DeliveryCall(
            price = 4530, distance = null, isMulti = false,
            platform = "baemin", point = 17.0, pickupDistanceKm = null
        )
        val filterResult = CallFilter.judge(call, ctx)
        val ttsMsg = OutputController.buildMessage(call, filterResult)

        // CallFilter: ACCEPT (가격 기준)
        assertEquals(CallFilter.Verdict.ACCEPT, filterResult.verdict)
        // OutputController: "보통" (거리 미측정)
        assertNotNull("TTS 메시지 생성", ttsMsg)
        assertTrue("보통 verdict", ttsMsg!!.contains("보통"))
        assertFalse("우세 아님 (거리 미측정)", ttsMsg.contains("우세"))
    }
}
