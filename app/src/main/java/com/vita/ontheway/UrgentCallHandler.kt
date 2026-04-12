package com.vita.ontheway

// ──────────────────────────────────────────
// 급송 선점 기능 v1.1
// 핵심 원칙: 급송은 추천이 아니라 "즉시 행동 신호"이다
// ──────────────────────────────────────────

// 필터 실패 이유 enum — 문자열 직접 사용 금지
enum class UrgentFilterFailReason(val value: String) {
    NO_URGENT_TEXT("no_urgent_text"),     // 급송 텍스트 미감지
    TOO_FAR("too_far"),                   // 픽업 거리 초과
    WRONG_DIRECTION("wrong_direction"),   // 방향 범위 초과
    NONE("none")                          // 필터 통과
}

// 급송 모드 ON/OFF 변경 이력 ("왜 안 잡혔지?" 분석용)
data class UrgentModeLog(
    val urgentModeEnabled: Boolean,
    val timestamp: String,
    val reason: String
)

// 급송 필터 결과
data class UrgentCheckResult(
    val isUrgent: Boolean,
    val passedFilter: Boolean,
    val reason: String,                          // UI 표시용 한 줄 이유
    val filterFailReason: UrgentFilterFailReason
)

object UrgentCallHandler {

    private const val MAX_PICKUP_DISTANCE_KM = 2.0
    private const val MAX_DIRECTION_DIFF_DEG = 60.0
    private const val URGENT_SCORE = 9999        // 필터 통과 시에만 부여

    private val URGENT_KEYWORDS = listOf("급송", "긴급", "URGENT")

    private var urgentModeEnabled: Boolean = true
    private val modeLog = mutableListOf<UrgentModeLog>()

    // ── 모드 ON/OFF — 반드시 이 함수로만 변경 ──
    fun setUrgentMode(enabled: Boolean, reason: String = "") {
        urgentModeEnabled = enabled
        modeLog.add(UrgentModeLog(
            urgentModeEnabled = enabled,
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            reason = reason.ifBlank { if (enabled) "수동 활성화" else "수동 비활성화" }
        ))
    }

    fun isUrgentModeEnabled(): Boolean = urgentModeEnabled
    fun getModeLog(): List<UrgentModeLog> = modeLog.toList()

    // ── 1. 급송 감지 (TextNode 탐지 후 호출) ──
    fun detectUrgent(nodeText: String): Boolean {
        if (!urgentModeEnabled) return false
        return URGENT_KEYWORDS.any { nodeText.contains(it, ignoreCase = true) }
    }

    // ── 2. 안전장치 검사 ──
    // 필터 통과 조건: 픽업 ≤ 2km AND 방향 ±60도
    fun evaluate(
        isUrgent: Boolean,
        pickupDistanceKm: Double,
        directionDiffDeg: Double
    ): UrgentCheckResult {
        if (!isUrgent) return UrgentCheckResult(false, false, "", UrgentFilterFailReason.NO_URGENT_TEXT)

        return when {
            pickupDistanceKm > MAX_PICKUP_DISTANCE_KM ->
                UrgentCheckResult(true, false, "", UrgentFilterFailReason.TOO_FAR)
            directionDiffDeg > MAX_DIRECTION_DIFF_DEG ->
                UrgentCheckResult(true, false, "", UrgentFilterFailReason.WRONG_DIRECTION)
            else ->
                UrgentCheckResult(true, true, "급송 · 거리 매우 가까움", UrgentFilterFailReason.NONE)
        }
    }

    // ── 3. score 강제 부여 ──
    // isUrgent AND urgentPassedFilter 동시 충족 시에만 9999
    // 일반 추천 로직과 섞지 않음 — 인터럽트 레이어로 유지
    fun applyUrgentScore(calls: List<CallInfo>): List<CallInfo> =
        calls.map { call ->
            if (call.isUrgent && call.urgentPassedFilter) call.copy(score = URGENT_SCORE)
            else call
        }

    // ── 4. TTS 메시지 ──
    fun getTtsMessage(): String = "급송입니다. 바로 잡기 좋습니다."
}

// ──────────────────────────────────────────
// CallInfo — 급송 필드 포함
// ──────────────────────────────────────────
data class CallInfo(
    val pickup: String,
    val dropoff: String,
    val price: Int,
    val distanceKm: Double,
    val etaMin: Int,
    val score: Int,
    val reason: String,
    val isUrgent: Boolean = false,
    val urgentPassedFilter: Boolean = false,
    val urgentReason: String? = null,
    val urgentFilterFail: UrgentFilterFailReason = UrgentFilterFailReason.NONE
)
