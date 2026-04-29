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
        "지도앱으로 검색하기"
    )

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
            val isBlacklisted = token.isNotBlank() && (
                STORE_NAME_BLACKLIST.contains(token) ||
                STORE_NAME_BLACKLIST.contains(token.lowercase()) ||
                token.startsWith("T2CG")
            )
            if (isBlacklisted) removed.add(token)
            !isBlacklisted && token.isNotBlank()
        }
        if (removed.isNotEmpty()) {
            OtwFileLogger.log("BaeminParser", "가게명 필터: \"$raw\" → \"${clean.joinToString("+")}\" (제거: ${removed.joinToString(", ")})")
        }
        return clean.joinToString("+")
    }

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

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
            val cleanStoreNames = storeNames.filter { !STORE_NAME_BLACKLIST.contains(it.lowercase()) }
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

        // 단건도 포인트 포함
        return results
    }

    /** 배민 포인트 값 추출 (거리 지표) */
    fun parsePoint(texts: List<String>): Double? {
        val joined = texts.joinToString(" ")
        return POINT_PATTERN.find(joined)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
