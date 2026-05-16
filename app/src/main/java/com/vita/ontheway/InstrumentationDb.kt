package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Core Sprint v2.1 계측 DB — parsed_event_candidates + dedup_decisions.
 *
 * 별도 DB 파일 (instrumentation.db):
 * - call_logs.db / ledger.db와 완전 분리
 * - retention: 7일 자동 정리
 * - 운영 데이터에 영향 없음
 */
class InstrumentationDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "instrumentation.db"
        const val DB_VERSION = 1
        const val TABLE_PARSED = "parsed_event_candidates"
        const val TABLE_DEDUP = "dedup_decisions"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7일

        private var instance: InstrumentationDb? = null
        fun get(ctx: Context): InstrumentationDb {
            if (instance == null) instance = InstrumentationDb(ctx.applicationContext)
            return instance!!
        }

        /** 테스트용 리셋 */
        fun resetForTest() { instance = null }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_PARSED (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                raw_event_ref TEXT,
                parser_version TEXT NOT NULL,
                platform TEXT NOT NULL,
                match_result TEXT NOT NULL,
                bundle_size INTEGER,
                bundle_type TEXT,
                price INTEGER,
                distance_km REAL,
                parse_status TEXT NOT NULL,
                failure_reason TEXT,
                raw_title_snippet TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE $TABLE_DEDUP (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                call_session_id TEXT,
                decision TEXT NOT NULL,
                dedup_reason TEXT,
                source_chain TEXT,
                matched_session_id TEXT,
                price INTEGER,
                platform TEXT,
                time_gap_ms INTEGER
            )
        """)
        Log.d("InstrumentationDb", "tables created: $TABLE_PARSED, $TABLE_DEDUP")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 only
    }

    fun insertParsedEvent(
        rawEventRef: String?,
        parserVersion: String,
        platform: String,
        matchResult: String,
        bundleSize: Int?,
        bundleType: String?,
        price: Int?,
        distanceKm: Double?,
        parseStatus: String,
        failureReason: String?,
        rawTitleSnippet: String?
    ) {
        try {
            val cv = ContentValues().apply {
                put("created_at", System.currentTimeMillis())
                put("raw_event_ref", rawEventRef)
                put("parser_version", parserVersion)
                put("platform", platform)
                put("match_result", matchResult)
                if (bundleSize != null) put("bundle_size", bundleSize)
                put("bundle_type", bundleType)
                if (price != null) put("price", price)
                if (distanceKm != null) put("distance_km", distanceKm)
                put("parse_status", parseStatus)
                put("failure_reason", failureReason)
                put("raw_title_snippet", rawTitleSnippet?.take(100))
            }
            writableDatabase.insert(TABLE_PARSED, null, cv)
        } catch (e: Exception) {
            Log.w("InstrumentationDb", "insertParsedEvent 실패: ${e.message}")
        }
    }

    fun insertDedupDecision(
        callSessionId: String?,
        decision: String,
        dedupReason: String?,
        sourceChain: String?,
        matchedSessionId: String?,
        price: Int,
        platform: String,
        timeGapMs: Long?
    ) {
        try {
            val cv = ContentValues().apply {
                put("created_at", System.currentTimeMillis())
                put("call_session_id", callSessionId)
                put("decision", decision)
                put("dedup_reason", dedupReason)
                put("source_chain", sourceChain)
                put("matched_session_id", matchedSessionId)
                put("price", price)
                put("platform", platform)
                if (timeGapMs != null) put("time_gap_ms", timeGapMs)
            }
            writableDatabase.insert(TABLE_DEDUP, null, cv)
        } catch (e: Exception) {
            Log.w("InstrumentationDb", "insertDedupDecision 실패: ${e.message}")
        }
    }

    /** 7일 이상 된 레코드 삭제 */
    fun cleanup() {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            writableDatabase.delete(TABLE_PARSED, "created_at < ?", arrayOf(cutoff.toString()))
            writableDatabase.delete(TABLE_DEDUP, "created_at < ?", arrayOf(cutoff.toString()))
        } catch (_: Exception) {}
    }

    /** parsed_event_candidates 건수 (테스트/진단용) */
    fun countParsedEvents(): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_PARSED", null)
            c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) { 0 }
    }

    /** dedup_decisions 건수 (테스트/진단용) */
    fun countDedupDecisions(): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_DEDUP", null)
            c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) { 0 }
    }
}
