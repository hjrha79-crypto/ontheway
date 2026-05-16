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
 * Fix A1-lite: REJECT/HOLD → lastDeliveryCall 갱신 X 검증.
 *
 * OnTheWayService는 Android Service이므로 직접 인스턴스화 불가.
 * 대신 CallFilter verdict 기반 분기 로직을 시뮬레이션하여
 * lastDeliveryCall 갱신 정책을 검증한다.
 *
 * T1: REJECT → lastDeliveryCall 갱신 X
 * T2: HOLD → lastDeliveryCall 갱신 X
 * T3: ACCEPT → lastDeliveryCall 갱신 O
 * T4: REJECT 후 클릭 → REJECT 콜 확정 X (이전 ACCEPT 콜 유지)
 * T5: REJECT 콜도 ledger에 기록됨 (verdict/reason 보존)
 */
class AttributionH1Test {

    private lateinit var ctx: Context

    // lastDeliveryCall 시뮬레이션
    private var simLastDeliveryCall: DeliveryCall? = null
    private var simLastDeliveryCallAt: Long = 0L
    private var simLastDeliverySessionId: String? = null
    private var simLastDeliveryVerdict: String = ""
    private var simLastDeliveryPlatform: String = ""

    /**
     * OnTheWayService의 verdict 분기 로직을 재현.
     * Fix A1-lite 적용 후 동작.
     */
    private fun applyVerdictBranch(
        call: DeliveryCall,
        result: CallFilter.FilterResult,
        platformName: String,
        eventId: String?
    ) {
        if (result.verdict == CallFilter.Verdict.REJECT) {
            // Fix A1-lite: lastDeliveryCall 갱신 X
            simLastDeliveryVerdict = "주의"
            simLastDeliveryPlatform = platformName
        } else if (result.verdict == CallFilter.Verdict.HOLD) {
            // Fix A1-lite: lastDeliveryCall 갱신 X
            simLastDeliveryVerdict = "보류"
            simLastDeliveryPlatform = platformName
        } else {
            simLastDeliveryCall = call
            simLastDeliveryCallAt = System.currentTimeMillis()
            simLastDeliveryPlatform = platformName
            simLastDeliveryVerdict = "보통"  // simplified
            simLastDeliverySessionId = eventId
        }
    }

    private fun mockContext(
        minPrice: Int = 3000,
        minUnitPrice: Int = 1400,
        highPriceThreshold: Int = 7000
    ): Context {
        val mockPrefs = mockk<SharedPreferences>()
        every { mockPrefs.getInt("min_price", any()) } returns minPrice
        every { mockPrefs.getInt("min_unit_price", any()) } returns minUnitPrice
        every { mockPrefs.getInt("high_price_threshold", any()) } returns highPriceThreshold
        every { mockPrefs.getInt(any(), any()) } answers { secondArg() }
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
        simLastDeliveryCall = null
        simLastDeliveryCallAt = 0L
        simLastDeliverySessionId = null
        simLastDeliveryVerdict = ""
        simLastDeliveryPlatform = ""
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── T1: REJECT → lastDeliveryCall 갱신 X ──

    @Test
    fun `T1 reject does not update lastDeliveryCall`() {
        // 먼저 ACCEPT 콜 설정
        val acceptCall = DeliveryCall(price = 5000, distance = 2.0, isMulti = false, platform = "baemin")
        val acceptResult = CallFilter.judge(acceptCall, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, acceptResult.verdict)
        applyVerdictBranch(acceptCall, acceptResult, "baemin", "session-accept-1")

        val savedCall = simLastDeliveryCall
        val savedSession = simLastDeliverySessionId
        assertNotNull("ACCEPT 후 lastDeliveryCall 설정됨", savedCall)

        // REJECT 콜 도착
        val rejectCall = DeliveryCall(price = 2000, distance = null, isMulti = false, platform = "baemin")
        val rejectResult = CallFilter.judge(rejectCall, ctx)
        assertEquals(CallFilter.Verdict.REJECT, rejectResult.verdict)
        applyVerdictBranch(rejectCall, rejectResult, "baemin", "session-reject-1")

        // lastDeliveryCall은 이전 ACCEPT 콜 유지
        assertSame("REJECT 후 lastDeliveryCall 변경 없음", savedCall, simLastDeliveryCall)
        assertEquals("lastDeliverySessionId 변경 없음", savedSession, simLastDeliverySessionId)
        assertEquals("verdict는 갱신됨", "주의", simLastDeliveryVerdict)
    }

    // ── T2: HOLD → lastDeliveryCall 갱신 X ──

    @Test
    fun `T2 hold does not update lastDeliveryCall`() {
        // ACCEPT 콜 설정
        val acceptCall = DeliveryCall(price = 5000, distance = 2.0, isMulti = false, platform = "baemin")
        val acceptResult = CallFilter.judge(acceptCall, ctx)
        applyVerdictBranch(acceptCall, acceptResult, "baemin", "session-accept-2")

        val savedCall = simLastDeliveryCall
        assertNotNull(savedCall)

        // HOLD 콜 도착 (distance=null + pickupKm=null)
        val holdCall = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "baemin", point = 15.0, pickupDistanceKm = null
        )
        val holdResult = CallFilter.judge(holdCall, ctx)
        assertEquals(CallFilter.Verdict.HOLD, holdResult.verdict)
        applyVerdictBranch(holdCall, holdResult, "baemin", "session-hold-1")

        assertSame("HOLD 후 lastDeliveryCall 변경 없음", savedCall, simLastDeliveryCall)
        assertEquals("verdict는 갱신됨", "보류", simLastDeliveryVerdict)
    }

