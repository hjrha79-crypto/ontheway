package com.vita.ontheway.ledger

import android.content.ContentValues
import android.content.Context
import android.util.Log

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  TIER 1: PERMANENT LEDGER — 운행 원장 데이터 자산                ║
 * ║                                                                  ║
 * ║  DO NOT ADD: cleanup(), delete(), truncate(), update() methods.  ║
 * ║  정정은 CORRECTION_ISSUED 이벤트로 append.                       ║
 * ║  retention 정책 없음 — 영구 보존.                                ║
 * ║  archive 정책은 별도 결정 (cleanup X, archive O).                ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * Retention Tier 분류:
 *
 * TIER 1 (영구 보존 — cleanup 금지):
 *   - ledger_events (이 Repository)
 *   - regret_candidate (P0-7 추가 예정)
 *
 * TIER 2 (운영 진단 — cleanup/cap 허용):
 *   - FilterLog: 200건 cap
 *   - DiagnosticLog: 1000건 cap
 *   - BaeminDiagnosticLogger: 5000건 cap
 *   - CoupangDiagnosticLogger: 5000건 cap
 *   - CallLogDb: 90일 cleanup
 *   - StateTransitionLog: 3일 cleanup
 *   - CriticalEventDb: 24시간 cleanup
 *
 * TIER 3 (임시 — 재시작 시 손실 OK):
 *   - JudgmentMatchLogger.pendingEvents (in-memory)
 *   - CallSessionRegistry.entries (in-memory)
 *   - dedup caches (in-memory)
 */
object LedgerEventsRepository {

    private const val TAG = "LedgerRepo"

    // 사이즈 모니터링 임계값
    private const val LOG_THRESHOLD_1K = 1_000
    private const val LOG_THRESHOLD_10K = 10_000
    private const val LOG_THRESHOLD_100K = 100_000
    private const val WARN_SIZE_BYTES = 100L * 1024 * 1024  // 100MB
    private var lastLoggedThreshold = 0

    /**
     * 이벤트 append (INSERT).
     * @return 삽입된 row id, 실패 시 -1
     */
    fun append(ctx: Context, event: LedgerEvent): Long {
        return try {
            val db = LedgerEventsDb.get(ctx).writableDatabase
            val cv = ContentValues().apply {
                put("ledger_event_id", event.ledgerEventId)
                put("call_session_id", event.callSessionId)
                put("event_id", event.eventId)
                put("order_id", event.orderId)
                put("platform", event.platform)
                put("event_type", event.eventType.name)
                put("source_channel", event.sourceChannel)
                put("occurred_at_wall", event.occurredAtWall)
                put("occurred_at_monotonic", event.occurredAtMonotonic)
                put("identity_confidence", event.identityConfidence)
                put("confidence", event.confidence)
                put("raw_payload_json", event.rawPayloadJson)
                put("derived_payload_json", event.derivedPayloadJson)
                put("schema_version", event.schemaVersion)
                put("created_at", event.createdAt)
            }
            val rowId = db.insertWithOnConflict(
                LedgerEventsDb.TABLE, null, cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            if (rowId == -1L) {
                Log.w(TAG, "append CONFLICT_IGNORE: ledger_event_id=${event.ledgerEventId}")
            } else {
                checkSizeThreshold(ctx)
            }
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "append 실패: ${e.message}")
            -1
        }
    }

    /** call_session_id로 조회 */
    fun getBySessionId(ctx: Context, sessionId: String): List<LedgerEvent> {
        return query(ctx, "call_session_id = ?", arrayOf(sessionId))
    }

    /** event_id로 조회 */
    fun getByEventId(ctx: Context, eventId: String): List<LedgerEvent> {
        return query(ctx, "event_id = ?", arrayOf(eventId))
    }

    /** event_type + since 시각 조회 */
    fun getByType(ctx: Context, type: LedgerEventType, sinceWall: Long = 0): List<LedgerEvent> {
        return query(ctx, "event_type = ? AND occurred_at_wall >= ?",
            arrayOf(type.name, sinceWall.toString()))
    }

