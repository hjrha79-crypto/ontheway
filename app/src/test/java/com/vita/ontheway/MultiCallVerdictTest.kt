package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * 멀티콜 verdict 보정 테스트.
 * - 단건 4400원/1.3km → 우세 (단가 3384원/km)
 * - 멀티 4400원/1.3km → 보통 (보정단가 1692원/km, 강등)
 * - 멀티 2000원/1.3km → 주의 (보정단가 769원/km < 1500)
 */
class MultiCallVerdictTest {

    private val acceptResult = CallFilter.FilterResult(CallFilter.Verdict.ACCEPT, "테스트")

    @Test
    fun `단건 4400원 1_3km = 우세`() {
        val call = DeliveryCall(
            price = 4400, distance = 1.3, isMulti = false,
            platform = "coupang", rawText = "",
            pickupDistanceKm = 0.5 // pickup 설정 → 강등 방지
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull("메시지가 null이면 안됨", msg)
        assertTrue("우세 포함: $msg", msg!!.contains("우세"))
    }

    @Test
    fun `멀티 4400원 1_3km = 보통 (강등)`() {
        val call = DeliveryCall(
            price = 4400, distance = 1.3, isMulti = true,
            platform = "coupang", rawText = ""
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull("메시지가 null이면 안됨", msg)
        assertTrue("보통 포함: $msg", msg!!.contains("보통"))
        assertFalse("우세 미포함: $msg", msg.contains("우세"))
    }

    @Test
    fun `멀티 2000원 1_3km = 주의 (보정단가 미달)`() {
        val call = DeliveryCall(
            price = 2000, distance = 1.3, isMulti = true,
            platform = "coupang", rawText = ""
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull("메시지가 null이면 안됨", msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
    }

    @Test
    fun `멀티 8000원 2km = 보통 (고액이어도 멀티 강등)`() {
        val call = DeliveryCall(
            price = 8000, distance = 2.0, isMulti = true,
            platform = "coupang", rawText = ""
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull("메시지가 null이면 안됨", msg)
        // 보정단가 = 4000*0.5 = 2000 >= 1500, raw="우세" → 강등 → "보통"
        assertTrue("보통 포함: $msg", msg!!.contains("보통"))
    }

    @Test
    fun `멀티 UI 표시에 묶음 포함`() {
        val call = DeliveryCall(
            price = 4400, distance = 1.3, isMulti = true,
            platform = "coupang", rawText = "", bundleCount = 2
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull(msg)
        assertTrue("묶음 포함: $msg", msg!!.contains("묶음"))
    }
}
