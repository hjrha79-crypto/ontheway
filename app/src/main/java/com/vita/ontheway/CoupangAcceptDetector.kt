package com.vita.ontheway

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * COUPANG-NOTIFICATION-FIRST + CRITICAL-FIX (v70.6): 쿠팡 수락 신호 조합 감지.
 *
 * 윈도우 분기:
 * - 0~15초: dismiss + state → MATCHED
 * - 0~2초: state evidence 필수 (dismiss 단독 → SUSPECTED)
 * - 15~30초: dismiss + state → SUSPECTED (late match)
 * - 30초: 후보 폐기 (EXPIRED)
 *
 * periodic tick (1초 주기) — onNotificationRemoved 의존 X.
 */
object CoupangAcceptDetector {

    private const val TAG = "CoupangAcceptDetector"
    private const val CANDIDATE_TTL_MS = 30_000L
    private const val STATE_WINDOW_MS = 15_000L
    private const val EARLY_WINDOW_MS = 2_000L
    private const val TICK_INTERVAL_MS = 1_000L

    data class AcceptCandidate(
        val notification: CoupangNotificationParser.CoupangNotification,
        val identityKey: CoupangIdentityKey.IdentityKey,
        val createdAt: Long = System.currentTimeMillis(),
        var dismissed: Boolean = false,
        var dismissedAt: Long = 0,
        var stateConfirmed: Boolean = false,
        var stateConfirmedAt: Long = 0,
        var stateConfidence: StateConfidence = StateConfidence.HIGH,
        val processedAtomic: AtomicBoolean = AtomicBoolean(false)
    ) {
        var processed: Boolean
            get() = processedAtomic.get()
            set(value) { processedAtomic.set(value) }
    }

    enum class AcceptResult {
        MATCHED,     // dismiss + state within 15s
        SUSPECTED,   // dismiss without state, or late match
        PENDING,
        EXPIRED
    }

