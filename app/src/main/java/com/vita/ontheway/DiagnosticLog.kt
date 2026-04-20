package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executors

/**
 * 개발자 진단용 Accessibility 이벤트 원시 로그 (v3.14).
 * FilterLog와 완전히 분리된 별도 DB.
 * UI에 표시하지 않음. CSV 내보내기만 지원.
 */
object DiagnosticLog {
    private const val DB_NAME = "diagnostic.db"
    private const val DB_VERSION = 1
    private const val TABLE = "diagnostic_log"
    private const val MAX_ENTRIES = 1000

    private var helper: DbHelper? = null
    // v3.16: 비동기 기록으로 메인 스레드 블로킹 방지
    private val executor = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        if (helper == null) {
            helper = DbHelper(context.applicationContext)
        }
    }

    /**
     * 비동기 기록 — 메인 스레드(Accessibility)를 블로킹하지 않음 (v3.16)
     */
    fun record(
        packageName: String,
        eventType: Int,
        className: String?,
        text: String?,
        source: String = "accessibility"
    ) {
        val h = helper ?: return
        val ts = System.currentTimeMillis()
        executor.execute {
            try {
                val db = h.writableDatabase ?: return@execute
                val values = ContentValues().apply {
                    put("timestamp", ts)
                    put("package_name", packageName)
                    put("event_type", eventType)
                    put("class_name", className ?: "")
                    put("text", text ?: "")
                    put("source", source)
                }
                db.insert(TABLE, null, values)
                trimIfNeeded(db)
            } catch (e: Exception) {
                Log.w("DiagnosticLog", "record 실패: ${e.message}")
            }
        }
    }

    private fun trimIfNeeded(db: SQLiteDatabase) {
        db.execSQL("""
            DELETE FROM $TABLE
            WHERE id NOT IN (
                SELECT id FROM $TABLE ORDER BY timestamp DESC LIMIT $MAX_ENTRIES
            )
        """)
    }

    fun count(): Int {
        val db = helper?.readableDatabase ?: return 0
        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            cursor.moveToFirst()
            val c = cursor.getInt(0)
            cursor.close()
            c
        } catch (e: Exception) { 0 }
    }

    fun clear() {
        try {
            helper?.writableDatabase?.delete(TABLE, null, null)
        } catch (e: Exception) {
            Log.w("DiagnosticLog", "clear 실패: ${e.message}")
        }
    }

    fun exportCsv(context: Context): String? {
        val db = helper?.readableDatabase ?: return null
        return try {
            val cursor = db.query(TABLE, null, null, null, null, null, "timestamp DESC")
            if (cursor.count == 0) { cursor.close(); return null }

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val fileSdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            val fileName = "diagnostic_${fileSdf.format(java.util.Date())}.csv"
            val file = java.io.File(context.filesDir, fileName)

            file.bufferedWriter().use { w ->
                w.write("timestamp,package,event_type,class,text,source")
                w.newLine()
                while (cursor.moveToNext()) {
                    val ts = sdf.format(java.util.Date(cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))))
                    val pkg = cursor.getString(cursor.getColumnIndexOrThrow("package_name"))
                    val evt = cursor.getInt(cursor.getColumnIndexOrThrow("event_type"))
                    val cls = cursor.getString(cursor.getColumnIndexOrThrow("class_name"))
                    val txt = cursor.getString(cursor.getColumnIndexOrThrow("text")).replace("\"", "\"\"")
                    val src = cursor.getString(cursor.getColumnIndexOrThrow("source"))
                    w.write("$ts,$pkg,$evt,$cls,\"$txt\",$src")
                    w.newLine()
                }
            }
            cursor.close()
            Log.d("DiagnosticLog", "CSV 내보내기: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.w("DiagnosticLog", "exportCsv 실패: ${e.message}")
            null
        }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    package_name TEXT NOT NULL,
                    event_type INTEGER NOT NULL,
                    class_name TEXT,
                    text TEXT,
                    source TEXT
                )
            """)
            db.execSQL("CREATE INDEX idx_diag_timestamp ON $TABLE(timestamp)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
