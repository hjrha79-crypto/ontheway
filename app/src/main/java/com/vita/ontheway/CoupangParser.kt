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

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

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
