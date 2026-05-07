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

    // ── v3.19 Layer 3: 가게명 금지어 블랙리스트 ──

    private val STORE_BLACKLIST = setOf(
        "배달 이어서 하기", "이어서 배달하기",
        "배차수락", "배차 수락",
        "지도 확대 기능이 꺼졌습니다", "지도 확대",
        "신규배달", "새로운 배달이 배정되었습니다",
        "픽업 완료", "배달 완료", "배달완료", "배달 중",
        "도착예정시간", "도착예상시간",
        "수락하기", "거절하기",
        "가맹점 도착", "고객 도착", "고객에게 전달",
        "배민배달", "배민커넥트",
        "조리완료", "조리 완료",
        // v3.21: 12:07 혼입 사례 추가
        "지도를 준비중입니다", "잠시만 기다려주세요",
        "고객 요청사항이 변경되었어요",
        "배달이 많은 지역을 볼수 있어요",
        "배달 이어서 하기"
    )

    // v3.21: 부분 매칭 키워드 (가게명에 포함되면 차단)
    private val STORE_SUBSTRING_BLACKLIST = listOf(
        "지도를 준비", "잠시만 기다", "현재 위치와 가까운",
        "고객 요청사항", "배달이 많은 지역", "배차를 찾고",
        "지도 확대 기능"
    )

    /** 공백 제거한 정규화 블랙리스트 (공백 불일치 대응) */
    private val NORMALIZED_BLACKLIST = STORE_BLACKLIST.map { it.replace("\\s+".toRegex(), "") }.toSet()

    private val INVALID_STORE_PATTERNS = listOf(
        Regex("^\\d{1,2}:\\d{2}"),              // 시각: "16:40 완료"
        Regex("^\\d+(\\.\\d+)?\\s*km$"),         // 거리: "3.5km"
        Regex("^\\d{1,3}(,\\d{3})*\\s*원$"),     // 금액: "4,990원"
        Regex("^\\d+(\\.\\d+)?\\s*P$", RegexOption.IGNORE_CASE), // 포인트: "38.3P"
        Regex("^\\d+(건|초|분)"),                 // 수량/시간: "2건"
        Regex("완료$"),                           // "~완료"
        Regex("^배달\\s")                         // "배달 ~"
    )

    /** Layer 3: 가게명이 블랙리스트에 해당하는지 */
    fun isBlacklisted(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed in STORE_BLACKLIST) return true
        if (trimmed.replace("\\s+".toRegex(), "") in NORMALIZED_BLACKLIST) return true
        if (INVALID_STORE_PATTERNS.any { it.containsMatchIn(trimmed) }) return true
        // v3.21: 부분 매칭 키워드
        if (STORE_SUBSTRING_BLACKLIST.any { trimmed.contains(it) }) return true
        // v3.21: UI 문장 의심 패턴 (~요, ~니다, ~습니다)
        if (trimmed.endsWith("요") || trimmed.endsWith("니다") || trimmed.endsWith("습니다")) {
            android.util.Log.d("StoreNameFilter", "UI 문장 의심: '$trimmed'")
            return true
        }
        // FIX-STORE-BLACKLIST: viewId / 짧은 영문 단독
        if (trimmed.startsWith("bros-") || trimmed.startsWith("com.")) return true
        if (trimmed.matches(Regex("^[a-z]{1,3}$"))) return true  // "id", "ok" 등
        return false
    }

    // ── v3.19 Layer 4: 가게명 형태 검증 ──

    // FIX-STORE-NAME: 한자(秀 등 CJK) + 특수문자(·/') 허용
    private val ALLOWED_CHARS = Regex("^[가-힣a-zA-Z0-9\\s\\u3400-\\u9FFF&\\-(). '·/]+$")

    /** Layer 4: 가게명 형태가 유효한지 */
    fun isValidFormat(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length < 2 || trimmed.length > 40) return false
        if (!ALLOWED_CHARS.matches(trimmed)) return false
        if (trimmed.all { it.isDigit() }) return false
        if (trimmed.none { it in '\uAC00'..'\uD7A3' || it.isLetter() }) return false
        val specialCount = trimmed.count { it in setOf('&', '-', '(', ')', '.') }
        if (trimmed.isNotEmpty() && specialCount.toDouble() / trimmed.length > 0.3) return false
        return true
    }

    /**
     * Layer 3+4 통합 검증. 거부 시 빈 문자열 반환.
     * @return 검증된 가게명, 또는 빈 문자열
     */
    fun validateStoreName(name: String): String {
        if (name.isBlank()) return ""
        if (isBlacklisted(name)) {
            android.util.Log.w("ParsingBlocked", "Blacklist: '$name'")
            return ""
        }
        if (!isValidFormat(name)) {
            android.util.Log.w("ParsingBlocked", "Format: '$name'")
            return ""
        }
        return name
    }
}
