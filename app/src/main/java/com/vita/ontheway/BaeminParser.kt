package com.vita.ontheway

import android.util.Log

object BaeminParser {

    // 배민 포인트→거리 환산 계수 (v3.10)
    // 2026-04-19 실측 검증: 38.3P→6km, 50.5P→8km, 53.9P→8~9km
    // 기존 0.25 → 0.15 (실측 기반 약 60% 수준)
    const val BAEMIN_POINT_TO_KM = 0.15

    /** 배민 포인트 → 추정 거리(km) 변환 */
    fun convertPointToKm(points: Double): Double = points * BAEMIN_POINT_TO_KM

    /**
     * 배민 앱 UI의 "배달료기준거리 (1,065m)" 텍스트 파싱용 정규식.
     * 2026-04-24: 저녁 진단 로거 분석으로 Case A 확정.
     * - 매칭 시: 해당 거리(m)를 km로 변환하여 distance 설정
     * - 미매칭 시: 기존 포인트×0.15 추정 유지 (fallback)
     */
    private val DISTANCE_PATTERN = Regex("""배달료기준거리\s*\(([0-9,]+)m\)""")

    private val PRICE_PATTERN = Regex("배달료\\s*([\\d,]+)\\s*원")
    private val AMOUNT_PATTERN = Regex("^([\\d,]+)\\s*원$")
    private val POINT_PATTERN = Regex("([\\d.]+)\\s*P", RegexOption.IGNORE_CASE)
    // v3.20: 한자(秀), 특수문자(&/·-(),'') 허용, 길이 30까지
    private val STORE_PATTERN = Regex("^[가-힣a-zA-Z0-9\\s\\u3400-\\u9FFF&/·\\-.(),']{2,30}$")
    private val DEST_PATTERN = Regex("^[가-힣]+(구|동|시|면|로|길).*")
    // 묶음배달 패턴: "묶음배달", "2건", "3건 묶음" 등
    private val BUNDLE_PATTERN = Regex("묶음|\\d+건", RegexOption.IGNORE_CASE)
    private val BUNDLE_COUNT_PATTERN = Regex("(\\d+)\\s*건")

    // 이전내역(완료된 배달 목록) 화면 키워드 — 신규 콜 오인 방지
    val HISTORY_SCREEN_KEYWORDS = listOf(
        "배정받은 배달이 없습니다",
        "신규배차가 중지되었습니다",
        "배달리스트",
        "픽업 완료 되었습니다",
        "도움이 필요하세요",
        "채팅문의",
        "아래 항목을 선택해 문제를 해결하세요"
    )

    // 가게명 오염 블랙리스트: accessibility tree에서 혼입되는 UI 컴포넌트 텍스트
    private val STORE_NAME_BLACKLIST = setOf(
        "touchable-image-container",
        "button-base",
        "naver",
        "지도",
        "네이버지도",
        "button",
        "image",
        "container",
        "view",
        "신규배차_끄기버튼",
        "신규배차_거절버튼",
        "신규배차_수락버튼",
        "신규배차",
        "배차수락",
        "이전내역",
        "픽업 완료 되었습니다",
        "지도앱으로 검색하기",
        "프로모션", "기상", "NAVER",
        // 배달 방법 선택 UI 텍스트
        "Bottom Sheet", "전달 사진 촬영", "전달 사진",
        "문 앞에 두고 초인종", "문 앞에 두고",
        "비대면 배달", "직접 전달", "초인종 누르기", "초인종",
        // 배민 콜 화면 UI 라벨
        "배민배달", "배민커넥트", "조리완료", "픽업지", "전달지",
        "배달료", "총 합계", "총합계", "할증", "포인트",
        "모두 거절", "2건 모두 수락", "3건 모두 수락",
        "신규배차_모두거절버튼", "신규배차_모두수락버튼",
        "현재 위치와 가까운 배차를 찾고 있어요",
        "고객 요청사항이 변경되었어요",
        "배달이 많은 지역을 볼수 있어요",
        "배차대기중_상단_마이페이지_버튼",
        // 시스템 메시지
        "중복된 요청입니다", "중복된 요청입니다."
    )

