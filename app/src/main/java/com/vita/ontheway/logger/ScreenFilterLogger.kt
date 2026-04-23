package com.vita.ontheway.logger

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * v3.20: ScreenFilter skip 진단 로거
 * - 배민 화면 감지 실패 시 accessibility tree 텍스트를 기록
 * - 파일: filesDir/screen_filter_skip.jsonl (최대 500건 롤링)
 * - 비동기 IO, 크래시 격리
 */
object ScreenFilterLogger {

    private const val FILE_NAME = "screen_filter_skip.jsonl"
    private const val MAX_LINES = 500

    private val executor = Executors.newSingleThreadExecutor()

    // 중복 억제: 같은 screenType+text 앞 50자 → 10초 내 재기록 방지
    private val recentLogs = mutableMapOf<String, Long>()
    private const val DEDUP_WINDOW_MS = 10_000L

    fun log(
        context: Context,
        pkg: String,
        screenType: String,
        confidence: String,
        joinedText: String,
        ts: Long = System.currentTimeMillis()
    ) {
        try {
            val dedupKey = "$screenType|${joinedText.take(50)}"
            synchronized(recentLogs) {
                recentLogs.entries.removeAll { ts - it.value > DEDUP_WINDOW_MS }
                if (recentLogs.containsKey(dedupKey)) return
                recentLogs[dedupKey] = ts
            }

            val truncated = if (joinedText.length > 200)
                joinedText.substring(0, 200) + "..."
            else joinedText

            val entry = JSONObject().apply {
                put("ts", ts)
                put("pkg", pkg)
                put("screenType", screenType)
                put("confidence", confidence)
                put("text", truncated)
            }.toString()

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

                    Log.d("ScreenFilterLog", "[$screenType/$confidence] ${truncated.take(40)}")
                } catch (e: Exception) {
                    Log.w("ScreenFilterLog", "로그 저장 실패: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("ScreenFilterLog", "로그 생성 실패: ${e.message}")
        }
    }
}