    // ── T3: ACCEPT → lastDeliveryCall 갱신 O ──

    @Test
    fun `T3 accept updates lastDeliveryCall`() {
        assertNull("초기 lastDeliveryCall null", simLastDeliveryCall)

        val acceptCall = DeliveryCall(price = 5000, distance = 2.0, isMulti = false, platform = "baemin")
        val acceptResult = CallFilter.judge(acceptCall, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, acceptResult.verdict)
        applyVerdictBranch(acceptCall, acceptResult, "baemin", "session-accept-3")

        assertSame("ACCEPT 후 lastDeliveryCall 갱신됨", acceptCall, simLastDeliveryCall)
        assertEquals("session ID 갱신", "session-accept-3", simLastDeliverySessionId)
        assertTrue("callAt 갱신", simLastDeliveryCallAt > 0)
    }

    // ── T4: REJECT 후 클릭 → REJECT 콜 확정 X (이전 ACCEPT 콜 유지) ──

    @Test
    fun `T4 reject then click does not confirm reject call`() {
        // 1. ACCEPT 콜: 5000원 2km
        val acceptCall = DeliveryCall(price = 5000, distance = 2.0, isMulti = false, platform = "baemin")
        val acceptResult = CallFilter.judge(acceptCall, ctx)
        applyVerdictBranch(acceptCall, acceptResult, "baemin", "session-accept-4")

        // 2. REJECT 콜: 2000원
        val rejectCall = DeliveryCall(price = 2000, distance = null, isMulti = false, platform = "baemin")
        val rejectResult = CallFilter.judge(rejectCall, ctx)
        applyVerdictBranch(rejectCall, rejectResult, "baemin", "session-reject-4")

        // 3. 사용자가 수락 버튼 클릭 → lastDeliveryCall 사용
        // lastDeliveryCall은 여전히 ACCEPT 콜 (5000원)
        val confirmTarget = simLastDeliveryCall
        assertNotNull("확정 대상 존재", confirmTarget)
        assertEquals("확정 대상은 ACCEPT 콜 (5000원)", 5000, confirmTarget!!.price)
        assertNotEquals("REJECT 콜 (2000원) 아님", 2000, confirmTarget.price)
        assertEquals("session은 ACCEPT session", "session-accept-4", simLastDeliverySessionId)
    }

    // ── T5: REJECT 콜도 ledger에 기록됨 ──

    @Test
    fun `T5 reject call still produces verdict and reason for ledger`() {
        val rejectCall = DeliveryCall(price = 2000, distance = null, isMulti = false, platform = "baemin")
        val result = CallFilter.judge(rejectCall, ctx)

        // CallFilter는 verdict + reason을 반환 (ledger 기록 가능)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("사유 존재", result.reason.isNotEmpty())
        // verdict.name = "REJECT" → ledger JUDGMENT_ISSUED에 기록 가능
        assertEquals("REJECT", result.verdict.name)
    }
}
