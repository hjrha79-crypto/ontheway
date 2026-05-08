package com.vita.ontheway.ledger

import android.content.ContentValues
import android.content.Context
import android.util.Log

/**
 * Ledger 원장 Repository — append-only.
 *
 * 원칙:
 * - append() = INSERT만 허용
 * - update/delete 메서드 없음 (컴파일 타임 강제)
 * - 정정은 CORRECTION_ISSUED 이벤트로 append
 * - retention 정책 없음 (영구 보존)
 */
object LedgerEventsRepository {

    private const val TAG = "LedgerRepo"

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

    /** 전체 카운트 (테스트/디버그용) */
    fun count(ctx: Context): Int {
        return try {
            val db = LedgerEventsDb.get(ctx).readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM ${LedgerEventsDb.TABLE}", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

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
