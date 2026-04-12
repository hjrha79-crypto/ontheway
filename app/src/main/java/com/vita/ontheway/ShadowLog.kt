package com.vita.ontheway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────
// ShadowLog — 분석 엔진 (단순 로그 저장소 아님)
// ──────────────────────────────────────────

data class AvailableCall(
    val id: String,
    val price: Int,
    val direction: String,
    val distanceKm: Double = 0.0,
    val etaMin: Int = 0
)

data class RecommendedEntry(
    val rank: Int,
    val callId: String
)

data class ShadowLogEntry(
    val timestamp: String,
    val location: String,
    val availableCalls: List<AvailableCall>,
    val recommended: List<RecommendedEntry>,
    val userSelected: String,

    // KPI 핵심 필드
    val bestCallId: String,
    val lossWon: Int,                  // best_call_price - user_selected_price (₩)
    val mismatchReason: String,        // MismatchReasonConst 값

    // 보조 지표
    val top1Hit: Boolean,
    val top3Hit: Boolean,
    val bestCallPrice: Int,
    val userSelectedPrice: Int,
    val missedCall: Boolean = false
)

// 하루 집계
data class ShadowDailySummary(
    val date: String,
    val totalCalls: Int,
    val top1HitRate: Double,
    val top3HitRate: Double,
    val avgLossWon: Int,
    val maxLossWon: Int,
    val totalGainIfFollowed: Int,
    val mismatchBreakdown: Map<String, Int>,
    val autoAcceptReady: Boolean
) {
    // 제품 가치 전달 메시지
    fun toSummaryMessage(): String {
        val gain = if (totalGainIfFollowed > 0) "+${totalGainIfFollowed.toFormattedWon()}" else "0원"
        return "오늘 AI 따랐으면 $gain"
    }
}

fun Int.toFormattedWon(): String = String.format("%,d원", this)

object ShadowLog {

    private const val PREF_KEY = "shadow_log_entries"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private val dayFormat  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // 자동 수락 조건
    private const val AUTO_ACCEPT_TOP1  = 85.0
    private const val AUTO_ACCEPT_TOP3  = 95.0
    private const val AUTO_ACCEPT_LOSS  = 1000

    // ══ 로그 기록 ══════════════════════════
    fun record(
        context: Context,
        location: String,
        availableCalls: List<AvailableCall>,
        recommended: List<RecommendedEntry>,
        userSelectedId: String
    ) {
        val bestCall = availableCalls.maxByOrNull { it.price } ?: return
        val userCall = availableCalls.firstOrNull { it.id == userSelectedId } ?: return

        val lossWon     = maxOf(0, bestCall.price - userCall.price)
        val top1Id      = recommended.firstOrNull { it.rank == 1 }?.callId ?: ""
        val top3Ids     = recommended.map { it.callId }.toSet()
        val missedCall  = userSelectedId != bestCall.id

        val mismatchReason = if (!missedCall) MismatchReasonConst.UNKNOWN
        else analyzeMismatch(recommended, bestCall, userCall, availableCalls)

        val entry = ShadowLogEntry(
            timestamp        = dateFormat.format(Date()),
            location         = location,
            availableCalls   = availableCalls,
            recommended      = recommended,
            userSelected     = userSelectedId,
            bestCallId       = bestCall.id,
            lossWon          = lossWon,
            mismatchReason   = mismatchReason,
            top1Hit          = (top1Id == userSelectedId),
            top3Hit          = (userSelectedId in top3Ids),
            bestCallPrice    = bestCall.price,
            userSelectedPrice = userCall.price,
            missedCall       = missedCall
        )
        saveEntry(context, entry)
    }

