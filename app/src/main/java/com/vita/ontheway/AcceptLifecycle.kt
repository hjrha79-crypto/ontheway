package com.vita.ontheway

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Fix W + 보강: ACCEPT lifecycle 3단계 분리.
 *
 * CANDIDATE → CONFIRMED / UNCONFIRMED / FALSE
 *
 * detector 감지 → ACCEPT_CANDIDATE (ledger만, 매출 미누적)
 *   ↓ 60초 grace
 *   승격 신호 → CONFIRMED_ACCEPT (ledger DRIVER_ACCEPTED + EarningsTracker)
 *   60초 timeout + 신호 없음 → UNCONFIRMED (ledger만, 매출 미반영)
 *   명시적 반증 → REJECTED_FALSE (ledger만, 매출 미반영)
 *
 * 승격 신호 (하나 이상 충족):
 * 1. 누적 변위 ≥ DISPLACEMENT_CONFIRM_M (80m)
 * 2. 속도 ≥ SPEED_CONFIRM_KMH (8km/h) 연속 SPEED_DURATION_MS (10초)
 * 3. ProximityDetector PICKUP_NEAR(300m) / DELIVERY_NEAR(200m)
 * 4. 화면 상태 신호 ("픽업 완료", "배달 중", "매장 도착" 등)
 */
object AcceptLifecycle {

    private const val TAG = "AcceptLifecycle"

    enum class AcceptState {
        UNKNOWN,
        ACCEPT_CANDIDATE,
        CONFIRMED_ACCEPT,
        UNCONFIRMED,         // 60초 timeout, 신호 없음 (반증 아님, 미확정)
        REJECTED_FALSE       // 명시적 반증 (새 콜 탐색 복귀, reject 등)
    }

    data class Candidate(
        val callSessionId: String,
        val eventId: String,
        val orderId: String,
        val platform: String,
        val price: Int,
        val storeName: String,
        val source: AcceptCoordinator.AcceptSource,
        val candidateAt: Long,
        val startLat: Double,
        val startLng: Double,
        val joinEligible: Boolean,
        var state: AcceptState = AcceptState.ACCEPT_CANDIDATE,
        // 속도 추적
        var speedAboveThresholdSince: Long = 0L
    )

    // ── 설정 ──
    private const val GRACE_PERIOD_MS = 60_000L
    internal const val DISPLACEMENT_CONFIRM_M = 80.0
    internal const val SPEED_CONFIRM_KMH = 8.0
    internal const val SPEED_DURATION_MS = 10_000L

    // ── 상태 ──
    private val candidates = mutableMapOf<String, Candidate>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnables = mutableMapOf<String, Runnable>()

    // ── 테스트 주입 ──
    internal var onConfirmed: ((Context, Candidate) -> Unit)? = null
    internal var onUnconfirmed: ((Context, Candidate) -> Unit)? = null
    internal var onFalse: ((Context, Candidate) -> Unit)? = null
    internal var timeSource: (() -> Long) = { System.currentTimeMillis() }
    /** true이면 markAcceptCandidate 즉시 CONFIRMED 처리 (기존 테스트 호환) */
    internal var autoConfirm: Boolean = false
    /** 테스트용: ledger writer override */
    internal var ledgerWriter: ((Context, String, String, String, String,
        com.vita.ontheway.ledger.LedgerEventType, String, String) -> Unit)? = null
    /** 테스트용: accept_state writer override */
    internal var acceptStateWriter: ((String, String, Long?) -> Unit)? = null
    /** 테스트용: driver_action writer override */
    internal var driverActionWriter: ((String, String, Long) -> Unit)? = null

    // ── 공개 API ──

