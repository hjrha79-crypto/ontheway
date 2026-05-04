package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 피드백 엔트리 데이터 클래스 */
data class FeedbackEntry(
    val ts: Long,
    val sessionId: String?,
    val platform: String,
    val storeName: String,
    val price: Int,
    val distanceKm: Double,
    val verdict: String,
    val reason: String,
    val feedback: String,       // "up" / "down"
    val reasons: List<String>,  // ["pickup_GOOD", "delivery_BAD", ...]
    val overwroteTs: Long? = null,
    val driverAction: String = "unknown",
    // v3.22: 양방향 매트릭스 필드
    val pickupRating: String? = null,     // "GOOD" / "BAD" / null
    val deliveryRating: String? = null,
    val priceRating: String? = null,
    val judgmentRating: String? = null,
    val entryPoint: String? = null,       // "thumbs_up" / "thumbs_down"
    // v3.24: 거리 차이 자동 수집
    val platformDistanceKm: Float? = null,
    val onthewayDistanceKm: Float? = null,
    val distanceDiffKm: Float? = null,
    val pointValue: Float? = null,          // 배민 포인트 (예: 27.5)
    val memo: String? = null               // 자유 메모
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("platform", platform)
        put("store", storeName)
        put("price", price)
        put("distance_km", distanceKm)
        put("verdict", verdict)
        put("reason", reason)
        put("session_id", sessionId ?: JSONObject.NULL)
        put("feedback", feedback)
        put("reasons", JSONArray().also { arr -> reasons.forEach { arr.put(it) } })
        if (overwroteTs != null) put("overwrote_ts", overwroteTs)
        put("driver_action", driverAction)
        put("pickup_rating", pickupRating ?: JSONObject.NULL)
        put("delivery_rating", deliveryRating ?: JSONObject.NULL)
        put("price_rating", priceRating ?: JSONObject.NULL)
        put("judgment_rating", judgmentRating ?: JSONObject.NULL)
        put("entry_point", entryPoint ?: JSONObject.NULL)
        put("platform_distance_km", platformDistanceKm?.toDouble() ?: JSONObject.NULL)
        put("ontheway_distance_km", onthewayDistanceKm?.toDouble() ?: JSONObject.NULL)
        put("distance_diff_km", distanceDiffKm?.toDouble() ?: JSONObject.NULL)
        put("point_value", pointValue?.toDouble() ?: JSONObject.NULL)
        if (!memo.isNullOrBlank()) put("memo", memo)
        put("event_type", "call_feedback_submitted")
        put("source_app", "on_the_way")
    }

    companion object {
        fun fromJson(json: JSONObject): FeedbackEntry? = try {
            val reasonsArr = json.optJSONArray("reasons")
            val reasonsList = mutableListOf<String>()
            if (reasonsArr != null) {
                for (i in 0 until reasonsArr.length()) {
                    reasonsList.add(reasonsArr.getString(i))
                }
            }
            FeedbackEntry(
                ts = json.getLong("ts"),
                sessionId = json.optString("session_id").takeIf { it.isNotBlank() && it != "null" },
                platform = json.optString("platform", ""),
                storeName = json.optString("store", ""),
                price = json.optInt("price", 0),
                distanceKm = json.optDouble("distance_km", 0.0),
                verdict = json.optString("verdict", ""),
                reason = json.optString("reason", ""),
                feedback = json.optString("feedback", ""),
                reasons = reasonsList,
                overwroteTs = if (json.has("overwrote_ts")) json.getLong("overwrote_ts") else null,
                driverAction = json.optString("driver_action", "unknown"),
                pickupRating = json.optString("pickup_rating").takeIf { it.isNotBlank() && it != "null" },
                deliveryRating = json.optString("delivery_rating").takeIf { it.isNotBlank() && it != "null" },
                priceRating = json.optString("price_rating").takeIf { it.isNotBlank() && it != "null" },
                judgmentRating = json.optString("judgment_rating").takeIf { it.isNotBlank() && it != "null" },
                entryPoint = json.optString("entry_point").takeIf { it.isNotBlank() && it != "null" },
                platformDistanceKm = if (json.has("platform_distance_km") && !json.isNull("platform_distance_km")) json.optDouble("platform_distance_km").toFloat() else null,
                onthewayDistanceKm = if (json.has("ontheway_distance_km") && !json.isNull("ontheway_distance_km")) json.optDouble("ontheway_distance_km").toFloat() else null,
                distanceDiffKm = if (json.has("distance_diff_km") && !json.isNull("distance_diff_km")) json.optDouble("distance_diff_km").toFloat() else null,
                pointValue = if (json.has("point_value") && !json.isNull("point_value")) json.optDouble("point_value").toFloat() else null,
                memo = json.optString("memo").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            Log.w("FeedbackLogger", "엔트리 파싱 실패: ${e.message}")
            null
        }
    }
}

