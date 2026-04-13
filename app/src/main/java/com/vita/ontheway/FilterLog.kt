package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object FilterLog {

    private const val PREFS = "filter_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    fun record(ctx: Context, call: DeliveryCall, result: CallFilter.FilterResult) {
        val unitPrice = if (call.distance != null && call.distance > 0)
            (call.price / call.distance).toInt() else 0
        val entry = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("platform", call.platform)
            put("rawText", call.rawText)
            put("price", call.price)
            put("distanceKm", call.distance ?: -1.0)
            put("unitPrice", unitPrice)
            put("multi", call.isMulti)
            put("verdict", result.verdict.name)
            put("reason", result.reason)
            put("parseSuccess", call.parseSuccess)
            put("storeName", call.storeName)
            put("destination", call.destination)
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

        arr.put(entry)

        // 200건 초과 시 오래된 것 제거
        while (arr.length() > MAX_ENTRIES) {
            arr.remove(0)
        }

        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        Log.d("FilterLog", "${call.platform} ${call.price}원 → ${result.verdict}")
    }

    fun getAll(ctx: Context): JSONArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun getTodayStats(ctx: Context): String {
        val entries = getAll(ctx)
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis

        var total = 0; var rejected = 0; var accepted = 0
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.getLong("ts") < todayStart) continue
            total++
            if (e.getString("verdict") == "REJECT") rejected++ else accepted++
        }
        return "오늘: ${total}건 (통과 ${accepted} / 필터 ${rejected})"
    }

    /** 오늘 통계 상세: total, reject, accept 카운트 + REJECT/ACCEPT 평균금액 */
    data class TodayDetail(
        val total: Int, val reject: Int, val accept: Int,
        val rejectAvgPrice: Int, val acceptAvgPrice: Int
    )

    fun getTodayDetail(ctx: Context): TodayDetail {
        val entries = getAll(ctx)
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis

        var total = 0; var reject = 0; var accept = 0
        var rejectSum = 0L; var acceptSum = 0L
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.getLong("ts") < todayStart) continue
            total++
            val price = e.optInt("price", 0)
            if (e.getString("verdict") == "REJECT") { reject++; rejectSum += price }
            else { accept++; acceptSum += price }
        }
        return TodayDetail(
            total, reject, accept,
            if (reject > 0) (rejectSum / reject).toInt() else 0,
            if (accept > 0) (acceptSum / accept).toInt() else 0
        )
    }

    /** CSV 내보내기 → 내부저장소에 저장, 파일 경로 반환 */
    fun exportCsv(ctx: Context): String? {
        val entries = getAll(ctx)
        if (entries.length() == 0) return null

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val fileSdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
        val fileName = "filter_log_${fileSdf.format(java.util.Date())}.csv"
        val file = java.io.File(ctx.filesDir, fileName)

        file.bufferedWriter().use { w ->
            w.write("timestamp,platform,price,distanceKm,unitPrice,isMulti,verdict,reason,parseSuccess,storeName,destination")
            w.newLine()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val ts = sdf.format(java.util.Date(e.optLong("ts", 0)))
                val line = listOf(
                    ts,
                    e.optString("platform", ""),
                    e.optInt("price", 0).toString(),
                    e.optDouble("distanceKm", -1.0).toString(),
                    e.optInt("unitPrice", 0).toString(),
                    e.optBoolean("multi", false).toString(),
                    e.optString("verdict", ""),
                    "\"${e.optString("reason", "").replace("\"", "\"\"")}\"",
                    e.optBoolean("parseSuccess", true).toString(),
                    "\"${e.optString("storeName", "").replace("\"", "\"\"")}\"",
                    "\"${e.optString("destination", "").replace("\"", "\"\"")}\"",
                ).joinToString(",")
                w.write(line)
                w.newLine()
            }
        }
        Log.d("FilterLog", "CSV 내보내기: ${file.absolutePath} (${entries.length()}건)")
        return file.absolutePath
    }

    /** 최근 N건 반환 (최신순) */
    fun getRecent(ctx: Context, count: Int = 20): List<JSONObject> {
        val entries = getAll(ctx)
        val result = mutableListOf<JSONObject>()
        val start = maxOf(0, entries.length() - count)
        for (i in entries.length() - 1 downTo start) {
            result.add(entries.getJSONObject(i))
        }
        return result
    }
}
