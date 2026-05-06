package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * FIX-LOGSYNC: 치명 이벤트 SQLite 백업.
 * 강제 종료 후에도 보존. 24시간 자동 삭제.
 *
 * ADB 회수:
 * adb shell "run-as com.vita.ontheway sqlite3 databases/critical_events.db 'SELECT * FROM critical_event ORDER BY ts'"
 */
class CriticalEventDb(ctx: Context) : SQLiteOpenHelper(
    ctx.applicationContext, "critical_events.db", null, 1
) {
    companion object {
        const val TABLE = "critical_event"
        private const val RETENTION_MS = 24L * 60 * 60 * 1000 // 24시간

        @Volatile
        private var instance: CriticalEventDb? = null
        fun get(ctx: Context): CriticalEventDb {
            return instance ?: synchronized(this) {
                instance ?: CriticalEventDb(ctx).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                pkg TEXT,
                raw_summary TEXT,
                status TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_critical_ts ON $TABLE(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    /**
     * 치명 이벤트 기록.
     * 기록 전 24시간 이전 데이터 자동 삭제.
     */
    fun record(eventType: String, pkg: String? = null, rawSummary: String? = null, status: String? = null) {
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            // 자동 삭제
            db.delete(TABLE, "ts < ?", arrayOf((now - RETENTION_MS).toString()))
            // 삽입
            val cv = ContentValues().apply {
                put("ts", now)
                put("event_type", eventType)
                put("pkg", pkg)
                put("raw_summary", rawSummary)
                put("status", status)
            }
            db.insert(TABLE, null, cv)
        } catch (e: Exception) {
            Log.w("CriticalEventDb", "record 실패: ${e.message}")
        }
    }

    /** 최근 N건 조회 (테스트/디버그용) */
    fun getRecent(limit: Int = 50): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE, null, null, null, null, null, "ts DESC", limit.toString())
            cursor.use {
                while (it.moveToNext()) {
                    val row = mutableMapOf<String, String>()
                    for (i in 0 until it.columnCount) {
                        row[it.getColumnName(i)] = it.getString(i) ?: ""
                    }
                    results.add(row)
                }
            }
        } catch (e: Exception) {
            Log.w("CriticalEventDb", "getRecent 실패: ${e.message}")
        }
        return results
    }
}
