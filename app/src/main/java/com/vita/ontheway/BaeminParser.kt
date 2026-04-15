package com.vita.ontheway

import android.util.Log

object BaeminParser {

    private val PRICE_PATTERN = Regex("배달료\\s*([\\d,]+)\\s*원")
    private val AMOUNT_PATTERN = Regex("^([\\d,]+)\\s*원$")
    private val POINT_PATTERN = Regex("([\\d.]+)\\s*P", RegexOption.IGNORE_CASE)
    private val STORE_PATTERN = Regex("^[가-힣a-zA-Z0-9\\s]{2,20}$")
    private val DEST_PATTERN = Regex("^[가-힣]+(구|동|시|면|로|길).*")
    // 묶음배달 패턴: "묶음배달", "2건", "3건 묶음" 등
    private val BUNDLE_PATTERN = Regex("묶음|\\d+건", RegexOption.IGNORE_CASE)
    private val BUNDLE_COUNT_PATTERN = Regex("(\\d+)\\s*건")
    // 총 합계 패턴
    private val TOTAL_PATTERN = Regex("총\\s*합계|합계\\s*([\\d,]+)\\s*원")

    // 묶음 총액 기록 (부분 파싱 중복 방지용)
    private var lastBundleTotalPrice: Int = 0
    private var lastBundleTotalTime: Long = 0
    private const val BUNDLE_DEDUP_MS = 10_000L  // 10초

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

        // 가게명/전달지 추출
        val storeNames = texts.filter { t ->
            t.length in 2..20 &&
            !PRICE_PATTERN.containsMatchIn(t) &&
            !t.contains("배달료") && !t.contains("원") && !t.contains("P") &&
            !t.contains("배달을") && !t.contains("신규배차") &&
            STORE_PATTERN.matches(t.trim())
        }.map { it.trim() }.distinct()

        val storeName = storeNames.firstOrNull() ?: ""

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
                    rawText = joined, storeName = storeName, destination = destination,
                    point = point
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
                        rawText = joined, storeName = storeName, destination = destination,
                        point = point
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
                        rawText = joined, storeName = storeName, destination = destination,
                        point = point
                    ))
                    Log.d("BaeminParser", "파싱(join): ${price}원")
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

            // 다중 픽업 판정: 서로 다른 가게명이 2개 이상
            val isMultiPickup = storeNames.size >= 2

            // 부분 파싱 중복 방지: 총 합계 후 10초 이내 부분 조합 스킵
            val now = System.currentTimeMillis()
            if (lastBundleTotalPrice > 0 && now - lastBundleTotalTime < BUNDLE_DEDUP_MS) {
                // 현재 합산이 이전 총액의 부분합인지 확인
                if (totalPrice < lastBundleTotalPrice && totalPrice > lastBundleTotalPrice / 2) {
                    Log.d("BaeminParser", "묶음 부분합 중복 스킵: ${totalPrice}원 (총액 ${lastBundleTotalPrice}원, ${now - lastBundleTotalTime}ms)")
                    return emptyList()
                }
            }

            // 총 합계 기록
            val hasTotal = TOTAL_PATTERN.containsMatchIn(joined)
            if (hasTotal) {
                lastBundleTotalPrice = totalPrice
                lastBundleTotalTime = now
            }

            Log.d("BaeminParser", "묶음배달 감지: ${bundleCount}건 합산 ${totalPrice}원, 다중픽업=$isMultiPickup")
            return listOf(DeliveryCall(
                price = totalPrice,
                distance = null,
                isMulti = true,
                platform = "baemin",
                rawText = joined,
                storeName = storeNames.joinToString("+"),
                destination = destination,
                bundleCount = bundleCount,
                isMultiPickup = isMultiPickup,
                point = point
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
