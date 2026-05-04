package com.vita.ontheway

/**
 * 멀티 플랫폼 콜 비교 엔진 v0.
 * 단가 기준 비교만 (Phase 1).
 */
object ComparisonEngine {

    enum class Result { A_BETTER, B_BETTER, TIE }

    /** 두 콜 비교. 단가 우선, 거리 없으면 금액 비교. */
    fun compare(a: CallBuffer.BufferedCall, b: CallBuffer.BufferedCall): Result {
        val unitA = a.unitPrice()
        val unitB = b.unitPrice()

        // 둘 다 단가 있음
        if (unitA > 0 && unitB > 0) {
            return when {
                unitA > unitB -> Result.A_BETTER
                unitB > unitA -> Result.B_BETTER
                else -> Result.TIE
            }
        }

        // 하나만 단가 있음 → 단가 있는 쪽이 정보 우위
        if (unitA > 0) return Result.A_BETTER
        if (unitB > 0) return Result.B_BETTER

        // 둘 다 거리 없음 → 금액 비교
        return when {
            a.price > b.price -> Result.A_BETTER
            b.price > a.price -> Result.B_BETTER
            else -> Result.TIE
        }
    }

    /** 비교 결과를 TTS 메시지로 변환 (향후 사용) */
    fun toMessage(a: CallBuffer.BufferedCall, b: CallBuffer.BufferedCall, result: Result): String? {
        val aPlatform = if (a.platform == "coupang") "쿠팡" else "배민"
        val bPlatform = if (b.platform == "coupang") "쿠팡" else "배민"
        return when (result) {
            Result.A_BETTER -> "$aPlatform ${a.price}원이 $bPlatform ${b.price}원보다 유리"
            Result.B_BETTER -> "$bPlatform ${b.price}원이 $aPlatform ${a.price}원보다 유리"
            Result.TIE -> null
        }
    }
}
