package com.vita.ontheway.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Core Pipeline Phase 1 계측 DB — raw_events + parsed_events + tts_decisions.
 *
 * 별도 DB 파일 (core_instrumentation.db):
 * - 기존 instrumentation.db (parsed_event_candidates/dedup_decisions) 무변경
 * - 기존 ledger.db 무변경
 * - retention: 7일 자동 정리
 */
class CoreInstrumentationDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "core_instrumentation.db"
        const val DB_VERSION = 1
        const val TABLE_RAW = "raw_events"
        const val TABLE_PARSED = "parsed_events"
        const val TABLE_TTS = "tts_decisions"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        private var instance: CoreInstrumentationDb? = null
        fun get(ctx: Context): CoreInstrumentationDb {
            if (instance == null) instance = CoreInstrumentationDb(ctx.applicationContext)
            return instance!!
        }

        fun resetForTest() { instance = null }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_RAW (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_event_id TEXT NOT NULL,
                source_type TEXT NOT NULL,
                platform_guess TEXT NOT NULL,
                package_name TEXT NOT NULL,
                occurred_at_wall INTEGER NOT NULL,
                source_timestamp INTEGER,
                payload_hash INTEGER,
                payload_text TEXT,
                truncated INTEGER DEFAULT 0,
                schema_version INTEGER DEFAULT 1
            )
        """)
        db.execSQL("""
            CREATE TABLE $TABLE_PARSED (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parsed_event_id TEXT NOT NULL,
                raw_event_id TEXT NOT NULL,
                parser_name TEXT NOT NULL,
                parser_version TEXT,
                platform TEXT NOT NULL,
                event_type TEXT NOT NULL,
                parse_status TEXT NOT NULL,
                failure_reason TEXT,
                price INTEGER,
                distance_text TEXT,
                distance_value REAL,
                bundle_size INTEGER,
                bundle_type TEXT,
                store_hint TEXT,
                pickup_hint TEXT,
                dropoff_hint TEXT,
                confidence_score REAL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE $TABLE_TTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tts_decision_id TEXT NOT NULL,
                raw_event_id TEXT,
                parsed_event_id TEXT,
                call_session_id TEXT,
                platform TEXT NOT NULL,
                decision TEXT NOT NULL,
                reason TEXT NOT NULL,
                message_preview TEXT,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_raw_occurred ON $TABLE_RAW(occurred_at_wall)")
        db.execSQL("CREATE INDEX idx_parsed_created ON $TABLE_PARSED(created_at)")
        db.execSQL("CREATE INDEX idx_tts_created ON $TABLE_TTS(created_at)")
        Log.d("CoreInstrDb", "tables created: $TABLE_RAW, $TABLE_PARSED, $TABLE_TTS")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun insertRawEvent(e: RawEvent) {
        try {
            val cv = ContentValues().apply {
                put("raw_event_id", e.rawEventId)
                put("source_type", e.sourceType)
                put("platform_guess", e.platformGuess)
                put("package_name", e.packageName)
                put("occurred_at_wall", e.occurredAtWall)
                put("source_timestamp", e.sourceTimestamp)
                put("payload_hash", e.payloadHash)
                put("payload_text", e.payloadText)
                put("truncated", if (e.truncated) 1 else 0)
                put("schema_version", e.schemaVersion)
            }
            writableDatabase.insert(TABLE_RAW, null, cv)
        } catch (ex: Exception) {
            Log.w("CoreInstrDb", "insertRawEvent 실패: ${ex.message}")
        }
    }

    fun insertParsedEvent(e: ParsedEvent) {
        try {
            val cv = ContentValues().apply {
                put("parsed_event_id", e.parsedEventId)
                put("raw_event_id", e.rawEventId)
                put("parser_name", e.parserName)
                put("parser_version", e.parserVersion)
                put("platform", e.platform)
                put("event_type", e.eventType)
                put("parse_status", e.parseStatus)
                put("failure_reason", e.failureReason)
                put("price", e.price)
                put("distance_text", e.distanceText)
                if (e.distanceValue != null) put("distance_value", e.distanceValue)
                put("bundle_size", e.bundleSize)
                put("bundle_type", e.bundleType)
                put("store_hint", e.storeHint)
                put("pickup_hint", e.pickupHint)
                put("dropoff_hint", e.dropoffHint)
                put("confidence_score", e.confidenceScore)
                put("created_at", e.createdAt)
            }
            writableDatabase.insert(TABLE_PARSED, null, cv)
        } catch (ex: Exception) {
            Log.w("CoreInstrDb", "insertParsedEvent 실패: ${ex.message}")
        }
    }

    fun insertTtsDecision(d: TtsDecisionLog) {
        try {
            val cv = ContentValues().apply {
                put("tts_decision_id", d.ttsDecisionId)
                put("raw_event_id", d.rawEventId)
                put("parsed_event_id", d.parsedEventId)
                put("call_session_id", d.callSessionId)
                put("platform", d.platform)
                put("decision", d.decision)
                put("reason", d.reason)
                put("message_preview", d.messagePreview)
                put("created_at", d.createdAt)
            }
            writableDatabase.insert(TABLE_TTS, null, cv)
        } catch (ex: Exception) {
            Log.w("CoreInstrDb", "insertTtsDecision 실패: ${ex.message}")
        }
    }

    fun cleanup() {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            writableDatabase.delete(TABLE_RAW, "occurred_at_wall < ?", arrayOf(cutoff.toString()))
            writableDatabase.delete(TABLE_PARSED, "created_at < ?", arrayOf(cutoff.toString()))
            writableDatabase.delete(TABLE_TTS, "created_at < ?", arrayOf(cutoff.toString()))
        } catch (_: Exception) {}
    }

    fun countRawEvents(): Int = countTable(TABLE_RAW)
    fun countParsedEvents(): Int = countTable(TABLE_PARSED)
    fun countTtsDecisions(): Int = countTable(TABLE_TTS)

    private fun countTable(table: String): Int {
        return try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Exception) { 0 }
    }
}