    // ══ 하루 집계 ══════════════════════════
    fun getDailySummary(context: Context, date: String? = null): ShadowDailySummary? {
        val targetDate = date ?: dayFormat.format(Date())
        val entries = loadEntries(context).filter { it.timestamp.startsWith(targetDate) }
        if (entries.isEmpty()) return null

        val total     = entries.size
        val top1Hits  = entries.count { it.top1Hit }
        val top3Hits  = entries.count { it.top3Hit }
        val totalLoss = entries.sumOf { it.lossWon }
        val maxLoss   = entries.maxOf { it.lossWon }

        val breakdown = entries.groupBy { it.mismatchReason }.mapValues { it.value.size }
        val top1Rate  = (top1Hits.toDouble() / total) * 100
        val top3Rate  = (top3Hits.toDouble() / total) * 100
        val avgLoss   = if (total > 0) totalLoss / total else 0

        return ShadowDailySummary(
            date                 = targetDate,
            totalCalls           = total,
            top1HitRate          = top1Rate,
            top3HitRate          = top3Rate,
            avgLossWon           = avgLoss,
            maxLossWon           = maxLoss,
            totalGainIfFollowed  = totalLoss,
            mismatchBreakdown    = breakdown,
            autoAcceptReady      = top1Rate >= AUTO_ACCEPT_TOP1 &&
                                   top3Rate >= AUTO_ACCEPT_TOP3 &&
                                   avgLoss  <= AUTO_ACCEPT_LOSS
        )
    }

