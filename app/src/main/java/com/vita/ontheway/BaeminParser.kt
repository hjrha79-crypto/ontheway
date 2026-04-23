package com.vita.ontheway

import android.util.Log

object BaeminParser {

    // 배민 포인트→거리 환산 계수 (v3.10)
    // 2026-04-19 실측 검증: 38.3P→6km, 50.5P→8km, 53.9P→8~9km
    // 기존 0.25 → 0.15 (실측 기반 약 60% 수준)
    const val BAEMIN_POINT_TO_KM = 0.15

    /** 배민 포인트 → 추정 거리(km) 변환 */
    fun convertPointToKm(points: Double): Double = points * BAEMIN_POINT_TO_KM

    private val PRICE_PATTERN = Regex("배달료\\s*([\\d,]+)\\s*원")
    private val AMOUNT_PATTERN = Regex("^([\\d,]+)\\s*원$")
    private val POINT_PATTERN = Regex("([\\d.]+)\\s*P", RegexOption.IGNORE_CASE)
    // v3.20: 한자(秀), 특수문자(&/·-(),'') 허용, 길이 30까지
    private val STORE_PATTERN = Regex("^[가-힣a-zA-Z0-9\\s\\u3400-\\u9FFF&/·\\-.(),']{2,30}$")
    private val DEST_PATTERN = Regex("^[가-힣]+(구|동|시|면|로|길).*")
    // 묶음배달 패턴: "묶음배달", "2건", "3건 묶음" 등
    private val BUNDLE_PATTERN = Regex("묶음|\\d+건", RegexOption.IGNORE_CASE)
    private val BUNDLE_COUNT_PATTERN = Regex("(\\d+)\\s*건")

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

        val rawStoreName = storeAfterPickup ?: storeNames.firstOrNull() ?: ""
        val storeName = StoreNameCleaner.validateStoreName(rawStoreName)

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