    private val candidates = ConcurrentHashMap<String, AcceptCandidate>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CoupangAccept-Tick").apply { isDaemon = true }
    }
    private var tickFuture: ScheduledFuture<*>? = null
    private var tickContext: Context? = null

    fun onNotificationReceived(notification: CoupangNotificationParser.CoupangNotification): AcceptCandidate {
        cleanup()
        val identityKey = CoupangIdentityKey.resolve(
            sbnKey = notification.notificationKey.ifBlank { null },
            postTime = notification.postTime,
            price = notification.offeredPrice,
            distanceKm = notification.distanceKm,
            bundleCount = notification.bundleCount,
            rawText = notification.rawText
        )
        val candidate = AcceptCandidate(notification = notification, identityKey = identityKey)
        candidates[identityKey.key] = candidate
        OtwFileLogger.log(TAG, "CANDIDATE: ${notification.offeredPrice}원 ${notification.distanceKm}km key=${identityKey.key.take(20)}")
        ensureTickRunning()
        return candidate
    }

    fun onNotificationDismissed(sbnKey: String, postTime: Long) {
        val key = findCandidateKey(sbnKey, postTime) ?: return
        val candidate = candidates[key] ?: return
        if (candidate.processed) return

        candidate.dismissed = true
        candidate.dismissedAt = System.currentTimeMillis()
        OtwFileLogger.log(TAG, "DISMISSED: key=${key.take(20)} price=${candidate.notification.offeredPrice}")

        // 즉시 평가
        evaluateCandidate(candidate)
    }

    enum class StateConfidence { HIGH, MEDIUM }

    /**
     * 쿠팡 상태 이벤트로 ACCEPT_SUSPECTED → DRIVER_ACCEPTED 승격.
     *
     * @param stateText 감지된 상태 텍스트 (nullable — 하위 호환)
     * @return 승격된 candidate 수
     */
    @JvmOverloads
    fun onCoupangStateEvent(stateText: String? = null): Int {
        val confidence = classifyStateConfidence(stateText) ?: return 0
        val now = System.currentTimeMillis()
        var promoted = 0
        for ((_, candidate) in candidates) {
            if (candidate.processed) continue
            if (now - candidate.createdAt > CANDIDATE_TTL_MS) continue

            candidate.stateConfirmed = true
            candidate.stateConfirmedAt = now
            candidate.stateConfidence = confidence

            evaluateCandidate(candidate)
            if (candidate.processed) promoted++
        }
        OtwFileLogger.log(TAG, "STATE_EVENT: text=${stateText?.take(20)} confidence=$confidence promoted=$promoted")
        return promoted
    }

    // Q-0c: 신규 콜 패턴 (픽업 완료와 동시 등장 시 state event 무시)
    private val NEW_CALL_PATTERN = Regex(""".*\d+,\d+원.*""")

    /**
     * 상태 텍스트 → confidence 분류.
     * null이면 HIGH (하위 호환: 이전 코드에서 파라미터 없이 호출).
     *
     * Q-0c: "픽업 완료" + 신규콜 패턴 동시 등장 → null (차단)
     */
    internal fun classifyStateConfidence(stateText: String?): StateConfidence? {
        if (stateText == null) return StateConfidence.HIGH
        return when {
            stateText.contains("매장 도착") || stateText.contains("매장 픽업") -> StateConfidence.HIGH
            stateText.contains("픽업 완료") -> {
                // Q-0c guard: 신규배달/가격/수락 패턴 동시 등장 → 신규 콜 화면, state 무시
                if (stateText.contains("신규배달") || stateText.contains("수락")
                    || NEW_CALL_PATTERN.containsMatchIn(stateText)) {
                    OtwFileLogger.log(TAG, "Q-0c: 픽업완료 + 신규콜 패턴 → 차단")
                    null
                } else {
                    StateConfidence.MEDIUM
                }
            }
            else -> null
        }
    }

    /**
     * 윈도우 분기 평가.
     * P0-2: MATCHED 시 processMatched() 즉시 dispatch.
     */
    private fun evaluateCandidate(candidate: AcceptCandidate) {
        if (candidate.processed) return
        if (!candidate.dismissed) return // dismiss 없으면 아직 판단 불가

        val elapsed = candidate.dismissedAt - candidate.createdAt

        if (elapsed in 0..STATE_WINDOW_MS) {
            // 0~15초 윈도우
            if (elapsed < EARLY_WINDOW_MS && !candidate.stateConfirmed) {
                // 0~2초 + state X → 아직 판단 보류 (state 대기)
                return
            }
            if (candidate.stateConfirmed) {
                // dismiss + state → MATCHED → 즉시 dispatch
                val ctx = tickContext
                if (ctx != null) {
                    processMatched(ctx, candidate)
                } else {
                    // context 없음 → processed만 설정 (tick에서 재시도)
                    candidate.processed = true
                    OtwFileLogger.log(TAG, "MATCHED_NO_CTX: ${candidate.notification.offeredPrice}원 (context null)")
                }
            }
            // state 미확인 + 2~15초 → PENDING 유지 (tick에서 timeout 처리)
        }
        // 15~30초는 tick에서 처리
    }

    /**
     * periodic tick (1초 주기) — timeout 처리.
     * onNotificationRemoved 없이도 동작.
     */
    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        val toProcess = mutableListOf<Pair<AcceptCandidate, AcceptResult>>()

        for ((key, candidate) in candidates) {
            if (candidate.processed) continue
            val elapsed = now - candidate.createdAt

            if (elapsed > CANDIDATE_TTL_MS) {
                // 30초 초과 — 최종 판정
                candidate.processed = true
                val result = when {
                    candidate.dismissed && candidate.stateConfirmed -> AcceptResult.SUSPECTED // late match → SUSPECTED
                    candidate.dismissed -> AcceptResult.SUSPECTED  // dismiss만
                    else -> AcceptResult.EXPIRED  // 아무 신호 없음
                }
                toProcess.add(candidate to result)
                OtwFileLogger.log(TAG, "${result.name}(tick): key=${key.take(20)} elapsed=${elapsed}ms")
            } else if (elapsed > STATE_WINDOW_MS && candidate.dismissed && !candidate.stateConfirmed) {
                // 15~30초 + dismiss만 + state X → SUSPECTED 즉시 발행
                candidate.processed = true
                toProcess.add(candidate to AcceptResult.SUSPECTED)
                OtwFileLogger.log(TAG, "SUSPECTED(window): key=${key.take(20)} elapsed=${elapsed}ms")
            } else if (candidate.dismissed && candidate.stateConfirmed && !candidate.processed) {
                // 이미 evaluate에서 처리됐어야 하지만 safety net
                candidate.processed = true
                val result = if (elapsed <= STATE_WINDOW_MS) AcceptResult.MATCHED else AcceptResult.SUSPECTED
                toProcess.add(candidate to result)
            }
        }

        // 처리
        for ((candidate, result) in toProcess) {
            when (result) {
                AcceptResult.MATCHED -> processMatched(ctx, candidate)
                AcceptResult.SUSPECTED -> processSuspected(ctx, candidate)
                else -> {}
            }
        }

        // 활성 candidate 없으면 tick 중지
        if (candidates.none { !it.value.processed }) {
            stopTick()
        }

        cleanup()
    }

    fun checkPending(ctx: Context): List<Pair<AcceptCandidate, AcceptResult>> {
        // tick 실행
        tick(ctx)

        val now = System.currentTimeMillis()
        val results = mutableListOf<Pair<AcceptCandidate, AcceptResult>>()
        for ((_, candidate) in candidates) {
            if (candidate.processed) continue
            val elapsed = now - candidate.createdAt
            if (candidate.dismissed && candidate.stateConfirmed) {
                results.add(candidate to AcceptResult.MATCHED)
            } else {
                results.add(candidate to AcceptResult.PENDING)
            }
        }
        return results
    }

    fun processMatched(ctx: Context, candidate: AcceptCandidate) {
        if (!candidate.processedAtomic.compareAndSet(false, true)) return // atomic idempotent
        OtwFileLogger.log(TAG, "MATCHED_DISPATCH: ${candidate.notification.offeredPrice}원 key=${candidate.identityKey.key.take(20)}")
        val n = candidate.notification
        // P0-1 (v70.8): notificationKey ≠ orderId — eventId에 identityKey, orderId는 빈값
        // Fix W+: 운영 경로 → recordAcceptCandidate (60초 grace 후 확정)
        AcceptCoordinator.recordAcceptCandidate(
            ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            n.offeredPrice, "coupang",
            storeName = n.storeName,
            eventId = candidate.identityKey.key,
            orderId = "",  // 쿠팡 알림에 실제 주문 ID 없음
            explicitIdentityConf = candidate.identityKey.confidence,
            explicitPriceSource = "offered"
        )
    }

    fun processSuspected(ctx: Context, candidate: AcceptCandidate) {
        val n = candidate.notification
        try {
            // Fix 2 (v70.8.1): orderId="" (notificationKey ≠ orderId)
            val callSessionId = com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(
                eventId = candidate.identityKey.key,
                orderId = null  // 쿠팡 알림에 실제 주문 ID 없음
            )
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                ctx, callSessionId, candidate.identityKey.key, null,
                "coupang",
                com.vita.ontheway.ledger.LedgerEventType.ACCEPT_SUSPECTED,
                "notification",
                org.json.JSONObject().apply {
                    put("price", n.offeredPrice)
                    put("distanceKm", n.distanceKm ?: 0.0)
                    put("storeName", n.storeName)
                    put("source", "COUPANG_NOTIFICATION_SUSPECTED")
                    put("identity_confidence", candidate.identityKey.confidence)
                    put("join_eligible", false)
                    put("price_source", "offered")
                    put("source_channel", n.sourceChannel)
                }.toString()
            )
        } catch (_: Exception) {}
        OtwFileLogger.log(TAG, "SUSPECTED_LEDGER: ${n.offeredPrice}원 key=${candidate.identityKey.key.take(20)}")
    }

    private fun ensureTickRunning() {
        if (tickFuture == null || tickFuture?.isDone == true) {
            tickFuture = scheduler.scheduleAtFixedRate({
                try {
                    val ctx = tickContext ?: return@scheduleAtFixedRate
                    tick(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "tick 실패: ${e.message}")
                }
            }, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopTick() {
        tickFuture?.cancel(false)
        tickFuture = null
    }

    /** DeliveryNotificationService에서 context 설정 */
    fun setContext(ctx: Context) {
        tickContext = ctx.applicationContext
    }

    private fun findCandidateKey(sbnKey: String, postTime: Long): String? {
        val primaryKey = "coupang:$sbnKey:$postTime"
        if (candidates.containsKey(primaryKey)) return primaryKey
        for ((key, _) in candidates) {
            if (key.contains(sbnKey)) return key
        }
        return null
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        candidates.entries.removeIf { now - it.value.createdAt > CANDIDATE_TTL_MS * 2 }
    }

    fun pendingCount(): Int = candidates.count { !it.value.processed }

    fun resetForTest() {
        candidates.clear()
        stopTick()
        tickContext = null
    }
}
