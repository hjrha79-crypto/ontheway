package com.vita.ontheway

/**
 * 배민 화면 타입 식별 (v3.19 Layer 1)
 * 신규 콜이 아닌 화면에서 파싱 자체를 차단한다.
 */
object ScreenTypeDetector {

    enum class ScreenType {
        NEW_CALL,
        BUNDLE_SESSION,
        IN_PROGRESS,
        DELIVERY_RESUME,
        COMPLETED_LIST,
        IDLE,
        UNKNOWN
    }

    enum class Confidence { HIGH, MEDIUM, LOW }

    data class ScreenDetection(val type: ScreenType, val confidence: Confidence)

    private val IDLE_KEYWORDS = listOf(
        "가상 배달을 체험해 보세요", "신규배차를 켜고 배달을 시작하세요",
        "배달을 시작해", "배차 대기",
        "배달 체험하기",
        "진행 배달미션", "배달 미션", "완료 시 최대", "미션 전체보기",
        "신규배차를 켜고",
        "주행기록 기반",
        "배달이 많은 곳으로 이동",
        "배달 내역", "정산", "공지사항", "내 정보"
    )

    private val PROGRESS_KEYWORDS = listOf(
        "배달 중", "가게 도착", "고객에게 전달",
        "메뉴금액", "주문정보", "가게정보", "찾아오는 길"
    )

    // FIX-DETAIL-CLASSIFIER: 배달 상세/진행 화면 전용 키워드
    // 이 키워드가 있으면 "배달료 X원"이 있어도 NEW_CALL 아님
    private val DETAIL_ONLY_KEYWORDS = listOf(
        "가게전화", "주문금액", "고객연결", "고객요청", "결제",
        "전달 사진 촬영", "메뉴 보기", "가게 전화"
    )

    private val RESUME_KEYWORDS = listOf(
        "배달 이어서 하기", "이어서 배달하기"
    )

    private val COMPLETED_TIME_PATTERN = Regex("\\d{1,2}:\\d{2}\\s*(완료|배달완료)")
    private val PRICE_PATTERN = Regex("배달료\\s*[\\d,]+\\s*원")
    private val TOTAL_KEYWORD = Regex("총\\s*합계")
    private val BUNDLE_ACCEPT = Regex("\\d+건\\s*모두\\s*수락")

    fun detect(joined: String): ScreenDetection {
        // FIX-DETAIL-CLASSIFIER: 상세/진행 화면 전용 키워드 사전 체크
        val hasDetailOnly = DETAIL_ONLY_KEYWORDS.any { joined.contains(it) }

        // 1. IDLE (단, 배달료 패턴이 있고 상세 키워드 없으면 신규 콜 우선)
        if (IDLE_KEYWORDS.any { joined.contains(it) }) {
            if (PRICE_PATTERN.containsMatchIn(joined) && !hasDetailOnly)
                return ScreenDetection(ScreenType.NEW_CALL, Confidence.MEDIUM)
            return ScreenDetection(ScreenType.IDLE, Confidence.HIGH)
        }

        // 2. IN_PROGRESS (단, 배달료 패턴이 있고 상세 키워드 없으면 신규 콜 우선)
        if (PROGRESS_KEYWORDS.any { joined.contains(it) }) {
            if (PRICE_PATTERN.containsMatchIn(joined) && !hasDetailOnly)
                return ScreenDetection(ScreenType.NEW_CALL, Confidence.MEDIUM)
            return ScreenDetection(ScreenType.IN_PROGRESS, Confidence.HIGH)
        }

        // 2.5 FIX-DETAIL-CLASSIFIER: 상세 전용 키워드 단독 → IN_PROGRESS
        if (hasDetailOnly) {
            return ScreenDetection(ScreenType.IN_PROGRESS, Confidence.MEDIUM)
        }

        // 3. DELIVERY_RESUME
        if (RESUME_KEYWORDS.any { joined.contains(it) })
            return ScreenDetection(ScreenType.DELIVERY_RESUME, Confidence.HIGH)

        // 4. COMPLETED_LIST
        val completedCount = COMPLETED_TIME_PATTERN.findAll(joined).count()
        if (completedCount >= 2)
            return ScreenDetection(ScreenType.COMPLETED_LIST, Confidence.HIGH)
        if (joined.contains("배달 내역") && joined.contains("완료"))
            return ScreenDetection(ScreenType.COMPLETED_LIST, Confidence.MEDIUM)

        // 5. BUNDLE_SESSION
        if (TOTAL_KEYWORD.containsMatchIn(joined) ||
            BUNDLE_ACCEPT.containsMatchIn(joined) ||
            joined.contains("모두 거절"))
            return ScreenDetection(ScreenType.BUNDLE_SESSION, Confidence.HIGH)

        // 6. NEW_CALL
        if (PRICE_PATTERN.containsMatchIn(joined))
            return ScreenDetection(ScreenType.NEW_CALL, Confidence.MEDIUM)

        // 7. UNKNOWN
        return ScreenDetection(ScreenType.UNKNOWN, Confidence.LOW)
    }
}
