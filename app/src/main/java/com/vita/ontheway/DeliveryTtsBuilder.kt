package com.vita.ontheway

/**
 * FIX-TTS-DELIVERY-FLOW: 배달 흐름 TTS 메시지 빌더.
 *
 * 원칙:
 * - 동사 최소 (알려드립니다/도착했습니다 X)
 * - 명사 + 정보만, 콤마 구분
 * - 짧을수록 좋음
 */
object DeliveryTtsBuilder {

    /**
     * 시나리오 A: 배달지 100m 접근.
     * 예: "103동 1302호"
     */
    fun buildCrossing(delivery: DeliveryAddress): String? {
        val short = delivery.shortAddress()
        if (short.isBlank()) return null
        return short
    }

    /**
     * 시나리오 B: 배달지 도착 (10m~50m).
     * 예: "103동 1302호, 공동현관 1234, 문앞에 두고 문자 주세요"
     */
    fun buildArrival(delivery: DeliveryAddress): String? {
        val short = delivery.shortAddress()
        if (short.isBlank()) return null

        val parts = mutableListOf(short)

        // 공동현관 코드
        if (delivery.customerRequest.isNotBlank()) {
            val code = DeliveryAddress.extractEntranceCode(delivery.customerRequest)
            if (code != null) {
                parts.add("공동현관 $code")
            }
        }

        // 고객 요청
        if (delivery.customerRequest.isNotBlank()) {
            parts.add(delivery.customerRequest)
        }

        return parts.joinToString(", ").take(50) // TTS 길이 제한
    }

    /**
     * 시나리오 C: 60초 미완료 리마인더.
     * 예: "문앞에 두고 문자 주세요"
     */
    fun buildReminder(delivery: DeliveryAddress): String? {
        if (delivery.customerRequest.isNotBlank()) {
            return delivery.customerRequest
        }
        return null // 요청사항 없으면 발화 X
    }

    /**
     * 시나리오 D: 배달 완료 → 다음 배달지.
     * 예: "다음 207동 1801호"
     * 큐 없으면 null (발화 X).
     */
    fun buildNextDelivery(next: DeliveryAddress?): String? {
        if (next == null) return null
        val short = next.shortAddress()
        if (short.isBlank()) return null
        return "다음 $short"
    }

    /**
     * 시나리오 E: 멀티 픽업 완료 후 배달 순서.
     * 예: "105동 502호 다음 207동 1801호"
     */
    fun buildMultiPickupNotice(deliveries: List<DeliveryAddress>): String? {
        if (deliveries.isEmpty()) return null

        val parts = deliveries.mapNotNull { it.shortAddress().takeIf { s -> s.isNotBlank() } }
        if (parts.isEmpty()) return null

        return when (parts.size) {
            1 -> parts[0]
            else -> parts[0] + " 다음 " + parts.drop(1).joinToString(" 다음 ")
        }.take(50) // TTS 길이 제한
    }
}
