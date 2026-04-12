package com.vita.ontheway

// ──────────────────────────────────────────
// ResultEvaluator
// GOOD / NORMAL / BAD 기준 고정
// 이 기준이 흔들리면 KPI 전체가 흔들린다
// ──────────────────────────────────────────
// 기준:
// GOOD   = earnedDiff >= 0         (예상 이상 수익)
// NORMAL = earnedDiff >= -2000원   (손실 2,000원 미만)
// BAD    = earnedDiff < -2000원    (손실 2,000원 이상)
// ──────────────────────────────────────────

object ResultEvaluator {

    private const val BAD_THRESHOLD = -2000  // 이 이하면 BAD

    // ── 핵심 판정 함수 ──────────────────────
    // 반드시 이 함수를 통해서만 결과 판정
    // SessionStore.updateEarned() 호출 시 사용
    fun evaluate(
        actualEarnedWon: Int,
        expectedWon: Int
    ): SessionResult {
        val diff = actualEarnedWon - expectedWon
        return when {
            diff >= 0           -> SessionResult.GOOD
            diff >= BAD_THRESHOLD -> SessionResult.NORMAL
            else                -> SessionResult.BAD
        }
    }

    // ── 기준 설명 텍스트 (Dev Mode 표시용) ──
    fun criteriaText(): String = """
        결과 판정 기준
        GOOD   : 예상 대비 0원 이상
        NORMAL : 예상 대비 -2,000원 이상
        BAD    : 예상 대비 -2,000원 미만
    """.trimIndent()
}

// ──────────────────────────────────────────
// 사용 예시 — SessionStore.updateEarned() 연동
// ──────────────────────────────────────────
// val result = ResultEvaluator.evaluate(
//     actualEarnedWon = 실제수익,
//     expectedWon     = session.expectedWon
// )
// SessionStore.updateEarned(context, sessionId, 실제수익, result)
