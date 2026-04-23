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
    val reasons: List<String>,  // ["pickup", "delivery", ...]
    val overwroteTs: Long? = null
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
                overwroteTs = if (json.has("overwrote_ts")) json.getLong("overwrote_ts") else null
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
            sessionId: String, feedback: String, reasons: List<String>) {
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
                reasons = reasons
            )
            val file = File(ctx.filesDir, FILE_NAME)
            file.appendText(entry.toJson().toString() + "\n")
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
