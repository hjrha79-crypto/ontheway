package com.vita.ontheway

import android.util.Log
import java.util.UUID

class SessionManager(private val transitionLog: StateTransitionLog) {

    @Volatile
    private var activeSession: CallSession? = null

    // v3.20: 연결성 분석용 — 마지막 FINALIZED 세션 보관
    @Volatile
    private var lastCompletedSession: CallSession? = null

    // v3.20: self-suppression 방지 — 방금 finalize된 세션 ID 보관
    @Volatile
    private var lastFinalizedSessionId: String? = null
    @Volatile
    private var lastFinalizedAt: Long = 0L

    private val lock = Any()

    // 묶음 FINALIZED 후 낱개 재진입 차단용 suppression list
    // key: "정규화가게명|가격", value: SuppressionEntry(timestamp, registeredBySessionId)
    data class SuppressionEntry(val timestamp: Long, val registeredBySessionId: String)
    private val suppressionList = mutableMapOf<String, SuppressionEntry>()
    private val SUPPRESSION_TTL_MS = 30_000L  // 30초간 차단

    companion object {
        /** storeName 정규화: trim + 연속 공백 제거 + lowercase */
        fun normalizeStoreName(s: String?): String =
            (s ?: "UNKNOWN").trim().replace(Regex("\\s+"), " ").lowercase()

        /** suppressionKey 생성: 정규화된 "가게명|금액" */
        fun buildSuppressionKey(storeName: String?, price: Int): String =
            "${normalizeStoreName(storeName)}|$price"
    }

    /**
     * TTS 큐잉 전에 호출하여 suppression 여부만 판정.
     * onEventReceived()와 달리 세션 생성 사이드이펙트 없음.
     */
    fun isSuppressed(storeName: String?, price: Int, currentSessionId: String? = null): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        suppressionList.entries.removeAll { (now - it.value.timestamp) > SUPPRESSION_TTL_MS }

        val key = buildSuppressionKey(storeName, price)
        val entry = suppressionList[key] ?: return false

        // 자기 자신의 세션이 등록한 suppression은 차단하지 않음
        if (currentSessionId != null && entry.registeredBySessionId == currentSessionId) {
            Log.d("SessionManager",
                "isSuppressed=false (self-session skip): key=$key, sessionId=$currentSessionId")
            return false
        }

