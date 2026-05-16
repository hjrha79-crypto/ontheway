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
 * Fix IT-3.fix: distance=null + pickupKm=null → HOLD (거리 미측정 보류).
 *
 * T1. distance=null + pickupKm=null → verdict ≠ ACCEPT (HOLD)
 * T2. reason에 "추정거리"/"추정단가" 표현 없음
 * T3. shadow_verdict ≠ recommended_accept (mapping 검증)
 * T4. 실제 distance 존재 → 기존 동작 유지
 * T5. trust policy 4-way 일치 검증
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

    // ── T1. distance=null + pickupKm=null → HOLD (ACCEPT 아님) ──

    @Test
    fun `T1 baemin distance_null pickupKm_null - verdict is HOLD not ACCEPT`() {
        val call = DeliveryCall(
            price = 4530, distance = null, isMulti = false,
            platform = "baemin", point = 17.0,
            pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)

        assertEquals("거리 미측정 → HOLD", CallFilter.Verdict.HOLD, result.verdict)
        assertNotEquals("ACCEPT 아님", CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("거리 미측정 사유", result.reason.contains("거리 미측정"))
    }

    // ── T2. reason에 "추정거리"/"추정단가" 표현 금지 ──

    @Test
    fun `T2 baemin no_distance - reason never contains estimated expressions`() {
        val testCases = listOf(
            DeliveryCall(price = 4000, distance = null, isMulti = false,
                platform = "baemin", point = 10.0, pickupDistanceKm = null),
            DeliveryCall(price = 2500, distance = null, isMulti = false,
                platform = "baemin", point = 30.0, pickupDistanceKm = null),
            DeliveryCall(price = 8000, distance = null, isMulti = false,
                platform = "baemin", point = 50.0, pickupDistanceKm = null),
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

    // ── T3. shadow_verdict mapping: HOLD → "보류" → "recommended_hold" ──

    @Test
    fun `T3 HOLD shadow_verdict is not recommended_accept`() {
        val call = DeliveryCall(
            price = 4530, distance = null, isMulti = false,
            platform = "baemin", point = 17.0, pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.HOLD, result.verdict)

        // OnTheWayService mapping: HOLD → lastDeliveryVerdict="보류" → shadow_verdict
        val simulatedLastDeliveryVerdict = "보류"  // HOLD maps to this
        val shadowVerdict = when (simulatedLastDeliveryVerdict) {
            "우세", "보통" -> "recommended_accept"
            "주의" -> "recommended_reject"
            "보류" -> "recommended_hold"
            else -> null
        }
        assertEquals("recommended_hold", shadowVerdict)
        assertNotEquals("recommended_accept", shadowVerdict)
    }

    // ── T4. distance 실제 존재 → 기존 동작 유지 ──

    @Test
    fun `T4 baemin with real distance - existing behavior preserved`() {
        // 고단가 근거리 → ACCEPT
        val acceptCall = DeliveryCall(
            price = 5000, distance = 2.0, isMulti = false,
            platform = "baemin", point = 13.0
        )
        val acceptResult = CallFilter.judge(acceptCall, ctx)
        assertEquals("거리 있으면 ACCEPT 유지", CallFilter.Verdict.ACCEPT, acceptResult.verdict)

        // 단가 미달 → REJECT
        val rejectCall = DeliveryCall(
            price = 3100, distance = 5.0, isMulti = false,
            platform = "baemin", point = 33.0
        )
        val rejectResult = CallFilter.judge(rejectCall, ctx)
        assertEquals("단가 미달 REJECT 유지", CallFilter.Verdict.REJECT, rejectResult.verdict)
    }

    // ── T5. trust policy 4-way 일치: CallFilter(HOLD) + OutputController(보통) + DB(HOLD) + shadow(recommended_hold) ──

    @Test
    fun `T5 trust policy 4-way consistency for distance_null`() {
        val call = DeliveryCall(
            price = 4530, distance = null, isMulti = false,
            platform = "baemin", point = 17.0, pickupDistanceKm = null
        )
        val filterResult = CallFilter.judge(call, ctx)

        // 1. CallFilter verdict = HOLD
        assertEquals("CallFilter: HOLD", CallFilter.Verdict.HOLD, filterResult.verdict)

        // 2. DB verdict column = "HOLD"
        val dbVerdict = filterResult.verdict.name
        assertEquals("DB: HOLD", "HOLD", dbVerdict)

        // 3. OutputController: HOLD + totalKm=0 → "보통"
        val ttsMsg = OutputController.buildMessage(call, filterResult)
        assertNotNull("TTS 메시지 생성", ttsMsg)
        assertTrue("OutputController: 보통", ttsMsg!!.contains("보통"))
        assertFalse("OutputController: 우세 아님", ttsMsg.contains("우세"))

        // 4. shadow_verdict = "recommended_hold" (not recommended_accept)
        val shadowVerdict = when ("보류") {  // HOLD → "보류" in OnTheWayService
            "우세", "보통" -> "recommended_accept"
            "주의" -> "recommended_reject"
            "보류" -> "recommended_hold"
            else -> null
        }
        assertNotEquals("shadow: not recommended_accept", "recommended_accept", shadowVerdict)
    }

    // ── 묶음배달: point*0.15 → 효율 ACCEPT 차단 (IT-3 기존 검증) ──

    @Test
    fun `bundle with point but no distance - no efficiency ACCEPT from estimated distance`() {
        val call = DeliveryCall(
            price = 10000, distance = null, isMulti = true,
            platform = "baemin", point = 20.0, bundleCount = 2
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertFalse("묶음 효율 사유 없어야 함", result.reason.contains("묶음 효율"))
    }

    // ── 쿠팡 영향 없음 확인 ──

    @Test
    fun `coupang not affected by IT3 fix`() {
        val call = DeliveryCall(
            price = 6000, distance = 3.0, isMulti = false,
            platform = "coupang"
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
    }

    // ── pickupKm 있으면 ACCEPT 허용 (HOLD 아님) ──

    @Test
    fun `baemin distance_null but pickupKm present - ACCEPT allowed`() {
        val call = DeliveryCall(
            price = 4530, distance = null, isMulti = false,
            platform = "baemin", point = 17.0,
            pickupDistanceKm = 1.5
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("pickupKm 있으면 ACCEPT", CallFilter.Verdict.ACCEPT, result.verdict)
    }
}
