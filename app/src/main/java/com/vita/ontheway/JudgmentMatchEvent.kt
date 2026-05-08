package com.vita.ontheway

import org.json.JSONArray
import org.json.JSONObject

data class JudgmentMatchEvent(
    val eventId: String,
    val timestamp: Long,
    val platform: String,
    val price: Int,
    val distanceKm: Double?,
    val storeName: String?,
    val onthewayJudgment: String,   // "JOB"/"OK"/"PASS"
    val userAction: String,          // "ACCEPTED"/"REJECTED"/"TIMEOUT"/"PENDING"
    val matchStatus: String,         // "MATCH"/"MISMATCH"/"PENDING"
    val userExplicitFeedback: String? = null,
    val rejectionReason: String? = null,
    val acceptanceReason: String? = null,
    val sourceApp: String = "on_the_way",
    val eventType: String = "judgment_action_matched",
    val routedAgent: String = "mobility"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("ts", timestamp)
        put("platform", platform)
        put("price", price)
        put("distance_km", distanceKm ?: JSONObject.NULL)
        put("store_name", storeName ?: JSONObject.NULL)
        put("judgment", onthewayJudgment)
        put("user_action", userAction)
        put("match_status", matchStatus)
        if (userExplicitFeedback != null) put("user_feedback", userExplicitFeedback)
        if (rejectionReason != null) put("rejection_reason", rejectionReason)
        if (acceptanceReason != null) put("acceptance_reason", acceptanceReason)
        put("source_app", sourceApp)
        put("event_type", eventType)
        put("routed_agent", routedAgent)
        put("summary", buildSummary())
        put("tags", JSONArray().apply {
            put("judgment_match")
            put(onthewayJudgment.lowercase())
            put(userAction.lowercase())
            put(matchStatus.lowercase())
        })
    }

    private fun buildSummary(): String {
        val judgmentKr = when (onthewayJudgment) {
            "JOB" -> "우세"; "OK" -> "보통"; "PASS" -> "주의"; else -> onthewayJudgment
        }
        val actionKr = when (userAction) {
            "ACCEPTED" -> "수락"; "TIMEOUT" -> "미수락"; else -> userAction
        }
        return "$platform ${price}원 판정=$judgmentKr 행동=$actionKr → $matchStatus"
    }

    companion object {
        fun computeMatchStatus(judgment: String, action: String): String {
            return when {
                judgment == "JOB" && action == "ACCEPTED" -> "MATCH"
                judgment == "JOB" && action == "TIMEOUT" -> "MISMATCH"
                judgment == "PASS" && action == "ACCEPTED" -> "MISMATCH"
                judgment == "PASS" && action == "TIMEOUT" -> "MATCH"
                judgment == "OK" -> "MATCH"
                else -> "UNKNOWN"
            }
        }

        fun verdictToJudgment(verdict: String): String = when (verdict) {
            "우세", "잡으세요" -> "JOB"
            "보통", "괜찮습니다" -> "OK"
            "주의", "넘기세요" -> "PASS"
            else -> "UNKNOWN"
        }
    }
}