    /** 영문 소문자+숫자+하이픈만으로 구성된 패턴 (accessibility view ID) */
    private val VIEW_ID_PATTERN = Regex("^[a-z][a-z0-9\\-]*$")

    // 대문자+숫자 8글자 이상 토큰 (T2CK0000RGQM 등)
    private val TOKEN_PATTERN = Regex("^[A-Z0-9]{8,}$")

    // 주소 시작 패턴 (좌표/주소가 가게명으로 혼입 방지)
    private val ADDRESS_PREFIX = Regex("^(경기도?|서울특별시|서울시|부산|대구|인천|광주광역시|대전|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)")

    /** 가게명 블랙리스트 판정 (정확 일치 + 패턴) */
    fun isBlacklistedPattern(name: String): Boolean {
        if (name.isBlank()) return true
        if (STORE_NAME_BLACKLIST.contains(name)) return true
        if (STORE_NAME_BLACKLIST.contains(name.lowercase())) return true
        if (name.startsWith("T2CG")) return true
        if (name.startsWith("T2CK")) return true
        if (Regex("T2CI[A-Z0-9]{4,}").containsMatchIn(name)) return true
        // 대문자+숫자 8글자 이상 토큰
        if (TOKEN_PATTERN.matches(name)) return true
        // 주소/좌표 패턴
        if (ADDRESS_PREFIX.containsMatchIn(name)) return true
        // accessibility view ID 패턴 (영소문자+숫자+하이픈만)
        if (VIEW_ID_PATTERN.matches(name)) return true
        if (name.contains("-item-")) return true
        if (name.startsWith("notification-")) return true
        if (name.startsWith("ai-")) return true
        return false
    }

    /**
     * texts 리스트에서 "배달료기준거리 (X,XXXm)" 패턴 찾아 km로 반환.
     * 없으면 null.
     */
    private fun extractActualDistance(texts: List<String>): Double? {
        val joined = texts.joinToString(" ")
        val match = DISTANCE_PATTERN.find(joined) ?: return null
        val meters = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
        val km = meters / 1000.0
        Log.d("BaeminDistance", "parsed ${meters}m = ${km}km")
        return km
    }

    /**
     * 가게명에서 UI 컴포넌트 오염 토큰 제거.
     * "+" 구분자로 split → 블랙리스트 토큰 제거 → 재조합.
     */
    fun sanitizeStoreName(raw: String): String {
        if (raw.isBlank()) return ""
        val tokens = raw.split("+", ",").map { it.trim() }
        val removed = mutableListOf<String>()
        val clean = tokens.filter { token ->
            val isBlacklisted = isBlacklistedPattern(token)
            if (isBlacklisted) removed.add(token)
            !isBlacklisted
        }
        if (removed.isNotEmpty()) {
            OtwFileLogger.log("BaeminParser", "가게명 필터: \"$raw\" → \"${clean.joinToString("+")}\" (제거: ${removed.joinToString(", ")})")
        }
        return clean.joinToString("+")
    }

