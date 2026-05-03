package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

object FilterLog {

    private const val PREFS = "filter_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    // [P1] SP JSON I/O를 메인 스레드에서 분리
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FilterLog-IO").apply { isDaemon = true }
    }

    // v3.8: 허용 플랫폼 화이트리스트
    private val ALLOWED_PLATFORMS = setOf("coupang", "baemin", "kakaot")

    fun record(ctx: Context, call: DeliveryCall, result: CallFilter.FilterResult, baeminPoint: Double? = null, eventId: String? = null, sessionState: String? = null) {
        // GUARD 1: 플랫폼 화이트리스트
        if (call.platform !in ALLOWED_PLATFORMS) {
            Log.d("FilterLog", "BLOCKED: non-delivery platform ${call.platform}")
            return
        }

        // GUARD 2: 가격 유효성
        if (call.price <= 0) {
            Log.d("FilterLog", "BLOCKED: invalid price ${call.price}")
            return
        }

        // 메인 스레드에서 데이터 스냅샷 캡처
        val unitPrice = if (call.distance != null && call.distance > 0)
            (call.price / call.distance).toInt() else 0
        val entryStr = JSONObject().apply {
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
            if (call.bundleCount > 1) put("bundleCount", call.bundleCount)
            if (call.isMultiPickup) put("multiPickup", true)
            if (baeminPoint != null) put("point", baeminPoint)
            if (call.point != null) put("point", call.point)
            if (call.pickupDistanceKm != null) put("pickupKm", call.pickupDistanceKm)
            if (eventId != null) put("eventId", eventId)
            if (sessionState != null) put("state", sessionState)
        }.toString()

        val appCtx = ctx.applicationContext
        val platform = call.platform; val price = call.price; val verdict = result.verdict
        Log.d("FilterLog", "$platform ${price}원 → $verdict")

        // [P1] SP JSON I/O를 백그라운드로 분리
        ioExecutor.execute {
            try {
                val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = try {
                    JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
                } catch (e: Exception) {
                    JSONArray()
                }
                arr.put(JSONObject(entryStr))
                while (arr.length() > MAX_ENTRIES) {
                    arr.remove(0)
                }
                prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
            } catch (e: Exception) {
                Log.w("FilterLog", "record 실패: ${e.message}")
            }
        }
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

    /** v3.0: 수락 기록 추가 */
    fun recordAccepted(ctx: Context, price: Int, platform: String) {
        val entryStr = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("platform", platform)
            put("price", price)
            put("verdict", "ACCEPTED")
            put("reason", "수락됨")
        }.toString()

        val appCtx = ctx.applicationContext
        Log.d("FilterLog", "$platform ${price}원 → ACCEPTED")

        // [P1] SP JSON I/O를 백그라운드로 분리
        ioExecutor.execute {
            try {
                val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = try {
                    JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
                } catch (e: Exception) {
                    JSONArray()
                }
                arr.put(JSONObject(entryStr))
                while (arr.length() > MAX_ENTRIES) arr.remove(0)
                prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
            } catch (e: Exception) {
                Log.w("FilterLog", "recordAccepted 실패: ${e.message}")
            }
        }
    }

    /** 최근 콜의 storeName 업데이트 (사후 추출) */
    fun updateLastStoreName(ctx: Context, storeName: String) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
            if (arr.length() > 0) {
                val last = arr.getJSONObject(arr.length() - 1)
                if (last.optString("storeName", "").isBlank()) {
                    last.put("storeName", storeName)
                    prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
                }
            }
        } catch (e: Exception) {
            Log.w("FilterLog", "updateLastStoreName 실패: ${e.message}")
        }
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

    /** v3.8: 오염 데이터 일괄 정리 (1회 실행) */
    fun migrateV38Cleanup(ctx: Context) {
        val migrationPrefs = ctx.getSharedPreferences("ontheway", Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean("migration_v38_cleanup", false)) return

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

        val cleaned = JSONArray()
        var removed = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val platform = e.optString("platform", "")
            val price = e.optInt("price", 0)
            if (price <= 0 || platform !in ALLOWED_PLATFORMS) {
                removed++
                continue
            }
            cleaned.put(e)
        }

        prefs.edit().putString(KEY_ENTRIES, cleaned.toString()).apply()
        migrationPrefs.edit().putBoolean("migration_v38_cleanup", true).apply()
        Log.d("FilterLog", "v3.8 마이그레이션: ${removed}건 오염 데이터 삭제, ${cleaned.length()}건 유지")
    }
}
