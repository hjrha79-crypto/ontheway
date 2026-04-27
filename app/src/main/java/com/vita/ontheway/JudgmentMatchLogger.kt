package com.vita.ontheway

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 판정-행동 자동 매칭 로거.
 *
 * 흐름:
 * 1. onJudgmentIssued() — processDeliveryCall 끝에서 호출, PENDING 이벤트 생성 + 30s 타이머
 * 2. onAcceptDetected() — 수락 감지 시 호출, PENDING→ACCEPTED 갱신
 * 3. 30s 타임아웃 → PENDING→TIMEOUT 자동 종료
 */
object JudgmentMatchLogger {

    private const val TAG = "OTW_JUDGMENT_MATCH"
    private const val FILE_NAME = "judgment_match.jsonl"
    private const val MAX_LINES = 500
    private const val TIMEOUT_MS = 30_000L

    val matchCount = AtomicInteger(0)
    val mismatchCount = AtomicInteger(0)

    // JOB-MISMATCH, PASS-MISMATCH 세부 카운터
    val jobMismatchCount = AtomicInteger(0)
    val passMismatchCount = AtomicInteger(0)

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JudgmentMatch-IO").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    // 현재 PENDING 이벤트 (1개만 — 콜은 순차적)
    @Volatile
    private var pendingEvent: PendingJudgment? = null
    private var timeoutRunnable: Runnable? = null

    private data class PendingJudgment(
        val eventId: String,
        val timestamp: Long,
        val platform: String,
        val price: Int,
        val distanceKm: Double?,
        val storeName: String?,
        val judgment: String,   // "JOB"/"OK"/"PASS"
        val reason: String?
    )

    /**
     * 판정 발행 시 호출 (processDeliveryCall 끝).
     * PENDING 이벤트 생성 + 30s 타임아웃 시작.
     */
    fun onJudgmentIssued(
        ctx: Context,
        platform: String,
        price: Int,
        distanceKm: Double?,
        storeName: String?,
        verdict: String,
        reason: String?,
        sessionId: String?
    ) {
        try {
            // 기존 PENDING이 있으면 TIMEOUT으로 종료
            resolvePendingAsTimeout(ctx)

            val judgment = JudgmentMatchEvent.verdictToJudgment(verdict)
            val eventId = "jm_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"

            pendingEvent = PendingJudgment(
                eventId = eventId,
                timestamp = System.currentTimeMillis(),
                platform = platform,
                price = price,
                distanceKm = distanceKm,
                storeName = storeName,
                judgment = judgment,
                reason = reason
            )

            Log.d(TAG, "PENDING: $platform ${price}원 judgment=$judgment")

            // 30s 타임아웃 스케줄
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            val appCtx = ctx.applicationContext
            timeoutRunnable = Runnable { resolvePendingAsTimeout(appCtx) }
            handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "onJudgmentIssued 실패: ${e.message}")
        }
    }

    /**
     * 수락 감지 시 호출 (onAcceptDetected).
     * PENDING → ACCEPTED로 매칭.
     */
    fun onAcceptDetected(ctx: Context) {
        try {
            val pending = pendingEvent ?: return
            pendingEvent = null
            timeoutRunnable?.let { handler.removeCallbacks(it) }

            val matchStatus = JudgmentMatchEvent.computeMatchStatus(pending.judgment, "ACCEPTED")
            val event = JudgmentMatchEvent(
                eventId = pending.eventId,
                timestamp = pending.timestamp,
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
            Log.d(TAG, "RESOLVED: ACCEPTED, $matchStatus (${pending.judgment} → ACCEPTED)")
        } catch (e: Exception) {
            Log.w(TAG, "onAcceptDetected 실패: ${e.message}")
        }
    }

    private fun resolvePendingAsTimeout(ctx: Context) {
        try {
            val pending = pendingEvent ?: return
            pendingEvent = null

            val matchStatus = JudgmentMatchEvent.computeMatchStatus(pending.judgment, "TIMEOUT")
            val event = JudgmentMatchEvent(
                eventId = pending.eventId,
                timestamp = pending.timestamp,
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
            Log.d(TAG, "RESOLVED: TIMEOUT, $matchStatus (${pending.judgment} → TIMEOUT)")
        } catch (e: Exception) {
            Log.w(TAG, "resolvePendingAsTimeout 실패: ${e.message}")
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
        val json = event.toJson().toString()
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
}
