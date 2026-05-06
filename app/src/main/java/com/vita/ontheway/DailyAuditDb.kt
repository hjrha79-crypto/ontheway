package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * FIX-AUDIT: 매출 자체 진단 SQLite.
 * 운행 종료 시 실제 매출 입력 → 거품 비율 자동 계산.
 *
 * ADB 회수:
 * adb shell "run-as com.vita.ontheway sqlite3 databases/daily_audit.db \
 *   'SELECT date, declared_total, screen_total, bubble_pct FROM daily_audit ORDER BY date DESC'"
 */
class DailyAuditDb(ctx: Context) : SQLiteOpenHelper(
    ctx.applicationContext, "daily_audit.db", null, 1
) {
    companion object {
        const val TABLE = "daily_audit"

        @Volatile
        private var instance: DailyAuditDb? = null
        fun get(ctx: Context): DailyAuditDb {
            return instance ?: synchronized(this) {
                instance ?: DailyAuditDb(ctx).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                date TEXT PRIMARY KEY,
                declared_coupang INTEGER DEFAULT 0,
                declared_baemin INTEGER DEFAULT 0,
                declared_total INTEGER DEFAULT 0,
                screen_total INTEGER DEFAULT 0,
                screen_calls INTEGER DEFAULT 0,
                accept_logs_count INTEGER DEFAULT 0,
                accept_logs_amount INTEGER DEFAULT 0,
                bubble_pct REAL DEFAULT 0,
                ts INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    /** 저장 (같은 날 = 덮어쓰기) */
    fun save(entry: AuditEntry) {
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("date", entry.date)
                put("declared_coupang", entry.declaredCoupang)
                put("declared_baemin", entry.declaredBaemin)
                put("declared_total", entry.declaredTotal)
                put("screen_total", entry.screenTotal)
                put("screen_calls", entry.screenCalls)
                put("accept_logs_count", entry.acceptLogsCount)
                put("accept_logs_amount", entry.acceptLogsAmount)
                put("bubble_pct", entry.bubblePct)
                put("ts", entry.ts)
            }
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.w("DailyAuditDb", "save 실패: ${e.message}")
        }
    }

    /** 최근 N일 조회 (날짜 역순) */
    fun getRecent(days: Int = 7): List<AuditEntry> {
        val results = mutableListOf<AuditEntry>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE, null, null, null, null, null, "date DESC", days.toString())
            cursor.use {
                while (it.moveToNext()) {
                    results.add(AuditEntry(
                        date = it.getString(it.getColumnIndexOrThrow("date")),
                        declaredCoupang = it.getInt(it.getColumnIndexOrThrow("declared_coupang")),
                        declaredBaemin = it.getInt(it.getColumnIndexOrThrow("declared_baemin")),
                        declaredTotal = it.getInt(it.getColumnIndexOrThrow("declared_total")),
                        screenTotal = it.getInt(it.getColumnIndexOrThrow("screen_total")),
                        screenCalls = it.getInt(it.getColumnIndexOrThrow("screen_calls")),
                        acceptLogsCount = it.getInt(it.getColumnIndexOrThrow("accept_logs_count")),
                        acceptLogsAmount = it.getInt(it.getColumnIndexOrThrow("accept_logs_amount")),
                        bubblePct = it.getFloat(it.getColumnIndexOrThrow("bubble_pct")),
                        ts = it.getLong(it.getColumnIndexOrThrow("ts"))
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("DailyAuditDb", "getRecent 실패: ${e.message}")
        }
        return results
    }

    data class AuditEntry(
        val date: String,
        val declaredCoupang: Int = 0,
        val declaredBaemin: Int = 0,
        val declaredTotal: Int = 0,
        val screenTotal: Int = 0,
        val screenCalls: Int = 0,
        val acceptLogsCount: Int = 0,
        val acceptLogsAmount: Int = 0,
        val bubblePct: Float = 0f,
        val ts: Long = System.currentTimeMillis()
    )
}

/**
 * 거품 계산 유틸.
 * 서비스 의존 없이 순수 계산 — unit test 가능.
 */
object BubbleCalculator {

    enum class KpiLevel { GREEN, YELLOW, RED }

    data class BubbleResult(
        val bubblePct: Float,       // (screen - actual) / actual * 100
        val kpi: KpiLevel,
        val label: String           // "5%" 등
    )

    /**
     * 거품 비율 계산.
     * @param screenTotal 화면 매출 (앱 추적 합계)
     * @param declaredTotal 실제 매출 (사용자 입력)
     * @return 거품 결과. declaredTotal <= 0이면 측정 불가.
     */
    fun calculate(screenTotal: Int, declaredTotal: Int): BubbleResult {
        if (declaredTotal <= 0) {
            return BubbleResult(0f, KpiLevel.RED, "측정 불가")
        }
        val pct = (screenTotal - declaredTotal).toFloat() / declaredTotal * 100f
        val kpi = when {
            pct <= 5f -> KpiLevel.GREEN
            pct <= 15f -> KpiLevel.YELLOW
            else -> KpiLevel.RED
        }
        val label = "${pct.toInt()}%"
        return BubbleResult(pct, kpi, label)
    }

    /**
     * accept_logs 신뢰도 계산.
     * @param acceptLogsAmount 앱 감지 수락 합계
     * @param declaredTotal 실제 매출
     * @return 신뢰도 % (0~200+)
     */
    fun acceptReliability(acceptLogsAmount: Int, declaredTotal: Int): Float {
        if (declaredTotal <= 0) return 0f
        return acceptLogsAmount.toFloat() / declaredTotal * 100f
    }
}