    // ══ 2주 누적 분석 ══════════════════════
    fun getTwoWeekSummary(context: Context): String {
        val entries = loadEntries(context)
        if (entries.size < 300) return "데이터 부족: ${entries.size}건 / 최소 300건 필요"

        val total    = entries.size
        val top1Rate = (entries.count { it.top1Hit }.toDouble() / total) * 100
        val top3Rate = (entries.count { it.top3Hit }.toDouble() / total) * 100
        val avgLoss  = entries.sumOf { it.lossWon } / total
        val totalGain = entries.sumOf { it.lossWon }

        val breakdown = entries
            .groupBy { it.mismatchReason }
            .mapValues { (it.value.size.toDouble() / total * 100).toInt() }
            .entries.sortedByDescending { it.value }
            .joinToString("\n") { "  ${it.key}: ${it.value}%" }

        val autoReady = top1Rate >= AUTO_ACCEPT_TOP1 &&
                        top3Rate >= AUTO_ACCEPT_TOP3 &&
                        avgLoss  <= AUTO_ACCEPT_LOSS

        return """
━━━━━━━━━━━━━━━━━━━━━━━━━━━
OnTheWay Shadow Mode 2주 결과
━━━━━━━━━━━━━━━━━━━━━━━━━━━

총 콜: ${total}건
AI 따랐으면: +${totalGain.toFormattedWon()}

[핵심 KPI]
Top1 적중률: ${"%.1f".format(top1Rate)}%  (기준: 85%)
Top3 포함률: ${"%.1f".format(top3Rate)}%  (기준: 95%)
평균 손실:   ${avgLoss.toFormattedWon()}  (기준: ≤1,000원)

[오류 원인 분포]
$breakdown

[자동 수락 전환]
${if (autoReady) "✅ 조건 충족 → Live Mode 전환 가능" else "❌ 조건 미충족 → 알고리즘 보완 필요"}
━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
    }

    // ══ mismatch 자동 분류 ═════════════════
    private fun analyzeMismatch(
        recommended: List<RecommendedEntry>,
        bestCall: AvailableCall,
        userCall: AvailableCall,
        availableCalls: List<AvailableCall>
    ): String {
        val top1Call = recommended.firstOrNull { it.rank == 1 }
            ?.let { rec -> availableCalls.firstOrNull { it.id == rec.callId } }

        return when {
            top1Call != null && top1Call.direction != bestCall.direction ->
                MismatchReasonConst.DIRECTION_ERROR
            top1Call != null && top1Call.price > userCall.price && userCall.price < bestCall.price ->
                MismatchReasonConst.PRICE_WEIGHT
            top1Call != null && top1Call.etaMin > userCall.etaMin * 1.5 ->
                MismatchReasonConst.TIME_PREDICTION
            recommended.any { it.callId == bestCall.id } ->
                MismatchReasonConst.USER_PREFERENCE
            else -> MismatchReasonConst.UNKNOWN
        }
    }

    // ══ 저장 / 로드 (org.json) ═════════════
    fun save(context: Context, entry: ShadowLogEntry) = saveEntry(context, entry)

    fun getAll(context: Context): List<ShadowLogEntry> = loadEntries(context)

    fun getStats(context: Context): String {
        val summary = getDailySummary(context) ?: return "오늘 데이터 없음"
        return "오늘 ${summary.totalCalls}콜 추천 | 수락률 ${summary.top1HitRate.toInt()}%"
    }

    fun getTodayStats(context: Context): ShadowDailySummary? = getDailySummary(context)

    private fun saveEntry(context: Context, entry: ShadowLogEntry) {
        val entries = loadEntries(context).toMutableList()
        entries.add(entry)
        if (entries.size > 500) entries.removeAt(0)  // 최대 500건 유지
        val prefs = context.getSharedPreferences("ontheway_shadow", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY, entriesToJson(entries)).apply()
    }

    private fun loadEntries(context: Context): List<ShadowLogEntry> {
        val prefs = context.getSharedPreferences("ontheway_shadow", Context.MODE_PRIVATE)
        val json  = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try { jsonToEntries(json) } catch (e: Exception) { emptyList() }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences("ontheway_shadow", Context.MODE_PRIVATE)
            .edit().remove(PREF_KEY).apply()
    }

    // ── JSON 직렬화 (org.json) ─────────────
    private fun entriesToJson(entries: List<ShadowLogEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject()
            obj.put("timestamp", e.timestamp)
            obj.put("location", e.location)
            obj.put("userSelected", e.userSelected)
            obj.put("bestCallId", e.bestCallId)
            obj.put("lossWon", e.lossWon)
            obj.put("mismatchReason", e.mismatchReason)
            obj.put("top1Hit", e.top1Hit)
            obj.put("top3Hit", e.top3Hit)
            obj.put("bestCallPrice", e.bestCallPrice)
            obj.put("userSelectedPrice", e.userSelectedPrice)
            obj.put("missedCall", e.missedCall)

            val calls = JSONArray()
            e.availableCalls.forEach { c ->
                val co = JSONObject()
                co.put("id", c.id); co.put("price", c.price)
                co.put("direction", c.direction)
                co.put("distanceKm", c.distanceKm); co.put("etaMin", c.etaMin)
                calls.put(co)
            }
            obj.put("availableCalls", calls)

            val recs = JSONArray()
            e.recommended.forEach { r ->
                val ro = JSONObject()
                ro.put("rank", r.rank); ro.put("callId", r.callId)
                recs.put(ro)
            }
            obj.put("recommended", recs)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun jsonToEntries(json: String): List<ShadowLogEntry> {
        val arr = JSONArray(json)
        val list = mutableListOf<ShadowLogEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val calls = mutableListOf<AvailableCall>()
            val callArr = obj.getJSONArray("availableCalls")
            for (j in 0 until callArr.length()) {
                val c = callArr.getJSONObject(j)
                calls.add(AvailableCall(c.getString("id"), c.getInt("price"), c.getString("direction"), c.getDouble("distanceKm"), c.getInt("etaMin")))
            }
            val recs = mutableListOf<RecommendedEntry>()
            val recArr = obj.getJSONArray("recommended")
            for (j in 0 until recArr.length()) {
                val r = recArr.getJSONObject(j)
                recs.add(RecommendedEntry(r.getInt("rank"), r.getString("callId")))
            }
            list.add(ShadowLogEntry(
                timestamp         = obj.getString("timestamp"),
                location          = obj.getString("location"),
                availableCalls    = calls,
                recommended       = recs,
                userSelected      = obj.getString("userSelected"),
                bestCallId        = obj.getString("bestCallId"),
                lossWon           = obj.getInt("lossWon"),
                mismatchReason    = obj.getString("mismatchReason"),
                top1Hit           = obj.getBoolean("top1Hit"),
                top3Hit           = obj.getBoolean("top3Hit"),
                bestCallPrice     = obj.getInt("bestCallPrice"),
                userSelectedPrice = obj.getInt("userSelectedPrice"),
                missedCall        = obj.optBoolean("missedCall", false)
            ))
        }
        return list
    }
}
