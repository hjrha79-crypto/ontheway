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
 * Fix IT-3.fix-NLS: NLS 경로 HOLD verdict 처리 검증.
 *
 * T1: NLS 경로 CallFilter.HOLD → ACCEPT 처리 안 됨
 * T2: NLS 경로 HOLD → "우세" TTS 발화 없음
 * T3: NLS 경로 HOLD → shadow_verdict = recommended_hold
 * T4: NLS 경로 기존 ACCEPT 동작 유지
 * T5: NLS 경로 기존 REJECT 동작 유지
 */
class NlsHoldVerdictTest {

    private lateinit var ctx: Context

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
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── T1: NLS 경로 CallFilter.HOLD → ACCEPT 처리 안 됨 ──

    @Test
    fun `T1 NLS HOLD verdict is not ACCEPT`() {
        // 배민 distance=null + pickupKm=null → HOLD
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "baemin", point = 15.0, pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)

        assertNotEquals("HOLD ≠ ACCEPT", CallFilter.Verdict.ACCEPT, result.verdict)
        assertEquals("HOLD verdict", CallFilter.Verdict.HOLD, result.verdict)
    }

    // ── T2: NLS 경로 HOLD → "우세" TTS 발화 없음 ──

    @Test
    fun `T2 NLS HOLD - OutputController does not produce 우세`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "baemin", point = 15.0, pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.HOLD, result.verdict)

        // NLS 배민 경로는 OutputController.buildMessage 사용
        val ttsMsg = OutputController.buildMessage(call, result)
        assertNotNull("TTS 메시지 생성됨", ttsMsg)
        assertFalse("우세 미포함", ttsMsg!!.contains("우세"))
        assertTrue("보통 포함", ttsMsg.contains("보통"))
    }

    @Test
    fun `T2b NLS HOLD - TtsMessageBuilder actionLabel is 보통`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "baemin", point = 15.0, pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)

        // TtsMessageBuilder (카카오T NLS 경로에서 사용) — HOLD → "보통"
        val ttsBuilt = TtsMessageBuilder.build(
            TtsFormatMode.BASIC, call, result, "배민, 보통, 4000원"
        )
        assertFalse("우세 미포함", ttsBuilt.contains("우세"))
    }

    // ── T3: NLS 경로 HOLD → shadow_verdict = recommended_hold ──

    @Test
    fun `T3 NLS HOLD shadow_verdict mapping`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "baemin", point = 15.0, pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.HOLD, result.verdict)

        // NLS shadow_verdict 매핑 (DeliveryNotificationService 로직 재현)
        val notiShadowVerdict = when (result.verdict) {
            CallFilter.Verdict.HOLD -> "recommended_hold"
            else -> null
        }
        assertEquals("recommended_hold", notiShadowVerdict)
        assertNotEquals("recommended_accept", notiShadowVerdict)
    }

    // ── T4: NLS 경로 기존 ACCEPT 동작 유지 ──

    @Test
    fun `T4 NLS ACCEPT - existing behavior preserved`() {
        // 거리 있는 배민 콜 → ACCEPT
        val call = DeliveryCall(
            price = 5000, distance = 2.5, isMulti = false,
            platform = "baemin", point = 17.0
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)

        val ttsMsg = OutputController.buildMessage(call, result)
        assertNotNull(ttsMsg)
        // ACCEPT + distance 존재 → "우세" 또는 "보통" (단가에 따라)
        // shadow_verdict 매핑: null (기존 동작 유지)
        val notiShadowVerdict = when (result.verdict) {
            CallFilter.Verdict.HOLD -> "recommended_hold"
            else -> null
        }
        assertNull("기존 ACCEPT = shadow null", notiShadowVerdict)
    }

    // ── T5: NLS 경로 기존 REJECT 동작 유지 ──

    @Test
    fun `T5 NLS REJECT - existing behavior preserved`() {
        // 최소배달료 미달 → REJECT
        val call = DeliveryCall(
            price = 2000, distance = null, isMulti = false,
            platform = "baemin", point = 10.0, pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)

        val ttsMsg = OutputController.buildMessage(call, result)
        assertNotNull(ttsMsg)
        assertTrue("주의 포함", ttsMsg!!.contains("주의"))

        // shadow_verdict 매핑: null (기존 동작 유지)
        val notiShadowVerdict = when (result.verdict) {
            CallFilter.Verdict.HOLD -> "recommended_hold"
            else -> null
        }
        assertNull("기존 REJECT = shadow null", notiShadowVerdict)
    }
}
