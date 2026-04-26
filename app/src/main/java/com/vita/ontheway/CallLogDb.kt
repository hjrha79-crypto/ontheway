package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/** v3.5 SQLite 영구 저장 (Room 대안 - 추가 플러그인 불필요) */
class CallLogDb(ctx: Context) : SQLiteOpenHelper(ctx, "call_logs.db", null, 4) {

    companion object {
        const val TABLE = "call_logs"
        private var instance: CallLogDb? = null
        fun get(ctx: Context): CallLogDb {
            if (instance == null) instance = CallLogDb(ctx.applicationContext)
            return instance!!
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                platform TEXT,
                price INTEGER,
                distance REAL,
                unitPrice INTEGER,
                point REAL,
                verdict TEXT,
                reason TEXT,
                bundleCount INTEGER DEFAULT 1,
                isMultiPickup INTEGER DEFAULT 0,
                storeName TEXT,
                destination TEXT,
                pickupKm REAL,
                accepted INTEGER DEFAULT 0,
                completed INTEGER DEFAULT 0,
                deliveryTimeMin INTEGER DEFAULT 0,
                judge_version TEXT DEFAULT '${V2Event.JUDGE_VERSION}',
                tts_suppressed INTEGER DEFAULT 0,
                source_type TEXT DEFAULT 'unknown',
                parsing_method TEXT DEFAULT 'unknown',
                driver_action TEXT DEFAULT 'unknown'
            )
        """)
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_platform ON $TABLE(platform)")

        createLocationTraceTable(db)
    }

    private fun createLocationTraceTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS location_trace (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mobility_event_id TEXT,
                ts INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                speed REAL,
                accuracy REAL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_location_trace_ts ON location_trace(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_location_trace_event ON location_trace(mobility_event_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN judge_version TEXT DEFAULT '${V2Event.JUDGE_VERSION}'")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN tts_suppressed INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN source_type TEXT DEFAULT 'unknown'")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN parsing_method TEXT DEFAULT 'unknown'")
        }
        if (old < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN driver_action TEXT DEFAULT 'unknown'")
        }
        if (old < 4) {
            createLocationTraceTable(db)
            Log.d("CallLogDb", "v3->v4: location_trace table created")
        }
    }

    fun insert(
        platform: String, price: Int, distance: Double?, unitPrice: Int,
        point: Double?, verdict: String, reason: String,
        bundleCount: Int = 1, isMultiPickup: Boolean = false,
        storeName: String = "", destination: String = "", pickupKm: Double? = null,
        ttsSuppressed: Boolean = false,
        sourceType: String = V2Event.SOURCE_UNKNOWN,
        parsingMethod: String = V2Event.PARSING_UNKNOWN,
        driverAction: String = "unknown"
    ) {
        val cv = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("platform", platform)
            put("price", price)
            put("distance", distance ?: -1.0)
            put("unitPrice", unitPrice)
            put("point", point ?: -1.0)
            put("verdict", verdict)
            put("reason", reason)
            put("bundleCount", bundleCount)
            put("isMultiPickup", if (isMultiPickup) 1 else 0)
            put("storeName", storeName)
            put("destination", destination)
            put("pickupKm", pickupKm ?: -1.0)
            put("judge_version", V2Event.JUDGE_VERSION)
            put("tts_suppressed", if (ttsSuppressed) 1 else 0)
            put("source_type", sourceType)
            put("parsing_method", parsingMethod)
            put("driver_action", driverAction)
        }
        writableDatabase.insert(TABLE, null, cv)
    }

    /** 시뮬레이션 ACCEPT 콜 조회 (driver_action == "simulated_accept") */
    fun getSimulatedAcceptCalls(sinceMs: Long): List<SimCallRow> {
        val rows = mutableListOf<SimCallRow>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, price FROM $TABLE WHERE driver_action = ? AND timestamp >= ? ORDER BY timestamp ASC",
            arrayOf("simulated_accept", sinceMs.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                rows.add(SimCallRow(ts = it.getLong(0), price = it.getInt(1)))
            }
        }
        return rows
    }

    data class SimCallRow(val ts: Long, val price: Int)

    /** v1: 사용자 [수락]/[거절] 탭 시 driver_action 덮어쓰기 */
    fun updateDriverAction(price: Int, platform: String, action: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET driver_action=? WHERE id=(SELECT id FROM $TABLE WHERE price=? AND platform=? ORDER BY timestamp DESC LIMIT 1)",
                arrayOf(action, price.toString(), platform)
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateDriverAction 실패: ${e.message}")
        }
    }

    fun markAccepted(price: Int, platform: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET accepted=1 WHERE id=(SELECT id FROM $TABLE WHERE price=? AND platform=? AND accepted=0 ORDER BY timestamp DESC LIMIT 1)",
                arrayOf(price.toString(), platform)
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "markAccepted 실패: ${e.message}")
        }
    }

    fun markCompleted(deliveryTimeMin: Int) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET completed=1, deliveryTimeMin=? WHERE id=(SELECT id FROM $TABLE WHERE accepted=1 AND completed=0 ORDER BY timestamp DESC LIMIT 1)",
                arrayOf(deliveryTimeMin.toString())
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "markCompleted 실패: ${e.message}")
        }
    }

    fun insertLocationTrace(trace: LocationTrace): Long {
        val cv = ContentValues().apply {
            put("mobility_event_id", trace.mobilityEventId)
            put("ts", trace.ts)
            put("lat", trace.lat)
            put("lng", trace.lng)
            put("speed", trace.speed)
            put("accuracy", trace.accuracy)
        }
        return writableDatabase.insert("location_trace", null, cv)
    }

    fun getRecentTraces(limit: Int = 10): List<LocationTrace> {
        val traces = mutableListOf<LocationTrace>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, mobility_event_id, ts, lat, lng, speed, accuracy FROM location_trace ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                traces.add(LocationTrace(
                    id = it.getLong(0),
                    mobilityEventId = it.getString(1),
                    ts = it.getLong(2),
                    lat = it.getDouble(3),
                    lng = it.getDouble(4),
                    speed = it.getFloat(5),
                    accuracy = it.getFloat(6)
                ))
            }
        }
        return traces
    }

    /** 90일 이상 오래된 데이터 정리 */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val deleted = writableDatabase.delete(TABLE, "timestamp < ?", arrayOf(cutoff.toString()))
        if (deleted > 0) Log.d("CallLogDb", "오래된 데이터 정리: ${deleted}건")
    }

    /** 평균 배달 소요시간 (분) */
    fun getAvgDeliveryTime(ctx: Context): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT AVG(deliveryTimeMin) FROM $TABLE WHERE completed=1 AND deliveryTimeMin > 0", null
        )
        val avg = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return avg
    }

    /** 기간별 통계 */
    fun getCount(sinceMs: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE timestamp >= ?", arrayOf(sinceMs.toString())
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }
}
