package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** v3.5 백업/복원: 모든 설정 + 즐겨찾기 + 통계 데이터를 JSON으로 */
object BackupManager {

    private const val TAG = "BackupManager"

    fun backup(ctx: Context): String? {
        try {
            val json = JSONObject()

            // 설정들
            val prefsToBackup = listOf("delivery_filter", "advanced_prefs", "tts_prefs",
                "store_manager", "peak_detector", "goal_manager", "earning")

            for (prefName in prefsToBackup) {
                val prefs = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val prefJson = JSONObject()
                for ((key, value) in prefs.all) {
                    when (value) {
                        is String -> prefJson.put(key, value)
                        is Int -> prefJson.put(key, value)
                        is Long -> prefJson.put(key, value)
                        is Float -> prefJson.put(key, value.toDouble())
                        is Boolean -> prefJson.put(key, value)
                    }
                }
                json.put(prefName, prefJson)
            }

            // FilterLog 데이터
            json.put("filter_log", FilterLog.getAll(ctx))

            json.put("backup_version", "3.5")
            json.put("backup_time", System.currentTimeMillis())

            val fileName = "ontheway_backup_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.json"
            val file = java.io.File(ctx.filesDir, fileName)
            file.writeText(json.toString(2))

            Log.d(TAG, "백업 완료: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "백업 실패: ${e.message}")
            return null
        }
    }

    fun restore(ctx: Context, jsonStr: String): Boolean {
        try {
            val json = JSONObject(jsonStr)

            val prefsToRestore = listOf("delivery_filter", "advanced_prefs", "tts_prefs",
                "store_manager", "peak_detector", "goal_manager", "earning")

            for (prefName in prefsToRestore) {
                if (!json.has(prefName)) continue
                val prefJson = json.getJSONObject(prefName)
                val editor = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
                for (key in prefJson.keys()) {
                    when (val value = prefJson.get(key)) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is Boolean -> editor.putBoolean(key, value)
                    }
                }
                editor.apply()
            }

            // FilterLog 복원
            if (json.has("filter_log")) {
                val logArr = json.getJSONArray("filter_log")
                ctx.getSharedPreferences("filter_log", Context.MODE_PRIVATE).edit()
                    .putString("entries", logArr.toString()).apply()
            }

            Log.d(TAG, "복원 완료")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "복원 실패: ${e.message}")
            return false
        }
    }
}
