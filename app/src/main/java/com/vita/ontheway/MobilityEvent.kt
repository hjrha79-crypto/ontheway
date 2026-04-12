package com.vita.ontheway

// ──────────────────────────────────────────
// MobilityEvent — VitaCore 저장 스키마 v2.1
// ──────────────────────────────────────────
// 원칙
// - 기존 필드 변경 없음
// - 확장 필드는 모두 null 허용 (optional)
// - Shadow Mode 수집 전용 (Live Mode 로직 미반영)
// - 추천 INSERT → 행동 UPDATE 2단계 구조
// ──────────────────────────────────────────

data class MobilityEvent(

    // ══ 기존 필드 (변경 없음) ══════════════
    val eventId: String,
    val userId: String,
    val sessionId: String,
    val callId: String,
    val timestamp: String,

    val callType: String,          // "recommendation" | "manual"
    val driverDirection: String,
    val recommended: Boolean,
    val recommendScore: Int,
    val recommendAt: String,
    val responseTimeMs: Long,

    val shadowMode: Boolean,
    val driverAction: String,      // "accepted" | "ignored" | "missed" | "pending"
    val driverActionAt: String,

    // ══ VitaCore 정합성 필드 ════════════════
    val sourceApp: String = "on_the_way",     // 항상 on_the_way
    val routedAgent: String = "mobility",      // 항상 mobility
    val summary: String = "",                  // 추천 요약 한 줄
    val tags: List<String> = emptyList(),      // ["recommendation", "accepted"] 등

    // ══ 확장 필드 — Shadow Mode 수집 ═══════
    val lossWon: Int? = null,
    val mismatchReason: String? = null,
    val missedCall: Boolean? = null,
    val idleTime: Int? = null,
    val deltaRevenue: Int? = null,
    val userIgnoreReason: String? = null,
    val userFeedback: String? = null,

    // 급송 관련
    val isUrgent: Boolean = false,
    val urgentPassedFilter: Boolean = false,
    val urgentFilterFailReason: String? = null
)

// ──────────────────────────────────────────
// enum 상수 — 오타 방지
// ──────────────────────────────────────────
object IgnoreReason {
    const val DISTANCE   = "distance"
    const val PRICE      = "price"
    const val PREFERENCE = "preference"
    const val UNKNOWN    = "unknown"
}

object MismatchReasonConst {
    const val DIRECTION_ERROR  = "direction_error"
    const val PRICE_WEIGHT     = "price_weight"
    const val TIME_PREDICTION  = "time_prediction"
    const val USER_PREFERENCE  = "user_preference"
    const val UNKNOWN          = "unknown"
}

object DriverActionConst {
    const val ACCEPTED = "accepted"
    const val IGNORED  = "ignored"
    const val MISSED   = "missed"
    const val PENDING  = "pending"   // ← 추가: 추천만 발생, 아직 반응 없음
}

object UserFeedbackConst {
    const val POSITIVE = "positive"
    const val NEGATIVE = "negative"
    const val NONE     = "none"
}

// ──────────────────────────────────────────
// MobilityEvent 생성 헬퍼
// ──────────────────────────────────────────
object MobilityEventBuilder {

    private val dateFormat = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()
    )

    // ── 1단계: 추천 발생 시 INSERT ───────────
    // OnTheWayService.resultCallback 직전에 호출
    fun buildRecommendation(
        sessionId: String,
        callId: String,
        call: CallData,
        score: Double,
        reason: String,
        direction: String
    ): MobilityEvent {
        val now = dateFormat.format(java.util.Date())
        val summary = "${call.from} → ${call.to} ${String.format("%,d", call.amount)}원 | $reason"
        val tags = mutableListOf("recommendation", call.callType)
        if (call.isReservation) tags.add("reservation")
        if (call.amount >= 15000) tags.add("high_value")

        return MobilityEvent(
            eventId         = "evt_${System.currentTimeMillis()}",
            userId          = "on_the_way",
            sessionId       = sessionId,
            callId          = callId,
            timestamp       = now,
            callType        = "recommendation",
            driverDirection = direction,
            recommended     = true,
            recommendScore  = score.toInt(),
            recommendAt     = now,
            responseTimeMs  = 0L,
            shadowMode      = true,
            driverAction    = DriverActionConst.PENDING,
            driverActionAt  = "",
            sourceApp       = "on_the_way",
            routedAgent     = "mobility",
            summary         = summary,
            tags            = tags
        )
    }

    // ── 2단계: 기사 반응 시 UPDATE ───────────
    // MainActivity.updateShadowAction() 에서 호출
    fun fromShadowLog(
        entry: ShadowLogEntry,
        userId: String,
        sessionId: String,
        callId: String,
        responseTimeMs: Long = 0L,
        idleTime: Int? = null,
        deltaRevenue: Int? = null,
        userFeedback: String? = null,
        userIgnoreReason: String? = null
    ): MobilityEvent {

        val top1Id = entry.recommended.firstOrNull { it.rank == 1 }?.callId ?: ""
        val driverAction = when {
            entry.userSelected == top1Id -> DriverActionConst.ACCEPTED
            entry.missedCall == true     -> DriverActionConst.MISSED
            else                         -> DriverActionConst.IGNORED
        }

        val bestCall = entry.availableCalls.firstOrNull { it.id == entry.bestCallId }
        val summary = "${bestCall?.direction ?: ""} ${String.format("%,d", entry.bestCallPrice)}원 → $driverAction"
        val tags = mutableListOf("driver_action", driverAction)
        if (entry.top1Hit) tags.add("top1_hit")
        if (entry.missedCall == true) tags.add("missed")

        return MobilityEvent(
            eventId         = "evt_${System.currentTimeMillis()}",
            userId          = "on_the_way",
            sessionId       = sessionId,
            callId          = callId,
            timestamp       = entry.timestamp,
            callType        = "recommendation",
            driverDirection = entry.location,
            recommended     = true,
            recommendScore  = 0,
            recommendAt     = entry.timestamp,
            responseTimeMs  = responseTimeMs,
            shadowMode      = true,
            driverAction    = driverAction,
            driverActionAt  = entry.timestamp,
            sourceApp       = "on_the_way",
            routedAgent     = "mobility",
            summary         = summary,
            tags            = tags,
            lossWon         = entry.lossWon,
            mismatchReason  = entry.mismatchReason.takeIf { it != "none" },
            missedCall      = entry.missedCall,
            idleTime        = idleTime,
            deltaRevenue    = deltaRevenue,
            userIgnoreReason = if (driverAction == DriverActionConst.IGNORED) userIgnoreReason ?: IgnoreReason.UNKNOWN else null,
            userFeedback    = userFeedback ?: UserFeedbackConst.NONE
        )
    }
}
