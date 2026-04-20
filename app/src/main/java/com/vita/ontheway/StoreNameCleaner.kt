package com.vita.ontheway

/**
 * 배민 Accessibility에서 가져온 가게명 문자열에서
 * UI 버튼 라벨 등 불필요한 텍스트를 제거하는 유틸리티 (v3.15).
 */
object StoreNameCleaner {

    // 배민 화면에 섞여 들어오는 UI 라벨 블랙리스트
    private val UI_LABELS = setOf(
        "배민배달", "배민커넥트", "배민",
        "쿠팡배달", "쿠팡이츠", "쿠팡",
        "카카오T",
        "픽업지", "전달지",
        "포인트",
        "총 합계", "총합계",
        "모두 거절",
        "지도앱으로 검색하기",
        "조리완료",
        "배차", "배차 수락"
    )

    // "N건 모두 수락", "N초", "N분" 같은 동적 라벨
    private val UI_PATTERNS = listOf(
        Regex("""^\d+건\s*모두\s*수락$"""),
        Regex("""^\d+초$"""),
        Regex("""^\d+분$""")
    )

    /** "+" 구분 원시 문자열에서 UI 라벨 제거 후 가게명만 반환 */
    fun clean(raw: String): List<String> {
        val tokens = raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        return tokens.filter { token ->
            token !in UI_LABELS && UI_PATTERNS.none { it.matches(token) }
        }.distinct()
    }

    /** clean() 결과를 ", "로 합쳐서 반환 */
    fun cleanToString(raw: String): String = clean(raw).joinToString(", ")
}
