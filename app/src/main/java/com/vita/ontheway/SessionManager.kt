package com.vita.ontheway

import android.util.Log
import java.util.UUID

class SessionManager(private val transitionLog: StateTransitionLog) {

    @Volatile
    private var activeSession: CallSession? = null

    private val lock = Any()

    // 묶음 FINALIZED 후 낱개 재진입 차단용 suppression list
    // key: "가게명|가격", value: 등록 timestamp
    private val suppressionList = mutableMapOf<String, Long>()
    private val SUPPRESSION_TTL_MS = 30_000L  // 30초간 차단

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
        suppressionList.entries.removeAll { (now - it.value) > SUPPRESSION_TTL_MS }

        // 묶음 suppression 체크: 묶음 FINALIZED 후 낱개로 재진입 차단
        val suppressionKey = "${storeName ?: "UNKNOWN"}|$price"
        val suppressedAt = suppressionList[suppressionKey]
        if (suppressedAt != null) {
            val elapsedSec = (now - suppressedAt) / 1000.0
            Log.w("SessionManager",
                "SuppressedByBundle: storeName=$storeName, price=$price, " +
                "bundleFinalizedAt=$suppressedAt, elapsed=${"%.1f".format(elapsedSec)}s, " +
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
    fun registerBundleSuppression(keys: List<String>) = synchronized(lock) {
        val now = System.currentTimeMillis()
        for (key in keys) {
            suppressionList[key] = now
            Log.d("SessionManager", "Suppression registered: $key (30s TTL)")
        }
    }

    fun getActiveSession(): CallSession? = activeSession
}
