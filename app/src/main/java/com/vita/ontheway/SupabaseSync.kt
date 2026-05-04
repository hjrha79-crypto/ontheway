package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Supabase REST API 자동 업로드.
 *
 * - 콜/피드백/판정매칭 데이터를 Supabase에 INSERT
 * - 실패 시 로컬에만 저장 (앱 동작 영향 없음)
 * - 앱 시작 시 미업로드 JSONL 파일 자동 sync
 */
object SupabaseSync {

    private const val TAG = "SupabaseSync"
    private const val PENDING_DIR = "supabase_pending"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Supabase-IO").apply { isDaemon = true }
    }

    private fun isConfigured(): Boolean =
        Config.SUPABASE_URL.isNotBlank() && Config.SUPABASE_ANON_KEY.isNotBlank()

    // ── 공개 API ──

    fun uploadCallLog(ctx: Context, json: JSONObject) {
        if (!isConfigured()) return
        json.put("source_app", "on_the_way")
        enqueue(ctx, "otw_call_logs", DataAnonymizer.anonymize(json))
    }

    fun uploadFeedback(ctx: Context, json: JSONObject) {
        if (!isConfigured()) return
        json.put("source_app", "on_the_way")
        enqueue(ctx, "otw_feedback_logs", DataAnonymizer.anonymize(json))
    }

    fun uploadJudgmentMatch(ctx: Context, json: JSONObject) {
        if (!isConfigured()) return
        json.put("source_app", "on_the_way")
        enqueue(ctx, "otw_judgment_match", DataAnonymizer.anonymize(json))
    }

    /** 앱 시작 시 미전송 데이터 재전송 */
    fun syncPending(ctx: Context) {
        if (!isConfigured()) return
        executor.execute {
            try {
                val dir = File(ctx.filesDir, PENDING_DIR)
                if (!dir.exists()) return@execute
                val files = dir.listFiles() ?: return@execute
                var synced = 0
                for (file in files.sortedBy { it.lastModified() }) {
                    try {
                        val content = file.readText().trim()
                        if (content.isBlank()) { file.delete(); continue }
                        val obj = JSONObject(content)
                        val table = obj.getString("_table")
                        val data = obj.getJSONObject("_data")
                        if (postToSupabase(table, data)) {
                            file.delete()
                            synced++
                        } else {
                            break // 네트워크 문제면 나머지도 실패할 가능성
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "pending 파일 처리 실패: ${file.name}: ${e.message}")
                        file.delete() // 파싱 불가 파일 제거
                    }
                }
                if (synced > 0) {
                    OtwFileLogger.log(TAG, "pending sync 완료: ${synced}건")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncPending 실패: ${e.message}")
            }
        }
    }

    // ── 내부 ──

    private fun enqueue(ctx: Context, table: String, data: JSONObject) {
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                if (postToSupabase(table, data)) {
                    return@execute
                }
                // 실패 → pending 파일로 저장
                savePending(appCtx, table, data)
            } catch (e: Exception) {
                Log.w(TAG, "enqueue 실패: ${e.message}")
                try { savePending(appCtx, table, data) } catch (_: Exception) {}
            }
        }
    }

    private fun postToSupabase(table: String, data: JSONObject): Boolean {
        val url = URL("${Config.SUPABASE_URL}/rest/v1/$table")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", Config.SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code in 200..299) {
                true
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                Log.w(TAG, "Supabase $code: $table → ${errorBody.take(200)}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Supabase 연결 실패: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun savePending(ctx: Context, table: String, data: JSONObject) {
        val dir = File(ctx.filesDir, PENDING_DIR)
        if (!dir.exists()) dir.mkdirs()
        // pending 파일 상한 (500개)
        val existing = dir.listFiles()?.size ?: 0
        if (existing > 500) return
        val wrapper = JSONObject().apply {
            put("_table", table)
            put("_data", data)
        }
        val file = File(dir, "${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.json")
        file.writeText(wrapper.toString())
    }
}