    fun parse(texts: List<String>): List<DeliveryCall>? {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

        // 이전내역 화면 감지 → DROP (신규 콜 증거 있으면 우회)
        val hasNewCallEvidence =
            joined.contains("신규배차_수락버튼") ||
            joined.contains("배차수락") ||
            Regex("\\d+초(?!\\S)").containsMatchIn(joined)

        if (HISTORY_SCREEN_KEYWORDS.any { joined.contains(it) }) {
            if (hasNewCallEvidence) {
                OtwFileLogger.log("BaeminParser", "HISTORY_KEYWORD_BUT_NEW_CALL: keyword detected but proceeding")
            } else {
                OtwFileLogger.log("BaeminParser", "DROP_HISTORY_SCREEN")
                return null
            }
        }

        // v3.17: 가게명 추출 — "픽업지" 다음 토큰 우선, 기존 패턴 매칭 보조
        val UI_LABELS = setOf(
            "배민배달", "배민커넥트", "픽업지", "전달지", "포인트", "총 합계", "총합계",
            "모두 거절", "지도앱으로 검색하기", "조리완료", "배차", "배차 수락",
            "배달료", "수락", "거절"
        )
        val UI_PATTERN = Regex("""^\d+(건|초|분)""")

        // 방법1: "픽업지" 다음 토큰 (가장 정확)
        val pickupIdx = texts.indexOfFirst { it.trim() == "픽업지" }
        val storeAfterPickup = if (pickupIdx >= 0 && pickupIdx + 1 < texts.size) {
            val candidate = texts[pickupIdx + 1].trim()
            if (candidate.isNotBlank() && candidate !in UI_LABELS && !UI_PATTERN.containsMatchIn(candidate)
                && !PRICE_PATTERN.containsMatchIn(candidate) && !candidate.contains("원") && !candidate.contains("P"))
                candidate else null
        } else null

        // 방법2: 기존 패턴 매칭
        val storeNames = texts.filter { t ->
            t.trim().let { tt ->
                tt.length in 2..30 && tt !in UI_LABELS && !UI_PATTERN.containsMatchIn(tt) &&
                !PRICE_PATTERN.containsMatchIn(tt) &&
                !tt.contains("배달료") && !tt.contains("원") && !tt.contains("P") &&
                !tt.contains("배달을") && !tt.contains("신규배차") &&
                STORE_PATTERN.matches(tt)
            }
        }.map { it.trim() }.distinct()
            .filter { StoreNameCleaner.validateStoreName(it).isNotEmpty() }

        val rawStoreName = storeAfterPickup ?: storeNames.firstOrNull() ?: ""
        val storeName = sanitizeStoreName(StoreNameCleaner.validateStoreName(rawStoreName))

        // 방법1: "전달지" 다음 토큰 (가장 정확)
        val destIdx = texts.indexOfFirst { it.trim() == "전달지" }
        val destAfterLabel = if (destIdx >= 0 && destIdx + 1 < texts.size) {
            val candidate = texts[destIdx + 1].trim()
            if (candidate.isNotBlank() && candidate !in UI_LABELS && !PRICE_PATTERN.containsMatchIn(candidate)
                && !candidate.contains("원") && !candidate.contains("P"))
                candidate else null
        } else null

        // 방법2: 기존 패턴 매칭
        val destByPattern = texts.firstOrNull { t ->
            t.length in 3..30 && DEST_PATTERN.matches(t.trim())
        }?.trim()

        val destination = destAfterLabel ?: destByPattern ?: ""

        // 포인트 파싱 (배민커넥트 거리 지표)
        val point = POINT_PATTERN.find(joined)?.groupValues?.get(1)?.toDoubleOrNull()

        // 방법1: 단일 노드에 "배달료 7,010원" 있는 경우
        for (text in texts) {
            val match = PRICE_PATTERN.find(text) ?: continue
            val price = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (price in 500..100000 && results.none { it.price == price }) {
                results.add(DeliveryCall(
                    price = price, distance = extractActualDistance(texts), isMulti = false, platform = "baemin",
                    rawText = joined, storeName = storeName, destination = destination,
                    point = point, parsingMethod = V2Event.PARSING_ACCESSIBILITY_TEXT
                ))
                Log.d("BaeminParser", "파싱(단일): ${price}원, point=${point}P, store=$storeName")
                OtwFileLogger.log("BaeminParser", "파싱(단일): ${price}원, point=${point}P, store=$storeName")
            }
        }

        // 방법2: "배달료" / "7,010원" 이 별도 노드인 경우
        if (results.isEmpty()) {
            for (i in texts.indices) {
                if (texts[i].trim() != "배달료") continue
                val next = texts.getOrNull(i + 1)?.trim() ?: continue
                val match = AMOUNT_PATTERN.find(next) ?: continue
                val price = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
                if (price in 500..100000 && results.none { it.price == price }) {
                    results.add(DeliveryCall(
                        price = price, distance = extractActualDistance(texts), isMulti = false, platform = "baemin",
                        rawText = joined, storeName = storeName, destination = destination,
                        point = point, parsingMethod = V2Event.PARSING_ACCESSIBILITY_TEXT
                    ))
                    Log.d("BaeminParser", "파싱(분리노드): ${price}원")
                    OtwFileLogger.log("BaeminParser", "파싱(분리노드): ${price}원")
                }
            }
        }

        // 방법3: join 후 재시도
        if (results.isEmpty()) {
            val match = PRICE_PATTERN.find(joined)
            if (match != null) {
                val price = match.groupValues[1].replace(",", "").toIntOrNull()
                if (price != null && price in 500..100000) {
                    results.add(DeliveryCall(
                        price = price, distance = extractActualDistance(texts), isMulti = false, platform = "baemin",
                        rawText = joined, storeName = storeName, destination = destination,
                        point = point, parsingMethod = V2Event.PARSING_TEXT_REGEX
                    ))
                    Log.d("BaeminParser", "파싱(join): ${price}원")
                    OtwFileLogger.log("BaeminParser", "파싱(join): ${price}원")
                }
            }
        }

        // ── 묶음배달 합산 판정 (v2 2.0 개선) ──
        val isBundle = BUNDLE_PATTERN.containsMatchIn(joined) || results.size >= 2
        if (isBundle && results.size >= 2) {
            val totalPrice = results.sumOf { it.price }

            // 묶음 건수 추출
            val bundleCount = BUNDLE_COUNT_PATTERN.find(joined)?.groupValues?.get(1)?.toIntOrNull()
                ?: results.size

            // 다중 픽업 판정: 서로 다른 가게명이 2개 이상 (블랙리스트 제외)
            val cleanStoreNames = storeNames.filter { !isBlacklistedPattern(it) }
            val isMultiPickup = cleanStoreNames.size >= 2

            Log.d("BaeminParser", "묶음배달 감지: ${bundleCount}건 합산 ${totalPrice}원, 다중픽업=$isMultiPickup")
            OtwFileLogger.log("BaeminParser", "묶음배달 감지: ${bundleCount}건 합산 ${totalPrice}원, 다중픽업=$isMultiPickup")
            return listOf(DeliveryCall(
                price = totalPrice,
                distance = extractActualDistance(texts),
                isMulti = true,
                platform = "baemin",
                rawText = joined,
                storeName = sanitizeStoreName(storeNames.joinToString("+")),
                destination = destination,
                bundleCount = bundleCount,
                isMultiPickup = isMultiPickup,
                point = point,
                parsingMethod = V2Event.PARSING_ACCESSIBILITY_TEXT
            ))
        }

        // 결과 로그
        if (results.isNotEmpty()) {
            val r = results[0]
            OtwFileLogger.log("BaeminParser", "result: store='${r.storeName.ifEmpty { "(없음)" }}', price=${r.price}, distance=${r.distance}km, multi=${r.isMulti}, callCount=${results.size}")
        }

        // 단건도 포인트 포함
        return results
    }

