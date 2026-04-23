package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * v3.20: 수락 감지 로그 수집 (판단 X, 기록만)
 * - MoneyMap 첫 데이터용 1주 분석 자료
 * - 파일: filesDir/accept_logs.jsonl (최대 500건 롤링)
 * - 비동기 IO, 크래시 격리
 */
object AcceptDetectionLogger {

    private const val FILE_NAME = "accept_logs.jsonl"
    private const val MAX_LINES = 500

    private val executor = Executors.newSingleThreadExecutor()

    // 중복 억제: "pkg|text" → timestamp
    private val recentLogs = mutableMapOf<String, Long>()
    private const val DEDUP_WINDOW_MS = 10_000L

    fun log(context: Context, source: String, pkg: String, text: String, sessionId: String?) {
        // 중복 억제
        val dedupKey = "$pkg|${text.take(50)}"
        val now = System.currentTimeMillis()
        synchronized(recentLogs) {
            recentLogs.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
            if (recentLogs.containsKey(dedupKey)) return
            recentLogs[dedupKey] = now
        }

        val entry = try {
            JSONObject().apply {
                put("source", source)
                put("pkg", pkg)
                put("text", text.take(200))
                put("ts", now)
                put("session_id", sessionId ?: JSONObject.NULL)
            }.toString()
        } catch (e: Exception) {
            Log.w("AcceptLog", "JSON 생성 실패: ${e.message}")
            return
        }

        val filesDir = context.filesDir
        executor.execute {
            try {
                val file = File(filesDir, FILE_NAME)
                file.appendText(entry + "\n")

                // 500건 롤링
                val lines = file.readLines()
                if (lines.size > MAX_LINES) {
                    file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
                }

                Log.d("AcceptLog", "[$source] $pkg: ${text.take(40)}")
            } catch (e: Exception) {
                Log.w("AcceptLog", "로그 저장 실패: ${e.message}")
            }
        }
    }
}
