package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/** v3.5 SQLite 영구 저장 (Room 대안 - 추가 플러그인 불필요) */
class CallLogDb(ctx: Context) : SQLiteOpenHelper(ctx, "call_logs.db", null, 19) {

    private val appCtx: Context = ctx.applicationContext

    companion object {
        const val TABLE = "call_logs"
        private var instance: CallLogDb? = null
        fun get(ctx: Context): CallLogDb {
            if (instance == null) instance = CallLogDb(ctx.applicationContext)
            return instance!!
        }
    }

    // P0-2 (v70.8): ALTER TABLE graceful — 존재하는 컬럼만 insert
    private var cachedColumns: Set<String>? = null

    fun getColumns(): Set<String> {
        cachedColumns?.let { return it }
        val cols = mutableSetOf<String>()
        try {
            val cursor = readableDatabase.rawQuery("PRAGMA table_info($TABLE)", null)
            cursor.use {
                while (it.moveToNext()) {
                    cols.add(it.getString(it.getColumnIndexOrThrow("name")))
                }
            }
        } catch (e: Exception) {
            Log.w("CallLogDb", "PRAGMA table_info 실패: ${e.message}")
        }
        cachedColumns = cols
        return cols
    }

    fun safeContentValues(cv: ContentValues): ContentValues {
        val columns = getColumns()
        if (columns.isEmpty()) return cv  // PRAGMA 실패 시 원본 반환
        val safe = ContentValues()
        for (key in cv.keySet()) {
            if (key in columns) {
                // Fix 3 (v70.8.1): 타입 보존
                when (val v = cv.get(key)) {
                    is Long -> safe.put(key, v)
                    is Int -> safe.put(key, v)
                    is Double -> safe.put(key, v)
                    is Float -> safe.put(key, v)
                    is Boolean -> safe.put(key, if (v) 1 else 0)
                    is ByteArray -> safe.put(key, v)
                    null -> safe.putNull(key)
                    else -> safe.put(key, v.toString())
                }
            } else {
                Log.w("CallLogDb", "safeCV: column '$key' missing, skipping")
            }
        }
        return safe
    }

