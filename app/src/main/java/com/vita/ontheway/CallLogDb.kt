package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/** v3.5 SQLite 영구 저장 (Room 대안 - 추가 플러그인 불필요) */
class CallLogDb(ctx: Context) : SQLiteOpenHelper(ctx, "call_logs.db", null, 1) {

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
                deliveryTimeMin INTEGER DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_platform ON $TABLE(platform)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun insert(
        platform: String, price: Int, distance: Double?, unitPrice: Int,
        point: Double?, verdict: String, reason: String,
        bundleCount: Int = 1, isMultiPickup: Boolean = false,
        storeName: String = "", destination: String = "", pickupKm: Double? = null
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
        }
        writableDatabase.insert(TABLE, null, cv)
    }

    fun markAccepted(price: Int, platform: String) {
        val cv = ContentValues().apply { put("accepted", 1) }
        writableDatabase.update(TABLE, cv, "price=? AND platform=? AND accepted=0 ORDER BY timestamp DESC LIMIT 1",
            arrayOf(price.toString(), platform))
    }

    fun markCompleted(deliveryTimeMin: Int) {
        val cv = ContentValues().apply { put("completed", 1); put("deliveryTimeMin", deliveryTimeMin) }
        writableDatabase.update(TABLE, cv, "accepted=1 AND completed=0 ORDER BY timestamp DESC LIMIT 1", null)
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