    /**
     * CANDIDATE 등록. ledger ACCEPT_CANDIDATE만 기록, EarningsTracker 호출 X.
     */
    fun markAcceptCandidate(
        ctx: Context,
        callSessionId: String,
        eventId: String,
        orderId: String,
        platform: String,
        price: Int,
        storeName: String,
        source: AcceptCoordinator.AcceptSource,
        lat: Double,
        lng: Double,
        joinEligible: Boolean,
        immediateConfirm: Boolean = false
    ) {
        if (candidates.containsKey(callSessionId)) return  // dedup

        val candidate = Candidate(
            callSessionId = callSessionId,
            eventId = eventId,
            orderId = orderId,
            platform = platform,
            price = price,
            storeName = storeName,
            source = source,
            candidateAt = timeSource(),
            startLat = lat,
            startLng = lng,
            joinEligible = joinEligible
        )
        candidates[callSessionId] = candidate

        // Ledger: ACCEPT_CANDIDATE 기록 (EarningsTracker 호출 X)
        writeLedger(ctx, candidate, com.vita.ontheway.ledger.LedgerEventType.ACCEPT_CANDIDATE,
            "candidate_registered", "lifecycle")

        // Fix W+: call_logs.accept_state = 'CANDIDATE'
        writeAcceptState(ctx, callSessionId, "CANDIDATE")

        OtwFileLogger.log(TAG, "CANDIDATE: ${price}원 $platform store='$storeName' session=${callSessionId.take(8)}")

        // 즉시 확정: handleAccept() 하위 호환 또는 테스트 autoConfirm
        // Fix Y v2: immediateConfirm은 고신뢰 경로 → ORPHAN check 스킵
        if (immediateConfirm || autoConfirm) {
            promoteConfirmed(ctx, callSessionId, if (immediateConfirm) "immediate_confirm" else "auto_confirm",
                skipOrphanCheck = true)
            return
        }

        // 60초 timeout 스케줄
        val runnable = Runnable { evaluateTimeout(ctx, callSessionId) }
        timeoutRunnables[callSessionId] = runnable
        handler.postDelayed(runnable, GRACE_PERIOD_MS)
    }

    /**
     * GPS 위치 + 속도 업데이트.
     * 승격 조건: 누적 변위 ≥80m OR 속도 ≥8km/h 연속 10초.
     */
    fun onGpsUpdate(ctx: Context, lat: Double, lng: Double, speedMs: Float = 0f) {
        val now = timeSource()
        val speedKmh = speedMs * 3.6
        val toConfirm = mutableListOf<Pair<String, String>>()

        for ((id, c) in candidates) {
            if (c.state != AcceptState.ACCEPT_CANDIDATE) continue

            // 조건 1: 누적 변위
            if (c.startLat != 0.0 || c.startLng != 0.0) {
                val dist = haversineM(c.startLat, c.startLng, lat, lng)
                if (dist >= DISPLACEMENT_CONFIRM_M) {
                    toConfirm.add(id to "gps_displacement_${dist.toInt()}m")
                    continue
                }
            }

            // 조건 2: 속도 기반
            if (speedKmh >= SPEED_CONFIRM_KMH) {
                if (c.speedAboveThresholdSince == 0L) {
                    c.speedAboveThresholdSince = now
                } else if (now - c.speedAboveThresholdSince >= SPEED_DURATION_MS) {
                    toConfirm.add(id to "gps_speed_${speedKmh.toInt()}kmh")
                    continue
                }
            } else {
                c.speedAboveThresholdSince = 0L  // 속도 미달 → 리셋
            }
        }
        for ((id, reason) in toConfirm) {
            promoteConfirmed(ctx, id, reason)
        }
    }

    /**
     * Fix W++: ProximityDetector 이벤트 (platform/sessionId 매칭).
     */
    fun onProximityEvent(ctx: Context, event: String, platform: String? = null, sessionId: String? = null) {
        val matched = matchCandidates(platform, sessionId)
        for (id in matched) {
            promoteConfirmed(ctx, id, "proximity:$event")
        }
    }

