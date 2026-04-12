package com.vita.ontheway

import android.content.Context
import org.json.JSONObject

// ──────────────────────────────────────────
// WeightConfig — 추천 점수 가중치
// 핵심 원칙: 추천은 코드가 아니라 데이터가 결정한다
// ──────────────────────────────────────────

data class WeightConfig(
    val directionWeight: Double = 0.4,
    val distanceWeight: Double  = 0.2,
    val priceWeight: Double     = 0.3,
    val urgentWeight: Double    = 0.1,
    val updatedAt: Long         = System.currentTimeMillis(),
    val sampleCount: Int        = 0       // 학습에 사용된 데이터 건수
) {
    // 가중치 합 = 1.0 검증
    fun isValid(): Boolean {
        val sum = directionWeight + distanceWeight + priceWeight + urgentWeight
        return sum in 0.99..1.01
    }

    fun toDisplayText(): String = """
        가중치 현황 (n=${sampleCount})
        방향: ${"%.0f".format(directionWeight * 100)}%
        거리: ${"%.0f".format(distanceWeight * 100)}%
        금액: ${"%.0f".format(priceWeight * 100)}%
        급송: ${"%.0f".format(urgentWeight * 100)}%
    """.trimIndent()
}

// 개별 콜 점수 입력값
data class CallScoreInput(
    val directionScore: Double,  // 0.0 ~ 1.0
    val distanceScore: Double,
    val priceScore: Double,
    val urgentScore: Double
)

// ──────────────────────────────────────────
// WeightEngine — 점수 계산 + 자동 학습
// ──────────────────────────────────────────
object WeightEngine {

    private const val PREF_NAME    = "ontheway_weights"
    private const val KEY_WEIGHTS  = "weight_config"
    private const val MIN_SAMPLES  = 30   // 최소 30건 이상일 때만 가중치 업데이트

    // 기본 가중치
    val DEFAULT = WeightConfig()

    // ══ 점수 계산 ══════════════════════════
    fun calculateScore(input: CallScoreInput, weight: WeightConfig): Double {
        return (input.directionScore * weight.directionWeight) +
               (input.distanceScore  * weight.distanceWeight)  +
               (input.priceScore     * weight.priceWeight)     +
               (input.urgentScore    * weight.urgentWeight)
    }

    // ══ 자동 가중치 업데이트 ════════════════
    // "사람이 많이 선택한 패턴 = 점수 높인다"
    // 주의: MIN_SAMPLES 이상일 때만 실행
    fun updateWeights(
        context: Context,
        sessions: List<RecommendationSession>
    ): WeightConfig {
        val accepted = sessions.filter { it.userAction == UserAction.ACCEPT }

        // 데이터 부족 → 기본값 유지
        if (accepted.size < MIN_SAMPLES) {
            return load(context).also {
                android.util.Log.d("WeightEngine",
                    "데이터 부족 (${accepted.size}건 < ${MIN_SAMPLES}건) → 기존 가중치 유지")
            }
        }

        // 수락된 콜의 각 점수 평균 계산
        // SessionCall에서 score를 방향/거리/금액으로 분해
        val avgDirection = accepted.map { directionScore(it.selectedCall ?: it.recommendedCall) }.average()
        val avgDistance  = accepted.map { distanceScore(it.selectedCall  ?: it.recommendedCall) }.average()
        val avgPrice     = accepted.map { priceScore(it.selectedCall     ?: it.recommendedCall) }.average()
        val urgentCount  = accepted.count { it.isUrgentAccepted }.toDouble()
        val avgUrgent    = urgentCount / accepted.size  // 비율로 정규화

        val total = avgDirection + avgDistance + avgPrice + avgUrgent

        // 0 나누기 방지
        if (total <= 0) return load(context)

        val newWeight = WeightConfig(
            directionWeight = (avgDirection / total).coerceIn(0.1, 0.7),
            distanceWeight  = (avgDistance  / total).coerceIn(0.1, 0.5),
            priceWeight     = (avgPrice     / total).coerceIn(0.1, 0.6),
            urgentWeight    = (avgUrgent    / total).coerceIn(0.05, 0.3),
            updatedAt       = System.currentTimeMillis(),
            sampleCount     = accepted.size
        )

        // 정규화 — 합이 1.0이 되도록
        val normalized = normalize(newWeight)
        save(context, normalized)

        android.util.Log.d("WeightEngine",
            "가중치 업데이트 완료 (${accepted.size}건 기반)\n${normalized.toDisplayText()}")

        return normalized
    }

    // ══ 하루 1회 자동 실행 ═══════════════════
    // MainActivity.onCreate 또는 자정에 호출
    fun dailyUpdate(context: Context) {
        val sessions = SessionStore.loadAll(context)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())

        // 오늘 데이터만 사용
        val todaySessions = sessions.filter {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(it.timestamp)) == today
        }

        if (todaySessions.size >= MIN_SAMPLES) {
            updateWeights(context, todaySessions)
        } else {
            // 전체 데이터로 시도
            updateWeights(context, sessions)
        }
    }

    // ══ 저장 / 로드 ════════════════════════
    fun save(context: Context, config: WeightConfig) {
        val obj = JSONObject()
        obj.put("directionWeight", config.directionWeight)
        obj.put("distanceWeight",  config.distanceWeight)
        obj.put("priceWeight",     config.priceWeight)
        obj.put("urgentWeight",    config.urgentWeight)
        obj.put("updatedAt",       config.updatedAt)
        obj.put("sampleCount",     config.sampleCount)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEIGHTS, obj.toString()).apply()
    }

    fun load(context: Context): WeightConfig {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_WEIGHTS, null) ?: return DEFAULT
        return try {
            val o = JSONObject(json)
            WeightConfig(
                directionWeight = o.getDouble("directionWeight"),
                distanceWeight  = o.getDouble("distanceWeight"),
                priceWeight     = o.getDouble("priceWeight"),
                urgentWeight    = o.getDouble("urgentWeight"),
                updatedAt       = o.getLong("updatedAt"),
                sampleCount     = o.optInt("sampleCount", 0)
            )
        } catch (e: Exception) { DEFAULT }
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_WEIGHTS).apply()
    }

    // ══ 내부 헬퍼 ══════════════════════════

    // SessionCall → 각 항목 점수 (0.0 ~ 1.0)
    private fun directionScore(call: SessionCall): Double =
        if (call.direction in 270.0..360.0 || call.direction in 0.0..90.0) 0.8 else 0.3

    private fun distanceScore(call: SessionCall): Double = when {
        call.distance <= 3.0  -> 1.0
        call.distance <= 7.0  -> 0.7
        call.distance <= 12.0 -> 0.4
        else                   -> 0.2
    }

    private fun priceScore(call: SessionCall): Double = when {
        call.price >= 20000 -> 1.0
        call.price >= 15000 -> 0.8
        call.price >= 10000 -> 0.5
        else                -> 0.3
    }

    // 정규화 — 합이 정확히 1.0이 되도록
    private fun normalize(w: WeightConfig): WeightConfig {
        val sum = w.directionWeight + w.distanceWeight + w.priceWeight + w.urgentWeight
        if (sum <= 0) return DEFAULT
        return w.copy(
            directionWeight = w.directionWeight / sum,
            distanceWeight  = w.distanceWeight  / sum,
            priceWeight     = w.priceWeight     / sum,
            urgentWeight    = w.urgentWeight    / sum
        )
    }
}
