package com.vita.ontheway

// ──────────────────────────────────────────
// CallCardMeta
// 추천 카드에 표시할 3가지 메타 정보 생성
// 루트 엔진 / 인터럽트 계산 없음
// 목표: 0.5초 판단 + 1초 실행
// ──────────────────────────────────────────

data class CallCardMeta(
    val reason: String,          // 한 줄 이유 (필수)
    val riskWarning: String?,    // 리스크 경고 (있을 때만)
    val nextHint: String?        // 다음 콜 힌트 (가벼운 버전)
)

object CallCardMetaBuilder {

    // ══════════════════════════════════════
    // 메인 — 카드 메타 생성
    // ══════════════════════════════════════
    fun build(
        call: RecommendedCall,
        driverDirection: String,    // 기사 현재 방향
        cancelCount: Int = 0,       // 누적 취소 횟수
        pickupDistanceKm: Double = 0.0
    ): CallCardMeta {
        return CallCardMeta(
            reason      = buildReason(call, driverDirection, pickupDistanceKm),
            riskWarning = buildRisk(call, driverDirection, cancelCount),
            nextHint    = buildNextHint(call, driverDirection)
        )
    }

    // ══════════════════════════════════════
    // 1. 이유 압축 — 핵심 2~3개 키워드로
    // ══════════════════════════════════════
    private fun buildReason(
        call: RecommendedCall,
        driverDirection: String,
        pickupDistanceKm: Double
    ): String {
        val parts = mutableListOf<String>()

        if (call.isUrgent) parts.add("급송")

        // 방향: 실제 목적지명 표시
        val dirClean = driverDirection.replace(" 방향", "").replace("방향", "").trim()
        if (dirClean.isNotEmpty()) parts.add("${dirClean} 방향")

        // 픽업 거리
        if (pickupDistanceKm > 0) {
            parts.add("픽업 ${String.format("%.1f", pickupDistanceKm)}km")
        }

        // km당 단가
        val totalKm = pickupDistanceKm + 5.0
        if (call.price > 0 && totalKm > 0) {
            val wonPerKm = (call.price / totalKm).toInt()
            parts.add("km당 ${String.format("%,d", wonPerKm)}원")
        }

        return parts.take(4).joinToString(" · ")
            .ifBlank { call.reason.take(20) }
    }

    // ══════════════════════════════════════
    // 2. 리스크 경고 — 위험 요소 있을 때만
    // ══════════════════════════════════════
    private fun buildRisk(
        call: RecommendedCall,
        driverDirection: String,
        cancelCount: Int
    ): String? {
        val risks = mutableListOf<String>()

        // 취소 누적 경고
        if (cancelCount >= 2) risks.add("취소 ${cancelCount}회 누적 ⚠")

        // 방향 반대
        val oppositeKeywords = listOf("반대", "역방향")
        val isOpposite = oppositeKeywords.any { call.reason.contains(it) } ||
                         (driverDirection.isNotBlank() && !call.dropoff.contains(driverDirection.take(1)))
        if (isOpposite && call.score < 60) risks.add("방향 반대 ⚠")

        // 장거리 복귀 어려움
        if (call.distanceKm >= 15.0) risks.add("복귀 어려움 ⚠")

        // 낮은 효율
        if (call.price > 0 && call.distanceKm > 0) {
            val efficiency = call.price / call.distanceKm
            if (efficiency < 800) risks.add("효율 낮음 ⚠")
        }

        return risks.firstOrNull() // 가장 중요한 1개만
    }

    // ══════════════════════════════════════
    // 3. 다음 콜 힌트 — 루트 계산 없이 간단하게
    // ══════════════════════════════════════
    private fun buildNextHint(
        call: RecommendedCall,
        driverDirection: String
    ): String? {
        // 급송은 힌트 생략 — 즉시 행동이 우선
        if (call.isUrgent) return null

        // 점수 기반 단순 판단
        return when {
            call.score >= 85 -> "이후 콜 연결 가능성 높음"
            call.score >= 70 -> "이후 흐름 보통"
            call.distanceKm >= 15.0 -> "이후 흐름 끊길 가능성 있음"
            else -> null  // 힌트 없는 게 나을 때는 표시 안 함
        }
    }
}
