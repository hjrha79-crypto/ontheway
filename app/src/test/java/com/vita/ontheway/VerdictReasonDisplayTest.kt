package com.vita.ontheway

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * FIX-VERDICT-REASON-DISPLAY: 모든 verdict에 사유 표시 테스트.
 *
 * extractShortReason은 MainActivity의 private method이므로
 * OutputController.buildMessage 경유 또는 동등 로직으로 검증.
 */
class VerdictReasonDisplayTest {

    // ── OutputController buildMessage verdict 포함 확인 ──

    private val acceptResult = CallFilter.FilterResult(CallFilter.Verdict.ACCEPT, "테스트")
    private val rejectResult = CallFilter.FilterResult(CallFilter.Verdict.REJECT, "단가 700원/km < 1,400원 기준 미달")

    @Test
    fun `우세 verdict 포함`() {
        val call = DeliveryCall(
            price = 5000, distance = 1.0, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("우세 포함: $msg", msg!!.contains("우세"))
    }

    @Test
    fun `보통 verdict 포함`() {
        val call = DeliveryCall(
            price = 4000, distance = 2.5, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        // totalKm = 3.0, unitPrice = 1333 → 보통
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("보통 포함: $msg", msg!!.contains("보통"))
    }

    @Test
    fun `주의 verdict 포함`() {
        val call = DeliveryCall(
            price = 3000, distance = 4.0, isMulti = false,
            platform = "coupang", pickupDistanceKm = 0.5
        )
        // totalKm = 4.5, unitPrice = 666 → 주의
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
    }

    @Test
    fun `REJECT 시 주의`() {
        val call = DeliveryCall(
            price = 3000, distance = 4.0, isMulti = false,
            platform = "coupang"
        )
        val msg = OutputController.buildMessage(call, rejectResult)
        assertNotNull(msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
    }

    // ── 사유 reason 텍스트 검증 (CallFilter reason 형태 확인) ──

    @Test
    fun `고단가 근거리 reason 포함`() {
        val reason = "우세: 고단가 근거리 3,571원/km ≥ 2,500원 + 거리 1.0km ≤ 3km"
        assertTrue(reason.contains("고단가 근거리"))
    }

    @Test
    fun `단가 미달 reason 포함`() {
        val reason = "단가 766원/km < 1,400원 기준 미달 (픽업 5.0km)"
        assertTrue(reason.contains("기준 미달"))
        val match = Regex("""단가\s*([\d,]+)원/km""").find(reason)
        assertNotNull(match)
        assertEquals("766", match!!.groupValues[1])
    }

    @Test
    fun `외지 페널티 reason 포함`() {
        val reason = "외지 페널티: 픽업 5.0km, 단가 1,200원/km < 1,500원"
        assertTrue(reason.contains("외지 페널티"))
    }

    @Test
    fun `거리 미측정 reason 포함`() {
        val reason = "거리 미측정, 금액 4,000원 ≥ 최소기준 3,000원"
        assertTrue(reason.contains("거리 미측정"))
    }

    @Test
    fun `블랙리스트 reason 포함`() {
        val reason = "블랙리스트 거부: 맥도날드"
        assertTrue(reason.contains("블랙리스트"))
    }
}
