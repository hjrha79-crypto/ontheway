package com.vita.ontheway

import android.util.Log

object BaeminParser {

    // ⑤ "배달료 2,300원"
    // ⑥ "배달료 3,360원"
    private val PRICE_PATTERN = Regex("배달료\\s*([\\d,]+)\\s*원")

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()

        for (text in texts) {
            if (text.length < 4 || text.length > 50) continue
            val match = PRICE_PATTERN.find(text) ?: continue
            val price = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (price !in 500..100000) continue
            if (results.any { it.price == price }) continue

            results.add(DeliveryCall(
                price = price,
                distance = null,
                isMulti = false,
                platform = "baemin"
            ))
            Log.d("BaeminParser", "파싱: ${price}원")
        }

        return results
    }
}