    /**
     * Fix W++: 화면 상태 신호 (platform/sessionId 매칭 필수).
     * Fix Y: BAEMIN_PROGRESS 자기-confirm 차단.
     */
    fun onStateSignal(ctx: Context, signal: String, platform: String? = null, sessionId: String? = null) {
        val matched = matchCandidates(platform, sessionId)
        for (id in matched) {
            val candidate = candidates[id] ?: continue
            // Fix Y: BAEMIN_PROGRESS source가 baemin_in_progress 신호로 자기 자신을 confirm 하는 것 차단
            if (candidate.source == AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS
                && signal == "baemin_in_progress"
                && platform == "baemin") {
                Log.w(TAG, "SUPPRESS_SELF_STATE_CONFIRM: source=BAEMIN_PROGRESS signal=baemin_in_progress sessionId=${id.take(8)}")
                OtwFileLogger.log(TAG, "SUPPRESS_SELF_STATE_CONFIRM: source=BAEMIN_PROGRESS signal=baemin_in_progress sessionId=${id.take(8)}")
                continue  // confirm 하지 않음
            }
            promoteConfirmed(ctx, id, "state_signal:$signal")
        }
    }

    /**
     * Fix W++: platform + sessionId 기반 candidate 매칭.
     * - platform null → 전역 (하위 호환, 비추천)
     * - platform 지정 + sessionId 지정 → exact match
     * - platform 지정 + sessionId null + candidate 1개 → confirm
     * - platform 지정 + sessionId null + candidate 2개+ → 가장 최근 1개만
     */
    private fun matchCandidates(platform: String?, sessionId: String?): List<String> {
        val pending = candidates.filter { it.value.state == AcceptState.ACCEPT_CANDIDATE }
        if (pending.isEmpty()) return emptyList()

        // platform null → 전역 매칭 (하위 호환, GPS-only 경로)
        if (platform == null) return pending.keys.toList()

        // platform 필터
        val platformMatch = pending.filter { it.value.platform == platform }
        if (platformMatch.isEmpty()) return emptyList()

        // sessionId 있으면 exact match
        if (sessionId != null) {
            val exact = platformMatch.filter {
                it.value.eventId == sessionId || it.value.callSessionId == sessionId
            }
            return exact.keys.toList()
        }

        // sessionId null → 가장 최근 1개만
        val newest = platformMatch.maxByOrNull { it.value.candidateAt }
        return if (newest != null) listOf(newest.key) else emptyList()
    }

    /**
     * 명시적 reject: 새 콜 탐색 화면 복귀, 명확한 reject 등.
     */
    fun markExplicitReject(ctx: Context, callSessionId: String, reason: String) {
        val c = candidates[callSessionId] ?: return
        if (c.state != AcceptState.ACCEPT_CANDIDATE) return
        c.state = AcceptState.REJECTED_FALSE

        timeoutRunnables.remove(callSessionId)?.let { handler.removeCallbacks(it) }

        OtwFileLogger.log(TAG, "FALSE: ${c.price}원 ${c.platform} reason=$reason session=${callSessionId.take(8)}")

        val callback = onFalse
        if (callback != null) {
            callback(ctx, c)
        } else {
            doFalse(ctx, c, reason)
        }
        candidates.remove(callSessionId)
    }

    // ── 내부 ──

