package com.vita.ontheway

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * 앱 내 자체 로그 파일 시스템.
 * logcat 버퍼 소멸과 무관하게 운행 중 로그를 파일에 보존.
 *
 * 저장: /data/data/com.vita.ontheway/files/otw_log_YYYYMMDD.txt
 * 포맷: HH:mm:ss.SSS [TAG] message
 * 제한: 5MB, 초과 시 .1 suffix로 rotate
 */
object OtwFileLogger {

    private const val MAX_SIZE = 5L * 1024 * 1024  // 5MB
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OtwFileLogger-IO").apply { isDaemon = true }
    }

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    var lastWriteTime: Long = 0L
        private set

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    fun log(tag: String, message: String) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val line = "${sdf.format(Date(now))} [$tag] $message\n"
        lastWriteTime = now
        val filesDir = ctx.filesDir
        executor.execute {
            try {
                val file = getLogFile(filesDir)
                if (file.length() > MAX_SIZE) {
                    rotate(file)
                }
                file.appendText(line)
            } catch (_: Exception) {}
        }
    }

    private fun getLogFile(filesDir: File): File {
        val dateStr = dateFmt.format(Date())
        return File(filesDir, "otw_log_$dateStr.txt")
    }

    private fun rotate(file: File) {
        try {
            val parent = file.parent ?: return
            val name = file.name
            // 4단 rotate: .3 삭제 → .2→.3 → .1→.2 → current→.1
            val f3 = File(parent, "$name.3")
            val f2 = File(parent, "$name.2")
            val f1 = File(parent, "$name.1")
            if (f3.exists()) f3.delete()
            if (f2.exists()) f2.renameTo(f3)
            if (f1.exists()) f1.renameTo(f2)
            file.renameTo(f1)
        } catch (_: Exception) {}
    }

    /**
     * FIX-LOGSYNC: 치명 이벤트 동기 쓰기 + 디스크 flush.
     * 강제 종료 시 executor queue 손실 방지.
     * 호출 스레드에서 직접 쓰기 — critical path에서만 사용.
     */
    fun logSync(tag: String, message: String) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val line = "${sdf.format(Date(now))} [$tag] $message\n"
        lastWriteTime = now
        try {
            val file = getLogFile(ctx.filesDir)
            if (file.length() > MAX_SIZE) {
                rotate(file)
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray())
                fos.fd.sync()
            }
        } catch (_: Exception) {
            // fail-safe: 앱 크래시 방지
        }
    }

    /** 오늘 로그 파일 정보 (DevStats 표시용) */
    fun getTodayFileInfo(context: Context): FileInfo {
        val dateStr = dateFmt.format(Date())
        val file = File(context.filesDir, "otw_log_$dateStr.txt")
        return FileInfo(
            exists = file.exists(),
            sizeBytes = if (file.exists()) file.length() else 0,
            path = file.absolutePath,
            lastWrite = lastWriteTime
        )
    }

    data class FileInfo(
        val exists: Boolean,
        val sizeBytes: Long,
        val path: String,
        val lastWrite: Long
    )
}
