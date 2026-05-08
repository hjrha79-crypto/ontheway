package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * FIX-VERDICT-LOGIC: 단가 임계값 일관성 + 거리 null 처리 테스트.
 *
 * 5/8 audit raw fixture 기반:
 * - 단가 502, 거리 5.01km → 주의 (was ACCEPT)
 * - 단가 1,218, 거리 4.22km → 주의
 * - 단가 12,500, 거리 0.20km → 우세
 * - 거리 null → 보통 (verdict 보류)
 * - 외지 페널티: 픽업 ≥ 5km && 단가 < 1,500 → 주의
 */
class VerdictLogicTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        val mockPrefs = mockk<SharedPreferences>()
        every { mockPrefs.getInt(any(), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }
        ctx = mockk<Context>()
        every { ctx.getSharedPreferences(any(), any()) } returns mockPrefs
    }

    // ── CallFilter: 단가 미달 REJECT (hasDist=false but pickupKm > 0) ──

    @Test
    fun `단가 502 거리 5_01km = REJECT (minUnitPrice 1400 미달)`() {
        // audit: 2519원, pickup 5.01km → 단가 502원/km → was ACCEPT, should be REJECT
        val call = DeliveryCall(
            price = 2519, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = 5.01
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("단가 미달 → REJECT", CallFilter.Verdict.REJECT, result.verdict)
    }

    @Test
    fun `단가 626 거리 4_63km = REJECT`() {
        // audit: 2900원, pickup 4.63km → 단가 626원/km → was ACCEPT
        val call = DeliveryCall(
            price = 2900, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = 4.63
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("단가 미달 → REJECT", CallFilter.Verdict.REJECT, result.verdict)
    }

    @Test
    fun `단가 1218 거리 4_22km = REJECT`() {
        // audit: 5140원, dist 4.22km → 단가 1218원/km → below 1400
        val call = DeliveryCall(
            price = 5140, distance = 4.22, isMulti = false,
            platform = "coupang"
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("단가 미달 → REJECT", CallFilter.Verdict.REJECT, result.verdict)
    }

    @Test
    fun `고액 12500원 0_20km = ACCEPT`() {
        val call = DeliveryCall(
            price = 12500, distance = 0.20, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("고액 → ACCEPT", CallFilter.Verdict.ACCEPT, result.verdict)
    }

    // ── CallFilter: 거리 null 처리 ──

    @Test
    fun `거리 null 금액 충분 = ACCEPT with 거리 미측정 reason`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("거리 미측정 reason", result.reason.contains("거리 미측정"))
    }

    @Test
    fun `거리 null 금액 미달 = REJECT`() {
        val call = DeliveryCall(
            price = 2000, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = null
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("금액 미달 → REJECT", CallFilter.Verdict.REJECT, result.verdict)
    }

    // ── CallFilter: 외지 페널티 ──

    @Test
    fun `외지 페널티 픽업 5km 단가 1200 = REJECT`() {
        // price=6000, pickup=5.0km → 단가 1200원/km < 1500 외지 기준
        val call = DeliveryCall(
            price = 6000, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = 5.0
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("외지 페널티 → REJECT", CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("외지 reason", result.reason.contains("외지") || result.reason.contains("단가"))
    }

    @Test
    fun `외지 픽업 5km 단가 1600 = ACCEPT (페널티 미적용)`() {
        // price=8000, pickup=5.0km → 단가 1600원/km ≥ 1500 → 외지 페널티 X
        val call = DeliveryCall(
            price = 8000, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = 5.0
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("외지지만 단가 OK → ACCEPT", CallFilter.Verdict.ACCEPT, result.verdict)
    }

    // ── OutputController: 3단계 verdict 임계값 ──

    private val acceptResult = CallFilter.FilterResult(CallFilter.Verdict.ACCEPT, "테스트")

    @Test
    fun `단가 2100 = 우세`() {
        // totalKm = 2.0, unitPrice = 4200/2.0 = 2100 ≥ 2000 → 우세
        val call = DeliveryCall(
            price = 4200, distance = 2.0, isMulti = false,
            platform = "coupang", pickupDistanceKm = null
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        // pickupUnknown → 우세 강등 → 보통 (고액 아닌 경우)
        // pickup 설정해서 강등 방지
        val call2 = call.copy(pickupDistanceKm = 0.1)
        // totalKm = 2.1, unitPrice = 4200/2.1 = 2000 ≥ 2000 → 우세
        val msg2 = OutputController.buildMessage(call2, acceptResult)
        assertNotNull(msg2)
        assertTrue("우세 포함: $msg2", msg2!!.contains("우세"))
    }

    @Test
    fun `단가 1500 = 보통`() {
        val call = DeliveryCall(
            price = 4500, distance = 3.0, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        // totalKm = 3.5, unitPrice = 4500/3.5 = 1285 → 보통
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("보통 포함: $msg", msg!!.contains("보통"))
    }

    @Test
    fun `단가 1100 = 주의`() {
        val call = DeliveryCall(
            price = 4400, distance = 4.0, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        // totalKm = 4.5, unitPrice = 4400/4.5 = 977 → 주의
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
    }

    @Test
    fun `거리 null = 보통 (verdict 보류)`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = null
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("보통 포함: $msg", msg!!.contains("보통"))
    }

    @Test
    fun `고액 12500원 0_2km = 우세`() {
        val call = DeliveryCall(
            price = 12500, distance = 0.2, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("우세 포함: $msg", msg!!.contains("우세"))
    }

    @Test
    fun `외지 verdict 픽업 6km 단가 1200 = 주의`() {
        val call = DeliveryCall(
            price = 7200, distance = null, isMulti = false,
            platform = "coupang", pickupDistanceKm = 6.0
        )
        // totalKm = 6.0, unitPrice = 1200 < 1500 → 외지 페널티 주의
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
    }
}