    /** 컬럼 캐시 무효화 (ALTER TABLE 후 호출) */
    fun invalidateColumnCache() { cachedColumns = null }

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
                distance_source TEXT DEFAULT '',
                pickup_distance_source TEXT DEFAULT '',
                accepted INTEGER DEFAULT 0,
                completed INTEGER DEFAULT 0,
                deliveryTimeMin INTEGER DEFAULT 0,
                judge_version TEXT DEFAULT '${V2Event.JUDGE_VERSION}',
                tts_suppressed INTEGER DEFAULT 0,
                source_type TEXT DEFAULT 'unknown',
                parsing_method TEXT DEFAULT 'unknown',
                driver_action TEXT DEFAULT 'no_action',
                shadow_verdict TEXT,
                session_id TEXT,
                ai_reason TEXT,
                event_id TEXT,
                order_id TEXT,
                call_session_id TEXT,
                identity_confidence REAL DEFAULT 0.0,
                distance_confidence REAL DEFAULT 0.0,
                action_source TEXT,
                accepted_at INTEGER,
                card_finalized_at INTEGER,
                delivery_completed_at INTEGER,
                next_call_wait_ms INTEGER,
                join_eligible INTEGER DEFAULT 1,
                quarantine_reason TEXT,
                store_name_first_source TEXT,
                store_name_last_source TEXT,
                store_name_change_count INTEGER DEFAULT 0,
                bundle_count_confidence REAL DEFAULT 1.0,
                bundle_count_source TEXT DEFAULT '',
                accept_state TEXT DEFAULT 'UNKNOWN',
                vehicle_type TEXT DEFAULT '',
                location_snapshot_lat REAL,
                location_snapshot_lng REAL,
                location_snapshot_age_ms INTEGER,
                location_snapshot_accuracy REAL,
                request_classification TEXT
            )
        """)
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_platform ON $TABLE(platform)")
        db.execSQL("CREATE INDEX idx_call_session ON $TABLE(call_session_id)")
        db.execSQL("CREATE INDEX idx_event_id ON $TABLE(event_id)")

        createLocationTraceTable(db)
        createReviewLogTable(db)
    }

    private fun createReviewLogTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS review_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                call_ts INTEGER NOT NULL,
                platform TEXT,
                price INTEGER,
                verdict TEXT,
                verdict_msg TEXT,
                user_action TEXT DEFAULT 'UNKNOWN',
                platform_distance_km REAL,
                gps_distance_km REAL,
                reviewed_at INTEGER,
                created_at INTEGER DEFAULT (strftime('%s','now') * 1000),
                session_id TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_review_call_ts ON review_log(call_ts)")
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
                accuracy REAL,
                session_id TEXT,
                speed_kmh REAL
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
        if (old < 5) {
            createReviewLogTable(db)
            Log.d("CallLogDb", "v4->v5: review_log table created")
        }
        if (old < 6) {
            try {
                db.execSQL("ALTER TABLE review_log ADD COLUMN gps_distance_km REAL")
            } catch (_: Exception) {}
            Log.d("CallLogDb", "v5->v6: review_log gps_distance_km column added")
        }
        if (old < 7) {
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN session_id TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN ai_reason TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE location_trace ADD COLUMN session_id TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE review_log ADD COLUMN session_id TEXT") } catch (_: Exception) {}
            Log.d("CallLogDb", "v6->v7: session_id + ai_reason columns added")
        }
        if (old < 8) {
            try { db.execSQL("ALTER TABLE location_trace ADD COLUMN speed_kmh REAL") } catch (_: Exception) {}
            Log.d("CallLogDb", "v7->v8: location_trace speed_kmh column added")
        }
        if (old < 9) {
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN distance_source TEXT DEFAULT ''") } catch (_: Exception) {}
            Log.d("CallLogDb", "v8->v9: distance_source column added")
        }
        if (old < 10) {
            val cols = listOf(
                "event_id TEXT",
                "order_id TEXT",
                "call_session_id TEXT",
                "identity_confidence REAL DEFAULT 0.0",
                "distance_confidence REAL DEFAULT 0.0",
                "action_source TEXT",
                "accepted_at INTEGER",
                "card_finalized_at INTEGER",
                "delivery_completed_at INTEGER",
                "next_call_wait_ms INTEGER",
                "join_eligible INTEGER DEFAULT 1"
            )
            for (col in cols) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN $col") } catch (_: Exception) {}
            }
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_call_session ON $TABLE(call_session_id)") } catch (_: Exception) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_id ON $TABLE(event_id)") } catch (_: Exception) {}
            Log.d("CallLogDb", "v9->v10: 11 lifecycle columns + indexes added")
        }
        if (old < 11) {
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN quarantine_reason TEXT") } catch (_: Exception) {}
            Log.d("CallLogDb", "v10->v11: quarantine_reason column added")
        }
        if (old < 12) {
            // Fix 1 (v70.9): storeName provenance 분리 필드
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN store_name_first_source TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN store_name_last_source TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN store_name_change_count INTEGER DEFAULT 0") } catch (_: Exception) {}
            Log.d("CallLogDb", "v11->v12: storeName provenance columns added")
        }
        if (old < 13) {
            // Fix A (v70.10): driver_action/shadow_verdict 분리
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN shadow_verdict TEXT") } catch (_: Exception) {}
            // 기존 simulated_* 값을 shadow_verdict로 이관
            try {
                db.execSQL("UPDATE $TABLE SET shadow_verdict = CASE " +
                    "WHEN driver_action = 'simulated_accept' THEN 'recommended_accept' " +
                    "WHEN driver_action = 'simulated_reject' THEN 'recommended_reject' " +
                    "ELSE driver_action END " +
                    "WHERE driver_action IS NOT NULL AND driver_action != 'unknown'")
            } catch (_: Exception) {}
            // driver_action 리셋 (이후 real_accepted만 기록)
            try {
                db.execSQL("UPDATE $TABLE SET driver_action = 'no_action'")
            } catch (_: Exception) {}
            // ledger DRIVER_ACCEPTED backfill
            try { backfillRealAccepted(db) } catch (e: Exception) {
                Log.w("CallLogDb", "v12->v13 backfill 실패: ${e.message}")
            }
            Log.d("CallLogDb", "v12->v13: shadow_verdict + driver_action 분리")
        }
        if (old < 14) {
            // Fix B (v70.10): distance_source 의미 분리
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN pickup_distance_source TEXT DEFAULT ''") } catch (_: Exception) {}
            // 기존 data 정리: pickup enrichment가 distance_source를 오염한 경우
            try {
                // distance=-1 (배달거리 없음) + distance_source='fallback' = pickup source 오염
                db.execSQL("UPDATE $TABLE SET pickup_distance_source = distance_source, distance_source = 'failed' " +
                    "WHERE distance < 0 AND distance_source IN ('fallback', 'estimated', 'gps_calculated')")
                // distance>0 + distance_source가 pickup source = NLS/screen 원본 복구 불가 → 'mixed' 표시
                db.execSQL("UPDATE $TABLE SET pickup_distance_source = distance_source " +
                    "WHERE distance > 0 AND distance_source IN ('fallback', 'estimated', 'gps_calculated')")
            } catch (_: Exception) {}
            Log.d("CallLogDb", "v13->v14: pickup_distance_source 분리")
        }
        if (old < 15) {
            // Fix D (v70.10): bundleCount confidence + source
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN bundle_count_confidence REAL DEFAULT 1.0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN bundle_count_source TEXT DEFAULT ''") } catch (_: Exception) {}
            Log.d("CallLogDb", "v14->v15: bundle_count_confidence/source columns added")
        }
        if (old < 16) {
            // Fix W (v70.11.6): accept lifecycle 3단계
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN accept_state TEXT DEFAULT 'UNKNOWN'") } catch (_: Exception) {}
            Log.d("CallLogDb", "v15->v16: accept_state column added")
        }
        if (old < 17) {
            // Fix BB (v70.12): vehicle_type per-call 기록
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN vehicle_type TEXT DEFAULT ''") } catch (_: Exception) {}
            Log.d("CallLogDb", "v16->v17: vehicle_type column added")
        }
        if (old < 18) {
            // Fix IT-1 (v70.12.IT-1): LocationSnapshot 기록
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN location_snapshot_lat REAL") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN location_snapshot_lng REAL") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN location_snapshot_age_ms INTEGER") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN location_snapshot_accuracy REAL") } catch (_: Exception) {}
            Log.d("CallLogDb", "v17->v18: location_snapshot columns added")
        }
        if (old < 19) {
            // Memory M1: 요청사항 분류
            try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN request_classification TEXT") } catch (_: Exception) {}
            Log.d("CallLogDb", "v18->v19: request_classification column added")
        }
        invalidateColumnCache()
    }

    /**
     * Ledger ACCEPT_CONFIRMED / DRIVER_ACCEPTED 기반으로 call_logs.driver_action = 'real_accepted' backfill.
     * Fix W+: ACCEPT_CONFIRMED 우선, 없으면 legacy DRIVER_ACCEPTED fallback.
     * 우선순위: call_session_id > event_id > order_id
     */
    private fun backfillRealAccepted(db: SQLiteDatabase) {
        try {
            val ledgerDb = com.vita.ontheway.ledger.LedgerEventsDb.get(appCtx).readableDatabase
            val cursor = ledgerDb.rawQuery(
                "SELECT call_session_id, event_id, order_id, occurred_at_wall FROM ${com.vita.ontheway.ledger.LedgerEventsDb.TABLE} " +
                "WHERE event_type IN ('ACCEPT_CONFIRMED', 'DRIVER_ACCEPTED') " +
                "ORDER BY CASE event_type WHEN 'ACCEPT_CONFIRMED' THEN 0 ELSE 1 END", null
            )
            var count = 0
            cursor.use {
                while (it.moveToNext()) {
                    val csId = it.getString(0)
                    val evId = it.getString(1)
                    val ordId = it.getString(2)
                    val ts = it.getLong(3)

                    val updated = when {
                        !csId.isNullOrBlank() -> {
                            db.execSQL("UPDATE $TABLE SET driver_action = 'real_accepted', accepted_at = ? WHERE call_session_id = ? AND driver_action != 'real_accepted'",
                                arrayOf(ts.toString(), csId))
                            true
                        }
                        !evId.isNullOrBlank() -> {
                            db.execSQL("UPDATE $TABLE SET driver_action = 'real_accepted', accepted_at = ? WHERE event_id = ? AND driver_action != 'real_accepted'",
                                arrayOf(ts.toString(), evId))
                            true
                        }
                        !ordId.isNullOrBlank() -> {
                            db.execSQL("UPDATE $TABLE SET driver_action = 'real_accepted', accepted_at = ? WHERE order_id = ? AND driver_action != 'real_accepted'",
                                arrayOf(ts.toString(), ordId))
                            true
                        }
                        else -> false
                    }
                    if (updated) count++
                }
            }
            Log.d("CallLogDb", "backfillRealAccepted: ${count}건 처리")
        } catch (e: Exception) {
            Log.w("CallLogDb", "backfillRealAccepted 실패: ${e.message}")
        }
    }

    /**
     * Ledger ACCEPT_CONFIRMED / DRIVER_ACCEPTED → call_logs.accepted_at 시간 기반 backfill.
     * Fix W+: ACCEPT_CONFIRMED 우선, DRIVER_ACCEPTED legacy fallback.
     * 조건: 동일 platform, timestamp -5분~+30초, verdict='ACCEPT', accepted_at IS NULL
     */
    fun backfillAcceptedAt(): BackfillResult {
        var matched = 0
        var skipped = 0
        try {
            val ledgerDb = com.vita.ontheway.ledger.LedgerEventsDb.get(appCtx).readableDatabase
            val cursor = ledgerDb.rawQuery(
                "SELECT platform, occurred_at_wall FROM ${com.vita.ontheway.ledger.LedgerEventsDb.TABLE} " +
                "WHERE event_type IN ('ACCEPT_CONFIRMED', 'DRIVER_ACCEPTED') " +
                "ORDER BY CASE event_type WHEN 'ACCEPT_CONFIRMED' THEN 0 ELSE 1 END, occurred_at_wall ASC", null
            )
            val db = writableDatabase
            cursor.use {
                while (it.moveToNext()) {
                    val platform = it.getString(0) ?: continue
                    val wallTs = it.getLong(1)

                    val windowStart = wallTs - 5 * 60_000L  // -5분
                    val windowEnd = wallTs + 30_000L         // +30초

                    val match = db.rawQuery(
                        "SELECT id, timestamp FROM $TABLE " +
                        "WHERE platform = ? AND verdict = 'ACCEPT' AND accepted_at IS NULL " +
                        "AND timestamp >= ? AND timestamp <= ? " +
                        "ORDER BY ABS(timestamp - ?) ASC LIMIT 1",
                        arrayOf(platform, windowStart.toString(), windowEnd.toString(), wallTs.toString())
                    )
                    match.use { m ->
                        if (m.moveToFirst()) {
                            val id = m.getLong(0)
                            val cv = ContentValues().apply {
                                put("accepted_at", wallTs)
                                put("action_source", "ledger_backfill")
                            }
                            db.update(TABLE, cv, "id = ?", arrayOf(id.toString()))
                            matched++
                        } else {
                            skipped++
                        }
                    }
                }
            }
            Log.i("CallLogDb", "backfillAcceptedAt: matched=$matched, skipped=$skipped")
        } catch (e: Exception) {
            Log.w("CallLogDb", "backfillAcceptedAt 실패: ${e.message}")
        }
        return BackfillResult(matched, skipped)
    }

    data class BackfillResult(val matched: Int, val skipped: Int)

    fun insert(
        platform: String, price: Int, distance: Double?, unitPrice: Int,
        point: Double?, verdict: String, reason: String,
        bundleCount: Int = 1, isMultiPickup: Boolean = false,
        storeName: String = "", destination: String = "", pickupKm: Double? = null,
        ttsSuppressed: Boolean = false,
        sourceType: String = V2Event.SOURCE_UNKNOWN,
        parsingMethod: String = V2Event.PARSING_UNKNOWN,
        driverAction: String = "no_action",
        sessionId: String? = null,
        distanceSource: String = "",
        eventId: String? = null,
        orderId: String? = null,
        callSessionId: String? = null,
        identityConfidence: Double = 0.0,
        distanceConfidence: Double = 0.0,
        shadowVerdict: String? = null,
        pickupDistanceSource: String = "",
        bundleCountConfidence: Double = 1.0,
        bundleCountSource: String = "",
        vehicleType: String = "",
        locationSnapshotLat: Double? = null,
        locationSnapshotLng: Double? = null,
        locationSnapshotAgeMs: Long? = null,
        locationSnapshotAccuracy: Float? = null,
        requestClassification: String? = null
    ) {
        // join_eligible 자동 계산 + quarantine 자동 분류 (v70.6: 0.5→0.7 통일)
        val joinEligible = if (identityConfidence < 0.7) 0 else 1
        val autoQuarantine = if (identityConfidence > 0 && identityConfidence < 0.7)
            com.vita.ontheway.ledger.QuarantineReason.IDENTITY_LOW_CONFIDENCE.name else null

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
            put("shadow_verdict", shadowVerdict)
            put("session_id", sessionId)
            put("distance_source", distanceSource)
            put("pickup_distance_source", pickupDistanceSource)
            put("event_id", eventId)
            put("order_id", orderId)
            put("call_session_id", callSessionId)
            put("identity_confidence", identityConfidence)
            put("distance_confidence", distanceConfidence)
            put("join_eligible", joinEligible)
            put("quarantine_reason", autoQuarantine)
            put("bundle_count_confidence", bundleCountConfidence)
            put("bundle_count_source", bundleCountSource)
            put("vehicle_type", vehicleType)
            // Fix IT-1: LocationSnapshot 기록
            if (locationSnapshotLat != null) put("location_snapshot_lat", locationSnapshotLat)
            if (locationSnapshotLng != null) put("location_snapshot_lng", locationSnapshotLng)
            if (locationSnapshotAgeMs != null) put("location_snapshot_age_ms", locationSnapshotAgeMs)
            if (locationSnapshotAccuracy != null) put("location_snapshot_accuracy", locationSnapshotAccuracy.toDouble())
            // Memory M1: 요청사항 분류
            if (requestClassification != null) put("request_classification", requestClassification)
            // Fix H-3: 초기 insert 시 storeName 출처 자동 설정
            if (storeName.isNotBlank()) {
                val initialSource = "${platform}_${parsingMethod}"
                put("store_name_first_source", initialSource)
                put("store_name_last_source", initialSource)
                put("store_name_change_count", 0)
            }
        }
        val safeCv = safeContentValues(cv)
        val rowId = writableDatabase.insert(TABLE, null, safeCv)

        // Supabase 업로드 (백그라운드, 실패해도 로컬 저장 완료)
        try {
            val ts = cv.getAsLong("timestamp") ?: System.currentTimeMillis()
            val json = JSONObject().apply {
                put("local_id", rowId)
                put("ts", ts)
                put("platform", platform)
                put("price", price)
                put("distance", distance ?: JSONObject.NULL)
                put("unit_price", unitPrice)
                put("point", point ?: JSONObject.NULL)
                put("verdict", verdict)
                put("reason", reason)
                put("bundle_count", bundleCount)
                put("is_multi_pickup", isMultiPickup)
                put("store_name", storeName)
                put("destination", destination)
                put("pickup_km", pickupKm ?: JSONObject.NULL)
                put("judge_version", V2Event.JUDGE_VERSION)
                put("tts_suppressed", ttsSuppressed)
                put("source_type", sourceType)
                put("parsing_method", parsingMethod)
                put("driver_action", driverAction)
                put("shadow_verdict", shadowVerdict ?: JSONObject.NULL)
                put("session_id", sessionId ?: JSONObject.NULL)
                put("distance_source", distanceSource)
                put("pickup_distance_source", pickupDistanceSource)
                put("event_id", eventId ?: JSONObject.NULL)
                put("order_id", orderId ?: JSONObject.NULL)
                put("call_session_id", callSessionId ?: JSONObject.NULL)
                put("identity_confidence", identityConfidence)
                put("distance_confidence", distanceConfidence)
                put("join_eligible", joinEligible == 1)
                put("vehicle_type", vehicleType)
            }
            SupabaseSync.uploadCallLog(appCtx, json)
        } catch (_: Exception) {}
    }


    /** 추천 ACCEPT 콜 조회 (shadow_verdict == "recommended_accept") */
    fun getRecommendedAcceptCalls(sinceMs: Long): List<SimCallRow> {
        val rows = mutableListOf<SimCallRow>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, price FROM $TABLE WHERE shadow_verdict = ? AND timestamp >= ? ORDER BY timestamp ASC",
            arrayOf("recommended_accept", sinceMs.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                rows.add(SimCallRow(ts = it.getLong(0), price = it.getInt(1)))
            }
        }
        return rows
    }

    /** 실제 수락 콜 조회 (driver_action == "real_accepted") */
    fun getRealAcceptedCalls(sinceMs: Long): List<SimCallRow> {
        val rows = mutableListOf<SimCallRow>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, price FROM $TABLE WHERE driver_action = ? AND timestamp >= ? ORDER BY timestamp ASC",
            arrayOf("real_accepted", sinceMs.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                rows.add(SimCallRow(ts = it.getLong(0), price = it.getInt(1)))
            }
        }
        return rows
    }

    data class SimCallRow(val ts: Long, val price: Int)

    /** v1: 사용자 [수락]/[거절] 탭 시 shadow_verdict 덮어쓰기 */
    fun updateShadowVerdict(price: Int, platform: String, verdict: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET shadow_verdict=? WHERE id=(SELECT id FROM $TABLE WHERE price=? AND platform=? ORDER BY timestamp DESC LIMIT 1)",
                arrayOf(verdict, price.toString(), platform)
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateShadowVerdict 실패: ${e.message}")
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

    /**
     * 카드 수집 종료 시점 기록 (기존 completed=1과 구분).
     * card_finalized_at = 판정 카드 닫힘 시점.
     */
    fun markCardFinalized(callSessionId: String, ts: Long = System.currentTimeMillis()) {
        try {
            val cv = ContentValues().apply {
                put("card_finalized_at", ts)
            }
            writableDatabase.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(callSessionId))
        } catch (e: Exception) {
            Log.w("CallLogDb", "markCardFinalized 실패: ${e.message}")
        }
        // Ledger: CARD_FINALIZED
        try {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                appCtx, callSessionId, null, null, "",
                com.vita.ontheway.ledger.LedgerEventType.CARD_FINALIZED, "internal"
            )
        } catch (_: Exception) {}
    }

    /**
     * 실제 배달 완료 시점 기록.
     * delivery_completed_at ≠ card_finalized_at.
     */
    fun markDeliveryCompleted(callSessionId: String, ts: Long = System.currentTimeMillis()) {
        try {
            val cv = ContentValues().apply {
                put("delivery_completed_at", ts)
                put("completed", 1)
            }
            writableDatabase.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(callSessionId))
        } catch (e: Exception) {
            Log.w("CallLogDb", "markDeliveryCompleted 실패: ${e.message}")
        }
        // Ledger: DELIVERY_COMPLETED
        try {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                appCtx, callSessionId, null, null, "",
                com.vita.ontheway.ledger.LedgerEventType.DELIVERY_COMPLETED, "internal"
            )
        } catch (_: Exception) {}
    }

    /**
     * 수락 시점 + source 기록 (AcceptCoordinator에서 호출).
     */
    fun markAcceptedWithSource(callSessionId: String, actionSource: String, ts: Long = System.currentTimeMillis()) {
        try {
            val cv = ContentValues().apply {
                put("accepted", 1)
                put("accepted_at", ts)
                put("action_source", actionSource)
                put("driver_action", "real_accepted")
            }
            writableDatabase.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(callSessionId))
        } catch (e: Exception) {
            Log.w("CallLogDb", "markAcceptedWithSource 실패: ${e.message}")
        }
    }

    /**
     * 다음 콜 대기 시간 기록 (연결성 분석용).
     */
    fun updateNextCallWaitMs(callSessionId: String, waitMs: Long) {
        try {
            val cv = ContentValues().apply {
                put("next_call_wait_ms", waitMs)
            }
            writableDatabase.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(callSessionId))
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateNextCallWaitMs 실패: ${e.message}")
        }
    }

    /**
     * Quarantine: 데이터 학습 자격 격리.
     * join_eligible=0 + quarantine_reason set + ledger QUARANTINED append.
     */
    /** Fix W+: accept_state 업데이트 (call_session_id 기준) */
    fun updateAcceptState(callSessionId: String, state: String, confirmedAt: Long? = null) {
        try {
            val cv = ContentValues().apply {
                put("accept_state", state)
                if (confirmedAt != null) put("accepted_at", confirmedAt)
            }
            writableDatabase.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(callSessionId))
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateAcceptState 실패: ${e.message}")
        }
    }

    /**
     * Fix W+++: accept_state legacy backfill (1회성).
     * call_logs의 accept_state='UNKNOWN' 행에 대해 ledger에서 상태 역산.
     */
    fun backfillAcceptStateFromLedger(): Int {
        var count = 0
        try {
            val db = writableDatabase
            val ledgerDb = com.vita.ontheway.ledger.LedgerEventsDb.get(appCtx).readableDatabase

            // accept_state가 UNKNOWN인 call_session_id 수집
            val unknowns = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT DISTINCT call_session_id FROM $TABLE WHERE accept_state IN ('UNKNOWN', '') AND call_session_id IS NOT NULL AND call_session_id != ''",
                null
            )
            cursor.use { while (it.moveToNext()) unknowns.add(it.getString(0)) }

            for (csId in unknowns) {
                // ledger에서 해당 session의 event_type 목록 조회
                val types = mutableSetOf<String>()
                val lCursor = ledgerDb.rawQuery(
                    "SELECT event_type FROM ${com.vita.ontheway.ledger.LedgerEventsDb.TABLE} WHERE call_session_id = ?",
                    arrayOf(csId)
                )
                lCursor.use { while (it.moveToNext()) types.add(it.getString(0)) }

                // 우선순위: FALSE > UNCONFIRMED > CONFIRMED > DRIVER_ACCEPTED(legacy)
                val newState = when {
                    "ACCEPT_REJECTED_FALSE" in types -> "REJECTED_FALSE"
                    "ACCEPT_UNCONFIRMED" in types -> "UNCONFIRMED"
                    "ACCEPT_CONFIRMED" in types -> "CONFIRMED"
                    "DRIVER_ACCEPTED" in types -> "CONFIRMED"  // legacy
                    else -> null  // UNKNOWN 유지
                }
                if (newState != null) {
                    val cv = ContentValues().apply {
                        put("accept_state", newState)
                        if (newState == "CONFIRMED") put("driver_action", "real_accepted")
                    }
                    db.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(csId))
                    count++
                }
            }
            Log.d("CallLogDb", "backfillAcceptStateFromLedger: ${count}건 업데이트 (총 ${unknowns.size}건 검사)")
        } catch (e: Exception) {
            Log.w("CallLogDb", "backfillAcceptStateFromLedger 실패: ${e.message}")
        }
        return count
    }

    fun markQuarantined(callSessionId: String, reason: com.vita.ontheway.ledger.QuarantineReason, context: String? = null) {
        try {
            val cv = ContentValues().apply {
                put("join_eligible", 0)
                put("quarantine_reason", reason.name)
            }
            writableDatabase.update(TABLE, safeContentValues(cv), "call_session_id = ?", arrayOf(callSessionId))
        } catch (e: Exception) {
            Log.w("CallLogDb", "markQuarantined 실패: ${e.message}")
        }
        // Ledger: QUARANTINED
        try {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                appCtx, callSessionId, null, null, "",
                com.vita.ontheway.ledger.LedgerEventType.QUARANTINED, "internal",
                org.json.JSONObject().apply {
                    put("reason", reason.name)
                    if (context != null) put("context", context)
                }.toString()
            )
        } catch (_: Exception) {}
    }

    /** regret 학습 가능한 콜만 (join_eligible=1 + quarantine 없음) */
    fun getCallsForRegret(sinceMs: Long): List<ReviewEntry> {
        val entries = mutableListOf<ReviewEntry>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT timestamp, platform, price, verdict, reason, storeName, destination, distance, bundleCount " +
                "FROM $TABLE WHERE timestamp >= ? AND join_eligible = 1 AND quarantine_reason IS NULL ORDER BY timestamp DESC",
                arrayOf(sinceMs.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val dist = it.getDouble(7)
                    entries.add(ReviewEntry(
                        id = 0, callTs = it.getLong(0), platform = it.getString(1) ?: "",
                        price = it.getInt(2), verdict = it.getString(3) ?: "",
                        verdictMsg = it.getString(4) ?: "", userAction = "UNKNOWN",
                        platformDistanceKm = null,
                        storeName = it.getString(5) ?: "", destination = it.getString(6) ?: "",
                        distance = if (dist < 0) null else dist, bundleCount = it.getInt(8)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("CallLogDb", "getCallsForRegret 실패: ${e.message}")
        }
        return entries
    }

    /** quarantine 된 콜 (디버깅용) */
    fun getQuarantinedCalls(sinceMs: Long): List<Pair<ReviewEntry, String>> {
        val entries = mutableListOf<Pair<ReviewEntry, String>>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT timestamp, platform, price, verdict, reason, storeName, quarantine_reason " +
                "FROM $TABLE WHERE timestamp >= ? AND quarantine_reason IS NOT NULL ORDER BY timestamp DESC",
                arrayOf(sinceMs.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val entry = ReviewEntry(
                        id = 0, callTs = it.getLong(0), platform = it.getString(1) ?: "",
                        price = it.getInt(2), verdict = it.getString(3) ?: "",
                        verdictMsg = it.getString(4) ?: "", userAction = "UNKNOWN",
                        platformDistanceKm = null, storeName = it.getString(5) ?: ""
                    )
                    entries.add(entry to (it.getString(6) ?: "UNKNOWN"))
                }
            }
        } catch (e: Exception) {
            Log.w("CallLogDb", "getQuarantinedCalls 실패: ${e.message}")
        }
        return entries
    }

    /** quarantine 사유별 카운트 (디버깅용) */
    fun getQuarantineSummary(sinceMs: Long): Map<String, Int> {
        val summary = mutableMapOf<String, Int>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT quarantine_reason, COUNT(*) FROM $TABLE WHERE timestamp >= ? AND quarantine_reason IS NOT NULL GROUP BY quarantine_reason",
                arrayOf(sinceMs.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    summary[it.getString(0) ?: "UNKNOWN"] = it.getInt(1)
                }
            }
        } catch (e: Exception) {
            Log.w("CallLogDb", "getQuarantineSummary 실패: ${e.message}")
        }
        return summary
    }

    fun insertLocationTrace(trace: LocationTrace): Long {
        val speedKmh = if (trace.speed > 0) trace.speed * 3.6f else 0.0f
        val cv = ContentValues().apply {
            put("mobility_event_id", trace.mobilityEventId)
            put("ts", trace.ts)
            put("lat", trace.lat)
            put("lng", trace.lng)
            put("speed", trace.speed)
            put("accuracy", trace.accuracy)
            put("speed_kmh", speedKmh)
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

    fun getTraceCount(): Int {
        return try {
            val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM location_trace", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    /**
     * @deprecated Use [updateStoreNameBySessionId] instead.
     * 가격 기반 매칭은 오매칭 위험.
     */
    fun updateStoreNameIfEmpty(platform: String, price: Int, storeName: String) {
        if (storeName.isBlank()) return
        Log.w("CallLogDb", "DEPRECATED updateStoreNameIfEmpty: use updateStoreNameBySessionId")
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET storeName=? WHERE id=(SELECT id FROM $TABLE WHERE platform=? AND price=? AND (storeName IS NULL OR storeName='') ORDER BY timestamp DESC LIMIT 1)",
                arrayOf(storeName, platform, price.toString())
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateStoreNameIfEmpty 실패: ${e.message}")
        }
    }

    /**
     * FIX-ENRICHMENT-IDENTITY + storeName provenance (v70.9):
     * callSessionId 기반 가게명 보강 + 출처 추적.
     */
    fun updateStoreNameBySessionId(callSessionId: String, storeName: String, source: String = "enrichment") {
        if (storeName.isBlank() || callSessionId.isBlank()) return
        try {
            val db = writableDatabase
            // storeName 설정 (빈 값만)
            db.execSQL(
                "UPDATE $TABLE SET storeName=? WHERE call_session_id=? AND (storeName IS NULL OR storeName='')",
                arrayOf(storeName, callSessionId)
            )
            // provenance 추적
            val cv = ContentValues().apply {
                put("store_name_last_source", source)
            }
            val safeCv = safeContentValues(cv)
            db.update(TABLE, safeCv, "call_session_id = ?", arrayOf(callSessionId))

            // first_source 설정 (NULL일 때만)
            db.execSQL(
                "UPDATE $TABLE SET store_name_first_source=? WHERE call_session_id=? AND store_name_first_source IS NULL",
                arrayOf(source, callSessionId)
            )
            // change_count 증가
            db.execSQL(
                "UPDATE $TABLE SET store_name_change_count = COALESCE(store_name_change_count, 0) + 1 WHERE call_session_id=?",
                arrayOf(callSessionId)
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateStoreNameBySessionId 실패: ${e.message}")
        }
    }

    /**
     * Fix IT-1: 비동기 픽업 거리 업데이트 (callSessionId 기준).
     * 조건: pickupKm 없음 OR low-trust source (fallback overwrite 허용).
     * @param source "api_keyword" | "api_address" | "cache_mem" | "cache_prefs" | "fallback_location_table"
     * @return 업데이트된 행 수
     */
    fun updatePickupDistanceBySessionId(callSessionId: String, km: Double, source: String, confidence: Double = -1.0): Int {
        if (callSessionId.isBlank()) return 0
        return try {
            val cv = ContentValues().apply {
                put("pickupKm", km)
                put("pickup_distance_source", source)
                if (confidence >= 0) put("distance_confidence", confidence)
            }
            val safeCv = safeContentValues(cv)
            writableDatabase.update(
                TABLE, safeCv,
                "call_session_id = ? AND (pickupKm IS NULL OR pickupKm < 0 OR pickup_distance_source IN ('fallback', 'fallback_location_table', 'estimated', ''))",
                arrayOf(callSessionId)
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "updatePickupDistanceBySessionId 실패: ${e.message}")
            0
        }
    }

    /** AI 보조 사유 업데이트 */
    fun updateAiReason(price: Int, platform: String, aiReason: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET ai_reason=? WHERE id=(SELECT id FROM $TABLE WHERE price=? AND platform=? ORDER BY timestamp DESC LIMIT 1)",
                arrayOf(aiReason, price.toString(), platform)
            )
        } catch (e: Exception) {
            Log.w("CallLogDb", "updateAiReason 실패: ${e.message}")
        }
    }

    /** 90일 이상 오래된 데이터 정리 (TIER 2 운영 진단용).
     *  Ledger (ledger.db) 영향 X — 별도 DB, 영구 보존. */
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

    // ═══ review_log CRUD ═══

    fun insertReview(callTs: Long, platform: String?, price: Int, verdict: String?, verdictMsg: String?) {
        val cv = ContentValues().apply {
            put("call_ts", callTs)
            put("platform", platform)
            put("price", price)
            put("verdict", verdict)
            put("verdict_msg", verdictMsg)
        }
        writableDatabase.insert("review_log", null, cv)
    }

    fun updateUserAction(callTs: Long, price: Int, userAction: String, platformDistanceKm: Double? = null) {
        val cv = ContentValues().apply {
            put("user_action", userAction)
            put("reviewed_at", System.currentTimeMillis())
            if (platformDistanceKm != null) put("platform_distance_km", platformDistanceKm)
        }
        writableDatabase.update("review_log", cv, "call_ts=? AND price=?",
            arrayOf(callTs.toString(), price.toString()))
    }

    fun getTodayUnreviewed(): List<ReviewEntry> {
        return getUnreviewedCalls()
    }

    /** 최근 2일치 미복기 콜 반환 (오늘 + 어제) */
    fun getUnreviewedCalls(): List<ReviewEntry> {
        val twoDaysAgoStart = twoDaysAgoStartMs()
        val entries = mutableListOf<ReviewEntry>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, call_ts, platform, price, verdict, verdict_msg, user_action, platform_distance_km FROM review_log WHERE call_ts >= ? AND user_action = 'UNKNOWN' ORDER BY call_ts DESC",
            arrayOf(twoDaysAgoStart.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                entries.add(ReviewEntry(
                    id = it.getInt(0),
                    callTs = it.getLong(1),
                    platform = it.getString(2) ?: "",
                    price = it.getInt(3),
                    verdict = it.getString(4) ?: "",
                    verdictMsg = it.getString(5) ?: "",
                    userAction = it.getString(6) ?: "UNKNOWN",
                    platformDistanceKm = if (it.isNull(7)) null else it.getDouble(7)
                ))
            }
        }
        return entries
    }

    fun getTodayReviewed(): List<ReviewEntry> {
        val todayStart = todayStartMs()
        val entries = mutableListOf<ReviewEntry>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, call_ts, platform, price, verdict, verdict_msg, user_action, platform_distance_km FROM review_log WHERE call_ts >= ? AND user_action != 'UNKNOWN' ORDER BY call_ts DESC",
            arrayOf(todayStart.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                entries.add(ReviewEntry(
                    id = it.getInt(0),
                    callTs = it.getLong(1),
                    platform = it.getString(2) ?: "",
                    price = it.getInt(3),
                    verdict = it.getString(4) ?: "",
                    verdictMsg = it.getString(5) ?: "",
                    userAction = it.getString(6) ?: "UNKNOWN",
                    platformDistanceKm = if (it.isNull(7)) null else it.getDouble(7)
                ))
            }
        }
        return entries
    }

    /** 오늘 call_logs에서 복기 대상 로드 */
    fun getTodayCallLogs(): List<ReviewEntry> {
        return getCallLogs(todayStartMs())
    }

    /** 최근 2일치 call_logs에서 복기 대상 로드 */
    fun getTwoDayCallLogs(): List<ReviewEntry> {
        return getCallLogs(twoDaysAgoStartMs())
    }

    private fun getCallLogs(sinceMs: Long): List<ReviewEntry> {
        val entries = mutableListOf<ReviewEntry>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, platform, price, verdict, reason, storeName, destination, distance, bundleCount FROM $TABLE WHERE timestamp >= ? ORDER BY timestamp DESC",
            arrayOf(sinceMs.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val dist = it.getDouble(7)
                entries.add(ReviewEntry(
                    id = 0,
                    callTs = it.getLong(0),
                    platform = it.getString(1) ?: "",
                    price = it.getInt(2),
                    verdict = it.getString(3) ?: "",
                    verdictMsg = it.getString(4) ?: "",
                    userAction = "UNKNOWN",
                    platformDistanceKm = null,
                    storeName = it.getString(5) ?: "",
                    destination = it.getString(6) ?: "",
                    distance = if (dist < 0) null else dist,
                    bundleCount = it.getInt(8)
                ))
            }
        }
        return entries
    }

    /** review_log에 이미 등록된 call_ts 조회 (모든 상태) */
    fun getReviewedCallTimestamps(): Set<Long> {
        return getReviewedCallTimestamps(todayStartMs())
    }

    fun getReviewedCallTimestamps(sinceMs: Long): Set<Long> {
        val timestamps = mutableSetOf<Long>()
        val cursor = readableDatabase.rawQuery(
            "SELECT call_ts FROM review_log WHERE call_ts >= ?",
            arrayOf(sinceMs.toString())
        )
        cursor.use { while (it.moveToNext()) timestamps.add(it.getLong(0)) }
        return timestamps
    }

    /** 복기 완료된 (reviewed_at NOT NULL) call_ts만 조회 */
    fun getCompletedReviewTimestamps(): Set<Long> {
        return getCompletedReviewTimestamps(twoDaysAgoStartMs())
    }

    fun getCompletedReviewTimestamps(sinceMs: Long): Set<Long> {
        val timestamps = mutableSetOf<Long>()
        val cursor = readableDatabase.rawQuery(
            "SELECT call_ts FROM review_log WHERE call_ts >= ? AND reviewed_at IS NOT NULL",
            arrayOf(sinceMs.toString())
        )
        cursor.use { while (it.moveToNext()) timestamps.add(it.getLong(0)) }
        return timestamps
    }

    /** insert or update: 없으면 insert, 있으면 update */
    fun upsertReview(callTs: Long, platform: String?, price: Int, verdict: String?, verdictMsg: String?, userAction: String, platformDistanceKm: Double? = null) {
        val existing = readableDatabase.rawQuery(
            "SELECT id FROM review_log WHERE call_ts=? AND price=?",
            arrayOf(callTs.toString(), price.toString())
        )
        val exists = existing.use { it.moveToFirst() }
        if (exists) {
            updateUserAction(callTs, price, userAction, platformDistanceKm)
        } else {
            val cv = ContentValues().apply {
                put("call_ts", callTs)
                put("platform", platform)
                put("price", price)
                put("verdict", verdict)
                put("verdict_msg", verdictMsg)
                put("user_action", userAction)
                put("reviewed_at", System.currentTimeMillis())
                if (platformDistanceKm != null) put("platform_distance_km", platformDistanceKm)
            }
            writableDatabase.insert("review_log", null, cv)
        }
    }

    private fun todayStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun twoDaysAgoStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

data class ReviewEntry(
    val id: Int,
    val callTs: Long,
    val platform: String,
    val price: Int,
    val verdict: String,
    val verdictMsg: String,
    val userAction: String,
    val platformDistanceKm: Double?,
    val storeName: String = "",
    val destination: String = "",
    val distance: Double? = null,
    val bundleCount: Int = 1
)