    /** 전체 카운트 */
    fun count(ctx: Context): Int {
        return try {
            val db = LedgerEventsDb.get(ctx).readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM ${LedgerEventsDb.TABLE}", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    /** DB 파일 크기 (bytes) */
    fun dbSizeBytes(ctx: Context): Long {
        return try {
            val dbPath = ctx.getDatabasePath(LedgerEventsDb.DB_NAME)
            if (dbPath.exists()) dbPath.length() else 0
        } catch (_: Exception) { 0 }
    }

    /** 사이즈 요약 (디버그용) */
    fun getSizeReport(ctx: Context): String {
        val rows = count(ctx)
        val bytes = dbSizeBytes(ctx)
        val mb = bytes / (1024.0 * 1024.0)
        return "ledger: ${rows}건 / ${"%.1f".format(mb)}MB"
    }

    // ── 사이즈 모니터링 (cleanup X, 인지만) ──

    private fun checkSizeThreshold(ctx: Context) {
        try {
            val rows = count(ctx)
            val threshold = when {
                rows >= LOG_THRESHOLD_100K && lastLoggedThreshold < LOG_THRESHOLD_100K -> LOG_THRESHOLD_100K
                rows >= LOG_THRESHOLD_10K && lastLoggedThreshold < LOG_THRESHOLD_10K -> LOG_THRESHOLD_10K
                rows >= LOG_THRESHOLD_1K && lastLoggedThreshold < LOG_THRESHOLD_1K -> LOG_THRESHOLD_1K
                else -> return
            }
            lastLoggedThreshold = threshold
            val sizeBytes = dbSizeBytes(ctx)
            val mb = sizeBytes / (1024.0 * 1024.0)
            Log.i(TAG, "Ledger milestone: ${rows}건, ${"%.1f".format(mb)}MB")
            if (sizeBytes > WARN_SIZE_BYTES) {
                Log.w(TAG, "Ledger 100MB 초과! Archive 정책 검토 필요. (${rows}건, ${"%.1f".format(mb)}MB)")
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════
    // PERMANENT LEDGER: 아래 메서드는 절대 추가 금지
    // cleanup() / delete() / truncate() / update()
    // 정정 = CORRECTION_ISSUED 이벤트 append
    // ══════════════════════════════════════════════

    private fun query(ctx: Context, selection: String, args: Array<String>): List<LedgerEvent> {
        val results = mutableListOf<LedgerEvent>()
        try {
            val db = LedgerEventsDb.get(ctx).readableDatabase
            val cursor = db.query(
                LedgerEventsDb.TABLE, null, selection, args,
                null, null, "occurred_at_wall ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    results.add(cursorToEvent(it))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "query 실패: ${e.message}")
        }
        return results
    }

    private fun cursorToEvent(c: android.database.Cursor): LedgerEvent {
        return LedgerEvent(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            ledgerEventId = c.getString(c.getColumnIndexOrThrow("ledger_event_id")),
            callSessionId = c.getString(c.getColumnIndexOrThrow("call_session_id")),
            eventId = c.getString(c.getColumnIndexOrThrow("event_id")),
            orderId = c.getString(c.getColumnIndexOrThrow("order_id")),
            platform = c.getString(c.getColumnIndexOrThrow("platform")),
            eventType = try {
                LedgerEventType.valueOf(c.getString(c.getColumnIndexOrThrow("event_type")))
            } catch (_: Exception) { LedgerEventType.ORPHAN_CLASSIFIED },
            sourceChannel = c.getString(c.getColumnIndexOrThrow("source_channel")),
            occurredAtWall = c.getLong(c.getColumnIndexOrThrow("occurred_at_wall")),
            occurredAtMonotonic = c.getLong(c.getColumnIndexOrThrow("occurred_at_monotonic")),
            identityConfidence = c.getDouble(c.getColumnIndexOrThrow("identity_confidence")),
            confidence = c.getDouble(c.getColumnIndexOrThrow("confidence")),
            rawPayloadJson = c.getString(c.getColumnIndexOrThrow("raw_payload_json")),
            derivedPayloadJson = c.getString(c.getColumnIndexOrThrow("derived_payload_json")),
            schemaVersion = c.getInt(c.getColumnIndexOrThrow("schema_version")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }
}
