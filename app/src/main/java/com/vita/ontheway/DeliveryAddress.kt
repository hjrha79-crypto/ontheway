package com.vita.ontheway

/**
 * FIX-TTS-DELIVERY-FLOW: 배달 주소 파싱 결과.
 * 동/호수 분리 + 고객 요청사항.
 */
data class DeliveryAddress(
    val dong: String = "",           // "103동"
    val ho: String = "",             // "1302호"
    val fullAddress: String = "",    // 원본 전체 주소
    val customerRequest: String = "", // "문앞에 두고 문자 주세요"
    val storeName: String = ""       // 픽업 가게명
) {
    /** 동호수 단축 표현: "103동 1302호" 또는 "1302호" */
    fun shortAddress(): String {
        return when {
            dong.isNotBlank() && ho.isNotBlank() -> "$dong $ho"
            ho.isNotBlank() -> ho
            dong.isNotBlank() -> dong
            fullAddress.isNotBlank() -> fullAddress.take(20)
            else -> ""
        }
    }

    companion object {
        // "103동 1302호", "103동1302호", "103동 1302 호"
        private val DONG_HO_PATTERN = Regex("""(\d+)\s*동\s*(\d+)\s*호""")
        // "B동 304호", "2005동 1302호"
        private val DONG_HO_ALPHA_PATTERN = Regex("""([A-Z\d]+)\s*동\s*(\d+)\s*호""")
        // 호수만: "1302호"
        private val HO_ONLY_PATTERN = Regex("""(\d+)\s*호""")
        // 공동현관 비밀번호: "공동현관 1234", "#1234", "*1234#"
        private val ENTRANCE_CODE_PATTERN = Regex("""(?:공동현관|현관)\s*(?:비밀번호\s*)?[#*]?(\d{4,})[#*]?""")

        /** 주소 문자열에서 DeliveryAddress 파싱 */
        fun parse(address: String, customerRequest: String = "", storeName: String = ""): DeliveryAddress {
            if (address.isBlank()) return DeliveryAddress(
                customerRequest = customerRequest, storeName = storeName
            )

            // 동호수 파싱
            val dongHoMatch = DONG_HO_ALPHA_PATTERN.find(address)
            if (dongHoMatch != null) {
                return DeliveryAddress(
                    dong = "${dongHoMatch.groupValues[1]}동",
                    ho = "${dongHoMatch.groupValues[2]}호",
                    fullAddress = address,
                    customerRequest = customerRequest,
                    storeName = storeName
                )
            }

            // 호수만
            val hoMatch = HO_ONLY_PATTERN.find(address)
            if (hoMatch != null) {
                return DeliveryAddress(
                    ho = "${hoMatch.groupValues[1]}호",
                    fullAddress = address,
                    customerRequest = customerRequest,
                    storeName = storeName
                )
            }

            return DeliveryAddress(
                fullAddress = address,
                customerRequest = customerRequest,
                storeName = storeName
            )
        }

        /** 고객 요청사항에서 공동현관 코드 추출 */
        fun extractEntranceCode(customerRequest: String): String? {
            val match = ENTRANCE_CODE_PATTERN.find(customerRequest)
            return match?.groupValues?.get(1)
        }
    }
}
