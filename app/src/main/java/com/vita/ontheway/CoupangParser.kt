package com.vita.ontheway

import android.util.Log

object CoupangParser {

    // ① "(조리완료) 3,000원 배달 거리 0.7km"
    // ② "멀티 3,525원 거리할증 지원금 포함 배달 거리 2.1km"
    // ③ "대량 주문, 멀티 11,948원 거리할증 지원금 포함 배달 거리 5.3km"
    // ④ "멀티 8,300원 거래할증 포함 주문 두 건 배달 거리 5.5km"
    private val PRICE_PATTERN = Regex("([\\d,]+)\\s*원")
    private val DISTANCE_PATTERN = Regex("배달\\s*거리\\s*(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
    private val MULTI_PATTERN = Regex("멀티|주문\\s*두\\s*건|대량", RegexOption.IGNORE_CASE)

    private val STORE_PATTERN = Regex("^[가-힣a-zA-Z0-9\\s]{2,20}$")

    // 비콜 화면 키워드 (이 텍스트가 포함되면 콜이 아닌 UI 화면)
    private val NON_CALL_KEYWORDS = setOf(
        "배달 현황", "출근하기", "퇴근하기", "배달 완료",
        "고객에게 전달", "픽업 완료", "가게 도착", "고객 도착",
        "배달 중", "픽업 중", "주문 현황", "정산", "공지사항",
        "배달 내역", "수입 현황", "내 정보", "설정",
        // v2.1 추가
        "미션", "보상", "프로모션", "이벤트", "리워드",
        "완료 시 최대", "추가 수입", "인센티브",
        "주문을 기다리는 중", "대기 중",
        // v2.2 유령콜 필터
        "배달이 많은 곳으로"
    )

    // 콜 화면 필수 버튼 텍스트: 이 중 하나는 있어야 진짜 콜
    private val CALL_SCREEN_BUTTONS = setOf("거절", "주문 수락", "주문수락")

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

        // 비콜 필터링: 배달 진행/완료/메뉴 화면이면 빈 리스트 반환
        if (NON_CALL_KEYWORDS.any { joined.contains(it) }) {
            Log.d("CoupangParser", "비콜 화면 감지 - 스킵: ${joined.take(50)}")
            return results
        }

        // v2.1: "거절" 또는 "주문 수락" 버튼이 없으면 콜 화면이 아님
        if (CALL_SCREEN_BUTTONS.none { joined.contains(it) }) {
            Log.d("CoupangParser", "콜 버튼 없음 - 비콜 스킵: ${joined.take(50)}")
            return results
        }

        // 가게명 추출: 금액/거리/키워드가 아닌 짧은 한글 텍스트
        val storeName = texts.firstOrNull { t ->
            t.length in 2..20 &&
            !PRICE_PATTERN.containsMatchIn(t) &&
            !DISTANCE_PATTERN.containsMatchIn(t) &&
            !MULTI_PATTERN.containsMatchIn(t) &&
            !t.contains("km") && !t.contains("원") &&
            STORE_PATTERN.matches(t.trim())
        }?.trim() ?: ""

        val priceMatch = PRICE_PATTERN.find(joined)
        if (priceMatch != null) {
            val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull()
            if (price != null && price in 1000..100000) {
                val distance = DISTANCE_PATTERN.find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                val isMulti = MULTI_PATTERN.containsMatchIn(joined)

                results.add(DeliveryCall(
                    price = price,
                    distance = distance,
                    isMulti = isMulti,
                    platform = "coupang",
                    rawText = joined,
                    storeName = storeName
                ))
                Log.d("CoupangParser", "파싱: ${price}원, ${distance}km, multi=$isMulti")
            }
        }

        // 개별 텍스트에서도 추가 콜 탐색
        for (text in texts) {
            if (text.length < 5 || text.length > 80) continue
            val pm = PRICE_PATTERN.find(text) ?: continue
            val p = pm.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (p !in 1000..100000) continue

            // joined에서 이미 잡힌 금액이면 스킵
            if (results.any { it.price == p }) continue

            val d = DISTANCE_PATTERN.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val m = MULTI_PATTERN.containsMatchIn(text)
            results.add(DeliveryCall(price = p, distance = d, isMulti = m, platform = "coupang", rawText = text, storeName = storeName))
            Log.d("CoupangParser", "추가 파싱: ${p}원, ${d}km, multi=$m")
        }

        return results
    }
}
