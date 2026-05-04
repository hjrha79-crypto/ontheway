package com.vita.ontheway

import org.json.JSONObject

/**
 * 서버 전송 데이터 익명화.
 * 부정경쟁방지법 (2024.8 개정) + 개인정보보호법 대응.
 *
 * 원칙:
 * - 가게명/주소/원문 = 로컬만
 * - 서버 = 버킷화된 통계만
 */
object DataAnonymizer {

    /** 금액 버킷 (1000원 단위) */
    fun bucketPrice(price: Int): Int = (price / 1000) * 1000

    /** 거리 버킷 (0.5km 단위) */
    fun bucketDistance(km: Double?): Double? {
        if (km == null || km < 0) return null
        return Math.floor(km * 2) / 2.0
    }

    /** 시간 버킷 (1시간 단위) */
    fun bucketTimeHour(ts: Long): Long = (ts / 3600_000) * 3600_000

    /**
     * 서버 전송용 JSON 익명화.
     * 민감 필드 제거 + 버킷화.
     */
    fun anonymize(raw: JSONObject): JSONObject {
        val safe = JSONObject()

        // 허용 필드 (그대로)
        raw.optLong("ts", 0).takeIf { it > 0 }?.let { safe.put("ts_bucket", bucketTimeHour(it)) }
        raw.optString("platform").takeIf { it.isNotBlank() }?.let { safe.put("platform", it) }
        raw.optString("verdict").takeIf { it.isNotBlank() }?.let { safe.put("verdict", it) }
        raw.optString("driver_action").takeIf { it.isNotBlank() }?.let { safe.put("driver_action", it) }
        raw.optString("source_app").takeIf { it.isNotBlank() }?.let { safe.put("source_app", it) }
        raw.optString("source_type").takeIf { it.isNotBlank() }?.let { safe.put("source_type", it) }
        raw.optString("parsing_method").takeIf { it.isNotBlank() }?.let { safe.put("parsing_method", it) }
        raw.optString("judge_version").takeIf { it.isNotBlank() }?.let { safe.put("judge_version", it) }
        raw.optString("event_type").takeIf { it.isNotBlank() }?.let { safe.put("event_type", it) }
        raw.optString("feedback").takeIf { it.isNotBlank() }?.let { safe.put("feedback", it) }
        raw.optString("match_status").takeIf { it.isNotBlank() }?.let { safe.put("match_status", it) }
        raw.optString("user_action").takeIf { it.isNotBlank() }?.let { safe.put("user_action", it) }
        raw.optString("judgment").takeIf { it.isNotBlank() }?.let { safe.put("judgment", it) }
        if (raw.has("bundle_count")) safe.put("bundle_count", raw.optInt("bundle_count"))
        if (raw.has("is_multi_pickup")) safe.put("is_multi_pickup", raw.optBoolean("is_multi_pickup"))
        if (raw.has("tts_suppressed")) safe.put("tts_suppressed", raw.optBoolean("tts_suppressed"))

        // 버킷화 필드
        raw.optInt("price", 0).takeIf { it > 0 }?.let { safe.put("price_bucket", bucketPrice(it)) }
        raw.optInt("unit_price", 0).takeIf { it > 0 }?.let { safe.put("unit_price_bucket", bucketPrice(it)) }
        if (raw.has("distance") && !raw.isNull("distance")) {
            safe.put("distance_bucket", bucketDistance(raw.optDouble("distance")))
        }
        if (raw.has("pickup_km") && !raw.isNull("pickup_km")) {
            safe.put("pickup_km_bucket", bucketDistance(raw.optDouble("pickup_km")))
        }
        if (raw.has("distance_km") && !raw.isNull("distance_km")) {
            safe.put("distance_km_bucket", bucketDistance(raw.optDouble("distance_km")))
        }
        if (raw.has("point") && !raw.isNull("point")) {
            safe.put("point_bucket", bucketDistance(raw.optDouble("point")))
        }

        // 제거 (서버 미전송):
        // store_name, destination, reason, rawText, session_id, local_id,
        // memo, pickup_rating, delivery_rating, price_rating, judgment_rating

        return safe
    }
}
