package com.vita.ontheway.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Ledger 원장 DB — append-only 이벤트 저장.
 *
 * 별도 DB 파일 (ledger.db) 사용:
 * - call_logs.db 운영 데이터와 완전 분리
 * - 데이터 자산 원장 목적
 * - retention 정책 결정 전까지 영구 보존
 */
class LedgerEventsDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "ledger.db"
        const val DB_VERSION = 3
        const val TABLE = "ledger_events"

        private var instance: LedgerEventsDb? = null
        fun get(ctx: Context): LedgerEventsDb {
            if (instance == null) instance = LedgerEventsDb(ctx.applicationContext)
            return instance!!
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ledger_event_id TEXT NOT NULL UNIQUE,
                call_session_id TEXT,
                event_id TEXT,
                order_id TEXT,
                platform TEXT NOT NULL,
                event_type TEXT NOT NULL,
                source_channel TEXT NOT NULL,
                occurred_at_wall INTEGER NOT NULL,
                occurred_at_monotonic INTEGER DEFAULT 0,
                identity_confidence REAL DEFAULT 0.0,
                confidence REAL DEFAULT 0.0,
                raw_payload_json TEXT,
                derived_payload_json TEXT,
                schema_version INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_le_session ON $TABLE(call_session_id)")
        db.execSQL("CREATE INDEX idx_le_event_id ON $TABLE(event_id)")
        db.execSQL("CREATE INDEX idx_le_type ON $TABLE(event_type)")
        db.execSQL("CREATE INDEX idx_le_wall ON $TABLE(occurred_at_wall)")
        db.execSQL("CREATE INDEX idx_le_session_type ON $TABLE(call_session_id, event_type)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_le_unique_accept ON $TABLE(call_session_id) WHERE event_type = 'DRIVER_ACCEPTED'")
        Log.d("LedgerEventsDb", "ledger_events 테이블 생성 완료")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // append-only: 스키마 변경 시 ALTER TABLE만 허용, DROP 금지
        Log.d("LedgerEventsDb", "onUpgrade: $old → $new")
        if (old < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_le_session_type ON $TABLE(call_session_id, event_type)")
        }
        if (old < 3) {
            // 기존 중복 ACCEPT 정리 후 UNIQUE 제약 추가
            try {
                db.execSQL("""
                    DELETE FROM $TABLE WHERE id NOT IN (
                        SELECT MIN(id) FROM $TABLE
                        WHERE event_type = 'DRIVER_ACCEPTED' AND call_session_id IS NOT NULL
                        GROUP BY call_session_id
                    ) AND event_type = 'DRIVER_ACCEPTED' AND call_session_id IS NOT NULL
                """)
            } catch (_: Exception) {}
            try {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_le_unique_accept ON $TABLE(call_session_id) WHERE event_type = 'DRIVER_ACCEPTED'")
            } catch (e: Exception) {
                Log.w("LedgerEventsDb", "idx_le_unique_accept 생성 실패 (graceful): ${e.message}")
            }
        }
    }
}