        val elapsedSec = (now - entry.timestamp) / 1000.0
        Log.w("SessionManager",
            "isSuppressed=true: key=$key, elapsed=${"%.1f".format(elapsedSec)}s, " +
            "registeredBy=${entry.registeredBySessionId}, current=$currentSessionId")
        return true
    }

    /**
     * 새 이벤트 수신 시 호출.
     * - 활성 세션 없거나 expired → 새 세션 생성
     * - 활성 세션 있고 같은 eventId → attach (update)
     * - 활성 세션 있지만 다른 eventId → 기존 세션 EXPIRED 처리 후 새로 시작
     */
    fun onEventReceived(
        platform: String,
        storeName: String?,
        price: Int,
        trigger: String
    ): CallSession? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val eventId = EventIdGenerator.generate(storeName, price, now)

        // 만료된 suppression 항목 정리
        suppressionList.entries.removeAll { (now - it.value.timestamp) > SUPPRESSION_TTL_MS }

        // 묶음 suppression 체크: 정규화된 키로 비교
        val suppressionKey = buildSuppressionKey(storeName, price)
        val entry = suppressionList[suppressionKey]
        if (entry != null) {
            // self-suppression 방지: 방금 finalize된 세션이 등록한 것이면 통과
            val recentFinalizedId = if ((now - lastFinalizedAt) < 5000) lastFinalizedSessionId else null
            if (recentFinalizedId != null && entry.registeredBySessionId == recentFinalizedId) {
                Log.d("SessionManager",
                    "onEventReceived: self-suppression skip (key=$suppressionKey, sessionId=$recentFinalizedId)")
            } else {
                val elapsedSec = (now - entry.timestamp) / 1000.0
                Log.w("SessionManager",
                    "SuppressedByBundle: storeName=$storeName, price=$price, " +
                    "key=$suppressionKey, elapsed=${"%.1f".format(elapsedSec)}s, " +
                    "eventId=${eventId.take(8)}")
                transitionLog.record(StateTransition(
                    sessionId = "SUPPRESSED",
                    eventId = eventId,
                    fromState = "IDLE",
                    toState = "SUPPRESSED",
                    trigger = "SuppressedByBundle:storeName=$storeName|price=$price|elapsed=${"%.1f".format(elapsedSec)}s",
                    timestamp = now
                ))
                return null
            }
        }

        // 기존 세션 expired 처리
        activeSession?.let { current ->
            if (current.isExpired(now)) {
                Log.d("SessionManager", "Active session expired: ${current.eventId.take(8)}")
                endSession(current, SessionState.EXPIRED, "timeout")
            }
        }

        val current = activeSession

        return when {
            // 1) 활성 세션 없음 → 새로 시작
            current == null || current.state != SessionState.COLLECTING -> {
                createSession(platform, eventId, storeName, price, trigger, now)
            }

            // 2) 같은 eventId → attach (update만)
            current.eventId == eventId -> {
                attachToSession(current, storeName, price, trigger, now)
            }

            // 3) 다른 eventId → 기존 세션 강제 종료 후 새로 시작
            else -> {
                Log.w("SessionManager",
                    "New event while active: ${current.eventId.take(8)} → $eventId")
                endSession(current, SessionState.EXPIRED, "interrupted_by_new")
                createSession(platform, eventId, storeName, price, trigger, now)
            }
        }
    }

    private fun createSession(
        platform: String, eventId: String, storeName: String?,
        price: Int, trigger: String, now: Long
    ): CallSession {
        // v3.20: 이전 세션과의 대기 시간 계산 (0~30분 이내만 유효)
        val prev = lastCompletedSession
        if (prev?.completedAt != null) {
            val waitMs = now - prev.completedAt!!
            if (waitMs in 0..(30 * 60 * 1000L)) {
                prev.nextCallWaitMs = waitMs
                Log.d("SessionManager", "nextCallWaitMs=${waitMs}ms (prev=${prev.eventId.take(8)})")
            }
        }

        val session = CallSession(
            sessionId = UUID.randomUUID().toString(),
            eventId = eventId,
            platform = platform,
            state = SessionState.COLLECTING,
            startedAt = now,
            price = price,
            storeName = storeName
        )
        activeSession = session

        transitionLog.record(StateTransition(
            sessionId = session.sessionId,
            eventId = eventId,
            fromState = "IDLE",
            toState = "COLLECTING",
            trigger = trigger,
            timestamp = now
        ))

        return session
    }

    private fun attachToSession(
        session: CallSession, storeName: String?, price: Int,
        trigger: String, now: Long
    ): CallSession {
        // 기존 세션에 정보 업데이트 (null이 아닌 값만)
        if (storeName != null && session.storeName == null) {
            session.storeName = storeName
        }
        if (price > 0 && session.price == 0) {
            session.price = price
        }

        transitionLog.record(StateTransition(
            sessionId = session.sessionId,
            eventId = session.eventId,
            fromState = "COLLECTING",
            toState = "COLLECTING",  // 자기 참조 = attach
            trigger = "attach:$trigger",
            timestamp = now
        ))

        return session
    }

    /**
     * 세션 정상 종료 (FINALIZED). 여기서만 카운트 증가.
     */
    fun finalizeActiveSession(trigger: String): CallSession? = synchronized(lock) {
        val current = activeSession ?: return null
        if (current.state != SessionState.COLLECTING) return null

        return endSession(current, SessionState.FINALIZED, trigger)
    }

    private fun endSession(
        session: CallSession, newState: SessionState, trigger: String
    ): CallSession {
        val now = System.currentTimeMillis()
        session.state = newState
        session.endedAt = now

        // v3.20: FINALIZED 시 completedAt 기록 + lastCompletedSession 보관
        if (newState == SessionState.FINALIZED) {
            session.completedAt = now
            lastCompletedSession = session
            lastFinalizedSessionId = session.sessionId
            lastFinalizedAt = now
        }

        transitionLog.record(StateTransition(
            sessionId = session.sessionId,
            eventId = session.eventId,
            fromState = "COLLECTING",
            toState = newState.name,
            trigger = trigger,
            timestamp = now
        ))

        if (activeSession?.sessionId == session.sessionId) {
            activeSession = null
        }

        return session
    }

    /**
     * 묶음 FINALIZED 후 개별 아이템을 suppression list에 등록.
     * 30초간 동일 storeName|price 조합의 세션 생성을 차단한다.
     */
    fun registerBundleSuppression(keys: List<String>, registeredBySessionId: String = "") = synchronized(lock) {
        val now = System.currentTimeMillis()
        for (key in keys) {
            // 등록 시에도 정규화 적용 (BaeminBundleSession에서 온 키 보정)
            val parts = key.split("|", limit = 2)
            val normalizedKey = if (parts.size == 2) {
                "${normalizeStoreName(parts[0])}|${parts[1]}"
            } else {
                key
            }
            suppressionList[normalizedKey] = SuppressionEntry(now, registeredBySessionId)
            Log.d("SessionManager", "Suppression registered: $normalizedKey (30s TTL, session=$registeredBySessionId)")
        }
    }

    fun getActiveSession(): CallSession? = activeSession

    /**
     * v3.20: activeSession이 있으면 그 ID, 없으면 5초 이내 finalize된 세션 ID.
     * self-suppression 방지용: finalize 직후 activeSession=null 상태에서도 세션 ID 추적.
     */
    fun getCurrentOrLastSessionId(): String? {
        activeSession?.sessionId?.let { return it }
        val elapsed = System.currentTimeMillis() - lastFinalizedAt
        return if (elapsed < 5000) lastFinalizedSessionId else null
    }

    /** v3.20: 마지막 FINALIZED 세션 (연결성 분석용) */
    fun getLastCompletedSession(): CallSession? = lastCompletedSession
}
