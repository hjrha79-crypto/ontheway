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
        val entry = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("platform", call.platform)
            put("price", call.price)
            put("distance", call.distance ?: -1.0)
            put("multi", call.isMulti)
            put("verdict", result.verdict.name)
            put("reason", result.reason)
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
}
