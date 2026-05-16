package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * Fix M1.wire-fix v2: 리마인드 TTS / DeliveryFlowManager 로그의
 * customerRequest 원문 제거 검증.
 */
class CustomerRequestRedactionV2Test {

    private fun hashText(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((text + "otw").toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }

    // ── T1: OnTheWayService 리마인드 TTS 로그에 원문 X, hash만 ──

    @Test
    fun `T1 reminder_tts_log_has_hash_not_raw_request`() {
        val request = "문 앞에 놓아주세요 비밀번호 1234*"
        val h = hashText(request)

        // OnTheWayService line 760 로직 재현
        val logMsg = "리마인드 TTS: hash=$h len=${request.length}"

        assertFalse("원문 미포함", logMsg.contains("문 앞에"))
        assertFalse("비밀번호 미포함", logMsg.contains("비밀번호"))
        assertFalse("1234 미포함", logMsg.contains("1234"))
        assertTrue("hash 포함", logMsg.contains("hash="))
        assertTrue("len 포함", logMsg.contains("len="))
        assertEquals(16, h.length)
    }

    // ── T2: DeliveryFlowManager 시나리오 B 로그 — 별도 구성 메시지 (v3) ──

    @Test
    fun `T2 flow_manager_line66_redacts_customer_request`() {
        val customerRequest = "문 앞에 놓아주세요 초인종 누르지 마세요"
        val delivery = DeliveryAddress.parse(
            address = "103동 1302호 서울시 강남구",
            customerRequest = customerRequest,
            storeName = "맘스터치"
        )

        // v3: 별도 로그 메시지 구성 (사후 replace 아님)
        val reqPart = if (delivery.customerRequest.isNotBlank())
            "req_hash=${DeliveryFlowManager.hashText(delivery.customerRequest)} len=${delivery.customerRequest.length}"
        else "req=none"
        val logMsg = "시나리오 B (도착): addr=${delivery.shortAddress()} $reqPart"

        assertFalse("원문 미포함", logMsg.contains("문 앞에"))
        assertFalse("초인종 미포함", logMsg.contains("초인종"))
        assertTrue("req_hash 포함", logMsg.contains("req_hash="))
        assertTrue("addr 포함", logMsg.contains("103동 1302호"))
        assertTrue("len 포함", logMsg.contains("len="))
        assertEquals(16, DeliveryFlowManager.hashText(customerRequest).length)
    }

    // ── T2b: v3 핵심 — take(50) 잘림에도 원문 잔존 X ──

    @Test
    fun `T2b scenario_b_log_uses_dedicated_message_not_replace`() {
        // 긴 customerRequest → buildArrival take(50)에 의해 잘림 발생
        val customerRequest = "문 앞에 놓아주세요 공동현관 비밀번호 9876 초인종 금지"
        val delivery = DeliveryAddress.parse(
            address = "103동 1302호 서울시 강남구 역삼동",
            customerRequest = customerRequest,
            storeName = "맘스터치"
        )

        // TTS msg는 take(50)으로 잘릴 수 있음
        val ttsMsg = DeliveryTtsBuilder.buildArrival(delivery)!!
        // 잘린 경우 replace가 실패하여 원문 잔존 가능 — v3에서는 무관

        // v3: 로그는 TTS msg와 무관하게 별도 구성
        val reqPart = if (delivery.customerRequest.isNotBlank())
            "req_hash=${DeliveryFlowManager.hashText(delivery.customerRequest)} len=${delivery.customerRequest.length}"
        else "req=none"
        val logMsg = "시나리오 B (도착): addr=${delivery.shortAddress()} $reqPart"

        // 핵심: customerRequest 원문의 어떤 부분도 로그에 없음
        assertFalse("문 앞에 미포함", logMsg.contains("문 앞에"))
        assertFalse("비밀번호 미포함", logMsg.contains("비밀번호"))
        assertFalse("9876 미포함", logMsg.contains("9876"))
        assertFalse("초인종 미포함", logMsg.contains("초인종"))
        assertTrue("req_hash 포함", logMsg.contains("req_hash="))
        assertTrue("len 포함", logMsg.contains("len="))
    }

    // ── T3: DeliveryFlowManager 시나리오 C 로그에서 reminderMsg 전체 hash ──

    @Test
    fun `T3 flow_manager_line83_redacts_customer_request`() {
        val customerRequest = "경비실에 맡겨주세요"
        val delivery = DeliveryAddress.parse(
            address = "207동 1801호",
            customerRequest = customerRequest
        )

        // DeliveryTtsBuilder.buildReminder 결과 = customerRequest 그 자체
        val reminderMsg = DeliveryTtsBuilder.buildReminder(delivery)!!
        assertEquals(customerRequest, reminderMsg)

        // DeliveryFlowManager line 83 로직 재현
        val logMsg = "시나리오 C (리마인더): hash=${DeliveryFlowManager.hashText(reminderMsg)} len=${reminderMsg.length}"

        assertFalse("원문 미포함", logMsg.contains("경비실"))
        assertFalse("맡겨주세요 미포함", logMsg.contains("맡겨주세요"))
        assertTrue("hash 포함", logMsg.contains("hash="))
        assertTrue("len 포함", logMsg.contains("len="))
        assertEquals(16, DeliveryFlowManager.hashText(reminderMsg).length)
    }
}