    /** 배민 포인트 값 추출 (거리 지표) */
    fun parsePoint(texts: List<String>): Double? {
        val joined = texts.joinToString(" ")
        return POINT_PATTERN.find(joined)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** 고객 요청사항 키워드 */
    private val CUSTOMER_REQUEST_KEYWORDS = listOf(
        "문 앞", "초인종", "벨", "놓아", "비밀번호", "놓고", "두고",
        "전화", "노크", "경비실", "무인택배", "비대면"
    )

    /** 고객 요청사항 단축 매핑 */
    private val REQUEST_SHORTEN = listOf(
        Regex("문\\s*앞.*초인종") to "문 앞 초인종",
        Regex("문\\s*앞.*놓아") to "문 앞 놓기",
        Regex("문\\s*앞.*두고") to "문 앞 놓기",
        Regex("벨.*누르지") to "벨 누르지 마세요",
        Regex("초인종.*누르지") to "벨 누르지 마세요",
        Regex("비대면") to "비대면",
        Regex("경비실") to "경비실",
        Regex("무인택배") to "무인택배",
        Regex("비밀번호") to "비밀번호",
        Regex("전화") to "전화 주세요",
        Regex("노크") to "노크"
    )

    /**
     * 배달 진행 중 화면에서 고객 요청사항 파싱.
     * 키워드 포함 텍스트 노드를 찾아 단축 반환.
     */
    fun parseCustomerRequest(texts: List<String>): String? {
        for (text in texts) {
            val trimmed = text.trim()
            if (trimmed.length in 3..100 && CUSTOMER_REQUEST_KEYWORDS.any { trimmed.contains(it) }) {
                for ((pattern, short) in REQUEST_SHORTEN) {
                    if (pattern.containsMatchIn(trimmed)) return short
                }
                return trimmed
            }
        }
        return null
    }
}
