package com.vita.ontheway

import android.util.Log

object BaeminParser {

    private val PRICE_PATTERN = Regex("배달료\\s*([\\d,]+)\\s*원")
    private val AMOUNT_PATTERN = Regex("^([\\d,]+)\\s*원$")
    private val POINT_PATTERN = Regex("([\\d.]+)\\s*P", RegexOption.IGNORE_CASE)
    private val STORE_PATTERN = Regex("^[가-힣a-zA-Z0-9\\s]{2,20}$")
    private val DEST_PATTERN = Regex("^[가-힣]+(구|동|시|면|로|길).*")

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

        // 가게명/전달지 추출
        val storeName = texts.firstOrNull { t ->
            t.length in 2..20 &&
            !PRICE_PATTERN.containsMatchIn(t) &&
            !t.contains("배달료") && !t.contains("원") && !t.contains("P") &&
            !t.contains("배달을") && !t.contains("신규배차") &&
            STORE_PATTERN.matches(t.trim())
        }?.trim() ?: ""

        val destination = texts.firstOrNull { t ->
            t.length in 3..30 && DEST_PATTERN.matches(t.trim())
        }?.trim() ?: ""

        // 포인트 파싱 (배민커넥트 거리 지표)
        val point = POINT_PATTERN.find(joined)?.groupValues?.get(1)?.toDoubleOrNull()

        // 방법1: 단일 노드에 "배달료 7,010원" 있는 경우
        for (text in texts) {
            val match = PRICE_PATTERN.find(text) ?: continue
            val price = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (price in 500..100000 && results.none { it.price == price }) {
                results.add(DeliveryCall(
                    price = price, distance = null, isMulti = false, platform = "baemin",
                    rawText = joined, storeName = storeName, destination = destination
                ))
                Log.d("BaeminParser", "파싱(단일): ${price}원, point=${point}P, store=$storeName")
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
                        price = price, distance = null, isMulti = false, platform = "baemin",
                        rawText = joined, storeName = storeName, destination = destination
                    ))
                    Log.d("BaeminParser", "파싱(분리노드): ${price}원")
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
                        price = price, distance = null, isMulti = false, platform = "baemin",
                        rawText = joined, storeName = storeName, destination = destination
                    ))
                    Log.d("BaeminParser", "파싱(join): ${price}원")
                }
            }
        }

        return results
    }

    /** 배민 포인트 값 추출 (거리 지표) */
    fun parsePoint(texts: List<String>): Double? {
        val joined = texts.joinToString(" ")
        return POINT_PATTERN.find(joined)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
