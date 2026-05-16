package com.vita.ontheway

enum class TtsFormatMode {
    BASIC,      // 기존 포맷 그대로 (상황 추가 없음)
    CONCISE,    // 간결: [플랫폼] [상황], [행동] (금액/거리 제거)
    EXTENDED    // 확장: 기존 포맷 + 상황 삽입
}

object TtsMessageBuilder {

    /**
     * 메인 진입점.
     * @param mode 포맷 모드
     * @param call 배달 콜 데이터
     * @param result CallFilter 판정 결과
     * @param baseMsg 기존 로직이 생성한 메시지 (BASIC/EXTENDED에서 사용)
     */
    fun build(
        mode: TtsFormatMode,
        call: DeliveryCall,
        result: CallFilter.FilterResult,
        baseMsg: String
    ): String {
        val situation = determineSituation(call, result)
        return when (mode) {
            TtsFormatMode.BASIC -> baseMsg
            TtsFormatMode.CONCISE -> buildConcise(call, result, situation)
            TtsFormatMode.EXTENDED -> insertSituation(baseMsg, situation)
        }
    }

    /**
     * 상황 판정. null이면 상황 생략.
     */
    fun determineSituation(
        call: DeliveryCall,
        result: CallFilter.FilterResult
    ): String? {
        return when {
            call.isMulti || call.bundleCount > 1 -> "묶음"
            call.distance != null && call.distance < 1.0 -> "근거리"
            call.distance != null && call.distance > 3.0 -> "장거리"
            // TODO: call.isCookingReady 필드 추가 시 "조리완료" 상황 추가
            else -> null
        }
    }

    /**
     * 기존 메시지에 상황만 끼워넣기.
     * 예: "쿠팡, 우세, 만이천원" + "근거리"
     *     → "쿠팡, 근거리, 우세, 만이천원"
     */
    fun insertSituation(baseMsg: String, situation: String?): String {
        if (situation == null) return baseMsg
        val parts = baseMsg.split(",", limit = 2)
        return if (parts.size >= 2) {
            "${parts[0].trim()}, $situation,${parts[1]}"
        } else {
            "$baseMsg, $situation"
        }
    }

    /**
     * 간결 모드: 플랫폼 + 상황 + 행동만.
     * 예: "쿠팡 근거리, 우세"
     */
    fun buildConcise(
        call: DeliveryCall,
        result: CallFilter.FilterResult,
        situation: String?
    ): String {
        val platform = platformLabel(call.platform)
        val action = actionLabel(result)

        return if (situation != null) {
            "$platform $situation, $action"
        } else {
            "$platform, $action"
        }
    }

    private fun platformLabel(platform: String): String {
        return when (platform.lowercase()) {
            "coupang" -> "쿠팡"
            "baemin" -> "배민"
            "kakaot" -> "카카오"
            else -> "배달"
        }
    }

    private fun actionLabel(result: CallFilter.FilterResult): String {
        return when (result.verdict) {
            CallFilter.Verdict.ACCEPT -> "우세"
            CallFilter.Verdict.REJECT -> "주의"
            CallFilter.Verdict.HOLD -> "보통"
        }
    }
}
