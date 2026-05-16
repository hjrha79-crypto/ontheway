package com.vita.ontheway

/**
 * MobilityEvent v2.2 스키마 상수.
 *
 * 전략실 결정 (2026-04-24):
 * - judge_version: 배포 시점 기준 하드코딩
 * - source_type: 플랫폼 출처 (어느 앱)
 * - parsing_method: 파싱 방식 (어떻게 읽었는가)
 *
 * 향후 버전 업데이트 시 JUDGE_VERSION만 수정.
 */
object V2Event {
    const val JUDGE_VERSION = "rule_v3.20_20260425"

    // source_type values
    const val SOURCE_BAEMIN = "baemin"
    const val SOURCE_COUPANG = "coupang"
    const val SOURCE_KAKAO = "kakao"
    const val SOURCE_UNKNOWN = "unknown"

    // parsing_method values
    const val PARSING_ACCESSIBILITY_TEXT = "accessibility_text"
    const val PARSING_NOTIFICATION = "notification"
    const val PARSING_CONTENT_DESCRIPTION = "content_description"
    const val PARSING_TEXT_REGEX = "text_regex"
    const val PARSING_FALLBACK_POINT = "fallback_point"
    const val PARSING_ACCESSIBILITY_NEW_CALL = "accessibility_new_call"
    const val PARSING_UNKNOWN = "unknown"

    /**
     * DeliveryCall.platform -> source_type 매핑.
     */
    fun mapSourceType(platform: String?): String = when (platform) {
        "baemin" -> SOURCE_BAEMIN
        "coupang" -> SOURCE_COUPANG
        "kakaot" -> SOURCE_KAKAO
        else -> SOURCE_UNKNOWN
    }
}
