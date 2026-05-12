package com.vita.ontheway

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 판정-행동 매칭 로거 v2.
 *
 * v1 문제: pendingEvent 단일 → 연속 콜 시 잘못된 매칭.
 * v2 변경: ConcurrentHashMap 다중 pending + eventId/orderId 기반 매칭.
 *
 * 흐름:
 * 1. onJudgmentIssued() — PENDING 이벤트 생성 (eventId key)
 * 2. onAcceptDetected() — eventId/orderId/fingerprint 매칭 → ACCEPTED
 * 3. 5분 타임아웃 → TIMEOUT 자동 종료
 */
object JudgmentMatchLogger {

    private const val TAG = "OTW_JUDGMENT_MATCH"
    private const val FILE_NAME = "judgment_match.jsonl"
    private const val MAX_LINES = 500
    private const val TIMEOUT_MS = 300_000L  // 5분 (v1: 30초 → v2: 5분)
    private const val CLEANUP_INTERVAL_MS = 60_000L  // 1분마다 cleanup

    val matchCount = AtomicInteger(0)
    val mismatchCount = AtomicInteger(0)
    val jobMismatchCount = AtomicInteger(0)
    val passMismatchCount = AtomicInteger(0)

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JudgmentMatch-IO").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    // v2: 다중 동시 pending (key = eventId)
    private val pendingEvents = ConcurrentHashMap<String, PendingJudgment>()

    data class PendingJudgment(
        val eventId: String,
        val orderId: String?,
        val callSessionId: String?,
        val platform: String,
        val price: Int,
        val distanceKm: Double?,
        val storeName: String?,
        val judgment: String,   // "JOB"/"OK"/"PASS"
        val reason: String?,
        val issuedAt: Long = System.currentTimeMillis()
    ) {
        /** fingerprint for fallback matching */
        fun fingerprint(): String {
            val store = (storeName ?: "").trim().lowercase()
            return "$store|$price|$platform"
        }
    }

    enum class MatchConfidence { HIGH, LOW }

    private var cleanupScheduled = false

    /**
     * 판정 발행 시 호출.
     * eventId 기반 다중 pending 관리.
     */
    fun onJudgmentIssued(
        ctx: Context,
        platform: String,
        price: Int,
        distanceKm: Double?,
        storeName: String?,
        verdict: String,
        reason: String?,
        sessionId: String?,
        eventId: String? = null,
        orderId: String? = null,
        callSessionId: String? = null
    ) {
        try {
            val judgment = JudgmentMatchEvent.verdictToJudgment(verdict)
            // eventId: 전달된 값 우선, 없으면 sessionId(구 호환), 없으면 생성
            val effectiveEventId = eventId
                ?: sessionId
                ?: "jm_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"

            val pending = PendingJudgment(
                eventId = effectiveEventId,
                orderId = orderId,
                callSessionId = callSessionId,
                platform = platform,
                price = price,
                distanceKm = distanceKm,
                storeName = storeName,
                judgment = judgment,
                reason = reason
            )
            pendingEvents[effectiveEventId] = pending

            Log.d(TAG, "PENDING: $platform ${price}원 judgment=$judgment eventId=${effectiveEventId.take(8)}")
            OtwFileLogger.log(TAG, "PENDING: $platform ${price}원 judgment=$judgment eventId=${effectiveEventId.take(8)}")

            scheduleCleanup(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "onJudgmentIssued 실패: ${e.message}")
        }
    }