/** v3.19: 피드백 로그 — JSONL 파일 append (AnnoyLogger 대체) */
object FeedbackLogger {

    private const val FILE_NAME = "feedback_logs.jsonl"

    fun log(ctx: Context, platform: String, store: String, price: Int,
            distanceKm: Double, verdict: String, reason: String,
            sessionId: String, feedback: String, reasons: List<String>,
            driverAction: String = "unknown",
            pickupRating: String? = null, deliveryRating: String? = null,
            priceRating: String? = null, judgmentRating: String? = null,
            entryPoint: String? = null,
            platformDistanceKm: Float? = null, onthewayDistanceKm: Float? = null,
            distanceDiffKm: Float? = null, pointValue: Float? = null,
            memo: String? = null) {
        try {
            val entry = FeedbackEntry(
                ts = System.currentTimeMillis(),
                sessionId = sessionId,
                platform = platform,
                storeName = store,
                price = price,
                distanceKm = distanceKm,
                verdict = verdict,
                reason = reason,
                feedback = feedback,
                reasons = reasons,
                driverAction = driverAction,
                pickupRating = pickupRating,
                deliveryRating = deliveryRating,
                priceRating = priceRating,
                judgmentRating = judgmentRating,
                entryPoint = entryPoint,
                platformDistanceKm = platformDistanceKm,
                onthewayDistanceKm = onthewayDistanceKm,
                distanceDiffKm = distanceDiffKm,
                pointValue = pointValue,
                memo = memo
            )
            val json = entry.toJson()
            val file = File(ctx.filesDir, FILE_NAME)
            file.appendText(json.toString() + "\n")
            // Supabase 업로드 (백그라운드, 실패해도 로컬 저장 완료)
            try { SupabaseSync.uploadFeedback(ctx, JSONObject(json.toString())) } catch (_: Exception) {}
        } catch (_: Exception) {
            // 에러 시 조용히 실패
        }
    }

    /** 전체 엔트리 조회 (시간순) */
    fun getAll(ctx: Context): List<FeedbackEntry> {
        return try {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { FeedbackEntry.fromJson(JSONObject(it)) }
        } catch (e: Exception) {
            Log.w("FeedbackLogger", "전체 조회 실패: ${e.message}")
            emptyList()
        }
    }

    /** 최신순 N건 조회 */
    fun getRecent(ctx: Context, limit: Int = 10): List<FeedbackEntry> {
        return getAll(ctx).sortedByDescending { it.ts }.take(limit)
    }

    /** session_id로 조회 */
    fun findBySessionId(ctx: Context, sessionId: String): FeedbackEntry? {
        return getAll(ctx).firstOrNull { it.sessionId == sessionId }
    }

    /** 콜 타임스탬프+가격으로 피드백 조회 (콜 리스트 표시용) */
    fun findByCall(ctx: Context, callTs: Long, price: Int): FeedbackEntry? {
        // 정확한 sessionId 매칭 (CallDetailDialog 경로)
        val sessionId = "s_${callTs}_${price}"
        val exact = findBySessionId(ctx, sessionId)
        if (exact != null) {
            OtwFileLogger.log("FeedbackLookup", "findByCall 정확매칭: ts=$callTs price=$price → fb=${exact.feedback} sid=${exact.sessionId}")
            return exact
        }
        // 시간 근접 + 가격 일치 매칭 (MainActivity 경로: sessionId = "s_{feedbackTime}")
        val approx = getAll(ctx).firstOrNull { it.price == price && kotlin.math.abs(it.ts - callTs) < 120_000 }
        OtwFileLogger.log("FeedbackLookup", "findByCall 근접매칭: ts=$callTs price=$price → ${if (approx != null) "fb=${approx.feedback} diff=${kotlin.math.abs(approx.ts - callTs)}ms sid=${approx.sessionId}" else "null"}")
        return approx
    }

    /** session_id 기준 덮어쓰기 (전체 파일 재작성) */
    fun updateBySessionId(ctx: Context, sessionId: String, newEntry: FeedbackEntry): Boolean {
        return try {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return false
            val lines = file.readLines().filter { it.isNotBlank() }
            var found = false
            val updated = lines.map { line ->
                val json = JSONObject(line)
                val sid = json.optString("session_id")
                if (sid == sessionId) {
                    found = true
                    newEntry.copy(overwroteTs = System.currentTimeMillis()).toJson().toString()
                } else {
                    line
                }
            }
            if (found) {
                file.writeText(updated.joinToString("\n") + "\n")
            }
            found
        } catch (e: Exception) {
            Log.w("FeedbackLogger", "덮어쓰기 실패: ${e.message}")
            false
        }
    }
}
