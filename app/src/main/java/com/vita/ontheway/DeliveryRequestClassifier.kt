package com.vita.ontheway

import java.security.MessageDigest

/**
 * Memory M1: 배달 요청사항 3분류 + 분포 관찰.
 *
 * 분류 카테고리:
 * - REPEAT_CRITICAL: 반복 가치 높은 정보 (호수, 비밀번호, 문앞, 전화 등)
 * - NAVIGATION_HINT: 경로 힌트 (랜드마크, 입구, 주차장 등)
 * - IGNORE: 한 번 보면 기억되는 정보
 *
 * M1 범위: 분류 + 로깅만 (TTS/발화 X, confidence X)
 */
object DeliveryRequestClassifier {

    enum class Classification {
        REPEAT_CRITICAL,
        NAVIGATION_HINT,
        IGNORE
    }

    data class Result(
        val classification: Classification,
        val matchedKeywords: List<String>,
        val textHash: String
    )

    // ── salted hash ──
    private const val SALT = "otw_m1_req_2026"

    fun saltedHash(text: String): String {
        if (text.isBlank()) return ""
        val salted = "$SALT:$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(salted.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // ── REPEAT_CRITICAL taxonomy ──
    private val REPEAT_CRITICAL_PATTERNS: List<Pair<String, Regex>> = listOf(
        "UNIT" to Regex("\\d+\\s*호|\\d+동"),
        "DOOR_CODE" to Regex("비밀번호|공동현관|출입번호|비번"),
        "DOOR_FRONT" to Regex("문\\s*앞|현관\\s*앞"),
        "DIRECT_HANDOFF" to Regex("직접.*전달|대면.*전달|직접.*받"),
        "BELL" to Regex("초인종|벨|노크"),
        "CALL" to Regex("전화|연락|통화"),
        "TEXT" to Regex("문자|카톡|메시지"),
        "PHOTO" to Regex("사진|인증샷|촬영")
    )

    // ── NAVIGATION_HINT taxonomy ──
    private val NAVIGATION_HINT_PATTERNS: List<Pair<String, Regex>> = listOf(
        "LANDMARK" to Regex("층|편의점|스타벅스|카페|약국|마트|은행|학교|교회|성당|병원|공원"),
        "BUILDING_SIDE" to Regex("오른쪽|왼쪽|우측|좌측|뒤쪽|앞쪽|옆"),
        "PARKING" to Regex("주차장|주차|지하\\s*주차"),
        "ENTRANCE" to Regex("출입구|입구|후문|정문|쪽문")
    )

    fun classify(text: String?): Result {
        if (text.isNullOrBlank()) {
            return Result(Classification.IGNORE, emptyList(), "")
        }

        val matched = mutableListOf<String>()

        // REPEAT_CRITICAL 체크 (우선순위 높음)
        for ((key, pattern) in REPEAT_CRITICAL_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                matched.add(key)
            }
        }
        if (matched.isNotEmpty()) {
            return Result(Classification.REPEAT_CRITICAL, matched, saltedHash(text))
        }

        // NAVIGATION_HINT 체크
        for ((key, pattern) in NAVIGATION_HINT_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                matched.add(key)
            }
        }
        if (matched.isNotEmpty()) {
            return Result(Classification.NAVIGATION_HINT, matched, saltedHash(text))
        }

        // IGNORE
        return Result(Classification.IGNORE, emptyList(), saltedHash(text))
    }
}