    private fun promoteConfirmed(ctx: Context, callSessionId: String, reason: String, skipOrphanCheck: Boolean = false) {
        val c = candidates[callSessionId] ?: return
        if (c.state != AcceptState.ACCEPT_CANDIDATE) return

        // Fix Y v2: ORPHAN 사전 판별 — JudgmentMatchLogger에 매칭 pending 없으면 ORPHAN_ACCEPT
        // immediateConfirm 경로(handleAccept)는 고신뢰이므로 스킵
        val isOrphan = if (skipOrphanCheck) false else try {
            !JudgmentMatchLogger.hasPendingMatch(
                c.eventId.ifBlank { null }, c.orderId.ifBlank { null },
                c.price, c.storeName.ifBlank { null }, c.platform
            )
        } catch (_: Exception) { false }

        if (isOrphan) {
            c.state = AcceptState.UNCONFIRMED  // ORPHAN → 확정 차단
            timeoutRunnables.remove(callSessionId)?.let { handler.removeCallbacks(it) }
            OtwFileLogger.log(TAG, "ORPHAN_ACCEPT: ${c.price}원 ${c.platform} reason=$reason session=${callSessionId.take(8)} (no_pending_match)")
            doOrphanAccept(ctx, c, reason)
            candidates.remove(callSessionId)
            return
        }

        c.state = AcceptState.CONFIRMED_ACCEPT

        timeoutRunnables.remove(callSessionId)?.let { handler.removeCallbacks(it) }

        OtwFileLogger.log(TAG, "CONFIRMED: ${c.price}원 ${c.platform} reason=$reason session=${callSessionId.take(8)}")

        val callback = onConfirmed
        if (callback != null) {
            callback(ctx, c)
        } else {
            doConfirm(ctx, c, reason)
        }
        candidates.remove(callSessionId)
    }

    private fun doConfirm(ctx: Context, c: Candidate, reason: String) {
        // Ledger: DRIVER_ACCEPTED (CONFIRMED 시점에만)
        val derivedJson = org.json.JSONObject().apply {
            put("price", c.price)
            put("storeName", c.storeName)
            put("source", c.source.name)
            put("confirm_reason", reason)
            put("grace_ms", timeSource() - c.candidateAt)
        }.toString()
        writeLedger(ctx, c, com.vita.ontheway.ledger.LedgerEventType.ACCEPT_CONFIRMED,
            derivedJson, "lifecycle")

        // Fix W+: call_logs.accept_state = 'CONFIRMED'
        writeAcceptState(ctx, c.callSessionId, "CONFIRMED", timeSource())

        // Fix W+++: driver_action = 'real_accepted'
        writeDriverAction(ctx, c.callSessionId, c.source.name, timeSource())

        // EarningsTracker 누적 (CONFIRMED에서만)
        if (c.joinEligible) {
            EarningsTracker.recordAccept(ctx, c.price, c.platform, c.storeName, c.orderId.ifBlank { null })
        }

        // JudgmentMatchLogger + FilterLog
        try {
            JudgmentMatchLogger.onAcceptDetected(
                ctx, eventId = c.eventId, orderId = c.orderId,
                price = c.price, storeName = c.storeName, platform = c.platform
            )
        } catch (_: Exception) {}
        FilterLog.recordAccepted(ctx, c.price, c.platform, c.eventId, c.orderId, c.storeName)
    }

    private fun evaluateTimeout(ctx: Context, callSessionId: String) {
        val c = candidates[callSessionId] ?: return
        if (c.state != AcceptState.ACCEPT_CANDIDATE) return
        c.state = AcceptState.UNCONFIRMED

        timeoutRunnables.remove(callSessionId)

        OtwFileLogger.log(TAG, "UNCONFIRMED: ${c.price}원 ${c.platform} (60s timeout) session=${callSessionId.take(8)}")

        val callback = onUnconfirmed
        if (callback != null) {
            callback(ctx, c)
        } else {
            doUnconfirmed(ctx, c)
        }
        candidates.remove(callSessionId)
    }

    private fun doUnconfirmed(ctx: Context, c: Candidate) {
        // Fix W+: call_logs.accept_state = 'UNCONFIRMED'
        writeAcceptState(ctx, c.callSessionId, "UNCONFIRMED")

        val derivedJson = org.json.JSONObject().apply {
            put("price", c.price)
            put("storeName", c.storeName)
            put("source", c.source.name)
            put("reason", "timeout_no_signal")
            put("grace_ms", timeSource() - c.candidateAt)
        }.toString()
        writeLedger(ctx, c, com.vita.ontheway.ledger.LedgerEventType.ACCEPT_UNCONFIRMED,
            derivedJson, "lifecycle")
    }

