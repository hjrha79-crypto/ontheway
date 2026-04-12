package com.vita.ontheway

data class CallData(
    val from: String,
    val to: String,
    val amount: Int,
    val pickupKm: Double = 0.0,
    val deliveryKm: Double = 0.0,
    val callType: String = "퀵",
    val isReservation: Boolean = false,
    val reservationTime: String? = null,
    val deliveryDeadline: String? = null,
    val itemSize: String = "소형",
    val vehicleType: String? = null,
    val notice: String? = null
)

data class RecommendResult(
    val call: CallData,
    val dirScore: Double,
    val effScore: Double,
    val totalScore: Double,
    val reason: String,       // 화면 표시용: "강남 방향 · 픽업 1.2km"
    val reasonDetail: String, // 판정 레벨: GRAB / GOOD / OK / SKIP
    val voice: String = ""    // TTS 음성 출력용: "39,400원 강남 방향 잡으세요"
)

object CallRecommender {

    private enum class Verdict { GRAB, GOOD, OK, SKIP }

    fun recommend(calls: List<CallData>, direction: String): RecommendResult? {
        if (calls.isEmpty()) return null

        // 10,000원 미만 필터
        val viable = calls.filter { it.amount >= 10000 }
        val target = if (viable.isEmpty()) calls else viable

        // 여러 콜 중 Best 선택
        val best = target.maxByOrNull { rankScore(it, direction) } ?: return null

        val verdict = getVerdict(best, direction)
        val voice   = buildVoice(best, direction, verdict)
        val reason  = buildReason(best, direction)

        return RecommendResult(
            call         = best,
            dirScore     = 0.0,
            effScore     = 0.0,
            totalScore   = rankScore(best, direction),
            reason       = reason,
            reasonDetail = verdict.name,
            voice        = voice
        )
    }

    // Best 콜 선택용 랭킹 점수
    private fun rankScore(call: CallData, direction: String): Double {
        var score = call.amount.toDouble() / 1000.0
        if (call.pickupKm > 0) score -= call.pickupKm * 2.0
        if (direction.isNotEmpty() && isDirectionMatch(call, direction)) score += 20.0
        if (call.isReservation) score -= 10.0
        return score
    }

    // 조건 기반 3단계 판정
    private fun getVerdict(call: CallData, direction: String): Verdict {
        val hasDir   = direction.isNotEmpty()
        val dirMatch = hasDir && isDirectionMatch(call, direction)

        if (call.amount < 10000) return Verdict.SKIP
        if (call.pickupKm > 0 && call.pickupKm > 5.0) return Verdict.SKIP
        if (call.isReservation && parseHoursUntil(call.reservationTime) > 1.0) return Verdict.SKIP

        return when {
            hasDir && dirMatch &&
            call.amount >= 30000 &&
            (call.pickupKm == 0.0 || call.pickupKm <= 3.0) -> Verdict.GRAB

            call.amount >= 30000 &&
            (call.pickupKm == 0.0 || call.pickupKm <= 3.0) -> Verdict.GOOD

            call.amount >= 20000 -> Verdict.OK

            else -> Verdict.SKIP
        }
    }

    private fun isDirectionMatch(call: CallData, direction: String): Boolean {
        if (direction.isEmpty()) return false
        val dir = direction
            .replace(" 방향", "").replace("방향", "")
            .replace("역", "").trim()
        if (dir.isEmpty()) return false
        val dirShort = if (dir.length >= 2) dir.take(2) else dir
        return call.to.contains(dir) || call.to.contains(dirShort) ||
               call.from.contains(dir) || call.from.contains(dirShort)
    }

    // 음성 문구: 1초 이내, 결론만
    private fun buildVoice(call: CallData, direction: String, verdict: Verdict): String {
        val amtStr = "${String.format("%,d", call.amount)}원"
        val dirStr = if (verdict == Verdict.GRAB && direction.isNotEmpty()) {
            val d = direction.replace(" 방향","").replace("방향","").replace("역","").trim()
            if (d.isNotEmpty()) "$d 방향 " else ""
        } else ""

        return when (verdict) {
            Verdict.GRAB -> "$amtStr ${dirStr}잡으세요"
            Verdict.GOOD -> "$amtStr 좋습니다"
            Verdict.OK   -> "$amtStr 괜찮습니다"
            Verdict.SKIP -> "$amtStr 넘기세요"
        }.trim()
    }

    // 화면 표시용 이유
    private fun buildReason(call: CallData, direction: String): String {
        val parts = mutableListOf<String>()
        if (direction.isNotEmpty()) {
            val d = direction.replace(" 방향","").replace("방향","").replace("역","").trim()
            if (d.isNotEmpty()) parts.add("$d 방향")
        }
        if (call.pickupKm > 0) parts.add("픽업 ${String.format("%.1f", call.pickupKm)}km")
        val totalKm = (if (call.pickupKm > 0) call.pickupKm else 5.0) + 5.0
        parts.add("km당 ${String.format("%,d", (call.amount / totalKm).toInt())}원")
        return parts.joinToString(" · ")
    }

    // 예약 시간 파싱
    private fun parseHoursUntil(reservationTime: String?): Double {
        if (reservationTime == null) return 24.0
        val now = java.util.Calendar.getInstance()

        Regex("오늘\\s*(\\d{1,2}):(\\d{2})").find(reservationTime)?.let { m ->
            val t = now.clone() as java.util.Calendar
            t.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            t.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            if (t.before(now)) t.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return (t.timeInMillis - now.timeInMillis) / 3600000.0
        }

        Regex("내일\\s*(\\d{1,2}):(\\d{2})").find(reservationTime)?.let { m ->
            val t = now.clone() as java.util.Calendar
            t.add(java.util.Calendar.DAY_OF_MONTH, 1)
            t.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            t.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            return (t.timeInMillis - now.timeInMillis) / 3600000.0
        }

        Regex("(\\d{1,2}):(\\d{2})").find(reservationTime)?.let { m ->
            val t = now.clone() as java.util.Calendar
            t.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            t.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            if (t.before(now)) t.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return (t.timeInMillis - now.timeInMillis) / 3600000.0
        }

        return 24.0
    }
}
