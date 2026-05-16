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
    fun `멀티 4400원 1_3km = 주의 (건당 단가 + 보정)`() {
        // Fix D: 쿠팡 effectiveDist = 1.0+1.3=2.3, unitPrice=4400/2.3=1913
        // multi 보정: 1913*0.5=956 < 1500 → 주의
        val call = DeliveryCall(
            price = 4400, distance = 1.3, isMulti = true,
            platform = "coupang", rawText = ""
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull("메시지가 null이면 안됨", msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
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
    fun `멀티 8000원 2km = 주의 (쿠팡 추정픽업 반영)`() {
        // Fix D: 쿠팡 effectiveDist = 1.0+2.0=3.0, unitPrice=8000/3.0=2666
        // multi 보정: 2666*0.5=1333 < 1500 → 주의
        val call = DeliveryCall(
            price = 8000, distance = 2.0, isMulti = true,
            platform = "coupang", rawText = ""
        )
        val msg = OutputController.buildMessage(call, acceptResult)
        assertNotNull("메시지가 null이면 안됨", msg)
        assertTrue("주의 포함: $msg", msg!!.contains("주의"))
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