    /** Fix Y v2: ORPHAN_ACCEPT — 매칭 pending 없이 도달한 confirm 시도. EarningsTracker 미호출. */
    private fun doOrphanAccept(ctx: Context, c: Candidate, reason: String) {
        writeAcceptState(ctx, c.callSessionId, "ORPHAN_ACCEPT")

        val derivedJson = org.json.JSONObject().apply {
            put("price", c.price)
            put("storeName", c.storeName)
            put("source", c.source.name)
            put("confirm_reason", reason)
            put("reason", "no_pending_match")
            put("grace_ms", timeSource() - c.candidateAt)
        }.toString()
        writeLedger(ctx, c, com.vita.ontheway.ledger.LedgerEventType.ORPHAN_ACCEPT,
            derivedJson, "lifecycle")
    }

    private fun doFalse(ctx: Context, c: Candidate, reason: String) {
        // Fix W+: call_logs.accept_state = 'REJECTED_FALSE'
        writeAcceptState(ctx, c.callSessionId, "REJECTED_FALSE")

        val derivedJson = org.json.JSONObject().apply {
            put("price", c.price)
            put("storeName", c.storeName)
            put("source", c.source.name)
            put("reason", reason)
            put("grace_ms", timeSource() - c.candidateAt)
        }.toString()
        writeLedger(ctx, c, com.vita.ontheway.ledger.LedgerEventType.ACCEPT_REJECTED_FALSE,
            derivedJson, "lifecycle")
    }

    private fun writeLedger(
        ctx: Context, c: Candidate,
        type: com.vita.ontheway.ledger.LedgerEventType,
        derivedJson: String, trigger: String
    ) {
        try {
            val writer = ledgerWriter
            if (writer != null) {
                writer(ctx, c.callSessionId, c.eventId, c.orderId, c.platform, type, trigger, derivedJson)
            } else {
                com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                    ctx, c.callSessionId, c.eventId, c.orderId, c.platform,
                    type, trigger, derivedJson
                )
            }
        } catch (_: Exception) {}
    }

    /** Fix W+: CallLogDb.accept_state 업데이트 */
    private fun writeAcceptState(ctx: Context, callSessionId: String, state: String, confirmedAt: Long? = null) {
        val writer = acceptStateWriter
        if (writer != null) {
            writer(callSessionId, state, confirmedAt)
        } else {
            try { CallLogDb.get(ctx).updateAcceptState(callSessionId, state, confirmedAt) } catch (_: Exception) {}
        }
    }

    /** Fix W+++: CallLogDb.markAcceptedWithSource (driver_action='real_accepted') */
    private fun writeDriverAction(ctx: Context, callSessionId: String, source: String, ts: Long) {
        val writer = driverActionWriter
        if (writer != null) {
            writer(callSessionId, source, ts)
        } else {
            try { CallLogDb.get(ctx).markAcceptedWithSource(callSessionId, source, ts) } catch (_: Exception) {}
        }
    }

    // ── 조회 ──

    fun pendingCount(): Int = candidates.count { it.value.state == AcceptState.ACCEPT_CANDIDATE }

    fun getState(callSessionId: String): AcceptState =
        candidates[callSessionId]?.state ?: AcceptState.UNKNOWN

    fun getCandidate(callSessionId: String): Candidate? = candidates[callSessionId]

    /** 모든 pending candidate의 callSessionId 목록 */
    fun pendingSessionIds(): List<String> =
        candidates.filter { it.value.state == AcceptState.ACCEPT_CANDIDATE }.keys.toList()

    // ── 테스트 ──

    fun resetForTest() {
        for (r in timeoutRunnables.values) handler.removeCallbacks(r)
        candidates.clear()
        timeoutRunnables.clear()
        onConfirmed = null
        onUnconfirmed = null
        onFalse = null
        timeSource = { System.currentTimeMillis() }
        autoConfirm = false
        ledgerWriter = null
        acceptStateWriter = null
        driverActionWriter = null
    }

    /** Haversine distance in meters */
    internal fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
