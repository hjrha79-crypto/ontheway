package com.vita.ontheway

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fix S: 전체 로그 ZIP 패키징.
 * databases/ + shared_prefs/ + files/ (최근 7일) → ZIP
 */
object LogZipExporter {

    private const val TAG = "LogZipExporter"
    private const val CUTOFF_DAYS = 7

    data class ExportResult(
        val zipFile: File,
        val fileCount: Int,
        val sizeBytes: Long,
        val errors: List<String>
    )

    /**
     * 전체 로그 ZIP 생성.
     * @param onProgress 진행률 콜백 (0.0~1.0)
     */
    fun export(ctx: Context, onProgress: ((Float) -> Unit)? = null): ExportResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logDir = File(ctx.getExternalFilesDir(null), "logs")
        logDir.mkdirs()
        val zipFile = File(logDir, "otw_logs_$timestamp.zip")

        val errors = mutableListOf<String>()
        var fileCount = 0

        // 수집 대상 파일 목록 구성
        val entries = mutableListOf<Pair<File, String>>()  // file, zipPath
        val dataDir = ctx.applicationInfo.dataDir

        // 1. databases/
        val dbDir = File(dataDir, "databases")
        if (dbDir.exists()) {
            dbDir.listFiles()?.filter { it.isFile && it.extension == "db" }?.forEach {
                entries.add(it to "databases/${it.name}")
            }
        }

        // 2. shared_prefs/
        val prefsDir = File(dataDir, "shared_prefs")
        if (prefsDir.exists()) {
            prefsDir.listFiles()?.filter { it.isFile && it.extension == "xml" }?.forEach {
                entries.add(it to "shared_prefs/${it.name}")
            }
        }

        // 3. files/ (최근 7일, 특정 패턴만)
        val cutoffMs = System.currentTimeMillis() - CUTOFF_DAYS * 24 * 60 * 60 * 1000L
        val filesDir = File(dataDir, "files")
        if (filesDir.exists()) {
            filesDir.listFiles()
                ?.filter { it.isFile && it.lastModified() >= cutoffMs }
                ?.filter {
                    it.name.startsWith("otw_log_") ||
                    it.name.startsWith("baemin_diagnostic_") ||
                    it.name.startsWith("coupang_diagnostic_") ||
                    it.name.endsWith(".jsonl")
                }
                ?.forEach { entries.add(it to "files/${it.name}") }
        }

        val totalFiles = entries.size
        if (totalFiles == 0) {
            // 빈 ZIP은 생성하지 않음
            return ExportResult(zipFile, 0, 0, listOf("수집 대상 파일 없음"))
        }

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for ((idx, pair) in entries.withIndex()) {
                    val (file, zipPath) = pair
                    try {
                        addToZip(zos, file, zipPath)
                        fileCount++
                    } catch (e: Exception) {
                        errors.add("${file.name}: ${e.message}")
                        Log.w(TAG, "ZIP 추가 실패: ${file.name}", e)
                    }
                    onProgress?.invoke((idx + 1).toFloat() / totalFiles)
                }
            }
        } catch (e: Exception) {
            errors.add("ZIP 생성 실패: ${e.message}")
            Log.e(TAG, "ZIP 생성 실패", e)
        }

        val sizeBytes = if (zipFile.exists()) zipFile.length() else 0
        Log.d(TAG, "ZIP 생성 완료: ${zipFile.name} (${fileCount}파일, ${sizeBytes / 1024}KB)")
        return ExportResult(zipFile, fileCount, sizeBytes, errors)
    }

    private fun addToZip(zos: ZipOutputStream, file: File, zipPath: String) {
        zos.putNextEntry(ZipEntry(zipPath))
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var len: Int
            while (fis.read(buf).also { len = it } > 0) {
                zos.write(buf, 0, len)
            }
        }
        zos.closeEntry()
    }

    /** 포맷된 파일 크기 문자열 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }
}