    /**
     * 수락 감지 시 호출.
     * 매칭 우선순위: eventId → orderId → fingerprint(price+store+5분) → orphan.
     */
    fun onAcceptDetected(
        ctx: Context,
        eventId: String? = null,
        orderId: String? = null,
        price: Int = 0,
        storeName: String? = null,
        platform: String? = null
    ) {
        try {
            val matched = findMatch(eventId, orderId, price, storeName, platform)
            if (matched == null) {
                // ORPHAN — pending 없이 수락 감지
                Log.d(TAG, "ORPHAN: 매칭 pending 없음 (eventId=${eventId?.take(8)}, price=$price)")
                OtwFileLogger.log(TAG, "ORPHAN: 매칭 pending 없음 (eventId=${eventId?.take(8)}, price=$price)")

                // Ledger: ORPHAN_CLASSIFIED
                try {
                    val csId = com.vita.ontheway.ledger.CallSessionRegistry.findSessionId(eventId, orderId)
                    if (csId != null) {
                        com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                            ctx, csId, eventId, orderId, platform ?: "unknown",
                            com.vita.ontheway.ledger.LedgerEventType.ORPHAN_CLASSIFIED, "internal",
                            org.json.JSONObject().apply {
                                put("reason", "no_pending_match")
                                put("price", price)
                            }.toString()
                        )
                    }
                } catch (_: Exception) {}
                return
            }

            val (pending, confidence) = matched
            pendingEvents.remove(pending.eventId)

            val matchStatus = JudgmentMatchEvent.computeMatchStatus(pending.judgment, "ACCEPTED")
            val event = JudgmentMatchEvent(
                eventId = pending.eventId,
                timestamp = pending.issuedAt,
                platform = pending.platform,
                price = pending.price,
                distanceKm = pending.distanceKm,
                storeName = pending.storeName,
                onthewayJudgment = pending.judgment,
                userAction = "ACCEPTED",
                matchStatus = matchStatus,
                rejectionReason = pending.reason
            )

            updateCounters(matchStatus, pending.judgment)
            writeEvent(ctx, event)

            // Quarantine: LOW confidence 매칭 → STALE_TIMEOUT
            if (confidence == MatchConfidence.LOW) {
                try {
                    val csId = pending.callSessionId
                        ?: com.vita.ontheway.ledger.CallSessionRegistry.findSessionId(pending.eventId, pending.orderId)
                    if (csId != null) {
                        CallLogDb.get(ctx).markQuarantined(csId,
                            com.vita.ontheway.ledger.QuarantineReason.STALE_TIMEOUT,
                            "confidence=LOW, match_by=fingerprint")
                    }
                } catch (_: Exception) {}
            }

            Log.d(TAG, "RESOLVED: ACCEPTED $matchStatus confidence=$confidence " +
                "(${pending.judgment}→ACCEPTED, eventId=${pending.eventId.take(8)})")
            OtwFileLogger.log(TAG, "RESOLVED: ACCEPTED $matchStatus confidence=$confidence")
        } catch (e: Exception) {
            Log.w(TAG, "onAcceptDetected 실패: ${e.message}")
        }
    }

    private fun findMatch(
        eventId: String?, orderId: String?, price: Int, storeName: String?, platform: String?
    ): Pair<PendingJudgment, MatchConfidence>? {
        val now = System.currentTimeMillis()

        // 1. eventId 일치 → HIGH
        if (!eventId.isNullOrBlank()) {
            val match = pendingEvents[eventId]
            if (match != null && (now - match.issuedAt) < TIMEOUT_MS) {
                return match to MatchConfidence.HIGH
            }
        }

        // 2. orderId 일치 → HIGH
        if (!orderId.isNullOrBlank()) {
            val match = pendingEvents.values.firstOrNull {
                it.orderId == orderId && (now - it.issuedAt) < TIMEOUT_MS
            }
            if (match != null) return match to MatchConfidence.HIGH
        }

        // 3. fingerprint 일치 (price+store+platform, 5분 내) → LOW
        if (price > 0) {
            val store = (storeName ?: "").trim().lowercase()
            val fp = "$store|$price|${platform ?: ""}"
            val match = pendingEvents.values.firstOrNull {
                it.fingerprint() == fp && (now - it.issuedAt) < TIMEOUT_MS
            }
            if (match != null) return match to MatchConfidence.LOW
        }

        // 4. 단일 pending + 5분 내 → LOW (하위 호환 fallback)
        if (pendingEvents.size == 1) {
            val single = pendingEvents.values.first()
            if ((now - single.issuedAt) < TIMEOUT_MS) {
                return single to MatchConfidence.LOW
            }
        }

        return null
    }

    private fun scheduleCleanup(ctx: Context) {
        if (cleanupScheduled) return
        cleanupScheduled = true
        val appCtx = ctx.applicationContext
        handler.postDelayed(object : Runnable {
            override fun run() {
                cleanupExpired(appCtx)
                if (pendingEvents.isNotEmpty()) {
                    handler.postDelayed(this, CLEANUP_INTERVAL_MS)
                } else {
                    cleanupScheduled = false
                }
            }
        }, CLEANUP_INTERVAL_MS)
    }

    private fun cleanupExpired(ctx: Context) {
        val now = System.currentTimeMillis()
        val expired = pendingEvents.values.filter { (now - it.issuedAt) >= TIMEOUT_MS }
        for (pending in expired) {
            pendingEvents.remove(pending.eventId)
            resolveAsTimeout(ctx, pending)
        }
    }

    private fun resolveAsTimeout(ctx: Context, pending: PendingJudgment) {
        try {
            val matchStatus = JudgmentMatchEvent.computeMatchStatus(pending.judgment, "TIMEOUT")
            val event = JudgmentMatchEvent(
                eventId = pending.eventId,
                timestamp = pending.issuedAt,
                platform = pending.platform,
                price = pending.price,
                distanceKm = pending.distanceKm,
                storeName = pending.storeName,
                onthewayJudgment = pending.judgment,
                userAction = "TIMEOUT",
                matchStatus = matchStatus,
                rejectionReason = pending.reason
            )

            updateCounters(matchStatus, pending.judgment)
            writeEvent(ctx, event)

            // Ledger: TIMEOUT
            try {
                val csId = pending.callSessionId
                    ?: com.vita.ontheway.ledger.CallSessionRegistry.findSessionId(pending.eventId, pending.orderId)
                if (csId != null) {
                    com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                        ctx, csId, pending.eventId, pending.orderId, pending.platform,
                        com.vita.ontheway.ledger.LedgerEventType.TIMEOUT, "internal"
                    )
                }
            } catch (_: Exception) {}

            Log.d(TAG, "TIMEOUT: ${pending.platform} ${pending.price}원 (${pending.eventId.take(8)})")
            OtwFileLogger.log(TAG, "TIMEOUT: ${pending.platform} ${pending.price}원")
        } catch (e: Exception) {
            Log.w(TAG, "resolveAsTimeout 실패: ${e.message}")
        }
    }

    private fun updateCounters(matchStatus: String, judgment: String) {
        when (matchStatus) {
            "MATCH" -> matchCount.incrementAndGet()
            "MISMATCH" -> {
                mismatchCount.incrementAndGet()
                when (judgment) {
                    "JOB" -> jobMismatchCount.incrementAndGet()
                    "PASS" -> passMismatchCount.incrementAndGet()
                }
            }
        }
    }

    private fun writeEvent(ctx: Context, event: JudgmentMatchEvent) {
        val jsonObj = event.toJson()
        val json = jsonObj.toString()
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            try {
                val file = File(appCtx.filesDir, FILE_NAME)
                file.appendText(json + "\n")
                val lines = file.readLines()
                if (lines.size > MAX_LINES) {
                    file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "파일 기록 실패: ${e.message}")
            }
        }
        try { SupabaseSync.uploadJudgmentMatch(appCtx, org.json.JSONObject(json)) } catch (_: Exception) {}
    }

    /** 오늘 이벤트 로드 (DevStats 표시용) */
    fun getTodayEvents(ctx: Context): List<JudgmentMatchEvent> {
        return try {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            val todayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        val j = org.json.JSONObject(line)
                        val ts = j.optLong("ts", 0)
                        if (ts < todayStart) return@mapNotNull null
                        JudgmentMatchEvent(
                            eventId = j.optString("event_id"),
                            timestamp = ts,
                            platform = j.optString("platform"),
                            price = j.optInt("price"),
                            distanceKm = if (j.has("distance_km") && !j.isNull("distance_km")) j.getDouble("distance_km") else null,
                            storeName = if (j.has("store_name") && !j.isNull("store_name")) j.getString("store_name") else null,
                            onthewayJudgment = j.optString("judgment"),
                            userAction = j.optString("user_action"),
                            matchStatus = j.optString("match_status")
                        )
                    } catch (_: Exception) { null }
                }
        } catch (_: Exception) { emptyList() }
    }

    /** 테스트용 초기화 */
    fun resetForTest() {
        pendingEvents.clear()
        matchCount.set(0)
        mismatchCount.set(0)
        jobMismatchCount.set(0)
        passMismatchCount.set(0)
        cleanupScheduled = false
    }

    /** 디버그: 현재 pending 수 */
    fun pendingCount(): Int = pendingEvents.size

    /**
     * Fix Y v2: 매칭 가능한 pending이 있는지 검사 (ORPHAN 사전 판별).
     * promoteConfirmed 전에 호출하여 no_pending_match 시 ORPHAN_ACCEPT로 분리.
     */
    fun hasPendingMatch(
        eventId: String?, orderId: String?, price: Int, storeName: String?, platform: String?
    ): Boolean = findMatch(eventId, orderId, price, storeName, platform) != null
}
